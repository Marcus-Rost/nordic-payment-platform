package com.jrpay.domain.exception;

/**
 * Business rule violations — expected, handled by business logic.
 * Maps to specific HTTP status codes via RFC 9457.
 */
public abstract class DomainException extends PaymentException {

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
