package com.jrpay.clearing.duplicate;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Semantic duplicate detection per architecture decision.
 * Detects: same debtor + same creditor + same amount + same currency
 * within a configurable time window (default: 10 minutes).
 *
 * Warns but does not block — many legitimate payments look like duplicates.
 */
@Component
public class SemanticDuplicateDetector {

    private static final long DEFAULT_WINDOW_MS = 10 * 60 * 1000; // 10 minutes

    private final Map<String, DuplicateEntry> fingerprints = new ConcurrentHashMap<>();

    /**
     * Check if a payment is a potential semantic duplicate.
     *
     * @return the transaction ID of the potential duplicate, or empty if not a duplicate
     */
    public Optional<String> check(String debtorIban, String creditorIban,
                                  BigDecimal amount, String currency,
                                  String currentTransactionId) {
        String fingerprint = computeFingerprint(debtorIban, creditorIban, amount, currency);

        DuplicateEntry existing = fingerprints.get(fingerprint);
        if (existing != null && !existing.transactionId().equals(currentTransactionId)) {
            long age = Instant.now().toEpochMilli() - existing.timestamp().toEpochMilli();
            if (age < DEFAULT_WINDOW_MS) {
                return Optional.of(existing.transactionId());
            }
        }

        // Record this payment for future duplicate checks
        fingerprints.put(fingerprint, new DuplicateEntry(currentTransactionId, Instant.now()));
        return Optional.empty();
    }

    /**
     * SHA-256 fingerprint of the payment's semantic identity.
     * Delimiter prevents hash collisions between field values.
     */
    static String computeFingerprint(String debtorIban, String creditorIban,
                                     BigDecimal amount, String currency) {
        String input = debtorIban + "|" + creditorIban + "|" + amount.toPlainString() + "|" + currency;
        return com.jrpay.clearing.util.HashUtil.sha256Hex(input);
    }

    /** Reset for testing. */
    public void reset() {
        fingerprints.clear();
    }

    record DuplicateEntry(String transactionId, Instant timestamp) {}
}
