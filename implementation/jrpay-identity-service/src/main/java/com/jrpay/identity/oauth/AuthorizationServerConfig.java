package com.jrpay.identity.oauth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.http.MediaType;
import com.jrpay.identity.domain.IdentityUser;
import com.jrpay.identity.repository.IdentityUserRepository;
import org.springframework.security.core.Authentication;

/**
 * Spring Authorization Server wiring.
 *
 * <p>Two security chains:
 * <ol>
 *   <li>{@code authorizationServerSecurityFilterChain} — OAuth2 protocol
 *       endpoints ({@code /oauth2/authorize}, {@code /oauth2/token},
 *       {@code /oauth2/revoke}, {@code /oauth2/introspect}, OIDC discovery,
 *       JWKS). Form-login fallback covers the authorize redirect.
 *   <li>{@link DefaultIdentitySecurityConfig} — non-protocol endpoints
 *       ({@code /sca/*}, {@code /api/v1/*}, password-reset). Permits the
 *       ones that must be open ({@code /api/v1/public/**},
 *       {@code /oauth2/password-reset/request}) and gates the rest by
 *       resource-server JWT.
 * </ol>
 *
 * <p>The signing key is generated in-memory at startup. Production deployments
 * front-load via KMS / HSM (the {@link KeyPair} bean is the seam).
 */
@Configuration
public class AuthorizationServerConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
                .oidc(Customizer.withDefaults());
        http.exceptionHandling(exceptions ->
                exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
                )
        );
        // Resource-server config so /oauth2/revoke + /oauth2/introspect can
        // accept a bearer JWT that this same server issued.
        http.oauth2ResourceServer((OAuth2ResourceServerConfigurer<HttpSecurity> rs) ->
                rs.jwt(Customizer.withDefaults())
        );
        return http.build();
    }

    @Bean
    public KeyPair identitySigningKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA not available; required for JWT signing", e);
        }
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(KeyPair keyPair) {
        RSAKey rsa = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .algorithm(JWSAlgorithm.RS256)
                .build();
        return new ImmutableJWKSet<>(new JWKSet(rsa));
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(
            @Value("${jrpay.identity.issuer:http://localhost:8097}") String issuer) {
        return AuthorizationServerSettings.builder()
                .issuer(issuer)
                .build();
    }

    /**
     * Adds JRPay-specific claims to the access token: {@code aud}
     * (customer | admin), {@code acr} (loa3 / loa3-admin), {@code jrpay_uid}
     * (the {@link IdentityUser#getUserId()} UUID — the resource servers'
     * {@code AuthenticatedUser} helper reads this in preference to {@code sub}
     * because {@code sub} carries the username/email), and {@code role} when
     * the principal has one (operators only).
     *
     * <p>For client_credentials grants there is no user principal, so
     * {@code jrpay_uid} and {@code role} are skipped.
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer(IdentityUserRepository users) {
        return (JwtEncodingContext context) -> {
            if (!OAuth2Token.class.isAssignableFrom(context.getTokenType().getClass())) {
                return;
            }
            // Audience + ACR derived from the registered client.
            var registered = context.getRegisteredClient();
            String audience = "customer";
            if (registered != null) {
                String clientName = registered.getClientName() == null
                        ? registered.getClientId() : registered.getClientName();
                context.getClaims().claims(claims -> claims.put("client_name", clientName));
                if (clientName.toLowerCase().contains("admin")) {
                    audience = "admin";
                }
                context.getClaims().audience(java.util.List.of(audience));
                context.getClaims().claim("acr",
                        "admin".equals(audience) ? "loa3-admin" : "loa3");
            }

            // User-bound claims: jrpay_uid + role. The principal is the
            // user's Authentication (authorization_code grant); for
            // client_credentials it is the client itself with no associated
            // human user — skip.
            Authentication principal = context.getPrincipal();
            if (principal == null) return;
            Object principalDetails = principal.getPrincipal();
            if (principalDetails == null) return;
            String username = principal.getName();
            if (username == null) return;

            // Skip when the principal is the registered client itself.
            if (registered != null && username.equals(registered.getClientId())) return;

            IdentityUser.Audience aud = "admin".equals(audience)
                    ? IdentityUser.Audience.admin
                    : IdentityUser.Audience.customer;
            users.findByEmail(username)
                    .or(() -> users.findByAudienceAndUsername(aud, username))
                    .ifPresent(u -> {
                        context.getClaims().claim("jrpay_uid", u.getUserId().toString());
                        if (u.getRole() != null) {
                            context.getClaims().claim("role", u.getRole());
                        }
                    });
        };
    }
}
