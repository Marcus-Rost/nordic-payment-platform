package com.jrpay.psd2.sca;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * SCA session — tracks the state of a strong customer authentication flow.
 * Dynamic link data (amount + creditor) is immutable once created.
 * Session expires after 5 minutes (PSD2 RTS).
 * Lockout after 3 failed attempts (durable, survives restart).
 */
@Entity
@Table(name = "sca_sessions")
public class ScaSession {

    @Id
    @Column(name = "session_id", length = 36)
    private String sessionId;

    @Column(name = "account_holder_id", nullable = false, length = 50)
    private String accountHolderId;

    /** Dynamic link: payment amount — immutable after creation. */
    @Column(name = "linked_amount", precision = 18, scale = 2)
    private BigDecimal linkedAmount;

    /** Dynamic link: creditor name — immutable after creation. */
    @Column(name = "linked_creditor", length = 140)
    private String linkedCreditor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    private ScaStatus status;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected ScaSession() {} // JPA

    public ScaSession(String sessionId, String accountHolderId,
                      BigDecimal linkedAmount, String linkedCreditor) {
        this.sessionId = sessionId;
        this.accountHolderId = accountHolderId;
        this.linkedAmount = linkedAmount;
        this.linkedCreditor = linkedCreditor;
        this.status = ScaStatus.PENDING;
        this.failedAttempts = 0;
        this.createdAt = Instant.now();
        this.expiresAt = createdAt.plusSeconds(300); // 5-minute PSD2 timeout
    }

    /** Check if session is still valid for authentication. */
    public boolean isActive() {
        return status == ScaStatus.PENDING
                && Instant.now().isBefore(expiresAt)
                && !isLocked();
    }

    /** Check if locked out. */
    public boolean isLocked() {
        return lockedUntil != null && Instant.now().isBefore(lockedUntil);
    }

    /** Check if expired. */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /** Record a failed attempt. Locks after 3 failures. */
    public void recordFailedAttempt() {
        failedAttempts++;
        if (failedAttempts >= 3) {
            lockedUntil = Instant.now().plusSeconds(300); // 5-minute lockout
            status = ScaStatus.LOCKED;
        }
    }

    /** Complete SCA successfully. */
    public void complete() {
        this.status = ScaStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    /** Cancel (consent denied or expired). */
    public void cancel() {
        this.status = ScaStatus.CANCELLED;
    }

    // Getters
    public String getSessionId() { return sessionId; }
    public String getAccountHolderId() { return accountHolderId; }
    public BigDecimal getLinkedAmount() { return linkedAmount; }
    public String getLinkedCreditor() { return linkedCreditor; }
    public ScaStatus getStatus() { return status; }
    public int getFailedAttempts() { return failedAttempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getCompletedAt() { return completedAt; }

    public enum ScaStatus { PENDING, COMPLETED, LOCKED, CANCELLED, EXPIRED }
}
