package com.jrpay.psd2.consent;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Set;

/**
 * PSD2 consent — explicit authorization from account holder to a TPP.
 * Specifies which accounts and scopes the TPP can access, and for how long.
 */
@Entity
@Table(name = "consents")
public class Consent {

    @Id
    @Column(name = "consent_id", length = 36)
    private String consentId;

    @Column(name = "tpp_id", nullable = false, length = 50)
    private String tppId;

    @Column(name = "account_holder_id", nullable = false, length = 50)
    private String accountHolderId;

    @Column(name = "granted_scopes", nullable = false, length = 255)
    private String grantedScopes; // comma-separated: "accounts:read,transactions:read"

    @Column(name = "granted_accounts", length = 500)
    private String grantedAccounts; // comma-separated IBANs

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private ConsentStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected Consent() {} // JPA

    public Consent(String consentId, String tppId, String accountHolderId,
                   Set<String> scopes, Set<String> accounts, Instant expiresAt) {
        this.consentId = consentId;
        this.tppId = tppId;
        this.accountHolderId = accountHolderId;
        this.grantedScopes = String.join(",", scopes);
        this.grantedAccounts = accounts != null ? String.join(",", accounts) : null;
        this.status = ConsentStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    /** Check if this consent covers a requested scope. */
    public boolean hasScope(String scope) {
        return grantedScopes != null && Set.of(grantedScopes.split(",")).contains(scope);
    }

    /** Check if this consent covers a requested account. */
    public boolean coversAccount(String accountId) {
        if (grantedAccounts == null || grantedAccounts.isBlank()) {
            return true; // all accounts granted
        }
        return Set.of(grantedAccounts.split(",")).contains(accountId);
    }

    /** Check if consent is valid (active + not expired). */
    public boolean isValid() {
        return status == ConsentStatus.ACTIVE && Instant.now().isBefore(expiresAt);
    }

    /** Revoke this consent — immediate effect. */
    public void revoke() {
        this.status = ConsentStatus.REVOKED;
        this.revokedAt = Instant.now();
    }

    // Getters
    public String getConsentId() { return consentId; }
    public String getTppId() { return tppId; }
    public String getAccountHolderId() { return accountHolderId; }
    public String getGrantedScopes() { return grantedScopes; }
    public ConsentStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }

    public enum ConsentStatus { ACTIVE, REVOKED, EXPIRED }
}
