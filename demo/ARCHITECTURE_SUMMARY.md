# JRPay — Architecture Summary

## System Overview

JRPay is a distributed payment processing system consisting of six microservices, a shared domain model library, and a platform starter library. The services communicate via Kafka for asynchronous event-driven flows and REST for synchronous queries. Each service owns its own PostgreSQL database (database-per-service pattern). The architecture was designed through a systematic process: requirements engineering, architectural challenge identification, decision-making with documented rationale, and nine dedicated reviews (architecture consistency, security, crash resilience, financial integrity, regulatory compliance, scalability, performance, certification readiness, interoperability) with 138 findings identified and resolved before implementation began.

## Service Architecture

```
Payer → A3 (Bill Payment) or A4 (PSD2 API Gateway)
           │
           ├──→ A6 (VoP: verify beneficiary name before payment)
           │
           ├──→ A2 (Clearing Engine: orchestrate payment lifecycle)
           │       │
           │       ├──→ A1 (Account Ledger: reserve balance)
           │       ├──→ A5 (Fraud/Sanctions: score transaction)
           │       ├──→ A1 (confirm reservation, settle payment)
           │       └──→ Status update → A3/A4 → notify payer
           │
           └──→ A1 (reconciliation against settlement reports)
```

**A1 (Account Ledger)** uses event sourcing with Kafka as the event store. All state changes are immutable events published to the `ledger.events` topic. PostgreSQL projections are derived views, rebuildable by replaying events. The compound event model publishes debit and credit as a single Kafka message, making unbalanced entries structurally impossible. Balance reservations provide a hold mechanism for pending payments with a 30-second auto-release safety net.

**A2 (Clearing Engine)** is the payment orchestrator, deployed as separate JVM instances for batch and instant paths to ensure process isolation. The instant path uses a strict state machine where sanctions screening is structurally mandatory — no code path reaches settlement without passing through A5. A global 7-second hard-limit timer (per EPC 2025 SCT Inst rulebook) rejects any payment that cannot complete in time.

**A3 (Bill Payment)** and **A4 (PSD2 API Gateway)** are the entry points. A3 handles merchant-consumer flows with a 12-state R2P lifecycle state machine. A4 implements the full PSD2 OAuth2 consent flow with SCA, four exemption types, and consent enforcement on every request.

**A5 (Fraud/Sanctions)** implements a chain-of-responsibility scoring pipeline. Sanctions screening runs first and short-circuits on a hit — no further scoring occurs. The three-tier sanctions index (Bloom filter for fast pre-filtering, exact match, Jaro-Winkler fuzzy match) balances speed with accuracy.

**A6 (VoP Service)** is a stateless lookup and matching service with aggressive caching (Caffeine, 1-hour TTL). On cache miss, it fetches holder names from A1 via REST. The structural name matching pipeline decomposes names into components, handles Nordic diacritics per EPC288-23, and computes best-permutation Jaro-Winkler scores.

## Cross-Cutting Architecture

**Platform Starter.** Every service depends on the Platform Starter, which auto-configures: deny-all authentication (services must explicitly permit public endpoints), OWASP security headers on every response, RFC 9457 error handling (no stack traces exposed), and structured audit logging. The Gradle plugin fails the build if the starter dependency is missing.

**Interface Contracts.** A single authoritative document (`INTERFACE_CONTRACTS.md`) defines all inter-service interfaces: 18 Kafka topics with message keys, schemas, and timeout expectations; 4 inter-service REST contracts with paths, methods, and auth; shared enumerations (PaymentStatus, VoPResult, FraudDecision, SanctionsResult) defined once in the domain model and imported by all services; Kafka ACLs restricting which service can produce to which topic; and the GDPR erasure protocol across all services.

**Financial Integrity.** All monetary calculations use BigDecimal with HALF_EVEN rounding. The MonetaryAmount domain type enforces non-negative values, prevents cross-currency arithmetic, and derives decimal scale from ISO 4217 minor units per currency. PostgreSQL uses NUMERIC for all monetary columns. Kafka messages encode amounts as strings.

**Crash Resilience.** Every crash point in the instant payment flow was analyzed and has a documented recovery mechanism. Event sourcing (A1) provides inherent crash resilience — events are the source of truth, projections are rebuildable. Kafka Streams `exactly_once_v2` (A2, A5) ensures atomic processing. The transactional outbox pattern (A3, A4) handles the database-Kafka dual-write problem. All operations are idempotent, including bidirectional idempotency (confirming an already-released reservation is a no-op).

**Security by Construction.** Critical security properties are architecturally enforced, not policy-enforced. Sanctions screening cannot be bypassed (the state machine has no skip path). VoP actual names are never revealed on NO_MATCH (the response filter is structural, not configurable). Authentication is deny-all by default (the starter auto-configures it). These properties are continuously verified by adversarial tests in CI.

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 21 (Temurin LTS) |
| Framework | Spring Boot 3.4 |
| Build | Gradle 8.12 (Kotlin DSL) |
| Messaging | Apache Kafka (KRaft mode) |
| Database | PostgreSQL 16 (per-service, Flyway migrations) |
| Cache | Caffeine (W-TinyLFU eviction) |
| Name Matching | Apache Commons Text (Jaro-Winkler) |
| Sanctions | Guava Bloom Filter + Jaro-Winkler fuzzy match |
| Resilience | Resilience4j (circuit breaker, bulkhead) |
| Security | Spring Security (HTTP Basic for demo, mTLS for production) |
| Metrics | Micrometer + Prometheus |
| Testing | JUnit 5, jqwik (property-based), Testcontainers |
| Containers | Docker + Docker Compose |

## Reviewed and Verified

The architecture underwent nine dedicated reviews before implementation: architecture consistency (29 findings), security against OWASP ASVS Level 2 (22 findings), crash resilience with crash-point-by-crash-point analysis (12 findings), financial integrity (5 findings), regulatory compliance with EU and Norwegian legislation deep dive (42 findings), scalability and performance bottleneck identification (14 findings), certification readiness against EPC, PCI DSS, and DORA (7 findings), and interoperability against ISO 20022, ISO 13616, and BerlinGroup (7 findings). All 138 findings were resolved before implementation began. The implementation underwent a further 9 reviews (58 checks, 48 PASS, 5 fixed, rest documented as next-phase work).
