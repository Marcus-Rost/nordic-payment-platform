package com.jrpay.vop.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Optional;

/**
 * REST client for A1 Account Ledger — fetches holder names on cache miss.
 * Per INTERFACE_CONTRACTS Section 3.2: GET /api/v1/accounts/{accountId}/holder-name
 */
@Component
public class AccountLedgerClient {

    private static final Logger log = LoggerFactory.getLogger(AccountLedgerClient.class);

    private final RestClient restClient;

    public AccountLedgerClient(
            @Value("${jrpay.vop.a1-client.base-url:http://localhost:8091}") String baseUrl,
            @Value("${jrpay.vop.a1-client.username:admin}") String username,
            @Value("${jrpay.vop.a1-client.password:admin}") String password) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeaders(headers -> headers.setBasicAuth(username, password))
                .build();
    }

    /**
     * Fetch holder name from A1 for a given IBAN.
     *
     * @return the cache entry if found, empty if not found or error
     */
    public Optional<NameCacheEntry> fetchHolderName(String iban) {
        try {
            HolderNameResponse response = restClient.get()
                    .uri("/api/v1/accounts/{accountId}/holder-name", iban)
                    .retrieve()
                    .body(HolderNameResponse.class);

            if (response != null && response.holderName() != null) {
                return Optional.of(NameCacheEntry.of(
                        response.accountId(), response.holderName(), Instant.now()));
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to fetch holder name for IBAN {}: {}", iban, e.getMessage());
            return Optional.empty();
        }
    }

    record HolderNameResponse(String accountId, String holderName, String lastUpdated) {}
}
