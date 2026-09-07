package com.jrpay.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "stream_tokens")
public class StreamTokenEntity {

    @Id
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 16)
    private Audience audience;

    @Column(name = "purpose", nullable = false, length = 32)
    private String purpose;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    protected StreamTokenEntity() {
    }

    public StreamTokenEntity(String tokenHash, UUID userId, Audience audience, String purpose,
                             OffsetDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.audience = audience;
        this.purpose = purpose;
        this.issuedAt = OffsetDateTime.now();
        this.expiresAt = expiresAt;
    }

    public String getTokenHash() { return tokenHash; }
    public UUID getUserId() { return userId; }
    public Audience getAudience() { return audience; }
    public String getPurpose() { return purpose; }
    public OffsetDateTime getIssuedAt() { return issuedAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public OffsetDateTime getConsumedAt() { return consumedAt; }

    public boolean isLive() {
        return consumedAt == null && expiresAt.isAfter(OffsetDateTime.now());
    }

    public void markConsumed() {
        this.consumedAt = OffsetDateTime.now();
    }
}
