# UC-01: Automatic Security Headers on All Responses

*Component: SecurityHeadersAutoConfiguration*

---

## Scenario 1A: Default Configuration (Zero-config)

### Preconditions
- Developer adds `payment-security-spring-boot-starter` to their `build.gradle.kts`
- No explicit security header configuration in `application.yml`

### Main Success Scenario

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | Developer | Adds dependency: `implementation("com.nordpay:payment-security-spring-boot-starter:1.0.0")` | Gradle resolves dependency |
| 2 | Developer | Starts the application: `./gradlew bootRun` | Spring Boot auto-detects `SecurityHeadersAutoConfiguration`, registers a `WebFilter` (WebFlux) or `Filter` (Servlet) |
| 3 | Client | Sends any HTTP request: `GET /api/v1/accounts` | Application processes request normally |
| 4 | — | Response headers filter fires | Adds all default security headers to the response |
| 5 | Client | Receives response | Response includes: `Strict-Transport-Security: max-age=31536000; includeSubDomains`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy: default-src 'self'`, `Referrer-Policy: strict-origin-when-cross-origin` |

### Verification
```bash
curl -I http://localhost:8080/api/v1/accounts
# Expect all 5 security headers in response
```

---

## Scenario 1B: Custom CSP for Sub-system with Frontend

### Preconditions
- The PSD2 API Gateway (A4) serves a consent page with inline scripts

### Main Success Scenario

| Step | Actor | Action |
|------|-------|--------|
| 1 | Developer | Adds to `application.yml`: `payment.security.headers.csp.policy: "default-src 'self'; script-src 'self' 'nonce-{random}'"` |
| 2 | — | Auto-configuration picks up the custom CSP policy |
| 3 | Client | Requests consent page | Response has custom CSP, all other headers remain at defaults |

---

## Scenario 1C: Disable a Specific Header

### Preconditions
- A sub-system integrates with an iframe-based payment provider and needs `X-Frame-Options` relaxed

### Main Success Scenario

| Step | Actor | Action |
|------|-------|--------|
| 1 | Developer | Sets `payment.security.headers.frame-options.enabled: false` in `application.yml` |
| 2 | — | `X-Frame-Options` header is NOT added. All other security headers still present |

---

## Security Considerations
- The filter runs on EVERY response, including error responses and actuator endpoints
- Headers are added last (after the application has set its own headers), so they cannot be accidentally overridden
- If Spring Security is on the classpath and also configures headers, the starter defers to Spring Security's configuration (uses `@ConditionalOnMissingBean`)
