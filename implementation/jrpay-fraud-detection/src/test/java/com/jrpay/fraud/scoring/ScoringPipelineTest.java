package com.jrpay.fraud.scoring;

import com.jrpay.domain.payment.FraudDecision;
import com.jrpay.domain.payment.SanctionsResult;
import com.jrpay.domain.payment.VoPResult;
import com.jrpay.fraud.sanctions.SanctionsIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the scoring pipeline — the chain of responsibility.
 */
class ScoringPipelineTest {

    private SanctionsIndex sanctionsIndex;
    private ScoringPipeline pipeline;
    private VelocityLayer velocityLayer;

    @BeforeEach
    void setUp() {
        sanctionsIndex = new SanctionsIndex();
        sanctionsIndex.loadList("TEST-v1", List.of(
                "Sanctioned Person",
                "Blocked Entity Ltd"
        ));

        velocityLayer = new VelocityLayer();
        SanctionsLayer sanctionsLayer = new SanctionsLayer(sanctionsIndex);
        VoPRiskLayer vopRiskLayer = new VoPRiskLayer();

        pipeline = new ScoringPipeline(List.of(sanctionsLayer, velocityLayer, vopRiskLayer));
    }

    private ScoringRequest normalRequest() {
        return new ScoringRequest("TXN-001", "DEBTOR-1", "CREDITOR-1",
                "Ola Nordmann", new BigDecimal("5000.00"), "NOK",
                "INSTANT", VoPResult.MATCH, false);
    }

    @Test
    @DisplayName("Clean payment → PASS with low fraud score")
    void cleanPayment() {
        ScoringResult result = pipeline.score(normalRequest());

        assertEquals(SanctionsResult.CLEAR, result.sanctionsResult());
        assertEquals(FraudDecision.PASS, result.decision());
        assertTrue(result.fraudScore() < 0.5);
    }

    @Test
    @DisplayName("Sanctions HIT → BLOCK immediately (short-circuit)")
    void sanctionsHit() {
        ScoringRequest request = new ScoringRequest("TXN-002", "DEBTOR-1", "CREDITOR-1",
                "Sanctioned Person", new BigDecimal("1000.00"), "NOK",
                "INSTANT", VoPResult.MATCH, false);

        ScoringResult result = pipeline.score(request);

        assertEquals(SanctionsResult.HIT, result.sanctionsResult());
        assertEquals(FraudDecision.BLOCK, result.decision());
        assertEquals(1.0, result.fraudScore());
        assertEquals("Sanctioned Person", result.sanctionsMatchedEntry());
    }

    @Test
    @DisplayName("VoP NO_MATCH + payer override → +0.3 risk")
    void vopNoMatchOverride() {
        ScoringRequest request = new ScoringRequest("TXN-003", "DEBTOR-1", "CREDITOR-1",
                "Ola Nordmann", new BigDecimal("5000.00"), "NOK",
                "INSTANT", VoPResult.NO_MATCH, true);

        ScoringResult result = pipeline.score(request);

        assertEquals(SanctionsResult.CLEAR, result.sanctionsResult());
        assertTrue(result.fraudScore() >= 0.3,
                "Score should include +0.3 VoP risk, was " + result.fraudScore());
    }

    @Test
    @DisplayName("VoP NOT_FOUND + payer override → +0.4 risk")
    void vopNotFoundOverride() {
        ScoringRequest request = new ScoringRequest("TXN-004", "DEBTOR-1", "CREDITOR-1",
                "Ola Nordmann", new BigDecimal("5000.00"), "NOK",
                "INSTANT", VoPResult.NOT_FOUND, true);

        ScoringResult result = pipeline.score(request);

        assertTrue(result.fraudScore() >= 0.4,
                "Score should include +0.4 VoP risk, was " + result.fraudScore());
    }

    @Test
    @DisplayName("Large amount → velocity risk added")
    void largeAmount() {
        ScoringRequest request = new ScoringRequest("TXN-005", "DEBTOR-1", "CREDITOR-1",
                "Ola Nordmann", new BigDecimal("100000.00"), "NOK",
                "INSTANT", VoPResult.MATCH, false);

        ScoringResult result = pipeline.score(request);

        assertTrue(result.fraudScore() > 0,
                "Large amount should trigger velocity score");
    }

    @Test
    @DisplayName("High frequency → velocity risk increases")
    void highFrequency() {
        velocityLayer.resetCounters();

        // Submit 11 transactions from the same account (threshold is 10)
        ScoringResult lastResult = null;
        for (int i = 0; i < 11; i++) {
            ScoringRequest request = new ScoringRequest("TXN-FREQ-" + i, "SAME-DEBTOR", "CREDITOR-1",
                    "Ola Nordmann", new BigDecimal("1000.00"), "NOK",
                    "INSTANT", VoPResult.MATCH, false);
            lastResult = pipeline.score(request);
        }

        assertTrue(lastResult.fraudScore() > 0,
                "High frequency should trigger velocity score");
    }

    @Test
    @DisplayName("Score is always bounded [0, 1]")
    void scoreBounded() {
        // Combine all risk factors: large amount + VoP override
        ScoringRequest request = new ScoringRequest("TXN-MAX", "DEBTOR-1", "CREDITOR-1",
                "Ola Nordmann", new BigDecimal("100000.00"), "NOK",
                "INSTANT", VoPResult.NOT_FOUND, true);

        ScoringResult result = pipeline.score(request);

        assertTrue(result.fraudScore() >= 0.0);
        assertTrue(result.fraudScore() <= 1.0);
    }

    @Test
    @DisplayName("Sanctions version tracked in result")
    void sanctionsVersionTracked() {
        ScoringResult result = pipeline.score(normalRequest());
        assertEquals("TEST-v1", result.sanctionsListVersion());
    }
}
