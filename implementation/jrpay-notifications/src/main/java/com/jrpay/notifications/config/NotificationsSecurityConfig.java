package com.jrpay.notifications.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class NotificationsSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health/**",
                                "/actuator/readiness",
                                "/actuator/liveness",
                                "/actuator/prometheus"
                        ).permitAll()
                        // SSE stream uses a single-use stream-token in the
                        // query string; bearer auth doesn't apply.
                        .requestMatchers("/api/v1/customer/notifications/stream").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
                .csrf(csrf -> csrf.disable())
                .build();
    }

    /**
     * Default JwtDecoder pointing at the identity-service JWKS endpoint.
     * Tests + alternate environments can override with their own
     * {@link JwtDecoder} bean. NimbusJwtDecoder is lazy: the JWKS fetch
     * happens on first decode, not at startup, so unreachable URIs are
     * fine until the first authenticated request hits the service.
     */
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder jwtDecoder(
            @Value("${jrpay.notifications.jwt.jwks-uri:http://localhost:8097/oauth2/jwks}") String jwksUri) {
        return NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
    }
}
