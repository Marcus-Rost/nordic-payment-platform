package com.jrpay.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "identity_sca_factors")
public class ScaFactor {

    @Id
    @Column(name = "factor_id", nullable = false, updatable = false)
    private UUID factorId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "factor_type", nullable = false, length = 32)
    private FactorType factorType;

    @Column(name = "secret", nullable = false, columnDefinition = "TEXT")
    private String secret;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    protected ScaFactor() {
    }

    public ScaFactor(UUID factorId, UUID userId, FactorType factorType, String secret) {
        this.factorId = factorId;
        this.userId = userId;
        this.factorType = factorType;
        this.secret = secret;
        this.active = true;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getFactorId() {
        return factorId;
    }

    public UUID getUserId() {
        return userId;
    }

    public FactorType getFactorType() {
        return factorType;
    }

    public String getSecret() {
        return secret;
    }

    public boolean isActive() {
        return active;
    }

    public void deactivate() {
        this.active = false;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void markUsed() {
        this.lastUsedAt = OffsetDateTime.now();
    }

    public enum FactorType {
        TOTP, BANKID_SIMULATED, BANKID_REAL, BIOMETRIC
    }
}
