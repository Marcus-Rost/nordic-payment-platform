package com.jrpay.smoke;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * End-to-end smoke against the Phase-2 admin surfaces:
 *   - A1 admin (account-ledger): account search
 *   - A5 admin (fraud-detection): fraud queue, sanctions queue, sanctions lists
 *   - audit-query: nothing endpoint-bearing yet beyond /actuator/health, but
 *     we verify the service is reachable.
 */
class AdminSurfaceSmokeTest extends JwtSmokeTestBase {

    static String adminJwt;

    @BeforeAll
    static void requireServicesAndAcquireJwt() {
        var probe = new AdminSurfaceSmokeTest();
        Assumptions.assumeTrue(probe.isServiceUp(IDENTITY_MGMT),
                "identity-service not reachable at " + IDENTITY_MGMT);
        Assumptions.assumeTrue(probe.isServiceUp(LEDGER_MGMT),
                "account-ledger (A1) not reachable at " + LEDGER_MGMT);
        Assumptions.assumeTrue(probe.isServiceUp(FRAUD_MGMT),
                "fraud-detection (A5) not reachable at " + FRAUD_MGMT);
        Assumptions.assumeTrue(probe.isServiceUp(AUDIT_MGMT),
                "audit-query not reachable at " + AUDIT_MGMT);
        adminJwt = probe.acquireAdminJwt();
    }

    // -- A1 admin ------------------------------------------------------

    @Test
    void a1_searchAccountsByName_returns200() {
        RestClient a1 = bearerClient(LEDGER_BASE, adminJwt);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = a1.get()
                .uri("/api/v1/admin/accounts/search?mode=name&q=Ola&page=0&size=10")
                .retrieve()
                .body(Map.class);
        assertNotNull(body);
        assertTrue(body.containsKey("items"));
        assertTrue(body.containsKey("truncated"));
    }

    // -- A5 fraud + sanctions -----------------------------------------

    @Test
    void a5_listFraudCases_returns200WithCounters() {
        RestClient a5 = bearerClient(FRAUD_BASE, adminJwt);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = a5.get()
                .uri("/api/v1/admin/fraud/cases?page=0&size=10")
                .retrieve()
                .body(Map.class);
        assertNotNull(body);
        assertTrue(body.containsKey("items"));
        assertTrue(body.containsKey("page"));
        assertTrue(body.containsKey("counters"),
                "Fraud queue must surface dashboard counters");
        @SuppressWarnings("unchecked")
        Map<String, Object> counters = (Map<String, Object>) body.get("counters");
        assertNotNull(counters);
        assertTrue(counters.containsKey("openTotal"));
        assertTrue(counters.containsKey("assignedToMe"));
        assertTrue(counters.containsKey("slaBreached"));
    }

    @Test
    void a5_listSanctionsHits_returns200WithCounters() {
        RestClient a5 = bearerClient(FRAUD_BASE, adminJwt);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = a5.get()
                .uri("/api/v1/admin/sanctions/hits?page=0&size=10")
                .retrieve()
                .body(Map.class);
        assertNotNull(body);
        assertTrue(body.containsKey("items"));
        assertTrue(body.containsKey("counters"));
    }

    @Test
    void a5_sanctionsLists_returnsSeededRegistry() {
        RestClient a5 = bearerClient(FRAUD_BASE, adminJwt);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = a5.get()
                .uri("/api/v1/admin/sanctions/lists")
                .retrieve()
                .body(Map.class);
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        assertNotNull(items);
        assertTrue(items.stream().anyMatch(i -> "EU-CONS".equals(i.get("listId"))),
                "EU-CONS demo list should be seeded; got " + items);
        assertTrue(items.stream().anyMatch(i -> "OFAC-SDN".equals(i.get("listId"))),
                "OFAC-SDN demo list should be seeded; got " + items);
    }

    @Test
    void a5_fpCacheEmpty_returns200() {
        RestClient a5 = bearerClient(FRAUD_BASE, adminJwt);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = a5.get()
                .uri("/api/v1/admin/sanctions/fp-cache?page=0&size=10")
                .retrieve()
                .body(Map.class);
        assertNotNull(body);
        assertTrue(body.containsKey("items"));
    }

    // -- audit-query ---------------------------------------------------

    @Test
    void auditQuery_actuatorHealthIsUp() {
        // The audit-query admin surface requires audit:read scope; for a stable
        // smoke probe we verify the service-management plane responds. The
        // search endpoint shape is exercised in audit-query's own integration
        // suite, against a real chain-hashed projection.
        String body = RestClient.create(AUDIT_MGMT).get()
                .uri("/actuator/health")
                .retrieve()
                .body(String.class);
        assertNotNull(body);
        assertTrue(body.contains("\"status\""));
    }
}
