package com.did.issuer.service;

import com.did.issuer.config.IssuerKeyConfig.IssuerKeys;
import com.did.issuer.store.CredentialStore;
import com.did.issuer.store.HolderDIDStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verifica una Verifiable Presentation (VP JWT) enviada por un holder.
 *
 * Validaciones en orden:
 *   1. Estructura JWT válida (3 partes)
 *   2. alg = ES256K
 *   3. VP no expirada
 *   4. Firma ES256K del holder válida
 *   5. DID del holder registrado y activo
 *   6. Para cada VC dentro del VP:
 *      a. alg = ES256K
 *      b. iss = DID del issuer de este backend
 *      c. Firma ES256K del issuer válida
 *      d. sub = DID del holder del VP
 *      e. VC no expirada
 *      f. VC no revocada (consulta al store)
 */
@Service
public class VPVerifierService {

    private static final Logger log = LoggerFactory.getLogger(VPVerifierService.class);

    private final HolderDIDStore  holderDIDStore;
    private final CredentialStore credentialStore;
    private final IssuerKeys      issuerKeys;
    private final ObjectMapper    mapper = new ObjectMapper();

    public VPVerifierService(HolderDIDStore holderDIDStore,
                             CredentialStore credentialStore,
                             IssuerKeys issuerKeys) {
        this.holderDIDStore  = holderDIDStore;
        this.credentialStore = credentialStore;
        this.issuerKeys      = issuerKeys;
    }

    /**
     * Verifica el VP JWT.
     *
     * @param vpJwt JWT compacto de la Verifiable Presentation
     * @return mapa con los resultados de la verificación
     * @throws IllegalArgumentException si la verificación falla (con motivo)
     */
    public Map<String, Object> verify(String vpJwt) {
        try {
            String[] parts = vpJwt.split("\\.");
            if (parts.length != 3) throw new IllegalArgumentException("VP JWT malformado: se esperan 3 partes");

            JsonNode vpHeader  = mapper.readTree(DIDKeyUtil.b64urlDecode(parts[0]));
            JsonNode vpPayload = mapper.readTree(DIDKeyUtil.b64urlDecode(parts[1]));

            // 1. Verificar algoritmo
            String alg = vpHeader.path("alg").asText();
            if (!"ES256K".equals(alg))
                throw new IllegalArgumentException("Algoritmo inválido '" + alg + "': se requiere ES256K");

            // 2. Verificar expiración del VP
            long exp = vpPayload.path("exp").asLong(0);
            if (exp == 0 || System.currentTimeMillis() / 1000 > exp)
                throw new IllegalArgumentException("VP JWT expirado o sin campo exp");

            // 3. Verificar firma del holder
            String holderDid    = vpPayload.path("iss").asText(null);
            if (holderDid == null || holderDid.isBlank())
                throw new IllegalArgumentException("VP JWT no tiene campo iss (holderDid)");

            byte[] holderPubKey = DIDKeyUtil.publicKeyFromDIDKey(holderDid);
            String signingInput = parts[0] + "." + parts[1];
            byte[] sigBytes     = DIDKeyUtil.b64urlDecode(parts[2]);
            boolean holderSigOk = DIDKeyUtil.verifyES256K(
                signingInput.getBytes(StandardCharsets.UTF_8), sigBytes, holderPubKey);
            if (!holderSigOk)
                throw new IllegalArgumentException("Firma ES256K del holder inválida");

            // 4. DID del holder registrado y activo
            holderDIDStore.findByDid(holderDid).ifPresentOrElse(record -> {
                if (!record.isActive())
                    throw new IllegalArgumentException("DID del holder invalidado: " + holderDid);
            }, () -> {
                throw new IllegalArgumentException("DID del holder no registrado: " + holderDid);
            });

            // 5. Verificar cada VC dentro del VP
            JsonNode vcArray = vpPayload.path("vp").path("verifiableCredential");
            if (!vcArray.isArray() || vcArray.isEmpty())
                throw new IllegalArgumentException("VP no contiene verifiableCredential");

            List<Map<String, Object>> credentials = new ArrayList<>();
            for (JsonNode vcNode : vcArray) {
                credentials.add(verifyVC(vcNode.asText(), holderDid));
            }

            log.info("VP verificado correctamente para holder: {}", holderDid);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("valid",       true);
            result.put("holder_did",  holderDid);
            result.put("credentials", credentials);
            return result;

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Error inesperado verificando VP: {}", e.getMessage());
            throw new IllegalArgumentException("Error al verificar VP: " + e.getMessage());
        }
    }

    // ─── Verificación de una VC individual ────────────────────────────────────

    private Map<String, Object> verifyVC(String vcJwt, String expectedHolderDid) throws Exception {
        String[] parts = vcJwt.split("\\.");
        if (parts.length != 3) throw new IllegalArgumentException("VC JWT malformado");

        JsonNode vcHeader  = mapper.readTree(DIDKeyUtil.b64urlDecode(parts[0]));
        JsonNode vcPayload = mapper.readTree(DIDKeyUtil.b64urlDecode(parts[1]));

        // a. Algoritmo
        String alg = vcHeader.path("alg").asText();
        if (!"ES256K".equals(alg))
            throw new IllegalArgumentException("VC: algoritmo inválido '" + alg + "'");

        // b. Emisor = nuestro issuer
        String vcIssuerDid = vcPayload.path("iss").asText(null);
        if (!issuerKeys.did.equals(vcIssuerDid))
            throw new IllegalArgumentException(
                "VC emitida por un issuer desconocido: " + vcIssuerDid);

        // c. Firma del issuer
        byte[] issuerPubKey = DIDKeyUtil.publicKeyFromDIDKey(vcIssuerDid);
        String signingInput = parts[0] + "." + parts[1];
        byte[] sigBytes     = DIDKeyUtil.b64urlDecode(parts[2]);
        boolean issuerSigOk = DIDKeyUtil.verifyES256K(
            signingInput.getBytes(StandardCharsets.UTF_8), sigBytes, issuerPubKey);
        if (!issuerSigOk)
            throw new IllegalArgumentException("VC: firma ES256K del issuer inválida");

        // d. sub = holderDid del VP
        String sub = vcPayload.path("sub").asText(null);
        if (!expectedHolderDid.equals(sub))
            throw new IllegalArgumentException(
                "VC: sub '" + sub + "' no coincide con el holderDid del VP");

        // e. VC no expirada
        long exp = vcPayload.path("exp").asLong(0);
        if (exp == 0 || System.currentTimeMillis() / 1000 > exp)
            throw new IllegalArgumentException("VC expirada o sin campo exp");

        // f. VC no revocada
        String credentialId = vcPayload.path("jti").asText(null);
        boolean revoked = credentialStore.findByCredentialId(credentialId)
            .map(r -> r.isRevoked())
            .orElse(false);
        if (revoked)
            throw new IllegalArgumentException("VC revocada: " + credentialId);

        // Extraer datos del credentialSubject para la respuesta
        JsonNode vcObj   = vcPayload.path("vc");
        JsonNode subject = vcObj.path("credentialSubject");
        String credType  = "VerifiableCredential";
        if (vcObj.path("type").isArray()) {
            for (JsonNode t : vcObj.path("type")) {
                if (!"VerifiableCredential".equals(t.asText())) {
                    credType = t.asText();
                    break;
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("credential_id",   credentialId);
        result.put("credential_type", credType);
        result.put("subject",         mapper.convertValue(subject, Map.class));
        result.put("expires_at",      java.time.Instant.ofEpochSecond(exp).toString());
        result.put("revoked",         false);
        return result;
    }
}
