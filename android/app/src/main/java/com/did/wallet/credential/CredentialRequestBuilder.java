package com.did.wallet.credential;

import com.did.wallet.did.DIDManager;
import com.did.wallet.security.KeyManager;
import com.did.wallet.util.JWTUtil;

import org.json.JSONObject;

/**
 * Construye una solicitud de credencial verificable (Credential Request).
 *
 * Sigue un flujo inspirado en OpenID4VCI (draft):
 *   - El holder genera un JWT firmado con su clave como "prueba de posesión".
 *   - El emisor verifica la prueba y emite una VC.
 *
 * Formato del JWT de prueba (proof JWT):
 *   Header: { alg: ES256K, typ: "openid4vci-proof+jwt", kid: <holderKeyId> }
 *   Payload: { iss, aud, iat, exp, nonce, credential_type, subject_claims? }
 */
public class CredentialRequestBuilder {

    private final KeyManager  keyManager;
    private final DIDManager  didManager;

    private String     holderDid;
    private String     issuerUrl;
    private String     nonce;            // obtenido del emisor antes de firmar
    private String     credentialType = "VerifiableCredential";
    private JSONObject subjectClaims;

    public CredentialRequestBuilder(KeyManager keyManager, DIDManager didManager) {
        this.keyManager = keyManager;
        this.didManager = didManager;
    }

    public CredentialRequestBuilder holderDid(String did) {
        this.holderDid = did;
        return this;
    }

    public CredentialRequestBuilder issuerUrl(String url) {
        this.issuerUrl = url;
        return this;
    }

    /** Nonce fresco obtenido del emisor (GET /credentials/nonce). Previene ataques de replay. */
    public CredentialRequestBuilder nonce(String nonce) {
        this.nonce = nonce;
        return this;
    }

    public CredentialRequestBuilder credentialType(String type) {
        this.credentialType = type;
        return this;
    }

    /** Claims que el holder quiere que figuren en la credencial (nombre, edad, etc.). */
    public CredentialRequestBuilder subjectClaims(JSONObject claims) {
        this.subjectClaims = claims;
        return this;
    }

    /**
     * Construye y firma el Proof JWT.
     * Llamar en un hilo de fondo (usa criptografía).
     *
     * @return JWT compacto listo para enviar al emisor.
     */
    public String build() throws Exception {
        validate();

        String kid = didManager.getKeyId(holderDid);
        long   now = System.currentTimeMillis() / 1000;

        // ── Header ──────────────────────────────────────────────────────────
        JSONObject header = new JSONObject();
        header.put("alg", "ES256K");
        header.put("typ", "openid4vci-proof+jwt");
        header.put("kid", kid);

        // ── Payload ─────────────────────────────────────────────────────────
        JSONObject payload = new JSONObject();
        payload.put("iss", holderDid);
        payload.put("aud", issuerUrl);
        payload.put("iat", now);
        payload.put("exp", now + 300);           // válido 5 minutos
        payload.put("nonce", nonce);
        payload.put("credential_type", credentialType);
        if (subjectClaims != null) {
            payload.put("subject_claims", subjectClaims);
        }

        byte[] privKey = keyManager.loadPrivateKey();
        return JWTUtil.sign(header.toString(), payload.toString(), privKey);
    }

    private void validate() {
        if (holderDid  == null) throw new IllegalStateException("holderDid es obligatorio");
        if (issuerUrl  == null) throw new IllegalStateException("issuerUrl es obligatorio");
        if (nonce      == null) throw new IllegalStateException("nonce es obligatorio (solicítalo al emisor)");
    }
}
