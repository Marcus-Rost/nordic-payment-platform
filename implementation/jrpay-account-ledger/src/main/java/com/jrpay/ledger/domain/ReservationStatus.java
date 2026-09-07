package com.jrpay.ledger.domain;

/**
 * Reservation lifecycle: RESERVED → CONFIRMED or RELEASED.
 * Both CONFIRMED and RELEASED are terminal — operations on terminal
 * reservations are idempotent no-ops.
 */
public enum ReservationStatus {
    RESERVED,
    CONFIRMED,
    RELEASED
}
