# Platform Starter (#4) — Architecture Decisions

*Date: 2026-04-11*
*Decisions made during architectural design. Each maps to a challenge in [ARCHITECTURE_CHALLENGES.md](ARCHITECTURE_CHALLENGES.md).*
*References: Overarching principles P1 (Security by Design) and P2 (Crash Resilience by Design)*

---

## Decision Summary

| ID | Challenge | Decision | Scope |
|----|-----------|----------|-------|
| C1 | Defense in Depth | Layered starter with explicit boundaries | Cross-system |
| C2 | Distributed Tracing | W3C Trace Context via Micrometer, unified correlation/trace ID, starter-provided Kafka Streams interceptors | Cross-system |
| C3 | Error Handling | Base exception hierarchy in shared JAR + RFC 9457 mapping; three-category Kafka error handler with default classification and service override | Cross-system |
| C4 | Module Structure | Two modules (core + Kafka) plus shared domain model JAR and Gradle plugin | Cross-system |
| C5 | Audit Hash Chain | Per-instance chain, crash recovery from Kafka, SHA-256 over canonical JSON | Compliance |
| C6 | Kafka Audit Publisher | Dual-write (Kafka + local log), async Kafka, local log as compliance backstop | Compliance |
| C7 | Dependency Management | Align with Spring Boot's BOM, no version pinning for Spring-managed deps | Starter-internal |
| C8 | Testability | Three-layer pyramid: ApplicationContextRunner, MockMvc, sample app + Testcontainers | Starter-internal |
| C9 | Gradle Plugin | Tiered enforcement (fail/warn), includeBuild for dev + published for prod, externalized ban list | Starter-internal |
| C10 | Versioning | Unified versioning across all artifacts, semver, deprecate-in-minor/remove-in-major | Starter-internal |

---

## C1. Defense in Depth — Layered Security Model

**Decision:** Layered Starter with Explicit Boundaries

The starter owns specific security layers fully, provides overridable defaults for others, and explicitly declares some layers out of scope.

### Layer Responsibility Matrix

| Layer | Starter Role | Mechanism |
|-------|-------------|-----------|
| 0 Network/Infrastructure | **Out of scope** | TLS termination, network policy = Kubernetes/ingress concern |
| 1 HTTP Transport | **Owns fully** | Security headers on every response (OWASP-aligned). Service can disable individual headers via properties but cannot accidentally skip them |
| 2 Authentication | **Provides overridable default** | Deny-all `SecurityFilterChain` via `@ConditionalOnMissingBean`. Service replaces entirely when it defines its own `SecurityFilterChain` |
| 3 Authorization | **Provides actuator rules only** | Actuator endpoints: health/prometheus = public, all others = authenticated. Service-specific API authorization is the service's job |
| 4 Input Validation | **Out of scope** | Too service-specific. Starter does not auto-configure input filters |
| 5 Business Logic | **Out of scope** | Always a service concern |
| 6 Detection | **Owns fully** | Audit logging, metrics, health probes, correlation ID, distributed tracing. Always active |
| 7 Data Protection | **Partial** | Audit field redaction (owns). HSTS header (owns). Encryption at rest = infrastructure. Field-level encryption = service |

### Key Contracts

- **HTTPS enforcement** is an infrastructure concern (ingress/load balancer), not the starter's. The starter contributes HSTS (tells browsers to use HTTPS).
- **CSRF protection** is default ON as part of the deny-all SecurityFilterChain. When a service overrides the SecurityFilterChain, the service decides on CSRF.
- **Override visibility:** When a service provides its own `SecurityFilterChain`, the starter logs a WARNING at startup: *"Custom SecurityFilterChain detected — starter deny-all default is not active. Ensure your configuration follows the platform security policy."*

### Rationale

- Preserves the fortress principle (P1): new services are deny-all by default
- Clean override path via standard Spring Security mechanism (`@ConditionalOnMissingBean`)
- Security headers and audit logging work independently of the SecurityFilterChain — they are on separate filter chains
- Matches NIST SP 800-160 "Defense in Depth": multiple independent layers, each functioning regardless of others
- Follows the Skatteetaten Aurora pattern and Sam Newman's guidance: shared infrastructure, not shared business logic

---

## C2. Distributed Tracing Across REST and Kafka Streams

**Decision:** W3C Trace Context via Micrometer Tracing; unified correlation/trace ID; starter-provided Kafka Streams interceptors

### Sub-decisions

#### 2a: Tracing Protocol — W3C Trace Context via Micrometer Tracing

Spring Boot 3.2+ has built-in Micrometer Tracing support. Micrometer is already a dependency (metrics). Using Micrometer Tracing keeps one abstraction for both metrics and traces. W3C Trace Context (`traceparent` / `tracestate`) is the propagation format.

#### 2b: Correlation ID vs. Trace ID — Unified

The Micrometer-generated `traceId` serves as the correlation ID for internal traffic. For external-facing APIs (A4's PSD2 endpoints), an incoming `X-Correlation-Id` is stored as **baggage** on the trace. Audit logs include both:
- `traceId` — always W3C format, primary search key
- `externalCorrelationId` — if provided by external caller

#### 2c: Kafka Streams Tracing — Starter-Provided Interceptors

The starter ships a `ProducerInterceptor` + `ConsumerInterceptor` pair:
- On consume: extracts `traceparent` from incoming record headers, creates a child span
- On produce: injects `traceparent` into outgoing record headers from the current span

Auto-configured when Kafka Streams is on the classpath (`@ConditionalOnClass(KafkaStreams.class)`). Services using standard `@KafkaListener` get tracing automatically via Micrometer's built-in support.

### Cross-System Contract

Every Kafka record carries a `traceparent` header. Every REST call carries a `traceparent` header. The starter ensures both. Services using standard Spring mechanisms get this for free. Services using Kafka Streams get it via the starter's interceptors.

### Rationale

- Micrometer Tracing is native to Spring Boot — zero additional framework
- Unified ID eliminates "which ID do I search for?" confusion
- Starter-provided interceptors ensure consistent tracing across A2 and A5's Kafka Streams topologies (~50-100 lines of focused code)
- Pattern validated by Confluent's Kafka Summit 2022 presentation on OpenTelemetry + Kafka

---

## C3. Error Handling Standardization

**Decision:** Base exception hierarchy in shared JAR; RFC 9457 global handler; three-category Kafka error handler with default classification and service override

### Sub-decisions

#### 3a: REST Error Handling — Base Exception Hierarchy

Exception hierarchy lives in a **separate, lightweight JAR** (`payment-domain-model`) with zero dependencies:

```
PaymentException (abstract)
+-- PaymentValidationException    -> 400 Bad Request
+-- PaymentNotFoundException      -> 404 Not Found
+-- PaymentConflictException      -> 409 Conflict
+-- PaymentForbiddenException     -> 403 Forbidden
+-- PaymentRateLimitException     -> 429 Too Many Requests
+-- PaymentInternalException      -> 500 Internal Server Error
```

The starter's `@ControllerAdvice` maps these to RFC 9457 Problem Details (`application/problem+json`). Also handles:
- Spring Security exceptions (401, 403) -> RFC 9457 format
- Spring MVC exceptions (MethodArgumentNotValid -> 400, etc.) -> RFC 9457 format
- Unhandled exceptions -> 500, no internal details exposed (no stack traces, no class names)

#### 3b: Kafka Error Classification — Default + Service Override

Three error categories:

| Category | Action |
|----------|--------|
| Transient | Retry with backoff. After N retries, send to DLQ |
| Poisoned | Send directly to DLQ. No retry |
| Fatal | Alert + stop the consumer. Human intervention needed |

Default classification provided by the starter:

| Exception Type | Default Category |
|----------------|-----------------|
| `DeserializationException` | Poisoned |
| `PaymentValidationException` | Poisoned |
| `TransientDataAccessException` | Transient |
| `KafkaTimeoutException` | Transient |
| `OutOfMemoryError`, `StackOverflowError` | Fatal |
| Everything else | Transient (safe default) |

Services register domain-specific overrides via a `KafkaErrorClassifier` bean:
```java
@Bean
public KafkaErrorClassifier customClassifier() {
    return KafkaErrorClassifier.builder()
        .poisoned(InvalidIbanException.class)
        .fatal(SanctionsListLoadFailureException.class)
        .build();
}
```

The starter merges the service's classifier with defaults (service takes precedence). Every categorization decision is logged at INFO level.

### Rationale

- Base exception hierarchy in a zero-dependency JAR avoids coupling domain logic to Spring
- RFC 9457 gives clients a universal, machine-readable error format across all 6 services
- Default Kafka classification handles infrastructure exceptions consistently; services only classify domain-specific exceptions
- "Everything else = transient" is the safe default: retry + DLQ preserves the message for inspection. Fatal (stop consumer) is reserved for situations where continuing would cause data corruption

---

## C4. Module Structure

**Decision:** Two starter modules (core + Kafka) plus shared domain model JAR and Gradle plugin

### Artifact Structure

```
payment-domain-model                  -> exceptions, @Audited, shared value objects (zero deps)
payment-security-starter              -> core auto-configs (headers, audit, metrics, tracing, errors, shutdown)
payment-security-starter-kafka        -> Kafka auto-configs (audit publisher, health, error handler, Streams tracing)
payment-security-gradle-plugin        -> build-time enforcement (Java version, deps, SBOM)
```

### Service Consumption

| Service | Dependencies |
|---------|-------------|
| A6 (VoP — no Kafka) | `payment-security-starter` only |
| A1, A2, A3, A4, A5 | `payment-security-starter` + `payment-security-starter-kafka` |
| All services | `payment-domain-model` (transitive via starter or direct) |
| All builds | `payment-security-gradle-plugin` |

### Key Properties

- **Core module has no conditional activation** — every dependency is guaranteed present on any Spring Boot web application's classpath. No silent `@ConditionalOnClass` skips.
- **Kafka module** uses `@ConditionalOnClass(KafkaStreams.class)` internally for the Streams tracing interceptor only — one level of conditionality.
- **Gradle plugin** enforces `payment-security-starter` (core) for all services. Kafka module is required only if `spring-kafka` is on the classpath.

### Rationale

- The split matches the real dependency fault line: Kafka vs. non-Kafka
- Two modules is sustainable — not over-engineering for a 6-service platform
- Core module's zero-conditionality eliminates the silent-skip problem of a single JAR with many optional dependencies
- Follows the same pattern as Spring Boot itself (`spring-boot-starter-web` vs. `spring-boot-starter-kafka`)

---

## C5. Audit Log Integrity — Hash Chain Design and Crash Recovery

**Decision:** Per-instance chain; crash recovery from Kafka; SHA-256 over canonical JSON

### Sub-decisions

#### 5a: Chain Scope — Per-Instance

Each JVM instance maintains its own independent hash chain. Events include `instanceId` (Kubernetes pod name). Kafka topic is the authoritative store.

**Why not per-service:** Would require distributed coordination (locking or single-writer) — adds fragility to the audit path, which must be the most reliable component in the system.

**Why not global:** Impractical for a distributed system. Cross-service coordination for audit writes would be a bottleneck.

**Weakness mitigation:** Chain deletion detection relies on Kafka's append-only retention (minimum 1 year per PCI DSS 10.7). Verification groups events by `instanceId`, orders by `sequenceNumber`, verifies each chain independently.

#### 5b: Crash Recovery — Recover from Kafka, Fallback to GENESIS

On startup:
1. Attempt to read the last event for this `instanceId` from the `audit.events` Kafka topic
2. If found -> resume chain from that hash
3. If not found (first-ever start) or Kafka unavailable -> start from GENESIS marker, log WARNING

**Properties:** Chain continuity across normal restarts and crashes. No additional infrastructure beyond Kafka. Graceful degradation (GENESIS marker is an honest, detectable signal). Startup read is bounded (last 10 events for this instanceId, milliseconds).

#### 5c: Hash Algorithm — SHA-256 over Canonical JSON

`currentHash = SHA-256(previousHash + canonicalJson(eventPayload))`

The hash input is the previous chain hash concatenated with the canonical JSON of the event's **payload fields only** — excluding chain metadata (`instanceId`, `sequenceNumber`, `previousHash`, `currentHash`) to avoid circular dependency and double-counting.

Canonicalization: payload fields sorted alphabetically, no whitespace, UTC timestamps in ISO 8601, numbers as-is. Implemented once in `AuditEventSerializer`, tested with golden-file tests (known input -> known hash).

**Why not HMAC-SHA-256:** Adds key management complexity better addressed at infrastructure level (Kafka ACLs). Hash chaining is sufficient for PCI DSS 10.5. Digital signatures are a future enhancement if needed.

### Audit Event Structure

```json
{
  "instanceId": "clearing-engine-pod-2",
  "sequenceNumber": 4817,
  "previousHash": "a3f2b8c1...",
  "currentHash": "7d4e9f0a...",
  "timestamp": "2026-04-12T10:23:45.123Z",
  "principal": "tpp-client-42",
  "action": "POST /api/v1/payments",
  "traceId": "550e8400e29b41d4a716446655440000",
  ...
}
```

### Verification Procedure

Group by `instanceId` -> order by `sequenceNumber` -> recompute hashes sequentially. Any mismatch flags the exact tampered event. GENESIS markers flag expected chain breaks.

---

## C6. Kafka Audit Publisher — Failure Semantics

**Decision:** Dual-write (Kafka + local log); async Kafka send; local log as compliance backstop

### Mechanism

Every audit event is written to **two destinations simultaneously**:
1. **Local SLF4J logger** (`audit`), structured JSON — synchronous, always succeeds
2. **Kafka topic** (`audit.events`) — async best-effort via `KafkaTemplate.send()`

### Failure Behavior

| Scenario | Behavior |
|----------|----------|
| Kafka healthy | Event in both local log and Kafka |
| Kafka unavailable | Event in local log only. WARN log + `audit.kafka.send.failure` metric incremented |
| Local disk full | Event in Kafka only (SLF4J fails silently or throws — Logback handles this) |
| Both down | Catastrophic infrastructure failure, beyond starter scope |

### Key Properties

- **Zero impact on request latency.** Kafka send is async. Local log write is buffered.
- **Hash chain advances on both paths.** Hash computed before writing to either destination. Both stores contain identical hashes.
- **PCI DSS 10.2 satisfied by local log alone.** Kafka is supplementary (centralized querying), not the compliance mechanism.
- **Reconciliation procedure documented:** Identify Kafka gaps by timestamp, backfill from local logs of affected instances.
- **Metric for alerting:** `audit.kafka.send.failure` counter (tagged by service, error type) enables Grafana alerts on degraded audit delivery.

### Rationale

- Simplest option that satisfies PCI DSS — no queues, no retry threads, no circuit breaker tuning
- Survives every single-component failure mode
- No state to manage in the starter
- Local log files are already collected by standard infrastructure (Fluentd, Filebeat)

---

## C7. Transitive Dependency Management

**Decision:** Align with Spring Boot's BOM; don't pin versions for Spring-managed dependencies

### Dependency Declaration Strategy

| Dependency type | Declaration | Version source |
|----------------|-------------|----------------|
| Spring-managed (Micrometer, Kafka, Security, AOP, Web) | `compileOnly` or unversioned `implementation` | Consumer's Spring Boot BOM |
| Non-Spring (logstash-logback-encoder) | `implementation` with explicit version | Starter pins it |
| Domain model JAR | Zero external dependencies | No conflict possible |

### Supported Spring Boot Range

**Spring Boot 3.2+**, enforced by the Gradle plugin (STARTER-F51). CI matrix tests against minimum (3.2.x) and latest (3.4.x).

### Rationale

- Standard approach for Spring Boot ecosystem starters (Spring Cloud, Axon, Zalando Logbook all follow this)
- Zero version conflicts for BOM-managed dependencies by construction
- Non-Spring dependencies are few and stable (no overlap with Spring Boot's tree)
- The Gradle plugin's minimum version enforcement bounds the supported range

---

## C8. Testability Strategy

**Decision:** Three-layer test pyramid: ApplicationContextRunner, MockMvc, sample app + Testcontainers

### Test Layers

| Layer | Tool | What It Tests | Speed | Approximate Count |
|-------|------|--------------|-------|-------------------|
| 1 Unit | `ApplicationContextRunner` | Activation, back-off, property overrides, bean wiring | ms per test | 30-50 tests |
| 2 Slice | `@WebMvcTest` / MockMvc | HTTP headers, RFC 9457 errors, `@Audited` output, correlation ID | seconds per class | 10-20 tests |
| 3 Integration | Sample app + Testcontainers | End-to-end golden path, non-interference with service config | minutes | 5-10 tests |

### Key Patterns

- **Companion configuration test** (Layer 1): Verifies `@ConditionalOnMissingBean` back-off by supplying a `UserConfiguration` with a custom `SecurityFilterChain`.
- **Sample app as dual-purpose artifact:** Integration test harness + living documentation (requirement AC-07). Maintained because the build breaks if it falls out of sync.
- **Spring Boot version matrix:** Layer 1 tests run against min (3.2.x) and latest (3.4.x) to validate C7 decision.

### CI Pipeline

Layer 1 + 2 on every commit. Layer 3 on every commit (acceptable build time for a starter project).

### Rationale

- Matches the test pyramid: many fast tests, fewer slow tests
- Each layer earns its keep — no duplication
- Follows Spring Boot's own testing pattern (ApplicationContextRunner + smoke tests)

---

## C9. Gradle Plugin — Scope and Enforcement Model

**Decision:** Tiered enforcement; includeBuild for dev + published for prod; externalized ban list

### Sub-decisions

#### 9a: Enforcement Level — Tiered

| Check | Enforcement | Rationale |
|-------|-------------|-----------|
| Minimum Java version (F51) | **Fail build** | Non-negotiable platform requirement |
| Starter dependency present (F53) | **Fail build** | Enforcement mechanism for P1 (fortress principle) |
| Banned dependencies (F52) | **Warn** (configurable to fail) | Progressive enforcement — warn first, escalate later |
| SBOM generation (F50) | **Silent, always-on** | Adds CycloneDX task, no pass/fail |

#### 9b: Distribution — Dual Mode

- **Development:** `includeBuild` in `settings.gradle.kts` — fast iteration, immediate feedback
- **Production/portfolio:** Published to Maven repository with version number

Services use either mode via `settings.gradle.kts` configuration.

#### 9c: Ban List — Externalized

Ban list maintained as a YAML configuration file, not hardcoded in plugin source. Categories:
- **Known CVE libraries:** `commons-collections:3.x`, `log4j:1.x`
- **Superseded:** `javax.*` when `jakarta.*` equivalents exist
- **Insecure crypto:** Specific known-bad libraries (not broad pattern matches)
- **License-incompatible:** Project-specific, added when needed

### Plugin Extension API

```kotlin
paymentPlatform {
    minJavaVersion = 17
    requireStarter = true
    bannedDependencies {
        configFile = file("banned-dependencies.yml")
        failOnViolation = false
    }
    sbom {
        enabled = true
    }
}
```

### Rationale

- Tiered enforcement balances governance with developer experience
- The two hard-fail checks (Java version, starter presence) are the minimum for P1
- Externalized ban list allows updates without plugin release
- Dual distribution mode supports both fast development and proper release management

---

## C10. Versioning and Backward Compatibility

**Decision:** Unified versioning across all 4 artifacts; semantic versioning; deprecate-in-minor, remove-in-major

### Sub-decisions

#### 10a: Versioning Scheme — Unified

All 4 artifacts (`payment-domain-model`, `payment-security-starter`, `payment-security-starter-kafka`, `payment-security-gradle-plugin`) share one version number. Released together.

**Why:** For a 4-artifact, 6-consumer platform, compatibility clarity outweighs the cost of occasional no-op version bumps. Services always use one version number for everything.

#### 10b: Semantic Versioning Contract

| Change Type | Bump | Examples |
|------------|------|---------|
| **Major** (breaking) | X+1.0.0 | Remove config property. Remove exception class. Change audit JSON structure. Change hash algorithm. Drop Spring Boot version support |
| **Minor** (new feature) | X.Y+1.0 | Add new auto-configuration (**disabled by default**). Add new exception. Add new config property with default. Add new metric. Support new Spring Boot version |
| **Patch** (bugfix) | X.Y.Z+1 | Fix bug. CVE dependency bump. Documentation fix |

**Critical contract:** New auto-configurations default to **disabled** in minor versions. Enabled by default only in the next major. Minor version upgrades never change runtime behavior.

#### 10c: Deprecation Policy

Deprecate in minor version, remove in next major. Deprecated features produce a **startup WARNING with migration guide** — not just "X is deprecated" but "X is deprecated, use Y instead. See [link] for migration steps."

#### 10d: CI Compatibility Verification

**Monorepo (current):** Starter change triggers build of all 6 services via Gradle's dependency graph. Breakage caught immediately.

**Multi-repo fallback (if ever):** Nightly integration build checks out latest of everything and builds the full platform.

### Example Version Lifecycle

```
1.0.0 — Initial release
1.1.0 — Add Kafka Streams tracing interceptor (disabled by default)
1.1.1 — Fix hash computation bug in AuditLogger
1.2.0 — Add rate limiting auto-config (disabled by default). Deprecate property X -> Y
1.2.1 — CVE fix in logstash-logback-encoder
2.0.0 — Remove deprecated property X. Enable Streams tracing by default. Drop Spring Boot 3.2
```

---

## References

### Standards and Regulations
- [NIST SP 800-160 Vol.1 Rev.1](https://csrc.nist.gov/pubs/sp/800/160/v1/r1/final) — Engineering Trustworthy Secure Systems
- [OWASP Secure by Design Framework](https://owasp.org/www-project-secure-by-design-framework/)
- [OWASP Proactive Controls C4](https://top10proactive.owasp.org/the-top-10/c4-secure-architecture/)
- [EBA/GL/2019/04](https://www.eba.europa.eu/activities/single-rulebook/regulatory-activities/internal-governance/guidelines-ict-and-security-risk-management) — ICT and Security Risk Management
- [DORA](https://www.digital-operational-resilience-act.com/) — Digital Operational Resilience Act (EU 2022/2554)
- [PCI DSS v4.0 Requirement 10](https://pcidssguide.com/pci-dss-logging-requirements/) — Logging and Monitoring
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
- [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) — Problem Details for HTTP APIs

### Books and Papers
- Kleppmann, *Designing Data-Intensive Applications*, O'Reilly (2017; 2nd ed. 2025)
- Newman, *Building Microservices*, O'Reilly (2nd ed. 2021)
- Skelton & Pais, *Team Topologies*, IT Revolution (2019)
- Crosby & Wallach, [Efficient Data Structures for Tamper-Evident Logging](https://static.usenix.org/event/sec09/tech/full_papers/crosby.pdf), USENIX Security 2009

### Real-World Systems Studied
- [Skatteetaten Aurora Platform](https://github.com/Skatteetaten/aurora-spring-boot-starters) — closest real-world analogue
- [Netflix Nebula Plugins](http://nebula-plugins.github.io/) — Gradle plugin patterns
- [Zalando Logbook](https://github.com/zalando/logbook) — HTTP logging starter pattern
- [Mojaloop](https://docs.mojaloop.io/reference-architecture-doc/refarch/) — open-source payment platform, cross-cutting concerns architecture
- [Adyen Infrastructure](https://www.adyen.com/infrastructure) — stateless SOA, Kafka backbone

### Architecture Patterns
- [Saga Pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/saga) — Azure Architecture Center
- [Compensating Transaction Pattern](https://learn.microsoft.com/en-us/azure/architecture/patterns/compensating-transaction) — Azure Architecture Center
- [OpenTelemetry Kafka Instrumentation](https://opentelemetry.io/blog/2022/instrument-kafka-clients/)
- [Distributed Tracing for Kafka with OpenTelemetry](https://www.confluent.io/events/kafka-summit-london-2022/distributed-tracing-for-kafka-with-opentelemetry/) — Kafka Summit London 2022
