package com.jrpay.vop.cache;

import java.time.Instant;
import java.util.List;

/**
 * Cached account holder name(s) for an IBAN.
 * Pre-computed normalized name eliminates redundant normalization on repeated queries.
 */
public record NameCacheEntry(
        String accountId,
        String holderName,
        List<String> holderNames,
        Instant lastUpdated
) {
    /** Convenience for single-holder accounts. */
    public static NameCacheEntry of(String accountId, String holderName, Instant lastUpdated) {
        return new NameCacheEntry(accountId, holderName, List.of(holderName), lastUpdated);
    }
}
