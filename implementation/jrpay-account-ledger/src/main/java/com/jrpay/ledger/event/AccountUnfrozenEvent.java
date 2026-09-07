package com.jrpay.ledger.event;

import java.time.Instant;

public record AccountUnfrozenEvent(
        String eventId,
        String accountId,
        String reason,
        Instant timestamp
) implements LedgerEvent {
    @Override
    public String eventType() { return "ACCOUNT_UNFROZEN"; }
}
