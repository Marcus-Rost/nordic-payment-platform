package com.jrpay.billpay.customer.beneficiaries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jrpay.billpay.audit.CustomerAuditEvent;
import com.jrpay.billpay.audit.CustomerAuditPublisher;
import com.jrpay.billpay.config.AuthenticatedUser;
import com.jrpay.billpay.customer.BillPayCustomerIntegrationTestBase;
import com.jrpay.billpay.customer.CustomerProblemException;
import com.jrpay.billpay.customer.beneficiaries.dto.BeneficiaryAddRequest;
import com.jrpay.billpay.customer.beneficiaries.dto.BeneficiaryUpdateRequest;
import com.jrpay.billpay.domain.Beneficiary;
import com.jrpay.billpay.domain.BeneficiaryRepository;
import com.jrpay.billpay.sca.ScaInitiator;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class BeneficiaryServiceIntegrationTest extends BillPayCustomerIntegrationTestBase {

    @Autowired BeneficiaryService service;
    @Autowired BeneficiaryRepository beneficiaries;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean ScaInitiator scaInitiator;
    @MockitoBean CustomerAuditPublisher audit;
    @MockitoBean AuthenticatedUser caller;

    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-0000c0033333");

    @BeforeEach
    void setup() {
        when(caller.requireUserId()).thenReturn(userId);
        when(caller.currentUserId()).thenReturn(Optional.of(userId));
        when(caller.currentSubject()).thenReturn("customer:" + userId);
        when(caller.isCustomer()).thenReturn(true);
        when(caller.hasScope(anyString())).thenReturn(true);
        when(audit.publish(any(CustomerAuditEvent.class))).thenReturn("01HTESTBEN0000000000000XXX");
        when(scaInitiator.start(any(), org.mockito.ArgumentMatchers.eq(false)))
                .thenAnswer(inv -> new ScaInitiator.InitiateResult(
                        UUID.randomUUID(), "stub-dl-hash", "TOTP", false, null));
        jdbc.execute("TRUNCATE TABLE r2p_history, payment_requests, "
                + "mandate_executions, mandates, beneficiaries CASCADE");
    }

    @Test
    void addBeneficiaryReturnsPendingScaAndPersistsRow() {
        var pending = service.add(new BeneficiaryAddRequest(
                "Mormor", "NO9386011117947", "Søndagslønn", "MATCH", false));
        assertThat(pending.beneficiaryId()).startsWith("be-");
        assertThat(pending.scaSessionId()).isNotNull();
        Beneficiary b = beneficiaries.findById(pending.beneficiaryId()).orElseThrow();
        assertThat(b.getStatus()).isEqualTo(Beneficiary.Status.PENDING_SCA);
        assertThat(b.getIban()).isEqualTo("NO9386011117947");
        verify(audit, atLeastOnce()).publish(any(CustomerAuditEvent.class));
    }

    @Test
    void duplicateIbanReturns409() {
        service.add(new BeneficiaryAddRequest(
                "Mormor", "NO9386011117947", null, null, null));
        assertThatThrownBy(() -> service.add(new BeneficiaryAddRequest(
                "Mormor v2", "NO9386011117947", null, null, null)))
                .isInstanceOfSatisfying(CustomerProblemException.class, e ->
                        assertThat(e.getType()).contains("duplicate-iban"));
    }

    @Test
    void invalidIbanReturns422() {
        assertThatThrownBy(() -> service.add(new BeneficiaryAddRequest(
                "X", "NO0000000000000", null, null, null)))
                .isInstanceOfSatisfying(CustomerProblemException.class, e ->
                        assertThat(e.getType()).contains("invalid-iban"));
    }

    @Test
    void updateOnlyChangesNotes() {
        var pending = service.add(new BeneficiaryAddRequest(
                "Mormor", "NO9386011117947", "old", null, null));
        var detail = service.update(pending.beneficiaryId(),
                new BeneficiaryUpdateRequest("new note"));
        assertThat(detail.summary().notes()).isEqualTo("new note");
    }

    @Test
    void removeDeletesEntry() {
        var pending = service.add(new BeneficiaryAddRequest(
                "Mormor", "NO9386011117947", null, null, null));
        service.remove(pending.beneficiaryId());
        assertThat(beneficiaries.findById(pending.beneficiaryId())).isEmpty();
    }

    @Test
    void listMaskingPreservesLastFour() {
        service.add(new BeneficiaryAddRequest(
                "Mormor", "NO9386011117947", null, null, null));
        var page = service.list(null, 0, 25);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).ibanMasked())
                .startsWith("NO93")
                .endsWith("7947")
                .contains("•");
    }

    @Test
    void detailReturnsFullIbanForOwn() {
        var pending = service.add(new BeneficiaryAddRequest(
                "Mormor", "NO9386011117947", null, null, null));
        var detail = service.detail(pending.beneficiaryId());
        assertThat(detail.iban()).isEqualTo("NO9386011117947");
    }
}
