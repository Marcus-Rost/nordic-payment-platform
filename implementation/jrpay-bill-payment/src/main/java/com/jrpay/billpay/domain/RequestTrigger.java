package com.jrpay.billpay.domain;

/**
 * Events that trigger state transitions in the R2P lifecycle.
 */
public enum RequestTrigger {
    CONSUMER_ACCEPT,
    CONSUMER_DECLINE,
    CONSUMER_DEFER,
    CONSUMER_AMEND,
    MERCHANT_ACCEPT_AMENDMENT,
    MERCHANT_REJECT_AMENDMENT,
    MANDATE_AUTO_ACCEPT,
    PAYMENT_INITIATED,
    KAFKA_SETTLED,
    KAFKA_REJECTED,
    KAFKA_HELD,
    KAFKA_RETURN_INITIATED,
    KAFKA_RETURNED,
    TIMER_EXPIRED
}
