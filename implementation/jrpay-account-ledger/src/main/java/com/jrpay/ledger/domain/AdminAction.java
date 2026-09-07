package com.jrpay.ledger.domain;

/**
 * Subset of {@code AdminAction} (shared-schemas.yaml) bound to A1's admin
 * surface. The dynamic-linking class {@code admin-action} carries this value
 * in the SCA proof; the resource server verifies the bound action matches the
 * route handler.
 */
public enum AdminAction {
    FREEZE_ACCOUNT,
    UNFREEZE_ACCOUNT,
    CLOSE_ACCOUNT,
    CREATE_ACCOUNT,
    REVEAL_PII
}
