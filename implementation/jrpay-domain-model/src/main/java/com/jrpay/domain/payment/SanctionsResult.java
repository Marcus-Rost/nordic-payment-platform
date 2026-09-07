package com.jrpay.domain.payment;

/**
 * Sanctions screening result from A5. Defined in INTERFACE_CONTRACTS.md Section 1.4.
 */
public enum SanctionsResult {
    /** No sanctions match. */
    CLEAR,
    /** Sanctions match found — payment must be blocked. */
    HIT,
    /** Sanctions screening could not be performed. */
    UNAVAILABLE
}
