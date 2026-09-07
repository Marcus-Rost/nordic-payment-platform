package com.jrpay.ledger.event;

import java.time.Instant;

public record AccountClosedEvent(
        String eventId,
        String accountId,
        Instant timestamp
) implements LedgerEvent {
    @Override
    public String eventType() { return "ACCOUNT_CLOSED"; }
}
