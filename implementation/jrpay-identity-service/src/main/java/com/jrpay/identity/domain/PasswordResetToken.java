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
@Table(name = "identity_password_reset_tokens")
public class PasswordResetToken {

    @Id
    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 16)
    private IdentityUser.Audience audience;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;

    protected PasswordResetToken() {
    }

    public PasswordResetToken(String tokenHash, UUID userId, IdentityUser.Audience audience,
                              OffsetDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.audience = audience;
        this.issuedAt = OffsetDateTime.now();
        this.expiresAt = expiresAt;
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
