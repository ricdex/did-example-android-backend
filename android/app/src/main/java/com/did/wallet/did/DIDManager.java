package com.did.wallet.did;

import com.did.wallet.security.KeyManager;
import com.did.wallet.util.Base58;

/**
 * Gestiona DIDs del método did:key con clave secp256k1.
 *
 * Especificación: https://w3c-ccg.github.io/did-method-key/
 *
 * Formato:
 *   did:key:z<base58btc( varint(0xe701) || compressedPubKey )>
 *
 * El prefijo varint 0xe701 corresponde al multicodec 0xe7 (secp256k1-pub).
 * La 'z' inicial es el prefijo multibase para base58btc.
 */
public class DIDManager {

    // Multicodec para secp256k1-pub: 0xe7 en varint = [0xe7, 0x01]
    private static final byte[] SECP256K1_MULTICODEC = {(byte) 0xe7, (byte) 0x01};

    private final KeyManager keyManager;

    public DIDManager(KeyManager keyManager) {
        this.keyManager = keyManager;
    }

    // ─── DID ─────────────────────────────────────────────────────────────────

    /**
     * Construye el DID a partir de la clave pública almacenada.
     * Ejemplo: did:key:zQ3shvL9SmjJRUMBiAeVNQK3j8JvVRar35WNmB7EkaBuUKuXv
     */
    public String getDID() {
        String hex = keyManager.getPublicKeyHex();
        if (hex == null) throw new IllegalStateException("No hay clave pública. Genere el par primero.");

        byte[] pubKey = KeyManager.unhex(hex);
        byte[] multikey = concat(SECP256K1_MULTICODEC, pubKey);
        String encoded = "z" + Base58.encode(multikey);
        return "did:key:" + encoded;
    }

    /**
     * Identificador del método de verificación (kid).
     * Para did:key el kid es: did#<fragment> donde fragment == encoded key.
     */
    public String getKeyId(String did) {
        String fragment = did.substring("did:key:".length());
        return did + "#" + fragment;
    }

    /**
     * Genera un DID Document mínimo compatible con W3C DID Core 1.0.
     */
    public String buildDIDDocument(String did) {
        String kid = getKeyId(did);
        String pubHex = keyManager.getPublicKeyHex();

        return "{\n"
            + "  \"@context\": [\n"
            + "    \"https://www.w3.org/ns/did/v1\",\n"
            + "    \"https://w3id.org/security/suites/secp256k1-2019/v1\"\n"
            + "  ],\n"
            + "  \"id\": \"" + did + "\",\n"
            + "  \"verificationMethod\": [{\n"
            + "    \"id\": \"" + kid + "\",\n"
            + "    \"type\": \"EcdsaSecp256k1VerificationKey2019\",\n"
            + "    \"controller\": \"" + did + "\",\n"
            + "    \"publicKeyHex\": \"" + pubHex + "\"\n"
            + "  }],\n"
            + "  \"authentication\": [\"" + kid + "\"],\n"
            + "  \"assertionMethod\": [\"" + kid + "\"]\n"
            + "}";
    }

    // ─── Utilidad ────────────────────────────────────────────────────────────

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
