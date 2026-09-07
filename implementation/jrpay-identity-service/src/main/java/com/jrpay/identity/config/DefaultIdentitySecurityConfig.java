package com.jrpay.identity.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Non-OAuth security chain for the identity service.
 *
 * <ul>
 *   <li>{@code /actuator/health}, {@code /actuator/readiness},
 *       {@code /actuator/liveness}, {@code /actuator/prometheus} — open.
 *   <li>{@code /api/v1/public/**} — open by convention.
 *   <li>{@code /oauth2/password-reset/request}, {@code /oauth2/password-reset/verify},
 *       {@code /oauth2/password-reset/complete} — open (the reset flow is
 *       its own auth path; the URL token is the credential).
 *   <li>{@code /login} — open (form-login fallback).
 *   <li>Everything else under this chain (e.g. {@code /sca/*},
 *       {@code /api/v1/customer/**}) requires a JWT.
 * </ul>
 *
 * <p>Form-login is enabled so the OAuth2 authorize redirect can prompt for
 * credentials in a browser context.
 */
@Configuration
@EnableMethodSecurity
public class DefaultIdentitySecurityConfig {

    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health/**",
                                "/actuator/readiness",
                                "/actuator/liveness",
                                "/actuator/prometheus",
                                "/api/v1/public/**",
                                "/oauth2/password-reset/request",
                                "/oauth2/password-reset/verify",
                                "/oauth2/password-reset/complete",
                                "/login",
                                "/error",
                                // Dev-only smoke-test token mint. The controller
                                // is profile-gated (docker/demo/smoke); the path
                                // 404s in production where the bean isn't loaded.
                                "/dev/oauth2/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(Customizer.withDefaults())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt
                        .jwtAuthenticationConverter(jwtAuthenticationConverter())
                ))
                .csrf(csrf -> csrf.disable())
                .build();
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
    public PasswordEncoder userPasswordEncoder() {
        // bcrypt strength 10 — matches the seed migration's encoded values.
        return new BCryptPasswordEncoder(10);
    }
}
