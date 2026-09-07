package com.jrpay.fraud.casework.sanctions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.jrpay.fraud.casework.CaseworkIntegrationTestBase;
import com.jrpay.fraud.casework.CaseworkProblemException;
import com.jrpay.fraud.casework.audit.AdminAuditEvent;
import com.jrpay.fraud.casework.audit.AdminAuditPublisher;
import com.jrpay.fraud.casework.config.AuthenticatedUser;
import com.jrpay.fraud.casework.domain.FpCacheRepository;
import com.jrpay.fraud.casework.domain.FraudCaseRepository;
import com.jrpay.fraud.casework.domain.SanctionsHitEntity;
import com.jrpay.fraud.casework.domain.SanctionsHitRepository;
import com.jrpay.fraud.casework.sanctions.dto.SanctionsDecideRequest;
import com.jrpay.fraud.casework.sca.StepUpValidator;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class SanctionsHitServiceIntegrationTest extends CaseworkIntegrationTestBase {

    @Autowired SanctionsHitService service;
    @Autowired SanctionsHitRepository hits;
    @Autowired FraudCaseRepository fraudCases;
    @Autowired FpCacheRepository fpCache;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean StepUpValidator stepUpValidator;
    @MockitoBean AdminAuditPublisher audit;
    @MockitoBean AuthenticatedUser caller;

    private final UUID operatorId = UUID.fromString("00000000-0000-0000-0000-0000a5050002");

    @BeforeEach
    void setup() {
        when(caller.requireUserId()).thenReturn(operatorId);
        when(caller.currentUserId()).thenReturn(Optional.of(operatorId));
        when(caller.currentSubject()).thenReturn("operator:" + operatorId);
        when(caller.isAdmin()).thenReturn(true);
        when(caller.hasScope(anyString())).thenReturn(true);
        when(caller.hasAnyRole(anyString(), anyString())).thenReturn(true);
        when(audit.publish(any(AdminAuditEvent.class))).thenReturn("01HTESTSANC0000000000XXXX");
        when(stepUpValidator.validate(anyString(), anyString())).thenAnswer(inv ->
                new StepUpValidator.ValidationResult(true, StepUpValidator.Outcome.VALID,
                        operatorId, UUID.randomUUID(), null));
        jdbc.execute("TRUNCATE TABLE bulk_decide_runs, fraud_held_payments, "
                + "fraud_timeline, fraud_cases, sanctions_fp_cache, "
                + "sanctions_hits, sanctions_list_refresh_runs, "
                + "stream_tokens, processed_kafka_events, admin_idempotency CASCADE");
    }

    @Test
    void confirmedSanctionReturnsAccountFrozen() {
        SanctionsHitEntity h = seedHit();
        SanctionsDecideRequest req = new SanctionsDecideRequest(
                "CONFIRMED_SANCTION",
                "Verified the matched entity against the consolidated list and confirmed the sanction.",
                Boolean.TRUE, null);
        var ack = service.decide(h.getHitId(), req, "valid-step-up");
        assertThat(ack.status()).isEqualTo("CONFIRMED_SANCTION");
        assertThat(ack.accountFrozen()).isEqualTo(h.getDebtorAccountId());
    }

    @Test
    void falsePositiveAddsFpCacheEntryWhenRequested() {
        SanctionsHitEntity h = seedHit();
        SanctionsDecideRequest req = new SanctionsDecideRequest(
                "FALSE_POSITIVE",
                "Reviewed the matched entity profile and confirmed it is the wrong person; whitelist.",
                Boolean.TRUE, Boolean.TRUE);
        var ack = service.decide(h.getHitId(), req, "valid-step-up");
        assertThat(ack.status()).isEqualTo("FALSE_POSITIVE");
        assertThat(ack.fpCacheEntryId()).isNotNull();
        assertThat(fpCache.findById(ack.fpCacheEntryId())).isPresent();
    }

    @Test
    void releaseForFraudReviewCreatesNewFraudCase() {
        SanctionsHitEntity h = seedHit();
        SanctionsDecideRequest req = new SanctionsDecideRequest(
                "RELEASE_FOR_FRAUD_REVIEW",
                "Sanctions match likely false but pattern is suspicious; routing to fraud team.",
                Boolean.TRUE, null);
        var ack = service.decide(h.getHitId(), req, "valid-step-up");
        assertThat(ack.status()).isEqualTo("RELEASE_FOR_FRAUD_REVIEW");
        assertThat(ack.newFraudCaseId()).isNotNull();
        assertThat(fraudCases.findById(ack.newFraudCaseId())).isPresent();
    }

    @Test
    void decideOnAlreadyDecidedHitReturns409() {
        SanctionsHitEntity h = seedHit();
        h.decide(SanctionsHitEntity.Status.FALSE_POSITIVE, operatorId, "h", "s");
        hits.save(h);
        SanctionsDecideRequest req = new SanctionsDecideRequest(
                "CONFIRMED_SANCTION",
                "Trying to decide an already-decided hit to validate the precondition guard rejects.",
                Boolean.TRUE, null);
        assertThatThrownBy(() -> service.decide(h.getHitId(), req, "valid"))
                .isInstanceOfSatisfying(CaseworkProblemException.class, e ->
                        assertThat(e.getType()).contains("hit-already-decided"));
    }

    @Test
    void listMetadataIncludesSeededLists() {
        var meta = service.listMetadata();
        assertThat(meta).extracting("listId").contains("EU-CONS", "OFAC-SDN", "UN-CONS");
    }

    @Test
    void requestRefreshUpdatesLastRefreshAtAndReturnsSucceeded() {
        var run = service.requestRefresh(java.util.List.of("EU-CONS"), "valid-step-up");
        assertThat(run.status()).isEqualTo("SUCCEEDED");
        assertThat(run.runId()).isNotNull();
    }

    @Test
    void fpCacheRemoveSetsRemovedAt() {
        SanctionsHitEntity h = seedHit();
        var ack = service.decide(h.getHitId(),
                new SanctionsDecideRequest("FALSE_POSITIVE",
                        "FP whitelist for routine business counterparty after compliance verification.",
                        Boolean.TRUE, Boolean.TRUE),
                "valid-step-up");
        UUID entryId = ack.fpCacheEntryId();
        service.removeFpEntry(entryId, "valid-step-up");
        assertThat(fpCache.findById(entryId).orElseThrow().getRemovedAt()).isNotNull();
    }

    private SanctionsHitEntity seedHit() {
        SanctionsHitEntity h = new SanctionsHitEntity(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "ACC-" + UUID.randomUUID().toString().substring(0, 6),
                "Acme Sanctioned Co", "EU-CONS",
                new BigDecimal("0.92"),
                "NO9386011117947", "Acme",
                new BigDecimal("5000.00"), "NOK", "ref-1",
                "{\"name\":\"Acme Sanctioned Co\"}",
                Instant.now(),
                Instant.now().plus(Duration.ofHours(2)));
        return hits.save(h);
    }
}
