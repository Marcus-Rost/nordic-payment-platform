package com.jrpay.ledger.domain;

/** Mirrors {@code FreezeReason} in {@code shared-schemas.yaml}. */
public enum FreezeReason {
    FRAUD_INVESTIGATION,
    SANCTIONS_HOLD,
    REGULATORY_HOLD,
    OTHER
}
