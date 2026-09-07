package com.jrpay.ledger.command;

import com.jrpay.ledger.event.LedgerEvent;

/**
 * Publishes ledger events. In production, this publishes to Kafka.
 * For unit tests, a simple in-memory implementation captures events.
 */
public interface EventPublisher {

    void publish(LedgerEvent event);
}
