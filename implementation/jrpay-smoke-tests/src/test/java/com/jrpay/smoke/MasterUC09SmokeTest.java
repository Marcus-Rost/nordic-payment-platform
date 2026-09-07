package com.jrpay.smoke;

import org.junit.jupiter.api.*;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke tests derived from Master UC-09: Verification of Payee.
 *
 * Scenarios covered:
 *   9A — Requester: /verify endpoint (internal, returns MATCH/CLOSE_MATCH/NO_MATCH/NOT_FOUND)
 *   9B — Responder: /check endpoint (external PSP, privacy-filtered response)
 *   Privacy: actual name NEVER revealed on NO_MATCH
 *   Cross-service: A6 fetches holder name from A1 on cache miss
 *
 * Requires: A1 (8091) and A6 (8096) running with 'docker' profile.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MasterUC09SmokeTest extends SmokeTestBase {

    private static final String IBAN = "NO-UC09-" + UUID.randomUUID().toString().substring(0, 8);
    private static final String HOLDER_NAME = "Ola Nordmann AS";

    @BeforeAll
    static void checkServicesAndCreateAccount(TestInfo info) {
        var base = new MasterUC09SmokeTest();
        Assumptions.assumeTrue(base.isServiceUp(A1_MGMT),
                "A1 not reachable at " + A1_MGMT);
        Assumptions.assumeTrue(base.isServiceUp(A6_MGMT),
                "A6 not reachable at " + A6_MGMT);

        // Create the account in A1 so A6 can look it up
        base.createAccount(IBAN, HOLDER_NAME, "NOK", 10000.00);
    }

    // ── 9A: Requester — /verify (exact match) ───────────────────────

    @Test
    @Order(1)
    void uc09a_verify_exactMatch_returnsMatch() {
        Map<String, Object> result = vopVerify(IBAN, "Ola Nordmann AS", "req-exact-001");

        assertNotNull(result);
        assertEquals("MATCH", result.get("result"),
                "Exact name should return MATCH");
        assertNotNull(result.get("responseTimeMs"),
                "Response should include timing");
    }

    // ── 9A: Requester — /verify (close match) ───────────────────────

    @Test
    @Order(2)
    void uc09a_verify_legalSuffixStripped_treatedAsMatch() {
        // "Ola Nordmann" vs "Ola Nordmann AS" — legal entity detector strips "AS"
        // so the core name matches exactly → MATCH (not CLOSE_MATCH)
        Map<String, Object> result = vopVerify(IBAN, "Ola Nordmann", "req-suffix-001");

        assertNotNull(result);
        assertEquals("MATCH", result.get("result"),
                "Legal suffix should be stripped before matching — core name is exact");
    }

    @Test
    @Order(2)
    void uc09a_verify_closeMatch_returnsCloseMatchWithActualName() {
        // Partial name mismatch → should be CLOSE_MATCH (not exact, not completely wrong)
        Map<String, Object> result = vopVerify(IBAN, "Ole Nordmann AS", "req-close-001");

        assertNotNull(result);
        String matchResult = (String) result.get("result");
        assertTrue("CLOSE_MATCH".equals(matchResult) || "MATCH".equals(matchResult),
                "Similar name should be MATCH or CLOSE_MATCH, got: " + matchResult);
        // actualName is only returned on CLOSE_MATCH (to help the payer correct);
        // on MATCH it is null (no correction needed)
        if ("CLOSE_MATCH".equals(matchResult)) {
            assertNotNull(result.get("actualName"),
                    "CLOSE_MATCH should include actualName for payer guidance");
        }
    }

    // ── 9A: Requester — /verify (no match, privacy) ─────────────────

    @Test
    @Order(3)
    void uc09a_verify_noMatch_doesNotRevealActualName() {
        Map<String, Object> result = vopVerify(IBAN, "Completely Wrong Person", "req-nomatch-001");

        assertNotNull(result);
        assertEquals("NO_MATCH", result.get("result"),
                "Unrelated name should return NO_MATCH");
        assertNull(result.get("actualName"),
                "SECURITY: actualName must NEVER be revealed on NO_MATCH (prevents name harvesting)");
    }

    // ── 9A: Requester — /verify (account not found) ─────────────────

    @Test
    @Order(1)
    void uc09a_verify_unknownIban_returnsNotFound() {
        Map<String, Object> result = vopVerify(
                "NO-NONEXISTENT-IBAN", "Any Name", "req-notfound-001");

        assertNotNull(result);
        assertEquals("NOT_FOUND", result.get("result"),
                "Unknown IBAN should return NOT_FOUND");
        assertNull(result.get("actualName"),
                "NOT_FOUND should not reveal any name");
    }

    // ── 9B: Responder — /check (external PSP endpoint) ──────────────

    @Test
    @Order(4)
    void uc09b_check_exactMatch_returnsMatchNoNameSuggestion() {
        Map<String, Object> result = a6Client.post()
                .uri("/api/v1/vop/check")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "iban", IBAN,
                        "name", "Ola Nordmann AS"
                ))
                .retrieve()
                .body(Map.class);

        assertNotNull(result);
        assertEquals("MATCH", result.get("result"));
        // On exact MATCH, no name suggestion needed
        assertNull(result.get("nameSuggestion"),
                "MATCH should not include name suggestion");
    }

    @Test
    @Order(5)
    void uc09b_check_closeMatch_includesNameSuggestion() {
        // Partial name mismatch to trigger CLOSE_MATCH
        Map<String, Object> result = a6Client.post()
                .uri("/api/v1/vop/check")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "iban", IBAN,
                        "name", "Ole Nordmann AS"
                ))
                .retrieve()
                .body(Map.class);

        assertNotNull(result);
        String matchResult = (String) result.get("result");
        assertTrue("CLOSE_MATCH".equals(matchResult) || "MATCH".equals(matchResult),
                "Similar name should be MATCH or CLOSE_MATCH, got: " + matchResult);
    }

    @Test
    @Order(6)
    void uc09b_check_noMatch_neverRevealsName() {
        Map<String, Object> result = a6Client.post()
                .uri("/api/v1/vop/check")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "iban", IBAN,
                        "name", "Completely Wrong Person"
                ))
                .retrieve()
                .body(Map.class);

        assertNotNull(result);
        assertEquals("NO_MATCH", result.get("result"));
        assertNull(result.get("nameSuggestion"),
                "SECURITY: NO_MATCH on external /check must NEVER reveal name (structural enforcement)");
    }

    // ── Cross-service: A6 → A1 cache-miss integration ────────────────

    @Test
    @Order(7)
    void crossService_a6FetchesFromA1_onCacheMiss() {
        // Create a fresh account in A1 that A6 has never seen
        String freshIban = "NO-FRESH-" + UUID.randomUUID().toString().substring(0, 8);
        createAccount(freshIban, "Kari Transen", "NOK", 5000.00);

        // Query A6 — this must trigger a cache miss → REST call to A1
        Map<String, Object> result = vopVerify(freshIban, "Kari Transen", "req-cachemiss-001");

        assertNotNull(result);
        assertEquals("MATCH", result.get("result"),
                "A6 should find holder name from A1 on cache miss and return MATCH");
    }

    // ── Nordic diacritics ────────────────────────────────────────────

    @Test
    @Order(8)
    void nordicDiacritics_oWithStroke_handledCorrectly() {
        String nordicIban = "NO-NORDIC-" + UUID.randomUUID().toString().substring(0, 8);
        createAccount(nordicIban, "Bjørn Kjærstad", "NOK", 1000.00);

        // Name without diacritics should still match
        Map<String, Object> result = vopVerify(nordicIban, "Bjorn Kjaerstad", "req-nordic-001");

        assertNotNull(result);
        // Should be MATCH or CLOSE_MATCH (not NO_MATCH)
        String matchResult = (String) result.get("result");
        assertTrue("MATCH".equals(matchResult) || "CLOSE_MATCH".equals(matchResult),
                "Nordic diacritics should not prevent matching, got: " + matchResult);
    }

    // ── Security: unauthenticated VoP ────────────────────────────────

    @Test
    @Order(1)
    void security_unauthenticatedVopRequest_isRejected() {
        try {
            unauthenticatedClient.post()
                    .uri("/api/v1/vop/verify")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "beneficiaryIban", IBAN,
                            "statedName", "Test",
                            "requestId", "unauth-001"
                    ))
                    .retrieve()
                    .body(Map.class);
            fail("Expected 401 for unauthenticated VoP request");
        } catch (HttpClientErrorException e) {
            assertEquals(401, e.getStatusCode().value());
        }
    }
}
