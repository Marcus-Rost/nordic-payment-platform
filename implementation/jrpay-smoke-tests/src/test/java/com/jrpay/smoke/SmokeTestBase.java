package com.jrpay.smoke;

import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Base class for smoke tests against running services.
 *
 * Prerequisites: A1 (port 8091) and A6 (port 8096) must be running
 * with the 'docker' profile (HTTP Basic auth with admin/admin).
 *
 * These tests verify real HTTP calls, real auth, real database —
 * the dimension that unit tests cannot cover.
 */
abstract class SmokeTestBase {

    static final String A1_BASE = System.getProperty("a1.url", "http://localhost:8091");
    static final String A6_BASE = System.getProperty("a6.url", "http://localhost:8096");
    static final String A1_MGMT = System.getProperty("a1.mgmt.url", "http://localhost:9091");
    static final String A6_MGMT = System.getProperty("a6.mgmt.url", "http://localhost:9096");
    static final String USERNAME = "admin";
    static final String PASSWORD = "admin";

    final RestClient a1Client = RestClient.builder()
            .baseUrl(A1_BASE)
            .defaultHeaders(h -> h.setBasicAuth(USERNAME, PASSWORD))
            .build();

    final RestClient a6Client = RestClient.builder()
            .baseUrl(A6_BASE)
            .defaultHeaders(h -> h.setBasicAuth(USERNAME, PASSWORD))
            .build();

    final RestClient unauthenticatedClient = RestClient.builder()
            .baseUrl(A1_BASE)
            .build();

    /**
     * Create an account via A1 REST API.
     */
    Map<String, Object> createAccount(String accountId, String holderName,
                                      String currency, double initialBalance) {
        return a1Client.post()
                .uri("/api/v1/accounts")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "accountId", accountId,
                        "holderName", holderName,
                        "currency", currency,
                        "initialBalance", initialBalance
                ))
                .retrieve()
                .body(Map.class);
    }

    /**
     * Get account balances via A1 REST API.
     */
    Map<String, Object> getBalances(String accountId) {
        return a1Client.get()
                .uri("/api/v1/accounts/{id}/balances", accountId)
                .retrieve()
                .body(Map.class);
    }

    /**
     * Execute a payment transfer via A1 REST API.
     */
    Map<String, Object> transfer(String txnId, String debtorId, String creditorId,
                                 double amount, String currency) {
        return a1Client.post()
                .uri("/api/v1/accounts/transfer")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "transactionId", txnId,
                        "debtorAccountId", debtorId,
                        "creditorAccountId", creditorId,
                        "amount", amount,
                        "currency", currency,
                        "description", "Smoke test payment"
                ))
                .retrieve()
                .body(Map.class);
    }

    /**
     * VoP verify via A6 REST API.
     */
    Map<String, Object> vopVerify(String iban, String statedName, String requestId) {
        return a6Client.post()
                .uri("/api/v1/vop/verify")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "beneficiaryIban", iban,
                        "statedName", statedName,
                        "requestId", requestId
                ))
                .retrieve()
                .body(Map.class);
    }

    /**
     * Check if a service is reachable via its management port.
     */
    boolean isServiceUp(String managementUrl) {
        try {
            RestClient.create(managementUrl).get()
                    .uri("/actuator/health")
                    .retrieve()
                    .body(String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
