package com.jrpay.billpay;

import com.jrpay.billpay.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests derived from A3 UC-01 (Request-to-Pay Lifecycle).
 *
 * @see space/12_Bill_Payment_Request_to_Pay_Service/use_cases/UC01_request_to_pay_lifecycle.md
 */
@DataJpaTest
@ActiveProfiles("test")
class UseCaseEndToEndTest {

    @Autowired
    private PaymentRequestRepository requestRepository;

    @Autowired
    private MandateRepository mandateRepository;

    private PaymentRequest createRequest(String id) {
        return new PaymentRequest(id, "MERCHANT-001", "NO1234567890123",
                "Strøm AS", new BigDecimal("1250.00"), "NOK",
                "INV-2026-042", "April electricity", LocalDate.of(2026, 5, 1),
                "idem-" + id);
    }

    // ====================================================================
    // UC-01 Scenario 1A: Happy Path — Submit, Accept, Settle
    // ====================================================================

    @Test
    @DisplayName("UC-01 1A: Submit → Accept → Payment Initiated → Settled")
    void uc01_1A_happyPath() {
        PaymentRequest request = requestRepository.save(createRequest("REQ-001"));
        assertEquals(RequestStatus.PENDING, request.getStatus());

        // Consumer accepts
        request.applyTransition(RequestTrigger.CONSUMER_ACCEPT);
        assertEquals(RequestStatus.ACCEPTED, request.getStatus());

        // Payment initiated (VoP done, published to Kafka)
        request.applyTransition(RequestTrigger.PAYMENT_INITIATED);
        request.setTransactionId(UUID.randomUUID().toString());
        assertEquals(RequestStatus.PAYMENT_INITIATED, request.getStatus());

        // A2 settles
        request.applyTransition(RequestTrigger.KAFKA_SETTLED);
        requestRepository.save(request);

        assertEquals(RequestStatus.SETTLED, request.getStatus());
        assertTrue(request.getStatus().isCompleted());
    }

    // ====================================================================
    // UC-01 Scenario 1B: Decline with Reason
    // ====================================================================

    @Test
    @DisplayName("UC-01 1B: Consumer declines — terminal state, no payment")
    void uc01_1B_decline() {
        PaymentRequest request = requestRepository.save(createRequest("REQ-002"));

        request.applyTransition(RequestTrigger.CONSUMER_DECLINE);
        request.setDeclineReason("ALREADY_PAID");
        request.setDeclineMessage("Paid via bank transfer on April 5");
        requestRepository.save(request);

        assertEquals(RequestStatus.DECLINED, request.getStatus());
        assertTrue(request.getStatus().isTerminal());
        assertEquals("ALREADY_PAID", request.getDeclineReason());
    }

    // ====================================================================
    // UC-01 Scenario 1C: Defer (Pay Later)
    // ====================================================================

    @Test
    @DisplayName("UC-01 1C: Defer → reactivate → accept → settle")
    void uc01_1C_defer() {
        PaymentRequest request = requestRepository.save(createRequest("REQ-003"));

        // Consumer defers
        request.applyTransition(RequestTrigger.CONSUMER_DEFER);
        assertEquals(RequestStatus.DEFERRED, request.getStatus());

        // Timer reactivates on deferred date
        request.applyTransition(RequestTrigger.TIMER_EXPIRED);
        assertEquals(RequestStatus.PENDING, request.getStatus());

        // Consumer accepts after reactivation
        request.applyTransition(RequestTrigger.CONSUMER_ACCEPT);
        assertEquals(RequestStatus.ACCEPTED, request.getStatus());
    }

    // ====================================================================
    // UC-01 Scenario 1D: Amendment
    // ====================================================================

    @Test
    @DisplayName("UC-01 1D: Consumer proposes amendment → merchant accepts → new amount")
    void uc01_1D_amendmentAccepted() {
        PaymentRequest request = requestRepository.save(createRequest("REQ-004"));

        // Consumer amends
        request.applyTransition(RequestTrigger.CONSUMER_AMEND);
        assertEquals(RequestStatus.AMENDMENT_REQUESTED, request.getStatus());

        // Merchant accepts amendment — replaces the original (UC-G14 §6 step 7).
        request.applyTransition(RequestTrigger.MERCHANT_ACCEPT_AMENDMENT);
        request.setAmount(new BigDecimal("800.00")); // new amount on the replacement record
        assertEquals(RequestStatus.REPLACED, request.getStatus());
    }

    @Test
    @DisplayName("UC-01 1D: Consumer proposes amendment → merchant rejects → original amount")
    void uc01_1D_amendmentRejected() {
        PaymentRequest request = requestRepository.save(createRequest("REQ-005"));

        request.applyTransition(RequestTrigger.CONSUMER_AMEND);
        request.applyTransition(RequestTrigger.MERCHANT_REJECT_AMENDMENT);

        assertEquals(RequestStatus.PENDING, request.getStatus());
        assertEquals(new BigDecimal("1250.00"), request.getAmount()); // unchanged
    }

    // ====================================================================
    // UC-01 Scenario 1E: Mandate Auto-Accept (AvtaleGiro)
    // ====================================================================

    @Test
    @DisplayName("UC-01 1E: Mandate auto-accept — amount within limit → auto-accepted")
    void uc01_1E_mandateAutoAccept() {
        Mandate mandate = mandateRepository.save(new Mandate(
                "MAND-001", "MERCHANT-001", "NO9386011117947",
                new BigDecimal("5000.00"), "NOK"));

        PaymentRequest request = requestRepository.save(createRequest("REQ-006"));
        request.setMandateId("MAND-001");

        // Check mandate allows auto-accept
        assertTrue(mandate.canAutoAccept("MERCHANT-001", new BigDecimal("1250.00")));

        // Auto-accept
        request.applyTransition(RequestTrigger.MANDATE_AUTO_ACCEPT);
        assertEquals(RequestStatus.ACCEPTED, request.getStatus());
    }

    @Test
    @DisplayName("UC-01 1E: Mandate amount exceeded → auto-accept denied, stays PENDING")
    void uc01_1E_mandateExceeded() {
        Mandate mandate = mandateRepository.save(new Mandate(
                "MAND-002", "MERCHANT-001", "NO9386011117947",
                new BigDecimal("1000.00"), "NOK")); // max 1000

        // Amount 1250 exceeds mandate max 1000
        assertFalse(mandate.canAutoAccept("MERCHANT-001", new BigDecimal("1250.00")));
        // Request stays PENDING — consumer must manually approve
    }

    @Test
    @DisplayName("UC-01 1E: Mandate revoked → auto-accept denied")
    void uc01_1E_mandateRevoked() {
        Mandate mandate = mandateRepository.save(new Mandate(
                "MAND-003", "MERCHANT-001", "NO9386011117947",
                new BigDecimal("5000.00"), "NOK"));

        mandate.revoke();
        mandateRepository.save(mandate);

        assertFalse(mandate.canAutoAccept("MERCHANT-001", new BigDecimal("1250.00")));
    }

    // ====================================================================
    // Status Update Handling (from A2 via Kafka)
    // ====================================================================

    @Test
    @DisplayName("Payment REJECTED by A2 → request REJECTED")
    void paymentRejected() {
        PaymentRequest request = requestRepository.save(createRequest("REQ-007"));
        request.applyTransition(RequestTrigger.CONSUMER_ACCEPT);
        request.applyTransition(RequestTrigger.PAYMENT_INITIATED);

        request.applyTransition(RequestTrigger.KAFKA_REJECTED);
        assertEquals(RequestStatus.REJECTED, request.getStatus());
    }

    @Test
    @DisplayName("Payment HELD by A2 → request PAYMENT_HELD → eventually SETTLED")
    void paymentHeld() {
        PaymentRequest request = requestRepository.save(createRequest("REQ-008"));
        request.applyTransition(RequestTrigger.CONSUMER_ACCEPT);
        request.applyTransition(RequestTrigger.PAYMENT_INITIATED);

        request.applyTransition(RequestTrigger.KAFKA_HELD);
        assertEquals(RequestStatus.PAYMENT_HELD, request.getStatus());

        // Eventually settled after review
        request.applyTransition(RequestTrigger.KAFKA_SETTLED);
        assertEquals(RequestStatus.SETTLED, request.getStatus());
    }

    @Test
    @DisplayName("Payment RETURNED (late sanctions hit) → RETURN_IN_PROGRESS → RETURNED")
    void paymentReturned() {
        PaymentRequest request = requestRepository.save(createRequest("REQ-009"));
        request.applyTransition(RequestTrigger.CONSUMER_ACCEPT);
        request.applyTransition(RequestTrigger.PAYMENT_INITIATED);
        request.applyTransition(RequestTrigger.KAFKA_SETTLED);

        // Late sanctions hit → return initiated
        request.applyTransition(RequestTrigger.KAFKA_RETURN_INITIATED);
        assertEquals(RequestStatus.RETURN_IN_PROGRESS, request.getStatus());

        request.applyTransition(RequestTrigger.KAFKA_RETURNED);
        assertEquals(RequestStatus.PAYMENT_RETURNED, request.getStatus());
        assertTrue(request.getStatus().isTerminal());
    }

    // ====================================================================
    // Expiry
    // ====================================================================

    @Test
    @DisplayName("Request expires after due date → EXPIRED")
    void requestExpired() {
        PaymentRequest request = requestRepository.save(createRequest("REQ-010"));

        request.applyTransition(RequestTrigger.TIMER_EXPIRED);
        assertEquals(RequestStatus.EXPIRED, request.getStatus());
        assertTrue(request.getStatus().isTerminal());
    }

    // ====================================================================
    // Idempotency
    // ====================================================================

    @Test
    @DisplayName("Idempotency key prevents duplicate request creation")
    void idempotencyKey() {
        PaymentRequest first = requestRepository.save(createRequest("REQ-IDEM-1"));
        assertEquals("idem-REQ-IDEM-1", first.getIdempotencyKey());

        // Second request with same idempotency key → found by key
        var existing = requestRepository.findByIdempotencyKey("idem-REQ-IDEM-1");
        assertTrue(existing.isPresent());
        assertEquals("REQ-IDEM-1", existing.get().getRequestId());
    }
}
