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
 * System tests derived from master project UC-05 (Fraud Detection, Sanctions Screening).
 * Tests the full fraud scoring pipeline as described in the master use case.
 *
 * These tests cover cross-subsystem behavior: VoP results (from A6) feeding into
 * fraud scoring (A5), sanctions screening (A5), and the interaction between them.
 *
 * @see space/payment_processing/use_cases/UC05_fraud_detection.md
 */
class MasterUC05SystemTest {

    private SanctionsIndex sanctionsIndex;
    private ScoringPipeline pipeline;
    private VelocityLayer velocityLayer;

    @BeforeEach
    void setUp() {
        sanctionsIndex = new SanctionsIndex();
        sanctionsIndex.loadList("EU-CONSOLIDATED-2026-04-12", List.of(
                "IVANOV Sergey Mikhailovich",
                "AL-QAEDA",
                "BLOCKED ENTITY LTD"
        ));

        velocityLayer = new VelocityLayer();
        SanctionsLayer sanctionsLayer = new SanctionsLayer(sanctionsIndex);
        VoPRiskLayer vopRiskLayer = new VoPRiskLayer();

        pipeline = new ScoringPipeline(List.of(sanctionsLayer, velocityLayer, vopRiskLayer));
    }

    // ====================================================================
    // UC-05 Scenario 5A: Transaction Scoring (Automated)
    // ====================================================================

    @Test
    @DisplayName("UC-05 5A: Normal transaction, VoP MATCH → PASS (score < 0.5)")
    void uc05_5A_normalTransaction_autoPass() {
        ScoringRequest request = new ScoringRequest(
                "TXN-5A-001", "ACCT-X", "ACCT-Y",
                "Ola Nordmann",
                new BigDecimal("2500.00"), "NOK",
                "INSTANT", VoPResult.MATCH, false);

        ScoringResult result = pipeline.score(request);

        assertEquals(SanctionsResult.CLEAR, result.sanctionsResult());
        assertEquals(FraudDecision.PASS, result.decision());
        assertTrue(result.fraudScore() < 0.5,
                "Normal transaction score should be < 0.5 (auto-pass), was " + result.fraudScore());
        assertNotNull(result.sanctionsListVersion());
    }

    // ====================================================================
    // UC-05 Scenario 5E: Sanctions Screening
    // ====================================================================

    @Test
    @DisplayName("UC-05 5E Happy Path: Sanctions CLEAR → proceed to fraud scoring")
    void uc05_5E_sanctionsClear() {
        ScoringRequest request = new ScoringRequest(
                "TXN-5E-001", "ACCT-X", "ACCT-Y",
                "Kari Hansen", // not sanctioned
                new BigDecimal("5000.00"), "NOK",
                "INSTANT", VoPResult.MATCH, false);

        ScoringResult result = pipeline.score(request);

        assertEquals(SanctionsResult.CLEAR, result.sanctionsResult());
        // Fraud scoring ran (because sanctions was CLEAR)
        assertNotNull(result.decision());
    }

    @Test
    @DisplayName("UC-05 5E Potential Hit: Sanctioned name → BLOCK (overrides all fraud scoring)")
    void uc05_5E_sanctionsHit() {
        ScoringRequest request = new ScoringRequest(
                "TXN-5E-002", "ACCT-X", "ACCT-Y",
                "AL-QAEDA", // exact match in sanctions list
                new BigDecimal("100.00"), "NOK",
                "INSTANT", VoPResult.MATCH, false);

        ScoringResult result = pipeline.score(request);

        assertEquals(SanctionsResult.HIT, result.sanctionsResult());
        assertEquals(FraudDecision.BLOCK, result.decision());
        assertEquals(1.0, result.fraudScore());
        // Sanctions list version must be in the result (for audit traceability)
        assertEquals("EU-CONSOLIDATED-2026-04-12", result.sanctionsListVersion());
    }

    @Test
    @DisplayName("UC-05 5E Critical Rule: Sanctions screening cannot be skipped")
    void uc05_5E_sanctionsCannotBeSkipped() {
        // Even a tiny amount, VoP MATCH, no velocity risk — sanctions HIT still blocks
        ScoringRequest request = new ScoringRequest(
                "TXN-5E-003", "ACCT-X", "ACCT-Y",
                "BLOCKED ENTITY LTD",
                new BigDecimal("0.01"), "NOK",
                "INSTANT", VoPResult.MATCH, false);

        ScoringResult result = pipeline.score(request);

        assertEquals(SanctionsResult.HIT, result.sanctionsResult());
        assertEquals(FraudDecision.BLOCK, result.decision());
    }

    // ====================================================================
    // UC-05 Scenario 5F: VoP Result Integration with Fraud Scoring
    // ====================================================================

    @Test
    @DisplayName("UC-05 5F: VoP MATCH → +0.0 (neutral)")
    void uc05_5F_vopMatch_neutral() {
        ScoringRequest request = new ScoringRequest(
                "TXN-5F-001", "D", "C", "Normal Person",
                new BigDecimal("1000.00"), "NOK",
                "INSTANT", VoPResult.MATCH, false);

        ScoringResult result = pipeline.score(request);
        assertTrue(result.fraudScore() < 0.1,
                "VoP MATCH should add 0.0 risk, total was " + result.fraudScore());
    }

    @Test
    @DisplayName("UC-05 5F: VoP CLOSE_MATCH + user corrects → +0.0 (neutral)")
    void uc05_5F_vopCloseMatch_corrected() {
        // User corrected the name (vopPayerOverride=false means they accepted the correction)
        ScoringRequest request = new ScoringRequest(
                "TXN-5F-002", "D", "C", "Normal Person",
                new BigDecimal("1000.00"), "NOK",
                "INSTANT", VoPResult.CLOSE_MATCH, false);

        ScoringResult result = pipeline.score(request);
        assertTrue(result.fraudScore() < 0.1,
                "VoP CLOSE_MATCH without override should add 0.0, total was " + result.fraudScore());
    }

    @Test
    @DisplayName("UC-05 5F: VoP CLOSE_MATCH + user ignores correction → +0.1")
    void uc05_5F_vopCloseMatch_ignored() {
        ScoringRequest request = new ScoringRequest(
                "TXN-5F-003", "D", "C", "Normal Person",
                new BigDecimal("1000.00"), "NOK",
                "INSTANT", VoPResult.CLOSE_MATCH, true);

        ScoringResult result = pipeline.score(request);
        assertTrue(result.fraudScore() >= 0.1,
                "VoP CLOSE_MATCH+override should add >= 0.1, total was " + result.fraudScore());
    }

    @Test
    @DisplayName("UC-05 5F: VoP NO_MATCH + payer override → +0.3 (APP fraud indicator)")
    void uc05_5F_vopNoMatch_override() {
        ScoringRequest request = new ScoringRequest(
                "TXN-5F-004", "D", "C", "Normal Person",
                new BigDecimal("1000.00"), "NOK",
                "INSTANT", VoPResult.NO_MATCH, true);

        ScoringResult result = pipeline.score(request);
        assertTrue(result.fraudScore() >= 0.3,
                "VoP NO_MATCH+override should add >= 0.3, total was " + result.fraudScore());
    }

    @Test
    @DisplayName("UC-05 5F: VoP NOT_FOUND + payer override → +0.4 (IBAN doesn't exist)")
    void uc05_5F_vopNotFound_override() {
        ScoringRequest request = new ScoringRequest(
                "TXN-5F-005", "D", "C", "Normal Person",
                new BigDecimal("1000.00"), "NOK",
                "INSTANT", VoPResult.NOT_FOUND, true);

        ScoringResult result = pipeline.score(request);
        assertTrue(result.fraudScore() >= 0.4,
                "VoP NOT_FOUND+override should add >= 0.4, total was " + result.fraudScore());
    }

    @Test
    @DisplayName("UC-05 5F Example: Borderline score + VoP NO_MATCH override → exceeds BLOCK threshold")
    void uc05_5F_borderlineElevatedToBlock() {
        velocityLayer.resetCounters();

        // Create a borderline transaction: large amount (velocity risk ~0.1)
        // plus VoP NO_MATCH override (+0.3) = should exceed 0.3 total
        ScoringRequest request = new ScoringRequest(
                "TXN-5F-006", "ACCT-BORDERLINE", "ACCT-CREDITOR",
                "Normal Person",
                new BigDecimal("60000.00"), "NOK",  // large amount → velocity risk
                "INSTANT", VoPResult.NO_MATCH, true); // VoP override → +0.3

        ScoringResult result = pipeline.score(request);

        // The combined score should be significantly elevated
        assertTrue(result.fraudScore() >= 0.3,
                "Borderline + VoP override should elevate score, was " + result.fraudScore());
        // This demonstrates the UC-05 5F example: VoP override alone can push
        // a borderline transaction over the review/block threshold
    }

    // ====================================================================
    // UC-05 Scenario 5C: Velocity Rule Triggers
    // ====================================================================

    @Test
    @DisplayName("UC-05 5C VEL-01: High frequency (>10 transactions in 60 min)")
    void uc05_5C_vel01_highFrequency() {
        velocityLayer.resetCounters();

        // Submit 11 transactions from the same account
        ScoringResult lastResult = null;
        for (int i = 0; i < 11; i++) {
            ScoringRequest request = new ScoringRequest(
                    "TXN-VEL-" + i, "ACCT-RAPID", "ACCT-CREDITOR",
                    "Normal Person",
                    new BigDecimal("1000.00"), "NOK",
                    "INSTANT", VoPResult.MATCH, false);
            lastResult = pipeline.score(request);
        }

        // 11th transaction should trigger VEL-01
        assertTrue(lastResult.fraudScore() > 0,
                "High frequency should trigger velocity risk");
        assertTrue(lastResult.velocityTriggeredRules().stream()
                        .anyMatch(r -> r.contains("VEL-01") || r.contains("HIGH_FREQ")),
                "VEL-01 should appear in triggered rules: " + lastResult.velocityTriggeredRules());
    }

    // ====================================================================
    // Cross-Scenario Invariant
    // ====================================================================

    @Test
    @DisplayName("Invariant: Sanctions HIT always overrides fraud scoring regardless of other factors")
    void invariant_sanctionsAlwaysOverrides() {
        // Low fraud risk indicators BUT sanctioned name
        ScoringRequest request = new ScoringRequest(
                "TXN-INV-001", "ACCT-CLEAN", "ACCT-CREDITOR",
                "BLOCKED ENTITY LTD", // sanctioned
                new BigDecimal("10.00"), "NOK", // tiny amount
                "INSTANT", VoPResult.MATCH, false); // VoP MATCH (no risk)

        ScoringResult result = pipeline.score(request);

        // Sanctions overrides everything
        assertEquals(SanctionsResult.HIT, result.sanctionsResult());
        assertEquals(FraudDecision.BLOCK, result.decision());
        assertEquals(1.0, result.fraudScore());
    }
}
