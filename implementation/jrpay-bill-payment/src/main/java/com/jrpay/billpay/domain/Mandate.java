package com.jrpay.billpay.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * AvtaleGiro mandate. Backs the customer-mandates surface (UC-G08, UC-G09).
 * Lifecycle: {@code PENDING_SCA → ACTIVE} on SCA verify; pause / resume; revoke.
 */
@Entity
@Table(name = "mandates")
public class Mandate {

    @Id
    @Column(name = "mandate_id", length = 36)
    private String mandateId;

    @Column(name = "merchant_id", nullable = false, length = 50)
    private String merchantId;

    @Column(name = "customer_user_id")
    private UUID customerUserId;

    @Column(name = "debtor_iban", nullable = false, length = 34)
    private String debtorIban;

    @Column(name = "debtor_account_id", length = 34)
    private String debtorAccountId;

    /**
     * Legacy column kept for the existing R2P mandate-auto-accept logic;
     * mirrors {@link #maxAmount}.
     */
    @Column(name = "max_single_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal maxSingleAmount;

    @Column(name = "max_amount", precision = 18, scale = 2)
    private BigDecimal maxAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", length = 16)
    private MandateFrequency frequency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MandateStatus status;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "notify_before_debit", nullable = false)
    private boolean notifyBeforeDebit = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "resume_at")
    private Instant resumeAt;

    @Column(name = "sca_session_id")
    private UUID scaSessionId;

    @Column(name = "dl_hash", length = 64)
    private String dlHash;

    protected Mandate() {} // JPA

    public Mandate(String mandateId, String merchantId, String debtorIban,
                   BigDecimal maxSingleAmount, String currency) {
        this.mandateId = mandateId;
        this.merchantId = merchantId;
        this.debtorIban = debtorIban;
        this.maxSingleAmount = maxSingleAmount;
        this.maxAmount = maxSingleAmount;
        this.currency = currency;
        this.status = MandateStatus.ACTIVE;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Mandate(String mandateId, String merchantId, UUID customerUserId, String debtorAccountId,
                   String debtorIban, BigDecimal maxAmount, String currency,
                   MandateFrequency frequency, LocalDate startDate, LocalDate endDate,
                   boolean notifyBeforeDebit, UUID scaSessionId, String dlHash) {
        this.mandateId = mandateId;
        this.merchantId = merchantId;
        this.customerUserId = customerUserId;
        this.debtorAccountId = debtorAccountId;
        this.debtorIban = debtorIban;
        this.maxAmount = maxAmount;
        this.maxSingleAmount = maxAmount;
        this.currency = currency;
        this.frequency = frequency;
        this.startDate = startDate;
        this.endDate = endDate;
        this.notifyBeforeDebit = notifyBeforeDebit;
        this.status = MandateStatus.PENDING_SCA;
        this.scaSessionId = scaSessionId;
        this.dlHash = dlHash;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean canAutoAccept(String requestMerchantId, BigDecimal requestAmount) {
        BigDecimal cap = maxAmount != null ? maxAmount : maxSingleAmount;
        return status == MandateStatus.ACTIVE
                && merchantId.equals(requestMerchantId)
                && requestAmount.compareTo(cap) <= 0;
    }

    public void activate() {
        this.status = MandateStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void pause(Instant resumeAt) {
        this.status = MandateStatus.PAUSED;
        this.pausedAt = Instant.now();
        this.resumeAt = resumeAt;
        this.updatedAt = this.pausedAt;
    }

    public void resume() {
        this.status = MandateStatus.ACTIVE;
        this.pausedAt = null;
        this.resumeAt = null;
        this.updatedAt = Instant.now();
    }

    public void revoke() {
        this.status = MandateStatus.REVOKED;
        this.revokedAt = Instant.now();
        this.updatedAt = this.revokedAt;
    }

    public String getMandateId() { return mandateId; }
    public String getMerchantId() { return merchantId; }
    public UUID getCustomerUserId() { return customerUserId; }
    public String getDebtorIban() { return debtorIban; }
    public String getDebtorAccountId() { return debtorAccountId; }
    public BigDecimal getMaxSingleAmount() { return maxSingleAmount; }
    public BigDecimal getMaxAmount() { return maxAmount != null ? maxAmount : maxSingleAmount; }
    public String getCurrency() { return currency; }
    public MandateFrequency getFrequency() { return frequency; }
    public MandateStatus getStatus() { return status; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public boolean isNotifyBeforeDebit() { return notifyBeforeDebit; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Instant getPausedAt() { return pausedAt; }
    public Instant getResumeAt() { return resumeAt; }
    public UUID getScaSessionId() { return scaSessionId; }
    public String getDlHash() { return dlHash; }

    public void setMaxAmount(BigDecimal maxAmount) {
        this.maxAmount = maxAmount;
        this.maxSingleAmount = maxAmount;
        this.updatedAt = Instant.now();
    }

    public void setNotifyBeforeDebit(boolean v) {
        this.notifyBeforeDebit = v;
        this.updatedAt = Instant.now();
    }

    public void setScaSessionId(UUID v) { this.scaSessionId = v; }
    public void setDlHash(String v) { this.dlHash = v; }

    public enum MandateStatus { PENDING_SCA, ACTIVE, PAUSED, REVOKED, EXPIRED }
}
