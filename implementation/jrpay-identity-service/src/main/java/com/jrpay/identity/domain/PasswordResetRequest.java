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
 * Per-attempt request log used for rate-limiting (3/hour/identifier-hash) so
 * we can return a neutral 202 even when the identifier doesn't resolve and
 * still bound abuse from the outside.
 */
@Entity
@Table(name = "identity_password_reset_requests")
public class PasswordResetRequest {

    @Id
    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "identifier_hash", nullable = false, length = 64)
    private String identifierHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 16)
    private IdentityUser.Audience audience;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "resolved", nullable = false)
    private boolean resolved;

    protected PasswordResetRequest() {
    }

    public PasswordResetRequest(UUID requestId, String identifierHash,
                                IdentityUser.Audience audience, boolean resolved) {
        this.requestId = requestId;
        this.identifierHash = identifierHash;
        this.audience = audience;
        this.resolved = resolved;
        this.issuedAt = OffsetDateTime.now();
    }

    public UUID getRequestId() {
        return requestId;
    }

    public String getIdentifierHash() {
        return identifierHash;
    }

    public IdentityUser.Audience getAudience() {
        return audience;
    }

    public OffsetDateTime getIssuedAt() {
        return issuedAt;
    }

    public boolean isResolved() {
        return resolved;
    }
}
