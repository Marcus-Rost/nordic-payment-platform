package com.jrpay.ledger.domain;

import com.jrpay.domain.money.Currency;
import com.jrpay.domain.money.MonetaryAmount;

import java.time.Instant;
import java.util.Objects;

/**
 * Account aggregate — the core domain entity.
 * Represents the current state of an account as derived from events.
 * Immutable — state changes produce new instances.
 */
public final class Account {

    private final String accountId;
    private final String holderName;
    private final Currency currency;
    private final MonetaryAmount bookedBalance;
    private final MonetaryAmount reservedAmount;
    private final AccountStatus status;
    private final Instant createdAt;
    private final Instant updatedAt;

    public Account(String accountId, String holderName, Currency currency,
                   MonetaryAmount bookedBalance, MonetaryAmount reservedAmount,
                   AccountStatus status, Instant createdAt, Instant updatedAt) {
        this.accountId = Objects.requireNonNull(accountId);
        this.holderName = Objects.requireNonNull(holderName);
        this.currency = Objects.requireNonNull(currency);
        this.bookedBalance = Objects.requireNonNull(bookedBalance);
        this.reservedAmount = Objects.requireNonNull(reservedAmount);
        this.status = Objects.requireNonNull(status);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    /** Available balance = booked - reserved. Always non-negative. */
    public MonetaryAmount getAvailableBalance() {
        return bookedBalance.subtract(reservedAmount);
    }

    /** Check if a reservation for the given amount can be made. */
    public boolean canReserve(MonetaryAmount amount) {
        return status == AccountStatus.ACTIVE
                && getAvailableBalance().isGreaterThanOrEqual(amount);
    }

    /** Check if debits are allowed (not frozen, not closed). */
    public boolean canDebit() {
        return status == AccountStatus.ACTIVE;
    }

    /** Credits are allowed on active and frozen accounts (not closed). */
    public boolean canCredit() {
        return status != AccountStatus.CLOSED;
    }

    // Getters
    public String getAccountId() { return accountId; }
    public String getHolderName() { return holderName; }
    public Currency getCurrency() { return currency; }
    public MonetaryAmount getBookedBalance() { return bookedBalance; }
    public MonetaryAmount getReservedAmount() { return reservedAmount; }
    public AccountStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    @Override
    public String toString() {
        return "Account{id=" + accountId + ", holder=" + holderName
                + ", balance=" + bookedBalance + ", reserved=" + reservedAmount
                + ", available=" + getAvailableBalance()
                + ", status=" + status + "}";
    }
}
