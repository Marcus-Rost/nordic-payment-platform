package com.jrpay.domain.exception;

/**
 * Base exception for all payment system exceptions.
 * Two subtypes: DomainException (business rule violations) and
 * InfrastructureException (system failures).
 */
public abstract class PaymentException extends RuntimeException {

    protected PaymentException(String message) {
        super(message);
    }

    protected PaymentException(String message, Throwable cause) {
        super(message, cause);
    }
}
