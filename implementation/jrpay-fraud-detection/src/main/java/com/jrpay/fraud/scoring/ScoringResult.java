package com.jrpay.fraud.scoring;

import com.jrpay.domain.payment.FraudDecision;
import com.jrpay.domain.payment.SanctionsResult;

import java.util.List;

/**
 * Output of the scoring pipeline — the combined fraud assessment.
 */
public record ScoringResult(
        String transactionId,
        SanctionsResult sanctionsResult,
        String sanctionsMatchedEntry,
        String sanctionsListVersion,
        FraudDecision decision,
        double fraudScore,
        double velocityScore,
        List<String> velocityTriggeredRules,
        double anomalyScore,
        long scoringLatencyMs
) {
    /** Quick constructor for sanctions hit (short-circuit). */
    public static ScoringResult sanctionsHit(String transactionId, String matchedEntry,
                                             String listVersion, long latencyMs) {
        return new ScoringResult(transactionId, SanctionsResult.HIT, matchedEntry, listVersion,
                FraudDecision.BLOCK, 1.0, 0, List.of(), 0, latencyMs);
    }

    /** Quick constructor for clean pass. */
    public static ScoringResult pass(String transactionId, String listVersion,
                                     double fraudScore, double velocityScore,
                                     List<String> triggeredRules, double anomalyScore,
                                     long latencyMs) {
        FraudDecision decision;
        if (fraudScore >= 0.7) {
            decision = FraudDecision.BLOCK;
        } else if (fraudScore >= 0.5) {
            decision = FraudDecision.REVIEW;
        } else {
            decision = FraudDecision.PASS;
        }
        return new ScoringResult(transactionId, SanctionsResult.CLEAR, null, listVersion,
                decision, fraudScore, velocityScore, triggeredRules, anomalyScore, latencyMs);
    }
}
