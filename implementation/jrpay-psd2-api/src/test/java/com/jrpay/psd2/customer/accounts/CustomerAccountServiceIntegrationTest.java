package com.jrpay.psd2.customer.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.jrpay.psd2.customer.CustomerProblemException;
import com.jrpay.psd2.customer.Psd2CustomerIntegrationTestBase;
import com.jrpay.psd2.customer.audit.CustomerAuditEvent;
import com.jrpay.psd2.customer.audit.CustomerAuditPublisher;
import com.jrpay.psd2.customer.config.AuthenticatedUser;
import com.jrpay.psd2.customer.domain.CustomerAccountEntity;
import com.jrpay.psd2.customer.domain.CustomerAccountRepository;
import com.jrpay.psd2.customer.domain.CustomerTransactionEntity;
import com.jrpay.psd2.customer.domain.CustomerTransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class CustomerAccountServiceIntegrationTest extends Psd2CustomerIntegrationTestBase {

    @Autowired CustomerAccountService service;
    @Autowired CustomerAccountRepository accounts;
    @Autowired CustomerTransactionRepository transactions;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean AuthenticatedUser caller;
    @MockitoBean CustomerAuditPublisher audit;

    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-0000c0410001");

    @BeforeEach
    void setup() {
        when(caller.requireUserId()).thenReturn(userId);
        when(caller.currentUserId()).thenReturn(Optional.of(userId));
        when(caller.currentSubject()).thenReturn("customer:" + userId);
        when(caller.isCustomer()).thenReturn(true);
        when(caller.hasScope(anyString())).thenReturn(true);
        when(audit.publish(any(CustomerAuditEvent.class))).thenReturn("01HTESTACC0000000000000XXX");
        jdbc.execute("TRUNCATE TABLE statement_jobs, customer_idempotency, "
                + "stream_tokens, customer_transactions, payment_schedules, "
                + "customer_payments, customer_accounts, processed_kafka_events CASCADE");
    }

    @Test
    void listReturnsCustomerAccountsOnly() {
        seedAccount("a-1", userId, new BigDecimal("100.00"));
        seedAccount("a-2", UUID.randomUUID(), new BigDecimal("200.00")); // other customer
        var rows = service.list();
        assertThat(rows).extracting("accountId").containsExactly("a-1");
    }

    @Test
    void detailReturns404WhenNotOwn() {
        seedAccount("a-x", UUID.randomUUID(), BigDecimal.ZERO);
        assertThatThrownBy(() -> service.detail("a-x"))
                .isInstanceOfSatisfying(CustomerProblemException.class, e ->
                        assertThat(e.getType()).contains("not-found"));
    }

    @Test
    void balancesReturnsBookedReservedAvailable() {
        CustomerAccountEntity a = seedAccount("a-1", userId, new BigDecimal("500.00"));
        a.setReservedAmount(new BigDecimal("50.00"));
        accounts.save(a);
        var b = service.balances("a-1");
        assertThat(b.bookedBalance()).isEqualTo("500.00");
        assertThat(b.reservedAmount()).isEqualTo("50.00");
        assertThat(b.availableBalance()).isEqualTo("450.00");
    }

    @Test
    void transactionsAreScopedToCustomer() {
        seedAccount("a-1", userId, new BigDecimal("100.00"));
        seedTx("t-1", "a-1", userId, "100.00",
                CustomerTransactionEntity.Direction.DEBIT);
        var page = service.listTransactions("a-1", null, null, 0, 25);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).counterpartyIbanMasked()).isNull(); // none seeded
    }

    @Test
    void receiptUrlIs302RedirectablePlaceholder() {
        seedAccount("a-1", userId, new BigDecimal("100.00"));
        seedTx("t-1", "a-1", userId, "100.00",
                CustomerTransactionEntity.Direction.DEBIT);
        String url = service.getTransactionReceiptSignedUrl("t-1");
        assertThat(url).startsWith("https://signed.jrpay.example/receipts/t-1");
    }

    @Test
    void statementRequestReturnsReadyImmediately() {
        seedAccount("a-1", userId, new BigDecimal("100.00"));
        var resp = service.requestStatement("a-1",
                new com.jrpay.psd2.customer.accounts.dto.StatementRequestDto(
                        java.time.LocalDate.now().minusMonths(1),
                        java.time.LocalDate.now(),
                        "PDF"));
        assertThat(resp.status()).isEqualTo("READY");
        assertThat(resp.downloadUrl()).startsWith("https://signed.jrpay.example/statements/");
    }

    private CustomerAccountEntity seedAccount(String id, UUID owner, BigDecimal balance) {
        CustomerAccountEntity a = new CustomerAccountEntity(id, owner, "NOK");
        a.setBookedBalance(balance);
        a.setIban("NO938601111794" + id.charAt(id.length() - 1)); // unique-ish
        return accounts.save(a);
    }

    private void seedTx(String txId, String accountId, UUID owner, String amount,
                        CustomerTransactionEntity.Direction direction) {
        transactions.save(new CustomerTransactionEntity(
                txId, accountId, owner, direction, new BigDecimal(amount),
                "NOK", "SETTLED", null, null, null, null,
                Instant.now().minus(1, ChronoUnit.HOURS)));
    }
}
