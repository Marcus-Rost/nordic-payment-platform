package com.jrpay.domain.exception;

import com.jrpay.domain.money.MonetaryAmount;

/** Insufficient balance for the requested operation. Maps to HTTP 422. */
public class InsufficientBalanceException extends DomainException {

    private final String accountId;
    private final MonetaryAmount available;
    private final MonetaryAmount requested;

    public InsufficientBalanceException(String accountId, MonetaryAmount available, MonetaryAmount requested) {
        super("Account " + accountId + " has available balance " + available
                + ", requested " + requested);
        this.accountId = accountId;
        this.available = available;
        this.requested = requested;
    }

    public String getAccountId() { return accountId; }
    public MonetaryAmount getAvailable() { return available; }
    public MonetaryAmount getRequested() { return requested; }
}
