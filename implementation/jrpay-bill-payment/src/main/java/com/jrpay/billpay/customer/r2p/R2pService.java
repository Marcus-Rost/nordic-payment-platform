package com.jrpay.billpay.customer.r2p;

import com.jrpay.billpay.audit.CustomerAuditEvent;
import com.jrpay.billpay.audit.CustomerAuditPublisher;
import com.jrpay.billpay.config.AuthenticatedUser;
import com.jrpay.billpay.customer.CustomerProblemException;
import com.jrpay.billpay.customer.r2p.dto.R2pAcceptAck;
import com.jrpay.billpay.customer.r2p.dto.R2pAcceptPendingSca;
import com.jrpay.billpay.customer.r2p.dto.R2pAcceptRequest;
import com.jrpay.billpay.customer.r2p.dto.R2pAmendRequest;
import com.jrpay.billpay.customer.r2p.dto.R2pDeclineRequest;
import com.jrpay.billpay.customer.r2p.dto.R2pDeferAck;
import com.jrpay.billpay.customer.r2p.dto.R2pDeferPendingSca;
import com.jrpay.billpay.customer.r2p.dto.R2pDeferRequest;
import com.jrpay.billpay.customer.r2p.dto.R2pDetail;
import com.jrpay.billpay.customer.r2p.dto.R2pPage;
import com.jrpay.billpay.customer.r2p.dto.R2pSummary;
import com.jrpay.billpay.domain.PaymentRequest;
import com.jrpay.billpay.domain.PaymentRequestRepository;
import com.jrpay.billpay.domain.R2pHistoryEntry;
import com.jrpay.billpay.domain.R2pHistoryRepository;
import com.jrpay.billpay.domain.RequestStatus;
import com.jrpay.billpay.sca.DynamicLinkingHash;
import com.jrpay.billpay.sca.ScaInitiator;
import com.jrpay.billpay.sca.StepUpValidator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the customer R2P surface (UC-G11..G14). The service initiates
 * SCA via {@link ScaInitiator} when a Step-Up-Token is missing, and validates
 * a presented Step-Up-Token via {@link StepUpValidator} otherwise. Audit
 * events are published to {@code audit.events} on every state transition.
 */
@Service
public class R2pService {

    private static final Logger log = LoggerFactory.getLogger(R2pService.class);

    private final PaymentRequestRepository requests;
    private final R2pHistoryRepository history;
    private final ScaInitiator scaInitiator;
    private final StepUpValidator stepUpValidator;
    private final DynamicLinkingHash dlHasher;
    private final CustomerAuditPublisher audit;
    private final AuthenticatedUser caller;

    public R2pService(PaymentRequestRepository requests,
                      R2pHistoryRepository history,
                      ScaInitiator scaInitiator,
                      StepUpValidator stepUpValidator,
                      DynamicLinkingHash dlHasher,
                      CustomerAuditPublisher audit,
                      AuthenticatedUser caller) {
        this.requests = requests;
        this.history = history;
        this.scaInitiator = scaInitiator;
        this.stepUpValidator = stepUpValidator;
        this.dlHasher = dlHasher;
        this.audit = audit;
        this.caller = caller;
    }

    // -----------------------------------------------------------------
    // List + detail
    // -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public R2pPage list(Collection<RequestStatus> statuses, String merchantQuery, int page, int size) {
        UUID uid = caller.requireUserId();
        Collection<RequestStatus> effectiveStatuses = (statuses == null || statuses.isEmpty())
                ? List.of(RequestStatus.PENDING, RequestStatus.DEFERRED, RequestStatus.AMENDMENT_REQUESTED)
                : statuses;
        String q = blankToNull(merchantQuery);
        Page<PaymentRequest> result = (q == null)
                ? requests.searchInbox(uid, effectiveStatuses, PageRequest.of(page, size))
                : requests.searchInboxWithQuery(uid, effectiveStatuses, q, PageRequest.of(page, size));
        List<R2pSummary> items = result.getContent().stream().map(this::toSummary).toList();
        return new R2pPage(items, new R2pPage.Page(
                result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages()));
    }

    @Transactional(readOnly = true)
    public R2pDetail detail(String requestId) {
        PaymentRequest pr = requireOwn(requestId);
        return toDetail(pr);
    }

    // -----------------------------------------------------------------
    // Accept (UC-G11)
    // -----------------------------------------------------------------

    @Transactional
    public AcceptOutcome accept(String requestId, R2pAcceptRequest body, String stepUpToken) {
        PaymentRequest pr = requireOwn(requestId);
        guardAlreadyActed(pr);
        guardLifecycle(pr);

        String paymentId = ensurePaymentId(pr);
        String executionMode = body.executionMode() != null
                ? body.executionMode()
                : (pr.getRequestedExecutionMode() == null ? "INSTANT" : pr.getRequestedExecutionMode());
        Map<String, Object> dlInputs = dlHasher.paymentInputs(
                paymentId,
                pr.getAmount().toPlainString(),
                pr.getCurrency(),
                pr.getCreditorIban(),
                pr.getCreditorName(),
                executionMode);
        DynamicLinkingHash.Computation hash = dlHasher.compute(dlInputs);

        if (stepUpToken == null || stepUpToken.isBlank()) {
            ScaInitiator.InitiateResult init = scaInitiator.start(dlInputs, false);
            pr.setPaymentId(paymentId);
            requests.save(pr);
            publishAudit("r2p.sca.initiated", pr, body.debtorAccountId(), Map.of(
                    "scaSessionId", init.scaSessionId().toString(),
                    "dlHash", hash.hex(),
                    "purpose", "accept"));
            return new AcceptOutcome.PendingSca(new R2pAcceptPendingSca(
                    pr.getRequestId(), paymentId, init.scaSessionId(), init.dlHash(),
                    init.exempt(), init.stepUpToken(),
                    init.factorType() == null ? Map.of() : Map.of("type", init.factorType())));
        }

        verifyStepUp(stepUpToken, hash.hex());

        // SCA verified — finalise R2P transition.
        pr.setDebtorIban(body.debtorAccountId());
        pr.setPaymentId(paymentId);
        pr.applyTransition(com.jrpay.billpay.domain.RequestTrigger.CONSUMER_ACCEPT);
        requests.save(pr);
        history.save(new R2pHistoryEntry(pr.getRequestId(), pr.getStatus(),
                R2pHistoryEntry.Actor.CUSTOMER, "accepted via UC-G11"));

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("paymentId", paymentId);
        auditPayload.put("debtorAccountId", body.debtorAccountId());
        auditPayload.put("executionMode", executionMode);
        if (body.vopResult() != null) auditPayload.put("vopResult", body.vopResult());
        if (Boolean.TRUE.equals(body.vopPayerOverride())) {
            auditPayload.put("vopPayerOverride", true);
        }
        publishAudit("r2p.accepted", pr, body.debtorAccountId(), auditPayload);

        return new AcceptOutcome.Granted(new R2pAcceptAck(
                pr.getRequestId(), paymentId, "PENDING_EXECUTION",
                "INSTANT".equals(executionMode) ? "INSTANT" : "NICS",
                null, null));
    }

    public sealed interface AcceptOutcome
            permits AcceptOutcome.Granted, AcceptOutcome.PendingSca {
        record Granted(R2pAcceptAck ack) implements AcceptOutcome { }
        record PendingSca(R2pAcceptPendingSca pending) implements AcceptOutcome { }
    }

    // -----------------------------------------------------------------
    // Decline (UC-G12)
    // -----------------------------------------------------------------

    @Transactional
    public R2pDetail decline(String requestId, R2pDeclineRequest body) {
        PaymentRequest pr = requireOwn(requestId);
        guardAlreadyActed(pr);
        guardLifecycle(pr);
        validateDeclineNote(body);

        pr.setDeclineReason(body.reasonCode());
        pr.setDeclineNote(body.reasonNote());
        pr.applyTransition(com.jrpay.billpay.domain.RequestTrigger.CONSUMER_DECLINE);
        requests.save(pr);
        history.save(new R2pHistoryEntry(pr.getRequestId(), pr.getStatus(),
                R2pHistoryEntry.Actor.CUSTOMER, "declined via UC-G12"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reasonCode", body.reasonCode());
        if (body.reasonNote() != null) payload.put("reasonNote", body.reasonNote());
        publishAudit("r2p.declined", pr, null, payload);
        return toDetail(pr);
    }

    // -----------------------------------------------------------------
    // Defer (UC-G13)
    // -----------------------------------------------------------------

    @Transactional
    public DeferOutcome defer(String requestId, R2pDeferRequest body, String stepUpToken) {
        PaymentRequest pr = requireOwn(requestId);
        guardAlreadyActed(pr);
        guardLifecycle(pr);
        guardDeferTime(pr, body.executeAt());

        String scheduleId = pr.getScheduledPaymentId() == null
                ? UUID.randomUUID().toString() : pr.getScheduledPaymentId();
        String executionMode = body.executionMode() != null ? body.executionMode() : "STANDARD";
        Map<String, Object> dlInputs = dlHasher.paymentScheduleInputs(
                scheduleId,
                pr.getAmount().toPlainString(),
                pr.getCurrency(),
                pr.getCreditorIban(),
                pr.getCreditorName(),
                body.executeAt().toString(),
                executionMode);
        DynamicLinkingHash.Computation hash = dlHasher.compute(dlInputs);

        if (stepUpToken == null || stepUpToken.isBlank()) {
            ScaInitiator.InitiateResult init = scaInitiator.start(dlInputs, false);
            pr.setScheduledPaymentId(scheduleId);
            requests.save(pr);
            return new DeferOutcome.PendingSca(new R2pDeferPendingSca(
                    pr.getRequestId(), scheduleId, init.scaSessionId(), init.dlHash(),
                    init.exempt(), init.stepUpToken(),
                    init.factorType() == null ? Map.of() : Map.of("type", init.factorType())));
        }

        verifyStepUp(stepUpToken, hash.hex());
        pr.setDebtorIban(body.debtorAccountId());
        pr.setScheduledPaymentId(scheduleId);
        pr.applyTransition(com.jrpay.billpay.domain.RequestTrigger.CONSUMER_DEFER);
        requests.save(pr);
        history.save(new R2pHistoryEntry(pr.getRequestId(), pr.getStatus(),
                R2pHistoryEntry.Actor.CUSTOMER, "deferred via UC-G13 (executeAt=" + body.executeAt() + ")"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scheduledPaymentId", scheduleId);
        payload.put("executeAt", body.executeAt().toString());
        payload.put("executionMode", executionMode);
        if (body.note() != null) payload.put("note", body.note());
        publishAudit("r2p.deferred", pr, body.debtorAccountId(), payload);

        return new DeferOutcome.Granted(new R2pDeferAck(
                pr.getRequestId(), scheduleId, body.executeAt(), pr.getStatus().name()));
    }

    public sealed interface DeferOutcome
            permits DeferOutcome.Granted, DeferOutcome.PendingSca {
        record Granted(R2pDeferAck ack) implements DeferOutcome { }
        record PendingSca(R2pDeferPendingSca pending) implements DeferOutcome { }
    }

    @Transactional
    public R2pDetail cancelDefer(String requestId) {
        PaymentRequest pr = requireOwn(requestId);
        if (pr.getStatus() != RequestStatus.DEFERRED) {
            throw CustomerProblemException.r2pAlreadyActed(requestId);
        }
        if (pr.getScheduledPaymentId() == null) {
            throw CustomerProblemException.tooLateToCancel(requestId);
        }
        // In a real flow we'd call A4 to cancel the schedule; here we assume
        // the schedule has not yet executed.
        pr.setScheduledPaymentId(null);
        pr.setStatus(RequestStatus.PENDING);
        requests.save(pr);
        history.save(new R2pHistoryEntry(pr.getRequestId(), pr.getStatus(),
                R2pHistoryEntry.Actor.CUSTOMER, "defer cancelled (UC-G13 AF-03)"));
        publishAudit("r2p.defer.cancelled", pr, null, Map.of());
        return toDetail(pr);
    }

    // -----------------------------------------------------------------
    // Amend (UC-G14)
    // -----------------------------------------------------------------

    @Transactional
    public R2pDetail amend(String requestId, R2pAmendRequest body) {
        PaymentRequest pr = requireOwn(requestId);
        guardAlreadyActed(pr);
        guardLifecycle(pr);
        if (!pr.isAmendmentAllowed()) {
            throw CustomerProblemException.amendmentNotAllowed(requestId);
        }
        BigDecimal proposedAmount = parseAmountOrNull(body.proposedAmount());
        boolean amountDiffers = proposedAmount != null
                && proposedAmount.compareTo(pr.getAmount()) != 0;
        boolean dateDiffers = body.proposedDueDate() != null
                && !body.proposedDueDate().equals(pr.getDueDate());
        if (!amountDiffers && !dateDiffers) {
            throw CustomerProblemException.invalidInput(
                    "At least one of proposedAmount / proposedDueDate must differ from the original");
        }

        Instant now = Instant.now();
        pr.setAmendmentProposedAt(now);
        pr.setAmendmentProposedAmount(proposedAmount);
        pr.setAmendmentProposedDueDate(body.proposedDueDate());
        pr.setAmendmentReason(body.reason());
        pr.applyTransition(com.jrpay.billpay.domain.RequestTrigger.CONSUMER_AMEND);
        requests.save(pr);
        history.save(new R2pHistoryEntry(pr.getRequestId(), pr.getStatus(),
                R2pHistoryEntry.Actor.CUSTOMER, "amendment proposed (UC-G14)"));

        Map<String, Object> payload = new LinkedHashMap<>();
        if (proposedAmount != null) payload.put("proposedAmount", proposedAmount.toPlainString());
        if (body.proposedDueDate() != null) payload.put("proposedDueDate", body.proposedDueDate().toString());
        if (body.reason() != null) payload.put("reason", body.reason());
        publishAudit("r2p.amendment.requested", pr, null, payload);
        return toDetail(pr);
    }

    @Transactional
    public R2pDetail cancelAmend(String requestId) {
        PaymentRequest pr = requireOwn(requestId);
        if (pr.getStatus() != RequestStatus.AMENDMENT_REQUESTED
                && pr.getStatus() != RequestStatus.AMENDED) {
            throw CustomerProblemException.r2pAlreadyActed(requestId);
        }
        pr.setAmendmentProposedAt(null);
        pr.setAmendmentProposedAmount(null);
        pr.setAmendmentProposedDueDate(null);
        pr.setAmendmentReason(null);
        pr.setStatus(RequestStatus.PENDING);
        requests.save(pr);
        history.save(new R2pHistoryEntry(pr.getRequestId(), pr.getStatus(),
                R2pHistoryEntry.Actor.CUSTOMER, "amendment withdrawn (UC-G14 AF-05)"));
        publishAudit("r2p.amendment.cancelled", pr, null, Map.of());
        return toDetail(pr);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private PaymentRequest requireOwn(String requestId) {
        UUID uid = caller.requireUserId();
        return requests.findByRequestIdAndCustomerUserId(requestId, uid)
                .orElseThrow(() -> CustomerProblemException.notFound("R2P " + requestId));
    }

    private void guardAlreadyActed(PaymentRequest pr) {
        if (pr.getStatus() != RequestStatus.PENDING) {
            throw CustomerProblemException.r2pAlreadyActed(pr.getRequestId());
        }
    }

    private void guardLifecycle(PaymentRequest pr) {
        if (pr.getStatus() == RequestStatus.CANCELLED_BY_MERCHANT) {
            throw CustomerProblemException.r2pCancelled(pr.getRequestId());
        }
        if (pr.getStatus() == RequestStatus.EXPIRED) {
            throw CustomerProblemException.r2pExpired(pr.getRequestId());
        }
        if (pr.getDueDate() != null
                && pr.getDueDate().isBefore(LocalDate.now(ZoneOffset.UTC))) {
            // Late-accept policy: reject after due date.
            throw CustomerProblemException.r2pExpired(pr.getRequestId());
        }
    }

    private void guardDeferTime(PaymentRequest pr, Instant executeAt) {
        Instant now = Instant.now();
        if (executeAt == null || !executeAt.isAfter(now)) {
            throw CustomerProblemException.invalidInput("executeAt must be strictly after now");
        }
        if (pr.getDueDate() != null) {
            Instant dueEnd = OffsetDateTime.of(
                    pr.getDueDate(), LocalTime.of(23, 59, 59), ZoneOffset.UTC).toInstant();
            if (executeAt.isAfter(dueEnd)) {
                throw CustomerProblemException.invalidInput(
                        "executeAt must be on or before R2P due date");
            }
        }
    }

    private void validateDeclineNote(R2pDeclineRequest body) {
        if ("OTHER".equals(body.reasonCode())) {
            if (body.reasonNote() == null || body.reasonNote().length() < 10) {
                throw CustomerProblemException.validation(
                        "reasonNote required (≥10 chars) when reasonCode=OTHER");
            }
        }
    }

    private void verifyStepUp(String token, String expectedHash) {
        StepUpValidator.ValidationResult res = stepUpValidator.validate(token, expectedHash);
        switch (res.outcome()) {
            case VALID -> { /* ok */ }
            case MISSING, NOT_FOUND -> throw CustomerProblemException.stepUpRequired(res.detail());
            case DL_HASH_MISMATCH -> throw CustomerProblemException.dynamicLinkingMismatch(res.detail());
            case IDENTITY_ERROR -> throw CustomerProblemException.identityUnavailable(res.detail());
        }
    }

    private String ensurePaymentId(PaymentRequest pr) {
        if (pr.getPaymentId() != null) return pr.getPaymentId();
        return UUID.randomUUID().toString();
    }

    private void publishAudit(String type, PaymentRequest pr, String debtorAccountId,
                              Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("requestId", pr.getRequestId());
        payload.put("merchantId", pr.getMerchantId());
        if (debtorAccountId != null) payload.put("debtorAccountId", debtorAccountId);
        audit.publish(CustomerAuditEvent.builder(type)
                .actor(caller.currentSubject())
                .customerUserId(caller.currentUserId().orElse(null))
                .resourceType("payment-request")
                .resourceId(pr.getRequestId())
                .summary(type + " for R2P " + pr.getRequestId())
                .payload(payload)
                .build());
    }

    private R2pSummary toSummary(PaymentRequest pr) {
        return new R2pSummary(
                pr.getRequestId(),
                pr.getMerchantName(),
                pr.isMerchantVerified(),
                pr.getMerchantLogoUrl(),
                pr.getAmount().toPlainString(),
                pr.getCurrency(),
                pr.getDueDate(),
                pr.getStatus().name(),
                pr.getReference(),
                pr.getCreatedAt(),
                pr.getUpdatedAt(),
                pr.getRequestedExecutionMode());
    }

    private R2pDetail toDetail(PaymentRequest pr) {
        R2pDetail.Amendment amendment = pr.getAmendmentProposedAt() == null ? null
                : new R2pDetail.Amendment(
                        pr.getAmendmentProposedAt(),
                        pr.getAmendmentProposedAmount() == null ? null
                                : pr.getAmendmentProposedAmount().toPlainString(),
                        pr.getAmendmentProposedDueDate(),
                        pr.getAmendmentReason());
        List<R2pDetail.HistoryEntry> hist = history.findByRequestIdOrderByOccurredAtAsc(pr.getRequestId())
                .stream()
                .map(h -> new R2pDetail.HistoryEntry(
                        h.getOccurredAt(), h.getStatus().name(), h.getActor().name(), h.getNote()))
                .collect(Collectors.toList());
        return new R2pDetail(
                toSummary(pr),
                pr.getMerchantId(),
                pr.getDescription(),
                pr.getInvoicePdfUrl(),
                pr.getInvoicePdfExpiresAt(),
                pr.getCreditorIban(),
                pr.getCreditorName(),
                pr.isAmendmentAllowed(),
                pr.getPaymentId(),
                pr.getScheduledPaymentId(),
                pr.getDeclineReason(),
                pr.getDeclineNote(),
                pr.isAmendmentRejected(),
                amendment,
                pr.getReplacedByRequestId(),
                pr.getReplacesRequestId(),
                hist);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static BigDecimal parseAmountOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try { return new BigDecimal(s); } catch (Exception e) {
            throw CustomerProblemException.invalidInput("Malformed amount: " + s);
        }
    }
}
