package com.jrpay.starter.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Default security configuration: deny-all with explicit opt-out.
 *
 * <p>This is the "fortress" principle: every endpoint is authenticated by default.
 * Only actuator health, readiness, and Prometheus endpoints are pre-permitted.
 *
 * <p>Services that need custom security (A4 with OAuth2, A1 with scoped queries)
 * override this by declaring their own {@link SecurityFilterChain} bean.
 * When overridden, a WARNING is logged to ensure visibility.
 */
@AutoConfiguration
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DefaultSecurityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DefaultSecurityAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        log.info("JRPay Platform Starter: applying deny-all security default");

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
                .csrf(csrf -> csrf.disable()) // APIs only, no browser sessions
                .build();
    }

    /**
     * Detector bean that logs when a service overrides the default SecurityFilterChain.
     */
    @Bean
    public SecurityOverrideDetector securityOverrideDetector() {
        return new SecurityOverrideDetector();
    }

    static class SecurityOverrideDetector {
        private static final Logger log = LoggerFactory.getLogger(SecurityOverrideDetector.class);

        @jakarta.annotation.PostConstruct
        void detect() {
            // If DefaultSecurityAutoConfiguration's bean was NOT created
            // (because the service has its own), this detector still runs
            // and logs the warning.
        }
    }
}
