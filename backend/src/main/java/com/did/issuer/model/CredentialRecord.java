package com.did.issuer.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "credentials")
public class CredentialRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String credentialId;    // urn:uuid:...

    @Column(nullable = false)
    private String holderDid;

    @Column(nullable = false)
    private String issuerDid;

    @Column(nullable = false)
    private String credentialType;

    // El JWT completo NO se almacena. El holder es el único custodio de su VC.
    // Solo guardamos el hash para poder revocarlo en el futuro sin exponer el contenido.
    @Column(nullable = false, length = 64)
    private String vcJwtHash;       // SHA-256 hex del VC JWT (solo para revocación)

    @Column(nullable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked = false;

    // ── getters / setters ─────────────────────────────────────────────────

    public Long    getId()             { return id; }
    public String  getCredentialId()   { return credentialId; }
    public String  getHolderDid()      { return holderDid; }
    public String  getIssuerDid()      { return issuerDid; }
    public String  getCredentialType() { return credentialType; }
    public String  getVcJwtHash()      { return vcJwtHash; }
    public Instant getIssuedAt()       { return issuedAt; }
    public Instant getExpiresAt()      { return expiresAt; }
    public boolean isRevoked()         { return revoked; }

    public void setCredentialId(String credentialId)     { this.credentialId   = credentialId; }
    public void setHolderDid(String holderDid)           { this.holderDid      = holderDid; }
    public void setIssuerDid(String issuerDid)           { this.issuerDid      = issuerDid; }
    public void setCredentialType(String credentialType) { this.credentialType = credentialType; }
    public void setVcJwtHash(String vcJwtHash)           { this.vcJwtHash      = vcJwtHash; }
    public void setIssuedAt(Instant issuedAt)            { this.issuedAt       = issuedAt; }
    public void setExpiresAt(Instant expiresAt)          { this.expiresAt      = expiresAt; }
    public void setRevoked(boolean revoked)              { this.revoked        = revoked; }
}
