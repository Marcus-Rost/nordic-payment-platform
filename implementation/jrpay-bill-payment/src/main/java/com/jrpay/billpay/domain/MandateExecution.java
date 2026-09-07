package com.jrpay.billpay.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One debit attempt under a mandate. Surfaced to the customer via UC-G09 §6.2;
 * settled rows link to the ledger transaction so the UI can deep-link to UC-G03.
 */
@Entity
@Table(name = "mandate_executions")
public class MandateExecution {

    @Id
    @Column(name = "execution_id")
    private UUID executionId;

    @Column(name = "mandate_id", nullable = false, length = 36)
    private String mandateId;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "transaction_id", length = 36)
    private String transactionId;

    @Column(name = "rejection_reason", length = 40)
    private String rejectionReason;

    @Column(name = "invoice_reference", length = 140)
    private String invoiceReference;

    protected MandateExecution() {} // JPA

    public MandateExecution(UUID executionId, String mandateId, BigDecimal amount, String currency,
                            String status, Instant attemptedAt, String invoiceReference) {
        this.executionId = executionId;
        this.mandateId = mandateId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.attemptedAt = attemptedAt;
        this.invoiceReference = invoiceReference;
    }

    public UUID getExecutionId() { return executionId; }
    public String getMandateId() { return mandateId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public Instant getAttemptedAt() { return attemptedAt; }
    public Instant getSettledAt() { return settledAt; }
    public String getTransactionId() { return transactionId; }
    public String getRejectionReason() { return rejectionReason; }
    public String getInvoiceReference() { return invoiceReference; }

    public void markSettled(String transactionId) {
        this.status = "SETTLED";
        this.settledAt = Instant.now();
        this.transactionId = transactionId;
    }

    public void markRejected(String rejectionReason) {
        this.status = "REJECTED";
        this.rejectionReason = rejectionReason;
    }
}
