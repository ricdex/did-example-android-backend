package com.did.wallet.presentation;

import com.did.wallet.did.DIDManager;
import com.did.wallet.security.KeyManager;
import com.did.wallet.util.JWTUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Construye y firma Verifiable Presentations (VP) en formato JWT.
 *
 * Estándar: W3C Verifiable Credentials Data Model 1.1 + JWT encoding.
 *   https://www.w3.org/TR/vc-data-model/#json-web-token
 *
 * La VP empaqueta una o más VCs y va firmada con la clave privada del holder,
 * demostrando que el presentador es el legítimo titular.
 *
 * Estructura del JWT:
 *   Header  : { alg: ES256K, typ: JWT, kid: <holderKeyId> }
 *   Payload : { jti, iss (holder), aud (verifier), iat, exp, nonce, vp: { ... } }
 */
public class VPBuilder {

    private final KeyManager keyManager;
    private final DIDManager didManager;

    private String   holderDid;
    private String[] vcJwts;
    private String   audience;      // URL o DID del verificador
    private String   nonce;         // suministrado por el verificador

    public VPBuilder(KeyManager keyManager, DIDManager didManager) {
        this.keyManager = keyManager;
        this.didManager = didManager;
    }

    public VPBuilder holderDid(String did) {
        this.holderDid = did;
        return this;
    }

    /** Una o más VCs en formato JWT a incluir en la presentación. */
    public VPBuilder credentials(String... vcJwts) {
        this.vcJwts = vcJwts;
        return this;
    }

    /** DID o URL del verificador al que va dirigida la presentación. */
    public VPBuilder audience(String aud) {
        this.audience = aud;
        return this;
    }

    /** Nonce proporcionado por el verificador para evitar replay attacks. */
    public VPBuilder nonce(String nonce) {
        this.nonce = nonce;
        return this;
    }

    /**
     * Construye y firma la VP.
     * Ejecutar en hilo de fondo.
     *
     * @return JWT de VP compacto (header.payload.signature)
     */
    public String build() throws Exception {
        validate();

        String kid = didManager.getKeyId(holderDid);
        long   now = System.currentTimeMillis() / 1000;

        // ── Header ──────────────────────────────────────────────────────────
        JSONObject header = new JSONObject();
        header.put("alg", "ES256K");
        header.put("typ", "JWT");
        header.put("kid", kid);

        // ── VP object (W3C VP Data Model) ────────────────────────────────────
        JSONObject vp = new JSONObject();
        vp.put("@context", new JSONArray()
            .put("https://www.w3.org/2018/credentials/v1"));
        vp.put("type", new JSONArray()
            .put("VerifiablePresentation"));

        JSONArray vcArray = new JSONArray();
        for (String vc : vcJwts) vcArray.put(vc);
        vp.put("verifiableCredential", vcArray);

        // ── Payload ──────────────────────────────────────────────────────────
        JSONObject payload = new JSONObject();
        payload.put("jti", "urn:uuid:" + UUID.randomUUID());
        payload.put("iss", holderDid);
        if (audience != null) payload.put("aud", audience);
        payload.put("iat", now);
        payload.put("exp", now + 3600);     // válida 1 hora
        if (nonce != null) payload.put("nonce", nonce);
        payload.put("vp", vp);

        byte[] privKey = keyManager.loadPrivateKey();
        return JWTUtil.sign(header.toString(), payload.toString(), privKey);
    }

    private void validate() {
        if (holderDid == null)                    throw new IllegalStateException("holderDid requerido");
        if (vcJwts == null || vcJwts.length == 0) throw new IllegalStateException("Se requiere al menos una VC");
    }
}
