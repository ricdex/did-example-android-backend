package com.did.issuer.store;

import com.did.issuer.model.HolderDIDRecord;
import com.did.issuer.repository.HolderDIDRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Implementación JPA del HolderDIDStore — activa cuando NO hay Azure Table Storage configurado.
 */
@Component
@ConditionalOnMissingBean(name = "azureTableHolderDIDStore")
public class JpaHolderDIDStore implements HolderDIDStore {

    private final HolderDIDRepository repository;

    public JpaHolderDIDStore(HolderDIDRepository repository) {
        this.repository = repository;
    }

    @Override
    public HolderDIDRecord save(HolderDIDRecord record) {
        return repository.save(record);
    }

    @Override
    public Optional<HolderDIDRecord> findByDid(String did) {
        return repository.findById(did);
    }

    @Override
    public boolean invalidate(String did) {
        return repository.findById(did).map(record -> {
            record.setActive(false);
            record.setInvalidatedAt(Instant.now());
            repository.save(record);
            return true;
        }).orElse(false);
    }

    @Override
    public List<HolderDIDRecord> findByClientId(String clientId) {
        return repository.findByClientId(clientId);
    }
}
