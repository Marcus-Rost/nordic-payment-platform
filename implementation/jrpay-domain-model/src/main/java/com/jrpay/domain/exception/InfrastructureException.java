package com.jrpay.domain.exception;

/**
 * System failures — unexpected, operational issues.
 * Maps to 503 Service Unavailable. Triggers retries or circuit breakers.
 */
public abstract class InfrastructureException extends PaymentException {

    protected InfrastructureException(String message) {
        super(message);
    }

    protected InfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }
}
