# Project #4: Secure Spring Boot Platform Starter — Requirements

*Cross-cutting foundation library used by ALL Java sub-systems in the Nordic Payment Platform*
*Inspired by: Skatteetaten aurora-spring-boot-starters, aurora-gradle-plugin, aurora-prometheus*
*Target companies: Skatteetaten (primary), DNB, SpareBank 1, Tietoevry, Mastercard Oslo, all Spring Boot shops*

---

## 1. Overview

A reusable Spring Boot starter library that provides opinionated security and operational defaults for payment microservices. When a sub-system (e.g., the Fraud Detection Engine or PSD2 API Gateway) adds this starter as a dependency, it automatically gets: security headers, audit logging, Prometheus metrics, health checks, and graceful shutdown — without writing any configuration code.

Additionally, a companion Gradle plugin enforces build-time standards: minimum Java version, required dependencies, banned dependencies (e.g., no insecure crypto libraries).

This is **platform engineering**, not application development. It mirrors what Skatteetaten built for their Aurora platform (where all applications must run on JVM + Spring Boot) and what DNB, Tietoevry, and SpareBank 1 have as internal platform libraries.

### 1.1 Why This Matters for Target Companies

| Company | Relevance |
|---------|-----------|
| **Skatteetaten** | They mandate JVM + Spring Boot for ALL applications. They built aurora-spring-boot-starters (Maven Central: `no.skatteetaten.aurora`), aurora-gradle-plugin, aurora-prometheus, mockmvc-extensions-kotlin. Our starter mirrors this exact pattern |
| **DNB** | Large internal developer platform. Platform engineering focus. Kafka + GCP infrastructure with standardized service templates |
| **Tietoevry Banking** | Investment in Internal Developer Platforms (IDPs). Spring Boot is their primary framework for new banking services |
| **SpareBank 1** | turbo-maven-plugin on Maven Central (`no.sparebank1`). Shows they build and publish shared build tooling |
| **Mastercard Oslo** | oauth1-signer-java and client-encryption-java on Maven Central (`com.mastercard.api`). Shows they build reusable security libraries |

---

## 2. Functional Requirements

### 2.1 Auto-configured Security Headers

| ID | Requirement | Priority |
|----|------------|----------|
| STARTER-F01 | Auto-configure `Strict-Transport-Security: max-age=31536000; includeSubDomains` on all responses | Must |
| STARTER-F02 | Auto-configure `X-Content-Type-Options: nosniff` on all responses | Must |
| STARTER-F03 | Auto-configure `X-Frame-Options: DENY` on all responses (configurable to SAMEORIGIN) | Must |
| STARTER-F04 | Auto-configure `Content-Security-Policy: default-src 'self'` (configurable) | Must |
| STARTER-F05 | Auto-configure `X-XSS-Protection: 0` (modern recommendation: rely on CSP instead) | Must |
| STARTER-F06 | Auto-configure `Referrer-Policy: strict-origin-when-cross-origin` | Should |
| STARTER-F07 | Auto-configure `Permissions-Policy` (camera=(), microphone=(), geolocation=()) | Should |
| STARTER-F08 | All headers configurable via `application.yml` properties (e.g., `payment.security.headers.hsts.max-age=31536000`) | Must |
| STARTER-F09 | Headers can be disabled per-header if a sub-system needs custom handling (e.g., `payment.security.headers.csp.enabled=false`) | Must |
| STARTER-F09b | Auto-configure `Cross-Origin-Opener-Policy: same-origin` (COOP), `Cross-Origin-Resource-Policy: same-origin` (CORP), `Cross-Origin-Embedder-Policy: require-corp` (COEP). **Disabled by default** — these can break legitimate cross-origin integrations in Open Banking. Opt-in via config | Should |

### 2.2 Structured Audit Logging

| ID | Requirement | Priority |
|----|------------|----------|
| STARTER-F10 | Provide an `@Audited` annotation for Spring MVC controller methods. Automatically logs: timestamp (UTC ISO 8601), authenticated principal, HTTP method + path, request parameters (sanitized), response status, latency, source IP | Must |
| STARTER-F11 | Audit log entries formatted as structured JSON (Logstash-compatible) | Must |
| STARTER-F12 | Sensitive fields (passwords, tokens, account numbers) automatically redacted from audit logs via configurable patterns | Must |
| STARTER-F13 | Audit log entries optionally published to a Kafka topic (`audit.events`) for centralized collection | Should |
| STARTER-F14 | Correlation ID (`X-Correlation-Id`) propagated across service calls and included in all audit log entries | Must |
| STARTER-F15 | Provide a programmatic `AuditLogger` API for logging domain-specific audit events (not just HTTP requests) | Should |
| STARTER-F16 | **`@Audited(resource="paymentId")`**: optionally capture the primary key of the affected resource in the audit log (PCI DSS 10.3.6 — identity of affected data). Extract from path variable, request body field, or return value | Should |

### 2.3 Prometheus Metrics

| ID | Requirement | Priority |
|----|------------|----------|
| STARTER-F20 | Auto-configure custom Micrometer `MeterBinder` implementations for: JVM memory (beyond defaults), Kafka consumer lag, PostgreSQL connection pool stats, HTTP request latency histograms | Must |
| STARTER-F21 | Payment-specific metrics: `payment.transactions.total` (counter, tags: type, status), `payment.transactions.latency` (timer), `payment.fraud.score.distribution` (distribution summary) | Should |
| STARTER-F22 | All metrics exposed via `/actuator/prometheus` (auto-configured, no manual setup) | Must |
| STARTER-F23 | Metrics namespace configurable: `payment.security.metrics.namespace=nordpay` | Should |
| STARTER-F24 | Provide a Grafana dashboard JSON template that visualizes all auto-configured metrics | Should |

### 2.4 Health Checks & Readiness

| ID | Requirement | Priority |
|----|------------|----------|
| STARTER-F30 | Auto-configure health indicators for: PostgreSQL (via DataSource), Kafka broker connectivity, external REST service dependencies | Must |
| STARTER-F31 | Liveness probe at `/actuator/health/liveness` (is the JVM alive?) | Must |
| STARTER-F32 | Readiness probe at `/actuator/health/readiness` (are dependencies available?) | Must |
| STARTER-F33 | Startup probe at `/actuator/health/startup` (has the application finished initializing?) | Should |
| STARTER-F34 | Custom health indicator for Kafka consumer group lag (warn if lag > configurable threshold) | Should |

### 2.5 Graceful Shutdown

| ID | Requirement | Priority |
|----|------------|----------|
| STARTER-F40 | Enable Spring Boot graceful shutdown by default (`server.shutdown=graceful`) | Must |
| STARTER-F41 | Configurable shutdown timeout (`spring.lifecycle.timeout-per-shutdown-phase=30s`) | Must |
| STARTER-F42 | During shutdown: stop accepting new requests, drain in-flight requests, close Kafka consumers (commit offsets), close database connections | Must |
| STARTER-F43 | Log shutdown progress at INFO level: "Shutting down... N in-flight requests remaining" | Should |

### 2.6 SBOM and Dependency Security

| ID | Requirement | Priority |
|----|------------|----------|
| STARTER-F50 | The Gradle plugin auto-configures CycloneDX SBOM generation on every build | Should |
| STARTER-F51 | The Gradle plugin enforces minimum Java version (configurable, default: 17) | Must |
| STARTER-F52 | The Gradle plugin can ban specific dependencies (e.g., `commons-collections:3.x`, known vulnerable libraries) | Should |
| STARTER-F53 | The Gradle plugin can require specific dependencies (e.g., the starter itself, ensuring no sub-system skips security) | Should |

---

## 3. Non-functional Requirements

| ID | Requirement | Target |
|----|------------|--------|
| STARTER-NF01 | Startup overhead added by the starter | < 500ms additional |
| STARTER-NF02 | Memory overhead | < 20MB additional heap |
| STARTER-NF03 | Must not break existing Spring Boot auto-configuration | Zero conflicts |
| STARTER-NF04 | Must work with Spring Boot 3.2+ (current LTS range) | Compatibility |
| STARTER-NF05 | Published as a single JAR to Maven repository (local Nexus or Maven Central) | Distribution |
| STARTER-NF06 | Must be non-intrusive: any auto-configuration can be disabled via properties | Flexibility |

---

## 4. Technology Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 17+ (library code), Kotlin (Gradle plugin DSL) |
| Framework | Spring Boot 3.2+ auto-configuration (`@AutoConfiguration`, `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`) |
| Metrics | Micrometer 1.12+ with Prometheus registry |
| Logging | SLF4J + Logback (structured JSON via logstash-logback-encoder) |
| Audit | Custom Spring AOP aspect for `@Audited` annotation |
| Build | Gradle 8.x with Kotlin DSL. Plugin published as Gradle plugin |
| Testing | Spring Boot Test, JUnit 5, MockMvc |
| Publishing | Maven Central (via Sonatype OSSRH) or local Nexus |
| Documentation | Javadoc + README + usage examples |

---

## 5. Module Structure

```
payment-platform-starter/
├── payment-security-spring-boot-starter/     # The starter JAR
│   ├── src/main/java/
│   │   └── com/nordpay/platform/starter/
│   │       ├── SecurityHeadersAutoConfiguration.java
│   │       ├── AuditLoggingAutoConfiguration.java
│   │       ├── MetricsAutoConfiguration.java
│   │       ├── HealthCheckAutoConfiguration.java
│   │       ├── GracefulShutdownAutoConfiguration.java
│   │       ├── audit/
│   │       │   ├── Audited.java                    # @Audited annotation
│   │       │   ├── AuditAspect.java                # AOP aspect
│   │       │   ├── AuditLogger.java                # Programmatic API
│   │       │   └── AuditEvent.java                 # Audit log entry model
│   │       ├── security/
│   │       │   └── SecurityHeadersProperties.java  # @ConfigurationProperties
│   │       └── metrics/
│   │           ├── KafkaLagMeterBinder.java
│   │           └── PaymentMetrics.java
│   ├── src/main/resources/
│   │   └── META-INF/spring/
│   │       └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
│   └── src/test/java/
│       └── (auto-configuration tests, MockMvc tests)
│
├── payment-security-gradle-plugin/           # The Gradle plugin
│   └── src/main/kotlin/
│       └── com/nordpay/platform/gradle/
│           └── PaymentSecurityPlugin.kt
│
└── payment-security-starter-sample/          # Sample app demonstrating usage
    └── (minimal Spring Boot app that uses the starter)
```

---

## 6. Configuration Properties

```yaml
# application.yml — all properties with sensible defaults
payment:
  security:
    headers:
      hsts:
        enabled: true
        max-age: 31536000
        include-sub-domains: true
      content-type-options:
        enabled: true
      frame-options:
        enabled: true
        mode: DENY  # or SAMEORIGIN
      csp:
        enabled: true
        policy: "default-src 'self'"
      referrer-policy:
        enabled: true
        policy: strict-origin-when-cross-origin
    audit:
      enabled: true
      log-request-body: false  # PII risk — disabled by default
      redact-patterns:
        - "password"
        - "token"
        - "accountNumber"
        - "iban"
      kafka:
        enabled: false  # Opt-in: publish audit events to Kafka
        topic: audit.events
    metrics:
      namespace: nordpay
      kafka-lag:
        enabled: true
        warn-threshold: 1000
    health:
      kafka:
        enabled: true
      database:
        enabled: true
    graceful-shutdown:
      timeout: 30s
```

---

## 7. Security Requirements

| ID | Requirement |
|----|------------|
| STARTER-S01 | The starter itself must not introduce any security vulnerabilities (no transitive dependencies with known CVEs) |
| STARTER-S02 | Actuator endpoints must be secured: `/actuator/prometheus` and `/actuator/health` are public; all others require authentication |
| STARTER-S03 | Audit log redaction must cover: passwords, bearer tokens, API keys, IBANs, account numbers. Pattern-based, configurable |
| STARTER-S04 | Correlation ID must be cryptographically random (UUID v4), not sequential |
| STARTER-S05 | The Kafka audit publisher must not fail the request if Kafka is unavailable (fire-and-forget with retry) |

---

## 8. Integration Points

| Integration | Direction | Protocol |
|------------|-----------|----------|
| All sub-systems (A1-A6) | Inbound (dependency) | Maven/Gradle dependency |
| Kafka (audit topic) | Outbound (optional) | Kafka producer |
| Prometheus | Outbound (scrape) | HTTP `/actuator/prometheus` |
| Kubernetes | Inbound (probes) | HTTP `/actuator/health/*` |

---

## 9. Acceptance Criteria

| ID | Criterion |
|----|----------|
| AC-01 | A new Spring Boot app with only `payment-security-spring-boot-starter` as a dependency returns all security headers on every HTTP response |
| AC-02 | A controller method annotated with `@Audited` produces a structured JSON audit log entry with all required fields |
| AC-03 | `/actuator/prometheus` exposes custom payment metrics without any explicit configuration |
| AC-04 | `/actuator/health/readiness` reports DOWN if PostgreSQL is unavailable |
| AC-05 | During `docker stop`, the service drains in-flight requests before exiting |
| AC-06 | The Gradle plugin fails the build if Java version < 17 |
| AC-07 | The sample application demonstrates all features and serves as documentation |
