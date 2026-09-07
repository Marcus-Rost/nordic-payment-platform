package com.jrpay.psd2.customer.accounts;

import com.jrpay.psd2.customer.CustomerProblemException;
import com.jrpay.psd2.customer.IbanValidator;
import com.jrpay.psd2.customer.accounts.dto.AccountDetailDto;
import com.jrpay.psd2.customer.accounts.dto.AccountSummaryDto;
import com.jrpay.psd2.customer.accounts.dto.BalancesDto;
import com.jrpay.psd2.customer.accounts.dto.StatementRequestDto;
import com.jrpay.psd2.customer.accounts.dto.StatementResponseDto;
import com.jrpay.psd2.customer.accounts.dto.TransactionDetailDto;
import com.jrpay.psd2.customer.accounts.dto.TransactionPage;
import com.jrpay.psd2.customer.accounts.dto.TransactionSummaryDto;
import com.jrpay.psd2.customer.audit.CustomerAuditEvent;
import com.jrpay.psd2.customer.audit.CustomerAuditPublisher;
import com.jrpay.psd2.customer.config.AuthenticatedUser;
import com.jrpay.psd2.customer.domain.CustomerAccountEntity;
import com.jrpay.psd2.customer.domain.CustomerAccountRepository;
import com.jrpay.psd2.customer.domain.CustomerTransactionEntity;
import com.jrpay.psd2.customer.domain.CustomerTransactionRepository;
import com.jrpay.psd2.customer.domain.StatementJobEntity;
import com.jrpay.psd2.customer.domain.StatementJobRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerAccountService {

    private final CustomerAccountRepository accounts;
    private final CustomerTransactionRepository transactions;
    private final StatementJobRepository statements;
    private final CustomerAuditPublisher audit;
    private final AuthenticatedUser caller;

    public CustomerAccountService(CustomerAccountRepository accounts,
                                  CustomerTransactionRepository transactions,
                                  StatementJobRepository statements,
                                  CustomerAuditPublisher audit,
                                  AuthenticatedUser caller) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.statements = statements;
        this.audit = audit;
        this.caller = caller;
    }

    @Transactional(readOnly = true)
    public List<AccountSummaryDto> list() {
        UUID uid = caller.requireUserId();
        return accounts.findByCustomerUserIdOrderByPrimaryDescOpenedAtAsc(uid)
                .stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public AccountDetailDto detail(String accountId) {
        CustomerAccountEntity acc = requireOwn(accountId);
        return new AccountDetailDto(toSummary(acc), acc.getBic(),
                acc.getOpenedAt(), acc.getClosedAt(), acc.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public BalancesDto balances(String accountId) {
        CustomerAccountEntity acc = requireOwn(accountId);
        return new BalancesDto(acc.getAccountId(), acc.getCurrency(),
                acc.getBookedBalance().toPlainString(),
                acc.getReservedAmount().toPlainString(),
                acc.getAvailableBalance().toPlainString());
    }

    @Transactional(readOnly = true)
    public TransactionPage listTransactions(String accountId, LocalDate from, LocalDate to,
                                            int page, int size) {
        UUID uid = caller.requireUserId();
        requireOwn(accountId);
        Instant fromI = from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toI = to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        PageRequest req = PageRequest.of(page, size);
        Page<CustomerTransactionEntity> rows;
        if (fromI != null && toI != null) {
            rows = transactions.listForAccountRange(accountId, uid, fromI, toI, req);
        } else if (fromI != null) {
            rows = transactions.listForAccountFrom(accountId, uid, fromI, req);
        } else if (toI != null) {
            rows = transactions.listForAccountTo(accountId, uid, toI, req);
        } else {
            rows = transactions.listForAccount(accountId, uid, req);
        }
        List<TransactionSummaryDto> items = rows.getContent().stream()
                .map(this::toTxSummary).toList();
        return new TransactionPage(items, new TransactionPage.Page(
                rows.getNumber(), rows.getSize(),
                rows.getTotalElements(), rows.getTotalPages()));
    }

    @Transactional(readOnly = true)
    public TransactionDetailDto getTransaction(String transactionId) {
        UUID uid = caller.requireUserId();
        List<CustomerTransactionEntity> rows = transactions.findOwnedById(transactionId, uid);
        if (rows.isEmpty()) {
            throw CustomerProblemException.notFound("Transaction " + transactionId);
        }
        // First leg — both legs share metadata.
        CustomerTransactionEntity t = rows.get(0);
        return new TransactionDetailDto(toTxSummary(t),
                t.getCounterpartyIban(), t.getRejectionReason(), t.getPostedAt());
    }

    @Transactional(readOnly = true)
    public String getTransactionReceiptSignedUrl(String transactionId) {
        UUID uid = caller.requireUserId();
        if (transactions.findOwnedById(transactionId, uid).isEmpty()) {
            throw CustomerProblemException.notFound("Transaction " + transactionId);
        }
        // 5-min single-use signed URL placeholder (real signing in Phase 2N
        // smoke tests). Records the access in audit.
        Instant expiresAt = Instant.now().plus(5, ChronoUnit.MINUTES);
        String url = "https://signed.jrpay.example/receipts/" + transactionId
                + "?exp=" + expiresAt.getEpochSecond() + "&u=" + uid;
        audit.publish(CustomerAuditEvent.builder("payment.receipt.requested")
                .actor(caller.currentSubject())
                .customerUserId(uid)
                .resourceType("transaction")
                .resourceId(transactionId)
                .summary("Receipt PDF requested for " + transactionId)
                .payload(Map.of("expiresAt", expiresAt.toString()))
                .build());
        return url;
    }

    @Transactional
    public StatementResponseDto requestStatement(String accountId, StatementRequestDto req) {
        UUID uid = caller.requireUserId();
        requireOwn(accountId);
        if (req.from() == null || req.to() == null) {
            throw CustomerProblemException.invalidInput("from and to are required");
        }
        if (!req.from().isBefore(req.to()) && !req.from().isEqual(req.to())) {
            throw CustomerProblemException.invalidInput("from must be ≤ to");
        }
        StatementJobEntity.Format format = "CSV".equalsIgnoreCase(req.format())
                ? StatementJobEntity.Format.CSV : StatementJobEntity.Format.PDF;
        UUID requestId = UUID.randomUUID();
        StatementJobEntity job = new StatementJobEntity(
                requestId, uid, accountId, req.from(), req.to(), format);
        // Synchronous demo path: produce a placeholder URL and mark ready.
        Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
        job.markReady("https://signed.jrpay.example/statements/" + requestId, expiresAt);
        statements.save(job);
        audit.publish(CustomerAuditEvent.builder("payment.statement.requested")
                .actor(caller.currentSubject())
                .customerUserId(uid)
                .resourceType("account")
                .resourceId(accountId)
                .summary("Statement requested " + req.from() + ".." + req.to() + " " + format)
                .payload(Map.of("requestId", requestId.toString(), "format", format.name()))
                .build());
        return new StatementResponseDto(requestId, "READY", job.getDownloadUrl(), expiresAt);
    }

    @Transactional(readOnly = true)
    public StatementResponseDto getStatement(UUID requestId) {
        UUID uid = caller.requireUserId();
        StatementJobEntity job = statements.findByRequestIdAndCustomerUserId(requestId, uid)
                .orElseThrow(() -> CustomerProblemException.notFound(
                        "Statement request " + requestId));
        return new StatementResponseDto(job.getRequestId(), job.getStatus().name(),
                job.getDownloadUrl(), job.getDownloadExpiresAt());
    }

    private CustomerAccountEntity requireOwn(String accountId) {
        return accounts.findByAccountIdAndCustomerUserId(accountId, caller.requireUserId())
                .orElseThrow(() -> CustomerProblemException.notFound("Account " + accountId));
    }

    private AccountSummaryDto toSummary(CustomerAccountEntity acc) {
        return new AccountSummaryDto(
                acc.getAccountId(), acc.getIban(),
                acc.getAccountType().name(), acc.getCurrency(),
                acc.getBookedBalance().toPlainString(),
                acc.getReservedAmount().toPlainString(),
                acc.getAvailableBalance().toPlainString(),
                acc.getStatus().name(), acc.getNickname(), acc.isPrimary());
    }

    private TransactionSummaryDto toTxSummary(CustomerTransactionEntity t) {
        return new TransactionSummaryDto(
                t.getTransactionId(), t.getAccountId(), t.getPostedAt(),
                t.getDirection().name(), t.getAmount().toPlainString(), t.getCurrency(),
                t.getStatus(), t.getCounterpartyName(),
                IbanValidator.mask(t.getCounterpartyIban()),
                t.getReference(), t.getDescription());
    }
}
