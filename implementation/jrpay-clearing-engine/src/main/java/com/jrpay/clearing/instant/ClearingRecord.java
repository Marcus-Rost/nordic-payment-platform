package com.jrpay.clearing.instant;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Tracks the state of each payment through the clearing pipeline.
 * One record per payment instruction.
 */
@Entity
@Table(name = "clearing_records")
public class ClearingRecord {

    @Id
    @Column(name = "transaction_id", length = 36)
    private String transactionId;

    @Column(name = "debtor_iban", nullable = false, length = 34)
    private String debtorIban;

    @Column(name = "creditor_iban", nullable = false, length = 34)
    private String creditorIban;

    @Column(name = "creditor_name", length = 140)
    private String creditorName;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "path_type", nullable = false, length = 10)
    private String pathType; // INSTANT or BATCH

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 25)
    private PaymentState status;

    @Column(name = "rejection_reason", length = 50)
    private String rejectionReason;

    @Column(name = "reservation_id", length = 64)
    private String reservationId;

    @Column(name = "vop_result", length = 20)
    private String vopResult;

    @Column(name = "vop_payer_override")
    private Boolean vopPayerOverride;

    @Column(name = "fraud_score")
    private Double fraudScore;

    @Column(name = "sanctions_result", length = 20)
    private String sanctionsResult;

    @Column(name = "duplicate_override")
    private Boolean duplicateOverride;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ClearingRecord() {} // JPA

    public ClearingRecord(String transactionId, String debtorIban, String creditorIban,
                          String creditorName, BigDecimal amount, String currency,
                          String pathType, String vopResult, Boolean vopPayerOverride) {
        this.transactionId = transactionId;
        this.debtorIban = debtorIban;
        this.creditorIban = creditorIban;
        this.creditorName = creditorName;
        this.amount = amount;
        this.currency = currency;
        this.pathType = pathType;
        this.status = PaymentState.RECEIVED;
        this.vopResult = vopResult;
        this.vopPayerOverride = vopPayerOverride;
        this.receivedAt = Instant.now();
        this.updatedAt = this.receivedAt;
    }

    public void transitionTo(PaymentState newState) {
        if (this.status.isTerminal()) {
            throw new IllegalStateException(
                    "Cannot transition from terminal state " + this.status);
        }
        this.status = newState;
        this.updatedAt = Instant.now();
        if (newState == PaymentState.SETTLED) {
            this.settledAt = Instant.now();
        }
    }

    public void reject(String reason) {
        if (this.status.isTerminal()) {
            // Defense-in-depth: cannot reject an already-terminal payment
            return;
        }
        this.status = PaymentState.REJECTED;
        this.rejectionReason = reason;
        this.updatedAt = Instant.now();
    }

    // Getters
    public String getTransactionId() { return transactionId; }
    public String getDebtorIban() { return debtorIban; }
    public String getCreditorIban() { return creditorIban; }
    public String getCreditorName() { return creditorName; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getPathType() { return pathType; }
    public PaymentState getStatus() { return status; }
    public String getRejectionReason() { return rejectionReason; }
    public String getReservationId() { return reservationId; }
    public String getVopResult() { return vopResult; }
    public Boolean getVopPayerOverride() { return vopPayerOverride; }
    public Double getFraudScore() { return fraudScore; }
    public String getSanctionsResult() { return sanctionsResult; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getSettledAt() { return settledAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setReservationId(String reservationId) { this.reservationId = reservationId; }
    public void setFraudScore(Double fraudScore) { this.fraudScore = fraudScore; }
    public void setSanctionsResult(String sanctionsResult) { this.sanctionsResult = sanctionsResult; }
}
