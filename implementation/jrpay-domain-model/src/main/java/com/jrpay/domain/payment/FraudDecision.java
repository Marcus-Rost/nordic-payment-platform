package com.jrpay.domain.payment;

/**
 * Fraud scoring decision from A5. Defined in INTERFACE_CONTRACTS.md Section 1.3.
 */
public enum FraudDecision {
    /** Below fraud threshold, payment may proceed. */
    PASS,
    /** Above fraud threshold, payment should be rejected. */
    BLOCK,
    /** Borderline, payment proceeds but flagged for post-settlement review. */
    REVIEW,
    /** A5 could not complete scoring (e.g., graceful shutdown). */
    TIMEOUT
}
