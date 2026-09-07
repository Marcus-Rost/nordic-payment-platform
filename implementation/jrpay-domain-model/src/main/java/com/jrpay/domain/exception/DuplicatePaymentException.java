package com.jrpay.domain.exception;

/** Semantic duplicate payment detected. Maps to HTTP 409. */
public class DuplicatePaymentException extends DomainException {

    private final String transactionId;
    private final String fingerprint;

    public DuplicatePaymentException(String transactionId, String fingerprint) {
        super("Duplicate payment detected for transaction " + transactionId
                + " (fingerprint " + fingerprint + ")");
        this.transactionId = transactionId;
        this.fingerprint = fingerprint;
    }

    public String getTransactionId() { return transactionId; }
    public String getFingerprint() { return fingerprint; }
}
