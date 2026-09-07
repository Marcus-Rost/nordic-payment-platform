package com.jrpay.psd2.customer.sca;

import com.fasterxml.jackson.databind.JsonNode;
import com.jrpay.psd2.customer.config.AuthenticatedUser;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * Forwards the customer's bearer JWT to identity-service {@code /sca/start}
 * to begin a new SCA session bound to the supplied input map (the {@code class}
 * member is the {@code DYNAMIC_LINKING.md} discriminator).
 */
@Component
public class ScaInitiator {

    private static final Logger log = LoggerFactory.getLogger(ScaInitiator.class);

    private final RestClient http;
    private final String identityBaseUrl;
    private final AuthenticatedUser caller;

    public ScaInitiator(RestClient.Builder builder,
                        @Value("${jrpay.psd2.identity.base-url:http://localhost:8097}") String identityBaseUrl,
                        AuthenticatedUser caller) {
        this.http = builder.build();
        this.identityBaseUrl = identityBaseUrl;
        this.caller = caller;
    }

    public InitiateResult start(Map<String, Object> inputs, boolean exempt) {
        String bearer = caller.currentRawJwt()
                .orElseThrow(() -> new IllegalStateException("No bearer JWT in security context"));
        try {
            JsonNode response = http.post()
                    .uri(URI.create(identityBaseUrl + "/sca/start"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("inputs", inputs, "exempt", exempt))
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new IllegalStateException("Identity /sca/start returned empty body");
            }
            UUID scaSessionId = UUID.fromString(response.path("scaSessionId").asText());
            String dlHash = response.path("dl_hash").asText();
            String factorType = response.path("challenge").path("type").asText(null);
            boolean isExempt = response.path("exempt").asBoolean(false);
            String stepUpToken = isExempt ? response.path("stepUpToken").asText(null) : null;
            return new InitiateResult(scaSessionId, dlHash, factorType, isExempt, stepUpToken);
        } catch (HttpStatusCodeException e) {
            log.warn("Identity /sca/start rejected: {} {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new ScaInitiationException(
                    "Identity /sca/start " + e.getStatusCode().value(), e);
        } catch (Exception e) {
            log.warn("Identity /sca/start call failed", e);
            throw new ScaInitiationException("Identity /sca/start unreachable", e);
        }
    }

    public record InitiateResult(UUID scaSessionId, String dlHash, String factorType,
                                 boolean exempt, String stepUpToken) { }

    public static class ScaInitiationException extends RuntimeException {
        public ScaInitiationException(String message, Throwable cause) { super(message, cause); }
    }
}
