package com.did.issuer.repository;

import com.did.issuer.model.CredentialRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CredentialRepository extends JpaRepository<CredentialRecord, Long> {
    List<CredentialRecord>     findByHolderDid(String holderDid);
    Optional<CredentialRecord> findByCredentialId(String credentialId);
}
