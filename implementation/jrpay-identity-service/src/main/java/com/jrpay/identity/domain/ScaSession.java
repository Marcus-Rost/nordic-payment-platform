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
 * SCA challenge session: created on {@code POST /sca/start}, terminates with
 * {@code VERIFIED} on a correct proof or {@code EXPIRED}/{@code FAILED}.
 *
 * <p>The {@code dlHash} is computed by {@link com.jrpay.identity.sca.DynamicLinkingHash}
 * over the canonical-JSON inputs and binds any subsequent step-up token to the
 * intended operation. See {@code gui/architecture/DYNAMIC_LINKING.md}.
 */
@Entity
@Table(name = "identity_sca_sessions")
public class ScaSession {

    @Id
    @Column(name = "sca_session_id", nullable = false, updatable = false)
    private UUID scaSessionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "dl_class", nullable = false, length = 32)
    private String dlClass;

    @Column(name = "dl_hash", nullable = false, length = 64)
    private String dlHash;

    @Column(name = "canonical_input", nullable = false, columnDefinition = "TEXT")
    private String canonicalInput;

    @Enumerated(EnumType.STRING)
    @Column(name = "factor_type", nullable = false, length = 32)
    private ScaFactor.FactorType factorType;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "exempt", nullable = false)
    private boolean exempt;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private OffsetDateTime issuedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    protected ScaSession() {
    }

    public ScaSession(UUID scaSessionId, UUID userId, String dlClass, String dlHash,
                      String canonicalInput, ScaFactor.FactorType factorType,
                      OffsetDateTime expiresAt, boolean exempt) {
        this.scaSessionId = scaSessionId;
        this.userId = userId;
        this.dlClass = dlClass;
        this.dlHash = dlHash;
        this.canonicalInput = canonicalInput;
        this.factorType = factorType;
        this.expiresAt = expiresAt;
        this.exempt = exempt;
        this.state = exempt ? State.VERIFIED : State.PENDING;
        this.attempts = 0;
        OffsetDateTime now = OffsetDateTime.now();
        this.issuedAt = now;
        if (exempt) {
            this.verifiedAt = now;
        }
    }

    public UUID getScaSessionId() {
        return scaSessionId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getDlClass() {
        return dlClass;
    }

    public String getDlHash() {
        return dlHash;
    }

    public String getCanonicalInput() {
        return canonicalInput;
    }

    public ScaFactor.FactorType getFactorType() {
        return factorType;
    }

    public State getState() {
        return state;
    }

    public int getAttempts() {
        return attempts;
    }

    public boolean isExempt() {
        return exempt;
    }

    public OffsetDateTime getIssuedAt() {
        return issuedAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public OffsetDateTime getVerifiedAt() {
        return verifiedAt;
    }

    public void recordAttempt() {
        this.attempts += 1;
    }

    public void markVerified() {
        this.state = State.VERIFIED;
        this.verifiedAt = OffsetDateTime.now();
    }

    public void markFailed() {
        this.state = State.FAILED;
    }

    public void markConsumed() {
        this.state = State.CONSUMED;
    }

    public void markExpired() {
        this.state = State.EXPIRED;
    }

    public boolean isExpired() {
        return expiresAt.isBefore(OffsetDateTime.now());
    }

    public enum State {
        PENDING, VERIFIED, CONSUMED, FAILED, EXPIRED
    }
}
