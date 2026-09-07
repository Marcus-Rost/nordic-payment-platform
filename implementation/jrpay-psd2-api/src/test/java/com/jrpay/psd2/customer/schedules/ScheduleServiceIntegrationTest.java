package com.jrpay.psd2.customer.schedules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.jrpay.psd2.customer.CustomerProblemException;
import com.jrpay.psd2.customer.Psd2CustomerIntegrationTestBase;
import com.jrpay.psd2.customer.audit.CustomerAuditEvent;
import com.jrpay.psd2.customer.audit.CustomerAuditPublisher;
import com.jrpay.psd2.customer.config.AuthenticatedUser;
import com.jrpay.psd2.customer.domain.PaymentScheduleEntity;
import com.jrpay.psd2.customer.domain.PaymentScheduleRepository;
import com.jrpay.psd2.customer.payments.vop.VoPClient;
import com.jrpay.psd2.customer.sca.ScaInitiator;
import com.jrpay.psd2.customer.sca.StepUpValidator;
import com.jrpay.psd2.customer.schedules.dto.ScheduleRequestDto;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class ScheduleServiceIntegrationTest extends Psd2CustomerIntegrationTestBase {

    @Autowired ScheduleService service;
    @Autowired PaymentScheduleRepository schedules;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean ScaInitiator scaInitiator;
    @MockitoBean StepUpValidator stepUpValidator;
    @MockitoBean AuthenticatedUser caller;
    @MockitoBean CustomerAuditPublisher audit;
    @MockitoBean VoPClient vopClient;

    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-0000c0430001");

    @BeforeEach
    void setup() {
        when(caller.requireUserId()).thenReturn(userId);
        when(caller.currentUserId()).thenReturn(Optional.of(userId));
        when(caller.currentSubject()).thenReturn("customer:" + userId);
        when(caller.isCustomer()).thenReturn(true);
        when(caller.hasScope(anyString())).thenReturn(true);
        when(audit.publish(any(CustomerAuditEvent.class))).thenReturn("01HTESTSCH0000000000000XXX");
        when(scaInitiator.start(any(), org.mockito.ArgumentMatchers.eq(false)))
                .thenAnswer(inv -> new ScaInitiator.InitiateResult(
                        UUID.randomUUID(), "stub-dl-hash", "TOTP", false, null));
        when(stepUpValidator.validate(anyString(), anyString())).thenAnswer(inv ->
                new StepUpValidator.ValidationResult(true, StepUpValidator.Outcome.VALID,
                        userId, UUID.randomUUID(), null));
        jdbc.execute("TRUNCATE TABLE statement_jobs, customer_idempotency, "
                + "stream_tokens, customer_transactions, payment_schedules, "
                + "customer_payments, customer_accounts, processed_kafka_events CASCADE");
    }

    @Test
    void createWithoutStepUpReturnsPendingSca() {
        var outcome = service.create(scheduleReq(), "idem-1", null);
        assertThat(outcome).isInstanceOf(ScheduleService.CreateOutcome.PendingSca.class);
        var pending = ((ScheduleService.CreateOutcome.PendingSca) outcome).pending();
        PaymentScheduleEntity stored = schedules.findById(pending.paymentId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PaymentScheduleEntity.Status.PENDING_SCA);
    }

    @Test
    void createWithStepUpActivatesSchedule() {
        var outcome = service.create(scheduleReq(), "idem-2", "good-step-up");
        assertThat(outcome).isInstanceOf(ScheduleService.CreateOutcome.Granted.class);
        var dto = ((ScheduleService.CreateOutcome.Granted) outcome).schedule();
        assertThat(dto.status()).isEqualTo("SCHEDULED");
    }

    @Test
    void executeAtMustBeFuture() {
        ScheduleRequestDto past = new ScheduleRequestDto(
                "a-1", "NO9386011117947", "X", "100.00", "NOK", null,
                Instant.now().minusSeconds(60), "STANDARD", null);
        assertThatThrownBy(() -> service.create(past, "idem-3", "good"))
                .isInstanceOfSatisfying(CustomerProblemException.class, e ->
                        assertThat(e.getType()).contains("invalid-input"));
    }

    @Test
    void cancelTransitionsToCancelled() {
        var outcome = service.create(scheduleReq(), "idem-4", "good-step-up");
        var dto = ((ScheduleService.CreateOutcome.Granted) outcome).schedule();
        var cancelled = service.cancel(dto.scheduleId());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelOnExecutedScheduleRejected() {
        var outcome = service.create(scheduleReq(), "idem-5", "good-step-up");
        var dto = ((ScheduleService.CreateOutcome.Granted) outcome).schedule();
        // Mark executed manually
        PaymentScheduleEntity s = schedules.findById(dto.scheduleId()).orElseThrow();
        s.markExecuted("payment-x");
        schedules.save(s);
        assertThatThrownBy(() -> service.cancel(dto.scheduleId()))
                .isInstanceOfSatisfying(CustomerProblemException.class, e ->
                        assertThat(e.getType()).contains("payment-not-cancellable"));
    }

    @Test
    void listFiltersDefaultsToScheduled() {
        // Create one PENDING_SCA + one SCHEDULED.
        service.create(scheduleReq(), "idem-6", null);
        var ok = service.create(scheduleReqAt(Instant.now().plus(2, ChronoUnit.DAYS)),
                "idem-7", "good");
        assertThat(ok).isInstanceOf(ScheduleService.CreateOutcome.Granted.class);
        var page = service.list(null, 0, 25);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).status()).isEqualTo("SCHEDULED");
    }

    private ScheduleRequestDto scheduleReq() {
        return scheduleReqAt(Instant.now().plus(1, ChronoUnit.DAYS));
    }

    private ScheduleRequestDto scheduleReqAt(Instant executeAt) {
        return new ScheduleRequestDto(
                "a-1", "NO9386011117947", "Hafslund Strøm AS",
                "1234.56", "NOK", null,
                executeAt, "STANDARD", "later");
    }
}
