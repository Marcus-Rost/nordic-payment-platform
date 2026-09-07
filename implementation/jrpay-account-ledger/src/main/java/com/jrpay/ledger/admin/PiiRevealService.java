package com.jrpay.ledger.admin;

import com.jrpay.ledger.admin.audit.AdminAuditEvent;
import com.jrpay.ledger.admin.audit.AdminAuditPublisher;
import com.jrpay.ledger.admin.dto.RevealPiiAck;
import com.jrpay.ledger.admin.dto.RevealPiiExtendRequest;
import com.jrpay.ledger.admin.dto.RevealPiiPendingApproval;
import com.jrpay.ledger.admin.dto.RevealPiiRequest;
import com.jrpay.ledger.admin.sca.DynamicLinkingHash;
import com.jrpay.ledger.config.AuthenticatedUser;
import com.jrpay.ledger.domain.AdminAction;
import com.jrpay.ledger.domain.PiiField;
import com.jrpay.ledger.projection.AccountEntity;
import com.jrpay.ledger.projection.AccountRepository;
import com.jrpay.ledger.projection.PiiPendingApprovalEntity;
import com.jrpay.ledger.projection.PiiPendingApprovalRepository;
import com.jrpay.ledger.projection.PiiRevealEntity;
import com.jrpay.ledger.projection.PiiRevealRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements UC-G36 PII reveal: rate-limited, second-approval-on-overflow,
 * 60-second TTL with up to three 60-second extensions, full audit on every
 * step. The fnr field is gated to {@code accounts:admin:write} (Phase 1
 * FS.10 — FRAUD_ANALYST cannot reveal fnr).
 */
@Service
public class PiiRevealService {

    private static final Logger log = LoggerFactory.getLogger(PiiRevealService.class);
    private static final SecureRandom RNG = new SecureRandom();

    private final AccountRepository accounts;
    private final PiiRevealRepository reveals;
    private final PiiPendingApprovalRepository approvals;
    private final AdminAuditPublisher audit;
    private final AdminAccountService adminAccounts;
    private final AuthenticatedUser caller;

    private final Duration baseTtl;
    private final Duration extensionTtl;
    private final int rateLimitMaxPerWindow;
    private final Duration rateLimitWindow;
    private final Duration approvalTtl;

    public PiiRevealService(AccountRepository accounts,
                            PiiRevealRepository reveals,
                            PiiPendingApprovalRepository approvals,
                            AdminAuditPublisher audit,
                            AdminAccountService adminAccounts,
                            AuthenticatedUser caller,
                            @Value("${jrpay.ledger.pii.base-ttl:PT60S}") Duration baseTtl,
                            @Value("${jrpay.ledger.pii.extension-ttl:PT60S}") Duration extensionTtl,
                            @Value("${jrpay.ledger.pii.rate-limit-max:5}") int rateLimitMax,
                            @Value("${jrpay.ledger.pii.rate-limit-window:PT1H}") Duration rateLimitWindow,
                            @Value("${jrpay.ledger.pii.approval-ttl:PT15M}") Duration approvalTtl) {
        this.accounts = accounts;
        this.reveals = reveals;
        this.approvals = approvals;
        this.audit = audit;
        this.adminAccounts = adminAccounts;
        this.caller = caller;
        this.baseTtl = baseTtl;
        this.extensionTtl = extensionTtl;
        this.rateLimitMaxPerWindow = rateLimitMax;
        this.rateLimitWindow = rateLimitWindow;
        this.approvalTtl = approvalTtl;
    }

    public sealed interface RevealOutcome
            permits RevealOutcome.Granted, RevealOutcome.PendingApproval {
        record Granted(RevealPiiAck ack) implements RevealOutcome { }
        record PendingApproval(RevealPiiPendingApproval response) implements RevealOutcome { }
    }

    @Transactional
    public RevealOutcome reveal(String accountId, RevealPiiRequest req, String stepUpToken) {
        validateRequest(req);
        AccountEntity acc = requireAccount(accountId);
        UUID operatorId = caller.requireUserId();

        // Field-scope gate (Phase 1 FS.10).
        if (req.field() == PiiField.fodselsnummer && !caller.hasScope("accounts:admin:write")) {
            throw AdminProblemException.forbidden(
                    "fodselsnummer reveal requires accounts:admin:write (COMPLIANCE_OFFICER+)");
        }

        // Step-up SCA verification.
        adminAccounts.verifyStepUp(stepUpToken, AdminAction.REVEAL_PII, "account", accountId,
                req.field().name(), req.justificationNotes());

        Instant now = Instant.now();
        Instant windowStart = now.minus(rateLimitWindow);
        long recent = reveals.countRecent(operatorId, accountId, windowStart);
        boolean rateLimited = recent >= rateLimitMaxPerWindow;
        if (rateLimited) {
            String approvalRef = newApprovalRef();
            Instant expiry = now.plus(approvalTtl);
            String notesHash = DynamicLinkingHash.sha256HexNfc(req.justificationNotes());
            String summary = clampSummary(req.justificationNotes());
            approvals.save(new PiiPendingApprovalEntity(
                    approvalRef, accountId, operatorId, req.field(),
                    notesHash, summary, now, expiry));
            publishRevealAudit("audit.pii-revealed.rate-limited", accountId, acc, operatorId,
                    req.field(), summary, AdminAuditEvent.Outcome.REJECTED,
                    Map.of("reason", "RATE_LIMIT_EXCEEDED",
                            "approvalRef", approvalRef,
                            "rateLimitWindowResetAt", windowStart.plus(rateLimitWindow).toString()));
            return new RevealOutcome.PendingApproval(new RevealPiiPendingApproval(
                    true, approvalRef, expiry, windowStart.plus(rateLimitWindow)));
        }

        return new RevealOutcome.Granted(persistAndAck(acc, operatorId, req.field(),
                req.justificationNotes(), now, null, null));
    }

    @Transactional
    public RevealPiiAck approve(String accountId, String approvalRef, String stepUpToken) {
        if (!caller.hasAnyRole("SUPERVISOR", "ADMIN")) {
            throw AdminProblemException.forbidden("Approval requires SUPERVISOR role");
        }
        UUID supervisorId = caller.requireUserId();
        PiiPendingApprovalEntity pending = approvals.findByApprovalRef(approvalRef)
                .orElseThrow(() -> AdminProblemException.notFound("Unknown approvalRef"));
        Instant now = Instant.now();
        if (!pending.isLive(now)) {
            throw AdminProblemException.notFound("Approval expired or already consumed");
        }
        if (!pending.getAccountId().equals(accountId)) {
            throw AdminProblemException.validation(
                    "approvalRef bound to a different account");
        }

        adminAccounts.verifyStepUp(stepUpToken, AdminAction.REVEAL_PII, "account", accountId,
                pending.getField().name(), pending.getNotesSummary());

        AccountEntity acc = requireAccount(accountId);
        pending.approve(supervisorId, now);
        pending.consume(now);
        approvals.save(pending);

        publishRevealAudit("audit.pii-revealed.approved", accountId, acc, pending.getOperatorId(),
                pending.getField(), pending.getNotesSummary(), AdminAuditEvent.Outcome.SUCCESS,
                Map.of("approvalRef", approvalRef,
                        "approvedBy", supervisorId.toString()));

        return persistAndAck(acc, pending.getOperatorId(), pending.getField(),
                pending.getNotesSummary(), now, pending.getApprovalRef(), supervisorId);
    }

    @Transactional
    public Map<String, Object> extend(String accountId, RevealPiiExtendRequest req,
                                      String stepUpToken) {
        UUID operatorId = caller.requireUserId();
        PiiRevealEntity reveal = reveals.findById(req.revealId())
                .orElseThrow(() -> new AdminProblemException(HttpStatus.CONFLICT,
                        "urn:problem:no-active-reveal", "No active reveal", "Reveal not found"));
        if (!reveal.getOperatorId().equals(operatorId) || !reveal.getAccountId().equals(accountId)) {
            throw new AdminProblemException(HttpStatus.CONFLICT,
                    "urn:problem:no-active-reveal", "No active reveal",
                    "Reveal does not belong to this operator/account");
        }
        Instant now = Instant.now();
        if (!now.isBefore(reveal.getExpiresAt())) {
            throw new AdminProblemException(HttpStatus.CONFLICT,
                    "urn:problem:no-active-reveal", "No active reveal",
                    "Reveal already expired");
        }
        if (reveal.getExtensionCount() >= 3) {
            throw new AdminProblemException(HttpStatus.CONFLICT,
                    "urn:problem:reveal-extension-cap",
                    "Extension cap reached",
                    "Maximum 3 extensions per reveal (4×60s total)");
        }

        AccountEntity acc = requireAccount(accountId);
        adminAccounts.verifyStepUp(stepUpToken, AdminAction.REVEAL_PII, "account", accountId,
                reveal.getField().name(), reveal.getJustificationNotesHash());

        Instant newExpiry = reveal.getExpiresAt().plus(extensionTtl);
        reveal.extend(newExpiry);
        reveals.save(reveal);
        publishRevealAudit("audit.pii-revealed.extended", accountId, acc, operatorId,
                reveal.getField(), reveal.getNotesSummary(), AdminAuditEvent.Outcome.SUCCESS,
                Map.of("extensionCount", reveal.getExtensionCount(),
                        "newExpiry", newExpiry.toString()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ttlSeconds", extensionTtl.toSeconds());
        body.put("extensionsRemaining", 3 - reveal.getExtensionCount());
        return body;
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private RevealPiiAck persistAndAck(AccountEntity acc, UUID operatorId, PiiField field,
                                       String notes, Instant now, String approvalRef,
                                       UUID supervisorId) {
        Instant expiry = now.plus(baseTtl);
        UUID revealId = UUID.randomUUID();
        String notesHash = DynamicLinkingHash.sha256HexNfc(notes);
        String summary = clampSummary(notes);
        UUID customerUserId = parseUuid(acc.getCustomerId());

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("field", field.name());
        auditPayload.put("revealId", revealId.toString());
        auditPayload.put("ttlSeconds", baseTtl.toSeconds());
        auditPayload.put("notesSummary", summary);
        if (approvalRef != null) auditPayload.put("approvalRef", approvalRef);
        if (supervisorId != null) auditPayload.put("supervisorApprovalBy", supervisorId.toString());

        String auditEventId = audit.publish(AdminAuditEvent.builder("audit.pii-revealed")
                .actor("operator:" + operatorId)
                .operatorUserId(operatorId)
                .customerUserId(customerUserId)
                .resourceType("account")
                .resourceId(acc.getAccountId())
                .severity(AdminAuditEvent.Severity.WARN)
                .summary("PII " + field.name() + " revealed for account "
                        + acc.getAccountId() + " by operator")
                .payload(auditPayload)
                .build());

        PiiRevealEntity entity = new PiiRevealEntity(
                revealId, acc.getAccountId(), operatorId, customerUserId,
                field, now, expiry, notesHash, summary, auditEventId,
                null, approvalRef);
        reveals.save(entity);

        return new RevealPiiAck(field, resolveValue(acc, field),
                baseTtl.toSeconds(), 3, now, revealId, auditEventId);
    }

    private void publishRevealAudit(String type, String accountId, AccountEntity acc, UUID operatorId,
                                    PiiField field, String summary, AdminAuditEvent.Outcome outcome,
                                    Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("field", field.name());
        payload.put("notesSummary", summary);
        audit.publish(AdminAuditEvent.builder(type)
                .actor("operator:" + operatorId)
                .operatorUserId(operatorId)
                .customerUserId(parseUuid(acc.getCustomerId()))
                .resourceType("account")
                .resourceId(accountId)
                .outcome(outcome)
                .severity(outcome == AdminAuditEvent.Outcome.SUCCESS
                        ? AdminAuditEvent.Severity.WARN
                        : AdminAuditEvent.Severity.ERROR)
                .summary("PII reveal " + type + " for account " + accountId)
                .payload(payload)
                .build());
    }

    private static String resolveValue(AccountEntity acc, PiiField field) {
        return switch (field) {
            case holderName -> acc.getHolderName();
            case address -> acc.getHolderAddress();
            case fodselsnummer -> acc.getFodselsnummer();
        };
    }

    private AccountEntity requireAccount(String accountId) {
        return accounts.findByAccountId(accountId)
                .orElseThrow(() -> AdminProblemException.notFound("Account " + accountId));
    }

    private static void validateRequest(RevealPiiRequest req) {
        if (req.confirmedBusinessNeed() == null || !req.confirmedBusinessNeed()) {
            throw AdminProblemException.validation("confirmedBusinessNeed must be true");
        }
        if (req.justificationNotes() == null || req.justificationNotes().length() < 20) {
            throw AdminProblemException.validation("justificationNotes too short (min 20)");
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    private static String clampSummary(String notes) {
        if (notes == null) return "";
        String collapsed = notes.replaceAll("\\s+", " ").trim();
        return collapsed.length() > 140 ? collapsed.substring(0, 137) + "..." : collapsed;
    }

    private static String newApprovalRef() {
        byte[] raw = new byte[24];
        RNG.nextBytes(raw);
        return "ar_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
