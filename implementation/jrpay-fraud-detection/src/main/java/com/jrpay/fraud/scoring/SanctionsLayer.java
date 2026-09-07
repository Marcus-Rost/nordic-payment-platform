package com.jrpay.fraud.scoring;

import com.jrpay.fraud.sanctions.SanctionsIndex;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Layer 0: Sanctions screening.
 * Short-circuits on HIT — payment must be blocked immediately.
 * Uses a three-tier approach: Bloom filter → exact match → fuzzy match.
 */
@Component
@Order(0)
public class SanctionsLayer implements ScoringLayer {

    private final SanctionsIndex sanctionsIndex;

    public SanctionsLayer(SanctionsIndex sanctionsIndex) {
        this.sanctionsIndex = sanctionsIndex;
    }

    @Override
    public String name() {
        return "SANCTIONS";
    }

    @Override
    public LayerResult score(ScoringRequest request) {
        // Screen the creditor (beneficiary) name
        SanctionsIndex.ScreeningResult result = sanctionsIndex.screen(request.creditorName());

        if (result.isHit()) {
            // Short-circuit: sanctions match found
            return LayerResult.shortCircuit(1.0, result.matchedEntry());
        }

        return LayerResult.continueWith(0.0, "CLEAR");
    }

    public String getListVersion() {
        return sanctionsIndex.getListVersion();
    }
}
