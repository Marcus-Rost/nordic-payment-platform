package com.jrpay.fraud.casework.fraud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.fraud.casework.CaseworkProblemException;
import com.jrpay.fraud.casework.Masking;
import com.jrpay.fraud.casework.audit.AdminAuditEvent;
import com.jrpay.fraud.casework.audit.AdminAuditPublisher;
import com.jrpay.fraud.casework.config.AuthenticatedUser;
import com.jrpay.fraud.casework.domain.BulkDecideRunEntity;
import com.jrpay.fraud.casework.domain.BulkDecideRunRepository;
import com.jrpay.fraud.casework.domain.FraudCaseEntity;
import com.jrpay.fraud.casework.domain.FraudCaseRepository;
import com.jrpay.fraud.casework.domain.FraudHeldPaymentEntity;
import com.jrpay.fraud.casework.domain.FraudHeldPaymentRepository;
import com.jrpay.fraud.casework.domain.FraudTimelineEntry;
import com.jrpay.fraud.casework.domain.FraudTimelineRepository;
import com.jrpay.fraud.casework.fraud.dto.FraudAssignRequest;
import com.jrpay.fraud.casework.fraud.dto.FraudBulkDecideAck;
import com.jrpay.fraud.casework.fraud.dto.FraudBulkDecideRequest;
import com.jrpay.fraud.casework.fraud.dto.FraudCaseContextDto;
import com.jrpay.fraud.casework.fraud.dto.FraudCaseDetailDto;
import com.jrpay.fraud.casework.fraud.dto.FraudCasePage;
import com.jrpay.fraud.casework.fraud.dto.FraudCaseSummaryDto;
import com.jrpay.fraud.casework.fraud.dto.FraudDecideAck;
import com.jrpay.fraud.casework.fraud.dto.FraudDecideRequest;
import com.jrpay.fraud.casework.sca.DynamicLinkingHash;
import com.jrpay.fraud.casework.sca.StepUpValidator;
import com.jrpay.fraud.casework.stream.CaseworkStreamHub;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FraudCaseService {

    private final FraudCaseRepository cases;
    private final FraudTimelineRepository timeline;
    private final FraudHeldPaymentRepository held;
    private final BulkDecideRunRepository bulkRuns;
    private final StepUpValidator stepUpValidator;
    private final DynamicLinkingHash dlHasher;
    private final AdminAuditPublisher audit;
    private final AuthenticatedUser caller;
    private final CaseworkStreamHub hub;
    private final ObjectMapper mapper;
    private final Duration bulkCooldown;

    public FraudCaseService(FraudCaseRepository cases,
                            FraudTimelineRepository timeline,
                            FraudHeldPaymentRepository held,
                            BulkDecideRunRepository bulkRuns,
                            StepUpValidator stepUpValidator,
                            DynamicLinkingHash dlHasher,
                            AdminAuditPublisher audit,
                            AuthenticatedUser caller,
                            CaseworkStreamHub hub,
                            ObjectMapper mapper,
                            @Value("${jrpay.fraud.bulk-decide.cooldown:PT1M}") Duration bulkCooldown) {
        this.cases = cases;
        this.timeline = timeline;
        this.held = held;
        this.bulkRuns = bulkRuns;
        this.stepUpValidator = stepUpValidator;
        this.dlHasher = dlHasher;
        this.audit = audit;
        this.caller = caller;
        this.hub = hub;
        this.mapper = mapper;
        this.bulkCooldown = bulkCooldown;
    }

    // ---------------------------------------------------------------- queue
    @Transactional(readOnly = true)
    public FraudCasePage list(Collection<FraudCaseEntity.Status> statuses,
                              BigDecimal minScore, BigDecimal maxScore,
                              int page, int size) {
        Collection<FraudCaseEntity.Status> effective = (statuses == null || statuses.isEmpty())
                ? List.of(FraudCaseEntity.Status.OPEN, FraudCaseEntity.Status.ASSIGNED,
                          FraudCaseEntity.Status.INVESTIGATING)
                : statuses;
        Page<FraudCaseEntity> result = (minScore == null && maxScore == null)
                ? cases.listByStatus(effective, PageRequest.of(page, size))
                : cases.listByStatusScored(effective, minScore, maxScore, PageRequest.of(page, size));
        Instant now = Instant.now();
        List<FraudCaseSummaryDto> items = result.getContent().stream()
                .map(c -> toSummary(c, now)).toList();
        Collection<FraudCaseEntity.Status> activeStatuses = List.of(
                FraudCaseEntity.Status.OPEN, FraudCaseEntity.Status.ASSIGNED,
                FraudCaseEntity.Status.INVESTIGATING);
        long openTotal = cases.countByStatusIn(activeStatuses);
        UUID me = caller.currentUserId().orElse(null);
        long assignedToMe = me == null ? 0 : cases.countByAssigneeAndStatusIn(me, activeStatuses);
        long slaBreached = cases.countSlaBreached(activeStatuses, now);
        return new FraudCasePage(items,
                new FraudCasePage.Page(result.getNumber(), result.getSize(),
                        result.getTotalElements(), result.getTotalPages()),
                new FraudCasePage.Counters(openTotal, assignedToMe, slaBreached));
    }

    // ---------------------------------------------------------------- detail
    @Transactional
    public FraudCaseDetailDto detail(UUID caseId) {
        FraudCaseEntity c = require(caseId);
        timeline.save(new FraudTimelineEntry(caseId,
                FraudTimelineEntry.Type.CASE_VIEWED,
                caller.currentSubject(), null));
        publishAudit("audit.fraud.case.viewed", c, Map.of());
        return toDetail(c);
    }

    @Transactional(readOnly = true)
    public FraudCaseContextDto context(UUID caseId) {
        FraudCaseEntity c = require(caseId);
        // Demo-safe synthetic context: production aggregates from A1 + audit-query.
        return new FraudCaseContextDto(
                30,
                new FraudCaseContextDto.Money(c.getAmount().multiply(new BigDecimal("12")).toPlainString(), c.getCurrency()),
                7, 1,
                new FraudCaseContextDto.Money(c.getAmount().toPlainString(), c.getCurrency()),
                List.of(new FraudCaseContextDto.Deviation(
                        "AMOUNT_OUTLIER", c.getScoreBand().name(),
                        "Score " + c.getScore().toPlainString())));
    }

    // ---------------------------------------------------------------- assign
    @Transactional
    public FraudCaseDetailDto assign(UUID caseId, FraudAssignRequest req) {
        FraudCaseEntity c = require(caseId);
        if (c.isDecided()) throw CaseworkProblemException.caseAlreadyDecided(caseId.toString());
        UUID newAssignee;
        if ("unassigned".equalsIgnoreCase(req.assignee())) {
            newAssignee = null;
        } else if ("me".equalsIgnoreCase(req.assignee())) {
            newAssignee = caller.requireUserId();
        } else {
            // Reassign to a named operator: SUPERVISOR-only.
            if (!caller.hasAnyRole("SUPERVISOR", "ADMIN")) {
                throw CaseworkProblemException.forbidden(
                        "Reassign to a named operator requires SUPERVISOR role");
            }
            try { newAssignee = UUID.fromString(req.assignee()); }
            catch (IllegalArgumentException e) {
                throw CaseworkProblemException.invalidInput(
                        "assignee must be 'me', 'unassigned', or a UUID");
            }
        }
        c.assignTo(newAssignee);
        cases.save(c);
        timeline.save(new FraudTimelineEntry(caseId,
                FraudTimelineEntry.Type.CASE_ASSIGNED,
                caller.currentSubject(),
                newAssignee == null ? "Unassigned" : "Assigned to " + newAssignee));
        publishAudit("audit.fraud.case.assigned", c, Map.of(
                "assignee", String.valueOf(newAssignee)));
        Map<String, Object> sse = new LinkedHashMap<>();
        sse.put("caseId", caseId.toString());
        sse.put("assignee", String.valueOf(newAssignee));
        hub.publish(CaseworkStreamHub.Scope.FRAUD, caseId.toString(), "case.assigned", sse);
        return toDetail(c);
    }

    // ---------------------------------------------------------------- decide
    @Transactional
    public FraudDecideAck decide(UUID caseId, FraudDecideRequest req, String stepUpToken) {
        validateDecideBody(req);
        FraudCaseEntity c = require(caseId);
        if (c.isDecided()) throw CaseworkProblemException.caseAlreadyDecided(caseId.toString());
        verifyStepUp(stepUpToken, "DECIDE_FRAUD", "case", caseId.toString(),
                req.decision(), req.notes());
        return applyDecision(c, req.decision(), req.notes(), req.freezeReason());
    }

    // ---------------------------------------------------------------- bulk decide
    @Transactional
    public FraudBulkDecideAck bulkDecide(FraudBulkDecideRequest req, String stepUpToken) {
        validateBulkBody(req);
        UUID operator = caller.requireUserId();
        Instant lastRun = bulkRuns.findLastRequestedAt(operator);
        if (lastRun != null && Instant.now().isBefore(lastRun.plus(bulkCooldown))) {
            throw CaseworkProblemException.concurrentLimit(
                    "Bulk-decide cooldown (" + bulkCooldown + ") not elapsed");
        }
        // Bulk DL form: sorted, joined case-ids hash as the resourceId.
        List<UUID> sorted = new ArrayList<>(req.caseIds());
        sorted.sort(java.util.Comparator.comparing(UUID::toString));
        String bulkRef = DynamicLinkingHash.sha256HexNfc(
                sorted.stream().map(UUID::toString).collect(Collectors.joining(",")));
        verifyStepUp(stepUpToken, "DECIDE_FRAUD", "case", bulkRef,
                req.decision(), req.notes());

        List<FraudBulkDecideAck.Result> results = new ArrayList<>();
        Map<UUID, FraudCaseEntity> byId = new HashMap<>();
        cases.findByCaseIdIn(req.caseIds()).forEach(c -> byId.put(c.getCaseId(), c));
        int succeeded = 0;
        int failed = 0;
        for (UUID caseId : req.caseIds()) {
            FraudCaseEntity c = byId.get(caseId);
            if (c == null) {
                results.add(new FraudBulkDecideAck.Result(caseId, "NOT_FOUND", "case-not-found"));
                failed++;
                continue;
            }
            if (c.isDecided()) {
                results.add(new FraudBulkDecideAck.Result(caseId, "ALREADY_DECIDED", "case-already-decided"));
                failed++;
                continue;
            }
            try {
                applyDecision(c, req.decision(), req.notes(), req.freezeReason());
                results.add(new FraudBulkDecideAck.Result(caseId, "APPLIED", null));
                succeeded++;
            } catch (Exception e) {
                results.add(new FraudBulkDecideAck.Result(caseId, "INTERNAL_ERROR", e.getMessage()));
                failed++;
            }
        }
        bulkRuns.save(new BulkDecideRunEntity(operator, req.caseIds().size(), req.decision()));
        return new FraudBulkDecideAck(req.caseIds().size(), succeeded, failed, results);
    }

    // ---------------------------------------------------------------- helpers

    private FraudCaseEntity require(UUID caseId) {
        return cases.findById(caseId)
                .orElseThrow(() -> CaseworkProblemException.notFound("Case " + caseId));
    }

    private FraudDecideAck applyDecision(FraudCaseEntity c, String decision, String notes,
                                         String freezeReason) {
        FraudCaseEntity.Status target = "CONFIRMED_FRAUD".equals(decision)
                ? FraudCaseEntity.Status.CONFIRMED_FRAUD
                : FraudCaseEntity.Status.DISMISSED;
        UUID operator = caller.requireUserId();
        String notesHash = DynamicLinkingHash.sha256HexNfc(notes);
        String summary = clampSummary(notes);
        c.decide(target, operator, notesHash, summary);
        cases.save(c);

        // Cascade on held payments.
        List<FraudHeldPaymentEntity> heldPayments = held.findByCaseId(c.getCaseId());
        List<UUID> affected = new ArrayList<>();
        for (FraudHeldPaymentEntity hp : heldPayments) {
            String newStatus = target == FraudCaseEntity.Status.CONFIRMED_FRAUD ? "REJECTED" : "RELEASED";
            hp.setStatus(newStatus);
            held.save(hp);
            affected.add(hp.getPaymentId());
            timeline.save(new FraudTimelineEntry(c.getCaseId(),
                    target == FraudCaseEntity.Status.CONFIRMED_FRAUD
                            ? FraudTimelineEntry.Type.PAYMENT_REJECTED
                            : FraudTimelineEntry.Type.PAYMENT_RELEASED,
                    caller.currentSubject(),
                    "Payment " + hp.getPaymentId() + " " + newStatus));
        }
        timeline.save(new FraudTimelineEntry(c.getCaseId(),
                FraudTimelineEntry.Type.CASE_DECIDED,
                caller.currentSubject(), summary));

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("decision", target.name());
        auditPayload.put("notesHash", notesHash);
        auditPayload.put("affectedPayments", affected.stream().map(UUID::toString).toList());
        if (target == FraudCaseEntity.Status.CONFIRMED_FRAUD) {
            auditPayload.put("freezeReason", freezeReason == null ? "FRAUD_INVESTIGATION" : freezeReason);
            auditPayload.put("freezeReference", "fraud-case-" + c.getCaseId());
        }
        publishAudit("audit.fraud.case.decided", c, auditPayload);

        Map<String, Object> sse = new LinkedHashMap<>();
        sse.put("caseId", c.getCaseId().toString());
        sse.put("decision", target.name());
        sse.put("decidedBy", operator.toString());
        hub.publish(CaseworkStreamHub.Scope.FRAUD, c.getCaseId().toString(), "case.decided", sse);

        FraudDecideAck.AccountFrozen frozen = null;
        if (target == FraudCaseEntity.Status.CONFIRMED_FRAUD) {
            frozen = new FraudDecideAck.AccountFrozen(
                    c.getDebtorAccountId(),
                    freezeReason == null ? "FRAUD_INVESTIGATION" : freezeReason,
                    "fraud-case-" + c.getCaseId());
        }
        return new FraudDecideAck(c.getCaseId(), target.name(), c.getDecidedAt(),
                "operator:" + operator, affected, frozen);
    }

    private void validateDecideBody(FraudDecideRequest req) {
        if (req.confirmedCriteria() == null || !req.confirmedCriteria()) {
            throw CaseworkProblemException.invalidInput("confirmedCriteria must be true");
        }
        if (!"CONFIRMED_FRAUD".equals(req.decision()) && !"DISMISSED".equals(req.decision())) {
            throw CaseworkProblemException.invalidInput(
                    "decision must be CONFIRMED_FRAUD or DISMISSED");
        }
        if (req.notes() == null || req.notes().length() < 50) {
            throw CaseworkProblemException.validation("notes must be ≥ 50 characters");
        }
    }

    private void validateBulkBody(FraudBulkDecideRequest req) {
        if (req.confirmedCriteria() == null || !req.confirmedCriteria()) {
            throw CaseworkProblemException.invalidInput("confirmedCriteria must be true");
        }
        if (!"CONFIRMED_FRAUD".equals(req.decision()) && !"DISMISSED".equals(req.decision())) {
            throw CaseworkProblemException.invalidInput(
                    "decision must be CONFIRMED_FRAUD or DISMISSED");
        }
        if (req.caseIds().size() > 50) {
            throw CaseworkProblemException.validation("caseIds capped at 50");
        }
        if (req.notes() == null || req.notes().length() < 50) {
            throw CaseworkProblemException.validation("notes must be ≥ 50 characters");
        }
    }

    private void verifyStepUp(String token, String action, String resourceType,
                              String resourceId, String decision, String notes) {
        String notesHash = DynamicLinkingHash.sha256HexNfc(notes);
        DynamicLinkingHash.Computation expected = dlHasher.computeAdminAction(
                action, resourceType, resourceId, decision, notesHash);
        StepUpValidator.ValidationResult res = stepUpValidator.validate(token, expected.hex());
        switch (res.outcome()) {
            case VALID -> { /* ok */ }
            case MISSING, NOT_FOUND -> throw CaseworkProblemException.stepUpRequired(res.detail());
            case DL_HASH_MISMATCH -> throw CaseworkProblemException.dynamicLinkingMismatch(res.detail());
            case IDENTITY_ERROR -> throw CaseworkProblemException.identityUnavailable(res.detail());
        }
    }

    private void publishAudit(String type, FraudCaseEntity c, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("caseId", c.getCaseId().toString());
        payload.put("paymentId", c.getPaymentId().toString());
        audit.publish(AdminAuditEvent.builder(type)
                .actor(caller.currentSubject())
                .operatorUserId(caller.currentUserId().orElse(null))
                .customerUserId(c.getCustomerUserId())
                .resourceType("case")
                .resourceId(c.getCaseId().toString())
                .caseId(c.getCaseId())
                .paymentId(c.getPaymentId())
                .summary(type + " for case " + c.getCaseId())
                .payload(payload)
                .build());
    }

    private FraudCaseSummaryDto toSummary(FraudCaseEntity c, Instant now) {
        List<String> rules = c.getTriggeredRules() == null ? List.of()
                : Arrays.stream(c.getTriggeredRules().split(","))
                        .filter(s -> !s.isBlank())
                        .limit(3)
                        .toList();
        return new FraudCaseSummaryDto(
                c.getCaseId(), c.getStatus().name(),
                c.getScore().toPlainString(), c.getScoreBand().name(),
                c.getAmount().toPlainString(), c.getCurrency(),
                Masking.holderName(c.getHolderName()),
                c.getDebtorAccountId(),
                Masking.iban(c.getCreditorIban()),
                rules, c.getAssignee(),
                c.getCreatedAt(), c.getSlaDeadline(),
                c.isSlaBreached(now), c.getPaymentId());
    }

    private FraudCaseDetailDto toDetail(FraudCaseEntity c) {
        FraudCaseDetailDto.Transaction tx = new FraudCaseDetailDto.Transaction(
                c.getPaymentId(), null, c.getCreditorIban(), c.getCreditorName(),
                c.getAmount().toPlainString(), c.getCurrency(), c.getReference(),
                c.getExecutionMode(), c.getInitiatedAt());
        Map<String, Object> breakdown;
        try {
            breakdown = c.getScoreBreakdown() == null ? Map.of()
                    : mapper.readValue(c.getScoreBreakdown(), Map.class);
        } catch (Exception e) { breakdown = Map.of(); }
        breakdown = new LinkedHashMap<>(breakdown);
        breakdown.put("composite", c.getScore().toPlainString());
        breakdown.put("threshold", c.getThreshold().toPlainString());
        List<FraudCaseDetailDto.Timeline> tl = timeline.findByCaseIdOrderByOccurredAtAsc(c.getCaseId())
                .stream()
                .map(t -> new FraudCaseDetailDto.Timeline(t.getOccurredAt(),
                        t.getType().name(), t.getActor(), t.getNote()))
                .toList();
        List<FraudCaseDetailDto.HeldPayment> heldDtos = held.findByCaseId(c.getCaseId())
                .stream()
                .map(h -> new FraudCaseDetailDto.HeldPayment(
                        h.getPaymentId(), h.getAmount().toPlainString(),
                        h.getCurrency(), h.getStatus()))
                .toList();
        FraudCaseDetailDto.Decision decision = c.getDecidedAt() == null ? null
                : new FraudCaseDetailDto.Decision(
                        c.getDecidedBy() == null ? null : "operator:" + c.getDecidedBy(),
                        c.getDecidedAt(), c.getStatus().name(),
                        c.getDecisionNotesSummary(), c.getDecisionNotesHash());
        return new FraudCaseDetailDto(toSummary(c, Instant.now()), tx, breakdown, tl, heldDtos, decision);
    }

    private static String clampSummary(String notes) {
        if (notes == null) return "";
        String collapsed = notes.replaceAll("\\s+", " ").trim();
        return collapsed.length() > 140 ? collapsed.substring(0, 137) + "..." : collapsed;
    }
}
