package com.jrpay.psd2.customer.payments;

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
import com.jrpay.psd2.customer.domain.CustomerAccountEntity;
import com.jrpay.psd2.customer.domain.CustomerAccountRepository;
import com.jrpay.psd2.customer.domain.CustomerPaymentEntity;
import com.jrpay.psd2.customer.domain.CustomerPaymentRepository;
import com.jrpay.psd2.customer.payments.dto.PaymentRequestDto;
import com.jrpay.psd2.customer.sca.ScaInitiator;
import com.jrpay.psd2.customer.sca.StepUpValidator;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class CustomerPaymentServiceIntegrationTest extends Psd2CustomerIntegrationTestBase {

    @Autowired CustomerPaymentService service;
    @Autowired CustomerPaymentRepository payments;
    @Autowired CustomerAccountRepository accounts;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean ScaInitiator scaInitiator;
    @MockitoBean StepUpValidator stepUpValidator;
    @MockitoBean AuthenticatedUser caller;
    @MockitoBean CustomerAuditPublisher audit;

    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-0000c0420001");

    @BeforeEach
    void setup() {
        when(caller.requireUserId()).thenReturn(userId);
        when(caller.currentUserId()).thenReturn(Optional.of(userId));
        when(caller.currentSubject()).thenReturn("customer:" + userId);
        when(caller.isCustomer()).thenReturn(true);
        when(caller.hasScope(anyString())).thenReturn(true);
        when(audit.publish(any(CustomerAuditEvent.class))).thenReturn("01HTESTPAY0000000000000XXX");
        when(scaInitiator.start(any(), org.mockito.ArgumentMatchers.eq(false)))
                .thenAnswer(inv -> new ScaInitiator.InitiateResult(
                        UUID.randomUUID(), "stub-dl-hash", "TOTP", false, null));
        when(stepUpValidator.validate(anyString(), anyString())).thenAnswer(inv ->
                new StepUpValidator.ValidationResult(true, StepUpValidator.Outcome.VALID,
                        userId, UUID.randomUUID(), null));
        jdbc.execute("TRUNCATE TABLE statement_jobs, customer_idempotency, "
                + "stream_tokens, customer_transactions, payment_schedules, "
                + "customer_payments, customer_accounts, processed_kafka_events CASCADE");
        seedAccount("a-1", userId);
    }

    @Test
    void initiateWithoutStepUpReturnsPendingSca() {
        var outcome = service.initiate(payment(), "idem-1", null);
        assertThat(outcome).isInstanceOf(CustomerPaymentService.InitiateOutcome.PendingSca.class);
        var pending = ((CustomerPaymentService.InitiateOutcome.PendingSca) outcome).pending();
        assertThat(pending.scaSessionId()).isNotNull();
        assertThat(pending.dlHash()).isNotBlank();
        CustomerPaymentEntity p = payments.findByPaymentIdAndCustomerUserId(
                pending.paymentId(), userId).orElseThrow();
        assertThat(p.getStatus()).isEqualTo("PENDING_SCA");
    }

    @Test
    void initiateWithStepUpFinalisesAndAcks() {
        var outcome = service.initiate(payment(), "idem-2", "good-step-up");
        assertThat(outcome).isInstanceOf(CustomerPaymentService.InitiateOutcome.Granted.class);
        var ack = ((CustomerPaymentService.InitiateOutcome.Granted) outcome).ack();
        assertThat(ack.status()).isEqualTo("PENDING_EXECUTION");
        assertThat(ack.clearingPath()).isEqualTo("INSTANT");
    }

    @Test
    void duplicateSuspectedReturns409WithSuspectedOriginal() {
        // First payment
        service.initiate(payment(), "idem-3a", "good");
        // Second payment with same creditor + amount within window — different idempotency key
        assertThatThrownBy(() -> service.initiate(payment(), "idem-3b", "good"))
                .isInstanceOfSatisfying(CustomerProblemException.class, e -> {
                    assertThat(e.getType()).contains("duplicate-suspected");
                    assertThat(e.getExtensions()).containsKey("suspectedOriginal");
                });
    }

    @Test
    void idempotencyReplayReturnsSameOutcome() {
        var first = service.initiate(payment(), "idem-4", null);
        var firstId = ((CustomerPaymentService.InitiateOutcome.PendingSca) first).pending().paymentId();
        // Replay with same key + body must not create a new payment.
        var second = service.initiate(payment(), "idem-4", null);
        var secondId = ((CustomerPaymentService.InitiateOutcome.PendingSca) second).pending().paymentId();
        assertThat(secondId).isEqualTo(firstId);
    }

    @Test
    void idempotencyKeyConflictWithDifferentBody() {
        service.initiate(payment(), "idem-5", null);
        PaymentRequestDto changed = new PaymentRequestDto(
                "a-1", "NO9386011117947", "OTHER NAME",
                "999.99", "NOK", null, null, "INSTANT", null, null);
        assertThatThrownBy(() -> service.initiate(changed, "idem-5", null))
                .isInstanceOfSatisfying(CustomerProblemException.class, e ->
                        assertThat(e.getType()).contains("idempotency-key-conflict"));
    }

    @Test
    void cancelPreScaTransitionsToCancelled() {
        var outcome = service.initiate(payment(), "idem-6", null);
        var paymentId = ((CustomerPaymentService.InitiateOutcome.PendingSca) outcome).pending().paymentId();
        service.cancelPreSca(paymentId);
        CustomerPaymentEntity p = payments.findByPaymentIdAndCustomerUserId(paymentId, userId).orElseThrow();
        assertThat(p.getStatus()).isEqualTo("CANCELLED");
        assertThat(p.getCancelledAt()).isNotNull();
    }

    @Test
    void cancelPostScaIsRejected() {
        var outcome = service.initiate(payment(), "idem-7", "good");
        var paymentId = ((CustomerPaymentService.InitiateOutcome.Granted) outcome).ack().paymentId();
        assertThatThrownBy(() -> service.cancelPreSca(paymentId))
                .isInstanceOfSatisfying(CustomerProblemException.class, e ->
                        assertThat(e.getType()).contains("payment-not-cancellable"));
    }

    @Test
    void submitAfterScaTransitionsPendingScaToPendingExecution() {
        var outcome = service.initiate(payment(), "idem-8", null);
        var paymentId = ((CustomerPaymentService.InitiateOutcome.PendingSca) outcome).pending().paymentId();
        var ack = service.submit(paymentId, "good-step-up");
        assertThat(ack.status()).isEqualTo("PENDING_EXECUTION");
    }

    @Test
    void invalidIbanRejected() {
        PaymentRequestDto bad = new PaymentRequestDto(
                "a-1", "NO0000000000000", "X", "10.00", "NOK",
                null, null, "INSTANT", null, null);
        assertThatThrownBy(() -> service.initiate(bad, "idem-bad", null))
                .isInstanceOfSatisfying(CustomerProblemException.class, e ->
                        assertThat(e.getType()).contains("invalid-iban"));
    }

    @Test
    void debtorAccountFrozenIs422() {
        CustomerAccountEntity a = accounts.findById("a-1").orElseThrow();
        a.setStatus(CustomerAccountEntity.Status.FROZEN);
        accounts.save(a);
        assertThatThrownBy(() -> service.initiate(payment(), "idem-frozen", null))
                .isInstanceOfSatisfying(CustomerProblemException.class, e ->
                        assertThat(e.getType()).contains("account-frozen"));
    }

    private PaymentRequestDto payment() {
        return new PaymentRequestDto(
                "a-1", "NO9386011117947", "Hafslund Strøm AS",
                "1234.56", "NOK", "1234567890123",
                "April invoice", "INSTANT", "MATCH", false);
    }

    private void seedAccount(String id, UUID owner) {
        CustomerAccountEntity a = new CustomerAccountEntity(id, owner, "NOK");
        a.setBookedBalance(new BigDecimal("10000.00"));
        a.setIban("NO0820000000123");
        accounts.save(a);
    }
}
