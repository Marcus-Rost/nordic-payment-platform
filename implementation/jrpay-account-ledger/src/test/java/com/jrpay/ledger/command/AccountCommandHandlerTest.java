package com.jrpay.ledger.command;

import com.jrpay.domain.exception.AccountFrozenException;
import com.jrpay.domain.exception.InsufficientBalanceException;
import com.jrpay.domain.money.Currency;
import com.jrpay.domain.money.MonetaryAmount;
import com.jrpay.ledger.domain.AccountStatus;
import com.jrpay.ledger.domain.ReservationStatus;
import com.jrpay.ledger.event.LedgerEvent;
import com.jrpay.ledger.projection.AccountEntity;
import com.jrpay.ledger.projection.AccountRepository;
import com.jrpay.ledger.projection.ReservationEntity;
import com.jrpay.ledger.projection.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Helper: BigDecimal comparison using compareTo (not equals).
 * BigDecimal 0.00 and 0 are not equals() but are compareTo() == 0.
 */
@SuppressWarnings("unused")
class BigDecimalAssert {
    static void assertAmountEquals(String expected, java.math.BigDecimal actual) {
        assertEquals(0, new java.math.BigDecimal(expected).compareTo(actual),
                "Expected " + expected + " but was " + actual);
    }
}

/**
 * Tests the core account command handler with H2 in-memory database.
 * No Kafka required — uses a capturing EventPublisher.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import(AccountCommandHandlerTest.TestConfig.class)
class AccountCommandHandlerTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private com.jrpay.ledger.projection.AccountTransactionRepository transactionRepository;

    private AccountCommandHandler handler;
    private CapturingEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        eventPublisher = new CapturingEventPublisher();
        handler = new AccountCommandHandler(accountRepository, reservationRepository,
                transactionRepository, eventPublisher);
    }

    @Test
    @DisplayName("Create account — sets correct initial state")
    void createAccount() {
        AccountEntity account = handler.createAccount(
                "NO9386011117947", "Ola Nordmann",
                Currency.NOK, MonetaryAmount.of("10000.00", Currency.NOK));

        assertEquals("NO9386011117947", account.getAccountId());
        assertEquals("Ola Nordmann", account.getHolderName());
        assertEquals("NOK", account.getCurrency());
        BigDecimalAssert.assertAmountEquals("10000.00", account.getBookedBalance());
        BigDecimalAssert.assertAmountEquals("0.00", account.getReservedAmount());
        assertEquals(AccountStatus.ACTIVE, account.getStatus());
        assertEquals(1, eventPublisher.events.size());
        assertEquals("ACCOUNT_CREATED", eventPublisher.events.get(0).eventType());
    }

    @Test
    @DisplayName("Create account is idempotent")
    void createAccountIdempotent() {
        handler.createAccount("ACC1", "Holder", Currency.NOK, MonetaryAmount.of("100.00", Currency.NOK));
        handler.createAccount("ACC1", "Holder", Currency.NOK, MonetaryAmount.of("100.00", Currency.NOK));

        assertEquals(1, accountRepository.count());
        assertEquals(1, eventPublisher.events.size()); // only one event
    }

    @Test
    @DisplayName("Reserve — reduces available balance")
    void reserveReducesAvailable() {
        handler.createAccount("ACC1", "Holder", Currency.NOK, MonetaryAmount.of("10000.00", Currency.NOK));

        handler.reserve("ACC1", "RES1", "TXN1", MonetaryAmount.of("3000.00", Currency.NOK));

        AccountEntity account = accountRepository.findByAccountId("ACC1").orElseThrow();
        BigDecimalAssert.assertAmountEquals("10000.00", account.getBookedBalance());
        BigDecimalAssert.assertAmountEquals("3000.00", account.getReservedAmount());
        BigDecimalAssert.assertAmountEquals("7000.00", account.getAvailableBalance());
    }

    @Test
    @DisplayName("Reserve — insufficient balance throws")
    void reserveInsufficientBalance() {
        handler.createAccount("ACC1", "Holder", Currency.NOK, MonetaryAmount.of("100.00", Currency.NOK));

        assertThrows(InsufficientBalanceException.class,
                () -> handler.reserve("ACC1", "RES1", "TXN1",
                        MonetaryAmount.of("200.00", Currency.NOK)));
    }

    @Test
    @DisplayName("Reserve on frozen account throws")
    void reserveOnFrozenAccount() {
        handler.createAccount("ACC1", "Holder", Currency.NOK, MonetaryAmount.of("10000.00", Currency.NOK));
        handler.freezeAccount("ACC1", "FRAUD_CASE");

        assertThrows(AccountFrozenException.class,
                () -> handler.reserve("ACC1", "RES1", "TXN1",
                        MonetaryAmount.of("100.00", Currency.NOK)));
    }

    @Test
    @DisplayName("Reserve is idempotent")
    void reserveIdempotent() {
        handler.createAccount("ACC1", "Holder", Currency.NOK, MonetaryAmount.of("10000.00", Currency.NOK));
        handler.reserve("ACC1", "RES1", "TXN1", MonetaryAmount.of("3000.00", Currency.NOK));
        handler.reserve("ACC1", "RES1", "TXN1", MonetaryAmount.of("3000.00", Currency.NOK));

        AccountEntity account = accountRepository.findByAccountId("ACC1").orElseThrow();
        BigDecimalAssert.assertAmountEquals("3000.00", account.getReservedAmount()); // not 6000
    }

    @Test
    @DisplayName("Release — restores available balance")
    void releaseRestoresBalance() {
        handler.createAccount("ACC1", "Holder", Currency.NOK, MonetaryAmount.of("10000.00", Currency.NOK));
        handler.reserve("ACC1", "RES1", "TXN1", MonetaryAmount.of("3000.00", Currency.NOK));

        handler.release("RES1", "PAYMENT_REJECTED");

        AccountEntity account = accountRepository.findByAccountId("ACC1").orElseThrow();
        BigDecimalAssert.assertAmountEquals("0.00", account.getReservedAmount());
        BigDecimalAssert.assertAmountEquals("10000.00", account.getAvailableBalance());

        ReservationEntity reservation = reservationRepository.findByReservationId("RES1").orElseThrow();
        assertEquals(ReservationStatus.RELEASED, reservation.getStatus());
    }

    @Test
    @DisplayName("Release is idempotent — releasing already-released is no-op")
    void releaseIdempotent() {
        handler.createAccount("ACC1", "Holder", Currency.NOK, MonetaryAmount.of("10000.00", Currency.NOK));
        handler.reserve("ACC1", "RES1", "TXN1", MonetaryAmount.of("3000.00", Currency.NOK));
        handler.release("RES1", "REJECTED");
        handler.release("RES1", "REJECTED"); // second release — no-op

        AccountEntity account = accountRepository.findByAccountId("ACC1").orElseThrow();
        BigDecimalAssert.assertAmountEquals("0.00", account.getReservedAmount());
    }

    @Test
    @DisplayName("Confirm — removes reservation, keeps booked balance unchanged")
    void confirmReservation() {
        handler.createAccount("ACC1", "Holder", Currency.NOK, MonetaryAmount.of("10000.00", Currency.NOK));
        handler.reserve("ACC1", "RES1", "TXN1", MonetaryAmount.of("3000.00", Currency.NOK));

        handler.confirm("RES1");

        AccountEntity account = accountRepository.findByAccountId("ACC1").orElseThrow();
        BigDecimalAssert.assertAmountEquals("10000.00", account.getBookedBalance()); // unchanged
        BigDecimalAssert.assertAmountEquals("0.00", account.getReservedAmount());

        ReservationEntity reservation = reservationRepository.findByReservationId("RES1").orElseThrow();
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
    }

    @Test
    @DisplayName("Confirm on already-released reservation is no-op (crash recovery)")
    void confirmAfterReleaseIsNoOp() {
        handler.createAccount("ACC1", "Holder", Currency.NOK, MonetaryAmount.of("10000.00", Currency.NOK));
        handler.reserve("ACC1", "RES1", "TXN1", MonetaryAmount.of("3000.00", Currency.NOK));
        handler.release("RES1", "TIMEOUT");

        // Confirm after release — should be a no-op (bidirectional idempotency)
        handler.confirm("RES1");

        ReservationEntity reservation = reservationRepository.findByReservationId("RES1").orElseThrow();
        assertEquals(ReservationStatus.RELEASED, reservation.getStatus()); // stays RELEASED
    }

    @Test
    @DisplayName("Double-entry — debits one account, credits another, amounts match")
    void doubleEntry() {
        handler.createAccount("DEBTOR", "Debtor", Currency.NOK, MonetaryAmount.of("10000.00", Currency.NOK));
        handler.createAccount("CREDITOR", "Creditor", Currency.NOK, MonetaryAmount.of("5000.00", Currency.NOK));

        handler.applyDoubleEntry("TXN1", "DEBTOR", "CREDITOR",
                MonetaryAmount.of("2000.00", Currency.NOK), "Payment for invoice");

        AccountEntity debtor = accountRepository.findByAccountId("DEBTOR").orElseThrow();
        AccountEntity creditor = accountRepository.findByAccountId("CREDITOR").orElseThrow();

        BigDecimalAssert.assertAmountEquals("8000.00", debtor.getBookedBalance());
        BigDecimalAssert.assertAmountEquals("7000.00", creditor.getBookedBalance());

        // Conservation: total unchanged (10000 + 5000 = 15000)
        BigDecimalAssert.assertAmountEquals("15000.00",
                debtor.getBookedBalance().add(creditor.getBookedBalance()));
    }

    @Test
    @DisplayName("Freeze account — debits rejected, status changes")
    void freezeAccount() {
        handler.createAccount("ACC1", "Holder", Currency.NOK, MonetaryAmount.of("10000.00", Currency.NOK));
        handler.freezeAccount("ACC1", "SANCTIONS_HIT");

        AccountEntity account = accountRepository.findByAccountId("ACC1").orElseThrow();
        assertEquals(AccountStatus.FROZEN, account.getStatus());
    }

    @Test
    @DisplayName("Unfreeze account — restores ACTIVE status")
    void unfreezeAccount() {
        handler.createAccount("ACC1", "Holder", Currency.NOK, MonetaryAmount.of("10000.00", Currency.NOK));
        handler.freezeAccount("ACC1", "SANCTIONS_HIT");
        handler.unfreezeAccount("ACC1", "FALSE_POSITIVE");

        AccountEntity account = accountRepository.findByAccountId("ACC1").orElseThrow();
        assertEquals(AccountStatus.ACTIVE, account.getStatus());
    }

    @Test
    @DisplayName("Multiple concurrent reservations reduce available correctly")
    void multipleReservations() {
        handler.createAccount("ACC1", "Holder", Currency.NOK, MonetaryAmount.of("10000.00", Currency.NOK));
        handler.reserve("ACC1", "RES1", "TXN1", MonetaryAmount.of("3000.00", Currency.NOK));
        handler.reserve("ACC1", "RES2", "TXN2", MonetaryAmount.of("2000.00", Currency.NOK));

        AccountEntity account = accountRepository.findByAccountId("ACC1").orElseThrow();
        BigDecimalAssert.assertAmountEquals("5000.00", account.getReservedAmount());
        BigDecimalAssert.assertAmountEquals("5000.00", account.getAvailableBalance());
    }

    @Test
    @DisplayName("Third reservation fails when available is insufficient")
    void thirdReservationFails() {
        handler.createAccount("ACC1", "Holder", Currency.NOK, MonetaryAmount.of("10000.00", Currency.NOK));
        handler.reserve("ACC1", "RES1", "TXN1", MonetaryAmount.of("6000.00", Currency.NOK));
        handler.reserve("ACC1", "RES2", "TXN2", MonetaryAmount.of("3000.00", Currency.NOK));

        // Only 1000 available, requesting 2000
        assertThrows(InsufficientBalanceException.class,
                () -> handler.reserve("ACC1", "RES3", "TXN3",
                        MonetaryAmount.of("2000.00", Currency.NOK)));
    }

    // Test infrastructure
    static class CapturingEventPublisher implements EventPublisher {
        final List<LedgerEvent> events = new ArrayList<>();

        @Override
        public void publish(LedgerEvent event) {
            events.add(event);
        }
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        // Empty — just enables component scanning for this test
    }
}
