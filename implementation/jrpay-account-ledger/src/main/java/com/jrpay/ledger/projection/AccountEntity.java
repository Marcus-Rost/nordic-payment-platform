package com.jrpay.ledger.projection;

import com.jrpay.ledger.domain.AccountStatus;
import com.jrpay.ledger.domain.AccountType;
import com.jrpay.ledger.domain.FreezeReason;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * PostgreSQL projection of account state. Derived from events — rebuildable.
 * Backs both the customer-facing read endpoints and (in Phase 2I) the admin
 * search / detail / mutation flows.
 */
@Entity
@Table(name = "accounts")
public class AccountEntity {

    @Id
    @Column(name = "account_id", length = 34)
    private String accountId;

    @Column(name = "holder_name", nullable = false, length = 140)
    private String holderName;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "booked_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal bookedBalance;

    @Column(name = "reserved_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal reservedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Phase 2I additions
    @Column(name = "customer_id", length = 36)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 16)
    private AccountType accountType = AccountType.CHECKING;

    @Column(name = "iban", length = 34)
    private String iban;

    @Column(name = "bic", length = 11)
    private String bic;

    @Column(name = "holder_address", length = 500)
    private String holderAddress;

    @Column(name = "fodselsnummer", length = 11)
    private String fodselsnummer;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "freeze_reason", length = 40)
    private FreezeReason freezeReason;

    @Column(name = "freeze_reference", length = 140)
    private String freezeReference;

    @Column(name = "freeze_applied_at")
    private Instant freezeAppliedAt;

    @Column(name = "freeze_applied_by", length = 160)
    private String freezeAppliedBy;

    @Column(name = "freeze_notes_summary", length = 140)
    private String freezeNotesSummary;

    protected AccountEntity() {} // JPA

    public AccountEntity(String accountId, String holderName, String currency,
                         BigDecimal bookedBalance, BigDecimal reservedAmount,
                         AccountStatus status, Instant createdAt, Instant updatedAt) {
        this.accountId = accountId;
        this.holderName = holderName;
        this.currency = currency;
        this.bookedBalance = bookedBalance;
        this.reservedAmount = reservedAmount;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.openedAt = createdAt;
    }

    public BigDecimal getAvailableBalance() {
        return bookedBalance.subtract(reservedAmount);
    }

    public String getAccountId() { return accountId; }
    public String getHolderName() { return holderName; }
    public String getCurrency() { return currency; }
    public BigDecimal getBookedBalance() { return bookedBalance; }
    public BigDecimal getReservedAmount() { return reservedAmount; }
    public AccountStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public AccountType getAccountType() { return accountType; }
    public void setAccountType(AccountType accountType) { this.accountType = accountType; }

    public String getIban() { return iban; }
    public void setIban(String iban) { this.iban = iban; }

    public String getBic() { return bic; }
    public void setBic(String bic) { this.bic = bic; }

    public String getHolderAddress() { return holderAddress; }
    public void setHolderAddress(String holderAddress) { this.holderAddress = holderAddress; }

    public String getFodselsnummer() { return fodselsnummer; }
    public void setFodselsnummer(String fodselsnummer) { this.fodselsnummer = fodselsnummer; }

    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant openedAt) { this.openedAt = openedAt; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }

    public FreezeReason getFreezeReason() { return freezeReason; }
    public void setFreezeReason(FreezeReason freezeReason) { this.freezeReason = freezeReason; }

    public String getFreezeReference() { return freezeReference; }
    public void setFreezeReference(String freezeReference) { this.freezeReference = freezeReference; }

    public Instant getFreezeAppliedAt() { return freezeAppliedAt; }
    public void setFreezeAppliedAt(Instant freezeAppliedAt) { this.freezeAppliedAt = freezeAppliedAt; }

    public String getFreezeAppliedBy() { return freezeAppliedBy; }
    public void setFreezeAppliedBy(String freezeAppliedBy) { this.freezeAppliedBy = freezeAppliedBy; }

    public String getFreezeNotesSummary() { return freezeNotesSummary; }
    public void setFreezeNotesSummary(String freezeNotesSummary) { this.freezeNotesSummary = freezeNotesSummary; }

    public void setBookedBalance(BigDecimal bookedBalance) { this.bookedBalance = bookedBalance; }
    public void setReservedAmount(BigDecimal reservedAmount) { this.reservedAmount = reservedAmount; }
    public void setStatus(AccountStatus status) { this.status = status; }
    public void setHolderName(String holderName) { this.holderName = holderName; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
