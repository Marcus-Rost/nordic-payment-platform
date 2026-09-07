package com.jrpay.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Verifies the three SSE stream-token mint endpoints all issue a single-use
 * 5-minute token. Doesn't open the long-lived SSE connection — the token mint
 * is the stable smoke surface, and the open-connection path is covered by
 * the per-service Layer-2 integration tests.
 */
class SseStreamTokenSmokeTest extends JwtSmokeTestBase {

    static String customerJwt;
    static String adminJwt;

    @BeforeAll
    static void requireServicesAndAcquireJwts() {
        var probe = new SseStreamTokenSmokeTest();
        Assumptions.assumeTrue(probe.isServiceUp(IDENTITY_MGMT),
                "identity not reachable at " + IDENTITY_MGMT);
        Assumptions.assumeTrue(probe.isServiceUp(PSD2_MGMT),
                "psd2-api not reachable at " + PSD2_MGMT);
        Assumptions.assumeTrue(probe.isServiceUp(FRAUD_MGMT),
                "fraud-detection not reachable at " + FRAUD_MGMT);
        customerJwt = probe.acquireCustomerJwt();
        adminJwt = probe.acquireAdminJwt();
    }

    @Test
    @SuppressWarnings("unchecked")
    void psd2_paymentStreamToken_mintReturnsToken() {
        RestClient psd2 = bearerClient(PSD2_BASE, customerJwt);
        Map<String, Object> body = psd2.post()
                .uri("/api/v1/customer/payments/stream-token")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey())
                .body(Map.of())
                .retrieve()
                .body(Map.class);
        assertNotNull(body);
        assertTrue(body.containsKey("streamToken"));
        assertNotNull(body.get("streamToken"));
        assertEquals(300, ((Number) body.get("expiresIn")).intValue(),
                "Stream token TTL should be 300s");
    }

    @Test
    @SuppressWarnings("unchecked")
    void a5_fraudCasesStreamToken_mintReturnsToken() {
        RestClient a5 = bearerClient(FRAUD_BASE, adminJwt);
        Map<String, Object> body = a5.post()
                .uri("/api/v1/admin/fraud/cases/stream-token")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey())
                .body(Map.of())
                .retrieve()
                .body(Map.class);
        assertNotNull(body);
        assertTrue(body.containsKey("streamToken"));
        assertNotNull(body.get("streamToken"));
        assertEquals(300, ((Number) body.get("expiresIn")).intValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void a5_sanctionsHitsStreamToken_mintReturnsToken() {
        RestClient a5 = bearerClient(FRAUD_BASE, adminJwt);
        Map<String, Object> body = a5.post()
                .uri("/api/v1/admin/sanctions/hits/stream-token")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", idempotencyKey())
                .body(Map.of())
                .retrieve()
                .body(Map.class);
        assertNotNull(body);
        assertTrue(body.containsKey("streamToken"));
        assertNotNull(body.get("streamToken"));
        assertEquals(300, ((Number) body.get("expiresIn")).intValue());
    }
}
