# Platform Starter (#4) — Architectural Design

*Date: 2026-04-11*
*Version 1.0*
*Comprehensive architecture document for the Secure Spring Boot Platform Starter.*
*This is the authoritative reference for understanding the starter's architecture.*

*Supporting documents:*
- *[ARCHITECTURE_CHALLENGES.md](ARCHITECTURE_CHALLENGES.md) — brainstorming record and challenge analysis*
- *[ARCHITECTURE_DECISIONS.md](ARCHITECTURE_DECISIONS.md) — all decisions with rationale and alternatives considered*
- *[architecture_draft.md](architecture_draft.md) — original draft (superseded by this document)*

---

## Table of Contents

1. [Role in the System](#1-role-in-the-system)
2. [Overarching Principles](#2-overarching-principles)
3. [Module Structure and Artifact Inventory](#3-module-structure-and-artifact-inventory)
4. [Security Architecture](#4-security-architecture)
5. [Audit Logging Architecture](#5-audit-logging-architecture)
6. [Distributed Tracing Architecture](#6-distributed-tracing-architecture)
7. [Error Handling Architecture](#7-error-handling-architecture)
8. [Metrics and Health Architecture](#8-metrics-and-health-architecture)
9. [Graceful Shutdown Architecture](#9-graceful-shutdown-architecture)
10. [Build Tooling Architecture](#10-build-tooling-architecture)
11. [Auto-Configuration Design](#11-auto-configuration-design)
12. [Configuration Reference](#12-configuration-reference)
13. [Testing Architecture](#13-testing-architecture)
14. [Versioning and Evolution](#14-versioning-and-evolution)
15. [Cross-System Contracts](#15-cross-system-contracts)
16. [Dependency Management](#16-dependency-management)
17. [References](#17-references)

---

## 1. Role in the System

The Platform Starter is **not a running service**. It is a shared library — a set of build artifacts — that every service in the Nordic Payment Platform depends on. Its purpose is to enforce cross-cutting concerns uniformly, so that security, observability, and operational standards are structural properties of the platform rather than optional behaviors that individual service teams may or may not implement.

When a service adds the starter as a Gradle dependency, it automatically receives: security headers, deny-all authentication default, structured audit logging with tamper-evident hash chains, distributed tracing, standardized error responses, Prometheus metrics, Kubernetes health probes, and graceful shutdown — without writing any configuration code.

### 1.1 What the Starter Is

- A **platform engineering** artifact, not an application
- The architectural enforcement mechanism for the "fortress" security model
- The single point where cross-cutting standards are defined and maintained
- A Spring Boot auto-configuration library following the same patterns as Spring Boot's own starters

### 1.2 What the Starter Is Not

- Not a service (it has no main class, no deployment, no runtime identity)
- Not a framework (services don't extend it or implement its interfaces, except for the exception hierarchy)
- Not a governance document (it is executable governance — the code enforces what the document describes)

### 1.3 Master Architecture Decisions Implemented

The starter is the realization of several decisions from the master Payment Processing architecture:

| Master Decision | Starter Responsibility |
|----------------|----------------------|
| A.1b (deny-all default) | Default `SecurityFilterChain`: `anyRequest().authenticated()` with pre-permitted actuator endpoints |
| E.1a (RFC 9457 errors) | Global `@ControllerAdvice` mapping domain and framework exceptions to Problem Details |
| E.1b (Kafka error classification) | `PaymentErrorHandler` implementing three-category Kafka consumer error handling |
| E.1c (exception hierarchy) | Base exception classes in `payment-domain-model` JAR; starter maps them to HTTP and Kafka error responses |
| E.2a (distributed tracing) | Micrometer Tracing auto-configuration with W3C Trace Context propagation across REST and Kafka |
| F.2a (audit hash chaining) | `HashChainAuditPublisher` with per-instance chains, crash recovery, and dual-write |
| I.1a (config validation) | Common `@ConfigurationProperties` patterns with Bean Validation |

### 1.4 Consuming Services

| Service | Sub-system | Starter Artifacts Used |
|---------|-----------|----------------------|
| A1 Event-Sourced Account Ledger | `09_Event_Sourced_Account_Ledger/` | core + kafka |
| A2 Payment Clearing & Settlement Engine | `11_Payment_Clearing_Settlement_Engine/` | core + kafka (incl. Kafka Streams interceptors) |
| A3 Bill Payment & Request-to-Pay Service | `12_Bill_Payment_Request_to_Pay_Service/` | core + kafka |
| A4 PSD2 Open Banking API Gateway | `02_Open_Banking_PSD2_API/` | core + kafka |
| A5 Fraud Detection & Sanctions Engine | `01_Real_time_Transaction_Fraud_Detection_Engine/` | core + kafka (incl. Kafka Streams interceptors) |
| A6 Verification of Payee Service | `21_Verification_of_Payee_Service/` | core only (no Kafka) |

---

## 2. Overarching Principles

Two foundational principles govern every architectural decision in this project. They are not individual features but constraints that shape every design choice.

### 2.1 P1: Security by Design ("Fortress Architecture")

**Principle:** The system is secure by construction, not by afterthought. Security is structural — enforced by the platform — not behavioral (left to individual service teams). Every default is the most restrictive option; making the system less secure requires explicit, deliberate action.

**Implications:**
- The starter's default `SecurityFilterChain` denies all requests except explicitly permitted actuator endpoints
- Security headers are applied to every HTTP response automatically
- The Gradle plugin fails the build if the starter is not a dependency — no service can skip the security baseline
- New features added in minor versions are disabled by default — they never change the security posture without explicit opt-in

**Authoritative backing:**
- NIST SP 800-160 Vol.1 Rev.1: principles of Protective Defaults, Defense in Depth, Least Privilege, Mediated Access, Least Functionality
- OWASP Secure by Design Framework and Proactive Controls C4: "Address security from the start"
- EBA/GL/2019/04: ICT and Security Risk Management guidelines for financial institutions
- EU DORA (Digital Operational Resilience Act, applied January 2025): "resilience by design"

### 2.2 P2: Crash Resilience by Design

**Principle:** The system is designed from the start to handle crashes mid-transaction — whether from hardware failure, denial-of-service attack, or any other cause. Recovery must be possible without data loss or inconsistency. Crash behavior is not an edge case; it is a primary design driver.

**Implications:**
- The audit hash chain recovers from Kafka on startup — crashes don't lose chain continuity
- The audit publisher uses dual-write (local log + Kafka) — a crash mid-write loses at most the Kafka copy, never the local copy
- Graceful shutdown drains in-flight requests, commits Kafka offsets, and closes database connections before exit
- Health and readiness probes enable Kubernetes to stop routing traffic before a pod becomes unhealthy
- Every critical path has a defined answer to: "What happens if the process dies at this exact line?"

**Authoritative backing:**
- Kleppmann, *Designing Data-Intensive Applications*: async event log + idempotent writes over distributed transactions
- EU DORA: mandatory business continuity and disaster recovery plans, mandatory disruption testing
- Saga pattern literature (Microsoft Azure, AWS): orchestrator state persistence, compensating transactions, idempotent participants

---

## 3. Module Structure and Artifact Inventory

The starter consists of four artifacts, each with a distinct purpose and dependency profile. *(Decision C4)*

### 3.1 Artifact Overview

```
payment-platform-starter/
|
+-- payment-domain-model/                      [1] Shared domain types
|   +-- src/main/java/com/nordpay/domain/
|   |   +-- exception/
|   |   |   +-- PaymentException.java               (abstract base)
|   |   |   +-- PaymentValidationException.java      (-> 400)
|   |   |   +-- PaymentNotFoundException.java        (-> 404)
|   |   |   +-- PaymentConflictException.java        (-> 409)
|   |   |   +-- PaymentForbiddenException.java       (-> 403)
|   |   |   +-- PaymentRateLimitException.java       (-> 429)
|   |   |   +-- PaymentInternalException.java        (-> 500)
|   |   +-- audit/
|   |       +-- Audited.java                         (@Audited annotation)
|   +-- (zero external dependencies)
|
+-- payment-security-starter/                  [2] Core auto-configurations
|   +-- src/main/java/com/nordpay/starter/
|   |   +-- autoconfigure/
|   |   |   +-- SecurityHeadersAutoConfiguration.java
|   |   |   +-- DefaultSecurityAutoConfiguration.java
|   |   |   +-- AuditAutoConfiguration.java
|   |   |   +-- TracingAutoConfiguration.java
|   |   |   +-- ErrorHandlingAutoConfiguration.java
|   |   |   +-- MetricsAutoConfiguration.java
|   |   |   +-- HealthAutoConfiguration.java
|   |   |   +-- GracefulShutdownAutoConfiguration.java
|   |   +-- security/
|   |   |   +-- SecurityHeadersFilter.java
|   |   |   +-- SecurityHeadersProperties.java
|   |   +-- audit/
|   |   |   +-- AuditAspect.java
|   |   |   +-- AuditLogger.java
|   |   |   +-- AuditEvent.java
|   |   |   +-- AuditEventSerializer.java
|   |   |   +-- AuditRedactionEngine.java
|   |   +-- tracing/
|   |   |   +-- CorrelationIdFilter.java
|   |   |   +-- UnifiedTraceIdProvider.java
|   |   +-- error/
|   |   |   +-- PaymentExceptionHandler.java          (@ControllerAdvice, RFC 9457)
|   |   +-- metrics/
|   |       +-- PaymentMeterBinder.java
|   |       +-- PaymentMetrics.java
|   +-- src/main/resources/
|   |   +-- META-INF/spring/
|   |       +-- org.springframework.boot.autoconfigure.AutoConfiguration.imports
|   +-- src/test/java/
|       +-- (ApplicationContextRunner tests, MockMvc tests)
|
+-- payment-security-starter-kafka/            [3] Kafka-specific auto-configurations
|   +-- src/main/java/com/nordpay/starter/kafka/
|   |   +-- autoconfigure/
|   |   |   +-- AuditKafkaAutoConfiguration.java
|   |   |   +-- KafkaHealthAutoConfiguration.java
|   |   |   +-- KafkaErrorAutoConfiguration.java
|   |   |   +-- KafkaStreamsTracingAutoConfiguration.java
|   |   |   +-- KafkaHmacAutoConfiguration.java
|   |   +-- audit/
|   |   |   +-- HashChainAuditPublisher.java
|   |   |   +-- HashChainRecovery.java
|   |   +-- error/
|   |   |   +-- PaymentErrorHandler.java               (three-category handler)
|   |   |   +-- KafkaErrorClassifier.java              (classifier API)
|   |   |   +-- ErrorCategory.java                     (enum: TRANSIENT, POISONED, FATAL)
|   |   +-- hmac/
|   |   |   +-- HmacProducerInterceptor.java
|   |   |   +-- HmacConsumerInterceptor.java
|   |   +-- tracing/
|   |   |   +-- KafkaStreamsProducerInterceptor.java
|   |   |   +-- KafkaStreamsConsumerInterceptor.java
|   |   +-- health/
|   |       +-- KafkaLagHealthIndicator.java
|   +-- src/main/resources/
|   |   +-- META-INF/spring/
|   |       +-- org.springframework.boot.autoconfigure.AutoConfiguration.imports
|   +-- src/test/java/
|       +-- (ApplicationContextRunner tests, Testcontainers Kafka tests)
|
+-- payment-security-gradle-plugin/            [4] Build-time enforcement
|   +-- src/main/kotlin/com/nordpay/gradle/
|   |   +-- PaymentPlatformPlugin.kt
|   |   +-- PaymentPlatformExtension.kt
|   |   +-- DependencyGuardTask.kt
|   |   +-- SbomConfigurationTask.kt
|   +-- src/main/resources/
|       +-- banned-dependencies.yml                    (default ban list)
|
+-- payment-security-starter-sample/           [5] Sample app (integration test + docs)
    +-- src/main/java/com/nordpay/sample/
    |   +-- SampleApplication.java
    |   +-- SampleController.java                      (demonstrates @Audited, custom errors)
    |   +-- SampleSecurityConfig.java                  (demonstrates SecurityFilterChain override)
    +-- src/test/java/
        +-- (Testcontainers integration tests)
```

### 3.2 Dependency Graph

```
payment-domain-model          (zero external dependencies)
        ^
        |
payment-security-starter      (depends on: domain-model, Spring Web, Spring AOP,
        ^                      Spring Security, Micrometer, Micrometer Tracing,
        |                      logstash-logback-encoder)
        |
payment-security-starter-kafka (depends on: starter core, Spring Kafka.
                                Optional: Kafka Streams)
```

The Gradle plugin is independent — it has no runtime dependency on the starter JARs.

**Note on STARTER-NF05:** The requirements specify *"Published as a single JAR."* The architecture splits this into two starter JARs (core + Kafka) plus a domain model JAR. This is a deliberate deviation — the single-JAR requirement was written before the Kafka/non-Kafka dependency split was identified. The rationale is documented in Decision C4. For services that use Kafka (5 of 6), the consumption is two dependency lines instead of one — a negligible cost for clean dependency hygiene.

### 3.3 Why Two Starter Modules

The split follows the real dependency fault line in the platform: **Kafka vs. non-Kafka**. Service A6 (Verification of Payee) uses REST and Caffeine cache only — no Kafka. Bundling Kafka dependencies into the core would burden A6 with unnecessary transitive dependencies.

The core module has **no conditional activation** (`@ConditionalOnClass`). Every auto-configuration in the core activates unconditionally because every dependency is guaranteed present on any Spring Boot web application's classpath. This eliminates silent-skip scenarios.

The Kafka module uses `@ConditionalOnClass(KafkaStreams.class)` for the Streams tracing interceptor only — a single, well-justified level of conditionality.

### 3.4 Kafka Producer Defaults

**Producer Defaults:** The `payment-security-starter-kafka` module auto-configures `acks=all` for all Kafka producers via `KafkaProducerAutoConfiguration`. This prevents a developer from accidentally creating a producer with `acks=1` (which risks data loss on broker failure). Override requires explicit property: `spring.kafka.producer.acks=1` (logged as WARNING).

### 3.5 Kafka HMAC Interceptors (FS1.3)

The `payment-security-starter-kafka` module auto-configures HMAC-SHA256 message signing and verification for all Kafka producers and consumers via `KafkaHmacAutoConfiguration`.

- **`HmacProducerInterceptor`**: Auto-configured for all Kafka producers. Signs every outgoing message with the service's HMAC-SHA256 key. The key is loaded via `KeyProvider`. The service's key ID is included in Kafka message headers (`X-HMAC-Key-ID`) so that consumers know which key to use for verification.

- **`HmacConsumerInterceptor`**: Auto-configured for all Kafka consumers. Verifies the HMAC signature **before** Avro deserialization. This ordering is critical — a tampered message must never be deserialized, as malicious Avro payloads could exploit deserialization vulnerabilities. Failed verification results in the message being sent to the DLQ and a security alert being raised.

- **Auto-configuration**: Both interceptors are registered by `KafkaHmacAutoConfiguration` (part of `payment-security-starter-kafka`). They activate unconditionally when the Kafka module is on the classpath. Services opt out only by explicitly setting `payment.security.kafka.hmac.enabled=false`, which is logged as a WARNING.

- **Per-producer keys**: Each service has its own HMAC key, managed via `KeyProvider`. The key ID is included in Kafka headers (`X-HMAC-Key-ID`) so consumers can look up the correct verification key. This ensures that if one service's key is compromised, only that service's messages are affected — other services' keys remain valid.

---

## 4. Security Architecture

*(Decision C1: Defense in Depth — Layered Security Model)*

The starter implements a layered security model with explicit boundaries between what the starter owns, what it provides as defaults, and what is out of scope.

### 4.1 Layer Responsibility Matrix

| Layer | Concern | Starter Role | Mechanism |
|-------|---------|-------------|-----------|
| 0 | Network/Infrastructure | **Out of scope** | TLS termination, firewall rules, network policies are Kubernetes/ingress concerns |
| 1 | HTTP Transport | **Owns fully** | Security headers on every response. Configured via properties. Cannot be accidentally skipped |
| 2 | Authentication | **Overridable default** | Deny-all `SecurityFilterChain` via `@ConditionalOnMissingBean`. Service replaces entirely when it provides its own |
| 3 | Authorization | **Actuator rules only** | health/prometheus = public, all other actuator = authenticated. API authorization is the service's responsibility |
| 4 | Input Validation | **Out of scope** | Too service-specific. Starter does not auto-configure request validation |
| 5 | Business Logic | **Out of scope** | Always a service concern |
| 6 | Detection & Response | **Owns fully** | Audit logging, metrics, health probes, distributed tracing, correlation IDs. Always active |
| 7 | Data Protection | **Partial** | Audit field redaction (owns). HSTS header (owns). Encryption at rest and field-level encryption are infrastructure/service concerns |

### 4.2 Security Headers

The starter applies OWASP-recommended security headers to every HTTP response via a servlet `Filter` that runs on all responses, including error responses and actuator endpoints. The filter runs last (after the application has set its own headers) to prevent accidental override.

**Headers applied by default:**

| Header | Default Value | Requirement |
|--------|--------------|-------------|
| `Strict-Transport-Security` | `max-age=31536000; includeSubDomains` | STARTER-F01 |
| `X-Content-Type-Options` | `nosniff` | STARTER-F02 |
| `X-Frame-Options` | `DENY` | STARTER-F03 |
| `Content-Security-Policy` | `default-src 'self'` | STARTER-F04 |
| `X-XSS-Protection` | `0` (rely on CSP) | STARTER-F05 |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | STARTER-F06 |
| `Permissions-Policy` | `camera=(), microphone=(), geolocation=()` | STARTER-F07 |

**Headers available but disabled by default** (can break Open Banking cross-origin integrations):

| Header | Value when enabled | Requirement |
|--------|-------------------|-------------|
| `Cross-Origin-Opener-Policy` | `same-origin` | STARTER-F09b |
| `Cross-Origin-Resource-Policy` | `same-origin` | STARTER-F09b |
| `Cross-Origin-Embedder-Policy` | `require-corp` | STARTER-F09b |

Every header is individually configurable and disableable via `application.yml`.

### 4.3 Default Authentication

The starter provides a default `SecurityFilterChain` that denies all requests except explicitly permitted actuator endpoints:

```java
@AutoConfiguration
@ConditionalOnWebApplication
public class DefaultSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SecurityFilterChain.class)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/actuator/prometheus").permitAll()
                .anyRequest().authenticated()
            )
            .build();
    }
}
```

**Override behavior:** When a service defines its own `SecurityFilterChain` bean (e.g., A4 with OAuth2 Authorization Server, A5 with OAuth2 Resource Server), the starter's default is not loaded. The starter logs a WARNING at startup:

> *"Custom SecurityFilterChain detected — starter deny-all default is not active. Ensure your configuration follows the platform security policy."*

This makes the security handoff visible and auditable.

### 4.4 HTTPS and CSRF

- **HTTPS enforcement** is an infrastructure concern. In Kubernetes with TLS termination at the ingress controller, the application sees plain HTTP — a redirect would break. The starter contributes HSTS (tells browsers to use HTTPS for future requests).
- **CSRF protection** is default ON as part of the deny-all `SecurityFilterChain`. REST-only services that override the `SecurityFilterChain` typically disable CSRF (standard for stateless APIs). This is acceptable because the override is explicit.

### 4.5 Actuator Endpoint Security

| Endpoint | Access | Rationale |
|----------|--------|-----------|
| `/actuator/health/**` | Public | Kubernetes probes need unauthenticated access |
| `/actuator/prometheus` | Public | Prometheus scraping needs unauthenticated access |
| All other actuator endpoints | Authenticated (disabled by default) | Endpoints like `/actuator/env`, `/actuator/configprops` can leak sensitive configuration |

Health check details (database connection strings, Kafka broker addresses) are hidden from unauthenticated users: `management.endpoint.health.show-details=when-authorized`.

### Threat Model Traceability (Master A.3a)

The Platform Starter mitigates threats structurally for all consuming services:

| Threat Category | Starter Control | Mechanism |
|----------------|----------------|-----------|
| Spoofing | Deny-all authentication default | `anyRequest().authenticated()` auto-configured |
| Tampering (Kafka) | HMAC producer/consumer interceptors | Auto-configured, verify-before-deserialize |
| Tampering (HTTP) | Security headers (HSTS, CSP, X-Frame-Options) | Auto-configured on every response |
| Repudiation | Tamper-evident audit hash chain | SHA-256 chain, dual-write (log + Kafka) |
| Information Disclosure | PII redaction in logs | `AuditRedactionEngine` with configurable patterns |
| Information Disclosure | No stack traces in error responses | RFC 9457 with `expose-internal-details: false` |
| Denial of Service | Graceful shutdown with connection drain | Auto-configured shutdown hooks |
| Elevation of Privilege | Build-time enforcement | Gradle plugin fails build without starter dependency |

---

## 5. Audit Logging Architecture

*(Decisions C5: Hash Chain Design, C6: Kafka Publisher Failure Semantics)*

The audit logging subsystem serves two purposes: operational visibility (who did what, when) and regulatory compliance (PCI DSS Requirement 10).

### 5.1 Two Audit Interfaces

**1. `@Audited` annotation (declarative, for HTTP requests)**

Applied to Spring MVC controller methods. The starter's `AuditAspect` (Spring AOP) intercepts the method and automatically captures:

| Field | Source | PCI DSS Mapping |
|-------|--------|----------------|
| `timestamp` | System clock, UTC ISO 8601 | 10.3.3 |
| `principal` | JWT `sub` claim or Spring Security principal | 10.3.1 |
| `action` | HTTP method + path (e.g., `POST /api/v1/payments`) | 10.3.2 |
| `sourceIp` | `X-Forwarded-For` or remote address | 10.3.5 |
| `responseStatus` | HTTP status code | 10.3.4 |
| `latencyMs` | Wall-clock time of method execution | (operational) |
| `traceId` | Micrometer trace ID (unified with correlation ID) | 10.3.5 |
| `resource` | Optional: `@Audited(resource="paymentId")` extracts primary key | 10.3.6 |

**2. `AuditLogger` API (programmatic, for domain events)**

Injected via `@Autowired`. Used for events not tied to HTTP requests:

```java
auditLogger.log("PAYMENT_SETTLED", Map.of(
    "paymentId", id,
    "amount", amount,
    "currency", "NOK"
));
```

Both interfaces produce the same structured JSON output and feed into the same hash chain and dual-write mechanism.

### 5.2 Sensitive Field Redaction

The `AuditRedactionEngine` applies configurable pattern-based redaction to audit log entries before writing. Default redacted patterns: `password`, `token`, `accountNumber`, `iban`. Redacted values are replaced with `***REDACTED***`.

Request body logging is **disabled by default** (`payment.security.audit.log-request-body=false`) due to PII risk. When enabled, the body is redacted before logging.

### 5.3 Dual-Write Architecture

Every audit event is written to **two destinations simultaneously**:

```
                                +---> Local SLF4J logger ("audit")
                               |      Structured JSON, synchronous
AuditAspect / AuditLogger     |      Always succeeds (disk available)
        |                      |      PCI DSS 10.2 compliance backstop
        v                      |
  AuditEvent created ------>--+
  Hash computed                |
                               +---> Kafka topic ("audit.events")
                                      Async via KafkaTemplate.send()
                                      Best-effort, fire-and-forget
                                      Centralized querying and alerting
```

**Failure behavior:**

| Scenario | Local Log | Kafka | Compliance Impact |
|----------|----------|-------|-------------------|
| Normal operation | Written | Written | Full coverage |
| Kafka unavailable | Written | WARN logged, metric incremented | No impact (local log satisfies PCI DSS 10.2) |
| Local disk full | Logback handles | Written | Kafka provides the backup copy |
| Both down | Lost | Lost | Catastrophic infrastructure failure — beyond starter scope |

The starter emits a `audit.kafka.send.failure` counter metric (tagged by service and error type) to enable Grafana alerting on degraded Kafka audit delivery.

**Reconciliation:** When Kafka recovers from an outage, the operations team identifies gaps (no events in Kafka for the outage period) and can backfill from local log files of affected instances.

### 5.4 Hash Chain Design

Each audit event includes a tamper-evident hash, chaining it to the previous event. Modifying any past event breaks the chain from that point forward.

**Computation:**

```
eventPayload = canonicalJson(auditEvent excluding chain metadata fields)
currentHash  = SHA-256(previousHash + eventPayload)
```

The hash input consists of the previous chain hash concatenated with the canonical JSON of the event's **payload fields only** (timestamp, principal, action, traceId, responseStatus, latencyMs, service, and any domain-specific fields). The **chain metadata fields** (`instanceId`, `sequenceNumber`, `previousHash`, `currentHash`) are excluded from the hash input to avoid circular dependency (`currentHash` cannot be part of its own input) and double-counting (`previousHash` is already an explicit parameter).

The computation sequence is:
1. Build the audit event with all payload fields
2. Serialize payload fields to canonical JSON
3. Compute `currentHash = SHA-256(previousHash + payloadJson)`
4. Set `previousHash`, `currentHash`, `instanceId`, `sequenceNumber` on the event
5. Write the complete event (payload + chain metadata) to both destinations

**Canonicalization:** Payload fields sorted alphabetically, no whitespace, UTC timestamps in ISO 8601, numbers as-is. Implemented once in `AuditEventSerializer` and verified with golden-file tests (known input produces known hash).

**Chain scope:** Per-instance. Each JVM process (Kubernetes pod) maintains its own independent chain. Events include `instanceId` (pod name) and `sequenceNumber`.

**Why per-instance, not per-service or global:**
- Per-service would require distributed coordination (locking or single-writer) — adding fragility to the audit path, which must be the most reliable component
- Global would require cross-service coordination — impractical bottleneck
- Per-instance requires zero coordination and is independently verifiable

**Audit event structure:**

```json
{
  "instanceId": "clearing-engine-pod-2",
  "sequenceNumber": 4817,
  "previousHash": "a3f2b8c1d9e6f74502ab3819c6d87e4f1023a5b7...",
  "currentHash": "7d4e9f0a2b5c8d1e3f6a9b0c4d7e2f5a8b1c3d6e...",
  "timestamp": "2026-04-12T10:23:45.123Z",
  "principal": "tpp-client-42",
  "action": "POST /api/v1/payments",
  "traceId": "550e8400e29b41d4a716446655440000",
  "responseStatus": 201,
  "latencyMs": 42,
  "service": "clearing-engine"
}
```

### 5.5 Hash Chain Crash Recovery

On startup, the `HashChainRecovery` component restores chain continuity:

1. Read the last events for this `instanceId` from the `audit.events` Kafka topic (bounded read: last 10 events, filtered by instanceId, takes milliseconds)
2. If found: resume chain from the latest `currentHash`
3. If not found (first-ever start) or Kafka unavailable at startup: start from `GENESIS` marker, log WARNING

**GENESIS markers** are honest, detectable signals. A chain verification tool that encounters a GENESIS mid-stream knows "a restart occurred here with Kafka unavailable." This is not a gap — it is an explicit marker. The local log file provides the complete audit trail regardless.

### 5.6 Verification Procedure

Verification (run periodically or on-demand):
1. Read all events from the `audit.events` Kafka topic (or from local log files)
2. Group by `instanceId`
3. Order each group by `sequenceNumber`
4. For each event: extract payload fields (excluding chain metadata), recompute `SHA-256(previousHash + canonicalJson(payload))`, and compare to `currentHash`
5. Any mismatch flags the exact event where tampering occurred
6. GENESIS markers flag expected chain breaks (restart with Kafka unavailable)

---

## 6. Distributed Tracing Architecture

*(Decision C2: Distributed Tracing Across REST and Kafka Streams)*

### 6.1 Tracing Protocol

The starter uses **W3C Trace Context** (`traceparent` / `tracestate` headers) via **Micrometer Tracing**, which is native to Spring Boot 3.2+. Micrometer is already a dependency for metrics, so using Micrometer Tracing keeps one abstraction for both metrics and traces.

### 6.2 Unified Trace/Correlation ID

The Micrometer-generated `traceId` serves as the correlation ID. There is one identifier, not two.

| Scenario | Behavior |
|----------|----------|
| Internal request (service-to-service) | `traceId` is generated by Micrometer, propagated in `traceparent` header, included in all audit log entries |
| External request with `X-Correlation-Id` (e.g., TPP calling A4) | External correlation ID stored as **baggage** on the trace. Audit logs include both `traceId` (canonical internal ID) and `externalCorrelationId` (caller's ID) |
| No incoming trace context | Micrometer generates a new `traceId`. This becomes the correlation ID for the entire request chain |

Operators search by `traceId` as the primary key. External correlation IDs are supplementary.

**Note on STARTER-S04:** The requirement specifies *"Correlation ID must be cryptographically random (UUID v4), not sequential."* The unified `traceId` is a 128-bit cryptographically random hex string (from Micrometer Tracing), satisfying the intent (randomness, non-sequential). The format is W3C Trace Context hex (`550e8400e29b41d4a716446655440000`) rather than UUID v4 hyphenated format (`550e8400-e29b-41d4-a716-446655440000`). This is a deliberate trade-off: W3C compliance over UUID format.

### 6.3 REST Tracing

Automatic via Spring Boot's Micrometer Tracing integration:
- **Inbound:** `CorrelationIdFilter` extracts or generates `traceId`, stores in MDC
- **Outbound:** `RestClient` / `WebClient` interceptors inject `traceparent` into outgoing HTTP headers

Zero configuration required from services.

### 6.4 Kafka Tracing — Standard Consumers

For services using `@KafkaListener` (standard Spring Kafka consumers), Micrometer's built-in `KafkaListenerObservation` handles trace context extraction automatically. Zero configuration required.

### 6.5 Kafka Streams Tracing — Starter-Provided Interceptors

Kafka Streams manages its own internal consumers and producers and does not use Spring's `@KafkaListener` abstraction. Micrometer's built-in support does not cover this case.

The starter (in the Kafka module) provides a `ProducerInterceptor` + `ConsumerInterceptor` pair:

- **`KafkaStreamsConsumerInterceptor`**: Extracts `traceparent` from incoming record headers. Creates a child span linked to the parent trace.
- **`KafkaStreamsProducerInterceptor`**: Injects `traceparent` from the current span into outgoing record headers.

Auto-configured when Kafka Streams is on the classpath (`@ConditionalOnClass(KafkaStreams.class)`). Services using Kafka Streams (A2 Clearing Engine, A5 Fraud Engine) register these interceptors in their Streams configuration.

For complex topology fan-out (one input record producing multiple output records), each output carries the trace context of its input. Services can add additional span annotations in their stream processors for finer-grained tracing.

### 6.6 Cross-System Tracing Contract

**Every Kafka record carries a `traceparent` header. Every REST call carries a `traceparent` header.** The starter ensures both. A payment flowing A3->A6->A2->A5->A1 produces a single, connected trace visible in Jaeger or Zipkin.

---

## 7. Error Handling Architecture

*(Decision C3: Error Handling Standardization)*

### 7.1 REST Error Handling — RFC 9457 Problem Details

Every error response from every service follows RFC 9457 (Problem Details for HTTP APIs), returned as `application/problem+json`:

```json
{
  "type": "https://nordpay.com/problems/account-not-found",
  "title": "Account Not Found",
  "status": 404,
  "detail": "No account found with ID NO93860111117947",
  "instance": "/api/v1/accounts/NO93860111117947",
  "traceId": "550e8400e29b41d4a716446655440000"
}
```

The starter's `PaymentExceptionHandler` (`@ControllerAdvice`) maps exceptions to Problem Details:

| Exception Source | Mapping |
|-----------------|---------|
| `PaymentValidationException` | 400 Bad Request |
| `PaymentNotFoundException` | 404 Not Found |
| `PaymentConflictException` | 409 Conflict |
| `PaymentForbiddenException` | 403 Forbidden |
| `PaymentRateLimitException` | 429 Too Many Requests |
| `PaymentInternalException` | 500 Internal Server Error |
| Spring Security `AuthenticationException` | 401 Unauthorized |
| Spring Security `AccessDeniedException` | 403 Forbidden |
| Spring MVC `MethodArgumentNotValidException` | 400 Bad Request (with field-level validation details) |
| Any unhandled exception | 500 Internal Server Error (**no internal details exposed** — no stack traces, no class names) |

### 7.2 Exception Hierarchy

The base exceptions live in `payment-domain-model`, a JAR with **zero external dependencies**:

```
PaymentException (abstract)
+-- PaymentValidationException    -> 400
+-- PaymentNotFoundException      -> 404
+-- PaymentConflictException      -> 409
+-- PaymentForbiddenException     -> 403
+-- PaymentRateLimitException     -> 429
+-- PaymentInternalException      -> 500
```

Services extend these for domain-specific exceptions (e.g., `InvalidIbanException extends PaymentValidationException`). The starter's handler catches the base types. The zero-dependency design means services can use these exceptions in their domain layer without coupling to Spring.

### 7.3 Kafka Error Classification

When a Kafka consumer encounters an error processing a record, the starter's `PaymentErrorHandler` classifies the error and takes the appropriate action:

| Category | Action | When |
|----------|--------|------|
| **Transient** | Retry with exponential backoff. After N retries, send to DLQ | Temporary failures that may succeed on retry |
| **Poisoned** | Send directly to DLQ. No retry | The message itself is the problem — retrying won't help |
| **Fatal** | Alert (metric + log at ERROR) + stop consumer | Something is fundamentally wrong — human intervention needed |

**Default classification (provided by starter):**

| Exception | Category | Rationale |
|-----------|----------|-----------|
| `DeserializationException` | Poisoned | Message can never be deserialized — schema mismatch or corruption |
| `PaymentValidationException` | Poisoned | Business rule violation inherent to the message content |
| `TransientDataAccessException` | Transient | Database temporarily unavailable |
| `KafkaTimeoutException` | Transient | Broker temporarily slow |
| `OutOfMemoryError`, `StackOverflowError` | Fatal | JVM is in a broken state |
| Everything else | Transient | Safe default — retry preserves the message in DLQ for inspection |

**Service override:** Services register domain-specific classifications:

```java
@Bean
public KafkaErrorClassifier customClassifier() {
    return KafkaErrorClassifier.builder()
        .poisoned(InvalidIbanException.class)
        .poisoned(DuplicatePaymentException.class)
        .fatal(SanctionsListLoadFailureException.class)
        .build();
}
```

The starter merges the service's classifier with defaults (service takes precedence on overlap). Every categorization decision is logged at INFO level for auditability.

---

## 8. Metrics and Health Architecture

### 8.1 Prometheus Metrics

The starter auto-configures custom `MeterBinder` implementations via `MetricsAutoConfiguration`:

| Metric | Type | Tags | Module |
|--------|------|------|--------|
| `{namespace}_http_request_latency` | Timer histogram | method, path, status | Core |
| `{namespace}_jvm_gc_pause_seconds` | Timer | cause, action | Core |
| `{namespace}_db_pool_active_connections` | Gauge | pool | Core |
| `{namespace}_kafka_consumer_lag` | Gauge | topic, group | Kafka |
| `{namespace}_payment_transactions_total` | Counter | type, status | Core |
| `{namespace}_payment_transactions_latency` | Timer | type | Core |
| `{namespace}_payment_fraud_score` | Distribution summary | model | Core (recorded by A5 via `PaymentMetrics`) |
| `{namespace}_audit_kafka_send_failure` | Counter | service, error | Kafka |

The namespace defaults to `nordpay` and is configurable (`payment.security.metrics.namespace`).

All metrics are exposed at `/actuator/prometheus` without explicit configuration. The starter ships a `grafana-dashboard.json` template visualizing all auto-configured metrics.

### 8.2 Kubernetes Health Probes

| Probe | Path | Purpose | Auto-configured Indicators |
|-------|------|---------|---------------------------|
| Liveness | `/actuator/health/liveness` | Is the JVM alive? | JVM default |
| Readiness | `/actuator/health/readiness` | Are dependencies available? | PostgreSQL (via DataSource), Kafka broker connectivity |
| Startup | `/actuator/health/startup` | Has initialization finished? | Application context loaded |

Health check details are hidden from unauthenticated users (`show-details=when-authorized`).

### 8.3 Kafka Consumer Lag Health Indicator

The Kafka module provides a custom health indicator for Kafka consumer group lag. When lag exceeds a configurable threshold (default: 1000 messages), the indicator reports WARN status. This does not affect readiness (the service can still process, just slowly) but triggers Grafana alerts.

---

## 9. Graceful Shutdown Architecture

The starter enables Spring Boot's built-in graceful shutdown (`server.shutdown=graceful`) and configures the shutdown sequence:

| Phase | Time | Action |
|-------|------|--------|
| 1 | T+0s | SIGTERM received. Log: "Graceful shutdown initiated" |
| 2 | T+0s | Stop accepting new HTTP connections |
| 3 | T+0s to T+timeout | Drain in-flight requests. Log: "Draining N in-flight requests..." |
| 4 | After drain | Kafka consumers commit offsets and close |
| 5 | After Kafka | Database connection pool closes |
| 6 | After DB | Log: "Graceful shutdown complete. All resources released." Exit 0 |

**Timeout exceeded:** If in-flight requests are not drained within the configured timeout (default: 30s), the starter logs a WARNING ("Forced shutdown. N request(s) interrupted.") and forces exit.

This sequence ensures that:
- No payment transaction is interrupted mid-processing (if it completes within the timeout)
- Kafka consumer offsets are committed (no duplicate reprocessing on restart)
- Database connections are returned cleanly (no connection leak)
- The restart picks up where the previous instance left off

---

## 10. Build Tooling Architecture

*(Decision C9: Gradle Plugin Scope and Enforcement Model)*

### 10.1 Plugin Purpose

The `payment-security-gradle-plugin` is a governance tool that enforces build-time standards across all services. It complements the starter's runtime enforcement (security headers, deny-all) with compile-time enforcement (Java version, dependency hygiene).

### 10.2 Enforcement Model — Tiered

| Check | Enforcement | Requirement |
|-------|-------------|-------------|
| Minimum Java version (default: 17) | **Fail build** | STARTER-F51 (Must) |
| `payment-security-starter` on classpath | **Fail build** | STARTER-F53 — enforcement of P1 (fortress) |
| Banned dependencies | **Warn** (configurable to fail via `failOnViolation = true`) | STARTER-F52 |
| CycloneDX SBOM generation | **Silent, always-on** | STARTER-F50 |

### 10.3 Ban List

Maintained as an externalized YAML configuration file (not hardcoded in the plugin):

```yaml
# banned-dependencies.yml
banned:
  - group: "commons-collections"
    artifact: "commons-collections"
    versionPattern: "[1,4)"
    reason: "CVE-2015-6420 - use commons-collections4 instead"
  - group: "log4j"
    artifact: "log4j"
    versionPattern: "[1,2)"
    reason: "EOL - use SLF4J + Logback (provided by starter)"
  - group: "javax.servlet"
    artifact: "javax.servlet-api"
    versionPattern: "[0,)"
    reason: "Spring Boot 3 requires Jakarta - use jakarta.servlet-api"
```

Categories: known CVE libraries, superseded libraries (javax -> jakarta), insecure crypto, license-incompatible.

### 10.4 Plugin Extension API

```kotlin
paymentPlatform {
    minJavaVersion = 17
    requireStarter = true
    bannedDependencies {
        configFile = file("banned-dependencies.yml")
        failOnViolation = false   // default: warn. Set true for hard enforcement
    }
    sbom {
        enabled = true            // default: always generate SBOM
    }
}
```

### 10.5 Distribution

- **Development:** `includeBuild` in `settings.gradle.kts` for fast iteration
- **Production/portfolio:** Published to Maven repository with version number

---

## 11. Auto-Configuration Design

### 11.1 Design Principles

Every auto-configuration follows three rules:

1. **Conditional on missing bean:** If a service provides its own bean, the starter's default is skipped (`@ConditionalOnMissingBean`)
2. **Property-driven:** All behavior configurable via `application.yml` under the `payment.security.*` namespace
3. **Disableable:** Each feature has an `enabled` property (e.g., `payment.security.headers.enabled=false`)

### 11.2 Auto-Configuration Ordering

Auto-configurations that interact are explicitly ordered using `@AutoConfigureBefore` / `@AutoConfigureAfter`:

```
TracingAutoConfiguration               (first — traceId available for all other components)
    |
    v
DefaultSecurityAutoConfiguration       (provides SecurityFilterChain if missing)
    |
    v
SecurityHeadersAutoConfiguration       (adds headers filter — independent of SecurityFilterChain)
    |
    v
AuditAutoConfiguration                 (depends on tracing for traceId)
    |
    v
ErrorHandlingAutoConfiguration         (depends on audit for logging unhandled exceptions)
    |
    v
MetricsAutoConfiguration               (independent)
HealthAutoConfiguration                 (independent)
GracefulShutdownAutoConfiguration       (independent)
```

Kafka module auto-configurations run after their core counterparts:

```
AuditKafkaAutoConfiguration             (after AuditAutoConfiguration — adds Kafka publisher)
KafkaErrorAutoConfiguration             (independent)
KafkaHealthAutoConfiguration            (independent)
KafkaStreamsTracingAutoConfiguration     (after TracingAutoConfiguration)
```

### 11.3 Registration

Auto-configurations are registered via the standard Spring Boot 3.x mechanism:

```
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

Each module (core, Kafka) has its own imports file listing only its auto-configurations.

---

## 12. Configuration Reference

All properties with their defaults:

```yaml
payment:
  security:
    # --- Security Headers (SecurityHeadersAutoConfiguration) ---
    headers:
      enabled: true                        # master switch for all headers
      hsts:
        enabled: true
        max-age: 31536000                  # 1 year
        include-sub-domains: true
      content-type-options:
        enabled: true
      frame-options:
        enabled: true
        mode: DENY                         # DENY or SAMEORIGIN
      csp:
        enabled: true
        policy: "default-src 'self'"
      xss-protection:
        enabled: true                      # value is always "0" (rely on CSP)
      referrer-policy:
        enabled: true
        policy: strict-origin-when-cross-origin
      permissions-policy:
        enabled: true
        policy: "camera=(), microphone=(), geolocation=()"
      coop:
        enabled: false                     # disabled by default (Open Banking compat)
      corp:
        enabled: false
      coep:
        enabled: false

    # --- Audit Logging (AuditAutoConfiguration) ---
    audit:
      enabled: true
      log-request-body: false              # PII risk - disabled by default
      redact-patterns:
        - "password"
        - "token"
        - "accountNumber"
        - "iban"
      kafka:
        enabled: false                     # opt-in: publish audit events to Kafka
        topic: audit.events

    # --- Metrics (MetricsAutoConfiguration) ---
    metrics:
      namespace: nordpay                   # prefix for all custom metrics
      kafka-lag:
        enabled: true
        warn-threshold: 1000              # messages

    # --- Health (HealthAutoConfiguration) ---
    health:
      kafka:
        enabled: true
      database:
        enabled: true

    # --- Graceful Shutdown (GracefulShutdownAutoConfiguration) ---
    graceful-shutdown:
      timeout: 30s

    # --- Error Handling (ErrorHandlingAutoConfiguration) ---
    error-handling:
      enabled: true
      include-trace-id: true               # include traceId in Problem Details responses
      expose-internal-details: false        # never expose stack traces or class names (security)

    # --- Kafka Error Handler (KafkaErrorAutoConfiguration) ---
    kafka-error:
      enabled: true
      max-retries: 3                       # retries before sending transient errors to DLQ
      backoff-initial: 1000                # ms, initial backoff for retry
      backoff-multiplier: 2.0             # exponential backoff multiplier

    # --- Tracing (TracingAutoConfiguration) ---
    tracing:
      enabled: true
      kafka-streams-interceptors:
        enabled: true                      # auto-register when KafkaStreams on classpath
```

---

## 13. Testing Architecture

*(Decision C8: Testability Strategy)*

### 13.1 Three-Layer Test Pyramid

| Layer | Tool | What It Verifies | Speed | Count |
|-------|------|-----------------|-------|-------|
| 1 Unit | `ApplicationContextRunner` | Bean activation, conditional back-off, property overrides | Milliseconds | 30-50 |
| 2 Slice | `@WebMvcTest` / MockMvc | HTTP headers, RFC 9457 error format, `@Audited` log output, correlation ID | Seconds | 10-20 |
| 3 Integration | Sample app + Testcontainers | End-to-end: request -> headers + audit + Kafka event + metrics. Non-interference with service security config | Minutes | 5-10 |

### 13.2 Key Test Patterns

**Activation test (Layer 1):**
```java
new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(SecurityHeadersAutoConfiguration.class))
    .run(context -> assertThat(context).hasSingleBean(SecurityHeadersFilter.class));
```

**Back-off test (Layer 1):**
```java
new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(DefaultSecurityAutoConfiguration.class))
    .withUserConfiguration(CustomSecurityConfig.class)
    .run(context -> assertThat(context).doesNotHaveBean("defaultSecurityFilterChain"));
```

**Functional test (Layer 2):**
```java
mockMvc.perform(get("/api/test"))
    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
    .andExpect(header().string("Strict-Transport-Security", containsString("max-age=31536000")));
```

**Integration test (Layer 3):**
Sample app with Testcontainers (Kafka, PostgreSQL). Verifies the full stack including hash chain computation, Kafka audit event delivery, and Prometheus metric exposure.

### 13.3 Spring Boot Version Matrix

Layer 1 tests run against both the minimum supported (3.2.x) and latest (3.4.x) Spring Boot versions, validating the dependency management strategy (C7).

### 13.4 Sample Application

The `payment-security-starter-sample` serves dual purpose:
- **Integration test harness** — its test suite is Layer 3
- **Living documentation** — demonstrates all features (requirement AC-07)

It is maintained because the build breaks if it falls out of sync with the starter.

---

## 14. Versioning and Evolution

*(Decision C10: Versioning and Backward Compatibility)*

### 14.1 Unified Versioning

All four artifacts share one version number and are released together. Services always use a single version:

```kotlin
dependencies {
    implementation("com.nordpay:payment-security-starter:1.3.0")
    implementation("com.nordpay:payment-security-starter-kafka:1.3.0")
}
```

### 14.2 Semantic Versioning Contract

| Change | Bump | Rule |
|--------|------|------|
| **Breaking** | Major (X.0.0) | Remove property/class, change audit JSON structure, change hash algorithm, drop Spring Boot version |
| **New feature** | Minor (X.Y.0) | Add auto-configuration (**disabled by default**), add exception, add property, add metric |
| **Bugfix** | Patch (X.Y.Z) | Fix bug, CVE dependency bump, documentation |

**Critical invariant:** Minor version upgrades never change runtime behavior. New features default to disabled.

### 14.3 Deprecation Policy

- Deprecate in minor version: mark with `@Deprecated`, log startup WARNING with migration guide
- Remove in next major version
- Migration guides include specific instructions, not just "X is deprecated"

### 14.4 CI Compatibility

In the monorepo, a change to the starter triggers builds of all 6 services via Gradle's dependency graph. Breakage is caught before the starter is released.

---

## 15. Cross-System Contracts

This section summarizes what the starter guarantees to all consuming services. These are the interfaces that services depend on and that the starter must maintain across versions.

### 15.1 HTTP Contracts

| Contract | Guarantee |
|----------|-----------|
| Every HTTP response includes OWASP security headers | Unless individually disabled via property |
| Every error response is `application/problem+json` (RFC 9457) | For all exceptions: domain, Spring Security, Spring MVC, unhandled |
| Unhandled exceptions return 500 with no internal details | No stack traces, no class names, no internal message |
| Actuator health/prometheus endpoints are public | All other actuator endpoints require authentication |

### 15.2 Kafka Contracts

| Contract | Guarantee |
|----------|-----------|
| Every Kafka record carries a `traceparent` header | For both standard consumers and Kafka Streams |
| Audit events published to `audit.events` topic (when Kafka module present and configured) | Async, best-effort. Local log is the compliance guarantee, not Kafka |
| Consumer errors classified into transient/poisoned/fatal | Default classification for infrastructure exceptions. Services extend for domain exceptions |

### 15.3 Observability Contracts

| Contract | Guarantee |
|----------|-----------|
| All audit log entries include: timestamp, principal, action, traceId, responseStatus | Per `@Audited` or `AuditLogger` |
| Custom payment metrics exposed at `/actuator/prometheus` | Under configurable namespace |
| Hash-chained audit events with per-instance chains | SHA-256, canonical JSON, recoverable on restart |

### 15.4 Build Contracts

| Contract | Guarantee |
|----------|-----------|
| Build fails if Java version < 17 | Enforced by Gradle plugin |
| Build fails if `payment-security-starter` is not a dependency | Enforced by Gradle plugin |
| CycloneDX SBOM generated on every build | Silent, always-on |

---

## 16. Dependency Management

*(Decision C7: Transitive Dependency Management)*

### 16.1 Strategy

The starter aligns with Spring Boot's BOM. Dependencies managed by Spring Boot (Micrometer, Spring Kafka, Spring Security, Spring AOP, Spring Web) are declared without explicit versions — the consuming service's Spring Boot BOM provides versions. This ensures zero version conflicts by construction.

### 16.2 Dependency Table

| Dependency | Module | Declaration | Version Source |
|-----------|--------|-------------|----------------|
| Spring Web MVC | Core | `implementation` (unversioned) | Consumer's Spring Boot BOM |
| Spring AOP | Core | `implementation` (unversioned) | Consumer's Spring Boot BOM |
| Spring Security | Core | `compileOnly` (unversioned) | Consumer's BOM (may be absent) |
| Micrometer Core | Core | `implementation` (unversioned) | Consumer's Spring Boot BOM |
| Micrometer Tracing | Core | `implementation` (unversioned) | Consumer's Spring Boot BOM |
| Micrometer Prometheus Registry | Core | `implementation` (unversioned) | Consumer's Spring Boot BOM |
| logstash-logback-encoder | Core | `implementation` (pinned) | Starter pins version |
| Spring Kafka | Kafka | `implementation` (unversioned) | Consumer's Spring Boot BOM |
| Kafka Streams | Kafka | `compileOnly` (unversioned) | Consumer's BOM (may be absent) |
| `payment-domain-model` | Core | `api` (unified version) | Same version as starter |

### 16.3 Supported Range

**Spring Boot 3.2+**, enforced by the Gradle plugin. CI tests against 3.2.x (minimum) and 3.4.x (latest).

---

## 17. References

### Standards and Regulations

- [NIST SP 800-160 Vol.1 Rev.1](https://csrc.nist.gov/pubs/sp/800/160/v1/r1/final) — Engineering Trustworthy Secure Systems (33 security engineering principles)
- [OWASP Secure by Design Framework](https://owasp.org/www-project-secure-by-design-framework/) — Security integrated from design phase
- [OWASP Proactive Controls C4](https://top10proactive.owasp.org/the-top-10/c4-secure-architecture/) — "Address Security from the Start"
- [OWASP Secure Headers Project](https://owasp.org/www-project-secure-headers/) — Recommended HTTP security headers
- [EBA/GL/2019/04](https://www.eba.europa.eu/activities/single-rulebook/regulatory-activities/internal-governance/guidelines-ict-and-security-risk-management) — ICT and Security Risk Management for financial institutions
- [EU DORA](https://www.digital-operational-resilience-act.com/) — Digital Operational Resilience Act (EU 2022/2554, applied January 2025)
- [PCI DSS v4.0 Requirement 10](https://pcidssguide.com/pci-dss-logging-requirements/) — Logging and Monitoring requirements
- [W3C Trace Context](https://www.w3.org/TR/trace-context/) — Distributed tracing propagation standard
- [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) — Problem Details for HTTP APIs

### Books

- Kleppmann, M., *Designing Data-Intensive Applications*, O'Reilly (2017; 2nd ed. 2025 with Riccomini)
- Newman, S., *Building Microservices*, O'Reilly (2nd ed. 2021)
- Skelton, M. & Pais, M., *Team Topologies*, IT Revolution (2019)

### Papers

- Crosby, S. & Wallach, D., [Efficient Data Structures for Tamper-Evident Logging](https://static.usenix.org/event/sec09/tech/full_papers/crosby.pdf), USENIX Security 2009

### Real-World Systems

- [Skatteetaten Aurora Platform](https://github.com/Skatteetaten/aurora-spring-boot-starters) — Norwegian Tax Authority's Spring Boot starter ecosystem (closest real-world analogue)
- [Netflix Nebula Plugins](http://nebula-plugins.github.io/) — Organization-wide Gradle plugin patterns for dependency management and build enforcement
- [Zalando Logbook](https://github.com/zalando/logbook) — Extensible HTTP request/response logging as a Spring Boot starter
- [Mojaloop](https://docs.mojaloop.io/reference-architecture-doc/refarch/) — Open-source payment platform with explicit cross-cutting concerns architecture
- [Adyen Infrastructure](https://www.adyen.com/infrastructure) — Redundant, stateless payment SOA with Kafka backbone

### Conference Presentations

- Kim, D., [Distributed Tracing for Kafka with OpenTelemetry](https://www.confluent.io/events/kafka-summit-london-2022/distributed-tracing-for-kafka-with-opentelemetry/), Kafka Summit London 2022

### Specifications

- [OpenTelemetry Kafka Client Instrumentation](https://opentelemetry.io/blog/2022/instrument-kafka-clients/) — Trace context propagation through Kafka record headers
- [Saga Pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/saga) — Azure Architecture Center
- [Compensating Transaction Pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/compensating-transaction) — Azure Architecture Center
