package com.did.issuer.store;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
import com.azure.data.tables.models.ListEntitiesOptions;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableEntityUpdateMode;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.did.issuer.model.HolderDIDRecord;
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
 * Implementación Azure Table Storage del HolderDIDStore.
 * Se activa cuando la propiedad azure.storage.account-name está configurada.
 * PartitionKey = "dids" (fijo), RowKey = DID codificado (colones → "|")
 */
@Component("azureTableHolderDIDStore")
@ConditionalOnExpression("!'${azure.storage.account-name:}'.isEmpty()")
public class AzureTableHolderDIDStore implements HolderDIDStore {

    private static final Logger log = LoggerFactory.getLogger(AzureTableHolderDIDStore.class);
    private static final String PARTITION_KEY = "dids";
    private static final String TABLE_NAME    = "holderDids";

    @Value("${azure.storage.account-name}")
    private String accountName;

    private TableClient tableClient;

    @PostConstruct
    void init() {
        String connectionString = System.getenv("AZURE_STORAGE_CONNECTION_STRING");
        TableClientBuilder builder = new TableClientBuilder().tableName(TABLE_NAME);
        if (connectionString != null && !connectionString.isEmpty()) {
            builder.connectionString(connectionString);
            log.info("AzureTableHolderDIDStore inicializado con connection string (cuenta: {})", accountName);
        } else {
            String endpoint = "https://" + accountName + ".table.core.windows.net";
            builder.endpoint(endpoint).credential(new DefaultAzureCredentialBuilder().build());
            log.info("AzureTableHolderDIDStore inicializado con Managed Identity: {}/{}", endpoint, TABLE_NAME);
        }
        tableClient = builder.buildClient();
    }

    @Override
    public HolderDIDRecord save(HolderDIDRecord record) {
        TableEntity entity = new TableEntity(PARTITION_KEY, encodeRowKey(record.getDid()));
        entity.addProperty("did",            record.getDid());
        entity.addProperty("clientId",       record.getClientId());
        entity.addProperty("registeredAt",   record.getRegisteredAt().toString());
        entity.addProperty("active",         record.isActive());
        if (record.getInvalidatedAt() != null) {
            entity.addProperty("invalidatedAt", record.getInvalidatedAt().toString());
        }
        tableClient.upsertEntity(entity);
        log.debug("HolderDID guardado en Azure Table: {}", record.getDid());
        return record;
    }

    @Override
    public Optional<HolderDIDRecord> findByDid(String did) {
        try {
            TableEntity entity = tableClient.getEntity(PARTITION_KEY, encodeRowKey(did));
            return Optional.of(toRecord(entity));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean invalidate(String did) {
        try {
            TableEntity entity = tableClient.getEntity(PARTITION_KEY, encodeRowKey(did));
            entity.addProperty("active",        false);
            entity.addProperty("invalidatedAt", Instant.now().toString());
            tableClient.updateEntity(entity, TableEntityUpdateMode.MERGE);
            log.info("HolderDID invalidado en Azure Table: {}", did);
            return true;
        } catch (Exception e) {
            log.warn("No se encontró el DID para invalidar: {}", did);
            return false;
        }
    }

    @Override
    public List<HolderDIDRecord> findByClientId(String clientId) {
        String filter = String.format(
            "PartitionKey eq '%s' and clientId eq '%s'", PARTITION_KEY, clientId);
        ListEntitiesOptions options = new ListEntitiesOptions().setFilter(filter);
        List<HolderDIDRecord> result = new ArrayList<>();
        tableClient.listEntities(options, null, null)
            .forEach(entity -> result.add(toRecord(entity)));
        return result;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Azure Table RowKey no permite ':', '/', '\', '#', '?'.
     * Reemplazamos ':' por '|' para almacenar "did:key:z..." sin problemas.
     */
    private String encodeRowKey(String did) {
        return did.replace(":", "|");
    }

    private HolderDIDRecord toRecord(TableEntity entity) {
        HolderDIDRecord r = new HolderDIDRecord();
        r.setDid(getString(entity, "did"));
        r.setClientId(getString(entity, "clientId"));
        r.setRegisteredAt(Instant.parse(getString(entity, "registeredAt")));
        r.setActive(Boolean.TRUE.equals(entity.getProperty("active")));
        String inv = getString(entity, "invalidatedAt");
        if (!inv.isEmpty()) {
            r.setInvalidatedAt(Instant.parse(inv));
        }
        return r;
    }

    private String getString(TableEntity entity, String key) {
        Object val = entity.getProperty(key);
        return val == null ? "" : val.toString();
    }
}
