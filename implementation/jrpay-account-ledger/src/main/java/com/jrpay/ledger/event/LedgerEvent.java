package com.jrpay.ledger.event;

import java.time.Instant;

/**
 * Base interface for all ledger events published to the ledger.events Kafka topic.
 * Events are the source of truth — immutable, append-only.
 */
public sealed interface LedgerEvent
        permits AccountCreatedEvent, BalanceReservedEvent, BalanceReleasedEvent,
                BalanceConfirmedEvent, DoubleEntryEvent, AccountFrozenEvent,
                AccountUnfrozenEvent, AccountClosedEvent {

    String eventId();
    String accountId();
    Instant timestamp();
    String eventType();
}
