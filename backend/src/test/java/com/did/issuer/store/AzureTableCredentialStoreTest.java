package com.did.issuer.store;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import com.did.issuer.model.CredentialRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests para AzureTableCredentialStore usando reflection para inyectar
 * el TableClient mockeado y evitar que @PostConstruct se ejecute.
 */
@ExtendWith(MockitoExtension.class)
class AzureTableCredentialStoreTest {

    @Mock
    private TableClient tableClient;

    private AzureTableCredentialStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new AzureTableCredentialStore();
        // Inyectar tableClient sin pasar por @PostConstruct (que requiere Azure)
        Field field = AzureTableCredentialStore.class.getDeclaredField("tableClient");
        field.setAccessible(true);
        field.set(store, tableClient);
    }

    @Test
    void save_createsEntityWithCorrectPartitionAndRowKey() {
        CredentialRecord record = record("urn:uuid:abc-123", "did:key:zHolder1");

        store.save(record);

        ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(tableClient).createEntity(captor.capture());
        TableEntity entity = captor.getValue();

        assertThat(entity.getPartitionKey()).isEqualTo("credentials");
        assertThat(entity.getRowKey()).isEqualTo("urn:uuid:abc-123");
        assertThat(entity.getProperty("holderDid")).isEqualTo("did:key:zHolder1");
        assertThat(entity.getProperty("revoked")).isEqualTo(false);
    }

    @Test
    void save_serializesInstantsAsISO8601() {
        Instant issuedAt  = Instant.parse("2025-01-01T10:00:00Z");
        Instant expiresAt = Instant.parse("2025-01-02T10:00:00Z");

        CredentialRecord record = record("urn:uuid:ts-test", "did:key:zHolder2");
        record.setIssuedAt(issuedAt);
        record.setExpiresAt(expiresAt);

        store.save(record);

        ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(tableClient).createEntity(captor.capture());
        TableEntity entity = captor.getValue();

        assertThat(entity.getProperty("issuedAt")).isEqualTo("2025-01-01T10:00:00Z");
        assertThat(entity.getProperty("expiresAt")).isEqualTo("2025-01-02T10:00:00Z");
    }

    private CredentialRecord record(String credentialId, String holderDid) {
        CredentialRecord r = new CredentialRecord();
        r.setCredentialId(credentialId);
        r.setHolderDid(holderDid);
        r.setIssuerDid("did:key:zIssuer");
        r.setCredentialType("VerifiableCredential");
        r.setVcJwtHash("deadbeef");
        r.setIssuedAt(Instant.now());
        r.setExpiresAt(Instant.now().plusSeconds(86400));
        return r;
    }
}
