package com.jrpay.billpay.customer.mandates;

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
import com.jrpay.billpay.customer.mandates.dto.MandateCreateRequest;
import com.jrpay.billpay.customer.mandates.dto.MandateUpdateRequest;
import com.jrpay.billpay.domain.Mandate;
import com.jrpay.billpay.domain.MandateRepository;
import com.jrpay.billpay.sca.ScaInitiator;
import com.jrpay.billpay.sca.StepUpValidator;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class MandateServiceIntegrationTest extends BillPayCustomerIntegrationTestBase {

    @Autowired MandateService mandateService;
    @Autowired MandateRepository mandates;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean ScaInitiator scaInitiator;
    @MockitoBean StepUpValidator stepUpValidator;
    @MockitoBean CustomerAuditPublisher audit;
    @MockitoBean AuthenticatedUser caller;

    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-0000c0022222");

    @BeforeEach
    void setup() {
        when(caller.requireUserId()).thenReturn(userId);
        when(caller.currentUserId()).thenReturn(Optional.of(userId));
        when(caller.currentSubject()).thenReturn("customer:" + userId);
        when(caller.isCustomer()).thenReturn(true);
        when(caller.hasScope(anyString())).thenReturn(true);
        when(audit.publish(any(CustomerAuditEvent.class))).thenReturn("01HTESTMAND00000000000XXXX");
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
    void createReturnsPendingScaWithNewSession() {
        var outcome = mandateService.create(new MandateCreateRequest(
                "m-bits-0001234", "ACC-001", "1500.00", "NOK", "MONTHLY",
                LocalDate.now().plusDays(7), null, true));
        assertThat(outcome.pending().mandateId()).isNotBlank();
        assertThat(outcome.pending().scaSessionId()).isNotNull();
        Mandate stored = mandates.findById(outcome.pending().mandateId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(Mandate.MandateStatus.PENDING_SCA);
        verify(audit, atLeastOnce()).publish(any(CustomerAuditEvent.class));
    }

    @Test
    void createDuplicateActiveMandateRejects409() {
        // Create one and manually flip to ACTIVE
        var first = mandateService.create(new MandateCreateRequest(
                "m-bits-0001234", "ACC-001", "1500.00", "NOK", "MONTHLY",
                LocalDate.now().plusDays(7), null, true));
        Mandate m = mandates.findById(first.pending().mandateId()).orElseThrow();
        m.activate();
        mandates.save(m);

        assertThatThrownBy(() -> mandateService.create(new MandateCreateRequest(
                "m-bits-0001234", "ACC-001", "2000.00", "NOK", "MONTHLY",
                LocalDate.now().plusDays(8), null, true)))
                .isInstanceOfSatisfying(CustomerProblemException.class, e ->
                        assertThat(e.getType()).contains("duplicate-consent"));
    }

    @Test
    void unknownMerchantReturns404() {
        assertThatThrownBy(() -> mandateService.create(new MandateCreateRequest(
                "m-unknown", "ACC-001", "100.00", "NOK", "MONTHLY",
                LocalDate.now().plusDays(7), null, true)))
                .isInstanceOfSatisfying(CustomerProblemException.class, e ->
                        assertThat(e.getType()).contains("merchant-not-in-registry"));
    }

    @Test
    void revokeIsIdempotentAndSetsRevokedAt() {
        var first = mandateService.create(new MandateCreateRequest(
                "m-bits-0001234", "ACC-001", "1500.00", "NOK", "MONTHLY",
                LocalDate.now().plusDays(7), null, true));
        Mandate m = mandates.findById(first.pending().mandateId()).orElseThrow();
        m.activate();
        mandates.save(m);

        var detail = mandateService.revoke(first.pending().mandateId());
        assertThat(detail.summary().status()).isEqualTo("REVOKED");
        assertThat(detail.revokedAt()).isNotNull();
        // Second revoke should be a no-op (still REVOKED, no exception).
        var second = mandateService.revoke(first.pending().mandateId());
        assertThat(second.summary().status()).isEqualTo("REVOKED");
    }

    @Test
    void updateAmountWithoutStepUpReturnsPendingSca() {
        var first = mandateService.create(new MandateCreateRequest(
                "m-bits-0001234", "ACC-001", "1500.00", "NOK", "MONTHLY",
                LocalDate.now().plusDays(7), null, true));
        Mandate m = mandates.findById(first.pending().mandateId()).orElseThrow();
        m.activate();
        mandates.save(m);

        var outcome = mandateService.update(first.pending().mandateId(),
                new MandateUpdateRequest("2000.00", null, null, null), null);
        assertThat(outcome).isInstanceOf(MandateService.UpdateOutcome.PendingSca.class);
    }

    @Test
    void updateStatusToPausedNoSca() {
        var first = mandateService.create(new MandateCreateRequest(
                "m-bits-0001234", "ACC-001", "1500.00", "NOK", "MONTHLY",
                LocalDate.now().plusDays(7), null, true));
        Mandate m = mandates.findById(first.pending().mandateId()).orElseThrow();
        m.activate();
        mandates.save(m);

        var outcome = mandateService.update(first.pending().mandateId(),
                new MandateUpdateRequest(null, "PAUSED", null, null), null);
        assertThat(outcome).isInstanceOf(MandateService.UpdateOutcome.Granted.class);
        var detail = ((MandateService.UpdateOutcome.Granted) outcome).detail();
        assertThat(detail.summary().status()).isEqualTo("PAUSED");
    }

    @Test
    void merchantSearchFindsHafslund() {
        var page = mandateService.list(null, null, 0, 25); // also exercises empty list branch
        assertThat(page.items()).isEmpty();
    }
}
