package com.did.issuer.store;

import com.did.issuer.model.CredentialRecord;

import java.util.List;
import java.util.Optional;

/**
 * Port para persistir y consultar metadatos de VCs emitidas.
 * Implementaciones: JpaCredentialStore (local/H2) y AzureTableCredentialStore (cloud).
 */
public interface CredentialStore {

    void save(CredentialRecord record);

    List<CredentialRecord> findByHolderDid(String holderDid);

    Optional<CredentialRecord> findByCredentialId(String credentialId);

    /** @return true si se revocó, false si no se encontró */
    boolean revoke(String credentialId);
}
