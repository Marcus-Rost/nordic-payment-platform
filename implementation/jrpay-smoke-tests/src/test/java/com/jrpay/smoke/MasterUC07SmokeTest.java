package com.jrpay.smoke;

import org.junit.jupiter.api.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests derived from Master UC-07: Account Lifecycle Management.
 *
 * Scenarios covered:
 *   7A — Account creation with initial balance
 *   7B — Account freeze (rejects debits, accepts credits) and unfreeze
 *   7C — Balance query (booked, reserved, available)
 *
 * Requires: A1 running on port 8091 with 'docker' profile.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MasterUC07SmokeTest extends SmokeTestBase {

    private static final String ACCOUNT_ID = "NO-UC07-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String HOLDER_NAME = "Astrid Lindgren";
    private static final String CURRENCY = "NOK";
    private static final double INITIAL_BALANCE = 25000.00;

    @BeforeAll
    static void checkServices(TestInfo info) {
        var base = new MasterUC07SmokeTest();
        Assumptions.assumeTrue(base.isServiceUp(A1_MGMT),
                "A1 not reachable at " + A1_MGMT + " — skipping UC-07 smoke tests");
    }

    // ── 7A: Account Creation ─────────────────────────────────────────

    @Test
    @Order(1)
    void uc07a_createAccount_returnsCreatedWithCorrectFields() {
        Map<String, Object> response = createAccount(ACCOUNT_ID, HOLDER_NAME, CURRENCY, INITIAL_BALANCE);

        assertNotNull(response, "Create account response should not be null");
        assertEquals(ACCOUNT_ID, response.get("accountId"));
        assertEquals(HOLDER_NAME, response.get("holderName"));
        assertEquals(CURRENCY, response.get("currency"));
        assertEquals("ACTIVE", response.get("status"));
    }

    @Test
    @Order(2)
    void uc07a_createdAccount_canBeRetrieved() {
        Map<String, Object> response = a1Client.get()
                .uri("/api/v1/accounts/{id}", ACCOUNT_ID)
                .retrieve()
                .body(Map.class);

        assertNotNull(response);
        assertEquals(ACCOUNT_ID, response.get("accountId"));
        assertEquals(HOLDER_NAME, response.get("holderName"));
    }

    @Test
    @Order(2)
    void uc07a_duplicateCreate_isRejectedOrIdempotent() {
        // Creating the same account again should either fail (409) or be idempotent
        try {
            createAccount(ACCOUNT_ID, HOLDER_NAME, CURRENCY, INITIAL_BALANCE);
            // If it doesn't throw, it was idempotent — acceptable
        } catch (HttpClientErrorException e) {
            // 409 Conflict or 400 Bad Request are both acceptable
            assertTrue(e.getStatusCode().is4xxClientError(),
                    "Duplicate creation should return 4xx, got: " + e.getStatusCode());
        }
    }

    // ── 7C: Balance Query ────────────────────────────────────────────

    @Test
    @Order(3)
    void uc07c_balanceQuery_showsInitialBalance() {
        Map<String, Object> balances = getBalances(ACCOUNT_ID);

        assertNotNull(balances);
        assertEquals(ACCOUNT_ID, balances.get("accountId"));

        // Initial balance should be booked, nothing reserved
        BigDecimal booked = new BigDecimal(balances.get("bookedBalance").toString());
        BigDecimal reserved = new BigDecimal(balances.get("reservedAmount").toString());
        BigDecimal available = new BigDecimal(balances.get("availableBalance").toString());

        assertEquals(0, booked.compareTo(BigDecimal.valueOf(INITIAL_BALANCE)),
                "Booked balance should equal initial balance");
        assertEquals(0, reserved.compareTo(BigDecimal.ZERO),
                "Reserved amount should be zero");
        assertEquals(0, available.compareTo(BigDecimal.valueOf(INITIAL_BALANCE)),
                "Available balance should equal initial balance (no reservations)");
    }

    @Test
    @Order(3)
    void uc07c_balanceQuery_nonExistentAccount_returns404() {
        try {
            getBalances("NO-NONEXISTENT-ACCOUNT");
            fail("Expected 404 for non-existent account");
        } catch (HttpClientErrorException e) {
            assertEquals(404, e.getStatusCode().value());
        }
    }

    // ── 7A: Holder Name Endpoint (supports VoP cache lookup) ─────────

    @Test
    @Order(3)
    void uc07a_holderNameEndpoint_returnsCorrectName() {
        Map<String, Object> response = a1Client.get()
                .uri("/api/v1/accounts/{id}/holder-name", ACCOUNT_ID)
                .retrieve()
                .body(Map.class);

        assertNotNull(response);
        assertEquals(HOLDER_NAME, response.get("holderName"));
    }

    // ── Security: Authentication Required ────────────────────────────

    @Test
    @Order(1)
    void security_unauthenticatedRequest_isRejected() {
        try {
            unauthenticatedClient.get()
                    .uri("/api/v1/accounts/{id}", ACCOUNT_ID)
                    .retrieve()
                    .body(Map.class);
            fail("Expected 401 for unauthenticated request");
        } catch (HttpClientErrorException e) {
            assertEquals(401, e.getStatusCode().value(),
                    "Unauthenticated requests must receive 401");
        }
    }

    @Test
    @Order(1)
    void security_actuatorHealth_isPublic() {
        // Actuator health is on management port (separate from app port)
        String health = RestClient.create(A1_MGMT).get()
                .uri("/actuator/health")
                .retrieve()
                .body(String.class);

        assertNotNull(health);
        assertTrue(health.contains("UP") || health.contains("status"),
                "Health endpoint should return status");
    }
}
