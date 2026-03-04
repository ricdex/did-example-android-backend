package com.did.wallet.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;
import android.util.Base64;
import android.util.Log;

import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Security;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Gestiona el par de claves secp256k1 con fallback de hardware seguro.
 *
 * Jerarquía de seguridad para la clave AES de wrapping:
 *
 *   1. StrongBox (API 28+)  — chip de seguridad dedicado (Titan M, Pixel, etc.)
 *      Mayor aislamiento: el chip opera independientemente del SoC principal.
 *
 *   2. TEE (Trusted Execution Environment) — presente en prácticamente todos
 *      los Android modernos. Hardware-backed pero comparte el SoC.
 *
 *   3. Software Keystore — último recurso. Sin respaldo hardware.
 *      Solo ocurre en emuladores o dispositivos muy antiguos/raros.
 *
 * La clave privada secp256k1 se cifra con AES-256/GCM usando la clave del nivel
 * más alto disponible. El nivel real se detecta y registra en prefs.
 */
public class KeyManager {

    private static final String TAG = "KeyManager";

    private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
    private static final String KEYSTORE_ALIAS    = "did_wallet_wrap_key";

    private static final String PREFS_NAME     = "did_wallet_secure";
    private static final String PREF_ENC_PRIV  = "enc_private_key";
    private static final String PREF_PUB_HEX   = "public_key_hex";
    private static final String PREF_IV        = "aes_gcm_iv";
    private static final String PREF_SEC_LEVEL = "security_level";  // informativo

    private static final int GCM_TAG_BITS = 128;

    static {
        Security.removeProvider("BC");
        Security.addProvider(new BouncyCastleProvider());
    }

    private final Context context;

    public KeyManager(Context context) {
        this.context = context.getApplicationContext();
    }

    // ─── API pública ──────────────────────────────────────────────────────────

    /** Genera y persiste un nuevo par secp256k1 con la protección hardware disponible. */
    public KeyPairResult generateAndStore() throws Exception {
        SecretKey wrapKey = getOrCreateWrapKey();

        // Generar par secp256k1
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("ECDSA", "BC");
        kpg.initialize(spec, new SecureRandom());
        KeyPair pair = kpg.generateKeyPair();

        ECPrivateKey priv = (ECPrivateKey) pair.getPrivate();
        ECPublicKey  pub  = (ECPublicKey)  pair.getPublic();

        byte[] privBytes = to32Bytes(priv.getS());
        byte[] pubBytes  = compressedPublicKey(pub);

        // Cifrar clave privada con AES/GCM (wrapKey opera dentro del hardware)
        Cipher enc = Cipher.getInstance("AES/GCM/NoPadding");
        enc.init(Cipher.ENCRYPT_MODE, wrapKey);
        byte[] iv      = enc.getIV();
        byte[] encPriv = enc.doFinal(privBytes);

        prefs().edit()
            .putString(PREF_ENC_PRIV,  b64(encPriv))
            .putString(PREF_PUB_HEX,   hex(pubBytes))
            .putString(PREF_IV,        b64(iv))
            .apply();

        Log.i(TAG, "Par secp256k1 generado. Nivel de seguridad: " + getSecurityLevel());
        return new KeyPairResult(privBytes, pubBytes);
    }

    /** Descifra y devuelve los bytes de la clave privada (32 bytes, solo en RAM). */
    public byte[] loadPrivateKey() throws Exception {
        SharedPreferences p = prefs();
        String encB64 = p.getString(PREF_ENC_PRIV, null);
        String ivB64  = p.getString(PREF_IV, null);

        if (encB64 == null || ivB64 == null) {
            throw new IllegalStateException("No hay par de claves almacenado.");
        }

        byte[]    encPriv = b64d(encB64);
        byte[]    iv      = b64d(ivB64);
        SecretKey wrapKey = getWrapKey();

        Cipher dec = Cipher.getInstance("AES/GCM/NoPadding");
        dec.init(Cipher.DECRYPT_MODE, wrapKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return dec.doFinal(encPriv);
    }

    public String getPublicKeyHex()  { return prefs().getString(PREF_PUB_HEX, null); }
    public boolean hasKeyPair()      { return prefs().contains(PREF_ENC_PRIV); }

    /**
     * Devuelve el nivel de seguridad real de la clave de wrapping AES.
     * Consultar después de generateAndStore().
     */
    public SecurityLevel getSecurityLevel() {
        String stored = prefs().getString(PREF_SEC_LEVEL, null);
        if (stored != null) return SecurityLevel.valueOf(stored);

        // Si no hay registro, intentar detectarlo de la clave existente
        try {
            SecretKey key = getWrapKey();
            return detectLevel(key);
        } catch (Exception e) {
            return SecurityLevel.UNKNOWN;
        }
    }

    // ─── Android Keystore con fallback ───────────────────────────────────────

    private SecretKey getOrCreateWrapKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
        ks.load(null);

        if (ks.containsAlias(KEYSTORE_ALIAS)) {
            return getWrapKey();
        }

        // Intentar StrongBox (API 28+) → caer a TEE si no disponible
        SecretKey key;
        SecurityLevel level;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                key   = createAesKey(true);   // StrongBox
                level = SecurityLevel.STRONGBOX;
                Log.i(TAG, "Usando StrongBox para clave AES de wrapping");
            } catch (StrongBoxUnavailableException e) {
                Log.w(TAG, "StrongBox no disponible en este dispositivo, usando TEE");
                key   = createAesKey(false);  // TEE
                level = detectLevel(key);
            }
        } else {
            key   = createAesKey(false);
            level = detectLevel(key);
        }

        // Registrar nivel real (informativo, no afecta seguridad)
        prefs().edit().putString(PREF_SEC_LEVEL, level.name()).apply();
        Log.i(TAG, "Clave AES creada. Nivel: " + level);
        return key;
    }

    private SecretKey createAesKey(boolean requireStrongBox) throws Exception {
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false); // cambiar a true para requerir biometría

        if (requireStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setIsStrongBoxBacked(true);
        }

        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        kg.init(builder.build());
        return kg.generateKey();
    }

    private SecretKey getWrapKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(KEYSTORE_ALIAS, null);
        if (entry == null) throw new IllegalStateException("Clave AES no encontrada en Keystore");
        return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
    }

    /**
     * Detecta el nivel de seguridad real de una clave del Keystore.
     *
     * API 31+: distingue STRONGBOX / TEE / SOFTWARE con exactitud.
     * API 26-30: distingue hardware (TEE o StrongBox) vs software.
     */
    private SecurityLevel detectLevel(SecretKey key) {
        try {
            KeyFactory factory = KeyFactory.getInstance(key.getAlgorithm(), KEYSTORE_PROVIDER);
            KeyInfo info = factory.getKeySpec(key, KeyInfo.class);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // API 31+: nivel explícito
                return switch (info.getSecurityLevel()) {
                    case KeyProperties.SECURITY_LEVEL_STRONGBOX          -> SecurityLevel.STRONGBOX;
                    case KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> SecurityLevel.TEE;
                    default                                               -> SecurityLevel.SOFTWARE;
                };
            } else {
                // API 26-30: solo sabe si está en hardware o no
                return info.isInsideSecureHardware() ? SecurityLevel.TEE : SecurityLevel.SOFTWARE;
            }
        } catch (Exception e) {
            Log.w(TAG, "No se pudo detectar nivel de seguridad: " + e.getMessage());
            return SecurityLevel.UNKNOWN;
        }
    }

    // ─── Utilidades ──────────────────────────────────────────────────────────

    private static byte[] compressedPublicKey(ECPublicKey pub) {
        byte[] x = to32Bytes(pub.getW().getAffineX());
        byte[] y = to32Bytes(pub.getW().getAffineY());
        byte[] out = new byte[33];
        out[0] = (byte) ((y[31] & 0x01) == 0 ? 0x02 : 0x03);
        System.arraycopy(x, 0, out, 1, 32);
        return out;
    }

    public static byte[] to32Bytes(BigInteger n) {
        byte[] raw = n.toByteArray();
        if (raw.length == 32) return raw;
        byte[] result = new byte[32];
        if (raw.length > 32) {
            System.arraycopy(raw, raw.length - 32, result, 0, 32);
        } else {
            System.arraycopy(raw, 0, result, 32 - raw.length, raw.length);
        }
        return result;
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format("%02x", v));
        return sb.toString();
    }

    public static byte[] unhex(String h) {
        int len = h.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            out[i / 2] = (byte) ((Character.digit(h.charAt(i), 16) << 4)
                              +   Character.digit(h.charAt(i + 1), 16));
        return out;
    }

    private static String b64(byte[] b)   { return Base64.encodeToString(b, Base64.NO_WRAP); }
    private static byte[] b64d(String s)  { return Base64.decode(s, Base64.NO_WRAP); }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ─── Tipos ────────────────────────────────────────────────────────────────

    public enum SecurityLevel {
        /** Chip de seguridad dedicado (Titan M, etc.). Máxima protección. */
        STRONGBOX,
        /** Trusted Execution Environment integrado en el SoC. Hardware-backed. */
        TEE,
        /** Sin respaldo hardware. Solo emuladores o dispositivos muy antiguos. */
        SOFTWARE,
        UNKNOWN
    }

    public static final class KeyPairResult {
        public final byte[] privateKeyBytes;  // 32 bytes — solo usar en RAM
        public final byte[] publicKeyBytes;   // 33 bytes comprimidos
        public KeyPairResult(byte[] priv, byte[] pub) {
            this.privateKeyBytes = priv;
            this.publicKeyBytes  = pub;
        }
    }
}
