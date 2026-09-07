package com.jrpay.fraud;

import com.jrpay.domain.payment.FraudDecision;
import com.jrpay.domain.payment.SanctionsResult;
import com.jrpay.domain.payment.VoPResult;
import com.jrpay.fraud.sanctions.SanctionsIndex;
import com.jrpay.fraud.scoring.*;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests derived from UC-01 (Real-time Transaction Scoring).
 * Each test maps to a specific scenario in the use case document.
 *
 * @see space/01_Real_time_Transaction_Fraud_Detection_Engine/use_cases/UC01_transaction_scoring.md
 */
class UseCaseEndToEndTest {

    private SanctionsIndex sanctionsIndex;
    private ScoringPipeline pipeline;
    private VelocityLayer velocityLayer;

    @BeforeEach
    void setUp() {
        sanctionsIndex = new SanctionsIndex();
        sanctionsIndex.loadList("EU-CONSOLIDATED-2026-04", List.of(
                "Ivanov Sergey",
                "Al-Qaeda Foundation",
                "Blocked Entity Ltd"
        ));

        velocityLayer = new VelocityLayer();
        SanctionsLayer sanctionsLayer = new SanctionsLayer(sanctionsIndex);
        VoPRiskLayer vopRiskLayer = new VoPRiskLayer();

        pipeline = new ScoringPipeline(List.of(sanctionsLayer, velocityLayer, vopRiskLayer));
    }

    // ====================================================================
    // UC-01: Main Success — Low-Risk Transaction (AUTO_PASS)
    // ====================================================================

    @Test
    @DisplayName("UC-01 Main: Low-risk transaction → PASS (auto-pass)")
    void uc01_main_lowRisk_autoPass() {
        // Step 1: Normal payment, VoP MATCH, moderate amount
        ScoringRequest request = new ScoringRequest(
                "TXN-001", "ACCT-DEBTOR", "ACCT-CREDITOR",
                "Ola Nordmann",
                new BigDecimal("2500.00"), "NOK",
                "INSTANT", VoPResult.MATCH, false);

        // Steps 2-7: Sanctions CLEAR, velocity low, no VoP risk
        ScoringResult result = pipeline.score(request);

        // Step 8: AUTO_PASS
        assertEquals(SanctionsResult.CLEAR, result.sanctionsResult());
        assertEquals(FraudDecision.PASS, result.decision());
        assertTrue(result.fraudScore() < 0.5,
                "Low-risk score should be < 0.5, was " + result.fraudScore());
        assertTrue(result.scoringLatencyMs() >= 0);
    }

    // ====================================================================
    // UC-01 Alternative: High-Risk Transaction (AUTO_BLOCK)
    // ====================================================================

    @Test
    @DisplayName("UC-01 Alt: High-risk (large amount + VoP NO_MATCH override) → high score")
    void uc01_alt_highRisk() {
        // Large amount + VoP NO_MATCH with payer override
        ScoringRequest request = new ScoringRequest(
                "TXN-002", "ACCT-DEBTOR", "ACCT-CREDITOR",
                "Ola Nordmann",
                new BigDecimal("95000.00"), "NOK",
                "INSTANT", VoPResult.NO_MATCH, true);

        ScoringResult result = pipeline.score(request);

        assertEquals(SanctionsResult.CLEAR, result.sanctionsResult());
        // Score should be high: velocity (large amount) + VoP override (+0.3)
        assertTrue(result.fraudScore() >= 0.3,
                "High-risk score should be >= 0.3, was " + result.fraudScore());
    }

    // ====================================================================
    // UC-01 Alternative: Sanctions Hit
    // ====================================================================

    @Test
    @DisplayName("UC-01 Alt: Sanctions hit → BLOCK immediately (short-circuit)")
    void uc01_alt_sanctionsHit() {
        // Creditor name matches sanctions list exactly
        ScoringRequest request = new ScoringRequest(
                "TXN-003", "ACCT-DEBTOR", "ACCT-CREDITOR",
                "Ivanov Sergey",
                new BigDecimal("5000.00"), "NOK",
                "INSTANT", VoPResult.MATCH, false);

        ScoringResult result = pipeline.score(request);

        assertEquals(SanctionsResult.HIT, result.sanctionsResult());
        assertEquals(FraudDecision.BLOCK, result.decision());
        assertEquals(1.0, result.fraudScore());
        assertNotNull(result.sanctionsMatchedEntry());
        // Sanctions list version preserved for audit
        assertEquals("EU-CONSOLIDATED-2026-04", result.sanctionsListVersion());
    }

    @Test
    @DisplayName("UC-01 Alt: Sanctions hit overrides all other scoring")
    void uc01_alt_sanctionsOverridesAll() {
        // Even with VoP MATCH and low amount — sanctions HIT still blocks
        ScoringRequest request = new ScoringRequest(
                "TXN-004", "ACCT-DEBTOR", "ACCT-CREDITOR",
                "Al-Qaeda Foundation",
                new BigDecimal("1.00"), "NOK",
                "INSTANT", VoPResult.MATCH, false);

        ScoringResult result = pipeline.score(request);

        assertEquals(SanctionsResult.HIT, result.sanctionsResult());
        assertEquals(FraudDecision.BLOCK, result.decision());
    }

    // ====================================================================
    // UC-01: VoP Integration (Section 9C from master UC-09)
    // ====================================================================

    @Test
    @DisplayName("UC-01/UC-09: VoP MATCH + no override → neutral (0.0)")
    void vopMatch_neutral() {
        ScoringRequest request = new ScoringRequest(
                "TXN-VOP-1", "D", "C", "Name",
                new BigDecimal("1000.00"), "NOK",
                "INSTANT", VoPResult.MATCH, false);

        ScoringResult result = pipeline.score(request);
        // VoP MATCH adds 0.0 risk
        assertTrue(result.fraudScore() < 0.1);
    }

    @Test
    @DisplayName("UC-01/UC-09: VoP CLOSE_MATCH + payer ignores correction → +0.1")
    void vopCloseMatch_ignored() {
        ScoringRequest request = new ScoringRequest(
                "TXN-VOP-2", "D", "C", "Name",
                new BigDecimal("1000.00"), "NOK",
                "INSTANT", VoPResult.CLOSE_MATCH, true);

        ScoringResult result = pipeline.score(request);
        assertTrue(result.fraudScore() >= 0.1,
                "CLOSE_MATCH+override should add >= 0.1, was " + result.fraudScore());
    }

    @Test
    @DisplayName("UC-01/UC-09: VoP NO_MATCH + payer override → +0.3 (APP fraud indicator)")
    void vopNoMatch_override() {
        ScoringRequest request = new ScoringRequest(
                "TXN-VOP-3", "D", "C", "Name",
                new BigDecimal("1000.00"), "NOK",
                "INSTANT", VoPResult.NO_MATCH, true);

        ScoringResult result = pipeline.score(request);
        assertTrue(result.fraudScore() >= 0.3,
                "NO_MATCH+override should add >= 0.3, was " + result.fraudScore());
    }

    @Test
    @DisplayName("UC-01/UC-09: VoP NOT_FOUND + payer override → +0.4 (high risk)")
    void vopNotFound_override() {
        ScoringRequest request = new ScoringRequest(
                "TXN-VOP-4", "D", "C", "Name",
                new BigDecimal("1000.00"), "NOK",
                "INSTANT", VoPResult.NOT_FOUND, true);

        ScoringResult result = pipeline.score(request);
        assertTrue(result.fraudScore() >= 0.4,
                "NOT_FOUND+override should add >= 0.4, was " + result.fraudScore());
    }

    // ====================================================================
    // Invariants
    // ====================================================================

    @Test
    @DisplayName("Invariant: sanctions screening cannot be skipped")
    void invariant_sanctionsAlwaysRuns() {
        // Any payment with a sanctioned creditor must be blocked,
        // regardless of amount, VoP result, or other factors
        ScoringRequest request = new ScoringRequest(
                "TXN-INV-1", "D", "C",
                "Blocked Entity Ltd",
                new BigDecimal("0.01"), "NOK", // tiny amount
                "INSTANT", VoPResult.MATCH, false); // VoP MATCH

        ScoringResult result = pipeline.score(request);
        assertEquals(SanctionsResult.HIT, result.sanctionsResult());
        assertEquals(FraudDecision.BLOCK, result.decision());
    }
}
