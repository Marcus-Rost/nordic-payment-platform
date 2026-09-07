package com.jrpay.ledger.domain;

/** Account close reasons (UC-G35 §6.5). Mirrors {@code admin-accounts.yaml#CloseAccountRequest.reason}. */
public enum CloseReason {
    CUSTOMER_REQUEST,
    GDPR_ERASURE,
    OPERATIONAL_CLEANUP,
    REGULATORY,
    OTHER
}
