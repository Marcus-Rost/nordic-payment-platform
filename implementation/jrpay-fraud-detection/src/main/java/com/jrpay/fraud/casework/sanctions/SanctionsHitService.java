package com.jrpay.fraud.casework.sanctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.fraud.casework.CaseworkProblemException;
import com.jrpay.fraud.casework.Masking;
import com.jrpay.fraud.casework.audit.AdminAuditEvent;
import com.jrpay.fraud.casework.audit.AdminAuditPublisher;
import com.jrpay.fraud.casework.config.AuthenticatedUser;
import com.jrpay.fraud.casework.domain.FpCacheEntity;
import com.jrpay.fraud.casework.domain.FpCacheRepository;
import com.jrpay.fraud.casework.domain.FraudCaseEntity;
import com.jrpay.fraud.casework.domain.FraudCaseRepository;
import com.jrpay.fraud.casework.domain.FraudTimelineEntry;
import com.jrpay.fraud.casework.domain.FraudTimelineRepository;
import com.jrpay.fraud.casework.domain.SanctionsHitEntity;
import com.jrpay.fraud.casework.domain.SanctionsHitRepository;
import com.jrpay.fraud.casework.domain.SanctionsListEntity;
import com.jrpay.fraud.casework.domain.SanctionsListRefreshRunEntity;
import com.jrpay.fraud.casework.domain.SanctionsListRefreshRunRepository;
import com.jrpay.fraud.casework.domain.SanctionsListRepository;
import com.jrpay.fraud.casework.sanctions.dto.FpCacheEntryDto;
import com.jrpay.fraud.casework.sanctions.dto.FpCachePage;
import com.jrpay.fraud.casework.sanctions.dto.SanctionsDecideAck;
import com.jrpay.fraud.casework.sanctions.dto.SanctionsDecideRequest;
import com.jrpay.fraud.casework.sanctions.dto.SanctionsHitDetailDto;
import com.jrpay.fraud.casework.sanctions.dto.SanctionsHitPage;
import com.jrpay.fraud.casework.sanctions.dto.SanctionsHitSummaryDto;
import com.jrpay.fraud.casework.sanctions.dto.SanctionsListMetaDto;
import com.jrpay.fraud.casework.sanctions.dto.SanctionsListRefreshJobDto;
import com.jrpay.fraud.casework.sca.DynamicLinkingHash;
import com.jrpay.fraud.casework.sca.StepUpValidator;
import com.jrpay.fraud.casework.stream.CaseworkStreamHub;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SanctionsHitService {

    private final SanctionsHitRepository hits;
    private final SanctionsListRepository lists;
    private final SanctionsListRefreshRunRepository refreshRuns;
    private final FpCacheRepository fpCache;
    private final FraudCaseRepository fraudCases;
    private final FraudTimelineRepository fraudTimeline;
    private final StepUpValidator stepUpValidator;
    private final DynamicLinkingHash dlHasher;
    private final AdminAuditPublisher audit;
    private final AuthenticatedUser caller;
    private final CaseworkStreamHub hub;
    private final ObjectMapper mapper;
    private final Duration fpCacheTtl;
    private final Duration fraudSlaWindow;

    public SanctionsHitService(SanctionsHitRepository hits,
                               SanctionsListRepository lists,
                               SanctionsListRefreshRunRepository refreshRuns,
                               FpCacheRepository fpCache,
                               FraudCaseRepository fraudCases,
                               FraudTimelineRepository fraudTimeline,
                               StepUpValidator stepUpValidator,
                               DynamicLinkingHash dlHasher,
                               AdminAuditPublisher audit,
                               AuthenticatedUser caller,
                               CaseworkStreamHub hub,
                               ObjectMapper mapper,
                               @Value("${jrpay.fraud.fp-cache.ttl-days:90}") int fpCacheTtlDays,
                               @Value("${jrpay.fraud.case.sla:PT4H}") Duration fraudSlaWindow) {
        this.hits = hits;
        this.lists = lists;
        this.refreshRuns = refreshRuns;
        this.fpCache = fpCache;
        this.fraudCases = fraudCases;
        this.fraudTimeline = fraudTimeline;
        this.stepUpValidator = stepUpValidator;
        this.dlHasher = dlHasher;
        this.audit = audit;
        this.caller = caller;
        this.hub = hub;
        this.mapper = mapper;
        this.fpCacheTtl = Duration.ofDays(fpCacheTtlDays);
        this.fraudSlaWindow = fraudSlaWindow;
    }

    // -------------------------------------------------------------- queue
    @Transactional(readOnly = true)
    public SanctionsHitPage list(Collection<SanctionsHitEntity.Status> statuses, String listId,
                                 int page, int size) {
        Collection<SanctionsHitEntity.Status> effective = (statuses == null || statuses.isEmpty())
                ? List.of(SanctionsHitEntity.Status.OPEN)
                : statuses;
        Page<SanctionsHitEntity> result = (listId == null || listId.isBlank())
                ? hits.listByStatus(effective, PageRequest.of(page, size))
                : hits.listByStatusAndList(effective, listId, PageRequest.of(page, size));
        Instant now = Instant.now();
        List<SanctionsHitSummaryDto> items = result.getContent().stream()
                .map(h -> toSummary(h, now)).toList();
        long openTotal = hits.countByStatusIn(List.of(SanctionsHitEntity.Status.OPEN));
        long slaBreached = hits.countSlaBreached(List.of(SanctionsHitEntity.Status.OPEN), now);
        return new SanctionsHitPage(items,
                new SanctionsHitPage.Page(result.getNumber(), result.getSize(),
                        result.getTotalElements(), result.getTotalPages()),
                new SanctionsHitPage.Counters(openTotal, slaBreached));
    }

    @Transactional(readOnly = true)
    public SanctionsHitDetailDto detail(UUID hitId) {
        SanctionsHitEntity h = require(hitId);
        Map<String, Object> matched;
        try {
            matched = h.getMatchedEntityPayload() == null ? Map.of()
                    : mapper.readValue(h.getMatchedEntityPayload(), Map.class);
        } catch (Exception e) { matched = Map.of(); }
        SanctionsHitDetailDto.Decision decision = h.getDecidedAt() == null ? null
                : new SanctionsHitDetailDto.Decision(
                        h.getDecidedBy() == null ? null : "operator:" + h.getDecidedBy(),
                        h.getDecidedAt(), h.getStatus().name(),
                        h.getDecisionNotesSummary(), h.getDecisionNotesHash());
        return new SanctionsHitDetailDto(toSummary(h, Instant.now()),
                h.getCreditorIban(), h.getReference(), matched, decision);
    }

    // -------------------------------------------------------------- decide
    @Transactional
    public SanctionsDecideAck decide(UUID hitId, SanctionsDecideRequest req, String stepUpToken) {
        validateBody(req);
        SanctionsHitEntity h = require(hitId);
        if (h.isDecided()) throw CaseworkProblemException.hitAlreadyDecided(hitId.toString());
        SanctionsHitEntity.Status target = parseDecision(req.decision());
        verifyStepUp(stepUpToken, "DECIDE_SANCTIONS", "hit", hitId.toString(),
                target.name(), req.notes());
        UUID operator = caller.requireUserId();
        String notesHash = DynamicLinkingHash.sha256HexNfc(req.notes());
        String summary = clampSummary(req.notes());
        h.decide(target, operator, notesHash, summary);
        hits.save(h);

        UUID newFraudCaseId = null;
        UUID fpEntryId = null;
        String accountFrozen = null;

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("decision", target.name());
        auditPayload.put("notesHash", notesHash);

        switch (target) {
            case CONFIRMED_SANCTION -> {
                accountFrozen = h.getDebtorAccountId();
                auditPayload.put("freezeReason", "SANCTIONS_HOLD");
                auditPayload.put("freezeReference", "sanctions-hit-" + h.getHitId());
            }
            case FALSE_POSITIVE -> {
                if (Boolean.TRUE.equals(req.addToFpCache())) {
                    Instant expiresAt = Instant.now().plus(fpCacheTtl);
                    FpCacheEntity entry = new FpCacheEntity(
                            UUID.randomUUID(), h.getMatchedEntityName(), h.getMatchedListId(),
                            h.getCreditorIban(), summary, operator, h.getHitId(), expiresAt);
                    fpCache.save(entry);
                    fpEntryId = entry.getEntryId();
                    auditPayload.put("fpCacheEntryId", fpEntryId.toString());
                }
            }
            case RELEASE_FOR_FRAUD_REVIEW -> {
                FraudCaseEntity fc = new FraudCaseEntity(
                        UUID.randomUUID(), h.getPaymentId(),
                        h.getCustomerUserId(), h.getDebtorAccountId(),
                        null,
                        h.getCreditorIban(), h.getCreditorName(),
                        h.getAmount(), h.getCurrency(),
                        h.getReference(), null,
                        h.getMatchScore(), h.getMatchScore(),
                        "SANCT-ESCALATED", null,
                        Instant.now(), Instant.now().plus(fraudSlaWindow));
                fraudCases.save(fc);
                fraudTimeline.save(new FraudTimelineEntry(fc.getCaseId(),
                        FraudTimelineEntry.Type.CASE_CREATED,
                        caller.currentSubject(),
                        "Escalated from sanctions hit " + h.getHitId()));
                newFraudCaseId = fc.getCaseId();
                auditPayload.put("escalatedFraudCaseId", newFraudCaseId.toString());
            }
            default -> throw new IllegalStateException("Unhandled: " + target);
        }

        publishAudit("audit.sanctions.hit.decided", h, auditPayload);

        Map<String, Object> sse = new LinkedHashMap<>();
        sse.put("hitId", h.getHitId().toString());
        sse.put("decision", target.name());
        sse.put("decidedBy", operator.toString());
        hub.publish(CaseworkStreamHub.Scope.SANCTIONS, h.getHitId().toString(), "hit.decided", sse);

        return new SanctionsDecideAck(
                h.getHitId(), target.name(), h.getDecidedAt(),
                "operator:" + operator, newFraudCaseId, fpEntryId, accountFrozen);
    }

    // -------------------------------------------------------------- list metadata
    @Transactional(readOnly = true)
    public List<SanctionsListMetaDto> listMetadata() {
        Instant now = Instant.now();
        return lists.findAll().stream().map(l -> {
            long staleHours = ChronoUnit.HOURS.between(l.getLastRefreshAt(), now);
            return new SanctionsListMetaDto(
                    l.getListId(), l.getName(), l.getSource(),
                    l.getVersion(), l.getEntryCount(),
                    l.getLastRefreshAt(), Math.max(0, staleHours));
        }).toList();
    }

    @Transactional
    public SanctionsListRefreshJobDto requestRefresh(List<String> listIds, String stepUpToken) {
        UUID operator = caller.requireUserId();
        verifyStepUp(stepUpToken, "DECIDE_SANCTIONS", "audit", "list-refresh",
                null, "list-refresh");

        UUID runId = UUID.randomUUID();
        String listIdsStr = listIds == null || listIds.isEmpty() ? null
                : String.join(",", listIds);
        SanctionsListRefreshRunEntity run = new SanctionsListRefreshRunEntity(runId, operator, listIdsStr);
        run.markRunning();
        // Demo: refresh synchronously by bumping last_refresh_at.
        Collection<SanctionsListEntity> targets = listIds == null || listIds.isEmpty()
                ? lists.findAll()
                : lists.findAllById(listIds);
        for (SanctionsListEntity l : targets) {
            l.markRefreshed(l.getVersion(), l.getEntryCount());
            lists.save(l);
        }
        run.markSucceeded();
        refreshRuns.save(run);

        publishAuditList("audit.sanctions.list.refreshed", runId, Map.of(
                "listIds", listIdsStr == null ? "all" : listIdsStr));
        return toRefreshDto(run);
    }

    @Transactional(readOnly = true)
    public SanctionsListRefreshJobDto getRefresh(UUID runId) {
        return refreshRuns.findById(runId)
                .map(this::toRefreshDto)
                .orElseThrow(() -> CaseworkProblemException.notFound("Refresh run " + runId));
    }

    // -------------------------------------------------------------- fp cache
    @Transactional(readOnly = true)
    public FpCachePage listFpCache(String matchedEntity, int page, int size) {
        Page<FpCacheEntity> rows = (matchedEntity == null || matchedEntity.isBlank())
                ? fpCache.listActive(PageRequest.of(page, size))
                : fpCache.searchActive(matchedEntity, PageRequest.of(page, size));
        List<FpCacheEntryDto> items = rows.getContent().stream().map(this::toFpDto).toList();
        return new FpCachePage(items, new FpCachePage.Page(
                rows.getNumber(), rows.getSize(),
                rows.getTotalElements(), rows.getTotalPages()));
    }

    @Transactional
    public void removeFpEntry(UUID entryId, String stepUpToken) {
        verifyStepUp(stepUpToken, "DECIDE_SANCTIONS", "audit",
                "fp-cache-remove", entryId.toString(), "remove FP entry " + entryId);
        FpCacheEntity entry = fpCache.findById(entryId)
                .orElseThrow(() -> CaseworkProblemException.notFound("FP cache entry " + entryId));
        if (entry.getRemovedAt() != null) {
            return; // already removed; idempotent.
        }
        entry.remove();
        fpCache.save(entry);
        publishAuditFp("audit.sanctions.fp-cache.removed", entry, Map.of());
    }

    // -------------------------------------------------------------- helpers

    private SanctionsHitEntity require(UUID hitId) {
        return hits.findById(hitId)
                .orElseThrow(() -> CaseworkProblemException.notFound("Sanctions hit " + hitId));
    }

    private SanctionsHitEntity.Status parseDecision(String s) {
        return switch (s) {
            case "CONFIRMED_SANCTION" -> SanctionsHitEntity.Status.CONFIRMED_SANCTION;
            case "FALSE_POSITIVE" -> SanctionsHitEntity.Status.FALSE_POSITIVE;
            case "RELEASE_FOR_FRAUD_REVIEW" -> SanctionsHitEntity.Status.RELEASE_FOR_FRAUD_REVIEW;
            default -> throw CaseworkProblemException.invalidInput("Unknown decision: " + s);
        };
    }

    private void validateBody(SanctionsDecideRequest req) {
        if (req.confirmedCriteria() == null || !req.confirmedCriteria()) {
            throw CaseworkProblemException.invalidInput("confirmedCriteria must be true");
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

    private void publishAudit(String type, SanctionsHitEntity h, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("hitId", h.getHitId().toString());
        payload.put("paymentId", h.getPaymentId().toString());
        audit.publish(AdminAuditEvent.builder(type)
                .actor(caller.currentSubject())
                .operatorUserId(caller.currentUserId().orElse(null))
                .customerUserId(h.getCustomerUserId())
                .resourceType("hit")
                .resourceId(h.getHitId().toString())
                .paymentId(h.getPaymentId())
                .summary(type + " for hit " + h.getHitId())
                .payload(payload)
                .build());
    }

    private void publishAuditList(String type, UUID runId, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("runId", runId.toString());
        audit.publish(AdminAuditEvent.builder(type)
                .actor(caller.currentSubject())
                .operatorUserId(caller.currentUserId().orElse(null))
                .resourceType("audit")
                .resourceId(runId.toString())
                .summary(type + " runId=" + runId)
                .payload(payload)
                .build());
    }

    private void publishAuditFp(String type, FpCacheEntity entry, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("entryId", entry.getEntryId().toString());
        audit.publish(AdminAuditEvent.builder(type)
                .actor(caller.currentSubject())
                .operatorUserId(caller.currentUserId().orElse(null))
                .resourceType("audit")
                .resourceId(entry.getEntryId().toString())
                .summary(type + " entryId=" + entry.getEntryId())
                .payload(payload)
                .build());
    }

    private SanctionsHitSummaryDto toSummary(SanctionsHitEntity h, Instant now) {
        return new SanctionsHitSummaryDto(
                h.getHitId(), h.getPaymentId(),
                h.getMatchedEntityName(), h.getMatchedListId(),
                h.getMatchScore().toPlainString(),
                Masking.iban(h.getCreditorIban()),
                h.getCreditorName(),
                h.getAmount().toPlainString(), h.getCurrency(),
                h.getStatus().name(), h.getAssignee(),
                h.getSlaDeadline(),
                !h.isDecided() && now.isAfter(h.getSlaDeadline()),
                h.getCreatedAt());
    }

    private SanctionsListRefreshJobDto toRefreshDto(SanctionsListRefreshRunEntity run) {
        List<String> ids = run.getListIds() == null ? List.of()
                : Arrays.stream(run.getListIds().split(",")).filter(s -> !s.isBlank()).toList();
        return new SanctionsListRefreshJobDto(
                run.getRunId(), run.getStatus().name(), ids,
                run.getRequestedAt(), run.getStartedAt(), run.getCompletedAt(),
                run.getFailureReason());
    }

    private FpCacheEntryDto toFpDto(FpCacheEntity e) {
        return new FpCacheEntryDto(
                e.getEntryId(), e.getMatchedEntityName(), e.getMatchedListId(),
                Masking.iban(e.getCreditorIban()), e.getNotesSummary(),
                e.getAddedBy(), e.getCreatedAt(), e.getExpiresAt());
    }

    private static String clampSummary(String notes) {
        if (notes == null) return "";
        String collapsed = notes.replaceAll("\\s+", " ").trim();
        return collapsed.length() > 140 ? collapsed.substring(0, 137) + "..." : collapsed;
    }

}
