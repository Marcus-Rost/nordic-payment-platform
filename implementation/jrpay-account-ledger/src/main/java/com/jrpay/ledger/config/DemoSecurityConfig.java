package com.jrpay.ledger.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Demo security with HTTP Basic auth and in-memory test users.
 * Active with 'docker' or 'demo' profile.
 *
 * <p>The chain has two ordered filter chains so the demo profile can serve
 * both the legacy {@code /api/v1/accounts/*} endpoints (HTTP Basic for
 * service-to-service / smoke-test traffic) AND the new {@code /api/v1/admin/*}
 * surface (Bearer JWT issued by identity, used by the admin SPA + the
 * GUI-tier smoke tests). Without the JWT chain the admin SPA cannot drive A1
 * features like PII reveal that depend on {@code currentUserId()} from the
 * authenticated principal.
 *
 * <p>Production-profile {@link LedgerSecurityConfig} is JWT-only and remains
 * the canonical chain for non-demo deployments.
 */
@Configuration
@Profile({"docker", "demo"})
@EnableMethodSecurity
public class DemoSecurityConfig {

    /**
     * JWT chain — bound to the admin surface and the customer surface, so
     * the admin SPA's Bearer token works on /api/v1/admin/* and the
     * customer SPA's token works on /api/v1/customer/*.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain jwtFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/v1/admin/**", "/api/v1/customer/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt
                        .jwtAuthenticationConverter(jwtAuthenticationConverter())
                ))
                .csrf(csrf -> csrf.disable())
                .build();
    }

    /**
     * Legacy Basic chain — covers everything else, notably the demo
     * {@code /api/v1/accounts/*} endpoints used by the smoke-test base class
     * and by service-to-service callers (a3-service, a6-service).
     */
    @Bean
    @Order(2)
    public SecurityFilterChain demoSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**").permitAll()
                        .anyRequest().authenticated()
                )
                .httpBasic(basic -> {})
                .csrf(csrf -> csrf.disable())
                .build();
    }

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${jrpay.ledger.jwt.jwks-uri:http://identity:8097/oauth2/jwks}") String jwksUri) {
        return NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        scopes.setAuthoritiesClaimName("scope");
        scopes.setAuthorityPrefix("SCOPE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(scopes);
        return converter;
    }

    @Bean
    public UserDetailsService demoUsers() {
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(
                User.withUsername("admin")
                        .password(encoder.encode("admin"))
                        .roles("ADMIN")
                        .build(),
                User.withUsername("a3-service")
                        .password(encoder.encode("a3-service"))
                        .roles("SERVICE")
                        .build(),
                User.withUsername("a6-service")
                        .password(encoder.encode("a6-service"))
                        .roles("SERVICE")
                        .build()
        );
    }
}
