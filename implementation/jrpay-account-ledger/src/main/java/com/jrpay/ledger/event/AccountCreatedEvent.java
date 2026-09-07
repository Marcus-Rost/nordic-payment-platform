package com.jrpay.ledger.event;

import java.time.Instant;

public record AccountCreatedEvent(
        String eventId,
        String accountId,
        String holderName,
        String currency,
        String initialBalance,
        Instant timestamp
) implements LedgerEvent {
    @Override
    public String eventType() { return "ACCOUNT_CREATED"; }
}
