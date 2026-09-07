package com.jrpay.psd2.customer.payments.vop;

import com.fasterxml.jackson.databind.JsonNode;
import com.jrpay.psd2.customer.CustomerProblemException;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * Client for A6 (VoP) — proxies the customer-facing
 * {@code POST /api/v1/customer/payments/vop} call.
 */
@Component
public class VoPClient {

    private static final Logger log = LoggerFactory.getLogger(VoPClient.class);

    private final RestClient http;
    private final String vopBaseUrl;

    public VoPClient(RestClient.Builder builder,
                     @Value("${jrpay.psd2.vop.base-url:http://localhost:8096}") String vopBaseUrl) {
        this.http = builder.build();
        this.vopBaseUrl = vopBaseUrl;
    }

    public Result check(String iban, String statedName) {
        try {
            JsonNode response = http.post()
                    .uri(URI.create(vopBaseUrl + "/api/v1/vop/check"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("iban", iban, "name", statedName))
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw CustomerProblemException.identityUnavailable("VoP returned empty body");
            }
            String result = response.path("result").asText();
            String suggestion = response.path("nameSuggestion").isMissingNode()
                    ? null : response.path("nameSuggestion").asText(null);
            return new Result(result, suggestion);
        } catch (HttpStatusCodeException e) {
            log.warn("VoP /check rejected: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw CustomerProblemException.identityUnavailable(
                    "VoP service " + e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("VoP call failed", e);
            throw CustomerProblemException.identityUnavailable(
                    "VoP service unreachable: " + e.getMessage());
        }
    }

    public record Result(String result, String actualName) { }
}
