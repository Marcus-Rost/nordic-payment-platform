package com.jrpay.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Tracks issued step-up tokens by {@code SHA-256(token)} so we can revoke them
 * on logout / SCA-session consumption. The token bytes themselves never persist.
 */
@Entity
@Table(name = "identity_step_up_tokens")
public class StepUpToken {

    @Id
    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "sca_session_id", nullable = false)
    private UUID scaSessionId;

    @Column(name = "dl_hash", nullable = false, length = 64)
    private String dlHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    protected StepUpToken() {
    }

    public StepUpToken(String tokenHash, UUID userId, UUID scaSessionId, String dlHash,
                       OffsetDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.scaSessionId = scaSessionId;
        this.dlHash = dlHash;
        this.issuedAt = OffsetDateTime.now();
        this.expiresAt = expiresAt;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getScaSessionId() {
        return scaSessionId;
    }

    public String getDlHash() {
        return dlHash;
    }

    public OffsetDateTime getIssuedAt() {
        return issuedAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getConsumedAt() {
        return consumedAt;
    }

    public void markConsumed() {
        this.consumedAt = OffsetDateTime.now();
    }

    public boolean isLive() {
        return consumedAt == null && expiresAt.isAfter(OffsetDateTime.now());
    }
}
