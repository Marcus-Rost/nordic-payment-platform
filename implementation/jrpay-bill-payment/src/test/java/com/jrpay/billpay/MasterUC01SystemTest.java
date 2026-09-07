package com.jrpay.billpay;

import com.jrpay.billpay.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * System tests derived from master project UC-01 (Invoice Payment).
 * Tests the A3 portion of the end-to-end invoice payment flow:
 * Merchant submits → Consumer accepts → VoP → Payment → Settlement → Webhook.
 *
 * A6 (VoP), A2 (clearing), A5 (fraud), A1 (settlement) are simulated
 * by directly triggering state transitions that would come from Kafka.
 *
 * @see space/payment_processing/use_cases/UC01_invoice_payment.md
 */
@DataJpaTest
@ActiveProfiles("test")
class MasterUC01SystemTest {

    @Autowired
    private PaymentRequestRepository requestRepository;

    @Autowired
    private MandateRepository mandateRepository;

    // ====================================================================
    // UC-01: End-to-End Invoice Payment (A3 perspective)
    // ====================================================================

    @Test
    @DisplayName("UC-01: Full invoice payment lifecycle — submit → accept → settle → webhook")
    void uc01_fullInvoicePayment() {
        // Step 1: Merchant submits invoice via API
        PaymentRequest request = new PaymentRequest(
                "UC01-001", "ENERGINORD-AS", "NO1234567890123",
                "EnergiNord AS", new BigDecimal("1847.50"), "NOK",
                "INV-2026-042", "April electricity", LocalDate.of(2026, 5, 1),
                "energinord-inv-042");
        request = requestRepository.save(request);
        assertEquals(RequestStatus.PENDING, request.getStatus());

        // Step 2: Consumer views pending requests and accepts (instant payment)
        request.applyTransition(RequestTrigger.CONSUMER_ACCEPT);
        assertEquals(RequestStatus.ACCEPTED, request.getStatus());

        // Step 3: A3 calls A6 for VoP check → MATCH (simulated)
        // Step 4: A3 publishes to payment.instant-instructions
        request.applyTransition(RequestTrigger.PAYMENT_INITIATED);
        request.setTransactionId("TXN-UC01-001");
        assertEquals(RequestStatus.PAYMENT_INITIATED, request.getStatus());

        // Step 5-6: A2 → A5 → A1 (simulated by Kafka status update)
        request.applyTransition(RequestTrigger.KAFKA_SETTLED);
        requestRepository.save(request);

        // Step 7: Verify final state
        assertEquals(RequestStatus.SETTLED, request.getStatus());
        assertEquals("TXN-UC01-001", request.getTransactionId());
    }

    @Test
    @DisplayName("UC-01 Alt: Payment rejected by A2 (fraud) → request REJECTED")
    void uc01_paymentRejectedByFraud() {
        PaymentRequest request = new PaymentRequest(
                "UC01-002", "MERCHANT-X", "NO1111", "Merchant X",
                new BigDecimal("95000.00"), "NOK", "INV-HIGH",
                "Suspicious large payment", LocalDate.of(2026, 5, 1), "idem-uc01-002");
        request = requestRepository.save(request);

        request.applyTransition(RequestTrigger.CONSUMER_ACCEPT);
        request.applyTransition(RequestTrigger.PAYMENT_INITIATED);

        // A5 fraud score exceeds threshold → A2 rejects → status update
        request.applyTransition(RequestTrigger.KAFKA_REJECTED);
        requestRepository.save(request);

        assertEquals(RequestStatus.REJECTED, request.getStatus());
    }

    @Test
    @DisplayName("UC-01 Alt: Payment settled then returned (late sanctions hit)")
    void uc01_settledThenReturned() {
        PaymentRequest request = new PaymentRequest(
                "UC01-003", "MERCHANT-Y", "NO2222", "Merchant Y",
                new BigDecimal("5000.00"), "NOK", "INV-RETURN",
                "Normal payment", LocalDate.of(2026, 5, 1), "idem-uc01-003");
        request = requestRepository.save(request);

        // Full flow to SETTLED
        request.applyTransition(RequestTrigger.CONSUMER_ACCEPT);
        request.applyTransition(RequestTrigger.PAYMENT_INITIATED);
        request.applyTransition(RequestTrigger.KAFKA_SETTLED);

        // Late sanctions hit → return
        request.applyTransition(RequestTrigger.KAFKA_RETURN_INITIATED);
        assertEquals(RequestStatus.RETURN_IN_PROGRESS, request.getStatus());

        request.applyTransition(RequestTrigger.KAFKA_RETURNED);
        requestRepository.save(request);

        assertEquals(RequestStatus.PAYMENT_RETURNED, request.getStatus());
    }

    // ====================================================================
    // UC-01 with Mandate (AvtaleGiro)
    // ====================================================================

    @Test
    @DisplayName("UC-01 Mandate: Monthly auto-accept → instant settlement")
    void uc01_mandateAutoAccept() {
        // Consumer has active mandate for EnergiNord
        Mandate mandate = mandateRepository.save(new Mandate(
                "MAND-UC01", "ENERGINORD-AS", "NO9386011117947",
                new BigDecimal("5000.00"), "NOK"));

        // Merchant submits monthly invoice with mandate reference
        PaymentRequest request = new PaymentRequest(
                "UC01-004", "ENERGINORD-AS", "NO1234567890123",
                "EnergiNord AS", new BigDecimal("1847.50"), "NOK",
                "INV-2026-05", "May electricity", LocalDate.of(2026, 6, 1),
                "energinord-inv-05");
        request.setMandateId("MAND-UC01");
        request = requestRepository.save(request);

        // Mandate check: amount 1847.50 <= max 5000 → auto-accept
        assertTrue(mandate.canAutoAccept("ENERGINORD-AS", new BigDecimal("1847.50")));
        request.applyTransition(RequestTrigger.MANDATE_AUTO_ACCEPT);
        assertEquals(RequestStatus.ACCEPTED, request.getStatus());

        // Payment initiated and settled
        request.applyTransition(RequestTrigger.PAYMENT_INITIATED);
        request.applyTransition(RequestTrigger.KAFKA_SETTLED);
        requestRepository.save(request);

        assertEquals(RequestStatus.SETTLED, request.getStatus());
    }

    // ====================================================================
    // Invariants
    // ====================================================================

    @Test
    @DisplayName("Invariant: No path skips ACCEPTED state (consumer must approve)")
    void invariant_consumerMustApprove() {
        final PaymentRequest req = requestRepository.save(new PaymentRequest(
                "UC01-INV1", "MERCHANT", "NO1111", "Merchant",
                new BigDecimal("100.00"), "NOK", "REF", "Desc",
                LocalDate.now(), "idem-inv1"));

        // Cannot jump from PENDING to PAYMENT_INITIATED
        assertThrows(IllegalStateException.class,
                () -> req.applyTransition(RequestTrigger.PAYMENT_INITIATED));
    }

    @Test
    @DisplayName("Invariant: SETTLED → only RETURN_INITIATED allowed (not re-reject)")
    void invariant_settledOnlyReturn() {
        final PaymentRequest req = requestRepository.save(new PaymentRequest(
                "UC01-INV2", "MERCHANT", "NO1111", "Merchant",
                new BigDecimal("100.00"), "NOK", "REF", "Desc",
                LocalDate.now(), "idem-inv2"));

        req.applyTransition(RequestTrigger.CONSUMER_ACCEPT);
        req.applyTransition(RequestTrigger.PAYMENT_INITIATED);
        req.applyTransition(RequestTrigger.KAFKA_SETTLED);

        // Cannot re-reject a settled payment
        assertThrows(IllegalStateException.class,
                () -> req.applyTransition(RequestTrigger.KAFKA_REJECTED));

        // But CAN initiate return (late sanctions)
        assertDoesNotThrow(() -> req.applyTransition(RequestTrigger.KAFKA_RETURN_INITIATED));
    }
}
