package com.did.issuer.store;

import com.did.issuer.model.HolderDIDRecord;

import java.util.List;
import java.util.Optional;

/**
 * Puerto de persistencia para el registro de DIDs de holders.
 *
 * Implementaciones:
 *  - JpaHolderDIDStore     → H2 en local (activo por defecto)
 *  - AzureTableHolderDIDStore → Azure Table Storage (activo con AZURE_STORAGE_ACCOUNT_NAME)
 */
public interface HolderDIDStore {

    /** Persiste un registro nuevo. */
    HolderDIDRecord save(HolderDIDRecord record);

    /** Busca por DID exacto. */
    Optional<HolderDIDRecord> findByDid(String did);

    /**
     * Marca el DID como inactivo (invalidado).
     * @return true si existía y se invalidó, false si no existía
     */
    boolean invalidate(String did);

    /** Devuelve todos los DIDs (activos e inactivos) de un cliente. */
    List<HolderDIDRecord> findByClientId(String clientId);
}
