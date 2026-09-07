package com.jrpay.vop.api;

import com.jrpay.domain.payment.VoPResult;
import com.jrpay.vop.cache.NameCache;
import com.jrpay.vop.cache.NameCacheEntry;
import com.jrpay.vop.matching.NameMatcher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * VoP REST API — both internal (requester) and external (responder) endpoints.
 * Per INTERFACE_CONTRACTS.md Section 3.3 and 3.5.
 */
@RestController
public class VoPController {

    private static final Logger log = LoggerFactory.getLogger(VoPController.class);

    private final NameMatcher nameMatcher;
    private final NameCache nameCache;
    private final com.jrpay.vop.cache.AccountLedgerClient accountLedgerClient;

    public VoPController(NameMatcher nameMatcher, NameCache nameCache,
                         com.jrpay.vop.cache.AccountLedgerClient accountLedgerClient) {
        this.nameMatcher = nameMatcher;
        this.nameCache = nameCache;
        this.accountLedgerClient = accountLedgerClient;
    }

    /**
     * Internal requester API — called by A3/A4 before payment submission.
     * POST /api/v1/vop/verify
     */
    @PostMapping("/api/v1/vop/verify")
    public ResponseEntity<VerifyResponse> verify(@Valid @RequestBody VerifyRequest request) {
        long startTime = System.nanoTime();

        // Look up holder name from cache (or return NOT_FOUND/ERROR)
        var cacheEntry = nameCache.get(request.beneficiaryIban());

        if (cacheEntry.isEmpty()) {
            // Cache miss — fetch from A1 Account Ledger
            var fetched = accountLedgerClient.fetchHolderName(request.beneficiaryIban());
            if (fetched.isPresent()) {
                nameCache.put(request.beneficiaryIban(), fetched.get());
                cacheEntry = fetched;
            } else {
                long elapsed = (System.nanoTime() - startTime) / 1_000_000;
                return ResponseEntity.ok(new VerifyResponse(
                        request.requestId(), VoPResult.NOT_FOUND.name(),
                        null, null, elapsed));
            }
        }

        NameCacheEntry entry = cacheEntry.get();

        // Match against all holder names (joint accounts), take best
        NameMatcher.MatchResult bestMatch = null;
        for (String holderName : entry.holderNames()) {
            NameMatcher.MatchResult match = nameMatcher.match(request.statedName(), holderName);
            if (bestMatch == null || match.similarityScore() > bestMatch.similarityScore()) {
                bestMatch = match;
            }
        }

        long elapsed = (System.nanoTime() - startTime) / 1_000_000;

        // Response filtering: actual_name ONLY on CLOSE_MATCH (and optionally MATCH)
        String actualName = null;
        if (bestMatch.result() == VoPResult.CLOSE_MATCH) {
            actualName = entry.holderName();
        }

        return ResponseEntity.ok(new VerifyResponse(
                request.requestId(),
                bestMatch.result().name(),
                actualName,
                bestMatch.similarityScore(),
                elapsed));
    }

    /**
     * External responder API — called by external PSPs.
     * POST /api/v1/vop/check
     */
    @PostMapping("/api/v1/vop/check")
    public ResponseEntity<CheckResponse> check(@Valid @RequestBody CheckRequest request) {
        long startTime = System.nanoTime();

        var cacheEntry = nameCache.get(request.iban());

        if (cacheEntry.isEmpty()) {
            // Cache miss — fetch from A1
            var fetched = accountLedgerClient.fetchHolderName(request.iban());
            if (fetched.isPresent()) {
                nameCache.put(request.iban(), fetched.get());
                cacheEntry = fetched;
            } else {
                applyTimingFloor(startTime);
                return ResponseEntity.ok(new CheckResponse(VoPResult.NOT_FOUND.name(), null));
            }
        }

        NameCacheEntry entry = cacheEntry.get();

        NameMatcher.MatchResult bestMatch = null;
        for (String holderName : entry.holderNames()) {
            NameMatcher.MatchResult match = nameMatcher.match(request.name(), holderName);
            if (bestMatch == null || match.similarityScore() > bestMatch.similarityScore()) {
                bestMatch = match;
            }
        }

        // External response: name_suggestion ONLY on CLOSE_MATCH
        String nameSuggestion = null;
        if (bestMatch.result() == VoPResult.CLOSE_MATCH) {
            nameSuggestion = entry.holderName();
        }
        // NO_MATCH: NEVER reveal actual name (structural enforcement)

        applyTimingFloor(startTime);

        return ResponseEntity.ok(new CheckResponse(
                bestMatch.result().name(), nameSuggestion));
    }

    /**
     * Timing normalization: pad response to minimum 10ms to prevent timing side-channel.
     * All cache-hit responses become indistinguishable at ~10ms.
     */
    private void applyTimingFloor(long startNanos) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        long remainingMs = 10 - elapsedMs;
        if (remainingMs > 0) {
            try {
                Thread.sleep(remainingMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // DTOs

    record VerifyRequest(
            @NotBlank String beneficiaryIban,
            @NotBlank @Size(max = 140) String statedName,
            String requestId
    ) {}

    record VerifyResponse(
            String requestId,
            String result,
            String actualName,
            Double similarityScore,
            long responseTimeMs
    ) {}

    record CheckRequest(
            @NotBlank String iban,
            @NotBlank @Size(max = 140) String name
    ) {}

    record CheckResponse(
            String result,
            String nameSuggestion
    ) {}
}
