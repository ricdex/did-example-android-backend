package com.did.wallet.util;

import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Firma y construye JWTs usando ES256K (ECDSA secp256k1 + SHA-256).
 *
 * El algoritmo ES256K está definido en RFC 8812 y es el estándar para
 * Verifiable Credentials y DIDs con claves secp256k1.
 *
 * La firma resultante es el formato compacto R‖S (64 bytes), como exige JWT.
 */
public final class JWTUtil {

    private JWTUtil() {}

    /**
     * Construye y firma un JWT compacto: base64url(header).base64url(payload).base64url(sig)
     *
     * @param headerJson  JSON del header (e.g. {"alg":"ES256K","typ":"JWT","kid":"..."})
     * @param payloadJson JSON del payload
     * @param privKeyBytes clave privada secp256k1 de 32 bytes
     */
    public static String sign(String headerJson, String payloadJson, byte[] privKeyBytes) throws Exception {
        String encodedHeader  = Base64Url.encode(headerJson.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = Base64Url.encode(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput   = encodedHeader + "." + encodedPayload;

        byte[] sig = signES256K(signingInput.getBytes(StandardCharsets.UTF_8), privKeyBytes);
        return signingInput + "." + Base64Url.encode(sig);
    }

    // ─── Firma ES256K ────────────────────────────────────────────────────────

    /**
     * Firma el mensaje con ECDSA/secp256k1/SHA-256 usando RFC6979 (k determinístico).
     * Devuelve 64 bytes: R (32) ‖ S (32) en formato JWT compacto.
     */
    private static byte[] signES256K(byte[] message, byte[] privKeyBytes) throws Exception {
        // Hash SHA-256 del input de firma
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] hash = sha256.digest(message);

        // Parámetros de la curva secp256k1
        ECNamedCurveParameterSpec spec = ECNamedCurveTable.getParameterSpec("secp256k1");
        ECDomainParameters domain = new ECDomainParameters(
            spec.getCurve(), spec.getG(), spec.getN(), spec.getH());

        BigInteger d = new BigInteger(1, privKeyBytes);
        ECPrivateKeyParameters privParams = new ECPrivateKeyParameters(d, domain);

        // HMacDSAKCalculator implementa RFC6979 (k determinístico) — más seguro que k aleatorio
        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(
            new org.bouncycastle.crypto.digests.SHA256Digest()));
        signer.init(true, privParams);

        BigInteger[] rs = signer.generateSignature(hash);
        return encodeCompact(rs[0], rs[1]);
    }

    /** Codifica R y S como 32 bytes cada uno, concatenados (formato JWT). */
    private static byte[] encodeCompact(BigInteger r, BigInteger s) {
        byte[] rb = to32Bytes(r);
        byte[] sb = to32Bytes(s);
        byte[] out = new byte[64];
        System.arraycopy(rb, 0, out, 0, 32);
        System.arraycopy(sb, 0, out, 32, 32);
        return out;
    }

    private static byte[] to32Bytes(BigInteger n) {
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
}
