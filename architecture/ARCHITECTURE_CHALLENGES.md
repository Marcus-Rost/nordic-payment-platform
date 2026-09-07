# Architectural Challenges — Nordic Payment Processing Infrastructure

*Consolidated from brainstorming session, project research, and reference system analysis*
*Date: 2026-04-11*

---

## How to Read This Document

Each challenge describes a problem that the **architecture** must resolve. The requirements (system_requirements.md, Sections 1-11) specify *what* the system must do. This document identifies areas where the *how* — the structural design — still needs to be worked out.

Challenges already fully resolved during requirements engineering are excluded. Where requirements partially address a challenge, we note what is decided and what remains open.

Challenges are grouped by theme. The order within each theme reflects conceptual flow, not priority.

---

## Theme A: Security Architecture — "Security by Construction"

The system handles financial transactions and is subject to PSD2, AML, GDPR, DORA, and potentially PCI DSS-adjacent controls. Security cannot be an afterthought bolted on per service — it must be a structural property of the architecture.

### A.1 — Trust Boundaries and Defense-in-Depth Layers

**The challenge:** The system consists of 7 services, a message broker (Kafka), databases (PostgreSQL per service), and external interfaces (TPP APIs, webhook endpoints, external PSP queries for VoP). Where exactly are the trust boundaries? Which components trust each other, and to what degree?

**Why it matters:** A request arriving at the PSD2 API Gateway (A4) from an external TPP has passed zero internal validation. A Kafka message arriving at A1 from A2 has already passed through multiple services and should be trusted differently. Without explicit trust boundaries, either every service validates everything redundantly (wasteful, error-prone) or some services trust blindly (vulnerable).

**What is decided:**
- SEC-01 through SEC-12 define security controls per boundary type
- KM-06 specifies Kafka message-level HMAC signing (defense against broker compromise)
- A4-S09 specifies mTLS for external TPPs
- The Platform Starter (#4) enforces security headers and audit logging

**What remains open for architecture:**
- A formal trust boundary diagram: external zone, DMZ (A4, A3 merchant API, A6 responder API), internal zone (A2, A5, A1), infrastructure zone (Kafka, PostgreSQL)
- Whether services in the internal zone validate OAuth2 tokens or rely on service-to-service authentication only (mTLS, shared secret, or Kafka-level SASL)
- How the Platform Starter structurally *prevents* a developer from accidentally exposing an unauthenticated endpoint — is there an "authenticated by default, opt-out to make public" pattern?
- Network-level isolation strategy (network policies, firewall rules, or service mesh) — or whether this is documented as a deployment concern

**Reference:** Deutsche Bank uses a globally standardized Anti-Financial Crime (AFC) framework where security is organizational/structural, not per-service. FinTP makes sanctions filtering a mandatory routing step — payments physically cannot bypass screening.

### A.2 — Secrets, Keys, and Cryptographic Material Lifecycle

**The challenge:** The system uses multiple types of cryptographic material: JWT signing keys, Kafka SASL credentials, database encryption keys, HMAC secrets for webhooks, per-account encryption keys for crypto-shredding (GDPR), mTLS certificates. Each has different rotation schedules, compromise procedures, and storage requirements.

**Why it matters:** A compromised JWT signing key allows token forgery across all services. A compromised per-account encryption key exposes one account's PII. A compromised Kafka SASL credential gives access to the entire event stream. The blast radius differs enormously, but the architecture must handle all of them.

**What is decided:**
- KM-01 through KM-10 specify algorithms, rotation periods, storage, and compromise procedures
- KM-04 specifies KMS for production, Docker secrets for development

**What remains open for architecture:**
- The concrete key management topology: does each service contact a central KMS, or are keys distributed at deployment time? How does key rotation propagate to running instances?
- Crypto-shredding implementation detail: where are per-account encryption keys stored? How does A1 encrypt/decrypt event payloads? What happens to Kafka events when the key is deleted — do consumers handle decryption failure gracefully?
- Certificate management for mTLS between services: who issues certificates, what is the chain of trust, how are certificates rotated?

### A.3 — Threat Model and Attack Surface

**The challenge:** Before designing defenses, the architecture should identify what the system is defending against. A payment processing system faces: external attackers (credential stuffing, API abuse, injection), insider threats (rogue operator, compromised service account), infrastructure compromise (Kafka broker tampering, DNS hijacking), and regulatory adversaries (auditors verifying completeness of sanctions screening).

**Why it matters:** Different threats require different architectural responses. An external attacker is stopped at the API gateway. An insider threat requires audit trail immutability. A compromised Kafka broker requires message-level integrity (KM-06). Without a threat model, security controls may be misallocated — strong at the perimeter but weak internally, or vice versa.

**What remains open for architecture:**
- A formal threat model (STRIDE or similar) for the system — identifying threats per component and the architectural mitigations
- Which threats we explicitly do NOT defend against (scope limitation for a portfolio project) and why
- How the threat model maps to the existing security requirements (SEC-*, KM-*, A*-S*) — identifying any gaps between threats and controls

---

## Theme B: Consistency and Crash Recovery

The requirements specify crash recovery properties (RES-01 through RES-11). The architecture must describe the mechanisms that achieve those properties.

### B.1 — Payment State Machine and Ownership Transitions

**The challenge:** A payment moves through multiple services during its lifecycle: A3 or A4 (creation) -> A6 (VoP check) -> A2 (clearing) -> A5 (fraud/sanctions) -> A1 (settlement). At each stage, a different service "owns" the payment's state. If the system crashes, which service is responsible for recovery? Where are the handoff points, and what is the protocol for transferring ownership?

**Why it matters:** If A2 has sent a fraud check request to A5 and then crashes, the payment is in a limbo state. When A2 restarts, it must discover this pending payment and resume from the correct point. But A5 may have already published a response to Kafka that A2 hasn't consumed yet. The architecture must define a state machine that is consistent across all services and resilient to partial failure at any transition.

**What is decided:**
- A3-F05 defines the R2P lifecycle states: CREATED -> PENDING -> ACCEPTED -> PAYMENT_INITIATED -> SETTLED / REJECTED / DECLINED / EXPIRED / DEFERRED
- A1-F11 defines balance reservation states: AVAILABLE -> RESERVED -> CONFIRMED (or RELEASED)
- RES-01 specifies durable reservation timeout (30 seconds)
- RES-04 specifies batch clearing restartability (Spring Batch ExecutionContext)

**What remains open for architecture:**
- A unified payment state machine showing every state, every service that can trigger a transition, and every recovery path. Currently, states are defined per service — A3 has its states, A2 has implicit states, A1 has reservation states. The architecture needs to reconcile these into a coherent whole.
- The exact recovery procedure on service restart: does each service scan its database for in-flight transactions? Does it replay Kafka from the last committed offset? Both?
- How long does the system tolerate an inconsistency before alerting an operator? (e.g., a payment stuck in PAYMENT_INITIATED for >60 seconds)

### B.2 — Dual-Write Problem and Event–Database Synchronization

**The challenge:** Several services need to write to PostgreSQL and publish to Kafka as part of a single logical operation. These are two separate systems — there is no distributed transaction that spans both. If the database write succeeds but the Kafka publish fails (or vice versa), the system is in an inconsistent state.

**Why it matters:** In A2, settling a payment involves: (1) updating clearing status in PostgreSQL, (2) publishing a settlement event to Kafka for A1. If step 1 succeeds and step 2 fails, A2 thinks the payment is settled but A1 never receives it — the ledger is missing an entry. For a financial system, this is a critical data integrity failure.

**What is decided:**
- RES-03 specifies two options: Transactional Outbox Pattern (write event to outbox table in same DB transaction, relay to Kafka separately) OR Kafka-first (Kafka is the source of truth, PostgreSQL derived from consumers)
- The choice between these is explicitly left as an implementation decision

**What remains open for architecture:**
- Which option to choose for each service — it may differ. A1 (event-sourced) naturally fits Kafka-first. A2 (Spring Batch for batch clearing) may fit Outbox better because Spring Batch is database-centric.
- If using Outbox: the relay mechanism — CDC (Debezium), polling, or scheduled task? Each has different latency and operational complexity.
- If using Kafka-first: how to handle the case where a service needs to query its own recently-published events (read-your-own-writes problem)

### B.3 — Exactly-Once Semantics Across Service Boundaries

**The challenge:** Kafka supports exactly-once semantics *within* a single Kafka Streams application (using `processing.guarantee=exactly_once_v2`). But our system spans multiple independent services. A payment instruction published by A3, consumed by A2, which triggers a fraud check in A5, which returns a response consumed by A2 — this crosses three service boundaries. Exactly-once within A2 does not guarantee exactly-once for the end-to-end flow.

**Why it matters:** A double-processed payment means someone gets charged twice. A lost payment means someone doesn't get paid. Both are unacceptable in a financial system.

**What is decided:**
- RES-05 specifies idempotent message processing via `messageId` or `transactionId`
- A2-F11 specifies idempotent payment processing
- A3-F07 specifies idempotency keys on request creation

**What remains open for architecture:**
- The idempotency key strategy: who generates the key, at what granularity, where is it stored, how long before it expires? If A3 generates a key and A2 also needs one for its downstream interactions with A5, are these the same key or different?
- How cross-service idempotency interacts with retries: if A2 retries a fraud check request because A5 was slow, does A5 deduplicate or process again? The answer depends on whether A5 is stateless (idempotent by nature) or stateful.

### B.4 — Investigation Queues for Stuck Payments

**The challenge:** Despite all recovery mechanisms, some payments will inevitably get stuck in an intermediate state — due to bugs, unforeseen race conditions, or genuinely ambiguous situations (e.g., A2 timed out, but did the settlement actually happen at the central infrastructure?). These payments cannot be silently dropped. They need a path to resolution.

**Why it matters:** FinTP (Allevo) uses "investigation queues" as a core architectural concept: failed transactions go into operator queues for manual review and repair. This is not an error handling afterthought — it is a first-class processing path. Without it, stuck payments accumulate silently until reconciliation catches them (potentially hours or days later).

**What is decided:**
- RES-09 specifies Dead Letter Queues for unprocessable messages
- A5-F07 specifies case management for fraud alerts
- RES-11 specifies handling for late-arriving fraud responses

**What remains open for architecture:**
- Whether to implement a general-purpose investigation queue (beyond DLQ) where operators can inspect, repair, and re-submit stuck payments
- The operator tooling for payment investigation: a dashboard showing payments stuck in intermediate states, with the ability to force-complete, force-reject, or re-route
- Escalation rules: how long before a stuck payment triggers an alert? Who gets notified? What SLA for resolution?

---

## Theme C: Inter-Service Coordination and Communication

### C.1 — Orchestration vs. Choreography: Formalizing the Hybrid Model

**The challenge:** Our system uses a hybrid model: the clearing engine (A2) acts as a semi-orchestrator for the payment lifecycle, while entry points (A3, A4) and auxiliary services (A5, A6) are event-driven. This hybrid is implicit in the requirements but not explicitly designed. Without formalization, different developers may make different assumptions about who "drives" the flow.

**Why it matters:** If A2 is the orchestrator, it must track the state of every in-flight payment and handle timeouts for every downstream interaction. If the model is pure choreography, each service reacts independently and no single service has a complete view. The hybrid must clearly define: which interactions are request-response (A2 sends fraud check, waits for response) and which are fire-and-forget (A2 publishes settlement event, doesn't wait for A1 confirmation).

**What is decided:**
- Section 9.3 shows the end-to-end instant payment flow: A2 coordinates steps 4-15
- A2 publishes fraud check requests and waits for responses (request-response over Kafka)
- A2 publishes settlement events without waiting for A1 confirmation (fire-and-forget)
- A3/A4 initiate VoP checks before publishing to Kafka (outside A2's orchestration)

**What remains open for architecture:**
- A formal interaction diagram showing which interactions are synchronous (request-response) and which are asynchronous (fire-and-forget), and who holds the timeout responsibility for each
- What happens when choreographed events arrive out of expected order? (e.g., A1 receives a settlement event before the corresponding balance reservation — is this possible? How is it handled?)
- Whether to use the Saga pattern explicitly and, if so, whether orchestrated Sagas (A2 manages compensation) or choreographed Sagas (each service compensates independently)

### C.2 — Time Budget Allocation for Instant Payments

**The challenge:** The EPC 2025 SCT Inst rulebook mandates 5-second execution, 7-second hard limit, 9-second confirmation. The happy path traverses: A6 (VoP, before A2) -> A2 (orchestration) -> A1 (balance reservation) -> A5 (fraud+sanctions) -> A2 (settlement decision) -> A1 (confirm reservation, credit beneficiary) -> A2 (pacs.002 confirmation). Each hop consumes part of the 5-second budget.

**Why it matters:** If each service takes its maximum allowed time, the sum may exceed the budget. The architecture must allocate a time budget per service and enforce it with timeouts. But static allocation wastes time — if A5 responds in 20ms, A2 should use the saved time for retries or other processing, not sit idle until its allocated slot expires.

**What is decided:**
- A2-NF02: execution < 5 seconds, hard limit < 7 seconds
- A5-NF02: sanctions < 50ms, A5-NF03: combined fraud+sanctions < 500ms
- A6-NF02: VoP < 500ms
- A1-NF05: balance reservation < 100ms
- A2-F25: if processing cannot complete within 7 seconds, reject with timeout
- The requirements specify per-service latency targets but not how they compose

**What remains open for architecture:**
- A formal time budget breakdown: how do the individual service SLAs add up? Is the sum within the 5-second target? Where is the safety margin?
- Whether timeouts are statically configured (each service gets a fixed budget) or dynamically adjusted (A2 tracks elapsed time and adjusts downstream timeouts accordingly — e.g., `CompletableFuture.orTimeout(remainingBudget)`)
- What happens at each timeout boundary: A5 timeout -> A2 rejects (or retries? or auto-passes fraud but not sanctions?). The requirements address some cases (A5-F28: sanctions cannot be skipped; A2-F25: reject on timeout) but the architecture must map every timeout scenario

### C.3 — Backpressure and Load Isolation

**The challenge:** Batch and instant workloads share infrastructure. A large batch clearing cycle (10,000 instructions per A2-NF01) processes simultaneously with individual instant payments. If batch processing consumes all Kafka consumer threads or database connections, instant payments may be delayed beyond the 5-second SLA.

**Why it matters:** The requirements already separate Kafka topics (A2-F26: dedicated topics for instant). But separation at the topic level may not be sufficient — if both paths share the same PostgreSQL instance, a batch of 10,000 INSERTs could cause lock contention or I/O saturation that affects instant payment queries.

**What is decided:**
- A2-F26: separate Kafka topics for batch and instant
- A5-NF05: separate consumer groups for batch and instant fraud scoring
- OPS-05: database-per-service pattern

**What remains open for architecture:**
- Whether the batch and instant paths within A2 are separate thread pools, separate JVM instances, or separate Kubernetes pods — this determines the degree of resource isolation
- Connection pool sizing: if A2 shares a PostgreSQL connection pool between batch and instant, a batch cycle may exhaust connections. Separate pools? Separate databases?
- Kafka consumer configuration: separate consumer groups are specified, but what about partition count and consumer parallelism for instant topics vs. batch topics?

---

## Theme D: Data Integrity and Schema Evolution

### D.1 — Event Schema Evolution Under Event Sourcing

**The challenge:** A1 uses event sourcing with Kafka as the event store. The `ledger.events` topic has infinite retention (RES-07). Over the system's lifetime, the Avro schema for ledger events will evolve — new fields added, old fields deprecated, semantics changed. Every schema version ever published to this topic must remain readable, because projection rebuilds (A1-NF04) replay all events from the beginning.

**Why it matters:** A careless schema change (renaming a field, changing a type, removing a required field) can make historical events unreadable, breaking projection rebuilds and reconciliation. In a normal system, you can migrate the database. In event sourcing, the events ARE the source of truth and cannot be mutated.

**What is decided:**
- DATA-06: Avro with Schema Registry, BACKWARD compatibility mode
- A1-F07: event schema versioned with backward-compatible evolution

**What remains open for architecture:**
- The concrete schema evolution strategy: what changes are allowed (add optional fields, add new event types), what changes are forbidden (rename fields, change types, remove fields), and how this is enforced (Schema Registry compatibility checks in CI/CD)
- How projection code handles events from multiple schema versions — does the consumer have version-specific deserialization, or does Avro's schema resolution handle it transparently?
- What happens when a schema change requires semantic reinterpretation (e.g., a field that previously meant "gross amount" now means "net amount") — this cannot be handled by Avro compatibility alone

### D.2 — API Versioning and Backward Compatibility

**The challenge:** External-facing APIs (A3 merchant API, A4 PSD2 API, A6 VoP responder API) will evolve. TPPs and merchants integrate against specific API versions. Breaking changes require deprecation periods, parallel version support, and migration paths.

**Why it matters:** PSD2 mandates API availability for TPPs. If we break the API, TPPs cannot access accounts — which is a regulatory violation. Even for the merchant API (A3), breaking changes cause integration failures.

**What is decided:**
- A4-F16: BerlinGroup NextGenPSD2-compliant API surface (external standard constrains changes)

**What remains open for architecture:**
- API versioning strategy: URL path versioning (`/v1/`, `/v2/`), header versioning, or content negotiation?
- How many API versions to support concurrently and for how long
- Whether internal APIs (between services) need versioning or whether they evolve in lockstep (since we control both sides)

---

## Theme E: Cross-Cutting Uniformity

### E.1 — Error Handling Strategy

**The challenge:** Six services each handle errors. Without a uniform strategy, error responses will differ in format, error codes, and behavior. A3 might return `{"error": "not_found"}` while A4 returns `{"code": 404, "message": "Resource not found"}`. Kafka consumers might handle deserialization errors differently across services.

**Why it matters:** Inconsistent error handling makes the system harder to debug, harder to monitor (alert rules differ per service), and harder to extend. Cross-cutting concerns handled inconsistently undermine the cohesion of the system.

**What is decided:**
- The Platform Starter (#4) provides structured JSON logging, audit trail, and correlation ID propagation
- RES-09 specifies DLQ for unprocessable Kafka messages

**What remains open for architecture:**
- A standard error response format for all REST APIs (error code taxonomy, correlation ID, timestamp, human-readable message)
- A standard error classification for Kafka consumers: which errors are retryable (deserialize again later), which go to DLQ (permanently unprocessable), which trigger alerts (unexpected system errors)
- Exception hierarchy: domain exceptions (InsufficientBalanceException, SanctionsHitException) vs. infrastructure exceptions (KafkaUnavailableException, DatabaseTimeoutException) — are these handled at different layers?
- Whether the Platform Starter should enforce error handling patterns (e.g., a global exception handler auto-configured for Spring MVC) or just provide utilities

### E.2 — Observability Across Asynchronous Boundaries

**The challenge:** A single payment traverses multiple services connected by Kafka topics. Traditional distributed tracing (OpenTelemetry/Zipkin) traces synchronous HTTP calls well but loses context across Kafka publish/consume boundaries unless context is explicitly propagated in Kafka headers.

**Why it matters:** When an instant payment fails at the 4.8-second mark, an operator needs to see the full trace: how long did VoP take? How long was the message in the Kafka queue? How long did fraud scoring take? Without end-to-end tracing, debugging latency issues requires log correlation across 5 services — slow and error-prone.

**What is decided:**
- The Platform Starter provides correlation ID propagation
- OPS-01 specifies Prometheus metrics endpoints
- SEC-07 specifies structured audit logging for state changes

**What remains open for architecture:**
- The concrete tracing implementation: OpenTelemetry with Kafka instrumentation? Manual trace/span ID propagation in Kafka headers? How does the Starter auto-configure this?
- Metric design: which metrics are standard across all services (request latency, error rate, Kafka consumer lag) and which are service-specific (fraud scoring time, sanctions list size)?
- Log aggregation architecture: centralized logging with ELK/Loki? How are PII-containing fields redacted in centralized logs (STARTER-F12)?
- SLA monitoring: a composite metric that tracks end-to-end instant payment latency against the 5-second EPC target, not just per-service latency

---

## Theme F: Privacy and Regulatory Architecture

### F.1 — GDPR Right to Erasure vs. Immutable Event Log

**The challenge:** Event sourcing stores all balance changes as immutable events in Kafka (infinite retention). GDPR Article 17 grants the right to erasure. These two requirements directly contradict each other. The accepted solution is crypto-shredding: encrypt PII with a per-account key and delete the key to make PII irrecoverable. But this has deep architectural implications.

**Why it matters:** This is not a feature to add later. The encryption must happen at event creation time. Every consumer that reads events must handle decryption (and decryption failure after key deletion). Projection rebuilds must handle events where PII is irrecoverable. This affects the architecture of every service that touches account data.

**What is decided:**
- Section 2.6 specifies crypto-shredding for GDPR erasure
- A5-F12 specifies PII minimization in fraud scoring (anonymized IDs)

**What remains open for architecture:**
- Where are per-account encryption keys stored? In A1's database? In a separate key management service? What happens if the key store is unavailable?
- Which fields in Kafka events are encrypted? Account holder name, yes — but what about IBAN, transaction descriptions, amounts? Over-encrypting makes the event stream unusable; under-encrypting leaves PII exposed.
- How do consumers handle events where PII has been shredded? A1's projection rebuild encounters an event where the name field decrypts to garbage (key deleted). Does the projection store "REDACTED"? Does it skip the field?
- How does A6 (VoP responder) handle a query for an account whose PII has been shredded? The account exists but the name is irrecoverable — VoP cannot answer.

### F.2 — Audit Trail Immutability and Tamper Evidence

**The challenge:** Regulations require immutable audit trails (SEC-07, A5-S06). Kafka's `audit.events` topic is append-only, which provides immutability against application-level tampering. But a Kafka administrator with broker access could delete or modify messages. How does the architecture prove that the audit trail has not been tampered with?

**Why it matters:** In a regulatory audit or legal proceeding, the system must demonstrate that the audit trail is complete and unmodified. "We used Kafka" is not sufficient evidence. Tamper evidence requires additional architectural measures.

**What remains open for architecture:**
- Whether to implement cryptographic chaining of audit events (each event includes a hash of the previous event, forming a hash chain — tamper-evident log)
- Whether to use a separate, highly secured audit store (write-once storage, separate access controls) in addition to Kafka
- Whether audit events should be digitally signed by the producing service (non-repudiation: proves which service generated the event)
- What the minimum retention period is for different categories of audit data (AML: 5 years, PCI DSS: 1 year, GDPR: minimize retention)

---

## Theme G: Testing Architecture

### G.1 — Integration Testing of Event-Driven Flows

**The challenge:** The system's core flows are asynchronous: A3 publishes a Kafka message, A2 consumes it, publishes to A5, A5 responds, A2 publishes to A1. Testing a single service in isolation (unit tests) doesn't verify that the inter-service contracts are honored. But spinning up 6 services + Kafka + 6 PostgreSQL instances for every test run is slow and fragile.

**Why it matters:** The gap between unit tests and full integration tests is where bugs hide — wrong Avro schema versions, incorrect Kafka topic names, mismatched serialization, timing assumptions that work in tests but fail under load.

**What is decided:**
- Testcontainers for Kafka and PostgreSQL in service-level integration tests
- Pact for contract testing between services
- NAV mock-oauth2-server for OAuth2 testing

**What remains open for architecture:**
- The testing pyramid for this system: what is tested at each level (unit, service integration, contract, end-to-end)?
- Whether to use Pact for Kafka message contracts (Pact supports message-based contracts) in addition to REST API contracts
- How to test timing-dependent behavior (5-second SLA, timeout scenarios) — deterministic test clocks? Simulated delays?
- How to test sanctions screening without real sanctions lists (legal risk) — synthetic test lists? What properties must they have?

### G.2 — End-to-End Testing and Demonstration

**The challenge:** The system must be demonstrable in under 5 minutes (Section 10). This requires a reproducible, scripted end-to-end test that brings up the entire system, seeds test data, executes key flows, and verifies results. This is not just a test — it is the primary proof that the system works.

**What is decided:**
- Section 10 defines the demo steps
- Section 11 includes `scripts/generate-test-data.sh` and `scripts/run-demo.sh`

**What remains open for architecture:**
- How to seed the system with consistent test data (accounts, balances, merchants, TPP registrations, sanctions lists) in a reproducible way
- Whether the demo script is a shell script calling curl, a Spring Boot test class, or a dedicated test harness
- How to verify timing in the demo (proving the instant payment completed in <5 seconds) — timestamps in API responses? Log output with timing?

---

## Theme H: Deployment and Operational Architecture

### H.1 — Zero-Downtime Deployment

**The challenge:** EU IPR requires the instant payment path to be available 24/7/365 (OPS-08, A2-F21). Deploying a new version of A2 means shutting down old instances and starting new ones. During this window, instant payments would fail.

**Why it matters:** Even a 30-second deployment window means some instant payments are rejected — violating the regulatory mandate and potentially losing real money.

**What is decided:**
- OPS-02: graceful shutdown (drain in-flight requests)
- RES-10: complete or abort in-flight instant payments before closing Kafka consumers
- OPS-03: services start independently (no ordering dependency)

**What remains open for architecture:**
- Rolling deployment strategy: run old and new versions concurrently? Blue-green? Canary?
- Database migration compatibility: how to ensure a database migration in version N+1 doesn't break the still-running version N instances (expand-contract migration pattern)
- Kafka consumer rebalancing during deployment: when an A2 instance shuts down, Kafka reassigns its partitions to remaining instances. This causes a processing pause. Mitigation: cooperative sticky partition assignment?
- How to handle the case where old-version and new-version instances process different Avro schema versions concurrently

### H.2 — Configuration Management

**The challenge:** The system has many configurable parameters distributed across services: fraud scoring thresholds (A5), SCA exemption limits (A4), rate limits (KM-09), clearing cycle windows (A2), VoP cache TTL (A3-F08), reservation timeout (RES-01). Some must be consistent across services (e.g., the reservation timeout in A1 must match A2's expectation). Some may need to change at runtime without redeployment.

**What is decided:**
- OPS-06: configuration via environment variables or Spring profiles

**What remains open for architecture:**
- Whether to use Spring Cloud Config or a similar centralized configuration service for runtime-changeable parameters
- Which parameters can change at runtime (thresholds, rate limits) vs. which require a redeployment (schema changes, Kafka topic names)
- How to ensure consistency of shared parameters: if A1's reservation timeout is 30 seconds but A2 assumes 60 seconds, payments will fail. Where is the source of truth?

---

## Theme I: Extensibility and Evolution

### I.1 — Configuration-Driven Behavior

**The challenge:** Real payment systems (FinTP, NICS) rely heavily on configuration over code: routing rules, clearing windows, thresholds, and matching algorithms are parameterized rather than hardcoded. Our system should similarly be configurable without code changes for operational parameters.

**Why it matters:** Fraud patterns change. Regulatory thresholds are updated (the EPC 2025 timing update affected ~35 requirements). A new sanctions list source may be added. If these changes require code changes and redeployment, the operational burden is high.

**What is decided:**
- Fraud velocity rules (VEL-01 through VEL-10) are parameterized
- SCA exemption thresholds are configurable
- Rate limits are configurable per endpoint (KM-09)
- Balance reservation timeout is configurable (RES-01)
- Semantic duplicate detection window is configurable (A2-F12)

**What remains open for architecture:**
- Which parameters should be externalized to configuration files and which are acceptable as code constants?
- Whether to implement a configuration validation mechanism (e.g., on startup, verify that all required parameters are present and within valid ranges)
- Whether configuration changes should be audited (who changed the fraud threshold and when?)

### I.2 — Adding New Services or Payment Schemes

**The challenge:** The Kafka topic topology is inherently extensible — a new service can subscribe to existing topics without modifying existing services. But practical extensibility requires more: new message types, new Avro schemas, new API contracts.

**Why it matters:** Future requirements may include: CBDC (Central Bank Digital Currency) integration, real-time cross-border payments (P27-style), additional scheme support (SEPA Direct Debit is already mentioned in extensions). The architecture should accommodate these without requiring a fundamental redesign.

**What remains open for architecture:**
- Whether to document explicit extension points: "to add a new payment scheme, create a new producer for `payment.instructions` with a new `schemeType` field; A2 routes based on scheme"
- Whether the shared domain model (`payment-domain-model`) is designed for extension (open for new payment types) or is it a closed model that must be modified centrally?
- How API versioning (D.2) interacts with new capabilities — does a new scheme require a new API version, or can it be added within an existing version?

---

## Theme J: Reference Architecture Research

### J.1 — Architectural Patterns from Research Literature

**The challenge:** Many of our challenges are well-studied in software architecture literature. Before making architectural decisions, we should ground them in established patterns and their known tradeoffs.

**Key bodies of work to consult:**

| Source | Relevance |
|--------|-----------|
| **Enterprise Integration Patterns** (Hohpe & Woolf, 2003) | Message routing, content-based routing, publish-subscribe, dead letter channel, idempotent receiver — directly applicable to our Kafka topology |
| **Domain-Driven Design** (Evans, 2003) / **Implementing DDD** (Vernon, 2013) | Bounded contexts, aggregates, domain events — justification for our service boundaries |
| **Building Microservices** (Newman, 2nd ed. 2021) | Service decomposition, inter-service communication, testing strategies, deployment |
| **Designing Data-Intensive Applications** (Kleppmann, 2017) | Event sourcing, stream processing, exactly-once semantics, schema evolution — directly applicable to A1 and A2 |
| **Release It!** (Nygard, 2nd ed. 2018) | Stability patterns: circuit breakers, bulkheads, timeouts, steady-state — directly applicable to our resilience requirements |
| **OWASP ASVS v4.0** | Application Security Verification Standard — Level 2 or 3 for financial applications. Systematic security requirements |
| **ISO 20022 Architecture** | Official message flow patterns for payment processing |
| **EPC rulebooks** (SCT, SCT Inst, SRTP, VoP) | Prescribe specific processing flows, timing, and error handling |

**What remains to be done:**
- A targeted review of these sources for recommendations that apply to our open challenges (Themes A-I above)
- Documenting which patterns we adopt, which we adapt, and which we reject — with rationale

### J.2 — Reference Architectures from Real Systems

**The challenge:** Our project research has analyzed several real payment systems. Their architectural choices should inform ours — not by copying, but by understanding why they made certain decisions and whether those reasons apply to us.

| System | Architecture | Key Insight for Us |
|--------|-------------|-------------------|
| **Allevo FinTP** | Message-based middleware with content-driven routing. STP with investigation queues. Configuration-over-code. | Investigation queues for stuck payments (B.4). Configuration-driven behavior (I.1). Reconciliation as first-class concern. |
| **Deutsche Bank** | Java miniservices on Google Cloud. Globally standardized AFC framework. Multi-channel (REST + SWIFT + EBICS). AI-driven routing. | Security as organizational standard, not per-service (A.1). Multi-format support. Account Pre-Validation at scale (1B+ accounts). |
| **Mastercard/NICS** | Centralized clearing operator. 5 daily batch windows. Centralized account registry (KAR). Phone-to-IBAN routing. | Multi-window batch scheduling (our single-window may be simplistic). Central registry concept. Operational scale reference. |
| **TIPS (ECB)** | Gross settlement, central bank money, 24/7/365. EPC 2025 SLA. | Our instant path mirrors TIPS settlement model. Time budget constraints are real. |
| **P27 (Nordics)** | Multi-currency on single platform. Batch + instant hybrid. Cross-border as domestic. | Multi-currency handling (our JSR 354 approach). Shows batch+instant on one platform is proven. |

**What remains to be done:**
- Where our architecture diverges from these references, document why (deliberate choice for portfolio scope, or genuine disagreement with the reference approach)
- Identify any patterns used by multiple reference systems that we haven't considered

---

## Summary: Challenge Index

| ID | Challenge | Theme | Key Question |
|----|-----------|-------|-------------|
| A.1 | Trust boundaries | Security | Where are the trust boundaries and how are they enforced structurally? |
| A.2 | Key lifecycle | Security | How do keys flow through the system — creation, distribution, rotation, revocation? |
| A.3 | Threat model | Security | What threats does the architecture defend against, and which are out of scope? |
| B.1 | Payment state machine | Consistency | What is the unified state machine and who owns state at each transition? |
| B.2 | Dual-write problem | Consistency | Outbox or Kafka-first — which services use which approach? |
| B.3 | Cross-service exactly-once | Consistency | How do idempotency keys compose across service boundaries? |
| B.4 | Investigation queues | Consistency | What happens to payments stuck in intermediate states? |
| C.1 | Orchestration model | Coordination | Where exactly is orchestration vs. choreography and what are the handoffs? |
| C.2 | Time budget allocation | Coordination | How is the 5-second SLA divided among services? |
| C.3 | Backpressure and isolation | Coordination | How are batch and instant workloads isolated at the resource level? |
| D.1 | Event schema evolution | Data | How do immutable events handle schema changes over time? |
| D.2 | API versioning | Data | How are external APIs versioned and deprecated? |
| E.1 | Error handling strategy | Uniformity | What is the standard error model across all services? |
| E.2 | Distributed observability | Uniformity | How does a trace follow a payment across Kafka boundaries? |
| F.1 | GDPR vs. event sourcing | Privacy | How does crypto-shredding work in practice across all consumers? |
| F.2 | Audit immutability | Privacy | How do we prove the audit trail has not been tampered with? |
| G.1 | Integration testing | Testing | How do we test event-driven flows without full system startup? |
| G.2 | End-to-end testing | Testing | How is the demo scripted and verified? |
| H.1 | Zero-downtime deployment | Operations | How do we deploy without violating the 24/7/365 SLA? |
| H.2 | Configuration management | Operations | Where is the source of truth for shared configuration? |
| I.1 | Configuration-driven behavior | Extensibility | Which parameters are externalized and how are changes validated? |
| I.2 | Adding new services | Extensibility | What are the explicit extension points in the architecture? |
| J.1 | Literature review | Research | Which established patterns apply to our open challenges? |
| J.2 | Reference architectures | Research | What can we learn from real payment system architectures? |
