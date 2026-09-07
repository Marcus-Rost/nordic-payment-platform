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
import com.jrpay.ledger.admin.dto.RevealPiiAck;
import com.jrpay.ledger.admin.dto.RevealPiiExtendRequest;
import com.jrpay.ledger.admin.dto.RevealPiiRequest;
import com.jrpay.ledger.admin.sca.StepUpValidator;
import com.jrpay.ledger.command.AccountCommandHandler;
import com.jrpay.ledger.config.AuthenticatedUser;
import com.jrpay.ledger.domain.PiiField;
import com.jrpay.ledger.projection.AccountEntity;
import com.jrpay.ledger.projection.AccountRepository;
import com.jrpay.ledger.projection.AccountTransactionRepository;
import com.jrpay.ledger.projection.PiiPendingApprovalRepository;
import com.jrpay.ledger.projection.PiiRevealRepository;
import com.jrpay.ledger.projection.ReservationRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

class PiiRevealServiceIntegrationTest extends LedgerAdminIntegrationTestBase {

    @Autowired PiiRevealService reveals;
    @Autowired PiiRevealRepository revealRepo;
    @Autowired AccountRepository accounts;
    @Autowired AccountCommandHandler commands;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean StepUpValidator stepUp;
    @MockitoBean AuthenticatedUser caller;
    @MockitoBean AdminAuditPublisher audit;

    private final UUID operatorId = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private final UUID supervisorId = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    @BeforeEach
    void setup() {
        when(caller.requireUserId()).thenReturn(operatorId);
        when(caller.currentUserId()).thenReturn(Optional.of(operatorId));
        when(caller.currentSubject()).thenReturn("operator:" + operatorId);
        when(caller.isAdmin()).thenReturn(true);
        when(caller.currentRole()).thenReturn(Optional.of("COMPLIANCE_OFFICER"));
        when(caller.hasRole(anyString())).thenReturn(false);
        when(caller.hasAnyRole(anyString(), anyString())).thenReturn(false);
        when(caller.hasScope("accounts:admin:write")).thenReturn(true);
        when(caller.hasScope("accounts:admin:read")).thenReturn(true);
        when(caller.currentAcr()).thenReturn("loa3-admin");
        when(audit.publish(any(AdminAuditEvent.class))).thenReturn("01HTESTREVEAL000000000XXXX");
        when(stepUp.validate(anyString(), anyString())).thenReturn(
                new StepUpValidator.ValidationResult(true, StepUpValidator.Outcome.VALID,
                        operatorId, UUID.randomUUID(), null));
        // Cascade-cleanup in FK order: pending approvals + reveal log + reservations + transactions → accounts.
        jdbc.execute("TRUNCATE TABLE pii_pending_approvals, pii_reveal_log, "
                + "account_transactions, reservations, accounts CASCADE");
    }

    @Test
    void revealHappyPathReturnsValueWithSixtySecondTtl() {
        seedAccount("ACC-PII", "12345678901", "Storgata 1, Oslo");
        var req = new RevealPiiRequest(PiiField.fodselsnummer,
                "Verifying customer identity for fraud-case escalation per regulator request.",
                Boolean.TRUE);
        var outcome = reveals.reveal("ACC-PII", req, "step-up-token");
        assertThat(outcome).isInstanceOf(PiiRevealService.RevealOutcome.Granted.class);
        RevealPiiAck ack = ((PiiRevealService.RevealOutcome.Granted) outcome).ack();
        assertThat(ack.value()).isEqualTo("12345678901");
        assertThat(ack.ttlSeconds()).isEqualTo(60L);
        assertThat(ack.extensionsRemaining()).isEqualTo(3);
        assertThat(ack.revealId()).isNotNull();
        verify(audit, atLeastOnce()).publish(any(AdminAuditEvent.class));
    }

    @Test
    void revealOfFnrWithoutWriteScopeIsForbidden() {
        seedAccount("ACC-PII2", "12345678901", "Storgata 1, Oslo");
        when(caller.hasScope("accounts:admin:write")).thenReturn(false);
        var req = new RevealPiiRequest(PiiField.fodselsnummer,
                "Need to confirm customer identity per AML verification request.",
                Boolean.TRUE);
        assertThatThrownBy(() -> reveals.reveal("ACC-PII2", req, "step-up-token"))
                .isInstanceOfSatisfying(AdminProblemException.class, e ->
                        assertThat(e.getType()).contains("forbidden"));
    }

    @Test
    void extensionIncrementsAndCapsAtThree() {
        seedAccount("ACC-EXT", "12345678901", "Bygdøy alle 1, Oslo");
        var req = new RevealPiiRequest(PiiField.holderName,
                "Need full holder name for sanctions cross-check, low-risk action.",
                Boolean.TRUE);
        var ack = ((PiiRevealService.RevealOutcome.Granted)
                reveals.reveal("ACC-EXT", req, "step-up-token")).ack();
        for (int i = 1; i <= 3; i++) {
            var resp = reveals.extend("ACC-EXT",
                    new RevealPiiExtendRequest(ack.revealId()), "step-up-token");
            assertThat(resp.get("extensionsRemaining")).isEqualTo(3 - i);
            assertThat(resp.get("ttlSeconds")).isEqualTo(60L);
        }
        // 4th extension hits the cap.
        assertThatThrownBy(() -> reveals.extend("ACC-EXT",
                new RevealPiiExtendRequest(ack.revealId()), "step-up-token"))
                .isInstanceOfSatisfying(AdminProblemException.class, e ->
                        assertThat(e.getType()).contains("reveal-extension-cap"));
    }

    @Test
    void rateLimitTriggersPendingApprovalAfterFiveReveals() {
        seedAccount("ACC-RL", "12345678901", "Karl Johans gate 1, Oslo");
        var req = new RevealPiiRequest(PiiField.holderName,
                "Repeat reveals for documentation in fraud-case file, audit-tracked.",
                Boolean.TRUE);
        for (int i = 0; i < 5; i++) {
            var ok = reveals.reveal("ACC-RL", req, "step-up-token");
            assertThat(ok).isInstanceOf(PiiRevealService.RevealOutcome.Granted.class);
        }
        var sixth = reveals.reveal("ACC-RL", req, "step-up-token");
        assertThat(sixth).isInstanceOf(PiiRevealService.RevealOutcome.PendingApproval.class);
        var pending = (PiiRevealService.RevealOutcome.PendingApproval) sixth;
        assertThat(pending.response().requiresSecondApproval()).isTrue();
        assertThat(pending.response().approvalRef()).startsWith("ar_");
    }

    @Test
    void supervisorApprovalReleasesValue() {
        seedAccount("ACC-AP", "12345678901", "Slottsplassen 1, Oslo");
        var req = new RevealPiiRequest(PiiField.holderName,
                "Repeated identity verification needed for compliance reporting cycle.",
                Boolean.TRUE);
        for (int i = 0; i < 5; i++) {
            reveals.reveal("ACC-AP", req, "step-up-token");
        }
        var sixth = (PiiRevealService.RevealOutcome.PendingApproval)
                reveals.reveal("ACC-AP", req, "step-up-token");
        String approvalRef = sixth.response().approvalRef();

        // SUPERVISOR approves.
        when(caller.requireUserId()).thenReturn(supervisorId);
        when(caller.currentUserId()).thenReturn(Optional.of(supervisorId));
        when(caller.hasAnyRole(anyString(), anyString())).thenReturn(true);
        var ack = reveals.approve("ACC-AP", approvalRef, "step-up-token");
        assertThat(ack.value()).isNotNull();
        assertThat(ack.ttlSeconds()).isEqualTo(60L);

        // Re-approval is not allowed (consumed).
        assertThatThrownBy(() -> reveals.approve("ACC-AP", approvalRef, "step-up-token"))
                .isInstanceOf(AdminProblemException.class);
    }

    @Test
    void approveRequiresSupervisorRole() {
        seedAccount("ACC-NS", "12345678901", "Akersgata 1, Oslo");
        // Operator without SUPERVISOR or ADMIN
        when(caller.hasAnyRole(anyString(), anyString())).thenReturn(false);
        assertThatThrownBy(() -> reveals.approve("ACC-NS", "ar_unknown", "step-up-token"))
                .isInstanceOfSatisfying(AdminProblemException.class, e ->
                        assertThat(e.getType()).contains("forbidden"));
    }

    private void seedAccount(String id, String fnr, String address) {
        tx.executeWithoutResult(s -> {
            AccountEntity acc = commands.createAccount(id, "Test Holder",
                    Currency.NOK, MonetaryAmount.zero(Currency.NOK));
            acc.setFodselsnummer(fnr);
            acc.setHolderAddress(address);
            acc.setCustomerId(UUID.randomUUID().toString());
            accounts.save(acc);
        });
    }
}
