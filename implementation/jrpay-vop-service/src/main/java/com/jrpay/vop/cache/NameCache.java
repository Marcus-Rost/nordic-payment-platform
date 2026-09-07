package com.jrpay.vop.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * In-memory cache for account holder names, keyed by IBAN.
 * TTL-based invalidation (1 hour default). Production upgrade: event-driven.
 */
@Component
public class NameCache {

    private final Cache<String, NameCacheEntry> cache;

    public NameCache(
            @Value("${jrpay.vop.cache.max-entries:100000}") long maxEntries,
            @Value("${jrpay.vop.cache.ttl:PT1H}") Duration ttl) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(ttl)
                .recordStats()
                .build();
    }

    public Optional<NameCacheEntry> get(String iban) {
        return Optional.ofNullable(cache.getIfPresent(iban));
    }

    public void put(String iban, NameCacheEntry entry) {
        cache.put(iban, entry);
    }

    public void invalidate(String iban) {
        cache.invalidate(iban);
    }

    public long size() {
        return cache.estimatedSize();
    }

    public double hitRate() {
        return cache.stats().hitRate();
    }
}
