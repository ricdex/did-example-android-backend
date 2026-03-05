package com.did.issuer.store;

import com.did.issuer.model.CredentialRecord;
import com.did.issuer.repository.CredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JpaCredentialStoreTest {

    @Mock
    private CredentialRepository repository;

    private JpaCredentialStore store;

    @BeforeEach
    void setUp() {
        store = new JpaCredentialStore(repository);
    }

    @Test
    void save_delegatesToRepository() {
        CredentialRecord record = record("urn:uuid:test-1", "did:key:zHolder1");
        store.save(record);
        verify(repository).save(record);
    }

    @Test
    void findByHolderDid_returnsRepositoryResult() {
        String holderDid = "did:key:zHolder1";
        CredentialRecord record = record("urn:uuid:test-1", holderDid);
        when(repository.findByHolderDid(holderDid)).thenReturn(List.of(record));

        List<CredentialRecord> result = store.findByHolderDid(holderDid);

        assertThat(result).containsExactly(record);
    }

    @Test
    void revoke_whenFound_setsRevokedAndReturnsTrue() {
        String credentialId = "urn:uuid:test-1";
        CredentialRecord record = record(credentialId, "did:key:zHolder1");
        when(repository.findByCredentialId(credentialId)).thenReturn(Optional.of(record));

        boolean result = store.revoke(credentialId);

        assertThat(result).isTrue();
        assertThat(record.isRevoked()).isTrue();
        verify(repository).save(record);
    }

    @Test
    void revoke_whenNotFound_returnsFalse() {
        String credentialId = "urn:uuid:does-not-exist";
        when(repository.findByCredentialId(credentialId)).thenReturn(Optional.empty());

        boolean result = store.revoke(credentialId);

        assertThat(result).isFalse();
        verify(repository, never()).save(any());
    }

    private CredentialRecord record(String credentialId, String holderDid) {
        CredentialRecord r = new CredentialRecord();
        r.setCredentialId(credentialId);
        r.setHolderDid(holderDid);
        r.setIssuerDid("did:key:zIssuer");
        r.setCredentialType("VerifiableCredential");
        r.setVcJwtHash("abc123");
        r.setIssuedAt(Instant.now());
        r.setExpiresAt(Instant.now().plusSeconds(86400));
        return r;
    }
}
