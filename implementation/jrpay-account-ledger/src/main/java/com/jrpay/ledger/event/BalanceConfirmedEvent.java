package com.jrpay.ledger.event;

import java.time.Instant;

public record BalanceConfirmedEvent(
        String eventId,
        String accountId,
        String reservationId,
        String transactionId,
        Instant timestamp
) implements LedgerEvent {
    @Override
    public String eventType() { return "BALANCE_CONFIRMED"; }
}
