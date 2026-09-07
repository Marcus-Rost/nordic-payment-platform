package com.jrpay.psd2.customer.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A4-side payment record. Lifecycle:
 * <pre>
 *   PENDING_SCA → PENDING_EXECUTION → AWAITING_CLEARING → SETTLING → SETTLED
 *                                                       ↘ HELD → SETTLED / REJECTED
 *                                                       ↘ REJECTED / RETURNED
 *   PENDING_SCA → CANCELLED (pre-SCA only)
 * </pre>
 */
@Entity
@Table(name = "customer_payments")
public class CustomerPaymentEntity {

    @Id
    @Column(name = "payment_id", length = 36)
    private String paymentId;

    @Column(name = "customer_user_id", nullable = false)
    private UUID customerUserId;

    @Column(name = "debtor_account_id", nullable = false, length = 34)
    private String debtorAccountId;

    @Column(name = "creditor_iban", nullable = false, length = 34)
    private String creditorIban;

    @Column(name = "creditor_name", nullable = false, length = 140)
    private String creditorName;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "reference", length = 140)
    private String reference;

    @Column(name = "description", length = 280)
    private String description;

    @Column(name = "execution_mode", nullable = false, length = 16)
    private String executionMode;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "sca_session_id")
    private UUID scaSessionId;

    @Column(name = "dl_hash", length = 64)
    private String dlHash;

    @Column(name = "step_up_token_hash", length = 64)
    private String stepUpTokenHash;

    @Column(name = "vop_result", length = 16)
    private String vopResult;

    @Column(name = "vop_payer_override")
    private Boolean vopPayerOverride;

    @Column(name = "rejection_reason", length = 40)
    private String rejectionReason;

    @Column(name = "transaction_id", length = 36)
    private String transactionId;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "last_status_refreshed_at")
    private Instant lastStatusRefreshedAt;

    @Column(name = "idempotency_key", length = 64, unique = true)
    private String idempotencyKey;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CustomerPaymentEntity() {}

    public CustomerPaymentEntity(String paymentId, UUID customerUserId, String debtorAccountId,
                                 String creditorIban, String creditorName, BigDecimal amount,
                                 String currency, String reference, String description,
                                 String executionMode, String idempotencyKey, String requestHash) {
        this.paymentId = paymentId;
        this.customerUserId = customerUserId;
        this.debtorAccountId = debtorAccountId;
        this.creditorIban = creditorIban;
        this.creditorName = creditorName;
        this.amount = amount;
        this.currency = currency;
        this.reference = reference;
        this.description = description;
        this.executionMode = executionMode;
        this.status = "PENDING_SCA";
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getPaymentId() { return paymentId; }
    public UUID getCustomerUserId() { return customerUserId; }
    public String getDebtorAccountId() { return debtorAccountId; }
    public String getCreditorIban() { return creditorIban; }
    public String getCreditorName() { return creditorName; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReference() { return reference; }
    public String getDescription() { return description; }
    public String getExecutionMode() { return executionMode; }
    public String getStatus() { return status; }
    public UUID getScaSessionId() { return scaSessionId; }
    public String getDlHash() { return dlHash; }
    public String getStepUpTokenHash() { return stepUpTokenHash; }
    public String getVopResult() { return vopResult; }
    public Boolean getVopPayerOverride() { return vopPayerOverride; }
    public String getRejectionReason() { return rejectionReason; }
    public String getTransactionId() { return transactionId; }
    public Instant getSettledAt() { return settledAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Instant getLastStatusRefreshedAt() { return lastStatusRefreshedAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setStatus(String status) { this.status = status; this.updatedAt = Instant.now(); }
    public void setScaSessionId(UUID v) { this.scaSessionId = v; }
    public void setDlHash(String v) { this.dlHash = v; }
    public void setStepUpTokenHash(String v) { this.stepUpTokenHash = v; }
    public void setVopResult(String v) { this.vopResult = v; }
    public void setVopPayerOverride(Boolean v) { this.vopPayerOverride = v; }
    public void setRejectionReason(String v) { this.rejectionReason = v; }
    public void setTransactionId(String v) { this.transactionId = v; }
    public void setSettledAt(Instant v) { this.settledAt = v; }
    public void setCancelledAt(Instant v) { this.cancelledAt = v; }
    public void setLastStatusRefreshedAt(Instant v) { this.lastStatusRefreshedAt = v; }
}
