package com.jrpay.fraud.scoring;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Layer 1: Velocity rules.
 * Detects abnormal transaction patterns: high frequency, large amounts.
 * Uses in-memory counters (production: Kafka Streams state store).
 */
@Component
@Order(1)
public class VelocityLayer implements ScoringLayer {

    /** Per-account transaction count in the current window. */
    private final Map<String, AtomicInteger> txCountPerAccount = new ConcurrentHashMap<>();

    /** Threshold: transactions per hour per account before adding risk. */
    private static final int HIGH_FREQUENCY_THRESHOLD = 10;

    /** Threshold: single transaction amount that adds risk. */
    private static final BigDecimal LARGE_AMOUNT_THRESHOLD = new BigDecimal("50000");

    @Override
    public String name() {
        return "VELOCITY";
    }

    @Override
    public LayerResult score(ScoringRequest request) {
        double score = 0.0;
        StringBuilder detail = new StringBuilder();

        // Rule VEL-01: High frequency
        int count = txCountPerAccount
                .computeIfAbsent(request.debtorAccountId(), k -> new AtomicInteger(0))
                .incrementAndGet();

        if (count > HIGH_FREQUENCY_THRESHOLD) {
            score += 0.15;
            detail.append("VEL-01:HIGH_FREQ(").append(count).append(") ");
        }

        // Rule VEL-02: Large amount
        if (request.amount().compareTo(LARGE_AMOUNT_THRESHOLD) > 0) {
            score += 0.10;
            detail.append("VEL-02:LARGE_AMOUNT ");
        }

        return LayerResult.continueWith(score, detail.toString().trim());
    }

    /** Reset counters (for testing). */
    public void resetCounters() {
        txCountPerAccount.clear();
    }
}
