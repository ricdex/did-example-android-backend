package com.did.issuer.service;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.util.encoders.Hex;

import java.io.*;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Base64;

/**
 * Utilidades criptográficas para manejo de claves secp256k1 y DID:key.
 */
public final class DIDKeyUtil {

    // Prefijo multicodec para secp256k1-pub (varint de 0xe7)
    private static final byte[] SECP256K1_MULTICODEC = {(byte) 0xe7, (byte) 0x01};

    private DIDKeyUtil() {}

    // ─── Generación de claves ─────────────────────────────────────────────────

    public static KeyPair generateSecp256k1KeyPair() throws Exception {
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("ECDSA", "BC");
        kpg.initialize(spec, new SecureRandom());
        return kpg.generateKeyPair();
    }

    // ─── Serialización PEM ────────────────────────────────────────────────────

    public static void saveKeyPair(KeyPair keyPair, Path file) throws IOException {
        try (JcaPEMWriter writer = new JcaPEMWriter(new FileWriter(file.toFile()))) {
            writer.writeObject(keyPair.getPrivate());
        }
    }

    public static KeyPair loadKeyPair(Path file) throws Exception {
        try (PEMParser parser = new PEMParser(new FileReader(file.toFile()))) {
            return parseKeyPair(parser);
        }
    }

    /**
     * Carga un KeyPair desde bytes PEM (usado cuando la clave viene de Key Vault
     * inyectada como variable de entorno ISSUER_KEY_PEM en base64).
     */
    public static KeyPair loadKeyPairFromBytes(byte[] pemBytes) throws Exception {
        try (PEMParser parser = new PEMParser(
                new InputStreamReader(new ByteArrayInputStream(pemBytes),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            return parseKeyPair(parser);
        }
    }

    private static KeyPair parseKeyPair(PEMParser parser) throws Exception {
        Object obj = parser.readObject();
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
        if (obj instanceof PEMKeyPair pemKeyPair) {
            return converter.getKeyPair(pemKeyPair);
        }
        if (obj instanceof PrivateKeyInfo pki) {
            PrivateKey priv = converter.getPrivateKey(pki);
            ECPrivateKey ecPriv = (ECPrivateKey) priv;
            ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
            ECPoint Q = spec.getG().multiply(ecPriv.getS());
            org.bouncycastle.jce.spec.ECPublicKeySpec pubSpec =
                new org.bouncycastle.jce.spec.ECPublicKeySpec(Q, spec);
            PublicKey pub = KeyFactory.getInstance("ECDSA", "BC").generatePublic(pubSpec);
            return new KeyPair(pub, priv);
        }
        throw new IllegalStateException("Formato PEM no reconocido");
    }

    // ─── Clave pública comprimida ────────────────────────────────────────────

    public static String compressedPublicKeyHex(KeyPair keyPair) {
        ECPublicKey pub = (ECPublicKey) keyPair.getPublic();
        byte[] x = to32Bytes(pub.getW().getAffineX());
        byte[] y = to32Bytes(pub.getW().getAffineY());
        byte[] compressed = new byte[33];
        compressed[0] = (byte) ((y[31] & 0x01) == 0 ? 0x02 : 0x03);
        System.arraycopy(x, 0, compressed, 1, 32);
        return Hex.toHexString(compressed);
    }

    // ─── DID:key ─────────────────────────────────────────────────────────────

    public static String buildDIDKey(String compressedPubKeyHex) {
        byte[] pubBytes   = Hex.decode(compressedPubKeyHex);
        byte[] multikey   = concat(SECP256K1_MULTICODEC, pubBytes);
        String encoded    = "z" + Base58.encode(multikey);
        return "did:key:" + encoded;
    }

    /**
     * Extrae la clave pública comprimida (33 bytes) de un did:key secp256k1.
     */
    public static byte[] publicKeyFromDIDKey(String did) {
        if (!did.startsWith("did:key:z")) throw new IllegalArgumentException("DID inválido: " + did);
        String encoded  = did.substring("did:key:z".length());
        byte[] multikey = Base58.decode(encoded);

        // Verificar y eliminar prefijo multicodec (2 bytes para secp256k1)
        if (multikey[0] != SECP256K1_MULTICODEC[0] || multikey[1] != SECP256K1_MULTICODEC[1]) {
            throw new IllegalArgumentException("DID no es secp256k1: " + did);
        }
        byte[] pubKey = new byte[33];
        System.arraycopy(multikey, 2, pubKey, 0, 33);
        return pubKey;
    }

    // ─── Firma ES256K ─────────────────────────────────────────────────────────

    /**
     * Firma datos con ECDSA secp256k1 + SHA-256 (ES256K).
     * Usa RFC6979 (k determinístico) para mayor seguridad.
     *
     * @return 64 bytes: R (32) || S (32)
     */
    public static byte[] signES256K(byte[] data, byte[] privKeyBytes) throws Exception {
        byte[] hash = sha256(data);

        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
        ECDomainParameters domain = new ECDomainParameters(
            spec.getCurve(), spec.getG(), spec.getN(), spec.getH());

        BigInteger d = new BigInteger(1, privKeyBytes);
        ECPrivateKeyParameters params = new ECPrivateKeyParameters(d, domain);

        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        signer.init(true, params);
        BigInteger[] rs = signer.generateSignature(hash);

        return encodeCompact(rs[0], rs[1]);
    }

    /**
     * Verifica una firma ES256K.
     *
     * @param data          datos originales (signing input del JWT: "header.payload")
     * @param signatureRS   64 bytes: R || S
     * @param pubKeyBytes   clave pública comprimida de 33 bytes
     */
    public static boolean verifyES256K(byte[] data, byte[] signatureRS, byte[] pubKeyBytes) throws Exception {
        byte[] hash = sha256(data);

        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
        ECDomainParameters domain = new ECDomainParameters(
            spec.getCurve(), spec.getG(), spec.getN(), spec.getH());

        ECPoint point = spec.getCurve().decodePoint(pubKeyBytes);
        ECPublicKeyParameters params = new ECPublicKeyParameters(point, domain);

        BigInteger r = new BigInteger(1, signatureRS, 0, 32);
        BigInteger s = new BigInteger(1, signatureRS, 32, 32);

        ECDSASigner verifier = new ECDSASigner();
        verifier.init(false, params);
        return verifier.verifySignature(hash, r, s);
    }

    // ─── JWT helpers ──────────────────────────────────────────────────────────

    public static String buildJWT(String headerJson, String payloadJson, byte[] privKeyBytes) throws Exception {
        String h   = b64url(headerJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String p   = b64url(payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String input = h + "." + p;
        byte[] sig   = signES256K(input.getBytes(java.nio.charset.StandardCharsets.UTF_8), privKeyBytes);
        return input + "." + b64url(sig);
    }

    // ─── Utilidades ──────────────────────────────────────────────────────────

    public static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    public static String b64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    public static byte[] b64urlDecode(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    private static byte[] encodeCompact(BigInteger r, BigInteger s) {
        byte[] rb = to32Bytes(r);
        byte[] sb = to32Bytes(s);
        byte[] out = new byte[64];
        System.arraycopy(rb, 0, out, 0, 32);
        System.arraycopy(sb, 0, out, 32, 32);
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

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    // ─── Base58 interno ──────────────────────────────────────────────────────

    private static final class Base58 {
        private static final char[] ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
        private static final int[] INDEXES = new int[128];
        static { java.util.Arrays.fill(INDEXES, -1); for (int i = 0; i < ALPHABET.length; i++) INDEXES[ALPHABET[i]] = i; }

        static String encode(byte[] input) {
            if (input.length == 0) return "";
            int zeros = 0;
            while (zeros < input.length && input[zeros] == 0) zeros++;
            byte[] copy = java.util.Arrays.copyOf(input, input.length);
            char[] output = new char[copy.length * 2];
            int outputStart = output.length;
            for (int s = zeros; s < copy.length; ) {
                output[--outputStart] = ALPHABET[divmod(copy, s, 256, 58)];
                if (copy[s] == 0) s++;
            }
            while (outputStart < output.length && output[outputStart] == ALPHABET[0]) outputStart++;
            while (zeros-- > 0) output[--outputStart] = ALPHABET[0];
            return new String(output, outputStart, output.length - outputStart);
        }

        static byte[] decode(String input) {
            if (input.isEmpty()) return new byte[0];
            byte[] in58 = new byte[input.length()];
            for (int i = 0; i < input.length(); i++) { char c = input.charAt(i); int d = c < 128 ? INDEXES[c] : -1; if (d < 0) throw new IllegalArgumentException("Invalid char: " + c); in58[i] = (byte) d; }
            int zeros = 0;
            while (zeros < in58.length && in58[zeros] == 0) zeros++;
            byte[] decoded = new byte[input.length()];
            int outputStart = decoded.length;
            for (int s = zeros; s < in58.length; ) { decoded[--outputStart] = divmod(in58, s, 58, 256); if (in58[s] == 0) s++; }
            while (outputStart < decoded.length && decoded[outputStart] == 0) outputStart++;
            return java.util.Arrays.copyOfRange(decoded, outputStart - zeros, decoded.length);
        }

        private static byte divmod(byte[] num, int first, int base, int div) {
            int rem = 0;
            for (int i = first; i < num.length; i++) { int temp = rem * base + (num[i] & 0xFF); num[i] = (byte)(temp / div); rem = temp % div; }
            return (byte) rem;
        }
    }
}
