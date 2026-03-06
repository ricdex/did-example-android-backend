package com.did.issuer.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Registro de un DID de holder en el sistema del issuer.
 *
 * El issuer lleva un registro de qué DIDs están activos para poder:
 *  - Rechazar nonces y emisiones para DIDs no registrados
 *  - Invalidar un DID cuando el holder pierde el dispositivo
 *  - Permitir que verificadores consulten si un DID sigue activo
 */
@Entity
@Table(name = "holder_dids")
public class HolderDIDRecord {

    @Id
    @Column(length = 300)
    private String did;

    /** Identificador del cliente/usuario dueño de este DID (ej. email, userId, DNI). */
    @Column(nullable = false, length = 200)
    private String clientId;

    @Column(nullable = false)
    private Instant registeredAt;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = true)
    private Instant invalidatedAt;

    // ── getters / setters ─────────────────────────────────────────────────

    public String  getDid()             { return did; }
    public String  getClientId()        { return clientId; }
    public Instant getRegisteredAt()    { return registeredAt; }
    public boolean isActive()           { return active; }
    public Instant getInvalidatedAt()   { return invalidatedAt; }

    public void setDid(String did)                       { this.did            = did; }
    public void setClientId(String clientId)             { this.clientId       = clientId; }
    public void setRegisteredAt(Instant registeredAt)    { this.registeredAt   = registeredAt; }
    public void setActive(boolean active)                { this.active         = active; }
    public void setInvalidatedAt(Instant invalidatedAt)  { this.invalidatedAt  = invalidatedAt; }
}
