package com.jrpay.ledger.domain;

import com.jrpay.domain.money.MonetaryAmount;

import java.time.Instant;
import java.util.Objects;

/**
 * Balance reservation — holds funds for a pending payment.
 * Created when A2 requests a reservation, released on timeout or rejection,
 * confirmed on settlement.
 */
public final class Reservation {

    private final String reservationId;
    private final String accountId;
    private final String transactionId;
    private final MonetaryAmount amount;
    private final ReservationStatus status;
    private final Instant createdAt;
    private final Instant resolvedAt;

    public Reservation(String reservationId, String accountId, String transactionId,
                       MonetaryAmount amount, ReservationStatus status,
                       Instant createdAt, Instant resolvedAt) {
        this.reservationId = Objects.requireNonNull(reservationId);
        this.accountId = Objects.requireNonNull(accountId);
        this.transactionId = Objects.requireNonNull(transactionId);
        this.amount = Objects.requireNonNull(amount);
        this.status = Objects.requireNonNull(status);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.resolvedAt = resolvedAt; // nullable — not yet resolved
    }

    /** Check if this reservation has expired (older than timeout). */
    public boolean isExpired(Instant now, long timeoutSeconds) {
        return status == ReservationStatus.RESERVED
                && createdAt.plusSeconds(timeoutSeconds).isBefore(now);
    }

    /** Confirm: reservation → confirmed (settlement applied). */
    public boolean canConfirm() {
        return status == ReservationStatus.RESERVED;
    }

    /** Release: reservation → released (payment rejected or timeout). */
    public boolean canRelease() {
        return status == ReservationStatus.RESERVED;
    }

    // Getters
    public String getReservationId() { return reservationId; }
    public String getAccountId() { return accountId; }
    public String getTransactionId() { return transactionId; }
    public MonetaryAmount getAmount() { return amount; }
    public ReservationStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
