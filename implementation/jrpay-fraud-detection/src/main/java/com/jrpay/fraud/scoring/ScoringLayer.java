package com.jrpay.fraud.scoring;

/**
 * A single layer in the scoring pipeline (Chain of Responsibility).
 * Each layer computes a partial score and may short-circuit the pipeline.
 */
public interface ScoringLayer {

    /** Display name for logging and audit. */
    String name();

    /**
     * Score the request. Returns a partial result.
     * If shouldShortCircuit() returns true, the pipeline stops here.
     */
    LayerResult score(ScoringRequest request);

    /**
     * Partial result from one scoring layer.
     */
    record LayerResult(
            double score,
            boolean shortCircuit,
            String detail
    ) {
        public static LayerResult continueWith(double score, String detail) {
            return new LayerResult(score, false, detail);
        }

        public static LayerResult shortCircuit(double score, String detail) {
            return new LayerResult(score, true, detail);
        }
    }
}
