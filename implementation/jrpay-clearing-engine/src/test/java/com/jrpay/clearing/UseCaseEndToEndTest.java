package com.jrpay.clearing;

import com.jrpay.domain.payment.FraudDecision;
import com.jrpay.domain.payment.SanctionsResult;
import com.jrpay.clearing.duplicate.SemanticDuplicateDetector;
import com.jrpay.clearing.instant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests derived from A2 UC-01 (Batch and Instant Clearing).
 *
 * @see space/11_Payment_Clearing_Settlement_Engine/use_cases/UC01_batch_and_instant_clearing.md
 */
@DataJpaTest
@ActiveProfiles("test")
class UseCaseEndToEndTest {

    @Autowired
    private ClearingRecordRepository repository;

    private InstantPaymentProcessor processor;
    private SemanticDuplicateDetector duplicateDetector;

    @BeforeEach
    void setUp() {
        processor = new InstantPaymentProcessor(repository, new BigDecimal("100000"));
        duplicateDetector = new SemanticDuplicateDetector();
    }

    // ====================================================================
    // UC-01 Scenario 1B: Instant Payment Happy Path
    // ====================================================================

    @Test
    @DisplayName("UC-01 1B: Instant payment happy path — 5 steps to SETTLED")
    void uc01_1B_instantHappyPath() {
        // Step 1: A3/A4 publishes instruction
        ClearingRecord record = processor.receiveInstruction(
                "PAY-001", "NO9386011117947", "NO1234567890123",
                "Ola Nordmann AS", new BigDecimal("5000.00"), "NOK",
                "MATCH", false);
        assertEquals(PaymentState.RECEIVED, record.getStatus());

        // Step 2-3: Request reservation from A1 → confirmed
        processor.requestReservation("PAY-001");
        processor.handleReservationResponse("PAY-001", true, null);

        // Step 4-5: Fraud+sanctions from A5 → PASS
        processor.handleFraudResponse("PAY-001",
                SanctionsResult.CLEAR, FraudDecision.PASS, 0.05);

        // Step 6-8: Settlement
        ClearingRecord settled = processor.settle("PAY-001");

        assertEquals(PaymentState.SETTLED, settled.getStatus());
        assertNotNull(settled.getSettledAt());
        assertEquals("CLEAR", settled.getSanctionsResult());
        assertEquals(0.05, settled.getFraudScore());
    }

    // ====================================================================
    // UC-01 Scenario 1C: Fraud Timeout → Auto-Pass
    // ====================================================================

    @Test
    @DisplayName("UC-01 1C: Fraud timeout → auto-pass, proceed to settlement")
    void uc01_1C_fraudTimeout() {
        processor.receiveInstruction("PAY-002", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("PAY-002");
        processor.handleReservationResponse("PAY-002", true, null);

        // A5 responds with sanctions CLEAR but fraud TIMEOUT
        processor.handleFraudResponse("PAY-002",
                SanctionsResult.CLEAR, FraudDecision.TIMEOUT, 0);

        // Payment proceeds (sanctions confirmed clear, fraud auto-passed)
        ClearingRecord result = processor.settle("PAY-002");
        assertEquals(PaymentState.SETTLED, result.getStatus());
    }

    // ====================================================================
    // UC-01 Scenario 1D: Sanctions Timeout → REJECT
    // ====================================================================

    @Test
    @DisplayName("UC-01 1D: Sanctions timeout → REJECT (cannot skip sanctions)")
    void uc01_1D_sanctionsTimeout() {
        processor.receiveInstruction("PAY-003", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("PAY-003");
        processor.handleReservationResponse("PAY-003", true, null);

        // A5: sanctions UNAVAILABLE
        ClearingRecord result = processor.handleFraudResponse("PAY-003",
                SanctionsResult.UNAVAILABLE, FraudDecision.TIMEOUT, 0);

        assertEquals(PaymentState.REJECTED, result.getStatus());
        assertEquals("SANCTIONS_UNAVAILABLE", result.getRejectionReason());
    }

    // ====================================================================
    // UC-01 Scenario 1E: 7-Second Hard Limit Breach → REJECT
    // ====================================================================

    @Test
    @DisplayName("UC-01 1E: 7-second hard limit → REJECT with TIMEOUT")
    void uc01_1E_hardLimit() {
        processor.receiveInstruction("PAY-004", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("PAY-004");

        // Hard limit fires while AWAITING_RESERVATION
        ClearingRecord result = processor.handleTimeout("PAY-004", "HARD_LIMIT_7S");

        assertEquals(PaymentState.REJECTED, result.getStatus());
        assertEquals("HARD_LIMIT_7S", result.getRejectionReason());
    }

    // ====================================================================
    // UC-01 Scenario 1F: Semantic Duplicate Detection
    // ====================================================================

    @Test
    @DisplayName("UC-01 1F: Semantic duplicate detected — same debtor+creditor+amount in 10 min")
    void uc01_1F_semanticDuplicate() {
        duplicateDetector.reset();

        // First payment
        Optional<String> first = duplicateDetector.check(
                "NO1111", "NO2222", new BigDecimal("5000.00"), "NOK", "PAY-001");
        assertTrue(first.isEmpty(), "First payment should not be flagged");

        // Second payment — same debtor+creditor+amount, different transaction ID
        Optional<String> second = duplicateDetector.check(
                "NO1111", "NO2222", new BigDecimal("5000.00"), "NOK", "PAY-002");
        assertTrue(second.isPresent(), "Second payment should be flagged as duplicate");
        assertEquals("PAY-001", second.get());
    }

    @Test
    @DisplayName("UC-01 1F: Different amount → not a duplicate")
    void uc01_1F_differentAmountNotDuplicate() {
        duplicateDetector.reset();

        duplicateDetector.check("NO1111", "NO2222", new BigDecimal("5000.00"), "NOK", "PAY-001");

        Optional<String> result = duplicateDetector.check(
                "NO1111", "NO2222", new BigDecimal("6000.00"), "NOK", "PAY-002");
        assertTrue(result.isEmpty(), "Different amount should not be flagged");
    }

    @Test
    @DisplayName("UC-01 1F: Different creditor → not a duplicate")
    void uc01_1F_differentCreditorNotDuplicate() {
        duplicateDetector.reset();

        duplicateDetector.check("NO1111", "NO2222", new BigDecimal("5000.00"), "NOK", "PAY-001");

        Optional<String> result = duplicateDetector.check(
                "NO1111", "NO3333", new BigDecimal("5000.00"), "NOK", "PAY-002");
        assertTrue(result.isEmpty(), "Different creditor should not be flagged");
    }

    // ====================================================================
    // UC-01 Scenario 1H: Amount Exceeds Limit
    // ====================================================================

    @Test
    @DisplayName("UC-01 1H: Amount exceeds EUR 100,000 limit → immediate REJECT")
    void uc01_1H_amountExceeded() {
        ClearingRecord record = processor.receiveInstruction(
                "PAY-005", "NO1111", "NO2222", "Name",
                new BigDecimal("150000.00"), "EUR", "MATCH", false);

        assertEquals(PaymentState.REJECTED, record.getStatus());
        assertEquals("AMOUNT_EXCEEDED", record.getRejectionReason());
    }

    // ====================================================================
    // Invariants
    // ====================================================================

    @Test
    @DisplayName("Invariant: No path from RECEIVED to SETTLED skips fraud screening")
    void invariant_noPathSkipsFraud() {
        processor.receiveInstruction("PAY-INV", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("PAY-INV");
        processor.handleReservationResponse("PAY-INV", true, null);

        // Try to settle without fraud response → still AWAITING_FRAUD
        ClearingRecord result = processor.settle("PAY-INV");
        assertEquals(PaymentState.AWAITING_FRAUD, result.getStatus());
    }

    @Test
    @DisplayName("Invariant: SETTLED payment cannot be rejected")
    void invariant_settledCannotBeRejected() {
        processor.receiveInstruction("PAY-TERM", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("PAY-TERM");
        processor.handleReservationResponse("PAY-TERM", true, null);
        processor.handleFraudResponse("PAY-TERM", SanctionsResult.CLEAR, FraudDecision.PASS, 0.05);
        processor.settle("PAY-TERM");

        // Try to reject after settlement
        ClearingRecord result = processor.handleTimeout("PAY-TERM", "LATE");
        assertEquals(PaymentState.SETTLED, result.getStatus()); // unchanged
    }
}
