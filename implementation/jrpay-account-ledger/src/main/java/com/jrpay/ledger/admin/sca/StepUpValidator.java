package com.jrpay.ledger.admin.sca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * Validates an operator-presented step-up token by calling the identity
 * service. Acquires a service access token via client_credentials
 * (cached until 30s before expiry).
 *
 * <p>Outcomes map to {@link Outcome}; the controller layer converts these
 * to the corresponding RFC 9457 problem responses.
 */
@Component
public class StepUpValidator {

    private static final Logger log = LoggerFactory.getLogger(StepUpValidator.class);

    private final RestClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String identityBaseUrl;
    private final String clientId;
    private final String clientSecret;
    private final Duration safetyMargin = Duration.ofSeconds(30);

    private volatile String cachedAccessToken;
    private volatile Instant cachedAccessTokenExpiry = Instant.EPOCH;

    public StepUpValidator(
            RestClient.Builder builder,
            @Value("${jrpay.ledger.identity.base-url:http://localhost:8097}") String identityBaseUrl,
            @Value("${jrpay.ledger.identity.client-id:jrpay-a1-service}") String clientId,
            @Value("${jrpay.ledger.identity.client-secret:a1-service-secret}") String clientSecret) {
        this.http = builder.build();
        this.identityBaseUrl = identityBaseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public ValidationResult validate(String stepUpToken, String expectedDlHash) {
        if (stepUpToken == null || stepUpToken.isBlank()) {
            return new ValidationResult(false, Outcome.MISSING, null, null, "Step-Up-Token header missing");
        }
        try {
            String accessToken = getServiceAccessToken();
            Map<String, String> body = Map.of(
                    "token", stepUpToken,
                    "expectedDlHash", expectedDlHash);
            JsonNode response = http.post()
                    .uri(URI.create(identityBaseUrl + "/sca/step-up-tokens/validate"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response != null && response.path("valid").asBoolean(false)) {
                String userId = response.path("userId").asText(null);
                String scaSessionId = response.path("scaSessionId").asText(null);
                return new ValidationResult(true, Outcome.VALID,
                        parseUuid(userId),
                        parseUuid(scaSessionId),
                        null);
            }
            return new ValidationResult(false, Outcome.NOT_FOUND, null, null,
                    "Identity service returned valid=false without status code");
        } catch (HttpStatusCodeException e) {
            return mapHttpError(e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("Step-up validation call failed", e);
            return new ValidationResult(false, Outcome.IDENTITY_ERROR, null, null,
                    "Identity service unreachable: " + e.getMessage());
        }
    }

    private ValidationResult mapHttpError(HttpStatusCode status, String responseBody) {
        Outcome outcome;
        if (status.equals(HttpStatus.UNAUTHORIZED)) {
            outcome = parseProblemType(responseBody, Outcome.NOT_FOUND);
        } else if (status.equals(HttpStatus.FORBIDDEN)) {
            outcome = Outcome.DL_HASH_MISMATCH;
        } else {
            outcome = Outcome.IDENTITY_ERROR;
        }
        return new ValidationResult(false, outcome, null, null,
                "Identity service " + status.value() + ": " + responseBody);
    }

    private Outcome parseProblemType(String body, Outcome fallback) {
        if (body == null || body.isBlank()) return fallback;
        try {
            JsonNode node = mapper.readTree(body);
            String type = node.path("type").asText("");
            if (type.contains("dynamic-linking-mismatch")) return Outcome.DL_HASH_MISMATCH;
            if (type.contains("step-up-required")) return Outcome.NOT_FOUND;
        } catch (Exception ignored) { }
        return fallback;
    }

    private String getServiceAccessToken() {
        Instant now = Instant.now();
        String cached = cachedAccessToken;
        if (cached != null && now.isBefore(cachedAccessTokenExpiry.minus(safetyMargin))) {
            return cached;
        }
        String basic = Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> response = http.post()
                .uri(URI.create(identityBaseUrl + "/oauth2/token"))
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials&scope=sca:validate")
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
        if (response == null || response.get("access_token") == null) {
            throw new IllegalStateException("Identity token endpoint returned no access_token");
        }
        String token = String.valueOf(response.get("access_token"));
        Object expiresIn = response.get("expires_in");
        long secs = (expiresIn instanceof Number n) ? n.longValue() : 900L;
        this.cachedAccessToken = token;
        this.cachedAccessTokenExpiry = now.plusSeconds(secs);
        return token;
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (Exception e) { return null; }
    }

    /** Reset the cached service-access token (for tests). */
    void invalidateAccessToken() {
        this.cachedAccessToken = null;
        this.cachedAccessTokenExpiry = Instant.EPOCH;
    }

    public enum Outcome { VALID, MISSING, NOT_FOUND, DL_HASH_MISMATCH, IDENTITY_ERROR }

    public record ValidationResult(boolean valid, Outcome outcome, UUID userId,
                                   UUID scaSessionId, String detail) {

        public static ValidationResult missing() {
            return new ValidationResult(false, Outcome.MISSING, null, null,
                    "Step-Up-Token header missing");
        }
    }

    /** Test seam — overrides for fakes. */
    void primeForTest(String accessToken, Instant expiry) {
        this.cachedAccessToken = accessToken;
        this.cachedAccessTokenExpiry = expiry;
    }

    /** Build an admin-action canonical hash payload for the controller layer. */
    public static Map<String, Object> adminActionPayload(String action, String resourceType,
                                                        String resourceId, String decision,
                                                        String notesHash) {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("class", "admin-action");
        ordered.put("action", action);
        ordered.put("resourceType", resourceType);
        ordered.put("resourceId", resourceId);
        if (decision != null) ordered.put("decision", decision);
        ordered.put("notesHash", notesHash);
        return ordered;
    }
}
