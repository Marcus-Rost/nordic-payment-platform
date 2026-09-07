package com.jrpay.psd2.customer.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.psd2.customer.CustomerProblemException;
import com.jrpay.psd2.customer.IbanValidator;
import com.jrpay.psd2.customer.audit.CustomerAuditEvent;
import com.jrpay.psd2.customer.audit.CustomerAuditPublisher;
import com.jrpay.psd2.customer.config.AuthenticatedUser;
import com.jrpay.psd2.customer.domain.CustomerAccountEntity;
import com.jrpay.psd2.customer.domain.CustomerAccountRepository;
import com.jrpay.psd2.customer.domain.CustomerPaymentEntity;
import com.jrpay.psd2.customer.domain.CustomerPaymentRepository;
import com.jrpay.psd2.customer.payments.dto.PaymentAck;
import com.jrpay.psd2.customer.payments.dto.PaymentPage;
import com.jrpay.psd2.customer.payments.dto.PaymentPendingSca;
import com.jrpay.psd2.customer.payments.dto.PaymentRecordDto;
import com.jrpay.psd2.customer.payments.dto.PaymentRequestDto;
import com.jrpay.psd2.customer.sca.DynamicLinkingHash;
import com.jrpay.psd2.customer.sca.ScaInitiator;
import com.jrpay.psd2.customer.sca.StepUpValidator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
public class CustomerPaymentService {

    private final CustomerPaymentRepository payments;
    private final CustomerAccountRepository accounts;
    private final ScaInitiator scaInitiator;
    private final StepUpValidator stepUpValidator;
    private final DynamicLinkingHash dlHasher;
    private final CustomerAuditPublisher audit;
    private final AuthenticatedUser caller;
    private final ObjectMapper mapper;
    private final long duplicateWindowSeconds;
    private final long refreshMinIntervalSeconds;

    public CustomerPaymentService(CustomerPaymentRepository payments,
                                  CustomerAccountRepository accounts,
                                  ScaInitiator scaInitiator,
                                  StepUpValidator stepUpValidator,
                                  DynamicLinkingHash dlHasher,
                                  CustomerAuditPublisher audit,
                                  AuthenticatedUser caller,
                                  ObjectMapper mapper,
                                  @Value("${jrpay.psd2.payments.duplicate-window-seconds:120}") long dupWindow,
                                  @Value("${jrpay.psd2.payments.refresh-min-interval-seconds:900}") long refreshMin) {
        this.payments = payments;
        this.accounts = accounts;
        this.scaInitiator = scaInitiator;
        this.stepUpValidator = stepUpValidator;
        this.dlHasher = dlHasher;
        this.audit = audit;
        this.caller = caller;
        this.mapper = mapper;
        this.duplicateWindowSeconds = dupWindow;
        this.refreshMinIntervalSeconds = refreshMin;
    }

    public sealed interface InitiateOutcome
            permits InitiateOutcome.Granted, InitiateOutcome.PendingSca {
        record Granted(PaymentAck ack) implements InitiateOutcome { }
        record PendingSca(PaymentPendingSca pending) implements InitiateOutcome { }
    }

    @Transactional
    public InitiateOutcome initiate(PaymentRequestDto body, String idempotencyKey,
                                    String stepUpToken) {
        UUID uid = caller.requireUserId();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw CustomerProblemException.validation("Idempotency-Key header required");
        }
        validate(body);
        String creditorIban = IbanValidator.compact(body.creditorIban());
        BigDecimal amount = parseAmount(body.amount());
        String executionMode = body.executionMode() == null ? "INSTANT" : body.executionMode();

        CustomerAccountEntity debtor = accounts
                .findByAccountIdAndCustomerUserId(body.debtorAccountId(), uid)
                .orElseThrow(() -> CustomerProblemException.notFound(
                        "Debtor account " + body.debtorAccountId()));
        guardAccountState(debtor);

        String requestHash = DynamicLinkingHash.sha256HexUtf8(idempotencyKey + ":"
                + uid + ":" + creditorIban + ":" + amount.toPlainString() + ":"
                + body.currency() + ":" + executionMode);

        // Idempotency replay.
        var prior = payments.findByIdempotencyKey(idempotencyKey);
        if (prior.isPresent()) {
            CustomerPaymentEntity existing = prior.get();
            if (!existing.getCustomerUserId().equals(uid)) {
                throw CustomerProblemException.idempotencyKeyConflict(
                        "Idempotency-Key bound to a different customer");
            }
            if (!requestHash.equals(existing.getRequestHash())) {
                throw CustomerProblemException.idempotencyKeyConflict(
                        "Replayed Idempotency-Key with a different body");
            }
            return resumeExisting(existing, stepUpToken, executionMode);
        }

        // Probable-duplicate detection (separate from idempotency replay).
        Instant since = Instant.now().minus(duplicateWindowSeconds, ChronoUnit.SECONDS);
        List<CustomerPaymentEntity> recent = payments.findProbableDuplicates(
                uid, creditorIban, amount, body.currency(), since);
        if (!recent.isEmpty()) {
            CustomerPaymentEntity orig = recent.get(0);
            Map<String, Object> originalPayload = Map.of(
                    "paymentId", orig.getPaymentId(),
                    "creditorIban", IbanValidator.mask(orig.getCreditorIban()),
                    "amount", orig.getAmount().toPlainString(),
                    "currency", orig.getCurrency(),
                    "createdAt", orig.getCreatedAt().toString(),
                    "status", orig.getStatus());
            throw CustomerProblemException.duplicateSuspected(
                    "Recent similar payment exists; confirm before retrying",
                    originalPayload);
        }

        String paymentId = UUID.randomUUID().toString();
        CustomerPaymentEntity entity = new CustomerPaymentEntity(
                paymentId, uid, body.debtorAccountId(),
                creditorIban, body.creditorName(), amount, body.currency(),
                body.reference(), body.description(), executionMode,
                idempotencyKey, requestHash);
        entity.setVopResult(body.vopResult());
        entity.setVopPayerOverride(body.vopPayerOverride());
        payments.save(entity);

        Map<String, Object> dlInputs = dlHasher.paymentInputs(
                paymentId, amount.toPlainString(), body.currency(),
                creditorIban, body.creditorName(), executionMode);
        DynamicLinkingHash.Computation hash = dlHasher.compute(dlInputs);
        entity.setDlHash(hash.hex());

        if (stepUpToken == null || stepUpToken.isBlank()) {
            ScaInitiator.InitiateResult init = scaInitiator.start(dlInputs, false);
            entity.setScaSessionId(init.scaSessionId());
            payments.save(entity);
            publishAudit("payment.initiated.pending-sca", entity, Map.of(
                    "scaSessionId", init.scaSessionId().toString()));
            return new InitiateOutcome.PendingSca(new PaymentPendingSca(
                    paymentId, init.scaSessionId(), init.dlHash(),
                    init.exempt(), init.stepUpToken(),
                    init.factorType() == null ? Map.of() : Map.of("type", init.factorType())));
        }

        verifyStepUp(stepUpToken, hash.hex());
        return finalizeSubmit(entity, executionMode);
    }

    @Transactional
    public PaymentRecordDto getPayment(String paymentId) {
        CustomerPaymentEntity p = requireOwn(paymentId);
        return toDto(p);
    }

    @Transactional(readOnly = true)
    public PaymentPage list(int page, int size) {
        UUID uid = caller.requireUserId();
        Page<CustomerPaymentEntity> rows = payments.findByCustomerUserIdOrderByCreatedAtDesc(
                uid, PageRequest.of(page, size));
        List<PaymentRecordDto> items = rows.getContent().stream().map(this::toDto).toList();
        return new PaymentPage(items, new PaymentPage.Page(
                rows.getNumber(), rows.getSize(),
                rows.getTotalElements(), rows.getTotalPages()));
    }

    @Transactional
    public void cancelPreSca(String paymentId) {
        CustomerPaymentEntity p = requireOwn(paymentId);
        if (!"PENDING_SCA".equals(p.getStatus())) {
            throw CustomerProblemException.paymentNotCancellable(
                    "Payment " + paymentId + " is not in PENDING_SCA");
        }
        p.setStatus("CANCELLED");
        p.setCancelledAt(Instant.now());
        payments.save(p);
        publishAudit("payment.cancelled", p, Map.of("reason", "PRE_SCA_CANCEL"));
    }

    @Transactional
    public PaymentAck submit(String paymentId, String stepUpToken) {
        CustomerPaymentEntity p = requireOwn(paymentId);
        if (!"PENDING_SCA".equals(p.getStatus())) {
            throw CustomerProblemException.paymentNotCancellable(
                    "Payment " + paymentId + " is not in PENDING_SCA");
        }
        Map<String, Object> dlInputs = dlHasher.paymentInputs(
                p.getPaymentId(), p.getAmount().toPlainString(), p.getCurrency(),
                p.getCreditorIban(), p.getCreditorName(), p.getExecutionMode());
        DynamicLinkingHash.Computation hash = dlHasher.compute(dlInputs);
        verifyStepUp(stepUpToken, hash.hex());
        InitiateOutcome.Granted g = (InitiateOutcome.Granted)
                finalizeSubmit(p, p.getExecutionMode());
        return g.ack();
    }

    @Transactional
    public PaymentRecordDto refreshStatus(String paymentId) {
        CustomerPaymentEntity p = requireOwn(paymentId);
        Instant now = Instant.now();
        if (p.getLastStatusRefreshedAt() != null) {
            Instant cooldown = p.getLastStatusRefreshedAt().plusSeconds(refreshMinIntervalSeconds);
            if (now.isBefore(cooldown)) {
                throw CustomerProblemException.rateLimited(
                        "Status refresh allowed once per " + refreshMinIntervalSeconds + "s");
            }
        }
        p.setLastStatusRefreshedAt(now);
        payments.save(p);
        publishAudit("payment.status.refreshed", p, Map.of());
        return toDto(p);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private InitiateOutcome resumeExisting(CustomerPaymentEntity existing, String stepUpToken,
                                           String executionMode) {
        switch (existing.getStatus()) {
            case "PENDING_SCA":
                if (stepUpToken == null || stepUpToken.isBlank()) {
                    return new InitiateOutcome.PendingSca(new PaymentPendingSca(
                            existing.getPaymentId(), existing.getScaSessionId(),
                            existing.getDlHash(), false, null, Map.of()));
                }
                Map<String, Object> dlInputs = dlHasher.paymentInputs(
                        existing.getPaymentId(), existing.getAmount().toPlainString(),
                        existing.getCurrency(), existing.getCreditorIban(),
                        existing.getCreditorName(), executionMode);
                verifyStepUp(stepUpToken, dlHasher.compute(dlInputs).hex());
                return finalizeSubmit(existing, executionMode);
            default:
                return new InitiateOutcome.Granted(toAck(existing));
        }
    }

    private InitiateOutcome.Granted finalizeSubmit(CustomerPaymentEntity p, String executionMode) {
        // For Phase 2K, we transition to PENDING_EXECUTION and return synchronously.
        // The actual A2 clearing pipeline integration is a Phase 2K-end stretch — for
        // now the customer-payments table records the submit and downstream listeners
        // (Phase 2N smoke) drive the SETTLED/REJECTED transitions.
        p.setStatus("PENDING_EXECUTION");
        payments.save(p);
        publishAudit("payment.submitted", p, Map.of(
                "executionMode", executionMode));
        return new InitiateOutcome.Granted(toAck(p));
    }

    private CustomerPaymentEntity requireOwn(String paymentId) {
        return payments.findByPaymentIdAndCustomerUserId(paymentId, caller.requireUserId())
                .orElseThrow(() -> CustomerProblemException.notFound("Payment " + paymentId));
    }

    private void guardAccountState(CustomerAccountEntity acc) {
        if (acc.getStatus() == CustomerAccountEntity.Status.FROZEN) {
            throw CustomerProblemException.accountFrozen("Debtor account is frozen");
        }
        if (acc.getStatus() == CustomerAccountEntity.Status.CLOSED) {
            throw CustomerProblemException.accountClosed("Debtor account is closed");
        }
    }

    private void validate(PaymentRequestDto body) {
        if (!IbanValidator.isValid(body.creditorIban())) {
            throw CustomerProblemException.invalidIban("creditorIban failed mod-97 check");
        }
        if (!body.currency().matches("^[A-Z]{3}$")) {
            throw CustomerProblemException.unsupportedCurrency("currency must be ISO 4217 alpha-3");
        }
        BigDecimal amount = parseAmount(body.amount());
        if (amount.signum() <= 0) {
            throw CustomerProblemException.invalidInput("amount must be > 0");
        }
        if (body.creditorName() == null || body.creditorName().isBlank()) {
            throw CustomerProblemException.invalidInput("creditorName required");
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

    private void publishAudit(String type, CustomerPaymentEntity p, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("paymentId", p.getPaymentId());
        payload.put("amount", p.getAmount().toPlainString());
        payload.put("currency", p.getCurrency());
        payload.put("creditorIbanMasked", IbanValidator.mask(p.getCreditorIban()));
        audit.publish(CustomerAuditEvent.builder(type)
                .actor(caller.currentSubject())
                .customerUserId(caller.currentUserId().orElse(null))
                .resourceType("payment")
                .resourceId(p.getPaymentId())
                .summary(type + " for payment " + p.getPaymentId())
                .payload(payload)
                .build());
    }

    private PaymentAck toAck(CustomerPaymentEntity p) {
        String clearingPath = "INSTANT".equals(p.getExecutionMode()) ? "INSTANT"
                : "STANDARD".equals(p.getExecutionMode()) ? "NICS" : "BOOK_TRANSFER";
        return new PaymentAck(
                p.getPaymentId(), p.getStatus(), clearingPath,
                p.getSettledAt(), null);
    }

    private PaymentRecordDto toDto(CustomerPaymentEntity p) {
        return new PaymentRecordDto(
                p.getPaymentId(), p.getDebtorAccountId(),
                p.getCreditorIban(), p.getCreditorName(),
                p.getAmount().toPlainString(), p.getCurrency(),
                p.getReference(), p.getDescription(), p.getExecutionMode(),
                p.getStatus(), p.getRejectionReason(),
                p.getCreatedAt(), p.getUpdatedAt(),
                p.getSettledAt(), p.getCancelledAt());
    }
}
