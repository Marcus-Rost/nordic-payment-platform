package com.jrpay.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Smoke tests for jrpay-identity-service public surfaces + the dev-token
 * endpoint that downstream smoke tests rely on. Skipped gracefully when
 * identity-service isn't reachable.
 */
class IdentitySmokeTest extends JwtSmokeTestBase {

    @BeforeEach
    void requireIdentity() {
        Assumptions.assumeTrue(isServiceUp(IDENTITY_MGMT),
                "identity-service not reachable at " + IDENTITY_MGMT);
    }

    @Test
    void oidcDiscoveryDocumentExposesJwksAndTokenEndpoints() {
        String body = identityPlain.get()
                .uri("/.well-known/openid-configuration")
                .retrieve()
                .body(String.class);
        assertNotNull(body);
        assertTrue(body.contains("\"jwks_uri\""), "discovery should advertise jwks_uri");
        assertTrue(body.contains("\"authorization_endpoint\""),
                "discovery should advertise authorization_endpoint");
        assertTrue(body.contains("\"token_endpoint\""),
                "discovery should advertise token_endpoint");
    }

    @Test
    void jwksReturnsAtLeastOneRsaSigningKey() {
        String body = identityPlain.get()
                .uri("/oauth2/jwks")
                .retrieve()
                .body(String.class);
        assertNotNull(body);
        assertTrue(body.contains("\"kty\""), "JWKS must contain at least one key");
        assertTrue(body.contains("\"RS256\""), "Identity signs with RS256");
    }

    @Test
    void publicPrivacyNoticeIsReachableUnauthenticated() {
        String body = identityPlain.get()
                .uri("/api/v1/public/privacy-notice/latest")
                .retrieve()
                .body(String.class);
        assertNotNull(body);
        assertTrue(body.contains("\"version\""));
        assertTrue(body.contains("\"locales\""));
    }

    @Test
    void passwordResetRequestAlwaysReturns202() {
        HttpStatusCode status = identityPlain.post()
                .uri("/oauth2/password-reset/request")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "identifier", "unknown@nowhere.example",
                        "audience", "customer"))
                .retrieve()
                .toBodilessEntity()
                .getStatusCode();
        assertEquals(HttpStatus.ACCEPTED, status,
                "Password-reset request must always 202 (no enumeration leak)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void devTokenForCustomerCarriesExpectedClaims() {
        String jwt = acquireCustomerJwt();
        assertNotNull(jwt);
        // Decode payload (middle segment, base64url-no-pad).
        String payload = decodeJwtPayload(jwt);
        assertTrue(payload.contains("\"aud\""), "JWT must carry aud claim");
        assertTrue(payload.contains("customer"),
                "Customer JWT must declare audience=customer; got: " + payload);
        assertTrue(payload.contains("\"acr\":\"loa3\""),
                "Customer JWT must declare acr=loa3; got: " + payload);
        assertTrue(payload.contains("jrpay_uid"),
                "Customer JWT must carry jrpay_uid; got: " + payload);
    }

    @Test
    void devTokenForAdminCarriesAdminAudience() {
        String jwt = acquireAdminJwt();
        String payload = decodeJwtPayload(jwt);
        assertTrue(payload.contains("admin"),
                "Admin JWT must declare audience=admin; got: " + payload);
        assertTrue(payload.contains("\"acr\":\"loa3-admin\""),
                "Admin JWT must declare acr=loa3-admin; got: " + payload);
        assertTrue(payload.contains("\"role\""),
                "Admin JWT must carry role claim; got: " + payload);
    }

    @Test
    void devTokenWithBadCredentialsRejected() {
        try {
            RestClient.create(IDENTITY_BASE).post()
                    .uri("/dev/oauth2/dev-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "username", CUSTOMER_USERNAME,
                            "password", "obviously-wrong",
                            "audience", "customer"))
                    .retrieve()
                    .body(String.class);
            org.junit.jupiter.api.Assertions.fail("Bad credentials should not yield a token");
        } catch (Exception e) {
            // Expected — controller throws IllegalArgumentException → 500/400.
            assertTrue(e.getMessage() != null && !e.getMessage().isBlank());
        }
    }

    private static String decodeJwtPayload(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            throw new IllegalStateException("Not a JWT: " + jwt);
        }
        return new String(java.util.Base64.getUrlDecoder().decode(parts[1]),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
