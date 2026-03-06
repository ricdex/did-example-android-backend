package com.did.issuer.service;

import com.did.issuer.model.HolderDIDRecord;
import com.did.issuer.store.HolderDIDStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Lógica de negocio para gestión de DIDs de holders.
 *
 * Un cliente (clientId) puede tener múltiples DIDs a lo largo del tiempo:
 *  - DID activo  → el dispositivo actual
 *  - DID inactivo → dispositivo anterior (perdido / borrado)
 *
 * El clientId es un identificador externo (email, userId, DNI, etc.)
 * que el issuer ya conoce/valida por su propio canal.
 */
@Service
public class HolderDIDService {

    private static final Logger log = LoggerFactory.getLogger(HolderDIDService.class);

    private final HolderDIDStore store;

    public HolderDIDService(HolderDIDStore store) {
        this.store = store;
    }

    /**
     * Registra un nuevo DID para un cliente.
     * Si el DID ya existe, devuelve el registro existente sin modificarlo.
     *
     * @param clientId identificador del cliente (email, userId, DNI, etc.)
     * @param did      DID generado en el dispositivo ("did:key:z...")
     * @return el registro guardado
     */
    public HolderDIDRecord register(String clientId, String did) {
        Optional<HolderDIDRecord> existing = store.findByDid(did);
        if (existing.isPresent()) {
            log.info("DID ya registrado para cliente {}: {}", clientId, did);
            return existing.get();
        }
        HolderDIDRecord record = new HolderDIDRecord();
        record.setDid(did);
        record.setClientId(clientId);
        record.setRegisteredAt(Instant.now());
        record.setActive(true);
        HolderDIDRecord saved = store.save(record);
        log.info("DID registrado para cliente {}: {}", clientId, did);
        return saved;
    }

    /**
     * Invalida un DID (ej. el holder borró la app o perdió el dispositivo).
     *
     * @return true si existía y fue invalidado, false si no se encontró
     */
    public boolean invalidate(String did) {
        boolean ok = store.invalidate(did);
        if (ok) {
            log.info("DID invalidado: {}", did);
        } else {
            log.warn("Intento de invalidar DID inexistente: {}", did);
        }
        return ok;
    }

    /**
     * Devuelve el estado de un DID (para verificadores externos).
     */
    public Optional<HolderDIDRecord> findByDid(String did) {
        return store.findByDid(did);
    }

    /**
     * Devuelve todos los DIDs (activos e inactivos) asociados a un cliente.
     */
    public List<HolderDIDRecord> findByClientId(String clientId) {
        return store.findByClientId(clientId);
    }

    /**
     * Verifica que el DID está registrado y activo.
     * Lanza IllegalArgumentException si no lo está (para uso interno en CredentialController).
     */
    public void assertActive(String did) {
        HolderDIDRecord record = store.findByDid(did)
            .orElseThrow(() -> new IllegalArgumentException(
                "DID no registrado: " + did));
        if (!record.isActive()) {
            throw new IllegalArgumentException("DID invalidado: " + did);
        }
    }
}
