package com.jrpay.domain.exception;

/** Sanctions screening found a match — payment must be blocked. Maps to HTTP 422. */
public class SanctionsHitException extends DomainException {

    private final String matchedEntry;
    private final String listVersion;

    public SanctionsHitException(String matchedEntry, String listVersion) {
        super("Sanctions hit: matched entry '" + matchedEntry + "' (list version " + listVersion + ")");
        this.matchedEntry = matchedEntry;
        this.listVersion = listVersion;
    }

    public String getMatchedEntry() { return matchedEntry; }
    public String getListVersion() { return listVersion; }
}
