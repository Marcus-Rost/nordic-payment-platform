package com.jrpay.billpay.sca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jrpay.billpay.config.AuthenticatedUser;
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
 * Initiates an SCA session in identity-service on behalf of the customer.
 * The caller's bearer JWT is forwarded; identity-service uses it to identify
 * the user (via {@code jrpay_uid} / {@code sub}) and to create the session.
 *
 * <p>Returned record carries the {@code scaSessionId}, the canonical
 * {@code dl_hash} (so A3 can persist a binding), the chosen factor, and
 * an {@code exempt}/{@code stepUpToken} pair for the PSD2-RTS exemption case
 * (low-value payments, trusted beneficiary, etc.).
 */
@Component
public class ScaInitiator {

    private static final Logger log = LoggerFactory.getLogger(ScaInitiator.class);

    private final RestClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String identityBaseUrl;
    private final AuthenticatedUser caller;

    public ScaInitiator(RestClient.Builder builder,
                        @Value("${jrpay.billpay.identity.base-url:http://localhost:8097}") String identityBaseUrl,
                        AuthenticatedUser caller) {
        this.http = builder.build();
        this.identityBaseUrl = identityBaseUrl;
        this.caller = caller;
    }

    /**
     * Start an SCA session bound to the supplied input map. The map is
     * serialised to JSON and forwarded as-is in the {@code inputs} field of
     * the {@code /sca/start} request. The {@code class} member of {@code inputs}
     * is the discriminator the identity service uses to compute the
     * {@code dl_hash} per {@code DYNAMIC_LINKING.md}.
     */
    public InitiateResult start(Map<String, Object> inputs, boolean exempt) {
        String bearer = caller.currentRawJwt()
                .orElseThrow(() -> new IllegalStateException("No bearer JWT in security context"));
        Map<String, Object> body = Map.of(
                "inputs", inputs,
                "exempt", exempt);
        try {
            JsonNode response = http.post()
                    .uri(URI.create(identityBaseUrl + "/sca/start"))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
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
            log.warn("Identity /sca/start rejected request: {} {}",
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
        public ScaInitiationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
