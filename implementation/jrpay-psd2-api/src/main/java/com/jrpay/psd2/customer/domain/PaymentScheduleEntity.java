package com.jrpay.psd2.customer.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_schedules")
public class PaymentScheduleEntity {

    @Id
    @Column(name = "schedule_id", length = 36)
    private String scheduleId;

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

    @Column(name = "execute_at", nullable = false)
    private Instant executeAt;

    @Column(name = "execution_mode", nullable = false, length = 16)
    private String executionMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status;

    @Column(name = "sca_session_id")
    private UUID scaSessionId;

    @Column(name = "dl_hash", length = 64)
    private String dlHash;

    @Column(name = "executed_payment_id", length = 36)
    private String executedPaymentId;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "failure_reason", length = 40)
    private String failureReason;

    @Column(name = "note", length = 280)
    private String note;

    @Column(name = "idempotency_key", length = 64, unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentScheduleEntity() {}

    public PaymentScheduleEntity(String scheduleId, UUID customerUserId, String debtorAccountId,
                                 String creditorIban, String creditorName, BigDecimal amount,
                                 String currency, String reference, Instant executeAt,
                                 String executionMode, UUID scaSessionId, String dlHash,
                                 String note, String idempotencyKey) {
        this.scheduleId = scheduleId;
        this.customerUserId = customerUserId;
        this.debtorAccountId = debtorAccountId;
        this.creditorIban = creditorIban;
        this.creditorName = creditorName;
        this.amount = amount;
        this.currency = currency;
        this.reference = reference;
        this.executeAt = executeAt;
        this.executionMode = executionMode;
        this.status = Status.PENDING_SCA;
        this.scaSessionId = scaSessionId;
        this.dlHash = dlHash;
        this.note = note;
        this.idempotencyKey = idempotencyKey;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getScheduleId() { return scheduleId; }
    public UUID getCustomerUserId() { return customerUserId; }
    public String getDebtorAccountId() { return debtorAccountId; }
    public String getCreditorIban() { return creditorIban; }
    public String getCreditorName() { return creditorName; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReference() { return reference; }
    public Instant getExecuteAt() { return executeAt; }
    public String getExecutionMode() { return executionMode; }
    public Status getStatus() { return status; }
    public UUID getScaSessionId() { return scaSessionId; }
    public String getDlHash() { return dlHash; }
    public String getExecutedPaymentId() { return executedPaymentId; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Instant getExecutedAt() { return executedAt; }
    public String getFailureReason() { return failureReason; }
    public String getNote() { return note; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void activate() {
        this.status = Status.SCHEDULED;
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        this.status = Status.CANCELLED;
        this.cancelledAt = Instant.now();
        this.updatedAt = this.cancelledAt;
    }

    public void markExecuted(String paymentId) {
        this.status = Status.EXECUTED;
        this.executedAt = Instant.now();
        this.executedPaymentId = paymentId;
        this.updatedAt = this.executedAt;
    }

    public enum Status { PENDING_SCA, SCHEDULED, EXECUTED, CANCELLED, EXECUTION_FAILED }
}
