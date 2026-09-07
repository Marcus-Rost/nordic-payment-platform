package com.jrpay.ledger.event;

import java.time.Instant;

public record BalanceReservedEvent(
        String eventId,
        String accountId,
        String reservationId,
        String transactionId,
        String amount,
        String currency,
        Instant timestamp
) implements LedgerEvent {
    @Override
    public String eventType() { return "BALANCE_RESERVED"; }
}
