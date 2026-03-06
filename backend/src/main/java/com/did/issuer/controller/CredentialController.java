package com.did.issuer.controller;

import com.did.issuer.config.IssuerKeyConfig.IssuerKeys;
import com.did.issuer.dto.CredentialRequest;
import com.did.issuer.model.CredentialRecord;
import com.did.issuer.service.CredentialIssuerService;
import com.did.issuer.service.DIDKeyUtil;
import com.did.issuer.service.HolderDIDService;
import com.did.issuer.service.NonceService;
import com.did.issuer.service.ProofVerifier;
import com.did.issuer.service.VPVerifierService;
import com.did.issuer.store.CredentialStore;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Endpoints del emisor de Verifiable Credentials.
 *
 * GET  /credentials/nonce              → nonce fresco para el holder
 * POST /credentials/issue              → emitir una VC
 * GET  /credentials?holder_did={did}   → listar VCs de un holder
 * GET  /issuer/did                     → DID del emisor (para configuración del holder)
 */
@RestController
public class CredentialController {

    private static final Logger log = LoggerFactory.getLogger(CredentialController.class);

    private final NonceService            nonceService;
    private final ProofVerifier           proofVerifier;
    private final CredentialIssuerService issuerService;
    private final CredentialStore         store;
    private final IssuerKeys              issuerKeys;
    private final HolderDIDService        holderDIDService;
    private final VPVerifierService       vpVerifier;

    public CredentialController(
        NonceService nonceService,
        ProofVerifier proofVerifier,
        CredentialIssuerService issuerService,
        CredentialStore store,
        IssuerKeys issuerKeys,
        HolderDIDService holderDIDService,
        VPVerifierService vpVerifier
    ) {
        this.nonceService     = nonceService;
        this.proofVerifier    = proofVerifier;
        this.issuerService    = issuerService;
        this.store            = store;
        this.issuerKeys       = issuerKeys;
        this.holderDIDService = holderDIDService;
        this.vpVerifier       = vpVerifier;
    }

    // ── Nonce ────────────────────────────────────────────────────────────────

    /**
     * Devuelve un nonce de un solo uso.
     * El holder_did debe estar registrado y activo para obtener el nonce.
     */
    @GetMapping("/credentials/nonce")
    public ResponseEntity<?> nonce(@RequestParam("holder_did") String holderDid) {
        try {
            holderDIDService.assertActive(holderDid);
        } catch (IllegalArgumentException e) {
            log.warn("Nonce rechazado — DID inválido: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("nonce", nonceService.generate()));
    }

    // ── Emisión ──────────────────────────────────────────────────────────────

    /**
     * Emite una Verifiable Credential después de verificar el proof JWT.
     *
     * Body:
     * {
     *   "holder_did": "did:key:zQ3s...",
     *   "proof":      "<JWT firmado con secp256k1>"
     * }
     */
    @PostMapping("/credentials/issue")
    public ResponseEntity<?> issue(@Valid @RequestBody CredentialRequest request) {
        try {
            // 1. Verificar que el DID está registrado y activo
            holderDIDService.assertActive(request.getHolderDid());

            // 2. Verificar proof JWT (firma + nonce + expiración + iss)
            proofVerifier.verify(request.getProof(), request.getHolderDid());

            // 3. Extraer el payload para obtener subject_claims y credential_type
            String proofPayloadPart = request.getProof().split("\\.")[1];

            // 4. Emitir y persistir la VC
            CredentialIssuerService.IssueResult result =
                issuerService.issue(request.getHolderDid(), proofPayloadPart);

            return ResponseEntity.ok(Map.of(
                "credentialId", result.credentialId(),
                "credential",   result.credentialJwt()
            ));

        } catch (IllegalArgumentException e) {
            log.warn("Credential request rechazada: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Error emitiendo credential", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Error interno"));
        }
    }

    // ── Consulta ─────────────────────────────────────────────────────────────

    /**
     * Devuelve SOLO metadatos de auditoría de las VCs emitidas para un DID.
     * El contenido de la VC nunca se expone desde el servidor — el holder
     * es el único custodio (principio SSI).
     *
     * Útil para que el holder sepa qué emitió el servidor (e.g. tras reinstalar la app).
     */
    @GetMapping("/credentials")
    public ResponseEntity<List<Map<String, Object>>> listByHolder(
        @RequestParam("holder_did") String holderDid
    ) {
        List<Map<String, Object>> result = store.findByHolderDid(holderDid).stream()
            .map(r -> Map.<String, Object>of(
                "credential_id",   r.getCredentialId(),
                "credential_type", r.getCredentialType(),
                "issued_at",       r.getIssuedAt().toString(),
                "expires_at",      r.getExpiresAt().toString(),
                "revoked",         r.isRevoked()
                // ⚠️ No se expone el JWT — el holder lo tiene en su wallet
            ))
            .toList();
        return ResponseEntity.ok(result);
    }

    // ── Revocación ───────────────────────────────────────────────────────────

    /**
     * Revoca una VC por su ID.
     * El credentialId puede contener colones (urn:uuid:...), por eso :.+
     *
     * @return 204 si se revocó, 404 si no existe
     */
    @PostMapping("/credentials/{credentialId:.+}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable String credentialId) {
        log.info("Solicitud de revocación: {}", credentialId);
        boolean revoked = store.revoke(credentialId);
        return revoked
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    // ── Verificación de VP ────────────────────────────────────────────────────

    /**
     * Verifica una Verifiable Presentation (VP JWT) enviada por un holder.
     *
     * Body: { "vp_jwt": "eyJ..." }
     *
     * Respuesta 200 si es válida:
     *   { "valid": true, "holder_did": "...", "credentials": [...] }
     *
     * Respuesta 400 si es inválida:
     *   { "valid": false, "reason": "..." }
     */
    @PostMapping("/credentials/verify")
    public ResponseEntity<Map<String, Object>> verifyVP(@RequestBody Map<String, String> body) {
        String vpJwt = body.get("vp_jwt");
        if (vpJwt == null || vpJwt.isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("valid", false, "reason", "El campo vp_jwt es requerido"));
        }
        try {
            Map<String, Object> result = vpVerifier.verify(vpJwt);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("VP rechazada: {}", e.getMessage());
            return ResponseEntity.badRequest()
                .body(Map.of("valid", false, "reason", e.getMessage()));
        }
    }

    // ── Info del emisor ───────────────────────────────────────────────────────

    /** Devuelve el DID del emisor y su clave pública (para configurar el holder). */
    @GetMapping("/issuer/did")
    public ResponseEntity<Map<String, String>> issuerDid() {
        return ResponseEntity.ok(Map.of(
            "did",            issuerKeys.did,
            "public_key_hex", issuerKeys.publicKeyHex
        ));
    }

    /** DID Document del emisor (formato W3C). */
    @GetMapping("/issuer/did-document")
    public ResponseEntity<String> issuerDIDDocument() {
        String did    = issuerKeys.did;
        String kid    = did + "#" + did.substring("did:key:".length());
        String pubHex = issuerKeys.publicKeyHex;

        String doc = """
            {
              "@context": [
                "https://www.w3.org/ns/did/v1",
                "https://w3id.org/security/suites/secp256k1-2019/v1"
              ],
              "id": "%s",
              "verificationMethod": [{
                "id": "%s",
                "type": "EcdsaSecp256k1VerificationKey2019",
                "controller": "%s",
                "publicKeyHex": "%s"
              }],
              "assertionMethod": ["%s"]
            }
            """.formatted(did, kid, did, pubHex, kid);

        return ResponseEntity.ok()
            .header("Content-Type", "application/did+json")
            .body(doc.strip());
    }
}
