package com.jrpay.ledger.event;

import java.time.Instant;

/**
 * Compound event: debit + credit in a single message.
 * Atomic by construction — structurally impossible to publish
 * one leg without the other. This is the core accounting guarantee.
 */
public record DoubleEntryEvent(
        String eventId,
        String accountId,         // the account that initiated (debtor for settlement)
        String transactionId,
        String debitAccountId,
        String creditAccountId,
        String amount,
        String currency,
        String description,
        Instant timestamp
) implements LedgerEvent {
    @Override
    public String eventType() { return "DOUBLE_ENTRY"; }
}
