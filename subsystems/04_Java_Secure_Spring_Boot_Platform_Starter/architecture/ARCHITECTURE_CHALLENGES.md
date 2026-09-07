# Platform Starter (#4) — Architectural Challenges

*Date: 2026-04-11*
*Consolidated brainstorming results.*
*Status: ALL DECIDED — decisions recorded in ARCHITECTURE_DECISIONS.md*

---

## Overarching Principles

These are not individual challenges but foundational principles that inform every decision below. They emerged from brainstorming and are strongly validated by research literature and regulatory requirements.

### P1: Security by Design ("Fortress Architecture")

The system must be secure by construction, not by afterthought. Security is structural — enforced by the platform — not behavioral (left to individual service teams). Every default is the most restrictive option; relaxation requires explicit action.

**Authoritative backing:**
- NIST SP 800-160 Vol.1 Rev.1: "Protective Defaults", "Defense in Depth", "Least Privilege", "Mediated Access"
- OWASP Secure by Design Framework and Proactive Controls C4
- EBA/GL/2019/04 (ICT and Security Risk Management)
- EU DORA (Digital Operational Resilience Act, applied Jan 2025): "resilience by design"

**Implication for decisions below:** When evaluating options, prefer the option that fails secure. If an option requires services to opt *in* to security, reject it in favor of one where services must opt *out*.

### P2: Crash Resilience by Design

The system must be designed from the start to handle crashes mid-transaction — whether from hardware failure, denial-of-service attack, or any other cause. Recovery must be possible without data loss or inconsistency. This is not an edge case to handle later; it is a primary design driver.

**Authoritative backing:**
- Kleppmann (*Designing Data-Intensive Applications*): async event log + idempotent writes over distributed transactions
- EU DORA: mandatory business continuity and disaster recovery plans, mandatory disruption testing
- Saga pattern literature (Microsoft, AWS): orchestrator state persistence, compensating transactions, idempotent participants

**Implication for decisions below:** When evaluating options, prefer the option that has well-defined crash behavior. "What happens if the process dies at this exact line?" must have a clear answer for every critical path.

---

## Challenges Already Resolved

The following items were raised during brainstorming but are already resolved by standard Spring Boot patterns or by decisions made during requirements engineering. They do not need further architectural discussion.

| Item | Resolution |
|------|-----------|
| **Auto-configuration conflict with service-specific config** | Standard `@ConditionalOnMissingBean` / `@ConditionalOnProperty` pattern. Thoroughly documented in Spring Boot. Each service can override any starter bean. (Requirements: STARTER-F08, STARTER-F09, STARTER-NF06) |
| **Configuration granularity vs. simplicity** | Standard `@ConfigurationProperties` with sensible defaults. Zero-config for 80% case, full override via `application.yml`. Property namespace `payment.security.*` avoids clashes. (Requirements: Section 6 defines the full property tree) |
| **Security header behavior with/without Spring Security** | Resolved in gap analysis: `@ConditionalOnMissingBean(SecurityFilterChain.class)`. If Spring Security is configured by the service, starter defers. If absent, starter provides a plain servlet filter. (Gap analysis recommendation, accepted into STARTER-F09b) |
| **Graceful shutdown mechanism** | Spring Boot 3.2+ provides built-in graceful shutdown (`server.shutdown=graceful`). Starter enables it by default and configures timeout. No novel design needed. (Requirements: STARTER-F40 to F43) |

---

## Architectural Challenges — Prioritized

### Priority 1: Cross-System Impact (decisions that constrain other sub-systems)

---

#### C1. Defense in Depth — Layered Security Model

**What needs deciding:** The starter contributes to security at multiple layers (browser/HTTP, application, transport, detection). What exactly is the starter's responsibility at each layer, and where does the boundary lie between "starter provides" and "service must implement"?

**Why it matters:** If the boundary is unclear, either (a) services duplicate what the starter does, or (b) gaps appear where neither the starter nor the service covers a concern. With 6 services depending on the starter, a wrong boundary affects all of them.

**Scope:** Cross-system (defines the security contract between starter and all services)

**Sub-questions:**
- Which security layers does the starter own entirely vs. provide defaults for?
- Should the starter provide a default Spring Security `SecurityFilterChain` (deny-all), or only security headers without Spring Security?
- Should the starter enforce HTTPS-only (redirect HTTP→HTTPS), or is that an infrastructure concern (ingress/load balancer)?
- How does the starter interact with A4's OAuth2 Authorization Server and A5's resource server configuration?

---

#### C2. Distributed Tracing Across REST and Kafka Streams

**What needs deciding:** How does trace context propagate end-to-end across synchronous REST calls and asynchronous Kafka messaging, including Kafka Streams topologies (used by A2 and A5)?

**Why it matters:** Without end-to-end tracing, debugging a payment that flows through A3→A6→A2→A5→A1 is nearly impossible. The starter is the natural place to provide tracing infrastructure, but Kafka Streams has less mature tracing support than standard `@KafkaListener` consumers.

**Scope:** Cross-system (A2 and A5 use Kafka Streams; all services use REST; the starter provides the tracing infrastructure)

**Sub-questions:**
- W3C Trace Context via Micrometer Tracing (standard for REST) — how to bridge into Kafka record headers?
- Custom `KafkaStreamsTracingInterceptor` — what is the contract? Does the starter provide it, or just the interface?
- How does the correlation ID (`X-Correlation-Id`) relate to the trace ID? Same value, or separate concepts?
- What happens to trace context when a Kafka Streams topology fans out (one input → multiple outputs)?

---

#### C3. Error Handling Standardization (RFC 9457 + Kafka Error Classification)

**What needs deciding:** The starter is responsible for two error handling patterns from the master architecture: RFC 9457 (Problem Details) for REST errors, and three-category classification for Kafka consumer errors (transient → retry, poisoned → DLQ, fatal → alert). How are these implemented, and what is the contract with consuming services?

**Why it matters:** If each service implements its own error mapping, clients get inconsistent error responses. If Kafka error classification varies per service, retry/DLQ behavior becomes unpredictable.

**Scope:** Cross-system (all services expose REST APIs; A1, A2, A3, A5 consume from Kafka)

**Sub-questions:**
- Does the starter provide a base exception hierarchy (`PaymentException`, `TransientException`, `ValidationException`) that services extend?
- How does the RFC 9457 handler interact with Spring Security's own error handling (401, 403)?
- For Kafka: who defines which exceptions are transient vs. poisoned vs. fatal — the starter or the service?
- Should the three-category Kafka error handler be configurable (e.g., custom exception-to-category mapping)?

---

#### C4. Starter Module Structure — Single JAR vs. Multi-Module

**What needs deciding:** Is the starter a single JAR containing all auto-configurations (security, audit, metrics, health, tracing, error handling), or is it split into multiple modules that services can adopt independently?

**Why it matters:** A single JAR is simpler but pulls in dependencies that not every service needs (e.g., A6 doesn't use Kafka, so Kafka-related auto-configs and their transitive deps are unnecessary). Multiple modules add build complexity but reduce dependency footprint.

**Scope:** Cross-system (affects the dependency graph of all 6 services)

**Sub-questions:**
- Single JAR with `@ConditionalOnClass` (auto-configs only activate if the relevant library is on the classpath) vs. separate JARs (`payment-starter-core`, `payment-starter-kafka`, `payment-starter-metrics`)?
- If multi-module: what is the minimum core that every service must include?
- How does this interact with the Gradle plugin's "required dependency" enforcement (STARTER-F53)?
- What is the impact on transitive dependency management?

---

### Priority 2: Regulatory / Compliance Impact

---

#### C5. Audit Log Integrity — Hash Chain Design and Crash Recovery

**What needs deciding:** The audit hash chain (decision F.2a from master architecture) computes `hash(previous_hash + current_payload)` for each audit event, providing tamper evidence. The "previous hash" lives in memory. What happens when a service crashes or restarts?

**Why it matters:** PCI DSS 10.5 requires tamper-proof audit trails. A hash chain that silently restarts from "GENESIS" on every deployment creates a gap that undermines the tamper-evidence property. In a Kubernetes environment with rolling deployments, restarts are frequent.

**Scope:** Starter-internal implementation, but the integrity guarantee affects compliance posture of the entire system

**Sub-questions:**
- Per-service chain (current design) vs. global chain (requires cross-service coordination)?
- Crash recovery: persist last hash to DB? To local file? Recover from Kafka topic (read last event)?
- What is the verification procedure? Who runs it, how often?
- Does the chain need to be cryptographically signed (per-event digital signature), or is hash chaining sufficient for our compliance needs?
- How does rolling deployment (two instances of the same service running simultaneously) affect the chain?

---

#### C6. Kafka Audit Publisher — Failure Semantics

**What needs deciding:** The audit publisher sends events to Kafka. What happens when Kafka is unavailable? The requirement (STARTER-S05) says "fire-and-forget with retry" and "must not fail the request." But there's a spectrum of failure-handling strategies with different trade-offs.

**Why it matters:** Audit events are a compliance requirement (PCI DSS 10.2). "Lost" audit events could be a compliance finding. But blocking a payment transaction because the audit system is down is also unacceptable.

**Scope:** Starter-internal, but the failure semantics define the audit SLA for the entire platform

**Sub-questions:**
- Fire-and-forget (async send, ignore failures) vs. fallback-to-local-log vs. bounded in-memory retry queue vs. circuit breaker?
- If fallback-to-local-log: how are local log entries later reconciled with the Kafka audit topic?
- If bounded queue: what happens when the queue is full? Drop oldest? Drop newest? Block?
- Should there be an alert/metric when audit events are being dropped or falling back?
- Does the hash chain still advance when using the fallback path?

---

### Priority 3: Starter-Internal Quality (important, but decisions are contained)

---

#### C7. Transitive Dependency Management Strategy

**What needs deciding:** The starter depends on libraries (Micrometer, logstash-logback-encoder, Spring AOP, potentially Kafka client, Spring Security). How do we prevent version conflicts with the consuming services?

**Why it matters:** A version conflict can cause runtime `NoSuchMethodError` or `ClassNotFoundException` — silent, hard-to-debug failures in production. With 6 services at potentially different Spring Boot versions, the risk is real.

**Scope:** Starter-internal, but affects build stability of all consuming services

**Sub-questions:**
- Declare most dependencies as `compileOnly` / `optional` and use `@ConditionalOnClass` vs. publish a BOM (Bill of Materials)?
- Should the starter pin specific library versions, or defer to the consuming service's dependency management?
- Should the Gradle plugin enforce a minimum Spring Boot version?

---

#### C8. Testability Strategy for Auto-Configurations

**What needs deciding:** How do we verify that each auto-configuration (a) activates correctly with default settings, (b) backs off when the service provides its own bean, (c) respects property overrides, and (d) works with real infrastructure?

**Why it matters:** An auto-configuration bug affects all 6 services simultaneously. The starter must have higher test coverage than a normal service because its blast radius is the entire platform.

**Scope:** Starter-internal

**Sub-questions:**
- `ApplicationContextRunner` (lightweight, fast) for activation/deactivation tests vs. full `@SpringBootTest`?
- Testcontainers for Kafka and PostgreSQL integration tests?
- Should the sample application (`payment-security-starter-sample`) be the primary integration test, or a separate test suite?
- How to test that the starter doesn't break a service's existing Spring Security configuration?

---

#### C9. Gradle Plugin — Scope and Enforcement Model

**What needs deciding:** What exactly does the Gradle plugin enforce, and how strictly? Can services opt out of specific checks?

**Why it matters:** Too strict → services fight the plugin and work around it. Too lenient → the plugin adds complexity without value. The plugin is a governance tool; its effectiveness depends on getting the enforcement model right.

**Scope:** Starter-internal (build tooling), but affects developer experience across all 6 service teams

**Sub-questions:**
- Fail the build (hard enforcement) vs. emit warnings (soft enforcement) for each check?
- Minimum Java version: fail the build (STARTER-F51 says "must")?
- Banned dependencies: fail or warn?
- Required dependencies (e.g., the starter itself): fail or warn?
- SBOM generation: always-on or opt-in?
- Should the plugin be a `buildSrc` module (monorepo) or a published plugin (independent versioning)?

---

#### C10. Versioning and Backward Compatibility Strategy

**What needs deciding:** How does the starter evolve over time without breaking all 6 services simultaneously? What is the contract for breaking vs. non-breaking changes?

**Why it matters:** The starter is a single point of coupling for the entire platform. A careless breaking change causes 6 simultaneous build/runtime failures.

**Scope:** Starter-internal, but affects the upgrade cadence of all services

**Sub-questions:**
- Semantic versioning (major = breaking, minor = new features, patch = bugfixes)?
- Deprecation policy: how long before a deprecated feature is removed?
- Should CI build all 6 services against the starter's latest snapshot (integration test matrix)?
- New auto-configurations: default enabled or disabled in minor versions?

---

## Challenges Considered But Deferred

| Item | Reason for Deferral |
|------|-------------------|
| **WebFlux support** | All 6 services use Spring MVC (Servlet). WebFlux starter is a future extension if needed. (Gap analysis: "Acceptable for a portfolio project") |
| **Multi-tenancy** | Not in scope for the current payment platform. Single-tenant design. |
| **Internationalization of error messages** | RFC 9457 Problem Details supports `type` URIs for machine-readable errors. Human-readable messages are a UI concern, not a starter concern. |

---

## Status

All 10 challenges have been worked through. Decisions are recorded in [ARCHITECTURE_DECISIONS.md](ARCHITECTURE_DECISIONS.md).
