package com.jrpay.ledger;

import com.jrpay.domain.exception.AccountFrozenException;
import com.jrpay.domain.exception.InsufficientBalanceException;
import com.jrpay.domain.money.Currency;
import com.jrpay.domain.money.MonetaryAmount;
import com.jrpay.ledger.command.AccountCommandHandler;
import com.jrpay.ledger.command.EventPublisher;
import com.jrpay.ledger.domain.AccountStatus;
import com.jrpay.ledger.domain.ReservationStatus;
import com.jrpay.ledger.event.LedgerEvent;
import com.jrpay.ledger.projection.AccountEntity;
import com.jrpay.ledger.projection.AccountRepository;
import com.jrpay.ledger.projection.ReservationEntity;
import com.jrpay.ledger.projection.ReservationRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests derived directly from UC-01 (Ledger Operations) use cases.
 * Each test method maps to a specific scenario in the use case document.
 *
 * @see space/09_Event_Sourced_Account_Ledger/use_cases/UC01_ledger_operations.md
 */
@DataJpaTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UseCaseEndToEndTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private com.jrpay.ledger.projection.AccountTransactionRepository transactionRepository;

    private AccountCommandHandler handler;
    private List<LedgerEvent> publishedEvents;

    @BeforeEach
    void setUp() {
        publishedEvents = new ArrayList<>();
        EventPublisher capturingPublisher = publishedEvents::add;
        handler = new AccountCommandHandler(accountRepository, reservationRepository,
                transactionRepository, capturingPublisher);
    }

    // ====================================================================
    // Scenario 1A: Standard Credit Transfer (Debit + Credit Pair)
    // ====================================================================

    @Test
    @DisplayName("UC-01 Scenario 1A: Standard credit transfer — debit + credit pair")
    void scenario1A_standardCreditTransfer() {
        // Setup: two accounts
        handler.createAccount("ACCT-A", "Ola Nordmann", Currency.NOK,
                MonetaryAmount.of("10000.00", Currency.NOK));
        handler.createAccount("ACCT-B", "Kari Hansen", Currency.NOK,
                MonetaryAmount.of("5000.00", Currency.NOK));

        // Action: settlement event — debit 5000 from A, credit 5000 to B
        handler.applyDoubleEntry("SETTLE-001", "ACCT-A", "ACCT-B",
                MonetaryAmount.of("5000.00", Currency.NOK), "Invoice payment");

        // Verify: balances updated correctly
        AccountEntity a = accountRepository.findByAccountId("ACCT-A").orElseThrow();
        AccountEntity b = accountRepository.findByAccountId("ACCT-B").orElseThrow();

        assertAmountEquals("5000.00", a.getBookedBalance());   // 10000 - 5000
        assertAmountEquals("10000.00", b.getBookedBalance());  // 5000 + 5000

        // Invariant: total debits == total credits (conservation of money)
        assertAmountEquals("15000.00", a.getBookedBalance().add(b.getBookedBalance()));

        // Verify event published
        assertTrue(publishedEvents.stream()
                .anyMatch(e -> e.eventType().equals("DOUBLE_ENTRY")));
    }

    // ====================================================================
    // Scenario 1B: Instant Payment with Balance Reservation
    // ====================================================================

    @Test
    @DisplayName("UC-01 Scenario 1B: Instant payment — reserve → confirm → settle")
    void scenario1B_instantPaymentWithReservation() {
        // Setup
        handler.createAccount("ACCT-A", "Ola Nordmann", Currency.NOK,
                MonetaryAmount.of("10000.00", Currency.NOK));
        handler.createAccount("ACCT-B", "Kari Hansen", Currency.NOK,
                MonetaryAmount.of("5000.00", Currency.NOK));

        // Step 1: A2 requests reservation
        ReservationEntity reservation = handler.reserve("ACCT-A", "RES-001", "PAY-001",
                MonetaryAmount.of("5000.00", Currency.NOK));

        // Verify: available reduced, booked unchanged
        AccountEntity afterReserve = accountRepository.findByAccountId("ACCT-A").orElseThrow();
        assertAmountEquals("10000.00", afterReserve.getBookedBalance());
        assertAmountEquals("5000.00", afterReserve.getReservedAmount());
        assertAmountEquals("5000.00", afterReserve.getAvailableBalance());

        // Step 2: Fraud/sanctions pass. A2 settles — double entry
        handler.applyDoubleEntry("PAY-001", "ACCT-A", "ACCT-B",
                MonetaryAmount.of("5000.00", Currency.NOK), "Instant payment");

        // Step 3: A2 confirms reservation
        handler.confirm("RES-001");

        // Verify final state
        AccountEntity finalA = accountRepository.findByAccountId("ACCT-A").orElseThrow();
        AccountEntity finalB = accountRepository.findByAccountId("ACCT-B").orElseThrow();

        assertAmountEquals("5000.00", finalA.getBookedBalance());   // debited
        assertAmountEquals("0.00", finalA.getReservedAmount());     // reservation confirmed
        assertAmountEquals("5000.00", finalA.getAvailableBalance()); // available = booked
        assertAmountEquals("10000.00", finalB.getBookedBalance());  // credited

        // Conservation invariant
        assertAmountEquals("15000.00",
                finalA.getBookedBalance().add(finalB.getBookedBalance()));
    }

    // ====================================================================
    // Scenario 1C: Reservation Released (Payment Failed)
    // ====================================================================

    @Test
    @DisplayName("UC-01 Scenario 1C: Reservation released — payment rejected")
    void scenario1C_reservationReleased() {
        handler.createAccount("ACCT-A", "Ola Nordmann", Currency.NOK,
                MonetaryAmount.of("10000.00", Currency.NOK));

        // Step 1: Reserve
        handler.reserve("ACCT-A", "RES-001", "PAY-001",
                MonetaryAmount.of("5000.00", Currency.NOK));

        // Step 2: Fraud detection blocks → release
        handler.release("RES-001", "FRAUD_BLOCKED");

        // Verify: balance fully restored
        AccountEntity account = accountRepository.findByAccountId("ACCT-A").orElseThrow();
        assertAmountEquals("10000.00", account.getBookedBalance());
        assertAmountEquals("0.00", account.getReservedAmount());
        assertAmountEquals("10000.00", account.getAvailableBalance());

        // Reservation status is RELEASED
        ReservationEntity res = reservationRepository.findByReservationId("RES-001").orElseThrow();
        assertEquals(ReservationStatus.RELEASED, res.getStatus());
        assertNotNull(res.getResolvedAt());
    }

    // ====================================================================
    // Scenario 1D: Concurrent Reservations
    // ====================================================================

    @Test
    @DisplayName("UC-01 Scenario 1D: Concurrent reservations — first wins, second rejected")
    void scenario1D_concurrentReservations() {
        handler.createAccount("ACCT-A", "Ola Nordmann", Currency.NOK,
                MonetaryAmount.of("8000.00", Currency.NOK));

        // First reservation succeeds
        handler.reserve("ACCT-A", "RES-001", "PAY-001",
                MonetaryAmount.of("5000.00", Currency.NOK));

        // Second reservation for same amount — insufficient available (only 3000 left)
        assertThrows(InsufficientBalanceException.class, () ->
                handler.reserve("ACCT-A", "RES-002", "PAY-002",
                        MonetaryAmount.of("5000.00", Currency.NOK)));

        // Verify: only first reservation exists
        AccountEntity account = accountRepository.findByAccountId("ACCT-A").orElseThrow();
        assertAmountEquals("5000.00", account.getReservedAmount());
        assertAmountEquals("3000.00", account.getAvailableBalance());
    }

    // ====================================================================
    // Scenario 1F: Account Freeze on Fraud Confirmation
    // ====================================================================

    @Test
    @DisplayName("UC-01 Scenario 1F: Account freeze — debits rejected, credits allowed")
    void scenario1F_accountFreeze() {
        handler.createAccount("ACCT-A", "Ola Nordmann", Currency.NOK,
                MonetaryAmount.of("10000.00", Currency.NOK));
        handler.createAccount("ACCT-B", "Merchant", Currency.NOK,
                MonetaryAmount.of("5000.00", Currency.NOK));

        // Freeze account A
        handler.freezeAccount("ACCT-A", "CONFIRMED_FRAUD");

        // Verify: status is FROZEN
        AccountEntity frozen = accountRepository.findByAccountId("ACCT-A").orElseThrow();
        assertEquals(AccountStatus.FROZEN, frozen.getStatus());

        // Debit attempt → rejected
        assertThrows(AccountFrozenException.class, () ->
                handler.reserve("ACCT-A", "RES-001", "PAY-001",
                        MonetaryAmount.of("1000.00", Currency.NOK)));

        // Credit (merchant refund to frozen account) → allowed
        handler.applyDoubleEntry("REFUND-001", "ACCT-B", "ACCT-A",
                MonetaryAmount.of("500.00", Currency.NOK), "Refund");

        AccountEntity afterRefund = accountRepository.findByAccountId("ACCT-A").orElseThrow();
        assertAmountEquals("10500.00", afterRefund.getBookedBalance()); // received refund
    }

    // ====================================================================
    // Scenario 1I: Account Creation
    // ====================================================================

    @Test
    @DisplayName("UC-01 Scenario 1I: Account creation with initial balance")
    void scenario1I_accountCreation() {
        AccountEntity account = handler.createAccount("NO9386011117947", "Ola Nordmann",
                Currency.NOK, MonetaryAmount.of("0.00", Currency.NOK));

        assertEquals("NO9386011117947", account.getAccountId());
        assertEquals("Ola Nordmann", account.getHolderName());
        assertEquals("NOK", account.getCurrency());
        assertAmountEquals("0.00", account.getBookedBalance());
        assertEquals(AccountStatus.ACTIVE, account.getStatus());

        // Event published
        assertTrue(publishedEvents.stream()
                .anyMatch(e -> e.eventType().equals("ACCOUNT_CREATED")));
    }

    // ====================================================================
    // Cross-Scenario Invariant Tests
    // ====================================================================

    @Test
    @DisplayName("Invariant: Sum(debits) == Sum(credits) after multiple operations")
    void invariant_conservationOfMoney() {
        // Create 3 accounts with known total: 10000 + 5000 + 3000 = 18000
        handler.createAccount("A", "Alice", Currency.NOK, MonetaryAmount.of("10000.00", Currency.NOK));
        handler.createAccount("B", "Bob", Currency.NOK, MonetaryAmount.of("5000.00", Currency.NOK));
        handler.createAccount("C", "Charlie", Currency.NOK, MonetaryAmount.of("3000.00", Currency.NOK));

        // Multiple transfers
        handler.applyDoubleEntry("T1", "A", "B", MonetaryAmount.of("2000.00", Currency.NOK), "");
        handler.applyDoubleEntry("T2", "B", "C", MonetaryAmount.of("3000.00", Currency.NOK), "");
        handler.applyDoubleEntry("T3", "C", "A", MonetaryAmount.of("1000.00", Currency.NOK), "");

        // Total must be unchanged: 18000
        BigDecimal total = accountRepository.findAll().stream()
                .map(AccountEntity::getBookedBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertAmountEquals("18000.00", total);
    }

    @Test
    @DisplayName("Invariant: Available = Booked - Reserved at all times")
    void invariant_availableEqualsBookedMinusReserved() {
        handler.createAccount("ACCT", "Holder", Currency.NOK,
                MonetaryAmount.of("10000.00", Currency.NOK));

        // Reserve
        handler.reserve("ACCT", "R1", "T1", MonetaryAmount.of("3000.00", Currency.NOK));
        handler.reserve("ACCT", "R2", "T2", MonetaryAmount.of("2000.00", Currency.NOK));

        AccountEntity account = accountRepository.findByAccountId("ACCT").orElseThrow();
        assertAmountEquals(
                account.getBookedBalance().subtract(account.getReservedAmount()).toPlainString(),
                account.getAvailableBalance());

        // Release one
        handler.release("R1", "REJECTED");

        account = accountRepository.findByAccountId("ACCT").orElseThrow();
        assertAmountEquals(
                account.getBookedBalance().subtract(account.getReservedAmount()).toPlainString(),
                account.getAvailableBalance());

        // Confirm the other
        handler.confirm("R2");

        account = accountRepository.findByAccountId("ACCT").orElseThrow();
        assertAmountEquals(
                account.getBookedBalance().subtract(account.getReservedAmount()).toPlainString(),
                account.getAvailableBalance());
    }

    @Test
    @DisplayName("Crash recovery: Confirm after Release is no-op (bidirectional idempotency)")
    void crashRecovery_confirmAfterRelease() {
        handler.createAccount("ACCT", "Holder", Currency.NOK,
                MonetaryAmount.of("10000.00", Currency.NOK));

        handler.reserve("ACCT", "R1", "T1", MonetaryAmount.of("5000.00", Currency.NOK));

        // Simulate crash recovery: release fires first (auto-release timeout)
        handler.release("R1", "TIMEOUT_AUTO_RELEASE");

        // Then A2 recovery sends confirm (arrived late)
        handler.confirm("R1"); // should be no-op

        // Verify: reservation stays RELEASED (not CONFIRMED)
        ReservationEntity res = reservationRepository.findByReservationId("R1").orElseThrow();
        assertEquals(ReservationStatus.RELEASED, res.getStatus());

        // Verify: balance is correct
        AccountEntity account = accountRepository.findByAccountId("ACCT").orElseThrow();
        assertAmountEquals("0.00", account.getReservedAmount());
        assertAmountEquals("10000.00", account.getAvailableBalance());
    }

    // ====================================================================
    // Helper
    // ====================================================================

    private static void assertAmountEquals(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "Expected " + expected + " but was " + actual);
    }
}
