package com.jrpay.clearing;

import com.jrpay.domain.payment.FraudDecision;
import com.jrpay.domain.payment.SanctionsResult;
import com.jrpay.clearing.instant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * System tests derived from master project UC-08 (Instant Payment).
 * Tests the A2 orchestration as described in the master use case.
 *
 * Simulates A1 (reservation) and A5 (fraud) responses to test
 * the full A2 orchestration flow.
 *
 * @see space/payment_processing/use_cases/UC08_instant_payment.md
 */
@DataJpaTest
@ActiveProfiles("test")
class MasterUC08SystemTest {

    @Autowired
    private ClearingRecordRepository repository;

    private InstantPaymentProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new InstantPaymentProcessor(repository, new BigDecimal("100000"));
    }

    // ====================================================================
    // UC-08: Happy Path — Complete Instant Payment in <5 seconds
    // ====================================================================

    @Test
    @DisplayName("UC-08 Happy Path: Full instant payment → SETTLED")
    void uc08_happyPath() {
        // Step 1: Payer submits via A3 (VoP already done, result=MATCH)
        processor.receiveInstruction("UC08-001", "NO9386011117947", "NO1234567890123",
                "Ola Nordmann AS", new BigDecimal("5000.00"), "NOK", "MATCH", false);

        // Step 2: A2 validates and requests reservation from A1
        processor.requestReservation("UC08-001");

        // Step 3: A1 confirms reservation
        processor.handleReservationResponse("UC08-001", true, null);

        // Step 4: A5 returns sanctions CLEAR + fraud PASS
        processor.handleFraudResponse("UC08-001",
                SanctionsResult.CLEAR, FraudDecision.PASS, 0.02);

        // Step 5: A2 settles
        ClearingRecord result = processor.settle("UC08-001");

        assertEquals(PaymentState.SETTLED, result.getStatus());
        assertEquals("CLEAR", result.getSanctionsResult());
        assertEquals(0.02, result.getFraudScore());
    }

    // ====================================================================
    // UC-08 AF-01: Insufficient Balance
    // ====================================================================

    @Test
    @DisplayName("UC-08 AF-01: Insufficient balance → REJECTED at reservation")
    void uc08_AF01_insufficientBalance() {
        processor.receiveInstruction("UC08-AF01", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("UC08-AF01");

        ClearingRecord result = processor.handleReservationResponse(
                "UC08-AF01", false, "INSUFFICIENT_BALANCE");

        assertEquals(PaymentState.REJECTED, result.getStatus());
        assertEquals("INSUFFICIENT_BALANCE", result.getRejectionReason());
    }

    // ====================================================================
    // UC-08 AF-02: Account Frozen
    // ====================================================================

    @Test
    @DisplayName("UC-08 AF-02: Account frozen → REJECTED at reservation")
    void uc08_AF02_accountFrozen() {
        processor.receiveInstruction("UC08-AF02", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("UC08-AF02");

        ClearingRecord result = processor.handleReservationResponse(
                "UC08-AF02", false, "ACCOUNT_FROZEN");

        assertEquals(PaymentState.REJECTED, result.getStatus());
        assertEquals("ACCOUNT_FROZEN", result.getRejectionReason());
    }

    // ====================================================================
    // UC-08 AF-03: Sanctions HIT
    // ====================================================================

    @Test
    @DisplayName("UC-08 AF-03: Sanctions HIT → REJECTED (sanctions cannot be skipped)")
    void uc08_AF03_sanctionsHit() {
        processor.receiveInstruction("UC08-AF03", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("UC08-AF03");
        processor.handleReservationResponse("UC08-AF03", true, null);

        ClearingRecord result = processor.handleFraudResponse("UC08-AF03",
                SanctionsResult.HIT, FraudDecision.BLOCK, 1.0);

        assertEquals(PaymentState.REJECTED, result.getStatus());
        assertEquals("SANCTIONS_HIT", result.getRejectionReason());
    }

    // ====================================================================
    // UC-08 AF-04: Fraud BLOCK
    // ====================================================================

    @Test
    @DisplayName("UC-08 AF-04: Fraud score exceeds BLOCK threshold → REJECTED")
    void uc08_AF04_fraudBlock() {
        processor.receiveInstruction("UC08-AF04", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "NO_MATCH", true);
        processor.requestReservation("UC08-AF04");
        processor.handleReservationResponse("UC08-AF04", true, null);

        ClearingRecord result = processor.handleFraudResponse("UC08-AF04",
                SanctionsResult.CLEAR, FraudDecision.BLOCK, 0.85);

        assertEquals(PaymentState.REJECTED, result.getStatus());
        assertEquals("FRAUD_BLOCKED", result.getRejectionReason());
    }

    // ====================================================================
    // UC-08 AF-05: Fraud Timeout (2 cases from our review clarification)
    // ====================================================================

    @Test
    @DisplayName("UC-08 AF-05 Case A: Partial response (sanctions CLEAR, fraud TIMEOUT) → auto-pass")
    void uc08_AF05_caseA_partialResponse() {
        processor.receiveInstruction("UC08-AF05A", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("UC08-AF05A");
        processor.handleReservationResponse("UC08-AF05A", true, null);

        // A5 partial: sanctions confirmed CLEAR, fraud TIMEOUT
        ClearingRecord result = processor.handleFraudResponse("UC08-AF05A",
                SanctionsResult.CLEAR, FraudDecision.TIMEOUT, 0);

        // Payment proceeds (auto-pass fraud, sanctions confirmed clear)
        assertEquals(PaymentState.SETTLING, result.getStatus());

        ClearingRecord settled = processor.settle("UC08-AF05A");
        assertEquals(PaymentState.SETTLED, settled.getStatus());
    }

    @Test
    @DisplayName("UC-08 AF-05 Case B: Complete timeout (no A5 response) → REJECTED")
    void uc08_AF05_caseB_completeTimeout() {
        processor.receiveInstruction("UC08-AF05B", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("UC08-AF05B");
        processor.handleReservationResponse("UC08-AF05B", true, null);

        // Hard-limit punctuator fires — no A5 response
        ClearingRecord result = processor.handleTimeout("UC08-AF05B", "SANCTIONS_UNAVAILABLE");

        assertEquals(PaymentState.REJECTED, result.getStatus());
    }

    // ====================================================================
    // UC-08 AF-06: 7-Second Hard Limit
    // ====================================================================

    @Test
    @DisplayName("UC-08 AF-06: 7-second hard limit → REJECTED regardless of state")
    void uc08_AF06_hardLimit() {
        processor.receiveInstruction("UC08-AF06", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("UC08-AF06");
        processor.handleReservationResponse("UC08-AF06", true, null);
        // Now AWAITING_FRAUD — hard limit fires

        ClearingRecord result = processor.handleTimeout("UC08-AF06", "HARD_LIMIT_7S");

        assertEquals(PaymentState.REJECTED, result.getStatus());
        assertEquals("HARD_LIMIT_7S", result.getRejectionReason());
    }

    // ====================================================================
    // Invariants from Master Architecture
    // ====================================================================

    @Test
    @DisplayName("Invariant: Sanctions screening mandatory — no path to SETTLED without A5 response")
    void invariant_sanctionsMandatory() {
        processor.receiveInstruction("UC08-INV1", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("UC08-INV1");
        processor.handleReservationResponse("UC08-INV1", true, null);

        // Cannot settle without fraud response
        ClearingRecord result = processor.settle("UC08-INV1");
        assertEquals(PaymentState.AWAITING_FRAUD, result.getStatus()); // blocked

        // Must go through fraud response
        processor.handleFraudResponse("UC08-INV1",
                SanctionsResult.CLEAR, FraudDecision.PASS, 0.01);
        result = processor.settle("UC08-INV1");
        assertEquals(PaymentState.SETTLED, result.getStatus());
    }

    @Test
    @DisplayName("Invariant: SETTLED is terminal — cannot transition further")
    void invariant_settledTerminal() {
        processor.receiveInstruction("UC08-INV2", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("UC08-INV2");
        processor.handleReservationResponse("UC08-INV2", true, null);
        processor.handleFraudResponse("UC08-INV2", SanctionsResult.CLEAR, FraudDecision.PASS, 0.01);
        processor.settle("UC08-INV2");

        // Cannot reject a settled payment
        ClearingRecord result = processor.handleTimeout("UC08-INV2", "LATE");
        assertEquals(PaymentState.SETTLED, result.getStatus());
    }

    @Test
    @DisplayName("Invariant: REJECTED is terminal — cannot transition further")
    void invariant_rejectedTerminal() {
        processor.receiveInstruction("UC08-INV3", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("UC08-INV3");
        processor.handleReservationResponse("UC08-INV3", false, "INSUFFICIENT_BALANCE");

        // Cannot proceed after rejection
        ClearingRecord result = processor.handleFraudResponse("UC08-INV3",
                SanctionsResult.CLEAR, FraudDecision.PASS, 0.01);
        assertEquals(PaymentState.REJECTED, result.getStatus());
    }
}
