package com.did.issuer.service;

import com.did.issuer.config.IssuerKeyConfig.IssuerKeys;
import com.did.issuer.model.CredentialRecord;
import com.did.issuer.store.CredentialStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Emite Verifiable Credentials en formato JWT (VC-JWT).
 *
 * Formato: W3C VC Data Model 1.1 representado como JWT.
 * https://www.w3.org/TR/vc-data-model/#json-web-token
 *
 * El JWT tiene la siguiente estructura:
 *   Header  : { alg: ES256K, typ: JWT, kid: <issuerKeyId> }
 *   Payload : {
 *     iss  : <issuerDid>,
 *     sub  : <holderDid>,
 *     jti  : urn:uuid:...,
 *     iat  : <unix timestamp>,
 *     exp  : <unix timestamp>,
 *     vc   : { @context, type, credentialSubject, ... }
 *   }
 */
@Service
public class CredentialIssuerService {

    private static final Logger log = LoggerFactory.getLogger(CredentialIssuerService.class);

    private final IssuerKeys     issuerKeys;
    private final CredentialStore store;
    private final ObjectMapper    mapper = new ObjectMapper();

    @Value("${issuer.vc-ttl-seconds:86400}")
    private long vcTtlSeconds;

    public CredentialIssuerService(IssuerKeys issuerKeys, CredentialStore store) {
        this.issuerKeys = issuerKeys;
        this.store      = store;
    }

    /**
     * Emite una VC JWT para el holder indicado.
     *
     * @param holderDid     DID del titular de la credencial
     * @param proofPayload  Payload del proof JWT (para extraer subject_claims)
     * @return VC en formato JWT compacto
     */
    public String issue(String holderDid, String proofPayload) throws Exception {
        JsonNode claims    = extractSubjectClaims(proofPayload);
        String   credType  = extractCredentialType(proofPayload);
        String   vcId      = "urn:uuid:" + UUID.randomUUID();
        long     now       = Instant.now().getEpochSecond();

        String issuerDid   = issuerKeys.did;
        String issuerKeyId = issuerDid + "#" + issuerDid.substring("did:key:".length());

        // ── Header ──────────────────────────────────────────────────────────
        String headerJson = """
            {"alg":"ES256K","typ":"JWT","kid":"%s"}
            """.formatted(issuerKeyId).strip();

        // ── credentialSubject ────────────────────────────────────────────────
        String subjectJson = mapper.writeValueAsString(
            mapper.createObjectNode()
                .put("id", holderDid)
                .setAll((com.fasterxml.jackson.databind.node.ObjectNode) claims));

        // ── vc object ────────────────────────────────────────────────────────
        String vcJson = """
            {
              "@context": ["https://www.w3.org/2018/credentials/v1"],
              "id": "%s",
              "type": ["VerifiableCredential", "%s"],
              "issuer": "%s",
              "issuanceDate": "%s",
              "expirationDate": "%s",
              "credentialSubject": %s
            }
            """.formatted(
                vcId,
                credType,
                issuerDid,
                Instant.ofEpochSecond(now),
                Instant.ofEpochSecond(now + vcTtlSeconds),
                subjectJson
            ).strip();

        // ── Payload JWT ──────────────────────────────────────────────────────
        String payloadJson = """
            {
              "jti": "%s",
              "iss": "%s",
              "sub": "%s",
              "iat": %d,
              "exp": %d,
              "vc": %s
            }
            """.formatted(vcId, issuerDid, holderDid, now, now + vcTtlSeconds, vcJson).strip();

        // ── Firmar con clave secp256k1 del emisor ────────────────────────────
        byte[] privKeyBytes = privateKeyBytes();
        String vcJwt = DIDKeyUtil.buildJWT(headerJson, payloadJson, privKeyBytes);

        // ── Persistir solo metadatos de auditoría (nunca el JWT completo) ───────
        // El VC JWT se entrega UNA SOLA VEZ al holder. El servidor no lo guarda.
        // Solo almacenamos el hash SHA-256 para poder revocar sin conocer el contenido.
        String vcJwtHash = bytesToHex(DIDKeyUtil.sha256(vcJwt.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        CredentialRecord record = new CredentialRecord();
        record.setCredentialId(vcId);
        record.setHolderDid(holderDid);
        record.setIssuerDid(issuerDid);
        record.setCredentialType(credType);
        record.setVcJwtHash(vcJwtHash);
        record.setIssuedAt(Instant.ofEpochSecond(now));
        record.setExpiresAt(Instant.ofEpochSecond(now + vcTtlSeconds));
        store.save(record);

        log.info("VC emitida: {} para {}", vcId, holderDid);
        return vcJwt;
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private JsonNode extractSubjectClaims(String proofPayload) throws Exception {
        JsonNode payload = mapper.readTree(DIDKeyUtil.b64urlDecode(proofPayload));
        JsonNode claims  = payload.path("subject_claims");
        return claims.isMissingNode() ? mapper.createObjectNode() : claims;
    }

    private String extractCredentialType(String proofPayload) throws Exception {
        JsonNode payload = mapper.readTree(DIDKeyUtil.b64urlDecode(proofPayload));
        String   type    = payload.path("credential_type").asText("VerifiableCredential");
        // Sanitizar: solo alfanumérico + guión
        return type.replaceAll("[^a-zA-Z0-9\\-]", "");
    }

    private byte[] privateKeyBytes() {
        java.security.interfaces.ECPrivateKey priv =
            (java.security.interfaces.ECPrivateKey) issuerKeys.keyPair.getPrivate();
        return DIDKeyUtil.to32Bytes(priv.getS());
    }
}
