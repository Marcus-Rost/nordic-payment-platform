package com.jrpay.ledger.domain;

/**
 * Account lifecycle states.
 * ACTIVE: normal operations (debit + credit).
 * FROZEN: credits allowed, debits rejected (regulatory hold).
 * CLOSED: no operations allowed, pending GDPR erasure.
 */
public enum AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}
