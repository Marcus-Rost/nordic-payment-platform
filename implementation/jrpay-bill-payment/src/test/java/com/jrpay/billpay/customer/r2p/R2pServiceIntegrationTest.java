package com.jrpay.billpay.customer.r2p;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jrpay.billpay.audit.CustomerAuditEvent;
import com.jrpay.billpay.audit.CustomerAuditPublisher;
import com.jrpay.billpay.config.AuthenticatedUser;
import com.jrpay.billpay.customer.BillPayCustomerIntegrationTestBase;
import com.jrpay.billpay.customer.CustomerProblemException;
import com.jrpay.billpay.customer.r2p.dto.R2pAcceptRequest;
import com.jrpay.billpay.customer.r2p.dto.R2pAmendRequest;
import com.jrpay.billpay.customer.r2p.dto.R2pDeclineRequest;
import com.jrpay.billpay.customer.r2p.dto.R2pDeferRequest;
import com.jrpay.billpay.domain.PaymentRequest;
import com.jrpay.billpay.domain.PaymentRequestRepository;
import com.jrpay.billpay.domain.RequestStatus;
import com.jrpay.billpay.sca.ScaInitiator;
import com.jrpay.billpay.sca.StepUpValidator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class R2pServiceIntegrationTest extends BillPayCustomerIntegrationTestBase {

    @Autowired R2pService r2pService;
    @Autowired PaymentRequestRepository requests;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean ScaInitiator scaInitiator;
    @MockitoBean StepUpValidator stepUpValidator;
    @MockitoBean CustomerAuditPublisher audit;
    @MockitoBean AuthenticatedUser caller;

    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-0000c0011111");

    @BeforeEach
    void setup() {
        when(caller.requireUserId()).thenReturn(userId);
        when(caller.currentUserId()).thenReturn(Optional.of(userId));
        when(caller.currentSubject()).thenReturn("customer:" + userId);
        when(caller.isCustomer()).thenReturn(true);
        when(caller.hasScope(anyString())).thenReturn(true);
        when(caller.currentRawJwt()).thenReturn(Optional.of("dummy.jwt.token"));
        when(audit.publish(any(CustomerAuditEvent.class))).thenReturn("01HTESTR2P0000000000000000");
        // Default ScaInitiator: returns a fresh session bound to whatever inputs the
        // service computed (the test verifies the dl_hash matches the validator).
        when(scaInitiator.start(any(), org.mockito.ArgumentMatchers.eq(false)))
                .thenAnswer(inv -> new ScaInitiator.InitiateResult(
                        UUID.randomUUID(), "stub-dl-hash", "TOTP", false, null));
        when(stepUpValidator.validate(anyString(), anyString())).thenAnswer(inv ->
                new StepUpValidator.ValidationResult(true, StepUpValidator.Outcome.VALID,
                        userId, UUID.randomUUID(), null));
        jdbc.execute("TRUNCATE TABLE r2p_history, payment_requests, "
                + "mandate_executions, mandates, beneficiaries CASCADE");
    }

    @Test
    void listReturnsPendingRequestsForCaller() {
        seed("R-1", RequestStatus.PENDING);
        seed("R-2", RequestStatus.DECLINED);
        var page = r2pService.list(null, null, 0, 25);
        assertThat(page.items()).extracting("requestId").containsExactly("R-1");
    }

    @Test
    void listFilterByStatusOnly() {
        seed("R-1", RequestStatus.PENDING);
        seed("R-2", RequestStatus.SETTLED);
        var page = r2pService.list(List.of(RequestStatus.SETTLED), null, 0, 25);
        assertThat(page.items()).extracting("requestId").containsExactly("R-2");
    }

    @Test
    void declineMovesToDeclinedAndAuditsOnce() {
        seed("R-1", RequestStatus.PENDING);
        var detail = r2pService.decline("R-1",
                new R2pDeclineRequest("WRONG_AMOUNT", "Amount differs from invoice"));
        assertThat(detail.summary().status()).isEqualTo("DECLINED");
        verify(audit, atLeastOnce()).publish(any(CustomerAuditEvent.class));
    }

    @Test
    void declineWithReasonOtherRequiresLongerNote() {
        seed("R-1", RequestStatus.PENDING);
        assertThatThrownBy(() -> r2pService.decline("R-1",
                new R2pDeclineRequest("OTHER", "no")))
                .isInstanceOfSatisfying(CustomerProblemException.class, e ->
                        assertThat(e.getType()).contains("validation"));
    }

    @Test
    void acceptWithoutStepUpReturnsPendingScaAndPersistsPaymentId() {
        seed("R-1", RequestStatus.PENDING);
        var outcome = r2pService.accept("R-1",
                new R2pAcceptRequest("ACC-001", "INSTANT", "MATCH", false), null);
        assertThat(outcome).isInstanceOf(R2pService.AcceptOutcome.PendingSca.class);
        var pending = ((R2pService.AcceptOutcome.PendingSca) outcome).pending();
        assertThat(pending.scaSessionId()).isNotNull();
        assertThat(pending.dlHash()).isNotBlank();
        // R2P stays PENDING until SCA-verified accept.
        var pr = requests.findById("R-1").orElseThrow();
        assertThat(pr.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(pr.getPaymentId()).isNotNull();
    }

    @Test
    void acceptWithVerifiedStepUpTransitionsToAccepted() {
        seed("R-1", RequestStatus.PENDING);
        var outcome = r2pService.accept("R-1",
                new R2pAcceptRequest("ACC-001", "INSTANT", null, null), "valid-step-up-token");
        assertThat(outcome).isInstanceOf(R2pService.AcceptOutcome.Granted.class);
        var pr = requests.findById("R-1").orElseThrow();
        assertThat(pr.getStatus()).isEqualTo(RequestStatus.ACCEPTED);
        assertThat(pr.getDebtorIban()).isEqualTo("ACC-001");
    }

    @Test
    void acceptOnAlreadyAcceptedRequestReturns409() {
        var pr = seed("R-1", RequestStatus.PENDING);
        pr.setStatus(RequestStatus.ACCEPTED);
        requests.save(pr);
        assertThatThrownBy(() -> r2pService.accept("R-1",
                new R2pAcceptRequest("ACC-001", "INSTANT", null, null), "tok"))
                .isInstanceOfSatisfying(CustomerProblemException.class, e ->
                        assertThat(e.getType()).contains("r2p-already-acted"));
    }

    @Test
    void deferTransitionsToDeferredOnVerifiedStepUp() {
        seed("R-1", RequestStatus.PENDING);
        Instant executeAt = Instant.now().plus(2, ChronoUnit.DAYS);
        var outcome = r2pService.defer("R-1",
                new R2pDeferRequest("ACC-001", executeAt, "STANDARD", "later"),
                "valid-step-up-token");
        assertThat(outcome).isInstanceOf(R2pService.DeferOutcome.Granted.class);
        var pr = requests.findById("R-1").orElseThrow();
        assertThat(pr.getStatus()).isEqualTo(RequestStatus.DEFERRED);
        assertThat(pr.getScheduledPaymentId()).isNotNull();
    }

    @Test
    void deferRejectsExecuteAtAfterDueDate() {
        var pr = seed("R-1", RequestStatus.PENDING);
        pr.getClass(); // touch; due date already set in seed()
        Instant longAfterDue = LocalDate.now().plusYears(1).atStartOfDay()
                .toInstant(java.time.ZoneOffset.UTC);
        assertThatThrownBy(() -> r2pService.defer("R-1",
                new R2pDeferRequest("ACC-001", longAfterDue, "STANDARD", null), "tok"))
                .isInstanceOfSatisfying(CustomerProblemException.class, e ->
                        assertThat(e.getType()).contains("invalid-input"));
    }

    @Test
    void amendTransitionsToAmendmentRequested() {
        seed("R-1", RequestStatus.PENDING);
        var detail = r2pService.amend("R-1",
                new R2pAmendRequest("999.00", null,
                        "Customer disputes the line items, requests reconciliation"));
        assertThat(detail.summary().status()).isEqualTo("AMENDMENT_REQUESTED");
        assertThat(detail.amendment()).isNotNull();
        assertThat(detail.amendment().proposedAmount()).isEqualTo("999.00");
    }

    @Test
    void amendRejectsWhenNothingDiffers() {
        seed("R-1", RequestStatus.PENDING);
        assertThatThrownBy(() -> r2pService.amend("R-1",
                new R2pAmendRequest(null, null, "reason")))
                .isInstanceOf(CustomerProblemException.class);
    }

    @Test
    void cancelDeferReturnsToPending() {
        var pr = seed("R-1", RequestStatus.PENDING);
        // Move to deferred manually for the test.
        pr.setStatus(RequestStatus.DEFERRED);
        pr.setScheduledPaymentId("sched-1");
        requests.save(pr);
        var detail = r2pService.cancelDefer("R-1");
        assertThat(detail.summary().status()).isEqualTo("PENDING");
        assertThat(detail.scheduledPaymentId()).isNull();
    }

    @Test
    void detailReturns404WhenNotOwn() {
        seedForOtherCustomer("R-x");
        assertThatThrownBy(() -> r2pService.detail("R-x"))
                .isInstanceOfSatisfying(CustomerProblemException.class, e ->
                        assertThat(e.getType()).contains("not-found"));
    }

    private PaymentRequest seed(String id, RequestStatus status) {
        PaymentRequest pr = new PaymentRequest(
                id, "merchant-1", "NO9386011117947", "Hafslund Strøm AS",
                new BigDecimal("1234.56"), "NOK", "1234567890123",
                "April invoice", LocalDate.now().plusDays(5),
                "idem-" + id);
        pr.setMerchantName("Hafslund Strøm AS");
        pr.setCustomerUserId(userId);
        pr.setRequestedExecutionMode("INSTANT");
        pr.setStatus(status);
        return requests.save(pr);
    }

    private void seedForOtherCustomer(String id) {
        PaymentRequest pr = new PaymentRequest(
                id, "merchant-2", "NO9386011117947", "Other AS",
                new BigDecimal("100.00"), "NOK", null,
                null, LocalDate.now().plusDays(5),
                "idem-" + id);
        pr.setMerchantName("Other AS");
        pr.setCustomerUserId(UUID.randomUUID()); // different customer
        pr.setStatus(RequestStatus.PENDING);
        requests.save(pr);
    }
}
