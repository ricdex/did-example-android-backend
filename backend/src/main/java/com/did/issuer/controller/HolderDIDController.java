package com.did.issuer.controller;

import com.did.issuer.model.HolderDIDRecord;
import com.did.issuer.service.HolderDIDService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Endpoints para gestión y verificación de DIDs de holders.
 *
 * POST /dids/register                  → registrar DID asociado a un cliente
 * POST /dids/{did}/invalidate          → invalidar DID (app borrada / dispositivo perdido)
 * GET  /dids/{did}                     → verificar si un DID está activo
 * GET  /clients/{clientId}/dids        → listar DIDs de un cliente
 */
@RestController
public class HolderDIDController {

    private static final Logger log = LoggerFactory.getLogger(HolderDIDController.class);

    private final HolderDIDService service;

    public HolderDIDController(HolderDIDService service) {
        this.service = service;
    }

    // ── Registro ─────────────────────────────────────────────────────────────

    /**
     * Registra un DID asociado a un cliente.
     *
     * Body: { "client_id": "user@example.com", "did": "did:key:zQ3sh..." }
     *
     * Si el DID ya estaba registrado devuelve el registro existente (idempotente).
     */
    @PostMapping("/dids/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        String clientId = body.get("client_id");
        String did      = body.get("did");

        if (clientId == null || clientId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "client_id es requerido"));
        }
        if (did == null || did.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "did es requerido"));
        }
        if (!did.startsWith("did:key:")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Solo se admiten DIDs del método did:key"));
        }

        HolderDIDRecord record = service.register(clientId, did);
        return ResponseEntity.ok(toResponse(record));
    }

    // ── Invalidación ─────────────────────────────────────────────────────────

    /**
     * Invalida un DID.
     * Usar cuando el holder borra la app o pierde el dispositivo.
     * Las VCs emitidas para ese DID siguen siendo válidas técnicamente
     * (firmadas por el issuer), pero los verificadores que consulten
     * este endpoint sabrán que el DID fue invalidado.
     *
     * El DID puede contener colones (did:key:z...), por eso :.+
     * @return 204 si se invalidó, 404 si no existía
     */
    @PostMapping("/dids/{did:.+}/invalidate")
    public ResponseEntity<Void> invalidate(@PathVariable String did) {
        log.info("Solicitud de invalidación de DID: {}", did);
        boolean ok = service.invalidate(did);
        return ok
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }

    // ── Verificación ─────────────────────────────────────────────────────────

    /**
     * Devuelve el estado de un DID.
     * Endpoint público — los verificadores lo usan para comprobar si el holder
     * sigue siendo dueño del DID que presentó en el VP.
     *
     * @return 200 { did, client_id, active, registered_at, invalidated_at? }
     *         404 si el DID no está registrado
     */
    @GetMapping("/dids/{did:.+}")
    public ResponseEntity<?> status(@PathVariable String did) {
        return service.findByDid(did)
            .map(r -> ResponseEntity.ok(toResponse(r)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // ── Listado por cliente ───────────────────────────────────────────────────

    /**
     * Lista todos los DIDs (activos e inactivos) asociados a un cliente.
     * Útil para el panel de administración del issuer.
     */
    @GetMapping("/clients/{clientId}/dids")
    public ResponseEntity<List<Map<String, Object>>> byClient(@PathVariable String clientId) {
        List<Map<String, Object>> result = service.findByClientId(clientId).stream()
            .map(this::toResponse)
            .toList();
        return ResponseEntity.ok(result);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> toResponse(HolderDIDRecord r) {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("did",           r.getDid());
        map.put("client_id",     r.getClientId());
        map.put("active",        r.isActive());
        map.put("registered_at", r.getRegisteredAt().toString());
        if (r.getInvalidatedAt() != null) {
            map.put("invalidated_at", r.getInvalidatedAt().toString());
        }
        return map;
    }
}
