package com.jrpay.identity.oauth;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/**
 * Loads the {@code identity_oauth_clients} table into a
 * {@link RegisteredClientRepository}. The DB row is the source of truth; the
 * in-memory map is a hot-path cache. Re-loaded on app startup; runtime CRUD
 * for client registration is out of scope here (UC-G43 covers operator users
 * but not OAuth client registration — that's an ops task).
 */
@Configuration
public class OAuthClientLoader {

    private static final Logger log = LoggerFactory.getLogger(OAuthClientLoader.class);

    private final JdbcTemplate jdbc;

    public OAuthClientLoader(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository() {
        var clients = jdbc.query(
                "SELECT client_id, client_secret_hash, client_name, audience, "
                        + "grant_types, redirect_uris, scopes, require_pkce, require_consent, "
                        + "access_token_ttl_sec, refresh_token_ttl_sec FROM identity_oauth_clients",
                (rs, idx) -> {
                    String id = rs.getString("client_id");
                    String secret = rs.getString("client_secret_hash");
                    String name = rs.getString("client_name");
                    String audience = rs.getString("audience");
                    String grants = rs.getString("grant_types");
                    String redirects = rs.getString("redirect_uris");
                    String scopes = rs.getString("scopes");
                    boolean pkce = rs.getBoolean("require_pkce");
                    boolean consent = rs.getBoolean("require_consent");
                    int accessTtl = rs.getInt("access_token_ttl_sec");
                    int refreshTtl = rs.getInt("refresh_token_ttl_sec");
                    RegisteredClient.Builder b = RegisteredClient.withId(UUID.randomUUID().toString())
                            .clientId(id)
                            .clientName(name);
                    if (secret == null) {
                        b.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
                    } else {
                        b.clientSecret(secret)
                                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
                    }
                    splitCsv(grants).forEach(g -> b.authorizationGrantType(new AuthorizationGrantType(g)));
                    splitCsv(redirects).forEach(b::redirectUri);
                    splitWhitespace(scopes).forEach(b::scope);
                    if (Arrays.asList(scopes.split("\\s+")).contains(OidcScopes.OPENID)) {
                        b.scope(OidcScopes.OPENID);
                    }
                    b.clientSettings(ClientSettings.builder()
                            .requireProofKey(pkce)
                            .requireAuthorizationConsent(consent)
                            .build());
                    b.tokenSettings(TokenSettings.builder()
                            .accessTokenTimeToLive(Duration.ofSeconds(accessTtl))
                            .refreshTokenTimeToLive(Duration.ofSeconds(refreshTtl))
                            .reuseRefreshTokens(false)
                            .build());
                    return b.build();
                });
        log.info("Loaded {} OAuth2 registered clients from identity_oauth_clients", clients.size());
        return new InMemoryRegisteredClientRepository(clients);
    }

    @PostConstruct
    void selfCheck() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM identity_oauth_clients", Long.class);
        if (count == null || count == 0) {
            log.warn("identity_oauth_clients is empty — OAuth2 endpoints will reject every client");
        }
    }

    private static Collection<String> splitCsv(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static Collection<String> splitWhitespace(String value) {
        return Arrays.stream(value.split("\\s+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
