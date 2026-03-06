package com.did.issuer.repository;

import com.did.issuer.model.HolderDIDRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HolderDIDRepository extends JpaRepository<HolderDIDRecord, String> {
    List<HolderDIDRecord> findByClientId(String clientId);
}
