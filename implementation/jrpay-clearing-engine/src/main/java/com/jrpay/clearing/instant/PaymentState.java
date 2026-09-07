package com.jrpay.clearing.instant;

/**
 * Instant payment state machine states.
 * Strict linear progression: RECEIVED → AWAITING_RESERVATION → AWAITING_FRAUD →
 * SETTLING → SETTLED (or REJECTED at any point).
 * No step can be skipped. Sanctions screening cannot be bypassed.
 */
public enum PaymentState {
    /** Payment instruction received, validation pending. */
    RECEIVED,
    /** Balance reservation requested from A1, awaiting response. */
    AWAITING_RESERVATION,
    /** Reservation confirmed, fraud/sanctions check requested from A5. */
    AWAITING_FRAUD,
    /** Fraud cleared, settlement in progress. */
    SETTLING,
    /** Terminal: payment settled successfully. */
    SETTLED,
    /** Terminal: payment rejected (insufficient balance, sanctions, timeout, etc.). */
    REJECTED;

    public boolean isTerminal() {
        return this == SETTLED || this == REJECTED;
    }
}
