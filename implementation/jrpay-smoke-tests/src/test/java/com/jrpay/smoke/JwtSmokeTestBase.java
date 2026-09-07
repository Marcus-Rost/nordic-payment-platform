package com.jrpay.smoke;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Base for Phase-2 GUI-tier smoke tests. Acquires JWTs from identity-service
 * via the dev-only token endpoint, then exposes a typed {@link RestClient}
 * per downstream service. Service URLs default to the docker-compose host
 * mappings; override with system properties for any other deployment.
 *
 * <p>Each {@link #isServiceUp(String)} probe lets a test gracefully skip via
 * {@code Assumptions.assumeTrue(...)} when the target service isn't running —
 * matching the pattern in {@link SmokeTestBase}.
 */
abstract class JwtSmokeTestBase {

    static final String IDENTITY_BASE  = System.getProperty("identity.url",  "http://localhost:8097");
    static final String IDENTITY_MGMT  = System.getProperty("identity.mgmt", "http://localhost:9097");

    static final String LEDGER_BASE    = System.getProperty("a1.url", "http://localhost:8091");
    static final String LEDGER_MGMT    = System.getProperty("a1.mgmt.url", "http://localhost:9091");

    static final String BILLPAY_BASE   = System.getProperty("a3.url",  "http://localhost:8093");
    static final String BILLPAY_MGMT   = System.getProperty("a3.mgmt", "http://localhost:9093");

    static final String PSD2_BASE      = System.getProperty("a4.url",  "http://localhost:8094");
    static final String PSD2_MGMT      = System.getProperty("a4.mgmt", "http://localhost:9094");

    static final String FRAUD_BASE     = System.getProperty("a5.url",  "http://localhost:8095");
    static final String FRAUD_MGMT     = System.getProperty("a5.mgmt", "http://localhost:9095");

    static final String NOTIFICATIONS_BASE = System.getProperty("notifications.url",  "http://localhost:8098");
    static final String NOTIFICATIONS_MGMT = System.getProperty("notifications.mgmt", "http://localhost:9098");

    static final String AUDIT_BASE     = System.getProperty("audit.url",  "http://localhost:8099");
    static final String AUDIT_MGMT     = System.getProperty("audit.mgmt", "http://localhost:9099");

    static final String CUSTOMER_USERNAME = "ola.nordmann@example.com";
    static final String CUSTOMER_PASSWORD = "demo-password-1!";
    static final String ADMIN_USERNAME    = "admin@jrpay.example";
    static final String ADMIN_PASSWORD    = "demo-password-1!";

    final RestClient identityPlain = RestClient.builder().baseUrl(IDENTITY_BASE).build();

    String acquireCustomerJwt() {
        return acquireDevToken(CUSTOMER_USERNAME, CUSTOMER_PASSWORD, "customer");
    }

    String acquireAdminJwt() {
        return acquireDevToken(ADMIN_USERNAME, ADMIN_PASSWORD, "admin");
    }

    @SuppressWarnings("unchecked")
    String acquireDevToken(String username, String password, String audience) {
        Map<String, Object> response = identityPlain.post()
                .uri("/dev/oauth2/dev-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "username", username,
                        "password", password,
                        "audience", audience))
                .retrieve()
                .body(Map.class);
        if (response == null || response.get("access_token") == null) {
            throw new IllegalStateException("dev-token endpoint returned no access_token");
        }
        return String.valueOf(response.get("access_token"));
    }

    static RestClient bearerClient(String baseUrl, String jwt) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeaders(h -> h.setBearerAuth(jwt))
                .build();
    }

    static String idempotencyKey() {
        return UUID.randomUUID().toString();
    }

    boolean isServiceUp(String managementUrl) {
        try {
            String body = RestClient.create(managementUrl).get()
                    .uri("/actuator/health")
                    .retrieve()
                    .body(String.class);
            return body != null;
        } catch (Exception e) {
            return false;
        }
    }
}
