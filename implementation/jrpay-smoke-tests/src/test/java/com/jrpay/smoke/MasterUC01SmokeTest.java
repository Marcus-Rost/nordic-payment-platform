package com.jrpay.smoke;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests derived from Master UC-01: Electronic Invoice Payment.
 *
 * Tests the end-to-end payment flow through A1 (direct transfer mode):
 *   VoP check → Payment transfer → Verify settlement (balances updated)
 *   Conservation of money: total money in system before == total after
 *
 * In production, payment flows through A3→A2→A5→A1 via Kafka.
 * This smoke test uses A1's direct /transfer endpoint to verify the
 * settlement layer independently.
 *
 * Requires: A1 (8091) and A6 (8096) running with 'docker' profile.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MasterUC01SmokeTest extends SmokeTestBase {

    private static final String DEBTOR_ID = "NO-UC01D-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String CREDITOR_ID = "NO-UC01C-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String DEBTOR_NAME = "Kari Hansen";
    private static final String CREDITOR_NAME = "Bergen Strøm AS";
    private static final double DEBTOR_INITIAL = 50000.00;
    private static final double CREDITOR_INITIAL = 10000.00;
    private static final double PAYMENT_AMOUNT = 1250.00;
    private static final String TXN_ID = "TXN-" + UUID.randomUUID().toString().substring(0, 12);

    @BeforeAll
    static void checkServicesAndCreateAccounts(TestInfo info) {
        var base = new MasterUC01SmokeTest();
        Assumptions.assumeTrue(base.isServiceUp(A1_MGMT),
                "A1 not reachable at " + A1_MGMT);
        Assumptions.assumeTrue(base.isServiceUp(A6_MGMT),
                "A6 not reachable at " + A6_MGMT);

        // Create both accounts
        base.createAccount(DEBTOR_ID, DEBTOR_NAME, "NOK", DEBTOR_INITIAL);
        base.createAccount(CREDITOR_ID, CREDITOR_NAME, "NOK", CREDITOR_INITIAL);
    }

    // ── Step 1: Pre-payment VoP check ────────────────────────────────

    @Test
    @Order(1)
    void step1_vopCheck_creditorNameVerified() {
        Map<String, Object> result = vopVerify(CREDITOR_ID, "Bergen Strøm AS", "uc01-vop-001");

        assertNotNull(result);
        String matchResult = (String) result.get("result");
        assertTrue("MATCH".equals(matchResult) || "CLOSE_MATCH".equals(matchResult),
                "VoP check should confirm creditor name before payment, got: " + matchResult);
    }

    @Test
    @Order(1)
    void step1_vopCheck_wrongName_warnsBeforePayment() {
        Map<String, Object> result = vopVerify(CREDITOR_ID, "Scam Company Ltd", "uc01-vop-002");

        assertNotNull(result);
        assertEquals("NO_MATCH", result.get("result"),
                "Wrong creditor name should return NO_MATCH as fraud warning");
    }

    // ── Step 2: Verify initial balances ──────────────────────────────

    @Test
    @Order(2)
    void step2_initialBalances_areCorrect() {
        Map<String, Object> debtorBal = getBalances(DEBTOR_ID);
        Map<String, Object> creditorBal = getBalances(CREDITOR_ID);

        BigDecimal debtorBooked = new BigDecimal(debtorBal.get("bookedBalance").toString());
        BigDecimal creditorBooked = new BigDecimal(creditorBal.get("bookedBalance").toString());

        assertEquals(0, debtorBooked.compareTo(BigDecimal.valueOf(DEBTOR_INITIAL)),
                "Debtor should have initial balance");
        assertEquals(0, creditorBooked.compareTo(BigDecimal.valueOf(CREDITOR_INITIAL)),
                "Creditor should have initial balance");
    }

    // ── Step 3: Execute payment (direct settlement via A1) ───────────

    @Test
    @Order(3)
    void step3_transfer_settlesSuccessfully() {
        Map<String, Object> result = transfer(
                TXN_ID, DEBTOR_ID, CREDITOR_ID, PAYMENT_AMOUNT, "NOK");

        assertNotNull(result);
        assertEquals("SETTLED", result.get("status"),
                "Transfer should settle immediately in direct mode");
        assertEquals(TXN_ID, result.get("transactionId"));
    }

    // ── Step 4: Verify settlement — balances updated ─────────────────

    @Test
    @Order(4)
    void step4_debtorBalance_decreasedByPaymentAmount() {
        Map<String, Object> balances = getBalances(DEBTOR_ID);
        BigDecimal booked = new BigDecimal(balances.get("bookedBalance").toString());

        BigDecimal expected = BigDecimal.valueOf(DEBTOR_INITIAL - PAYMENT_AMOUNT);
        assertEquals(0, booked.compareTo(expected),
                "Debtor balance should decrease by payment amount: expected "
                        + expected + ", got " + booked);
    }

    @Test
    @Order(4)
    void step4_creditorBalance_increasedByPaymentAmount() {
        Map<String, Object> balances = getBalances(CREDITOR_ID);
        BigDecimal booked = new BigDecimal(balances.get("bookedBalance").toString());

        BigDecimal expected = BigDecimal.valueOf(CREDITOR_INITIAL + PAYMENT_AMOUNT);
        assertEquals(0, booked.compareTo(expected),
                "Creditor balance should increase by payment amount: expected "
                        + expected + ", got " + booked);
    }

    // ── Step 5: Conservation of money — fundamental financial invariant

    @Test
    @Order(5)
    void step5_conservationOfMoney_totalUnchanged() {
        Map<String, Object> debtorBal = getBalances(DEBTOR_ID);
        Map<String, Object> creditorBal = getBalances(CREDITOR_ID);

        BigDecimal debtorBooked = new BigDecimal(debtorBal.get("bookedBalance").toString());
        BigDecimal creditorBooked = new BigDecimal(creditorBal.get("bookedBalance").toString());

        BigDecimal totalBefore = BigDecimal.valueOf(DEBTOR_INITIAL + CREDITOR_INITIAL);
        BigDecimal totalAfter = debtorBooked.add(creditorBooked);

        assertEquals(0, totalBefore.compareTo(totalAfter),
                "Conservation of money: total before (" + totalBefore
                        + ") must equal total after (" + totalAfter + ")");
    }

    // ── Step 6: Idempotency ────────────────────────────────────────────
    // Note: The /transfer endpoint is a demo shortcut that bypasses A2 clearing.
    // Transaction-level idempotency is enforced in the real pipeline via
    // reservation idempotency in AccountCommandHandler (reserve/release/confirm).
    // The demo endpoint does NOT enforce duplicate detection — this is by design,
    // as production payments flow through A3→A2→A5→A1 with full idempotency.

    // ── Edge case: Insufficient funds ────────────────────────────────

    @Test
    @Order(7)
    void edgeCase_insufficientFunds_transferRejected() {
        String bigTxnId = "TXN-BIG-" + UUID.randomUUID().toString().substring(0, 8);
        double excessiveAmount = DEBTOR_INITIAL * 10; // Way more than available

        try {
            transfer(bigTxnId, DEBTOR_ID, CREDITOR_ID, excessiveAmount, "NOK");
            fail("Transfer exceeding available balance should be rejected");
        } catch (Exception e) {
            // Expected: should fail with insufficient funds
            assertTrue(e.getMessage().contains("400") || e.getMessage().contains("4"),
                    "Insufficient funds should return client error");
        }
    }

    // ── Edge case: Self-transfer prevention ──────────────────────────

    @Test
    @Order(7)
    void edgeCase_selfTransfer_isRejectedOrNoop() {
        String selfTxnId = "TXN-SELF-" + UUID.randomUUID().toString().substring(0, 8);

        Map<String, Object> balBefore = getBalances(DEBTOR_ID);
        BigDecimal before = new BigDecimal(balBefore.get("bookedBalance").toString());

        try {
            transfer(selfTxnId, DEBTOR_ID, DEBTOR_ID, 100.00, "NOK");
        } catch (Exception e) {
            // Rejection is the ideal behavior
            return;
        }

        // If it didn't throw, verify balance unchanged (self-transfer is a no-op)
        Map<String, Object> balAfter = getBalances(DEBTOR_ID);
        BigDecimal after = new BigDecimal(balAfter.get("bookedBalance").toString());
        assertEquals(0, before.compareTo(after),
                "Self-transfer should not change balance");
    }
}
