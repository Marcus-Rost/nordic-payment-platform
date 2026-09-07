package com.jrpay.fraud.casework.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jrpay.fraud.casework.CaseworkIntegrationTestBase;
import com.jrpay.fraud.casework.CaseworkProblemException;
import com.jrpay.fraud.casework.audit.AdminAuditEvent;
import com.jrpay.fraud.casework.audit.AdminAuditPublisher;
import com.jrpay.fraud.casework.config.AuthenticatedUser;
import com.jrpay.fraud.casework.domain.FraudCaseEntity;
import com.jrpay.fraud.casework.domain.FraudCaseRepository;
import com.jrpay.fraud.casework.domain.FraudHeldPaymentEntity;
import com.jrpay.fraud.casework.domain.FraudHeldPaymentRepository;
import com.jrpay.fraud.casework.fraud.dto.FraudAssignRequest;
import com.jrpay.fraud.casework.fraud.dto.FraudBulkDecideRequest;
import com.jrpay.fraud.casework.fraud.dto.FraudDecideRequest;
import com.jrpay.fraud.casework.sca.StepUpValidator;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class FraudCaseServiceIntegrationTest extends CaseworkIntegrationTestBase {

    @Autowired FraudCaseService service;
    @Autowired FraudCaseRepository cases;
    @Autowired FraudHeldPaymentRepository held;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean StepUpValidator stepUpValidator;
    @MockitoBean AdminAuditPublisher audit;
    @MockitoBean AuthenticatedUser caller;

    private final UUID operatorId = UUID.fromString("00000000-0000-0000-0000-0000a5050001");
    private final UUID supervisorId = UUID.fromString("00000000-0000-0000-0000-0000a5050099");

    @BeforeEach
    void setup() {
        when(caller.requireUserId()).thenReturn(operatorId);
        when(caller.currentUserId()).thenReturn(Optional.of(operatorId));
        when(caller.currentSubject()).thenReturn("operator:" + operatorId);
        when(caller.isAdmin()).thenReturn(true);
        when(caller.hasScope(anyString())).thenReturn(true);
        when(caller.hasAnyRole(anyString(), anyString())).thenReturn(true);
        when(audit.publish(any(AdminAuditEvent.class))).thenReturn("01HTESTFRAUD0000000000XXXX");
        when(stepUpValidator.validate(anyString(), anyString())).thenAnswer(inv ->
                new StepUpValidator.ValidationResult(true, StepUpValidator.Outcome.VALID,
                        operatorId, UUID.randomUUID(), null));
        jdbc.execute("TRUNCATE TABLE bulk_decide_runs, fraud_held_payments, "
                + "fraud_timeline, fraud_cases, sanctions_fp_cache, "
                + "sanctions_hits, sanctions_list_refresh_runs, "
                + "stream_tokens, processed_kafka_events, admin_idempotency CASCADE");
    }

    @Test
    void listReturnsActiveCasesByDefaultWithCounters() {
        seed(FraudCaseEntity.Status.OPEN, "0.92");
        seed(FraudCaseEntity.Status.CONFIRMED_FRAUD, "0.88");
        var page = service.list(null, null, null, 0, 25);
        assertThat(page.items()).hasSize(1);
        assertThat(page.counters().openTotal()).isEqualTo(1L);
    }

    @Test
    void detailEmitsViewedAuditAndTimeline() {
        FraudCaseEntity c = seed(FraudCaseEntity.Status.OPEN, "0.75");
        var detail = service.detail(c.getCaseId());
        assertThat(detail.summary().status()).isEqualTo("OPEN");
        // Timeline should have CASE_VIEWED
        assertThat(detail.timeline()).extracting("type").contains("CASE_VIEWED");
        verify(audit, atLeastOnce()).publish(any(AdminAuditEvent.class));
    }

    @Test
    void selfAssignSucceedsWithoutSupervisorRole() {
        FraudCaseEntity c = seed(FraudCaseEntity.Status.OPEN, "0.70");
        when(caller.hasAnyRole(anyString(), anyString())).thenReturn(false);
        var detail = service.assign(c.getCaseId(),
                new FraudAssignRequest("me", null));
        assertThat(detail.summary().status()).isEqualTo("ASSIGNED");
        assertThat(detail.summary().assignee()).isEqualTo(operatorId);
    }

    @Test
    void reassignToNamedOperatorRequiresSupervisor() {
        FraudCaseEntity c = seed(FraudCaseEntity.Status.OPEN, "0.70");
        when(caller.hasAnyRole(anyString(), anyString())).thenReturn(false);
        assertThatThrownBy(() -> service.assign(c.getCaseId(),
                new FraudAssignRequest(supervisorId.toString(), null)))
                .isInstanceOfSatisfying(CaseworkProblemException.class, e ->
                        assertThat(e.getType()).contains("forbidden"));
    }

    @Test
    void decideTransitionsToConfirmedFraudAndCascades() {
        FraudCaseEntity c = seed(FraudCaseEntity.Status.ASSIGNED, "0.96");
        held.save(new FraudHeldPaymentEntity(c.getCaseId(), c.getPaymentId(),
                c.getAmount(), c.getCurrency(), "HELD"));
        FraudDecideRequest req = new FraudDecideRequest(
                "CONFIRMED_FRAUD",
                "Confirmed fraudulent transaction following thorough investigation of the payment trail.",
                Boolean.TRUE, "FRAUD_INVESTIGATION");
        var ack = service.decide(c.getCaseId(), req, "valid-step-up");
        assertThat(ack.status()).isEqualTo("CONFIRMED_FRAUD");
        assertThat(ack.affectedPayments()).hasSize(1);
        assertThat(ack.accountFrozen()).isNotNull();
        assertThat(ack.accountFrozen().freezeReason()).isEqualTo("FRAUD_INVESTIGATION");
        assertThat(held.findByCaseId(c.getCaseId()).get(0).getStatus()).isEqualTo("REJECTED");
    }

    @Test
    void decideOnAlreadyDecidedCaseReturns409() {
        FraudCaseEntity c = seed(FraudCaseEntity.Status.OPEN, "0.91");
        c.decide(FraudCaseEntity.Status.DISMISSED, operatorId, "h", "summary");
        cases.save(c);
        FraudDecideRequest req = new FraudDecideRequest(
                "CONFIRMED_FRAUD",
                "Trying to decide an already-decided case to validate the precondition guard works.",
                Boolean.TRUE, null);
        assertThatThrownBy(() -> service.decide(c.getCaseId(), req, "valid"))
                .isInstanceOfSatisfying(CaseworkProblemException.class, e ->
                        assertThat(e.getType()).contains("case-already-decided"));
    }

    @Test
    void decideRequiresConfirmedCriteria() {
        FraudCaseEntity c = seed(FraudCaseEntity.Status.OPEN, "0.91");
        FraudDecideRequest req = new FraudDecideRequest(
                "DISMISSED",
                "Customer-side investigation cleared this transaction; releasing the held funds back.",
                Boolean.FALSE, null);
        assertThatThrownBy(() -> service.decide(c.getCaseId(), req, "valid"))
                .isInstanceOfSatisfying(CaseworkProblemException.class, e ->
                        assertThat(e.getType()).contains("invalid-input"));
    }

    @Test
    void decideRequiresMinimum50CharNotes() {
        FraudCaseEntity c = seed(FraudCaseEntity.Status.OPEN, "0.91");
        FraudDecideRequest req = new FraudDecideRequest(
                "DISMISSED", "Too short", Boolean.TRUE, null);
        assertThatThrownBy(() -> service.decide(c.getCaseId(), req, "valid"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void bulkDecideAppliesToBatchAndReportsResults() {
        FraudCaseEntity a = seed(FraudCaseEntity.Status.OPEN, "0.92");
        FraudCaseEntity b = seed(FraudCaseEntity.Status.OPEN, "0.71");
        FraudCaseEntity c = seed(FraudCaseEntity.Status.CONFIRMED_FRAUD, "0.85");
        FraudBulkDecideRequest req = new FraudBulkDecideRequest(
                List.of(a.getCaseId(), b.getCaseId(), c.getCaseId(), UUID.randomUUID()),
                "DISMISSED",
                "Batch dismissal after coordinated review of related cases by the compliance team.",
                Boolean.TRUE, null);
        var ack = service.bulkDecide(req, "valid-step-up");
        assertThat(ack.total()).isEqualTo(4);
        assertThat(ack.succeeded()).isEqualTo(2);
        assertThat(ack.failed()).isEqualTo(2);
    }

    @Test
    void bulkDecideRejectsBatchOver50() {
        java.util.List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) ids.add(UUID.randomUUID());
        FraudBulkDecideRequest req = new FraudBulkDecideRequest(
                ids, "DISMISSED",
                "Notes long enough to satisfy the minimum length validation rule for bulk decisions.",
                Boolean.TRUE, null);
        assertThatThrownBy(() -> service.bulkDecide(req, "valid"))
                .isInstanceOfSatisfying(CaseworkProblemException.class, e ->
                        assertThat(e.getType()).contains("validation"));
    }

    private FraudCaseEntity seed(FraudCaseEntity.Status status, String score) {
        BigDecimal s = new BigDecimal(score);
        FraudCaseEntity c = new FraudCaseEntity(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "ACC-" + UUID.randomUUID().toString().substring(0, 6),
                "Ola Nordmann",
                "NO9386011117947", "Hafslund Strøm AS",
                new BigDecimal("1234.56"), "NOK",
                "1234567890", "INSTANT",
                s, new BigDecimal("0.60"),
                "VEL-04,VOP-NM", null,
                Instant.now().minus(Duration.ofMinutes(10)),
                Instant.now().plus(Duration.ofHours(4)));
        if (status == FraudCaseEntity.Status.CONFIRMED_FRAUD) {
            c.decide(status, operatorId, "h", "summary");
        } else if (status != FraudCaseEntity.Status.OPEN) {
            c.assignTo(operatorId);
        }
        return cases.save(c);
    }
}
