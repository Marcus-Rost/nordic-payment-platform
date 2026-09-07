package com.jrpay.ledger.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Production security chain for A1 (Account Ledger).
 *
 * <p>Bearer-JWT resource server. Pulls the public key set from the
 * identity-service JWKS endpoint. {@code scope} claim values are exposed as
 * {@code SCOPE_<value>} authorities so endpoint-level
 * {@link org.springframework.security.access.prepost.PreAuthorize}
 * annotations can gate on them.
 *
 * <p>The {@link DemoSecurityConfig} chain (HTTP Basic) remains active under
 * the {@code docker} / {@code demo} profiles for local exploration. The
 * production chain here activates whenever neither demo profile is on.
 */
@Configuration
@Profile("!demo & !docker")
@EnableMethodSecurity
public class LedgerSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain ledgerSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health/**",
                                "/actuator/readiness",
                                "/actuator/liveness",
                                "/actuator/prometheus"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt
                        .jwtAuthenticationConverter(jwtAuthenticationConverter())
                ))
                .csrf(csrf -> csrf.disable())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder jwtDecoder(
            @Value("${jrpay.ledger.jwt.jwks-uri:http://localhost:8097/oauth2/jwks}") String jwksUri) {
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
}
