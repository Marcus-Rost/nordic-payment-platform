package com.jrpay.starter.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Auto-configures security headers on every HTTP response.
 * Independent of the SecurityFilterChain — even if a service overrides
 * authentication, security headers are still applied.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityHeadersAutoConfiguration {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityHeadersFilter securityHeadersFilter() {
        return new SecurityHeadersFilter();
    }

    static class SecurityHeadersFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain)
                throws ServletException, IOException {

            // OWASP recommended headers
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("X-Frame-Options", "DENY");
            response.setHeader("X-XSS-Protection", "0"); // modern browsers: CSP instead
            response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
            response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");

            // HSTS — 1 year, include subdomains
            response.setHeader("Strict-Transport-Security",
                    "max-age=31536000; includeSubDomains");

            // CSP — restrictive default for API services
            response.setHeader("Content-Security-Policy",
                    "default-src 'none'; frame-ancestors 'none'");

            // Prevent caching of sensitive API responses
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");

            filterChain.doFilter(request, response);
        }
    }
}
