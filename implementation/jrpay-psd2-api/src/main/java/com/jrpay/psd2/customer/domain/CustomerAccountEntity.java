package com.jrpay.psd2.customer.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-side projection of A1's account state, scoped per customer.
 * Updated by the {@code ledger.events} Kafka consumer.
 */
@Entity
@Table(name = "customer_accounts")
public class CustomerAccountEntity {

    @Id
    @Column(name = "account_id", length = 34)
    private String accountId;

    @Column(name = "customer_user_id", nullable = false)
    private UUID customerUserId;

    @Column(name = "iban", length = 34)
    private String iban;

    @Column(name = "bic", length = 11)
    private String bic;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 16)
    private AccountType accountType = AccountType.CHECKING;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "booked_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal bookedBalance = BigDecimal.ZERO;

    @Column(name = "reserved_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal reservedAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.ACTIVE;

    @Column(name = "nickname", length = 140)
    private String nickname;

    @Column(name = "is_primary", nullable = false)
    private boolean primary;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CustomerAccountEntity() {}

    public CustomerAccountEntity(String accountId, UUID customerUserId, String currency) {
        this.accountId = accountId;
        this.customerUserId = customerUserId;
        this.currency = currency;
        Instant now = Instant.now();
        this.openedAt = now;
        this.updatedAt = now;
    }

    public BigDecimal getAvailableBalance() {
        return bookedBalance.subtract(reservedAmount);
    }

    public String getAccountId() { return accountId; }
    public UUID getCustomerUserId() { return customerUserId; }
    public String getIban() { return iban; }
    public String getBic() { return bic; }
    public AccountType getAccountType() { return accountType; }
    public String getCurrency() { return currency; }
    public BigDecimal getBookedBalance() { return bookedBalance; }
    public BigDecimal getReservedAmount() { return reservedAmount; }
    public Status getStatus() { return status; }
    public String getNickname() { return nickname; }
    public boolean isPrimary() { return primary; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getClosedAt() { return closedAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setIban(String v) { this.iban = v; }
    public void setBic(String v) { this.bic = v; }
    public void setAccountType(AccountType v) { this.accountType = v; }
    public void setNickname(String v) { this.nickname = v; }
    public void setPrimary(boolean v) { this.primary = v; }
    public void setBookedBalance(BigDecimal v) { this.bookedBalance = v; this.updatedAt = Instant.now(); }
    public void setReservedAmount(BigDecimal v) { this.reservedAmount = v; this.updatedAt = Instant.now(); }
    public void setStatus(Status v) { this.status = v; this.updatedAt = Instant.now(); }
    public void setClosedAt(Instant v) { this.closedAt = v; }

    public enum AccountType { CHECKING, SAVINGS, CREDIT }
    public enum Status { ACTIVE, FROZEN, CLOSED }
}
