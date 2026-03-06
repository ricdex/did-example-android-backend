package com.did.issuer.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public class CredentialRequest {

    @JsonProperty("holder_did")
    @JsonAlias("holderDid")
    @NotBlank(message = "holder_did es obligatorio")
    private String holderDid;

    @NotBlank(message = "proof es obligatorio")
    private String proof;           // JWT de prueba de posesión

    // ── getters / setters ─────────────────────────────────────────────────

    public String getHolderDid() { return holderDid; }
    public void   setHolderDid(String holderDid) { this.holderDid = holderDid; }

    public String getProof()     { return proof; }
    public void   setProof(String proof) { this.proof = proof; }
}
