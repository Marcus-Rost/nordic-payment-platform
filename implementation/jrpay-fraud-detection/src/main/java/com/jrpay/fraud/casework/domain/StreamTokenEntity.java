package com.jrpay.fraud.casework.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stream_tokens")
public class StreamTokenEntity {

    @Id
    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    @Column(name = "operator_user_id", nullable = false)
    private UUID operatorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 16)
    private Scope scope;

    @Column(name = "resource_id", length = 64)
    private String resourceId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected StreamTokenEntity() {}

    public StreamTokenEntity(String tokenHash, UUID operatorUserId, Scope scope,
                             String resourceId, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.operatorUserId = operatorUserId;
        this.scope = scope;
        this.resourceId = resourceId;
        this.issuedAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public String getTokenHash() { return tokenHash; }
    public UUID getOperatorUserId() { return operatorUserId; }
    public Scope getScope() { return scope; }
    public String getResourceId() { return resourceId; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }

    public boolean isLive(Instant now) {
        return consumedAt == null && now.isBefore(expiresAt);
    }

    public void markConsumed() { this.consumedAt = Instant.now(); }

    public enum Scope { FRAUD, SANCTIONS }
}
