package com.jrpay.domain.payment;

/**
 * Canonical payment status values published by A2 on payment.status-updates.
 * Consumed by A3, A4. All consumers must handle ALL values.
 * Defined in INTERFACE_CONTRACTS.md Section 1.1.
 */
public enum PaymentStatus {

    /** Payment completed successfully, funds transferred. */
    SETTLED,

    /** Payment rejected (sanctions hit, insufficient balance, timeout, etc.). */
    REJECTED,

    /** Payment held for manual review (batch path only). */
    HELD,

    /** Reversal in progress (late sanctions hit, pacs.004 received). */
    RETURN_INITIATED,

    /** Reversal completed, funds returned to debtor. */
    RETURNED
}
