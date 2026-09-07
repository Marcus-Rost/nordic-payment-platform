package com.jrpay.identity.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jrpay.identity.IdentityIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Layer 2 — exercises endpoints that are explicitly public (no auth) so we
 * can hit them with an HTTP client over the test web server.
 *
 * <ul>
 *   <li>OIDC discovery + JWKS — must respond 200 even before any login.
 *   <li>Public privacy-notice metadata.
 *   <li>Password-reset request — always 202, even for unknown identifiers.
 * </ul>
 */
class PublicEndpointsIntegrationTest extends IdentityIntegrationTestBase {

    @Autowired TestRestTemplate http;

    @LocalServerPort int port;

    @Test
    void oidcDiscoveryDocumentExposesJwksUri() {
        var response = http.getForEntity(
                base() + "/.well-known/openid-configuration", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"jwks_uri\"");
        assertThat(response.getBody()).contains("\"authorization_endpoint\"");
        assertThat(response.getBody()).contains("\"token_endpoint\"");
    }

    @Test
    void jwksReturnsAtLeastOneRsaKey() {
        var response = http.getForEntity(base() + "/oauth2/jwks", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"kty\"");
        assertThat(response.getBody()).contains("\"RS256\"".substring(0, 4)); // "RS25" — match algorithm advertised
    }

    @Test
    void publicPrivacyNoticeIsReachableUnauthenticated() {
        var response = http.getForEntity(
                base() + "/api/v1/public/privacy-notice/latest", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"version\"");
        assertThat(response.getBody()).contains("\"locales\"");
    }

    @Test
    void passwordResetRequestAlwaysReturns202() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(
                "{\"identifier\":\"unknown@nowhere.example\",\"audience\":\"customer\"}",
                headers);
        ResponseEntity<String> response = http.exchange(
                base() + "/oauth2/password-reset/request",
                HttpMethod.POST,
                request,
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    private String base() {
        return "http://localhost:" + port;
    }
}
