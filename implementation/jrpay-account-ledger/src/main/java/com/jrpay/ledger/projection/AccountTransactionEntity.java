package com.jrpay.ledger.projection;

import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Read-side projection of a posted transaction leg. Composite key
 * {@code (accountId, transactionId)} mirrors the table's primary key so
 * each leg of a double-entry transfer surfaces as its own row.
 */
@Entity
@Table(name = "account_transactions")
@IdClass(AccountTransactionEntity.Pk.class)
public class AccountTransactionEntity {

    @Id
    @Column(name = "transaction_id", length = 36)
    private String transactionId;

    @Id
    @Column(name = "account_id", length = 34)
    private String accountId;

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

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    protected AccountTransactionEntity() {} // JPA

    public AccountTransactionEntity(String transactionId, String accountId, Direction direction,
                                    BigDecimal amount, String currency, String status,
                                    String counterpartyName, String counterpartyIban,
                                    String reference, String rejectionReason, Instant postedAt) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.counterpartyName = counterpartyName;
        this.counterpartyIban = counterpartyIban;
        this.reference = reference;
        this.rejectionReason = rejectionReason;
        this.postedAt = postedAt;
    }

    public String getTransactionId() { return transactionId; }
    public String getAccountId() { return accountId; }
    public Direction getDirection() { return direction; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public String getCounterpartyName() { return counterpartyName; }
    public String getCounterpartyIban() { return counterpartyIban; }
    public String getReference() { return reference; }
    public String getRejectionReason() { return rejectionReason; }
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
