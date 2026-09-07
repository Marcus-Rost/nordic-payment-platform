package com.jrpay.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Single-use SSE stream token (5-min TTL) used by UC-G52 / UC-G53 session
 * stream and the customer/admin SSE endpoints in general — see
 * {@code gui/architecture/SSE_CONVENTIONS.md} §"Authenticated streams".
 */
@Entity
@Table(name = "identity_stream_tokens")
public class StreamToken {

    @Id
    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 16)
    private IdentityUser.Audience audience;

    @Column(name = "purpose", nullable = false, length = 32)
    private String purpose;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected StreamToken() {
    }

    public StreamToken(String tokenHash, UUID userId, IdentityUser.Audience audience,
                       String purpose, OffsetDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.audience = audience;
        this.purpose = purpose;
        this.expiresAt = expiresAt;
        this.createdAt = OffsetDateTime.now();
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getUserId() {
        return userId;
    }

    public IdentityUser.Audience getAudience() {
        return audience;
    }

    public String getPurpose() {
        return purpose;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getConsumedAt() {
        return consumedAt;
    }

    public boolean isLive() {
        return consumedAt == null && expiresAt.isAfter(OffsetDateTime.now());
    }

    public void markConsumed() {
        this.consumedAt = OffsetDateTime.now();
    }
}
