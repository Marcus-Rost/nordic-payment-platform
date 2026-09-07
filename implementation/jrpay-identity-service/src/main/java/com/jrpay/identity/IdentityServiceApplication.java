package com.jrpay.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * JRPay Identity Service — OAuth2 Authorization Server + SCA orchestration +
 * password-reset + privacy notice + session management.
 *
 * <p>Implements the {@code identity.yaml} surface authored in Phase 2A. Hosts:
 * <ul>
 *   <li>OAuth2 endpoints ({@code /oauth2/*}) backed by Spring Authorization Server.
 *   <li>OIDC discovery + JWKS ({@code /.well-known/*}).
 *   <li>SCA orchestration ({@code /sca/*}) — TOTP factor for portfolio default.
 *   <li>Password reset ({@code /oauth2/password-reset/*}).
 *   <li>Privacy notice ({@code /api/v1/(public|customer)/privacy-notice/*}).
 *   <li>Active session listing + revocation ({@code /api/v1/customer/session/*}).
 * </ul>
 */
@SpringBootApplication
public class IdentityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
