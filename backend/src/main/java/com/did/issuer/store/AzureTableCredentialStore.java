package com.did.issuer.store;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableEntityUpdateMode;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.did.issuer.model.CredentialRecord;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Implementación Azure Table Storage del CredentialStore.
 * Se activa cuando la propiedad azure.storage.account-name está configurada.
 * Usa Managed Identity (DefaultAzureCredential) — sin contraseñas.
 */
@Component("azureTableCredentialStore")
@ConditionalOnExpression("!'${azure.storage.account-name:}'.isEmpty()")
public class AzureTableCredentialStore implements CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(AzureTableCredentialStore.class);
    private static final String PARTITION_KEY = "credentials";
    private static final String TABLE_NAME    = "credentials";

    @Value("${azure.storage.account-name}")
    private String accountName;

    private TableClient tableClient;

    @PostConstruct
    void init() {
        String connectionString = System.getenv("AZURE_STORAGE_CONNECTION_STRING");
        TableClientBuilder builder = new TableClientBuilder().tableName(TABLE_NAME);
        if (connectionString != null && !connectionString.isEmpty()) {
            builder.connectionString(connectionString);
            log.info("AzureTableCredentialStore inicializado con connection string (cuenta: {})", accountName);
        } else {
            String endpoint = "https://" + accountName + ".table.core.windows.net";
            builder.endpoint(endpoint).credential(new DefaultAzureCredentialBuilder().build());
            log.info("AzureTableCredentialStore inicializado con Managed Identity: {}/{}", endpoint, TABLE_NAME);
        }
        tableClient = builder.buildClient();
    }

    @Override
    public void save(CredentialRecord record) {
        TableEntity entity = new TableEntity(PARTITION_KEY, record.getCredentialId());
        entity.addProperty("holderDid",      record.getHolderDid());
        entity.addProperty("issuerDid",      record.getIssuerDid());
        entity.addProperty("credentialType", record.getCredentialType());
        entity.addProperty("vcJwtHash",      record.getVcJwtHash());
        entity.addProperty("issuedAt",       record.getIssuedAt().toString());
        entity.addProperty("expiresAt",      record.getExpiresAt().toString());
        entity.addProperty("revoked",        record.isRevoked());
        tableClient.createEntity(entity);
        log.debug("Entidad guardada en Azure Table: {}", record.getCredentialId());
    }

    @Override
    public List<CredentialRecord> findByHolderDid(String holderDid) {
        String filter = String.format(
            "PartitionKey eq '%s' and holderDid eq '%s'", PARTITION_KEY, holderDid);
        ListEntitiesOptions options = new ListEntitiesOptions().setFilter(filter);
        List<CredentialRecord> result = new ArrayList<>();
        tableClient.listEntities(options, null, null)
            .forEach(entity -> result.add(toRecord(entity)));
        return result;
    }

    @Override
    public Optional<CredentialRecord> findByCredentialId(String credentialId) {
        try {
            TableEntity entity = tableClient.getEntity(PARTITION_KEY, credentialId);
            return Optional.of(toRecord(entity));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean revoke(String credentialId) {
        try {
            TableEntity entity = tableClient.getEntity(PARTITION_KEY, credentialId);
            entity.addProperty("revoked", true);
            tableClient.updateEntity(entity, TableEntityUpdateMode.MERGE);
            log.info("Credencial revocada en Azure Table: {}", credentialId);
            return true;
        } catch (Exception e) {
            log.warn("No se encontró la credencial para revocar: {}", credentialId);
            return false;
        }
    }

    private CredentialRecord toRecord(TableEntity entity) {
        CredentialRecord r = new CredentialRecord();
        r.setCredentialId(entity.getRowKey());
        r.setHolderDid(getString(entity, "holderDid"));
        r.setIssuerDid(getString(entity, "issuerDid"));
        r.setCredentialType(getString(entity, "credentialType"));
        r.setVcJwtHash(getString(entity, "vcJwtHash"));
        r.setIssuedAt(Instant.parse(getString(entity, "issuedAt")));
        r.setExpiresAt(Instant.parse(getString(entity, "expiresAt")));
        r.setRevoked(Boolean.TRUE.equals(entity.getProperty("revoked")));
        return r;
    }

    private String getString(TableEntity entity, String key) {
        Object val = entity.getProperty(key);
        return val == null ? "" : val.toString();
    }
}
