package com.jrpay.ledger.event;

import java.time.Instant;

public record BalanceReleasedEvent(
        String eventId,
        String accountId,
        String reservationId,
        String transactionId,
        String reason,
        Instant timestamp
) implements LedgerEvent {
    @Override
    public String eventType() { return "BALANCE_RELEASED"; }
}
