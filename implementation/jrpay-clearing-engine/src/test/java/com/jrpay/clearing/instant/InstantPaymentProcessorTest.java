package com.jrpay.clearing.instant;

import com.jrpay.domain.payment.FraudDecision;
import com.jrpay.domain.payment.SanctionsResult;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the instant payment state machine.
 */
@DataJpaTest
@ActiveProfiles("test")
class InstantPaymentProcessorTest {

    @Autowired
    private ClearingRecordRepository repository;

    private InstantPaymentProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new InstantPaymentProcessor(repository, new BigDecimal("100000"));
    }

    @Test
    @DisplayName("Receive instruction creates record in RECEIVED state")
    void receiveCreatesRecord() {
        ClearingRecord record = processor.receiveInstruction(
                "TXN-001", "NO1111", "NO2222", "Ola Nordmann",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);

        assertEquals(PaymentState.RECEIVED, record.getStatus());
        assertEquals("TXN-001", record.getTransactionId());
    }

    @Test
    @DisplayName("Receive is idempotent")
    void receiveIdempotent() {
        processor.receiveInstruction("TXN-001", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.receiveInstruction("TXN-001", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);

        assertEquals(1, repository.count());
    }

    @Test
    @DisplayName("Amount exceeding limit → immediate REJECTED")
    void amountExceeded() {
        ClearingRecord record = processor.receiveInstruction(
                "TXN-002", "NO1111", "NO2222", "Name",
                new BigDecimal("150000.00"), "NOK", "MATCH", false);

        assertEquals(PaymentState.REJECTED, record.getStatus());
        assertEquals("AMOUNT_EXCEEDED", record.getRejectionReason());
    }

    @Test
    @DisplayName("Happy path: RECEIVED → AWAITING_RESERVATION → AWAITING_FRAUD → SETTLING → SETTLED")
    void happyPath() {
        processor.receiveInstruction("TXN-003", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);

        // Request reservation
        ClearingRecord r1 = processor.requestReservation("TXN-003");
        assertEquals(PaymentState.AWAITING_RESERVATION, r1.getStatus());
        assertNotNull(r1.getReservationId());

        // Reservation confirmed
        ClearingRecord r2 = processor.handleReservationResponse("TXN-003", true, null);
        assertEquals(PaymentState.AWAITING_FRAUD, r2.getStatus());

        // Fraud PASS
        ClearingRecord r3 = processor.handleFraudResponse("TXN-003",
                SanctionsResult.CLEAR, FraudDecision.PASS, 0.05);
        assertEquals(PaymentState.SETTLING, r3.getStatus());

        // Settle
        ClearingRecord r4 = processor.settle("TXN-003");
        assertEquals(PaymentState.SETTLED, r4.getStatus());
        assertNotNull(r4.getSettledAt());
    }

    @Test
    @DisplayName("Reservation rejected → REJECTED (insufficient balance)")
    void reservationRejected() {
        processor.receiveInstruction("TXN-004", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("TXN-004");

        ClearingRecord result = processor.handleReservationResponse(
                "TXN-004", false, "INSUFFICIENT_BALANCE");

        assertEquals(PaymentState.REJECTED, result.getStatus());
        assertEquals("INSUFFICIENT_BALANCE", result.getRejectionReason());
    }

    @Test
    @DisplayName("Sanctions HIT → REJECTED (cannot skip)")
    void sanctionsHit() {
        processor.receiveInstruction("TXN-005", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("TXN-005");
        processor.handleReservationResponse("TXN-005", true, null);

        ClearingRecord result = processor.handleFraudResponse("TXN-005",
                SanctionsResult.HIT, FraudDecision.BLOCK, 1.0);

        assertEquals(PaymentState.REJECTED, result.getStatus());
        assertEquals("SANCTIONS_HIT", result.getRejectionReason());
    }

    @Test
    @DisplayName("Sanctions UNAVAILABLE → REJECTED (cannot skip)")
    void sanctionsUnavailable() {
        processor.receiveInstruction("TXN-006", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("TXN-006");
        processor.handleReservationResponse("TXN-006", true, null);

        ClearingRecord result = processor.handleFraudResponse("TXN-006",
                SanctionsResult.UNAVAILABLE, FraudDecision.TIMEOUT, 0);

        assertEquals(PaymentState.REJECTED, result.getStatus());
        assertEquals("SANCTIONS_UNAVAILABLE", result.getRejectionReason());
    }

    @Test
    @DisplayName("Fraud BLOCK with sanctions CLEAR → REJECTED")
    void fraudBlock() {
        processor.receiveInstruction("TXN-007", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("TXN-007");
        processor.handleReservationResponse("TXN-007", true, null);

        ClearingRecord result = processor.handleFraudResponse("TXN-007",
                SanctionsResult.CLEAR, FraudDecision.BLOCK, 0.85);

        assertEquals(PaymentState.REJECTED, result.getStatus());
        assertEquals("FRAUD_BLOCKED", result.getRejectionReason());
    }

    @Test
    @DisplayName("Fraud TIMEOUT with sanctions CLEAR → auto-pass, proceed to SETTLING")
    void fraudTimeoutSanctionsClear() {
        processor.receiveInstruction("TXN-008", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("TXN-008");
        processor.handleReservationResponse("TXN-008", true, null);

        ClearingRecord result = processor.handleFraudResponse("TXN-008",
                SanctionsResult.CLEAR, FraudDecision.TIMEOUT, 0);

        assertEquals(PaymentState.SETTLING, result.getStatus());
    }

    @Test
    @DisplayName("7-second hard limit timeout → REJECTED")
    void hardLimitTimeout() {
        processor.receiveInstruction("TXN-009", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("TXN-009");

        ClearingRecord result = processor.handleTimeout("TXN-009", "HARD_LIMIT_7S");

        assertEquals(PaymentState.REJECTED, result.getStatus());
        assertEquals("HARD_LIMIT_7S", result.getRejectionReason());
    }

    @Test
    @DisplayName("Cannot transition from terminal state")
    void terminalStateBlocks() {
        processor.receiveInstruction("TXN-010", "NO1111", "NO2222", "Name",
                new BigDecimal("5000.00"), "NOK", "MATCH", false);
        processor.requestReservation("TXN-010");
        processor.handleReservationResponse("TXN-010", true, null);
        processor.handleFraudResponse("TXN-010", SanctionsResult.CLEAR, FraudDecision.PASS, 0.05);
        processor.settle("TXN-010");

        // SETTLED → cannot reject
        ClearingRecord result = processor.handleTimeout("TXN-010", "LATE_TIMEOUT");
        assertEquals(PaymentState.SETTLED, result.getStatus()); // unchanged
    }

    @Test
    @DisplayName("Deterministic reservation ID derived from transaction ID")
    void deterministicReservationId() {
        String id1 = InstantPaymentProcessor.deterministicId("TXN-001", "balance-reservation");
        String id2 = InstantPaymentProcessor.deterministicId("TXN-001", "balance-reservation");
        assertEquals(id1, id2); // same input → same output

        String id3 = InstantPaymentProcessor.deterministicId("TXN-002", "balance-reservation");
        assertNotEquals(id1, id3); // different transaction → different ID
    }
}
