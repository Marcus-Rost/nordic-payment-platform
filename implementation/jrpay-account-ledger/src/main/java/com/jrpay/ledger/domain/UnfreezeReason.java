package com.jrpay.ledger.domain;

/** Mirrors {@code UnfreezeReason} in {@code shared-schemas.yaml}. */
public enum UnfreezeReason {
    INVESTIGATION_CONCLUDED,
    FALSE_POSITIVE_CONFIRMED,
    CUSTOMER_DISPUTE_RESOLVED,
    OTHER
}
