package com.did.issuer.store;

import com.did.issuer.model.CredentialRecord;
import com.did.issuer.repository.CredentialRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Implementación JPA del CredentialStore — activa cuando NO hay Azure Table Storage configurado.
 * En local usa H2; el comportamiento es idéntico al de CredentialRepository anterior.
 */
@Component
@ConditionalOnMissingBean(name = "azureTableCredentialStore")
public class JpaCredentialStore implements CredentialStore {

    private final CredentialRepository repository;

    public JpaCredentialStore(CredentialRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(CredentialRecord record) {
        repository.save(record);
    }

    @Override
    public List<CredentialRecord> findByHolderDid(String holderDid) {
        return repository.findByHolderDid(holderDid);
    }

    @Override
    public Optional<CredentialRecord> findByCredentialId(String credentialId) {
        return repository.findByCredentialId(credentialId);
    }

    @Override
    public boolean revoke(String credentialId) {
        return repository.findByCredentialId(credentialId).map(record -> {
            record.setRevoked(true);
            repository.save(record);
            return true;
        }).orElse(false);
    }
}
