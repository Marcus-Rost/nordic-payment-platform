package com.jrpay.billpay.customer.mandates;

import com.jrpay.billpay.audit.CustomerAuditEvent;
import com.jrpay.billpay.audit.CustomerAuditPublisher;
import com.jrpay.billpay.config.AuthenticatedUser;
import com.jrpay.billpay.customer.CustomerProblemException;
import com.jrpay.billpay.customer.mandates.dto.MandateCreateRequest;
import com.jrpay.billpay.customer.mandates.dto.MandateDetail;
import com.jrpay.billpay.customer.mandates.dto.MandateExecutionDto;
import com.jrpay.billpay.customer.mandates.dto.MandateExecutionPage;
import com.jrpay.billpay.customer.mandates.dto.MandatePage;
import com.jrpay.billpay.customer.mandates.dto.MandatePendingSca;
import com.jrpay.billpay.customer.mandates.dto.MandateSummary;
import com.jrpay.billpay.customer.mandates.dto.MandateUpdateRequest;
import com.jrpay.billpay.domain.Mandate;
import com.jrpay.billpay.domain.MandateExecution;
import com.jrpay.billpay.domain.MandateExecutionRepository;
import com.jrpay.billpay.domain.MandateFrequency;
import com.jrpay.billpay.domain.MandateRepository;
import com.jrpay.billpay.domain.Merchant;
import com.jrpay.billpay.domain.MerchantRepository;
import com.jrpay.billpay.sca.DynamicLinkingHash;
import com.jrpay.billpay.sca.ScaInitiator;
import com.jrpay.billpay.sca.StepUpValidator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MandateService {

    private static final int DEFAULT_PAGE_SIZE = 25;

    private final MandateRepository mandates;
    private final MandateExecutionRepository executions;
    private final MerchantRepository merchants;
    private final ScaInitiator scaInitiator;
    private final StepUpValidator stepUpValidator;
    private final DynamicLinkingHash dlHasher;
    private final CustomerAuditPublisher audit;
    private final AuthenticatedUser caller;

    public MandateService(MandateRepository mandates,
                          MandateExecutionRepository executions,
                          MerchantRepository merchants,
                          ScaInitiator scaInitiator,
                          StepUpValidator stepUpValidator,
                          DynamicLinkingHash dlHasher,
                          CustomerAuditPublisher audit,
                          AuthenticatedUser caller) {
        this.mandates = mandates;
        this.executions = executions;
        this.merchants = merchants;
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
    public MandatePage list(Collection<Mandate.MandateStatus> statuses, String merchantQuery,
                            int page, int size) {
        UUID uid = caller.requireUserId();
        Collection<Mandate.MandateStatus> effective = (statuses == null || statuses.isEmpty())
                ? List.of(Mandate.MandateStatus.ACTIVE, Mandate.MandateStatus.PAUSED)
                : statuses;
        String q = blankToNull(merchantQuery);
        Page<Mandate> result = (q == null)
                ? mandates.searchInbox(uid, effective, PageRequest.of(page, size))
                : mandates.searchInboxWithQuery(uid, effective, q, PageRequest.of(page, size));
        List<MandateSummary> items = result.getContent().stream().map(this::toSummary).toList();
        return new MandatePage(items, new MandatePage.Page(
                result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages()));
    }

    @Transactional(readOnly = true)
    public MandateDetail detail(String mandateId) {
        Mandate m = requireOwn(mandateId);
        return toDetail(m, List.of());
    }

    @Transactional(readOnly = true)
    public MandateExecutionPage executions(String mandateId, int page, int size) {
        requireOwn(mandateId);
        Page<MandateExecution> rows = executions.findByMandateIdOrderByAttemptedAtDesc(
                mandateId, PageRequest.of(page, size));
        List<MandateExecutionDto> items = rows.getContent().stream().map(this::toExecutionDto).toList();
        return new MandateExecutionPage(items, new MandateExecutionPage.Page(
                rows.getNumber(), rows.getSize(),
                rows.getTotalElements(), rows.getTotalPages()));
    }

    // -----------------------------------------------------------------
    // Create (UC-G08)
    // -----------------------------------------------------------------

    @Transactional
    public CreateOutcome create(MandateCreateRequest req) {
        UUID uid = caller.requireUserId();
        validateAmount(req.maxAmount());
        validateStartDate(req.startDate(), req.endDate());

        Merchant merchant = merchants.findById(req.merchantId())
                .orElseThrow(() -> CustomerProblemException.merchantNotInRegistry(req.merchantId()));
        MandateFrequency frequency = resolveFrequency(merchant, req.frequency());

        // Duplicate-active mandate guard.
        List<Mandate> existing = mandates.findByCustomerUserIdAndMerchantIdAndStatus(
                uid, merchant.getMerchantId(), Mandate.MandateStatus.ACTIVE);
        if (!existing.isEmpty()) {
            throw CustomerProblemException.duplicateConsent(
                    "Active mandate with merchant " + merchant.getMerchantId() + " already exists");
        }

        String mandateId = UUID.randomUUID().toString();
        BigDecimal maxAmount = new BigDecimal(req.maxAmount());

        Map<String, Object> dlInputs = dlHasher.mandateInputs(
                mandateId, merchant.getMerchantId(),
                maxAmount.toPlainString(), req.currency(), req.debtorAccountId());
        ScaInitiator.InitiateResult init = scaInitiator.start(dlInputs, false);

        Mandate mandate = new Mandate(
                mandateId, merchant.getMerchantId(), uid,
                req.debtorAccountId(), merchant.getIban(),
                maxAmount, req.currency(), frequency,
                req.startDate(), req.endDate(),
                req.notifyBeforeDebit() == null || req.notifyBeforeDebit(),
                init.scaSessionId(), init.dlHash());
        mandates.save(mandate);

        if (init.exempt()) {
            // Auto-activate when SCA is exempt (rare for mandates).
            mandate.activate();
            mandates.save(mandate);
        }

        publishAudit("mandate.created", mandate, Map.of(
                "merchantId", merchant.getMerchantId(),
                "scaSessionId", init.scaSessionId().toString(),
                "exempt", init.exempt()));

        return new CreateOutcome(new MandatePendingSca(
                mandateId, init.scaSessionId(), init.dlHash(),
                init.exempt(), init.stepUpToken(),
                init.factorType() == null ? Map.of() : Map.of("type", init.factorType())));
    }

    public record CreateOutcome(MandatePendingSca pending) {}

    // -----------------------------------------------------------------
    // Update (UC-G09 §6.3, §6.4)
    // -----------------------------------------------------------------

    @Transactional
    public UpdateOutcome update(String mandateId, MandateUpdateRequest req, String stepUpToken) {
        Mandate m = requireOwn(mandateId);
        // Pending in-flight executions block any update.
        if (executions.countByMandateIdAndStatus(mandateId, "PENDING") > 0) {
            throw CustomerProblemException.pendingExecution(
                    "Mandate " + mandateId + " has a debit in-flight");
        }
        boolean amountChange = req.maxAmount() != null;
        boolean statusChange = req.status() != null;
        boolean notifyChange = req.notifyBeforeDebit() != null;
        if (!amountChange && !statusChange && !notifyChange) {
            throw CustomerProblemException.invalidInput(
                    "Body must include at least one of maxAmount / status / notifyBeforeDebit");
        }
        List<MandateDetail.Warning> warnings = new ArrayList<>();

        if (amountChange) {
            BigDecimal newAmount = parseAmountOrThrow(req.maxAmount());
            Map<String, Object> dlInputs = dlHasher.mandateInputs(
                    mandateId, m.getMerchantId(),
                    newAmount.toPlainString(), m.getCurrency(), m.getDebtorAccountId());
            DynamicLinkingHash.Computation hash = dlHasher.compute(dlInputs);
            if (stepUpToken == null || stepUpToken.isBlank()) {
                ScaInitiator.InitiateResult init = scaInitiator.start(dlInputs, false);
                publishAudit("mandate.update.sca.initiated", m, Map.of(
                        "scaSessionId", init.scaSessionId().toString(),
                        "newMaxAmount", newAmount.toPlainString()));
                return new UpdateOutcome.PendingSca(new MandatePendingSca(
                        mandateId, init.scaSessionId(), init.dlHash(),
                        init.exempt(), init.stepUpToken(),
                        init.factorType() == null ? Map.of() : Map.of("type", init.factorType())));
            }
            verifyStepUp(stepUpToken, hash.hex());
            // Last-debit warning: amount lower than the most recent settled debit.
            executions.findByMandateIdOrderByAttemptedAtDesc(mandateId, PageRequest.of(0, 1))
                    .stream()
                    .findFirst()
                    .filter(e -> "SETTLED".equals(e.getStatus()))
                    .filter(e -> newAmount.compareTo(e.getAmount()) < 0)
                    .ifPresent(e -> warnings.add(new MandateDetail.Warning(
                            "MAX_BELOW_LAST_DEBIT",
                            "New max amount is below the last settled debit ("
                                    + e.getAmount().toPlainString() + " "
                                    + e.getCurrency() + ")")));
            m.setMaxAmount(newAmount);
        }

        if (statusChange) {
            switch (req.status()) {
                case "ACTIVE" -> {
                    if (m.getStatus() != Mandate.MandateStatus.PAUSED
                            && m.getStatus() != Mandate.MandateStatus.ACTIVE) {
                        throw CustomerProblemException.invalidInput(
                                "Cannot transition from " + m.getStatus() + " to ACTIVE");
                    }
                    if (m.getStatus() == Mandate.MandateStatus.PAUSED) m.resume();
                }
                case "PAUSED" -> {
                    if (m.getStatus() != Mandate.MandateStatus.ACTIVE
                            && m.getStatus() != Mandate.MandateStatus.PAUSED) {
                        throw CustomerProblemException.invalidInput(
                                "Cannot transition from " + m.getStatus() + " to PAUSED");
                    }
                    m.pause(req.resumeAt());
                }
                default -> throw CustomerProblemException.invalidInput(
                        "Status transition only allows ACTIVE / PAUSED; use DELETE for revoke");
            }
        }

        if (notifyChange) {
            m.setNotifyBeforeDebit(req.notifyBeforeDebit());
        }

        mandates.save(m);
        publishAudit("mandate.updated", m, Map.of(
                "amountChanged", amountChange,
                "statusChanged", statusChange,
                "notifyChanged", notifyChange));

        return new UpdateOutcome.Granted(toDetail(m, warnings));
    }

    public sealed interface UpdateOutcome
            permits UpdateOutcome.Granted, UpdateOutcome.PendingSca {
        record Granted(MandateDetail detail) implements UpdateOutcome { }
        record PendingSca(MandatePendingSca pending) implements UpdateOutcome { }
    }

    // -----------------------------------------------------------------
    // Revoke (UC-G09 §6.5)
    // -----------------------------------------------------------------

    @Transactional
    public MandateDetail revoke(String mandateId) {
        Mandate m = requireOwn(mandateId);
        if (executions.countByMandateIdAndStatus(mandateId, "PENDING") > 0) {
            throw CustomerProblemException.pendingExecution(
                    "Mandate " + mandateId + " has a debit in-flight");
        }
        if (m.getStatus() == Mandate.MandateStatus.REVOKED) {
            return toDetail(m, List.of());
        }
        m.revoke();
        mandates.save(m);
        publishAudit("mandate.revoked", m, Map.of());
        return toDetail(m, List.of());
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private Mandate requireOwn(String mandateId) {
        return mandates.findByMandateIdAndCustomerUserId(mandateId, caller.requireUserId())
                .orElseThrow(() -> CustomerProblemException.notFound("Mandate " + mandateId));
    }

    private MandateFrequency resolveFrequency(Merchant merchant, String requested) {
        var accepted = merchant.getAcceptedFrequencies();
        if (requested != null && !requested.isBlank()) {
            try {
                MandateFrequency f = MandateFrequency.valueOf(requested);
                if (!accepted.contains(f)) {
                    throw CustomerProblemException.invalidInput(
                            "Merchant does not accept frequency " + requested);
                }
                return f;
            } catch (IllegalArgumentException e) {
                throw CustomerProblemException.invalidInput("Unknown frequency: " + requested);
            }
        }
        if (accepted.size() == 1) return accepted.get(0);
        throw CustomerProblemException.invalidInput(
                "Merchant accepts multiple frequencies; specify one of " + accepted);
    }

    private void validateAmount(String raw) {
        BigDecimal v = parseAmountOrThrow(raw);
        if (v.signum() <= 0) {
            throw CustomerProblemException.invalidInput("maxAmount must be > 0");
        }
    }

    private BigDecimal parseAmountOrThrow(String raw) {
        if (raw == null || raw.isBlank()) {
            throw CustomerProblemException.invalidInput("maxAmount required");
        }
        try { return new BigDecimal(raw); } catch (Exception e) {
            throw CustomerProblemException.invalidInput("Malformed amount: " + raw);
        }
    }

    private void validateStartDate(LocalDate startDate, LocalDate endDate) {
        if (startDate == null) {
            throw CustomerProblemException.invalidInput("startDate required");
        }
        LocalDate today = LocalDate.now();
        if (startDate.isBefore(today)) {
            throw CustomerProblemException.invalidInput("startDate must be ≥ today");
        }
        if (startDate.isAfter(today.plusMonths(6))) {
            throw CustomerProblemException.invalidInput("startDate must be ≤ today + 6 months");
        }
        if (endDate != null && !endDate.isAfter(startDate)) {
            throw CustomerProblemException.invalidInput("endDate must be strictly after startDate");
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

    private void publishAudit(String type, Mandate mandate, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("mandateId", mandate.getMandateId());
        payload.put("merchantId", mandate.getMerchantId());
        audit.publish(CustomerAuditEvent.builder(type)
                .actor(caller.currentSubject())
                .customerUserId(caller.currentUserId().orElse(null))
                .resourceType("mandate")
                .resourceId(mandate.getMandateId())
                .summary(type + " for mandate " + mandate.getMandateId())
                .payload(payload)
                .build());
    }

    private MandateSummary toSummary(Mandate m) {
        Merchant merchant = merchants.findById(m.getMerchantId()).orElse(null);
        Instant lastSettled = executions.findByMandateIdOrderByAttemptedAtDesc(
                m.getMandateId(), PageRequest.of(0, 1))
                .stream().findFirst().map(MandateExecution::getSettledAt).orElse(null);
        return new MandateSummary(
                m.getMandateId(),
                m.getMerchantId(),
                merchant == null ? m.getMerchantId() : merchant.getDisplayName(),
                merchant == null ? null : merchant.getLogoUrl(),
                m.getDebtorAccountId(),
                m.getMaxAmount() == null ? null : m.getMaxAmount().toPlainString(),
                m.getCurrency(),
                m.getFrequency() == null ? null : m.getFrequency().name(),
                m.getStatus().name(),
                m.getStartDate(),
                m.getEndDate(),
                lastSettled,
                null,
                m.getResumeAt());
    }

    private MandateDetail toDetail(Mandate m, List<MandateDetail.Warning> warnings) {
        Merchant merchant = merchants.findById(m.getMerchantId()).orElse(null);
        return new MandateDetail(
                toSummary(m),
                merchant == null ? null : merchant.getAvtaleId(),
                m.isNotifyBeforeDebit(),
                m.getCreatedAt(),
                m.getUpdatedAt(),
                m.getRevokedAt(),
                warnings.isEmpty() ? null : warnings);
    }

    private MandateExecutionDto toExecutionDto(MandateExecution e) {
        return new MandateExecutionDto(
                e.getExecutionId(), e.getMandateId(),
                e.getAmount().toPlainString(), e.getCurrency(),
                e.getStatus(), e.getAttemptedAt(), e.getSettledAt(),
                e.getTransactionId(), e.getRejectionReason(), e.getInvoiceReference());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
