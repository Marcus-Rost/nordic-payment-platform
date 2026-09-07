# Project #4: Secure Spring Boot Platform Starter — Gap Analysis

*Comparison against similar starters, target company patterns, and EU best practices*

---

## 1. Comparison with Target Company Implementations

### Skatteetaten Aurora Platform (Primary Reference)

Skatteetaten's Aurora platform on GitHub (`github.com/Skatteetaten`) provides the closest real-world equivalent to what we're building. Their ecosystem:

| Their Component | Maven Central Group | What It Does | Our Equivalent |
|----------------|--------------------|--------------|----|
| `aurora-spring-boot-mvc-starter` | `no.skatteetaten.aurora` | Auto-config for Spring MVC apps on Aurora platform: management interface, graceful shutdown, header handling | Our full starter (STARTER-F01 through F43) |
| `aurora-spring-boot-webflux-starter` | `no.skatteetaten.aurora` | Same for WebFlux (reactive) apps | We target MVC first; WebFlux is an extension |
| `aurora-prometheus` | `no.skatteetaten.aurora` | Opinionated Prometheus collectors for JVM + custom metrics | Our MetricsAutoConfiguration (STARTER-F20-F24) |
| `aurora-gradle-plugin` | `no.skatteetaten.aurora` | Enforces JVM standards, manages delivery bundle ("leveransepakke") | Our Gradle plugin (STARTER-F50-F53) |
| `mockmvc-extensions-kotlin` | `no.skatteetaten.aurora.mockmvc.extensions` | Kotlin DSL for Spring MockMvc testing | Not in our scope — but a nice future addition |
| `skatteprosessen-task` | `no.skatteetaten.aurora` | Task queue framework for ordered stream/batch processing | Not in our scope — covered by Spring Batch in A2 |

**Gaps vs. Skatteetaten:**
- They have a separate WebFlux starter — we only target MVC. **Acceptable** for a portfolio project.
- Their delivery mechanism is a "leveransepakke" (zip bundle uploaded to Nexus). We use standard Docker images. **Different by design** — Docker/K8s is more universal.
- They have `mockmvc-extensions-kotlin` — we don't. **Nice-to-have** but not critical.
- They deploy to OpenShift with custom Aurora operators (boober, gobo). We deploy to Docker/K8s. **Different infrastructure, same patterns.**

**Features we have that they may not:**
- Our `@Audited` annotation with AOP-based audit logging — this is an addition beyond the Aurora starter's scope.
- Our Kafka-based audit event publishing — Aurora doesn't appear to include this.
- Our CycloneDX SBOM generation — supply chain security focus they may handle separately.

### SpareBank 1 (Build Tooling Reference)

SpareBank 1's `turbo-maven-plugin` (`no.sparebank1:turbo-maven-plugin`) shows they build custom Maven plugins. Their approach:
- Focus on build speed (detecting changed sources for incremental builds)
- Published to Maven Central under `no.sparebank1`

**Relevance:** Validates that Norwegian companies DO publish custom build tools to Maven Central. Our Gradle plugin approach aligns with this culture.

### Mastercard (Security Library Reference)

Mastercard publishes on Maven Central under `com.mastercard.api`:
- `oauth1-signer-java` — OAuth 1.0a request signing
- `client-encryption-java` — JWE payload encryption/decryption

**Relevance:** These are security-focused reusable libraries. Our starter is broader (platform-level) but their pattern of publishing focused security libraries validates our approach.

### DNB (Platform Engineering Reference)

DNB has invested in internal developer platforms with standardized service templates. They use:
- GCP/GKE (not OpenShift like Skatteetaten)
- ArgoCD/GitOps for deployment
- Eufemia design system (frontend — shows they standardize across teams)

**Relevance:** Confirms that large Norwegian banks invest in platform engineering. Our starter is the library equivalent of their platform templates.

---

## 2. Comparison with Open-Source Spring Boot Security Starters

### Spring Boot's Built-in Security Headers

Spring Security (when on classpath) provides `SecurityFilterChain` with `.headers()` configuration:
```java
http.headers(h -> h
    .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31536000))
    .contentTypeOptions(Customizer.withDefaults())
    .frameOptions(fo -> fo.deny())
    .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'"))
);
```

**Gap in our approach:** We should use `@ConditionalOnMissingBean(SecurityFilterChain.class)` — if the sub-system already configures Spring Security, our auto-configured headers should defer, not conflict.

**Our added value vs. built-in:**
- Works without Spring Security on the classpath (just a servlet filter)
- Configurable via `application.yml` properties (Spring Security requires Java config)
- Includes audit logging, metrics, health checks — security headers are just one part

### Zalando Problem (Error Handling)

`org.zalando:problem-spring-web` provides standardized RFC 7807 problem details for Spring Boot error handling. Widely used in European fintech.

**Gap:** We don't include standardized error response formatting. **Recommendation:** Add RFC 7807 Problem Details support in a future version. Low priority for initial release.

### Logstash Logback Encoder

`net.logfrog.logstash:logstash-logback-encoder` provides structured JSON logging for Logback. Widely used.

**Our approach:** We depend on this library for audit log formatting. This is standard practice, not exotic.

---

## 3. EU/Industry Comparison

### OWASP Secure Headers Project

OWASP maintains the [Secure Headers Project](https://owasp.org/www-project-secure-headers/) with recommended headers for all web applications.

Our implementation covers all OWASP recommendations:
| OWASP Header | Our Status |
|-------------|-----------|
| Strict-Transport-Security | STARTER-F01 (**Have**) |
| X-Content-Type-Options | STARTER-F02 (**Have**) |
| X-Frame-Options | STARTER-F03 (**Have**) |
| Content-Security-Policy | STARTER-F04 (**Have**) |
| Referrer-Policy | STARTER-F06 (**Have**) |
| Permissions-Policy | STARTER-F07 (**Have**) |
| Cross-Origin-Opener-Policy | **Gap** — `COOP: same-origin` recommended by OWASP |
| Cross-Origin-Resource-Policy | **Gap** — `CORP: same-origin` recommended |
| Cross-Origin-Embedder-Policy | **Gap** — `COEP: require-corp` for cross-origin isolation |

**Recommendation:** Add COOP, CORP, COEP headers as optional defaults (disabled by default — they can break legitimate cross-origin integrations in Open Banking scenarios).

### PCI DSS Requirements for Logging

PCI DSS v4.0 (effective March 2025) Requirement 10:
- 10.2: Implement automated audit trails for all system components
- 10.3: Record audit trail entries with: user identification, type of event, date/time, success/failure, origination of event, identity or name of affected data/resource
- 10.5: Secure audit trails so they cannot be altered
- 10.7: Retain audit trail history for at least 12 months

**Our coverage:**
| PCI DSS Req | Our Status |
|------------|-----------|
| 10.2 Automated audit trails | STARTER-F10-F15 (**Have** — `@Audited` annotation) |
| 10.3.1 User identification | **Have** — principal from JWT `sub` claim |
| 10.3.2 Type of event | **Have** — HTTP method + path |
| 10.3.3 Date and time | **Have** — UTC ISO 8601 timestamp |
| 10.3.4 Success/failure | **Have** — response status code |
| 10.3.5 Origination | **Have** — source IP, correlation ID |
| 10.3.6 Identity of affected resource | **Partial** — path includes resource, but not the specific data modified |
| 10.5 Immutability | **Partial** — Kafka audit topic is append-only, but we don't enforce log file immutability |
| 10.7 Retention | **Gap** — We don't configure log retention. This is an infrastructure concern, not a library concern |

**Recommendation:** Enhance `@Audited` to optionally capture the primary key of the affected resource (e.g., `@Audited(resource = "paymentId")`). Document PCI DSS 10.5/10.7 as infrastructure requirements, not starter requirements.

---

## 4. "Exotic" Features Assessment

### Feature: Kafka-based Audit Event Publishing (STARTER-F13)

**Is this unusual?** Somewhat. Most Spring Boot starters log to files, not Kafka.
**Justification:** In a microservices architecture (6 services in our payment platform), centralized audit logging via Kafka is more practical than aggregating 6 log files. DNB, NAV, and SpareBank 1 all use Kafka as their event backbone. Publishing audit events to Kafka is consistent with their architecture.
**Verdict:** Keep. Well-justified for the target environment.

### Feature: Gradle Plugin for Dependency Enforcement (STARTER-F50-F53)

**Is this unusual?** For a portfolio project, yes. In production, this is standard (Netflix Nebula, Skatteetaten Aurora).
**Justification:** Skatteetaten's aurora-gradle-plugin does exactly this. Building a Gradle plugin (not just a starter) shows a different skill set (build tooling vs. application development).
**Verdict:** Keep. Directly mirrors Skatteetaten's approach.

### Feature: Custom MeterBinder for Kafka Consumer Lag (STARTER-F20)

**Is this unusual?** Slightly. Spring Boot's Kafka auto-configuration doesn't expose consumer lag as a metric by default.
**Justification:** Kafka consumer lag is the #1 operational metric for stream processing systems. Every production Kafka deployment monitors it. NAV, DNB, SpareBank 1 all care about this.
**Verdict:** Keep. High operational value.

---

## 5. Summary of Recommendations

| Action | Priority | Details |
|--------|----------|---------|
| Add `@ConditionalOnMissingBean` guards | **Must** | Don't conflict with existing Spring Security config |
| Add COOP/CORP/COEP headers (optional) | **Should** | OWASP recommended, but disabled by default |
| Add `@Audited(resource=...)` for PCI DSS 10.3.6 | **Should** | Capture primary key of affected resource |
| Document PCI DSS 10.5/10.7 as infrastructure reqs | **Should** | Not starter's responsibility, but should be documented |
| WebFlux variant | **Could** (future) | Skatteetaten has both MVC and WebFlux starters |
| RFC 7807 Problem Details | **Could** (future) | Zalando pattern, nice for consistency |
