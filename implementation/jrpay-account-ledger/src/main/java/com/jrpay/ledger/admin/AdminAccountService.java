package com.jrpay.ledger.admin;

import com.jrpay.domain.money.Currency;
import com.jrpay.domain.money.MonetaryAmount;
import com.jrpay.ledger.admin.audit.AdminAuditEvent;
import com.jrpay.ledger.admin.audit.AdminAuditPublisher;
import com.jrpay.ledger.admin.dto.AdminAccountDetail;
import com.jrpay.ledger.admin.dto.AdminAccountSummary;
import com.jrpay.ledger.admin.dto.AdminTransactionSummary;
import com.jrpay.ledger.admin.dto.CloseAccountRequest;
import com.jrpay.ledger.admin.dto.CreateAccountRequest;
import com.jrpay.ledger.admin.dto.FreezeRequest;
import com.jrpay.ledger.admin.dto.SearchResult;
import com.jrpay.ledger.admin.dto.UnfreezeRequest;
import com.jrpay.ledger.admin.sca.DynamicLinkingHash;
import com.jrpay.ledger.admin.sca.StepUpValidator;
import com.jrpay.ledger.command.AccountCommandHandler;
import com.jrpay.ledger.config.AuthenticatedUser;
import com.jrpay.ledger.domain.AccountStatus;
import com.jrpay.ledger.domain.AccountType;
import com.jrpay.ledger.domain.AdminAction;
import com.jrpay.ledger.domain.CloseReason;
import com.jrpay.ledger.domain.FreezeReason;
import com.jrpay.ledger.domain.UnfreezeReason;
import com.jrpay.ledger.projection.AccountEntity;
import com.jrpay.ledger.projection.AccountRepository;
import com.jrpay.ledger.projection.AccountTransactionEntity;
import com.jrpay.ledger.projection.AccountTransactionRepository;
import com.jrpay.ledger.projection.ReservationEntity;
import com.jrpay.ledger.projection.ReservationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the admin-accounts.yaml surface (UC-G35 §6) on top of the
 * existing {@link AccountCommandHandler}. This service is the trust
 * boundary: every mutating operation calls {@link StepUpValidator} to
 * verify the SCA proof, runs the business pre-conditions, mutates the
 * projection through the command handler, and publishes an
 * {@code audit.events} record.
 */
@Service
public class AdminAccountService {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountService.class);
    private static final int FUZZY_SEARCH_CAP = 50;

    private final AccountRepository accounts;
    private final AccountCommandHandler commands;
    private final AccountTransactionRepository transactions;
    private final ReservationRepository reservations;
    private final AdminAuditPublisher audit;
    private final StepUpValidator stepUp;
    private final DynamicLinkingHash dlHasher;
    private final AuthenticatedUser caller;

    private final long fnrSearchAcrWindowSeconds;

    public AdminAccountService(AccountRepository accounts,
                               AccountCommandHandler commands,
                               AccountTransactionRepository transactions,
                               ReservationRepository reservations,
                               AdminAuditPublisher audit,
                               StepUpValidator stepUp,
                               DynamicLinkingHash dlHasher,
                               AuthenticatedUser caller,
                               @Value("${jrpay.ledger.admin.fnr-search-acr-window-seconds:300}") long fnrAcrWindow) {
        this.accounts = accounts;
        this.commands = commands;
        this.transactions = transactions;
        this.reservations = reservations;
        this.audit = audit;
        this.stepUp = stepUp;
        this.dlHasher = dlHasher;
        this.caller = caller;
        this.fnrSearchAcrWindowSeconds = fnrAcrWindow;
    }

    // ---------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SearchResult search(String mode, String q, int page, int size) {
        if (q == null || q.length() < 2 || q.length() > 140) {
            throw AdminProblemException.validation("q length must be 2..140");
        }
        return switch (mode) {
            case "name" -> searchByName(q, page, size);
            case "iban" -> searchByIban(q);
            case "fodselsnummer" -> searchByFnr(q);
            default -> throw AdminProblemException.validation("Unknown mode: " + mode);
        };
    }

    private SearchResult searchByName(String q, int page, int size) {
        int capped = Math.min(size, FUZZY_SEARCH_CAP);
        List<AccountEntity> rows = accounts.fuzzySearchByName(
                q, q + "%", PageRequest.of(page, capped));
        List<AdminAccountSummary> items = rows.stream().map(this::toSummary).toList();
        boolean truncated = items.size() == capped;
        publishSearchAudit("name", q, items.size());
        return new SearchResult(items, page, capped, items.size(), truncated);
    }

    private SearchResult searchByIban(String iban) {
        String compact = iban.replace(" ", "").toUpperCase();
        if (!compact.matches("^[A-Z]{2}[0-9A-Z]{13,30}$")) {
            throw AdminProblemException.validation("Malformed IBAN");
        }
        if (!isIbanMod97Valid(compact)) {
            throw AdminProblemException.validation("IBAN failed mod-97 check");
        }
        List<AccountEntity> rows = accounts.findByIban(compact)
                .map(List::of).orElse(List.of());
        publishSearchAudit("iban", compact, rows.size());
        return new SearchResult(rows.stream().map(this::toSummary).toList(),
                0, rows.size(), rows.size(), false);
    }

    private SearchResult searchByFnr(String fnr) {
        if (!fnr.matches("^[0-9]{11}$")) {
            throw AdminProblemException.validation("Fødselsnummer must be 11 digits");
        }
        // ACR check: caller must have presented loa3-admin recently. We use
        // the JWT acr claim to gate; the resource server enforced auth.
        if (!"loa3-admin".equals(caller.currentAcr())) {
            throw AdminProblemException.stepUpRequired(
                    "Fødselsnummer search requires loa3-admin step-up within the last "
                            + fnrSearchAcrWindowSeconds + " seconds");
        }
        List<AccountEntity> rows = accounts.findByFodselsnummer(fnr)
                .map(List::of).orElse(List.of());
        // The full fnr is not logged — only the result count.
        publishSearchAudit("fodselsnummer", "[redacted]", rows.size());
        return new SearchResult(rows.stream().map(this::toSummary).toList(),
                0, rows.size(), rows.size(), false);
    }

    private void publishSearchAudit(String mode, String qSummary, int count) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", mode);
        payload.put("query", qSummary);
        payload.put("resultCount", count);
        audit.publish(AdminAuditEvent.builder("account.search")
                .actor(caller.currentSubject())
                .operatorUserId(caller.currentUserId().orElse(null))
                .summary("Admin account search (" + mode + ") → " + count + " results")
                .payload(payload)
                .build());
    }

    // ---------------------------------------------------------------------
    // Detail
    // ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public AdminAccountDetail detail(String accountId) {
        AccountEntity acc = accounts.findByAccountId(accountId)
                .orElseThrow(() -> AdminProblemException.notFound("Account " + accountId));
        return toDetail(acc);
    }

    @Transactional(readOnly = true)
    public Page<AccountTransactionEntity> listTransactions(String accountId, Instant from, Instant to,
                                                           Pageable pageable) {
        // Surface a clean 404 if the account doesn't exist.
        accounts.findByAccountId(accountId)
                .orElseThrow(() -> AdminProblemException.notFound("Account " + accountId));
        return transactions.findForAccount(accountId, from, to, pageable);
    }

    public AdminTransactionSummary toTransactionSummary(AccountTransactionEntity t) {
        return new AdminTransactionSummary(
                t.getTransactionId(), t.getPostedAt(), t.getDirection().name(),
                t.getAmount().toPlainString(), t.getCurrency(), t.getStatus(),
                Masking.holderName(t.getCounterpartyName()),
                Masking.iban(t.getCounterpartyIban()),
                t.getReference(), t.getRejectionReason());
    }

    // ---------------------------------------------------------------------
    // Mutations
    // ---------------------------------------------------------------------

    @Transactional
    public AdminAccountDetail freeze(String accountId, FreezeRequest req, String stepUpToken) {
        validateConfirmedCriteria(req.confirmedCriteria());
        validateNotes(req.notes(), 50, 2000);
        verifyStepUp(stepUpToken, AdminAction.FREEZE_ACCOUNT, "account", accountId,
                req.reason().name(), req.notes());
        AccountEntity acc = accounts.findByAccountId(accountId)
                .orElseThrow(() -> AdminProblemException.notFound("Account " + accountId));
        if (acc.getStatus() == AccountStatus.CLOSED) {
            throw AdminProblemException.accountClosed(accountId);
        }
        if (acc.getStatus() == AccountStatus.FROZEN) {
            throw AdminProblemException.accountFrozen(accountId);
        }

        commands.freezeAccount(accountId, req.reason().name() + ": " + summary(req.notes()));
        Instant now = Instant.now();
        acc.setFreezeReason(req.reason());
        acc.setFreezeReference(req.freezeReference());
        acc.setFreezeAppliedAt(now);
        acc.setFreezeAppliedBy(caller.currentSubject());
        acc.setFreezeNotesSummary(summary(req.notes()));
        accounts.save(acc);

        publishMutationAudit("account.freeze.applied", accountId, acc, Map.of(
                "reason", req.reason().name(),
                "reference", req.freezeReference() == null ? "" : req.freezeReference(),
                "notesSummary", summary(req.notes())));
        return toDetail(acc);
    }

    @Transactional
    public AdminAccountDetail unfreeze(String accountId, UnfreezeRequest req, String stepUpToken) {
        if (!caller.hasAnyRole("SUPERVISOR", "ADMIN")) {
            throw AdminProblemException.forbidden("Unfreeze requires SUPERVISOR role");
        }
        validateConfirmedCriteria(req.confirmedCriteria());
        validateNotes(req.notes(), 50, 2000);
        verifyStepUp(stepUpToken, AdminAction.UNFREEZE_ACCOUNT, "account", accountId,
                req.reason().name(), req.notes());
        AccountEntity acc = accounts.findByAccountId(accountId)
                .orElseThrow(() -> AdminProblemException.notFound("Account " + accountId));
        if (acc.getStatus() != AccountStatus.FROZEN) {
            throw new AdminProblemException(HttpStatus.CONFLICT,
                    "urn:problem:account-not-frozen",
                    "Account not frozen",
                    "Account " + accountId + " is not in FROZEN state");
        }

        commands.unfreezeAccount(accountId, req.reason().name() + ": " + summary(req.notes()));
        Instant now = Instant.now();
        acc.setFreezeReason(null);
        acc.setFreezeReference(null);
        acc.setFreezeAppliedAt(null);
        acc.setFreezeAppliedBy(null);
        acc.setFreezeNotesSummary(null);
        acc.setUpdatedAt(now);
        accounts.save(acc);

        publishMutationAudit("account.unfreeze.applied", accountId, acc, Map.of(
                "reason", req.reason().name(),
                "notesSummary", summary(req.notes())));
        return toDetail(acc);
    }

    @Transactional
    public AdminAccountDetail close(String accountId, CloseAccountRequest req, String stepUpToken) {
        validateConfirmedCriteria(req.confirmedCriteria());
        validateNotes(req.notes(), 50, 2000);
        verifyStepUp(stepUpToken, AdminAction.CLOSE_ACCOUNT, "account", accountId,
                req.reason().name(), req.notes());
        AccountEntity acc = accounts.findByAccountId(accountId)
                .orElseThrow(() -> AdminProblemException.notFound("Account " + accountId));
        if (acc.getStatus() == AccountStatus.CLOSED) {
            throw AdminProblemException.accountClosed(accountId);
        }
        if (acc.getBookedBalance().compareTo(BigDecimal.ZERO) != 0) {
            throw AdminProblemException.balanceNonzero(accountId);
        }
        if (acc.getReservedAmount().compareTo(BigDecimal.ZERO) != 0) {
            throw AdminProblemException.pendingExecution(accountId);
        }
        Instant now = Instant.now();
        acc.setStatus(AccountStatus.CLOSED);
        acc.setClosedAt(now);
        acc.setUpdatedAt(now);
        accounts.save(acc);

        publishMutationAudit("account.closed", accountId, acc, Map.of(
                "reason", req.reason().name(),
                "notesSummary", summary(req.notes())));
        return toDetail(acc);
    }

    @Transactional
    public AdminAccountDetail create(CreateAccountRequest req, String stepUpToken) {
        validateConfirmedCriteria(req.confirmedCriteria());
        validateNotes(req.notes(), 50, 2000);
        verifyStepUp(stepUpToken, AdminAction.CREATE_ACCOUNT, "user", req.customerId(),
                req.type().name(), req.notes());

        if (req.customerId() == null || req.customerId().isBlank()) {
            throw AdminProblemException.validation("customerId required");
        }
        Currency currency;
        try { currency = Currency.fromCode(req.currency().name()); }
        catch (Exception e) { throw AdminProblemException.validation("Unsupported currency: " + req.currency()); }

        String accountId = "ACC-" + UUID.randomUUID().toString().substring(0, 12);
        BigDecimal initial = BigDecimal.ZERO;
        if (req.initialDeposit() != null) {
            initial = new BigDecimal(req.initialDeposit().amount());
            if (initial.signum() <= 0) {
                throw AdminProblemException.validation("initialDeposit.amount must be positive");
            }
        }
        AccountEntity created = commands.createAccount(accountId, "Customer " + req.customerId(),
                currency, MonetaryAmount.of(initial, currency));
        created.setCustomerId(req.customerId());
        created.setAccountType(req.type());
        accounts.save(created);

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("customerId", req.customerId());
        auditPayload.put("type", req.type().name());
        auditPayload.put("currency", req.currency().name());
        if (req.initialDeposit() != null) {
            auditPayload.put("initialDeposit", Map.of(
                    "amount", req.initialDeposit().amount(),
                    "sourceAccountId", req.initialDeposit().sourceAccountId()));
        }
        publishMutationAudit("account.created", accountId, created, auditPayload);
        return toDetail(created);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    public void verifyStepUp(String stepUpToken, AdminAction action, String resourceType,
                             String resourceId, String decision, String notes) {
        String notesHash = DynamicLinkingHash.sha256HexNfc(notes == null ? "" : notes);
        DynamicLinkingHash.Computation expected = dlHasher.computeAdminAction(
                action.name(), resourceType, resourceId, decision, notesHash);
        StepUpValidator.ValidationResult result = stepUp.validate(stepUpToken, expected.hex());
        switch (result.outcome()) {
            case VALID -> { /* ok */ }
            case MISSING, NOT_FOUND -> throw AdminProblemException.stepUpRequired(result.detail());
            case DL_HASH_MISMATCH -> throw AdminProblemException.dynamicLinkingMismatch(result.detail());
            case IDENTITY_ERROR -> throw new AdminProblemException(HttpStatus.SERVICE_UNAVAILABLE,
                    "urn:problem:identity-unavailable",
                    "Identity service unavailable",
                    result.detail());
        }
    }

    private void publishMutationAudit(String type, String accountId, AccountEntity acc,
                                      Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("status", acc.getStatus().name());
        audit.publish(AdminAuditEvent.builder(type)
                .actor(caller.currentSubject())
                .operatorUserId(caller.currentUserId().orElse(null))
                .customerUserId(parseUuid(acc.getCustomerId()))
                .resourceType("account")
                .resourceId(accountId)
                .summary(type.replace('.', ' ') + " for " + accountId)
                .payload(payload)
                .build());
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    private static void validateConfirmedCriteria(boolean v) {
        if (!v) throw AdminProblemException.validation("confirmedCriteria must be true");
    }

    private static void validateNotes(String notes, int min, int max) {
        if (notes == null || notes.length() < min || notes.length() > max) {
            throw AdminProblemException.validation(
                    "notes must be between " + min + " and " + max + " characters");
        }
    }

    private static String summary(String notes) {
        if (notes == null) return "";
        String collapsed = notes.replaceAll("\\s+", " ").trim();
        return collapsed.length() > 140 ? collapsed.substring(0, 137) + "..." : collapsed;
    }

    public AdminAccountSummary toSummary(AccountEntity acc) {
        return new AdminAccountSummary(
                acc.getAccountId(),
                Masking.iban(acc.getIban()),
                acc.getAccountType() == null ? AccountType.CHECKING : acc.getAccountType(),
                acc.getCurrency(),
                acc.getStatus().name(),
                Masking.holderName(acc.getHolderName()),
                acc.getCustomerId(),
                acc.getBookedBalance().toPlainString(),
                acc.getAvailableBalance().toPlainString(),
                acc.getFreezeReason() == null ? null : acc.getFreezeReason().name());
    }

    public AdminAccountDetail toDetail(AccountEntity acc) {
        AdminAccountSummary summary = toSummary(acc);
        List<ReservationEntity> resv = reservations.findByAccountIdAndStatus(
                acc.getAccountId(),
                com.jrpay.ledger.domain.ReservationStatus.RESERVED);
        List<AdminAccountDetail.ReservationItem> reservationItems = new ArrayList<>();
        for (ReservationEntity r : resv) {
            reservationItems.add(new AdminAccountDetail.ReservationItem(
                    r.getTransactionId(),
                    r.getAmount().toPlainString(),
                    r.getCurrency(),
                    r.getCreatedAt()));
        }
        AdminAccountDetail.FreezeDetail freeze = acc.getFreezeReason() == null ? null
                : new AdminAccountDetail.FreezeDetail(
                        acc.getFreezeReason().name(),
                        acc.getFreezeReference(),
                        acc.getFreezeAppliedAt(),
                        acc.getFreezeAppliedBy(),
                        acc.getFreezeNotesSummary());
        return new AdminAccountDetail(
                summary,
                acc.getIban(),
                acc.getBic(),
                acc.getReservedAmount().toPlainString(),
                reservationItems,
                acc.getOpenedAt(),
                acc.getClosedAt(),
                freeze,
                Masking.address(acc.getHolderAddress()),
                Masking.fodselsnummer(acc.getFodselsnummer()));
    }

    /**
     * Mod-97 IBAN check (ISO 13616). Lightweight; for fuller validation a
     * dedicated library is recommended in production.
     */
    private static boolean isIbanMod97Valid(String iban) {
        if (iban.length() < 5) return false;
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < rearranged.length(); i++) {
            char c = rearranged.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
            else if (c >= 'A' && c <= 'Z') digits.append(10 + (c - 'A'));
            else return false;
        }
        // Streamed mod-97 (avoid BigInteger allocation per-char).
        long mod = 0;
        for (int i = 0; i < digits.length(); i++) {
            mod = (mod * 10 + (digits.charAt(i) - '0')) % 97;
        }
        return mod == 1;
    }
}
