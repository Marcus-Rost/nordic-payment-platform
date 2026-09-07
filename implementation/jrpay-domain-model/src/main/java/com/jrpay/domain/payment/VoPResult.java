package com.jrpay.domain.payment;

/**
 * Verification of Payee result. Returned by A6, carried in PaymentInstruction.
 * Defined in INTERFACE_CONTRACTS.md Section 1.2.
 */
public enum VoPResult {

    /** Name matches account holder (Jaro-Winkler >= 0.95). */
    MATCH,

    /** Near match (>= 0.85, < 0.95). Actual name provided to caller. */
    CLOSE_MATCH,

    /** Name does not match (< 0.85). Actual name NEVER provided. */
    NO_MATCH,

    /** IBAN does not exist (or account crypto-shredded). */
    NOT_FOUND,

    /** A6 could not complete verification in time. */
    TIMEOUT,

    /** A6 internal error. */
    ERROR;

    /**
     * Map to EPC VoP Scheme (EPC218-23) codes for external API.
     * ACCOUNT_CLOSED (internal only) is mapped to NOT_FOUND before calling this.
     */
    public String toEpcCode() {
        return switch (this) {
            case MATCH -> "MTCH";
            case CLOSE_MATCH -> "CMTCH";
            case NO_MATCH -> "NMTCH";
            case NOT_FOUND, TIMEOUT, ERROR -> "NOAP";
        };
    }
}
