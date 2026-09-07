package com.jrpay.audit.config;

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
public class AuditSecurityConfig {

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
                        // Signed download URL carries a single-use nonce + HMAC; bearer
                        // auth is not used. The export-job service verifies the URL.
                        .requestMatchers("/api/v1/admin/audit/export-bin").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
                .csrf(csrf -> csrf.disable())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder jwtDecoder(
            @Value("${jrpay.audit.jwt.jwks-uri:http://localhost:8097/oauth2/jwks}") String jwksUri) {
        return NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
    }
}
