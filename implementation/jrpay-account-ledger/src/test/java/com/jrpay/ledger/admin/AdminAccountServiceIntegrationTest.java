package com.jrpay.ledger.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jrpay.domain.money.Currency;
import com.jrpay.domain.money.MonetaryAmount;
import com.jrpay.ledger.admin.audit.AdminAuditEvent;
import com.jrpay.ledger.admin.audit.AdminAuditPublisher;
import com.jrpay.ledger.admin.dto.CloseAccountRequest;
import com.jrpay.ledger.admin.dto.CreateAccountRequest;
import com.jrpay.ledger.admin.dto.FreezeRequest;
import com.jrpay.ledger.admin.dto.UnfreezeRequest;
import com.jrpay.ledger.admin.sca.StepUpValidator;
import com.jrpay.ledger.command.AccountCommandHandler;
import com.jrpay.ledger.config.AuthenticatedUser;
import com.jrpay.ledger.domain.AccountStatus;
import com.jrpay.ledger.domain.AccountType;
import com.jrpay.ledger.domain.CloseReason;
import com.jrpay.ledger.domain.FreezeReason;
import com.jrpay.ledger.domain.UnfreezeReason;
import com.jrpay.ledger.projection.AccountEntity;
import com.jrpay.ledger.projection.AccountRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

class AdminAccountServiceIntegrationTest extends LedgerAdminIntegrationTestBase {

    @Autowired AdminAccountService service;
    @Autowired AccountRepository accounts;
    @Autowired AccountCommandHandler commands;
    @Autowired TransactionTemplate tx;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @MockitoBean StepUpValidator stepUp;
    @MockitoBean AuthenticatedUser caller;
    @MockitoBean AdminAuditPublisher audit;

    private final UUID operatorId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @BeforeEach
    void cleanup() {
        when(caller.currentUserId()).thenReturn(Optional.of(operatorId));
        when(caller.requireUserId()).thenReturn(operatorId);
        when(caller.currentSubject()).thenReturn("operator:" + operatorId);
        when(caller.isAdmin()).thenReturn(true);
        when(caller.currentRole()).thenReturn(Optional.of("SUPERVISOR"));
        when(caller.hasAnyRole(anyString(), anyString())).thenReturn(true);
        when(caller.hasRole(anyString())).thenReturn(true);
        when(caller.hasScope(anyString())).thenReturn(true);
        when(caller.currentAcr()).thenReturn("loa3-admin");
        when(audit.publish(any(AdminAuditEvent.class))).thenReturn("01HTESTEVENT0000000000XXXX");
        when(stepUp.validate(anyString(), anyString())).thenReturn(
                new StepUpValidator.ValidationResult(true, StepUpValidator.Outcome.VALID,
                        operatorId, UUID.randomUUID(), null));
        jdbc.execute("TRUNCATE TABLE pii_pending_approvals, pii_reveal_log, "
                + "account_transactions, reservations, accounts CASCADE");
    }

    @Test
    void searchByNameUsesTrigramSimilarity() {
        seed("ACC-A", "Ola Nordmann", "NO9386011117947");
        seed("ACC-B", "Olav Hansen", "NO0820000000123");
        seed("ACC-C", "Marie Berg", "NO9300000000010");

        var result = service.search("name", "Ola", 0, 50);
        assertThat(result.items()).extracting(s -> s.accountId())
                .contains("ACC-A", "ACC-B")
                .doesNotContain("ACC-C");
        assertThat(result.truncated()).isFalse();
        verify(audit, atLeastOnce()).publish(any(AdminAuditEvent.class));
    }

    @Test
    void searchByIbanReturnsExactMatch() {
        seed("ACC-A", "Ola Nordmann", "NO9386011117947");
        var result = service.search("iban", "NO93 8601 1117 947", 0, 25);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).accountId()).isEqualTo("ACC-A");
    }

    @Test
    void searchByMalformedIbanRejects422() {
        assertThatThrownBy(() -> service.search("iban", "NO00BAD", 0, 25))
                .isInstanceOf(AdminProblemException.class)
                .hasMessageContaining("Validation");
    }

    @Test
    void searchByFodselsnummerRequiresLoa3AdminAcr() {
        seed("ACC-A", "Ola Nordmann", "NO9386011117947");
        AccountEntity a = accounts.findByAccountId("ACC-A").orElseThrow();
        a.setFodselsnummer("12345678901");
        accounts.save(a);
        when(caller.currentAcr()).thenReturn("loa3");
        assertThatThrownBy(() -> service.search("fodselsnummer", "12345678901", 0, 25))
                .isInstanceOfSatisfying(AdminProblemException.class, e ->
                        assertThat(e.getType()).contains("step-up-required"));
    }

    @Test
    void freezeAppliesAndAuditsAndRejectsDoubleFreeze() {
        seed("ACC-F", "Frozen Tester", null);
        var req = new FreezeRequest(FreezeReason.FRAUD_INVESTIGATION,
                "Manual fraud investigation per regulator request, awaiting court order.",
                Boolean.TRUE, "REG-2026-001");
        var detail = service.freeze("ACC-F", req, "step-up-token");
        assertThat(detail.summary().status()).isEqualTo("FROZEN");
        assertThat(detail.freezeDetail()).isNotNull();
        assertThat(detail.freezeDetail().reason()).isEqualTo("FRAUD_INVESTIGATION");

        verify(stepUp).validate(org.mockito.ArgumentMatchers.eq("step-up-token"), anyString());
        verify(audit, atLeastOnce()).publish(any(AdminAuditEvent.class));

        // Second freeze hits the precondition → 409.
        assertThatThrownBy(() -> service.freeze("ACC-F", req, "step-up-token"))
                .isInstanceOfSatisfying(AdminProblemException.class, e ->
                        assertThat(e.getType()).contains("account-frozen"));
    }

    @Test
    void unfreezeFromActiveStateThrows409() {
        seed("ACC-A", "Active Account", null);
        var req = new UnfreezeRequest(UnfreezeReason.FALSE_POSITIVE_CONFIRMED,
                "Investigation concluded, customer cleared, releasing freeze per audit.",
                Boolean.TRUE);
        assertThatThrownBy(() -> service.unfreeze("ACC-A", req, "step-up-token"))
                .isInstanceOfSatisfying(AdminProblemException.class, e ->
                        assertThat(e.getType()).contains("account-not-frozen"));
    }

    @Test
    void closeRejectsNonZeroBalance() {
        seedWithBalance("ACC-NZ", "Has Money", new BigDecimal("100.00"));
        var req = new CloseAccountRequest(CloseReason.CUSTOMER_REQUEST,
                "Customer asked to close, but balance is non-zero so we should reject.",
                Boolean.TRUE);
        assertThatThrownBy(() -> service.close("ACC-NZ", req, "step-up-token"))
                .isInstanceOfSatisfying(AdminProblemException.class, e ->
                        assertThat(e.getType()).contains("account-balance-nonzero"));
    }

    @Test
    void closeAcceptsZeroBalance() {
        seed("ACC-Z", "Empty Account", null);
        var req = new CloseAccountRequest(CloseReason.CUSTOMER_REQUEST,
                "Customer requested closure of dormant account, zero balance, no reservations.",
                Boolean.TRUE);
        var detail = service.close("ACC-Z", req, "step-up-token");
        assertThat(detail.summary().status()).isEqualTo("CLOSED");
        assertThat(detail.closedAt()).isNotNull();
    }

    @Test
    void createIssuesNewAccountAndPublishesAudit() {
        var req = new CreateAccountRequest(
                UUID.randomUUID().toString(),
                AccountType.SAVINGS,
                Currency.NOK,
                "Buffer",
                null,
                "New customer onboarding from CDD; SAVINGS account requested per onboarding form.",
                Boolean.TRUE);
        var detail = service.create(req, "step-up-token");
        assertThat(detail.summary().accountId()).startsWith("ACC-");
        assertThat(detail.summary().status()).isEqualTo("ACTIVE");
        verify(audit, atLeastOnce()).publish(any(AdminAuditEvent.class));
    }

    private void seed(String id, String holderName, String iban) {
        tx.executeWithoutResult(s -> {
            AccountEntity acc = commands.createAccount(id, holderName,
                    Currency.NOK, MonetaryAmount.zero(Currency.NOK));
            if (iban != null) {
                acc.setIban(iban.replace(" ", ""));
                accounts.save(acc);
            }
        });
    }

    private void seedWithBalance(String id, String holderName, BigDecimal balance) {
        tx.executeWithoutResult(s -> {
            AccountEntity acc = commands.createAccount(id, holderName,
                    Currency.NOK, MonetaryAmount.of(balance, Currency.NOK));
            accounts.save(acc);
        });
    }

}
