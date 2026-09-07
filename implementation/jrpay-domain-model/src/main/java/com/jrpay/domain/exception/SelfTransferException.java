package com.jrpay.domain.exception;

/**
 * Thrown when a payment instruction has the same debit and credit accounts.
 * Self-transfers have no economic meaning (net change = 0) and would
 * collide on the projection's composite primary key.
 *
 * <p>Maps to {@code 422 Unprocessable Entity} via the global
 * {@code PaymentExceptionHandler} that handles {@code DomainException}.
 */
public class SelfTransferException extends DomainException {
    public SelfTransferException(String accountId) {
        super("Self-transfer rejected: debit and credit accounts are identical (" + accountId + ")");
    }
}
