package com.jrpay.psd2.customer.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Customer-scoped read-side row for a transaction leg. Composite key
 * {@code (accountId, transactionId)} mirrors the table.
 */
@Entity
@Table(name = "customer_transactions")
@IdClass(CustomerTransactionEntity.Pk.class)
public class CustomerTransactionEntity {

    @Id
    @Column(name = "transaction_id", length = 36)
    private String transactionId;

    @Id
    @Column(name = "account_id", length = 34)
    private String accountId;

    @Column(name = "customer_user_id", nullable = false)
    private UUID customerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 6)
    private Direction direction;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "counterparty_name", length = 140)
    private String counterpartyName;

    @Column(name = "counterparty_iban", length = 34)
    private String counterpartyIban;

    @Column(name = "reference", length = 140)
    private String reference;

    @Column(name = "rejection_reason", length = 40)
    private String rejectionReason;

    @Column(name = "description", length = 280)
    private String description;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    protected CustomerTransactionEntity() {}

    public CustomerTransactionEntity(String transactionId, String accountId, UUID customerUserId,
                                     Direction direction, BigDecimal amount, String currency,
                                     String status, String counterpartyName, String counterpartyIban,
                                     String reference, String description, Instant postedAt) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.customerUserId = customerUserId;
        this.direction = direction;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.counterpartyName = counterpartyName;
        this.counterpartyIban = counterpartyIban;
        this.reference = reference;
        this.description = description;
        this.postedAt = postedAt;
    }

    public String getTransactionId() { return transactionId; }
    public String getAccountId() { return accountId; }
    public UUID getCustomerUserId() { return customerUserId; }
    public Direction getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public String getCounterpartyName() { return counterpartyName; }
    public String getCounterpartyIban() { return counterpartyIban; }
    public String getReference() { return reference; }
    public String getRejectionReason() { return rejectionReason; }
    public String getDescription() { return description; }
    public Instant getPostedAt() { return postedAt; }

    public enum Direction { DEBIT, CREDIT }

    public static class Pk implements Serializable {
        private String transactionId;
        private String accountId;

        public Pk() {}
        public Pk(String transactionId, String accountId) {
            this.transactionId = transactionId;
            this.accountId = accountId;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Pk pk)) return false;
            return Objects.equals(transactionId, pk.transactionId)
                    && Objects.equals(accountId, pk.accountId);
        }

        @Override
        public int hashCode() { return Objects.hash(transactionId, accountId); }
    }
}
