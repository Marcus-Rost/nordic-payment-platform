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
 * End-to-end smoke against the Phase-2 customer surfaces:
 *   - PSD2 (A4):   accounts, payments
 *   - Bill-Pay (A3): R2P, mandates, beneficiaries
 *   - Notifications: inbox
 *
 * Each test acquires a fresh customer JWT and checks the listing endpoint
 * returns 200 with the expected shape. Empty lists are acceptable — the
 * smoke goal is HTTP + auth + DB plumbing, not data presence.
 */
class CustomerSurfaceSmokeTest extends JwtSmokeTestBase {

    static String customerJwt;

    @BeforeAll
    static void requireServicesAndAcquireJwt() {
        var probe = new CustomerSurfaceSmokeTest();
        Assumptions.assumeTrue(probe.isServiceUp(IDENTITY_MGMT),
                "identity-service not reachable at " + IDENTITY_MGMT);
        Assumptions.assumeTrue(probe.isServiceUp(PSD2_MGMT),
                "psd2-api (A4) not reachable at " + PSD2_MGMT);
        Assumptions.assumeTrue(probe.isServiceUp(BILLPAY_MGMT),
                "bill-payment (A3) not reachable at " + BILLPAY_MGMT);
        Assumptions.assumeTrue(probe.isServiceUp(NOTIFICATIONS_MGMT),
                "notifications not reachable at " + NOTIFICATIONS_MGMT);
        customerJwt = probe.acquireCustomerJwt();
    }

    // -- A4 PSD2 -------------------------------------------------------

    @Test
    void psd2_listAccounts_returns200WithList() {
        RestClient psd2 = bearerClient(PSD2_BASE, customerJwt);
        Object body = psd2.get()
                .uri("/api/v1/customer/accounts")
                .retrieve()
                .body(Object.class);
        assertTrue(body instanceof List, "/accounts must return a JSON array; got " + body);
    }

    @Test
    void psd2_listPayments_returns200() {
        RestClient psd2 = bearerClient(PSD2_BASE, customerJwt);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = psd2.get()
                .uri("/api/v1/customer/payments?page=0&size=10")
                .retrieve()
                .body(Map.class);
        assertNotNull(body);
        assertTrue(body.containsKey("items"));
        assertTrue(body.containsKey("page"));
    }

    // -- A3 Bill-Pay ---------------------------------------------------

    @Test
    void billpay_listR2p_returns200() {
        RestClient billpay = bearerClient(BILLPAY_BASE, customerJwt);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = billpay.get()
                .uri("/api/v1/customer/r2p?page=0&size=10")
                .retrieve()
                .body(Map.class);
        assertNotNull(body);
        assertTrue(body.containsKey("items"));
        assertTrue(body.containsKey("page"));
    }

    @Test
    void billpay_listMandates_returns200() {
        RestClient billpay = bearerClient(BILLPAY_BASE, customerJwt);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = billpay.get()
                .uri("/api/v1/customer/mandates?page=0&size=10")
                .retrieve()
                .body(Map.class);
        assertNotNull(body);
        assertTrue(body.containsKey("items"));
        assertTrue(body.containsKey("page"));
    }

    @Test
    void billpay_searchAvtaleGiroMerchants_returnsSeededDemoEntries() {
        RestClient billpay = bearerClient(BILLPAY_BASE, customerJwt);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = billpay.get()
                .uri("/api/v1/customer/mandates/merchants?q=Hafs")
                .retrieve()
                .body(Map.class);
        assertNotNull(body);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        assertNotNull(items);
        // Demo seed includes "Hafslund Strøm AS"
        assertTrue(items.stream().anyMatch(i -> "Hafslund Strøm AS".equals(i.get("displayName"))),
                "Expected the demo Hafslund merchant in the registry; got: " + items);
    }

    @Test
    void billpay_listBeneficiaries_returns200() {
        RestClient billpay = bearerClient(BILLPAY_BASE, customerJwt);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = billpay.get()
                .uri("/api/v1/customer/beneficiaries?page=0&size=10")
                .retrieve()
                .body(Map.class);
        assertNotNull(body);
        assertTrue(body.containsKey("items"));
        assertTrue(body.containsKey("page"));
    }

    // -- Notifications -------------------------------------------------

    @Test
    void notifications_listInbox_returns200() {
        RestClient notifs = bearerClient(NOTIFICATIONS_BASE, customerJwt);
        Object body = notifs.get()
                .uri("/api/v1/customer/notifications?page=0&size=10")
                .retrieve()
                .body(Object.class);
        assertNotNull(body);
        // The shape is {items, page} but we don't pin the exact keys here;
        // the existence of a JSON body proves the controller + auth + DB chain.
    }
}
