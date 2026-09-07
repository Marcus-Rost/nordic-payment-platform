# A5 Fraud Detection & Sanctions Engine — Architectural Challenges

*Consolidated from brainstorming, requirements analysis, literature review, and reference system survey*
*Date: 2026-04-11*

---

## How to Read This Document

This document captures the architectural challenges for the A5 subsystem — problems where the *structure* of the system must be designed, not just the features. Requirements (requirements.md) specify *what* the system must do. This document identifies where the *how* needs architectural decisions.

**Structure:**
- **Part I: Foundational Principles** — design philosophies that constrain all subsequent decisions. These are not challenges to solve; they are evaluation criteria applied when assessing options for every challenge.
- **Part II: Architectural Challenges** — specific technical problems requiring concrete solutions, prioritized and with solution options.

Items already fully resolved during requirements engineering are excluded. Where requirements partially address a challenge, we note what is decided and what remains open.

**Sources:**
- User brainstorming contributions (5 points on security, resilience, literature, reference systems)
- Technical analysis of requirements and use cases
- Literature review: Kleppmann (DDIA), Nygard (Release It!), Hohpe & Woolf (EIP), OWASP ASVS, NIST CSF, EU DORA, EU IPR
- Reference system survey: ING Bank, Feedzai Railgun, Sportradar DJL, FinTP (Allevo), TIPS (ECB), Danske Bank, UK Faster Payments

---

# Part I: Foundational Principles

These principles are not options to choose between — they are commitments that shape every architectural decision. Each principle originated from brainstorming and is reinforced by research literature and reference system analysis.

## P1 — Security by Construction ("Fortress")

> The system must be secure by design, not by patching. Security is a structural property of the architecture, not a feature to be added.

**Origin:** Brainstorming points 1 and 2. Reinforced by OWASP Secure by Design Framework, NIST CSF 2.0, DORA (EU, applicable since January 2025).

**What this means concretely for A5:**
- Every endpoint is authenticated by default (deny-all, opt-in to permit)
- Sanctions screening is structurally mandatory (hardcoded, cannot be disabled by configuration — SAN-F12)
- PII never enters the scoring pipeline (SEC-10) — data minimization is enforced by the data flow architecture, not by policy
- ML model integrity is verified at load time (checksum) — a corrupted model cannot be loaded
- Kafka messages carry HMAC signatures — message authenticity is verified structurally
- The Case Management API validates OAuth2 tokens with specific scopes — no anonymous access to fraud data

**Evaluation criterion:** For every architectural option, ask: "Does this option make the secure path the default path? Could a developer accidentally bypass the security control?"

**Reference:** FinTP enforces sanctions filtering as a mandatory routing step — payments physically cannot bypass screening. Deutsche Bank uses a globally standardized Anti-Financial Crime framework as an organizational/structural standard.

## P2 — Resilience by Design (Crash Recovery as Architecture)

> The system must handle crashes, hardware failures, and denial-of-service attacks as normal operating conditions — not exceptional events. Crash recovery must be designed from the beginning, not retrofitted.

**Origin:** Brainstorming point 3. Reinforced by Nygard's *Release It!* stability patterns, Kleppmann's *DDIA* stream processing crash recovery model, and the DORA requirement for operational resilience.

**What this means concretely for A5:**
- The Kafka Streams scoring pipeline uses `exactly_once_v2` — crash recovery is atomic (replay from last committed offset, discard uncommitted state changes)
- The sanctions index is rebuilt from cached list files on startup — A5 does not accept requests until the index is ready
- Dead Letter Queues capture every unprocessable message — nothing is silently lost
- Graceful shutdown drains in-flight scoring operations (FRD-RES03)
- Late-arriving responses are still published; late SANCTIONS_HIT creates an alert (FRD-RES04)

**Evaluation criterion:** For every architectural option, ask: "What happens if the system crashes at this exact point? Is recovery automatic or does it require manual intervention? Is any data lost?"

**Reference:** Feedzai Railgun persists state frequently so that on failure, only minimal events need replaying. Kafka Streams changelog topics provide the same guarantee for state stores.

## P3 — Regulatory Compliance as Structural Guarantee

> Compliance requirements (AML, GDPR, DORA, EU IPR) must be enforced by the architecture, not by developer discipline. A developer should find it difficult or impossible to build a non-compliant flow.

**Origin:** Implicit in the requirements (SAN-F12, SEC-05, SEC-10). Reinforced by EU DORA, EU Instant Payment Regulation (2024/886), EPC SCT Inst Rulebook 2025.

**What this means concretely for A5:**
- Every scoring decision is logged to an immutable audit trail with the specific sanctions list version, model version, rules triggered, and score breakdown (SEC-05)
- PII minimization is enforced by the data flow (scoring pipeline uses anonymized IDs; PII enrichment happens only at query time in the Case Management API)
- Sanctions screening decisions are timestamped and traceable to the specific list version used
- Alert resolution records (analyst ID, timestamp, justification) are immutable
- AML retention (minimum 5 years) is a data architecture property, not a manual procedure

**Evaluation criterion:** For every architectural option, ask: "Could a developer accidentally create a non-compliant flow? Is compliance auditable by examining the architecture, or does it require examining individual code paths?"

---

# Part II: Architectural Challenges

Challenges are prioritized into three tiers:
- **Priority 1 (Critical):** Decisions that shape the fundamental structure of the system. Getting these wrong requires a redesign. Must be resolved before implementation begins.
- **Priority 2 (Important):** Decisions that affect significant subsystems or cross-cutting concerns. Getting these wrong requires significant rework but not a redesign.
- **Priority 3 (Relevant):** Decisions that affect specific components. Can be deferred to implementation phase if needed.

---

## Priority 1 — Critical Structural Decisions

### C1 — Scoring Pipeline Internal Architecture

**The challenge:** The three-layer scoring pipeline (sanctions screening → velocity rules → anomaly detection + ML) is the core of A5. How is it structured internally? How do layers compose? How does short-circuit logic work (sanctions HIT bypasses everything)? How are layers independently configurable, testable, and degradable?

**Why it matters:** This is the heart of the system. Every other decision is constrained by how the pipeline is structured. The wrong abstraction here propagates complexity into every component.

**What is decided:**
- Three layers in fixed order: sanctions → velocity → anomaly+ML (requirements sections 2-3)
- Sanctions HIT bypasses fraud scoring entirely (section 3.4)
- ML is gracefully degradable — system works without it (ML-F07)
- Velocity rules are configurable (section 3.1)

**DECIDED — Chain of Responsibility Pattern:**

Each layer implements a `ScoringLayer` interface with `score(ScoringContext)` and `shouldShortCircuit(ScoringContext)` methods. A `ScoringPipeline` chains the layers in fixed order: sanctions → velocity → anomaly → ML. Within the Kafka Streams topology, a single Processor invokes the pipeline — no internal topic hops between layers.

*Key design properties:*
- **Short-circuit:** If sanctions returns HIT, the chain stops immediately — fraud scoring layers are never invoked. This is the structural guarantee that sanctions overrides everything.
- **Degradation:** When ML is unavailable, the ML layer is excluded from the chain (or returns a neutral result). No if-else branching in the pipeline — the chain simply has fewer links.
- **Testability:** Each layer is independently unit-testable via the `ScoringLayer` interface. The full chain is integration-testable.
- **ScoringContext:** Carries the transaction through the chain, accumulates per-layer results (sanctions result, velocity score, anomaly score, ML score, triggered rules), and provides final aggregation. This is the key internal data structure.
- **Kafka Streams topology:** Single processor node invokes the chain. State stores for velocity windows and account profiles are registered on this processor. Exactly-once semantics are straightforward within a single processor.

*Options rejected:*
- Pipes-and-Filters as separate Kafka Streams sub-topologies: rejected because internal topic hops add 20-80ms latency — unacceptable for the 500ms instant payment budget.
- Monolithic scorer function: rejected because it fails on testability and extensibility; would require refactoring as soon as a layer needs independent testing or a 5th layer is added.
- Rule engine (Drools): rejected because our scoring layers include algorithms (sanctions screening, ML inference) not just rules; Drools adds latency overhead, dependency weight, and debugging complexity unjustified for 10 velocity rules. (Note: the velocity rules *within* Layer 2 may use a lighter YAML-based configuration — see C10.)

**Relates to principles:** P1 (sanctions cannot be bypassed — structural guarantee), P2 (ML degradation must be automatic), P3 (each layer's decision must be auditable)

---

### C2 — Sanctions Index Lifecycle Management

**The challenge:** The sanctions index consists of three coupled data structures (Bloom filter, Aho-Corasick automaton, fuzzy match index) that must be rebuilt atomically when lists update, while screening continues uninterrupted. The index must track its version for regulatory traceability. On crash, the index must be rebuilt before the system accepts requests.

**Why it matters:** Sanctions screening is a legal obligation. An inconsistent index (e.g., Bloom filter from version N, Aho-Corasick from version N-1) could miss a sanctioned entity — criminal liability. A slow rebuild blocks all payment processing.

**What is decided:**
- Atomic swap via AtomicReference (FRD-RES01)
- List polling at least hourly (SAN-F07)
- Checksum verification on ingested lists (SAN-F08)
- Rebuild must complete in < 5 minutes (NFR-06)

**DECIDED — Four sub-decisions:**

**D1 — Screening model (IPR regulatory question):** Both daily batch screening AND per-transaction screening. EU Regulation 2024/886 requires PSPs to screen their own customer base at least daily (and immediately upon list changes). Flagged customers go into a `Set<String> blockedAccounts`. Per-transaction screening (Bloom → Aho-Corasick → Fuzzy) still runs for beneficiary names — catching sanctioned entities who are not our own customers. Fast-path: check `blockedAccounts` first (O(1) lookup); full three-tier pipeline only for unknown beneficiaries.

**D2 — Version tracking:** Version is embedded in the `SanctionsSnapshot` object (held by `AtomicReference`). When the sanctions layer screens a transaction, it reads `snapshot.listVersion()` and writes it into the `ScoringContext`. This flows through to the audit record. No separate version registry — the version is always co-located with the data structures it describes. Rejected: separate version registry (adds dependency, risk of version mismatch).

**D3 — Crash recovery (list caching):** Cache downloaded sanctions list files on local disk. On every successful download, write the raw XML/JSON atomically (write to temp file, rename). On startup, rebuild the index from the cached file — no external network dependency. Then immediately poll for updates. Rejected: re-download from EU/UN on every startup (adds external dependency to startup — violates P2). Rejected: Kafka topic as list cache (over-engineering — adds complexity without proportional benefit over a file).

**D4 — Startup readiness:** Strict readiness — A5 does not register Kafka consumers (does not consume fraud-check-requests) until the sanctions index is fully built. Kubernetes readiness probe returns unhealthy until the index is ready. Messages accumulate in Kafka during startup and are processed once A5 is ready. Rejected: accept-and-fail-closed during warmup (functionally equivalent from the payment's perspective but adds complexity to the pipeline for the "index not ready" state).

**Relates to principles:** P1 (index integrity is a security requirement), P2 (crash recovery for the index), P3 (version traceability is a regulatory requirement)

---

### C3 — Security Architecture and Attack Surface

**The challenge:** A5 has multiple attack surfaces: Kafka consumer interface (malicious messages), Case Management REST API (injection, data exfiltration, unauthorized access), sanctions list polling (man-in-the-middle, corrupted lists), ML model loading (model poisoning), and Kafka Streams state stores (sensitive windowed data). Each requires a specific defense.

**Why it matters:** A compromised A5 could either let sanctioned payments through (criminal liability) or freeze legitimate accounts (financial damage). A5 is the compliance gatekeeper — its compromise has the highest blast radius in the system.

**What is decided:**
- Case Management API requires OAuth2 with fraud:read and fraud:manage scopes (SEC-01)
- Kafka messages carry HMAC signatures (from master architecture KM-06)
- ML model files are checksum-verified (ML-F05)
- Sanctions list files are checksum-verified (SAN-F08)
- PII minimized in scoring pipeline (SEC-10)
- Rate limiting on Case Management API (SEC-09)

**DECIDED — Defense-in-depth per attack surface:**

**Surface 1 — Kafka consumer interface:** Layered defense: (a) Kafka ACLs restrict which service accounts can produce to fraud-check-request topics (infrastructure layer), (b) Avro Schema Registry validates message structure on deserialization — malformed messages fail and go to DLQ (structural layer), (c) HMAC verification on every consumed message proves authenticity and detects tampering (application layer, per KM-06). All three layers active simultaneously. HMAC overhead ~0.1ms — negligible against 500ms budget.

**Surface 2 — Case Management REST API:** Standard Spring Security hardening with deny-all default (inherited from Platform Starter, master Decision A.1b): (a) OAuth2 Resource Server with scope-based access (fraud:read, fraud:manage), (b) parameterized queries via JPA/Hibernate — SQL injection prevented by construction, (c) Bean Validation (`@Valid`) on all request bodies, (d) rate limiting (SEC-09), (e) CORS restricting origins to analyst UI domain, (f) security headers (X-Content-Type-Options, X-Frame-Options, CSP) via Platform Starter, (g) no PII in error messages (stack traces suppressed in production). Rejected: dedicated WAF — a deployment-time decision, not application architecture; documented as production recommendation.

**Surface 3 — Sanctions list download:** TLS for all HTTP connections + checksum verification (SAN-F08) + size/format validation (reject lists suspiciously small or schema-invalid) + operator alerting on download failure or dramatic list changes. Rejected: certificate pinning (too brittle for external government endpoints whose certificate infrastructure we don't control).

**Surface 4 — ML model loading:** SHA-256 checksum at load time (ML-F05) + model sourced from trusted location (restricted filesystem permissions or internal artifact repository) + model version and checksum logged with every scoring decision (ML-F06). Enables forensic investigation if a compromised model is later discovered.

**Surface 5 — State stores (RocksDB):** Filesystem-level encryption (LUKS, dm-crypt, or cloud-provider encrypted volumes) for production. Access controls only for development/portfolio demo. This is a deployment concern, not an application architecture concern — the application code does not change. Matches master architecture's two-tier approach (Decision A.2a). Rejected: RocksDB-level encryption (adds 5-15% I/O overhead, configuration complexity without benefit over filesystem-level encryption). Rejected: no encryption (state stores contain PII-adjacent data — transaction patterns, typical creditors per account).

**Cross-cutting:** The Platform Starter (Project #4) enforces deny-all security default, structured audit logging, correlation ID propagation, and security headers. A5 inherits these — security by construction (P1).

**Explicitly out of scope for A5's threat model (portfolio project boundaries):**
- Advanced persistent threat (APT) with prolonged undetected access to infrastructure
- Physical security of hosting environment
- Social engineering of analyst credentials (organizational control, not application architecture)
- Supply chain attacks on third-party libraries (mitigated by dependency scanning in CI/CD, not by A5's architecture)

**Relates to principles:** P1 (security by construction — every surface defended, secure path is default), P3 (DORA requires ICT risk management)

---

### C4 — Crash Recovery Model

**The challenge:** A5 can crash at multiple points during processing. The architecture must define a coherent recovery model covering all crash scenarios, not handle each case ad-hoc.

**Why it matters:** Without a unified crash recovery model, each scenario is handled differently, creating gaps and inconsistencies. A payment stuck in an unknown state after a crash is a financial and regulatory risk.

**Crash scenarios specific to A5:**

| Scenario | State at crash | Recovery question |
|----------|---------------|-------------------|
| Mid-scoring (Kafka Streams) | Transaction consumed but not yet scored | Kafka Streams replays from last committed offset — handled by exactly_once_v2 |
| After scoring, before Kafka publish | Score computed, response not published | Kafka Streams exactly_once_v2 handles this atomically |
| After scoring, alert written to PostgreSQL, before Kafka response | Alert exists, A2 never learns the decision | Dual-write problem — needs Transactional Outbox or idempotent retry |
| During sanctions index rebuild | Old index active, new index partially built | AtomicReference swap guarantees old index stays active until rebuild completes |
| During model loading at startup | No model loaded | ML-F07: start in ML_DISABLED mode, fall back to rules + anomaly |
| PostgreSQL unavailable | Cannot write alerts | Score is computed; can we publish the Kafka response without writing the alert? Or must we reject? |

**What is decided:**
- Kafka Streams exactly_once_v2 for the scoring pipeline (architecture draft section 2)
- AtomicReference swap for sanctions index (FRD-RES01)
- DLQ for unprocessable messages (FRD-RES02)
- Graceful shutdown with drain (FRD-RES03)
- Late-arriving response handling (FRD-RES04)
- ML fallback (ML-F07)

**DECIDED — Unified crash recovery model with two paths:**

**Scoring pipeline (Kafka Streams path) — Kafka-first:**
The scoring processor writes *only* to Kafka: fraud-check-response to A2 and, when an alert is needed, an alert event to an internal `fraud.alert-events` topic. Kafka Streams `exactly_once_v2` handles atomic commit of consumer offsets, state store changes, and output records. No PostgreSQL on the hot scoring path.

A separate idempotent Alert Consumer reads `fraud.alert-events` and persists alerts to PostgreSQL. Deduplication via UNIQUE constraint on transactionId (ON CONFLICT DO NOTHING). If this consumer crashes or PostgreSQL is unavailable, alerts accumulate in Kafka — scoring continues unaffected, A2 still gets responses, payments are not blocked.

*Rejected: alerts created inside the Kafka Streams processor (dual-write problem — PostgreSQL INSERT + Kafka commit are not atomic; PostgreSQL unavailability would stall the scoring pipeline, violating the 99.99% availability target).*
*Rejected: Transactional Outbox for the scoring pipeline (inverts the flow — response would be published by outbox relay instead of Kafka Streams, breaking the exactly_once_v2 model; adds latency; this pattern is appropriate for REST+DB paths, not Kafka→Kafka paths).*

**Case management (REST path) — Transactional Outbox:**
When an analyst resolves an alert (e.g., CONFIRMED_FRAUD), A5 writes to PostgreSQL (update alert status) AND inserts an outbox record (freeze command / audit event) in a single database transaction. A relay publishes outbox records to Kafka. Details in C8.

**Startup sequence:**
1. Connect to Kafka (broker discovery)
2. Rebuild sanctions index from cached list file on disk *(parallel with step 3)*
3. Load ML model — or enter ML_DISABLED mode if unavailable *(parallel with step 2)*
4. Initialize Kafka Streams topology — state stores recover from changelog topics
5. Kubernetes readiness probe returns healthy
6. Start consuming fraud-check-requests

Steps 2+3 run in parallel (independent). Step 4 depends on Kafka availability. Step 6 depends on all prior steps.

**State store failover:** Configure `num.standby.replicas=1` for production. Standby replicas pre-replicate state so failover is near-instant (no full changelog replay). Storage cost is acceptable (~20MB for profiles + windowed data purged after expiry). For development, standby replicas are unnecessary.

**Summary of crash scenario resolution:**

| Scenario | Recovery mechanism |
|----------|-------------------|
| Mid-scoring | exactly_once_v2 replays from last committed offset |
| After scoring, before Kafka commit | exactly_once_v2 — uncommitted output discarded, transaction re-scored |
| Alert consumer crashes | Kafka replays; idempotent INSERT (ON CONFLICT DO NOTHING) |
| PostgreSQL unavailable | Scoring unaffected (Kafka-first); alert consumer retries; alerts accumulate in Kafka |
| Sanctions index rebuild interrupted | AtomicReference — old index stays active; rebuild restarts from cached file |
| ML model load fails | ML_DISABLED mode; rules + anomaly only |
| Full crash and restart | Startup sequence above; standby replicas reduce state recovery time |

**Relates to principles:** P2 (crash recovery is architecture, not error handling), P3 (no scoring decision can be lost — audit trail completeness)

---

### C5 — Batch vs. Instant Path Isolation

**The challenge:** Both batch and instant fraud-check requests use the same scoring logic and the same state stores (velocity rules must see all transactions). But instant has strict latency requirements (<500ms). A burst of batch transactions must not starve instant scoring.

**Why it matters:** If batch and instant compete for resources (CPU, state store I/O, thread pool), a large batch cycle could push instant payment scoring beyond the 500ms budget — violating the EPC SLA.

**What is decided:**
- Separate Kafka topics for batch and instant (from master architecture)
- Separate consumer groups (NFR-07)
- Processing logic is identical for both paths (architecture draft section 2)
- State stores are shared (velocity rules must see all transactions)

**DECIDED — Separate JVM instances (separate Kubernetes pods):**

Batch and instant scoring run as separate deployments. Each has its own KafkaStreams instance, its own state stores, its own CPU/memory allocation. Scoring logic is identical — same pipeline, same layers, same configuration. The only difference is which Kafka topic each instance consumes from.

*State store consistency:* Each instance builds its own state stores from Kafka Streams changelog topics. The changelog records all state changes regardless of which input topic triggered them. When the batch instance processes a transaction and updates a velocity window, the change is written to the changelog. The instant instance replays this and updates its own copy. Both instances have a consistent view of velocity windows and account profiles, with a propagation delay of milliseconds via changelog replay — insignificant for 60-minute and 24-hour velocity windows.

*Scaling:* Independent. The instant path can have more replicas or guaranteed CPU/memory via Kubernetes resource requests. The batch path can use burstable resources.

*Rejected: single JVM with shared thread pool (batch burst can consume CPU and trigger GC pauses, increasing instant latency — the 500ms budget is non-negotiable).*
*Rejected: single JVM with separate thread pools (reduces thread contention but GC pauses and memory bus contention still shared; unusual Kafka Streams configuration with two KafkaStreams instances in one JVM).*

**Relates to principles:** P2 (instant path must be resilient to batch load surges)

---

## Priority 2 — Important Design Decisions

### C6 — Idempotency and Deduplication Strategy

**The challenge:** A2 may retry fraud-check requests (network timeout, A5 slow response). A5 must not score the same transaction twice (duplicate alerts, double-counted velocity windows, incorrect account profiles).

**What is decided:**
- Idempotent message processing required (from master architecture RES-05)
- Deduplication by fraudCheckRequestId (architecture draft section 2)

**DECIDED — Kafka Streams State Store for cross-retry deduplication:**

Within the Kafka Streams topology, `exactly_once_v2` handles dedup for crash-replay scenarios automatically. The remaining case is A2 intentionally retrying a fraud-check-request (new Kafka offset, same transactionId) because A5 was slow.

Deduplication via a RocksDB-backed KeyValueStore keyed by `transactionId` (generated by A2, unique per payment). Before scoring, the processor checks the state store. If found, the cached `ScoringResult` is returned immediately — no re-scoring, no velocity window side effects. If not found, the transaction is scored normally and the result is stored. Entries are purged via Kafka Streams punctuation after 1-hour TTL.

*Rejected: Caffeine cache (JVM-local, lost on crash — dedup state must be part of the atomic Kafka Streams state for exactly_once_v2 consistency; also ~720MB memory at 1,000 TPS with 1-hour TTL is heavy for the instant path pod).*
*Rejected: no application-level dedup (re-scoring would double-count in velocity windows and distort account profile statistics — a correctness issue, not just latency).*

**Relates to principles:** P2 (idempotency is a resilience mechanism), P3 (duplicate scoring could create duplicate audit records)

---

### C7 — ML Model Lifecycle (Loading, Versioning, Fallback, Updates)

**The challenge:** The ML model must be loaded at startup, integrity-verified, versioned, and gracefully degradable. Model updates should ideally not require application restart.

**What is decided:**
- DJL for inference (tech stack)
- ONNX or PyTorch format (ML-F02)
- SHA-256 checksum at load time (ML-F05)
- Model version logged with every decision (ML-F06)
- Graceful fallback to rules + anomaly if model unavailable (ML-F07)
- Fallback weight redistribution: velocity=0.35, anomaly=0.35 (section 3.4)

**DECIDED — Three sub-decisions:**

**D1 — Model storage:** Local filesystem (model file on the pod, mounted volume or baked into container image). Model loading is behind a `ModelProvider` interface with a `LocalFileModelProvider` default implementation. A future `S3ModelProvider` can load from an artifact repository without changing the scoring pipeline. For a portfolio project, the model ships with the container image.
*Rejected: S3/artifact repository at startup (adds external dependency — violates P2). Rejected: Maven dependency (same redeployment cost as local filesystem but with more packaging complexity).*

**D2 — Zero-downtime model updates:** Restart required. Model updates are deployed via rolling pod replacement. The `ModelProvider` returns an `AtomicReference<Model>` to keep the door open for hot-reload later, but the reload mechanism is not built now.
*Rejected: hot-reload via file watching or Kafka control topic (over-engineering for portfolio scope; model changes are infrequent and should go through review). Documented as future extension.*

**D3 — Champion/challenger:** Not implemented. Single model with version logging (ML-F06) is sufficient. Documented as future extension following the Danske Bank pattern.

**Relates to principles:** P1 (checksum verification prevents model poisoning), P2 (fallback ensures resilience)

**Reference:** Sportradar packages DJL models as Maven dependencies for in-process inference. ING Bank streams PMML models via Kafka for zero-downtime updates. Danske Bank uses champion/challenger for gradual model rollout.

---

### C8 — Case Management Dual-Write (PostgreSQL + Kafka)

**The challenge:** When an analyst resolves an alert (CONFIRMED_FRAUD), the system must write to PostgreSQL (update alert status) AND publish to Kafka (account.freeze-commands, audit.events). These are two separate systems with no distributed transaction.

**What is decided:**
- This dual-write exists and must be handled (architecture draft section 2)
- The master architecture recommends Transactional Outbox for REST+DB paths (master ARCHITECTURE_DECISIONS.md, Decision B.2a)

**DECIDED — Polling-based Transactional Outbox:**

The scoring pipeline does NOT use the outbox (resolved in C4 — Kafka-first, alerts published to `fraud.alert-events` topic). The outbox is only for the Case Management REST path.

When an analyst resolves an alert: (1) BEGIN TX, (2) UPDATE alert status in PostgreSQL, (3) INSERT outbox record (event_type, aggregate_id, payload as JSONB, created_at), (4) COMMIT. A Spring `@Scheduled` relay polls the outbox table every 100ms for unpublished records, publishes to Kafka (`account.freeze-commands`, `audit.events`), and marks records as published (`published_at` timestamp).

The relay is idempotent: if it crashes between Kafka publish and marking as published, the next poll re-publishes. Downstream consumers (A1 for freeze commands) handle duplicates via their own idempotent processing.

Case Management alert resolution volume is low (tens per hour) — 100ms polling latency is irrelevant for analyst workflows.

*Rejected: Debezium CDC (additional infrastructure — Kafka Connect + Debezium connector — unjustified for low-volume analyst operations in a portfolio project).*
*Rejected: Spring TransactionalEventListener (at-most-once failure mode — if Kafka publish fails after DB commit, the freeze command is lost; unacceptable for legally significant actions).*

**Relates to principles:** P2 (dual-write failure must not lose data), P3 (freeze commands are legally significant actions)

---

### C9 — Observability and Distributed Tracing

**The challenge:** A single transaction traverses A2 → A5 (via Kafka). Within A5, it passes through sanctions → velocity → anomaly → ML → aggregation. Operators need end-to-end visibility into scoring latency, per-layer timing, and decision explanations.

**What is decided:**
- Prometheus metrics for scoring latency, hit rates, false positive rates (SAN-F14, tech stack)
- Micrometer for metric instrumentation (tech stack)
- Structured audit logging for all scoring decisions (SEC-02)

**DECIDED — OpenTelemetry + per-layer Micrometer metrics:**

**Distributed tracing:** OpenTelemetry with Kafka client instrumentation. Trace context propagated automatically in Kafka headers. Spring Boot OpenTelemetry auto-configuration handles most of the setup. The Platform Starter (Project #4) auto-configures this for all services. Compatible with Jaeger, Zipkin, or any OTLP backend.
*Rejected: manual trace ID propagation (no automatic span creation or timing; manual effort for each producer/consumer).*

**Per-layer metrics (Micrometer → Prometheus):**

| Metric | Type | Purpose |
|--------|------|---------|
| `fraud.scoring.latency` (tags: path=batch/instant, layer=sanctions/velocity/anomaly/ml/total) | Timer (p50/p95/p99) | SLA monitoring per layer |
| `fraud.sanctions.hit.rate` | Counter ratio | Operational — spikes indicate list change or attack |
| `fraud.decision` (tags: decision type) | Counter | Volume monitoring — detect shifts in scoring behavior |
| `fraud.false.positive.rate` | Gauge | Model/rule quality |
| `fraud.ml.available` | Gauge (0/1) | Degradation detection |
| `fraud.dlq.depth` | Gauge | Operational — non-zero means messages failing |
| `fraud.consumer.lag` (tags: topic) | Gauge | Critical for instant topic |
| `fraud.sanctions.index.version` | Info gauge | Audit — active list version |
| `fraud.state.store.size` | Gauge | Capacity planning |

**Alerting thresholds (Grafana/Alertmanager):**
- `fraud.scoring.latency{path=instant}` p95 > 400ms → WARN; p95 > 480ms → CRITICAL
- `fraud.ml.available` = 0 → WARN
- `fraud.dlq.depth` > 0 → WARN
- `fraud.consumer.lag{topic=instant}` > 100 → CRITICAL

**PII in logs:** The Platform Starter's structured logging (STARTER-F12) redacts PII-containing fields in centralized logs. A5's scoring pipeline logs contain only opaque account IDs and transaction IDs — no names (consistent with C11 PII minimization).

**Relates to principles:** P3 (regulatory traceability requires end-to-end observability)

---

### C10 — Velocity Rules Engine: Configuration and Evaluation Strategy

**The challenge:** 10 velocity rules (VEL-01 through VEL-10) evaluate transaction patterns against windowed state. Rules should be configurable without code changes (FRD-F09). New rules may be added over time.

**What is decided:**
- 10 rules defined with default parameters (section 3.1)
- Rules are additive (each contributes a weight to combined score)
- Configurable without code changes desired (FRD-F09, Should priority)
- Kafka Streams WindowStore for time-windowed aggregations (section 3.2)

**DECIDED — YAML configuration, evaluate all rules:**

**Rule definition format:** YAML configuration files loaded via Spring `@ConfigurationProperties`. Rules are version-controlled with the application source. Changes require a rolling restart — acceptable because fraud rule changes are infrequent and should go through a review process (git history provides the audit trail per P3).
*Rejected: database-stored rules (over-engineering for 10 rules; adds database dependency to scoring hot path).*
*Rejected: Kafka control topic / Drools (infrastructure overhead unjustified for 10 stable rules; documented as future extension if rule set grows significantly).*

**Rule evaluation strategy:** Evaluate ALL rules for every transaction — no short-circuit. Even if the score exceeds AUTO_BLOCK after the first rule, all triggered rules are needed for the alert explanation and audit trail. Short-circuiting would produce incomplete explanations, violating P3.

**Window configuration:** Each rule defines its own window parameters in the YAML. VEL-01 uses a 60-minute window; VEL-03 uses 24 hours. The Kafka Streams WindowStore supports multiple window durations.

**Relates to principles:** P3 (rule changes auditable via git history; all triggered rules recorded for complete explanations)

**Reference:** ING Bank uses Flink broadcast state for runtime rule updates via Kafka. The Flink fraud detection reference architecture uses a Dynamic Rules Engine with JSON rule definitions on a Kafka control topic. Both are documented as future extension paths if the rule set grows.

---

### C11 — PII Minimization: Data Flow Architecture

**The challenge:** The scoring pipeline must process anonymized data (GDPR Art. 5(1)(c) data minimization). Analysts need to see account holder names when investigating alerts. The architecture must enforce this separation structurally.

**What is decided:**
- Scoring pipeline uses anonymized account IDs (SEC-10)
- Case Management API enriches alerts with PII from A1 at query time (SEC-10)

**DECIDED — Transient PII for sanctions, anonymized everywhere else:**

The creditor name IS present in the fraud-check-request message (A2 includes it) because sanctions screening requires it for fuzzy name matching. This is unavoidable — the legal obligation to screen names overrides the desire to eliminate PII from the pipeline entirely.

**PII flow through the pipeline:**
1. **Sanctions screening layer:** Receives creditor name, performs Bloom → Aho-Corasick → Fuzzy matching, records result (CLEAR/HIT/POTENTIAL_HIT + matched entity). The name is used transiently in memory and is **not persisted** in any A5 state store or database.
2. **Velocity rules + anomaly detection:** Operate on `accountId` (opaque), `amount`, `timestamp`. Never see the creditor name.
3. **Alert events** (`fraud.alert-events` topic): Contain `accountId` and `transactionId` but **not** account holder names or creditor names.
4. **PostgreSQL alerts table:** Stores opaque IDs only. If a sanctions match occurred, `sanctions_matched_entry` records which list entry matched (the sanctioned entity's name from the public list, not the creditor's name).
5. **Case Management API:** Enriches alerts with PII from A1 at query time (analyst opens alert → A1 lookup).
6. **AccountProfile state store:** `typicalCreditors` is a `Set<String>` of **IBANs** (opaque identifiers), not names.
7. **Kafka Streams state stores:** Contain only account IDs, IBANs, amounts, timestamps, counts — no names.

The creditor name passes through the sanctions layer transiently (in-memory during scoring) but is never written to any durable store within A5. GDPR data minimization is satisfied: PII is present only where functionally necessary (sanctions name matching) and only for the duration of the operation.

**Relates to principles:** P1 (PII minimization reduces blast radius of data breach), P3 (GDPR data minimization is a legal requirement)

---

## Priority 3 — Relevant Component-Level Decisions

### C12 — State Store Sizing, Performance, and Recovery Time

**The challenge:** Velocity windows and account profiles accumulate significant state. State must survive restarts and rebalancing.

**What is decided:**
- RocksDB-backed Kafka Streams state stores (default, tech stack)
- 60-minute and 24-hour tumbling windows for velocity (section 7)
- 90-day rolling account profiles (section 3.2)
- ~100K accounts estimated (architecture draft section 4.2)

**DECIDED — Standby replicas for production, default RocksDB tuning:**

- **Standby replicas:** `num.standby.replicas=1` for production (decided in C4). Near-instant failover. Storage cost acceptable (~20MB for profiles + windowed data purged after expiry). Not needed for development.
- **Account profile eviction:** If accounts exceed the estimate, oldest profiles (by `lastUpdated`) are evicted. The `typicalCreditors` set already uses LRU eviction (max 100 entries per account, per requirements section 3.2).
- **RocksDB tuning:** Start with Kafka Streams defaults. Monitor `fraud.state.store.size` and RocksDB metrics (block cache hit rate, compaction time). Tune only if monitoring shows problems. Premature RocksDB tuning is a common trap — the defaults are good for most workloads.
- **Recovery time:** With standby replicas, failover is near-instant (state is pre-replicated). Without standbys (development), full changelog replay may take seconds to minutes depending on state volume — acceptable for dev.

---

### C13 — Audit Trail Integrity and Long-Term Retention

**The challenge:** Every scoring decision is legally significant. The audit trail must be immutable, complete, and retained for at least 5 years (AML requirement).

**What is decided:**
- Kafka audit.events topic for all scoring decisions (section 8)
- PostgreSQL for queryable alert data (section 7)
- Immutable audit records (SEC-02, SEC-05)

**DECIDED — Kafka + archival, no cryptographic chaining:**

- **Tamper evidence:** Kafka's append-only nature plus Kafka ACLs (only A5 can produce to `audit.events`) is sufficient for a portfolio project. Cryptographic chaining (hash of previous event) is documented as a production hardening option but not implemented — it adds complexity (ordering guarantees across partitions) with limited benefit unless the threat model includes a compromised Kafka administrator.
- **Long-term retention:** Kafka topic retention is set to a practical period (e.g., 30 days). A separate archival consumer reads `audit.events` and writes to long-term storage. For production: write-once storage (S3 with Object Lock, or WORM-compliant storage). For portfolio: PostgreSQL audit table or local file archival is sufficient to demonstrate the pattern.
- **Audit event schema (minimum required fields):** `transactionId`, `decision`, `fraudScore`, `sanctionsResult`, `sanctionsListVersion`, `rulesTriggered` (list with weights and explanations), `anomalyScore`, `mlScore`, `mlModelVersion` (nullable), `vopResult`, `vopOverride`, `scoringLatencyMs`, `timestamp` (UTC), `processingInstanceId`, `path` (batch/instant).

---

### C14 — Testing Architecture for the Scoring Pipeline

**The challenge:** How to test sanctions screening without real sanctions lists, ML inference reproducibly, and timing-dependent velocity rules deterministically.

**What is decided:**
- Testcontainers for Kafka and PostgreSQL (tech stack)
- JUnit 5 (tech stack)
- ArchUnit for architecture rules (tech stack)

**DECIDED — Four-level testing pyramid:**

**Level 1 — Unit tests (per-layer, no infrastructure):**
- Each `ScoringLayer` implementation tested independently via the `ScoringLayer` interface (C1 Chain of Responsibility enables this)
- **Sanctions layer:** Synthetic sanctions list with fictional names exercising all paths: exact match, fuzzy match (Jaro-Winkler ≥ 0.85), transliteration (Cyrillic→Latin), reversed name order, definite negative (Bloom filter). Synthetic list avoids legal issues with real sanctions data.
- **Velocity rules:** Deterministic test clock (`java.time.Clock` injected) for time-dependent rules. Each rule tested with input that triggers and input that doesn't.
- **ML layer:** Frozen test model with known input→output mappings for regression testing. Tests verify: correct feature vector construction, model loading, checksum verification, fallback when model unavailable.
- **Tool:** JUnit 5 + Mockito

**Level 2 — Topology tests (Kafka Streams, no running Kafka broker):**
- Kafka Streams `TopologyTestDriver` to test the full scoring pipeline topology: input a fraud-check-request record, verify the output fraud-check-response record, verify state store updates (velocity windows, account profiles, dedup cache).
- Tests the pipeline wiring, serialization/deserialization, and state store interactions without Kafka infrastructure.

**Level 3 — Integration tests (with infrastructure via Testcontainers):**
- Testcontainers for Kafka and PostgreSQL
- Full end-to-end: produce a fraud-check-request to Kafka, consume the fraud-check-response, verify alert was persisted in PostgreSQL by the Alert Consumer
- Tests Kafka connectivity, Avro serialization with Schema Registry, PostgreSQL alert persistence, outbox relay

**Level 4 — Architecture tests (ArchUnit):**
- Enforce package dependency rules (e.g., scoring pipeline packages must not depend on Case Management packages)
- Enforce that no PII-handling classes are imported outside the sanctions screening package
- Enforce that all `ScoringLayer` implementations are in the expected package

---

## Challenge Dependency Map

Some challenges depend on others. The following shows which decisions must be made first:

```
C1 (Pipeline Architecture) ──────────┐
  must be decided before:             │
  C5 (Batch/Instant Isolation)        │
  C6 (Idempotency)                    │
  C10 (Velocity Rules Engine)         │
  C14 (Testing Architecture)          │
                                      │
C2 (Sanctions Index Lifecycle) ───────┤
  must be decided before:             │
  C11 (PII Minimization) *            │
                                      │
C3 (Security Architecture) ──────────┤
  influences all other challenges     │
                                      │
C4 (Crash Recovery Model) ───────────┤
  must be decided before:             │
  C8 (Case Mgmt Dual-Write)          │
  C12 (State Store Recovery)          │
                                      │
C7 (ML Model Lifecycle) ─────────────┘
  independent — can be decided in parallel

* C11 interacts with C2 because sanctions screening needs creditor names (PII)
```

---

## Summary Index — All Challenges Decided

| ID | Challenge | Priority | Decision Summary |
|----|-----------|----------|-----------------|
| P1 | Security by Construction | Principle | Every surface defended; secure path is default |
| P2 | Resilience by Design | Principle | Crash recovery designed into every component |
| P3 | Regulatory Compliance as Structure | Principle | Compliance enforced by architecture, not discipline |
| C1 | Scoring Pipeline Architecture | 1 — Critical | **Chain of Responsibility.** Single Kafka Streams processor invokes layered pipeline. Short-circuit on sanctions HIT. |
| C2 | Sanctions Index Lifecycle | 1 — Critical | **Atomic swap + disk cache + strict readiness.** Both daily batch + per-transaction screening. Version in SanctionsSnapshot. |
| C3 | Security Architecture | 1 — Critical | **Defense-in-depth per surface.** HMAC+Schema+ACLs on Kafka. Spring Security hardening on REST. TLS+checksum on downloads. |
| C4 | Crash Recovery Model | 1 — Critical | **Kafka-first for scoring pipeline.** Alerts to Kafka topic, persisted by idempotent consumer. Outbox for Case Management. Startup: sanctions→ML→state recovery→ready. |
| C5 | Batch/Instant Isolation | 1 — Critical | **Separate JVM instances.** Each builds own state stores from changelog. Independent scaling and resource allocation. |
| C6 | Idempotency Strategy | 2 — Important | **Kafka Streams state store** keyed by transactionId, 1-hour TTL. Prevents velocity double-counting. |
| C7 | ML Model Lifecycle | 2 — Important | **Local filesystem + ModelProvider interface.** Restart required for updates. Champion/challenger as future extension. |
| C8 | Case Management Dual-Write | 2 — Important | **Polling-based Transactional Outbox** (100ms interval). Only for REST path; scoring pipeline is Kafka-first (C4). |
| C9 | Observability | 2 — Important | **OpenTelemetry + per-layer Micrometer metrics.** 9 key metrics defined. Alerting thresholds for instant path latency. |
| C10 | Velocity Rules Engine | 2 — Important | **YAML configuration.** Evaluate all rules (no short-circuit). Per-rule window parameters. Git history for audit. |
| C11 | PII Minimization | 2 — Important | **Transient PII in sanctions layer only.** Name used for matching, never persisted. All state stores use opaque IDs. |
| C12 | State Store Sizing | 3 — Relevant | **Standby replicas for production.** Default RocksDB tuning. LRU eviction for account profiles. |
| C13 | Audit Trail Integrity | 3 — Relevant | **Kafka + archival consumer.** No cryptographic chaining (documented as production option). Audit event schema defined. |
| C14 | Testing Architecture | 3 — Relevant | **Four-level pyramid.** Unit (per-layer), topology (TopologyTestDriver), integration (Testcontainers), architecture (ArchUnit). |
