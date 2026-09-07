package com.jrpay.domain.exception;

/** Account is frozen — debits are rejected. Maps to HTTP 422. */
public class AccountFrozenException extends DomainException {

    private final String accountId;

    public AccountFrozenException(String accountId) {
        super("Account " + accountId + " is frozen — debit operations are rejected");
        this.accountId = accountId;
    }

    public String getAccountId() { return accountId; }
}
