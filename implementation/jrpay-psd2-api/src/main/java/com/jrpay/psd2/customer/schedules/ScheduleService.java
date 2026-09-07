package com.jrpay.psd2.customer.schedules;

import com.jrpay.psd2.customer.CustomerProblemException;
import com.jrpay.psd2.customer.IbanValidator;
import com.jrpay.psd2.customer.audit.CustomerAuditEvent;
import com.jrpay.psd2.customer.audit.CustomerAuditPublisher;
import com.jrpay.psd2.customer.config.AuthenticatedUser;
import com.jrpay.psd2.customer.domain.PaymentScheduleEntity;
import com.jrpay.psd2.customer.domain.PaymentScheduleRepository;
import com.jrpay.psd2.customer.payments.dto.PaymentPendingSca;
import com.jrpay.psd2.customer.schedules.dto.ScheduleDto;
import com.jrpay.psd2.customer.schedules.dto.SchedulePage;
import com.jrpay.psd2.customer.schedules.dto.ScheduleRequestDto;
import com.jrpay.psd2.customer.sca.DynamicLinkingHash;
import com.jrpay.psd2.customer.sca.ScaInitiator;
import com.jrpay.psd2.customer.sca.StepUpValidator;
import java.math.BigDecimal;
import java.time.Instant;
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
public class ScheduleService {

    private final PaymentScheduleRepository schedules;
    private final ScaInitiator scaInitiator;
    private final StepUpValidator stepUpValidator;
    private final DynamicLinkingHash dlHasher;
    private final CustomerAuditPublisher audit;
    private final AuthenticatedUser caller;

    public ScheduleService(PaymentScheduleRepository schedules,
                           ScaInitiator scaInitiator,
                           StepUpValidator stepUpValidator,
                           DynamicLinkingHash dlHasher,
                           CustomerAuditPublisher audit,
                           AuthenticatedUser caller) {
        this.schedules = schedules;
        this.scaInitiator = scaInitiator;
        this.stepUpValidator = stepUpValidator;
        this.dlHasher = dlHasher;
        this.audit = audit;
        this.caller = caller;
    }

    public sealed interface CreateOutcome
            permits CreateOutcome.Granted, CreateOutcome.PendingSca {
        record Granted(ScheduleDto schedule) implements CreateOutcome { }
        record PendingSca(PaymentPendingSca pending) implements CreateOutcome { }
    }

    @Transactional
    public CreateOutcome create(ScheduleRequestDto body, String idempotencyKey, String stepUpToken) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw CustomerProblemException.validation("Idempotency-Key header required");
        }
        UUID uid = caller.requireUserId();
        validate(body);
        String creditorIban = IbanValidator.compact(body.creditorIban());
        BigDecimal amount = parseAmount(body.amount());
        String executionMode = body.executionMode() == null ? "STANDARD" : body.executionMode();
        if (!body.executeAt().isAfter(Instant.now())) {
            throw CustomerProblemException.invalidInput("executeAt must be strictly after now");
        }

        String scheduleId = UUID.randomUUID().toString();
        Map<String, Object> dlInputs = dlHasher.paymentScheduleInputs(
                scheduleId, amount.toPlainString(), body.currency(),
                creditorIban, body.creditorName(),
                body.executeAt().toString(), executionMode);
        DynamicLinkingHash.Computation hash = dlHasher.compute(dlInputs);

        if (stepUpToken == null || stepUpToken.isBlank()) {
            ScaInitiator.InitiateResult init = scaInitiator.start(dlInputs, false);
            PaymentScheduleEntity entity = new PaymentScheduleEntity(
                    scheduleId, uid, body.debtorAccountId(),
                    creditorIban, body.creditorName(), amount, body.currency(),
                    body.reference(), body.executeAt(), executionMode,
                    init.scaSessionId(), init.dlHash(), body.note(), idempotencyKey);
            schedules.save(entity);
            publishAudit("payment.schedule.created.pending-sca", entity, Map.of(
                    "scaSessionId", init.scaSessionId().toString()));
            return new CreateOutcome.PendingSca(new PaymentPendingSca(
                    scheduleId, init.scaSessionId(), init.dlHash(),
                    init.exempt(), init.stepUpToken(),
                    init.factorType() == null ? Map.of() : Map.of("type", init.factorType())));
        }

        verifyStepUp(stepUpToken, hash.hex());
        PaymentScheduleEntity entity = new PaymentScheduleEntity(
                scheduleId, uid, body.debtorAccountId(),
                creditorIban, body.creditorName(), amount, body.currency(),
                body.reference(), body.executeAt(), executionMode,
                null, hash.hex(), body.note(), idempotencyKey);
        entity.activate();
        schedules.save(entity);
        publishAudit("payment.schedule.created", entity, Map.of());
        return new CreateOutcome.Granted(toDto(entity));
    }

    @Transactional(readOnly = true)
    public SchedulePage list(Collection<PaymentScheduleEntity.Status> statuses, int page, int size) {
        UUID uid = caller.requireUserId();
        Collection<PaymentScheduleEntity.Status> effective = (statuses == null || statuses.isEmpty())
                ? List.of(PaymentScheduleEntity.Status.SCHEDULED)
                : statuses;
        Page<PaymentScheduleEntity> rows = schedules.listFor(uid, effective, PageRequest.of(page, size));
        List<ScheduleDto> items = rows.getContent().stream().map(this::toDto).toList();
        return new SchedulePage(items, new SchedulePage.Page(
                rows.getNumber(), rows.getSize(),
                rows.getTotalElements(), rows.getTotalPages()));
    }

    @Transactional(readOnly = true)
    public ScheduleDto get(String scheduleId) {
        return toDto(requireOwn(scheduleId));
    }

    @Transactional
    public ScheduleDto cancel(String scheduleId) {
        PaymentScheduleEntity s = requireOwn(scheduleId);
        if (s.getStatus() == PaymentScheduleEntity.Status.EXECUTED
                || s.getStatus() == PaymentScheduleEntity.Status.CANCELLED) {
            throw CustomerProblemException.paymentNotCancellable(
                    "Schedule " + scheduleId + " is " + s.getStatus());
        }
        s.cancel();
        schedules.save(s);
        publishAudit("payment.schedule.cancelled", s, Map.of());
        return toDto(s);
    }

    private PaymentScheduleEntity requireOwn(String scheduleId) {
        return schedules.findByScheduleIdAndCustomerUserId(scheduleId, caller.requireUserId())
                .orElseThrow(() -> CustomerProblemException.notFound("Schedule " + scheduleId));
    }

    private void validate(ScheduleRequestDto body) {
        if (!IbanValidator.isValid(body.creditorIban())) {
            throw CustomerProblemException.invalidIban("creditorIban failed mod-97 check");
        }
        if (!body.currency().matches("^[A-Z]{3}$")) {
            throw CustomerProblemException.unsupportedCurrency("currency must be ISO 4217 alpha-3");
        }
    }

    private static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            throw CustomerProblemException.invalidInput("amount required");
        }
        try { return new BigDecimal(raw); }
        catch (Exception e) { throw CustomerProblemException.invalidInput("Malformed amount: " + raw); }
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

    private void publishAudit(String type, PaymentScheduleEntity s, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("scheduleId", s.getScheduleId());
        payload.put("amount", s.getAmount().toPlainString());
        payload.put("currency", s.getCurrency());
        payload.put("executeAt", s.getExecuteAt().toString());
        audit.publish(CustomerAuditEvent.builder(type)
                .actor(caller.currentSubject())
                .customerUserId(caller.currentUserId().orElse(null))
                .resourceType("payment-schedule")
                .resourceId(s.getScheduleId())
                .summary(type + " for schedule " + s.getScheduleId())
                .payload(payload)
                .build());
    }

    private ScheduleDto toDto(PaymentScheduleEntity s) {
        return new ScheduleDto(
                s.getScheduleId(), s.getDebtorAccountId(),
                s.getCreditorIban(), s.getCreditorName(),
                s.getAmount().toPlainString(), s.getCurrency(),
                s.getReference(), s.getExecuteAt(), s.getExecutionMode(),
                s.getStatus().name(), s.getExecutedPaymentId(),
                s.getCancelledAt(), s.getExecutedAt(),
                s.getFailureReason(), s.getNote(),
                s.getCreatedAt(), s.getUpdatedAt());
    }
}
