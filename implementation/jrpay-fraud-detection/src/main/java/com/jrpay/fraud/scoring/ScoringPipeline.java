package com.jrpay.fraud.scoring;

import com.jrpay.domain.payment.FraudDecision;
import com.jrpay.domain.payment.SanctionsResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The scoring pipeline — chains multiple scoring layers.
 * Layer 0: Sanctions screening (short-circuits on HIT)
 * Layer 1: Velocity rules
 * Layer 2: Anomaly detection
 * Layer 3: VoP risk adjustment
 *
 * Scores are combined with configurable weights.
 */
@Component
public class ScoringPipeline {

    private static final Logger log = LoggerFactory.getLogger(ScoringPipeline.class);

    private final List<ScoringLayer> layers;

    public ScoringPipeline(List<ScoringLayer> layers) {
        this.layers = layers;
        log.info("Scoring pipeline initialized with {} layers: {}",
                layers.size(), layers.stream().map(ScoringLayer::name).toList());
    }

    /**
     * Run the full scoring pipeline. Returns the combined result.
     * Sanctions HIT short-circuits immediately (no further scoring needed).
     */
    public ScoringResult score(ScoringRequest request) {
        long startTime = System.nanoTime();

        double totalScore = 0.0;
        List<String> triggeredRules = new ArrayList<>();
        String sanctionsListVersion = "unknown";
        SanctionsResult sanctionsResult = SanctionsResult.CLEAR;

        for (ScoringLayer layer : layers) {
            // Capture sanctions metadata BEFORE scoring (so it's available on short-circuit)
            if (layer instanceof SanctionsLayer sl) {
                sanctionsListVersion = sl.getListVersion();
            }

            ScoringLayer.LayerResult result = layer.score(request);

            log.debug("Layer '{}': score={}, shortCircuit={}, detail={}",
                    layer.name(), result.score(), result.shortCircuit(), result.detail());

            if (result.shortCircuit()) {
                // Sanctions HIT — stop immediately
                long latencyMs = (System.nanoTime() - startTime) / 1_000_000;
                return ScoringResult.sanctionsHit(
                        request.transactionId(), result.detail(),
                        sanctionsListVersion, latencyMs);
            }

            totalScore += result.score();
            if (result.score() > 0 && result.detail() != null) {
                triggeredRules.add(layer.name() + ": " + result.detail());
            }
        }

        long latencyMs = (System.nanoTime() - startTime) / 1_000_000;

        // Clamp to [0, 1]
        totalScore = Math.min(1.0, Math.max(0.0, totalScore));

        return ScoringResult.pass(
                request.transactionId(), sanctionsListVersion,
                totalScore, 0, triggeredRules, 0, latencyMs);
    }
}
