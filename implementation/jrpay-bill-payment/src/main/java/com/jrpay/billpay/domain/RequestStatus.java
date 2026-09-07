package com.jrpay.billpay.domain;

/**
 * R2P lifecycle states with enum-based transition enforcement.
 * Each state defines which transitions are valid.
 */
public enum RequestStatus {
    PENDING,
    ACCEPTED,
    DECLINED,
    DEFERRED,
    DEFER_FAILED,
    AMENDED,
    AMENDMENT_REQUESTED,
    REPLACED,
    CANCELLED_BY_MERCHANT,
    PAYMENT_INITIATED,
    SETTLED,
    REJECTED,
    EXPIRED,
    PAYMENT_HELD,
    RETURN_IN_PROGRESS,
    PAYMENT_RETURNED;

    /**
     * True if no further state transitions are possible.
     * Note: SETTLED is NOT terminal — it can transition to RETURN_IN_PROGRESS
     * (late sanctions hit triggers payment reversal).
     */
    public boolean isTerminal() {
        return this == DECLINED || this == EXPIRED || this == PAYMENT_RETURNED
                || this == REJECTED || this == REPLACED
                || this == CANCELLED_BY_MERCHANT;
    }

    /**
     * True when the customer has already taken a final-or-pending decision
     * on the request — used by R2P endpoints to gate against double-acts.
     * UC-G11/12/13/14 surface {@code urn:problem:r2p-already-acted} when
     * this returns true.
     */
    public boolean isAlreadyActed() {
        return this != PENDING;
    }

    /** True if the payment flow completed (settled or returned). */
    public boolean isCompleted() {
        return this == SETTLED || this == PAYMENT_RETURNED;
    }
}
