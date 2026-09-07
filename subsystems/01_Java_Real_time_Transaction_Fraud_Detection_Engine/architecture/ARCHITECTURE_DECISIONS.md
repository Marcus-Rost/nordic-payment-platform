# A5 Fraud Detection & Sanctions Engine — Architectural Decisions

*Decisions made during architecture design, with context, options evaluated, and rationale*
*Date: 2026-04-11*
*Input: Requirements (requirements.md), Use Cases (UC01, UC02), Architecture Challenges (ARCHITECTURE_CHALLENGES.md)*
*References: Literature review (Kleppmann, Nygard, Hohpe & Woolf, OWASP, NIST, DORA, EU IPR), Reference systems (ING Bank, Feedzai Railgun, Sportradar, FinTP, TIPS, Danske Bank)*

---

## How to Read This Document

Each decision follows this structure:
- **Context:** Why the decision was needed
- **Options Considered:** Realistic alternatives with pros and cons
- **Decision:** The chosen option
- **Rationale:** Why this option was chosen over the others
- **Consequences:** What follows from this decision — constraints on subsequent design
- **References:** Literature, reference systems, or regulations that informed the decision

Decisions are numbered D1-D14, corresponding to challenges C1-C14 in ARCHITECTURE_CHALLENGES.md.

---

## Foundational Principles

These are not decisions in the option-selection sense — they are commitments that were established during brainstorming and that constrain all subsequent decisions.

### P1 — Security by Construction ("Fortress")

The system is secure by design, not by patching. Security is a structural property of the architecture. Every endpoint is authenticated by default. Sanctions screening is structurally mandatory. PII never enters durable stores in the scoring pipeline.

*Origin: Brainstorming. Reinforced by OWASP Secure by Design Framework, NIST CSF 2.0, EU DORA.*

### P2 — Resilience by Design

Crashes, hardware failures, and denial-of-service attacks are normal operating conditions. Crash recovery is designed from the beginning. The Kafka Streams scoring pipeline uses exactly_once_v2. Dead Letter Queues capture every unprocessable message. Graceful shutdown drains in-flight operations.

*Origin: Brainstorming. Reinforced by Nygard's Release It! stability patterns, Kleppmann's DDIA stream processing model.*

### P3 — Regulatory Compliance as Structural Guarantee

Compliance requirements (AML, GDPR, DORA, EU IPR) are enforced by the architecture, not by developer discipline. Every scoring decision is logged to an immutable audit trail. PII minimization is enforced by data flow design. Sanctions list versions are traced per screening decision.

*Origin: Requirements (SAN-F12, SEC-05, SEC-10). Reinforced by EU DORA, EU Regulation 2024/886 (IPR).*

---

## D1 — Scoring Pipeline Internal Architecture

### Context

The three-layer scoring pipeline (sanctions screening → velocity rules → anomaly detection + ML) is the core of A5. Every other component depends on how this pipeline is structured. The pipeline must support short-circuit logic (sanctions HIT bypasses fraud scoring), graceful degradation (ML unavailable), independent testability per layer, and configurability.

### Options Considered

**Option A: Chain of Responsibility**
Each layer implements a `ScoringLayer` interface with `score(ScoringContext)` and `shouldShortCircuit(ScoringContext)`. A `ScoringPipeline` chains the layers. Within Kafka Streams, a single Processor invokes the pipeline.

- Pros: Each layer independently testable. Adding/removing layers is trivial. Short-circuit is explicit. Degradation is clean (exclude ML layer from chain). Simple Kafka Streams topology.
- Cons: All layers sequential in one thread (no parallelism). ScoringContext must be carefully designed.

**Option B: Pipes-and-Filters as Kafka Streams Sub-Topology**
Each layer is a separate Kafka Streams processor connected by internal topics.

- Pros: Each processor independently scalable. Clean separation.
- Cons: Internal topic hops add 20-80ms latency — unacceptable for 500ms instant budget. Complex topology. Awkward short-circuit logic.

**Option C: Monolithic Scorer Function**
All logic in one method with if-else branching.

- Pros: Simplest. No abstraction overhead.
- Cons: Fails on testability. Degradation logic becomes nested if-else. Adding layers means modifying the function.

**Option D: Rule Engine (Drools)**
Externalize scoring into a declarative rule engine.

- Pros: Maximum configurability.
- Cons: Sanctions and ML are algorithms, not rules. Drools adds latency, dependency weight, debugging complexity. Overkill for 10 velocity rules.

### Decision

**Option A — Chain of Responsibility.**

### Rationale

The pipeline is a fixed sequence of layers with clear short-circuit semantics. Chain of Responsibility is the textbook pattern. It gives independent testability per layer (critical for quality), clean degradation (remove ML layer when unavailable), and a simple Kafka Streams topology (single processor, straightforward exactly-once semantics). The latency cost of Option B is prohibitive. Option C would require refactoring as soon as we need independent layer testing. Option D mismatches the problem (algorithms, not rules).

### Consequences

- The `ScoringContext` is a key internal data structure — carries transaction through the chain, accumulates per-layer results, provides final aggregation. Must be designed carefully.
- A single Kafka Streams Processor invokes the chain. All state stores (velocity windows, account profiles, dedup cache) are registered on this processor.
- The velocity rules *within* Layer 2 may use a lighter configuration mechanism (YAML) — see D10.

### References

- Hohpe & Woolf, *Enterprise Integration Patterns*: Pipes and Filters pattern (we use the logical pattern without the physical topic separation)
- Feedzai Railgun: strict `Window → Filter → Group By → Aggregator` computation DAG validates layered pipeline approach

---

## D2 — Sanctions Index Lifecycle Management

### Context

The sanctions index (Bloom filter + Aho-Corasick automaton + fuzzy match index) must be rebuilt atomically when lists update, track its version for audit traceability, recover after crashes, and handle the EU IPR regulatory model.

### Sub-decisions

#### D2a — Screening Model (IPR Regulatory Question)

**Options:** Per-transaction screening only | Daily customer-base screening only | Both

**Decision: Both.** Daily batch screening of the PSP's own customer base satisfies EU Regulation 2024/886. Per-transaction screening of beneficiary names catches sanctioned entities who are not our own customers. Fast-path: check `blockedAccounts` set first (O(1)); full three-tier pipeline for unknown beneficiaries.

**Rationale:** The IPR requires customer-base screening, but per-transaction beneficiary screening is still needed — a payment to a sanctioned entity must be caught even if the originator is clean. Both models complement each other.

**Reference:** EU Regulation 2024/886 (Instant Payments Regulation), ECB TIPS documentation (sanctions screening is the PSP's responsibility before submitting to TIPS).

#### D2b — Version Tracking

**Options:** Version embedded in SanctionsSnapshot | Separate version registry

**Decision: Version embedded in SanctionsSnapshot.** The `SanctionsSnapshot` object (held by `AtomicReference`) carries a `listVersion` field. The sanctions layer reads `snapshot.listVersion()` and writes it into the `ScoringContext`. This flows through to the audit record.

**Rationale:** The version is always co-located with the data structures it describes. No synchronization needed — `AtomicReference.get()` returns a consistent snapshot including its version. A separate registry adds a dependency and a risk of version mismatch.

#### D2c — Crash Recovery (List Caching)

**Options:** Re-download on every startup | Cache on local disk | Kafka topic as cache

**Decision: Cache downloaded list files on local disk.** On every successful download, write the raw XML/JSON atomically (write to temp file, rename). On startup, rebuild from cached file. Then immediately poll for updates.

**Rationale:** No external network dependency at startup (P2 — resilience). File I/O is faster than HTTP download. If the EU endpoint is temporarily down, the system continues with the cached list. Kafka topic as cache adds complexity without proportional benefit.

#### D2d — Startup Readiness

**Options:** Reject requests until index ready | Accept and fail-closed during warmup

**Decision: Strict readiness.** A5 does not register Kafka consumers until the sanctions index is fully built. Kubernetes readiness probe returns unhealthy until ready. Messages accumulate in Kafka during startup.

**Rationale:** Structurally correct (P1). If the index isn't ready, A5 isn't ready. Kafka is designed to accumulate messages. The alternative (accept and fail-closed) is functionally equivalent from the payment's perspective but adds pipeline complexity for the "index not ready" state.

### Consequences

- A5 has two sanctions screening paths: daily batch job (screen customer base) and per-transaction (screen beneficiary name in the scoring pipeline)
- Startup time includes sanctions index rebuild (estimated 10-30 seconds from disk cache)
- Every audit record includes `sanctionsListVersion` — traceable to the specific list used

---

## D3 — Security Architecture and Attack Surface

### Context

A5 has five distinct attack surfaces. Principle P1 (Security by Construction) requires each to be defended structurally.

### Decision — Defense-in-Depth Per Surface

#### Surface 1: Kafka Consumer Interface

**Decision:** Three-layer defense:
1. Kafka ACLs — only A2's service account can produce to fraud-check-request topics (infrastructure)
2. Avro Schema Registry — validates message structure on deserialization; malformed → DLQ (structural)
3. HMAC verification — proves message authenticity, detects tampering (application, per master architecture requirement KM-06 in system_requirements.md)

**Rationale:** Layered defense per P1. HMAC overhead ~0.1ms — negligible. Each layer catches what the others miss: ACLs prevent unauthorized producers, schema validation catches structural errors, HMAC detects tampering.

#### Surface 2: Case Management REST API

**Decision:** Standard Spring Security hardening with deny-all default:
- OAuth2 Resource Server (fraud:read, fraud:manage scopes)
- Parameterized queries via JPA/Hibernate (SQL injection prevented by construction)
- Bean Validation (`@Valid`) on all request bodies
- Rate limiting (SEC-09)
- CORS restricting origins to analyst UI domain
- Security headers via Platform Starter
- No PII in error messages

**Rationale:** Uses Spring Security's well-tested framework. Most controls are configuration. The Platform Starter enforces deny-all default (master Decision A.1b). WAF is a deployment-time decision, documented as production recommendation.

#### Surface 3: Sanctions List Download

**Decision:** TLS + checksum verification (SAN-F08) + size/format validation + operator alerting on failure or dramatic changes.

**Rationale:** Multi-check defense. Certificate pinning rejected — too brittle for external government endpoints.

#### Surface 4: ML Model Loading

**Decision:** SHA-256 checksum at load time (ML-F05) + model sourced from trusted location (restricted filesystem) + model version logged with every decision (ML-F06).

#### Surface 5: State Stores (RocksDB)

**Decision:** Filesystem-level encryption for production (LUKS, dm-crypt, or cloud encrypted volumes). Access controls only for development.

**Rationale:** Deployment concern, not application architecture — application code does not change. Matches master two-tier approach (Decision A.2a). RocksDB-level encryption rejected (5-15% I/O overhead without benefit over filesystem encryption).

### Explicitly Out of Scope

APT with prolonged undetected access, physical security, social engineering of analyst credentials, supply chain attacks on third-party libraries (mitigated by CI/CD dependency scanning, not by A5's architecture).

#### Surface 6: PostgreSQL (Alerts Database)

**Decision:** Encryption at rest for the alerts database (SEC-04). For production: PostgreSQL TDE (Transparent Data Encryption) or cloud-provider encrypted storage (e.g., AWS RDS encryption, Azure Database encryption). For development: not required. Access logging enabled — all queries to the fraud_alerts table produce audit log entries.

**Rationale:** SEC-04 requires transaction data encrypted at rest and access logged. The fraud_alerts table contains transaction amounts, account IDs, and scoring details. Encryption at rest is a deployment configuration, not application code. Access logging uses PostgreSQL's `pgaudit` extension or cloud-provider audit logging.

### Explicitly Out of Scope

APT with prolonged undetected access, physical security, social engineering of analyst credentials, supply chain attacks on third-party libraries (mitigated by CI/CD dependency scanning, not by A5's architecture).

### Consequences

- All Kafka consumers verify HMAC before processing — adds ~0.1ms per message
- Platform Starter provides the security baseline; A5 inherits it
- State store encryption is an infrastructure requirement documented in the deployment guide
- PostgreSQL encryption at rest and access logging are deployment requirements

---

## D4 — Crash Recovery Model

### Context

A5 can crash at multiple points during processing. A unified recovery model is needed, not ad-hoc handling per scenario. A5 has two processing paths with fundamentally different recovery characteristics: the automated scoring pipeline (Kafka Streams) and the Case Management API (REST + PostgreSQL).

### Decision — Two-Path Recovery Model

#### Scoring Pipeline: Kafka-First

The scoring processor writes *only* to Kafka: fraud-check-response to A2 and alert events to `fraud.alert-events`. Kafka Streams `exactly_once_v2` handles atomic commit of consumer offsets, state store changes, and output records.

A separate idempotent Alert Consumer reads `fraud.alert-events` and persists alerts to PostgreSQL. Deduplication via UNIQUE constraint on transactionId (ON CONFLICT DO NOTHING).

**Options rejected:**
- Alerts inside the Kafka Streams processor (dual-write — PostgreSQL INSERT + Kafka commit not atomic; PostgreSQL unavailability stalls scoring, violating 99.99% availability)
- Transactional Outbox for scoring pipeline (inverts the flow — response published by relay, not Kafka Streams; breaks exactly_once_v2 model; adds latency; wrong pattern for Kafka→Kafka flows)

#### Case Management: Transactional Outbox

When an analyst resolves an alert: single database transaction writes alert update + outbox record. Polling relay publishes to Kafka. Details in D8.

#### Startup Sequence

1. Connect to Kafka (broker discovery)
2. Rebuild sanctions index from cached list file *(parallel with step 3)*
3. Load ML model — or enter ML_DISABLED mode *(parallel with step 2)*
4. Initialize Kafka Streams topology — state stores recover from changelog
5. Kubernetes readiness probe returns healthy
6. Start consuming fraud-check-requests

#### State Store Failover

`num.standby.replicas=1` for production. Standby replicas pre-replicate state for near-instant failover. Storage cost acceptable (~20MB for profiles + windowed data).

### Rationale

Kafka-first preserves the atomic commit model of Kafka Streams. PostgreSQL unavailability does not affect scoring availability — critical for 99.99% target. The alert consumer's dual-write is trivially solvable (idempotent INSERT). Clean separation: hot scoring path (latency-critical) decoupled from alert persistence (not latency-critical).

### Consequences

- Alerts are not immediately queryable — small delay (milliseconds) between scoring and alert appearing in PostgreSQL
- `fraud.alert-events` is a new internal Kafka topic
- The Alert Consumer is a separate component within A5 (runs alongside the Kafka Streams topology)

### Crash Scenario Resolution

| Scenario | Recovery |
|----------|----------|
| Mid-scoring | exactly_once_v2 replays from last committed offset |
| After scoring, before Kafka commit | Uncommitted output discarded, transaction re-scored |
| Alert consumer crashes | Kafka replays; idempotent INSERT |
| PostgreSQL unavailable | Scoring unaffected; alerts accumulate in Kafka |
| Sanctions index rebuild interrupted | AtomicReference — old index stays active |
| ML model load fails | ML_DISABLED mode |
| Full crash and restart | Startup sequence; standby replicas reduce recovery time |
| A2 timeout (late response) | A5 still publishes response; if late SANCTIONS_HIT, creates LATE_SANCTIONS_HIT alert (FRD-RES04) |
| Graceful shutdown (SIGTERM) | Drain in-flight scoring; publish TIMEOUT for incomplete operations (FRD-RES03) |

#### Graceful Shutdown (FRD-RES03)

On SIGTERM, A5 pauses Kafka consumers, completes in-flight scoring, and commits final offsets. If scoring cannot complete within the shutdown grace period, A5 publishes a response with `decision: TIMEOUT` so A2 can handle it (reject the payment or queue for re-scoring). This ensures no scoring request goes unanswered.

#### Late-Arriving Responses (FRD-RES04)

If A5 completes scoring after A2 has already timed out and auto-passed the payment, A5 still publishes the response (logged by A2). If the late response is `SANCTIONS_HIT`, A5 additionally publishes a `LATE_SANCTIONS_HIT` alert to `fraud.alert-events` for immediate analyst review — the payment may have already settled and may require reversal.

#### Dead Letter Queue (FRD-RES02)

Every Kafka consumer routes unprocessable messages to `{topic}.dlq` after 3 retries. DLQ messages include: original payload, error details, retry count, timestamp. DLQ depth monitored via `fraud.dlq.depth` Prometheus metric. Error classification: schema validation failure → DLQ immediately; HMAC failure → DLQ + security alert; business logic exception → DLQ after 3 retries; transient Kafka error → retry with backoff (indefinite).

### References

- Kleppmann, *DDIA* Ch. 11: exactly-once across external systems requires idempotent sinks
- Nygard, *Release It!*: Bulkhead pattern — isolating the scoring path from the alert persistence path
- Feedzai Railgun: RocksDB state with frequent persistence; minimal replay on failure

---

## D5 — Batch vs. Instant Path Isolation

### Context

Both batch and instant fraud-check requests use the same scoring logic and the same state (velocity rules must see all transactions). But instant has a non-negotiable 500ms latency budget. A batch burst must not affect instant scoring.

### Options Considered

**Option A: Single JVM, shared thread pool.** One process runs both consumer groups.
- Pros: Simplest deployment. State stores naturally shared. Lower cost.
- Cons: Batch burst consumes CPU and triggers GC pauses, increasing instant latency. No hard resource isolation.

**Option B: Single JVM, separate thread pools.** Two KafkaStreams instances in one JVM, each with dedicated threads.
- Pros: Thread-level isolation. Still one deployment.
- Cons: GC pauses and memory bus contention still shared. Unusual configuration.

**Option C: Separate JVM instances (separate pods).** Each has its own KafkaStreams instance, state stores, resource allocation.
- Pros: Maximum isolation — CPU, memory, GC fully independent. Independent scaling. Independent deployment.
- Cons: State store duplication (each builds from changelog). Two deployment units.

### Decision

**Option C — Separate JVM instances.**

### Rationale

The 500ms instant budget is non-negotiable. Any architecture where a batch burst *could* affect instant latency is a risk. Options A and B share JVM resources (GC, memory bus). Option C eliminates this risk class entirely.

State store duplication is acceptable: each instance builds from the same changelog topics. Both see all transactions (changelog records all state changes). Storage is doubled but small (~20MB profiles + windowed data). Disk is cheap; latency guarantees are not.

Propagation delay between instances (milliseconds via changelog replay) is insignificant for 60-minute and 24-hour velocity windows.

### Consequences

- Two Kubernetes deployments for A5: `a5-fraud-instant` and `a5-fraud-batch`
- Instant path can have guaranteed CPU/memory via Kubernetes resource requests
- Batch path can use burstable resources
- Scoring logic, configuration, and container image are identical — only the consumed topic differs
- Both instances publish to the same output topics and `fraud.alert-events`

### References

- Master ARCHITECTURE_CHALLENGES.md (C.3): same question for A2, noting separate instances may be needed
- Feedzai Railgun: custom engine with explicit resource isolation between processing units

---

## D6 — Idempotency and Deduplication Strategy

### Context

A2 may retry fraud-check-requests (timeout, A5 slow response). Within Kafka Streams, `exactly_once_v2` handles crash-replay dedup. The remaining case: A2 intentionally retries with a new Kafka offset but the same transactionId.

### Options Considered

**Option A: Caffeine cache.** JVM-local, ~720MB at 1,000 TPS with 1-hour TTL.
- Cons: Lost on crash. Heavy memory for instant pod.

**Option B: Kafka Streams state store (RocksDB).** KeyValueStore keyed by transactionId.
- Pros: Survives crashes (changelog-backed). RocksDB handles large datasets. Natural fit.
- Cons: ~1-2ms lookup (vs. <0.1ms Caffeine).

**Option C: No application-level dedup.** Re-score duplicates; rely on idempotent alert INSERT.
- Cons: Double-counting in velocity windows. Distorted account profiles. Correctness issue.

### Decision

**Option B — Kafka Streams state store.**

### Rationale

Option C is eliminated — double-counting in velocity windows is a correctness issue. Between A and B: the state store survives crashes (dedup state must be part of the atomic Kafka Streams state for exactly_once_v2 consistency), and 720MB Caffeine is heavy for the instant pod. The 1-2ms RocksDB lookup is negligible against 500ms. Entries purged via punctuation after 1-hour TTL.

### Consequences

- Before scoring, the processor checks the dedup state store. If transactionId exists, return cached ScoringResult — no re-scoring, no velocity side effects.
- The dedup state store adds to changelog volume and state store size (manageable at 1,000 TPS)
- The dedup key is `transactionId` (generated by A2, unique per payment)

---

## D7 — ML Model Lifecycle

### Context

The ML model (DJL, ONNX or PyTorch) must be loaded, integrity-verified, versioned, and gracefully degradable. The question is where models are stored and how they are updated.

### Sub-decisions

#### D7a — Model Storage

**Decision: Local filesystem** with a `ModelProvider` interface. Default implementation: `LocalFileModelProvider`. For portfolio, model ships with container image. Interface enables future `S3ModelProvider` without changing the pipeline.

**Rejected:** S3 at startup (adds external dependency — P2 violation). Maven dependency (same redeployment cost, more packaging complexity).

#### D7b — Model Updates

**Decision: Restart required.** Model updates deployed via rolling pod replacement. The `ModelProvider` returns an `AtomicReference<Model>` to enable future hot-reload, but the reload mechanism is not built now.

**Rejected:** Hot-reload via file watching or Kafka control topic (over-engineering for portfolio scope). Documented as future extension.

**Reference:** Sportradar packages DJL models as Maven dependencies for in-process inference. ING Bank streams PMML models via Kafka for zero-downtime updates (future path for us).

#### D7c — Champion/Challenger

**Decision: Not implemented.** Single model with version logging (ML-F06). Documented as future extension following Danske Bank pattern (60% false positive reduction via champion/challenger).

### Consequences

- Model file is a deployment artifact — versioned via container image tags
- `ModelProvider` interface is small (~3 methods) but makes the production upgrade path explicit
- `AtomicReference<Model>` is used internally even though hot-reload isn't built — costs nothing, enables future extension

---

## D8 — Case Management Dual-Write

### Context

When an analyst resolves an alert (e.g., CONFIRMED_FRAUD), A5 must update PostgreSQL (alert status) AND publish to Kafka (freeze command, audit event). The scoring pipeline is Kafka-first (D4), so the outbox is only for this REST path.

### Options Considered

**Option A: Polling-based outbox relay.** Spring `@Scheduled` polls outbox table every 100ms.
- Pros: Simplest. No additional infrastructure. Idempotent (re-publishes on crash).
- Cons: Up to 100ms latency.

**Option B: Debezium CDC.** Monitors PostgreSQL WAL, streams to Kafka automatically.
- Pros: Near-zero latency. Well-proven.
- Cons: Kafka Connect + Debezium connector — operational overhead for portfolio.

**Option C: Spring TransactionalEventListener.** Publishes to Kafka after DB commit.
- Pros: No polling, no infrastructure.
- Cons: If Kafka publish fails after commit, event is lost (at-most-once). Unacceptable for freeze commands.

### Decision

**Option A — Polling-based outbox relay.**

### Rationale

Case Management volume is low (tens of alert resolutions per hour). 100ms polling latency is irrelevant for analyst workflows. Simplicity wins over Debezium's infrastructure overhead. Option C's at-most-once failure mode is unacceptable — a lost freeze command means a fraudulent account stays active.

### Consequences

- Outbox table in A5's PostgreSQL schema: `id`, `event_type`, `aggregate_id`, `payload` (JSONB), `created_at`, `published_at`
- Spring `@Scheduled` relay polls every 100ms, publishes to Kafka, marks as published
- Downstream consumers (A1 for freeze commands) handle duplicates via idempotent processing
- The relay runs within the same A5 JVM that hosts the Case Management API (not in the scoring pods)

---

## D9 — Observability and Distributed Tracing

### Context

Operators need end-to-end visibility into scoring latency, per-layer timing, and decision explanations across Kafka boundaries.

### Decision

**OpenTelemetry** for distributed tracing with Kafka client instrumentation. Trace context propagated automatically in Kafka headers. Spring Boot auto-configuration. Platform Starter provides the baseline.

**Micrometer → Prometheus** for metrics. Nine key metrics defined:

| Metric | Type | Purpose |
|--------|------|---------|
| `fraud.scoring.latency` (tags: path, layer) | Timer | SLA monitoring per layer |
| `fraud.sanctions.hit.rate` | Counter ratio | Operational monitoring |
| `fraud.decision` (tags: decision type) | Counter | Volume monitoring |
| `fraud.false.positive.rate` | Gauge | Model/rule quality |
| `fraud.ml.available` | Gauge (0/1) | Degradation detection |
| `fraud.dlq.depth` | Gauge | Operational health |
| `fraud.consumer.lag` (tags: topic) | Gauge | Critical for instant topic |
| `fraud.sanctions.index.version` | Info gauge | Audit traceability |
| `fraud.state.store.size` | Gauge | Capacity planning |

**Alerting thresholds:**
- Instant path p95 > 400ms → WARN; > 480ms → CRITICAL
- ML unavailable → WARN
- DLQ depth > 0 → WARN
- Instant consumer lag > 100 → CRITICAL

### Rationale

OpenTelemetry is the industry standard. Manual trace ID propagation rejected — no automatic span creation, manual effort per producer/consumer. Per-layer instrumentation gives pinpoint visibility into latency regressions (e.g., sanctions screening suddenly slower after a list update).

### References

- Platform Starter (Project #4): provides OpenTelemetry auto-configuration for all services
- EPC SCT Inst Rulebook 2025: 5-second end-to-end SLA makes per-layer timing essential

---

## D10 — Velocity Rules Engine

### Context

10 velocity rules evaluate transaction patterns against windowed state. Rules should be configurable without code changes (FRD-F09, Should priority).

### Decision

**YAML configuration** loaded via Spring `@ConfigurationProperties`. Each rule defines: id, name, condition type, threshold, window duration, severity weight, enabled flag.

**Evaluate all rules** for every transaction — no short-circuit. Even if score exceeds AUTO_BLOCK after the first rule, all triggered rules are recorded for alert explanations and audit trail completeness (P3).

Each rule defines its own window parameters (VEL-01: 60 minutes, VEL-03: 24 hours).

### Rationale

10 rules is a small, well-defined set. YAML is simple, readable, version-controlled. Changes require rolling restart — acceptable because fraud rule changes are infrequent and should go through review (P3: git history provides audit trail). Database-stored rules are over-engineering. Kafka control topic is documented as future extension if the rule set grows significantly.

### References

- ING Bank: Flink broadcast state for runtime rule updates (future path)
- Flink fraud detection reference architecture: Dynamic Rules Engine with JSON on Kafka control topic (future path)

---

## D11 — PII Minimization

### Context

GDPR Art. 5(1)(c) requires data minimization. The scoring pipeline should process anonymized data. But sanctions screening requires the creditor name for fuzzy matching — a direct conflict.

### Decision

**Transient PII in the sanctions layer only.**

The creditor name is present in the fraud-check-request message (A2 includes it — sanctions screening needs it). The name passes through the sanctions layer transiently in memory but is **never persisted** in any A5 state store or database.

**PII data flow:**
1. Sanctions layer: receives name, performs matching, records result. Name is not persisted.
2. Velocity + anomaly + ML: operate on accountId (opaque), amount, timestamp. Never see the name.
3. Alert events + PostgreSQL alerts: contain accountId and transactionId only. No names.
4. Case Management API: enriches with PII from A1 at query time.
5. AccountProfile.typicalCreditors: IBANs (opaque identifiers), not names.
6. All state stores: account IDs, IBANs, amounts, timestamps, counts. No names.

### Rationale

The legal obligation to screen names (AML, sanctions regulation) overrides the desire to eliminate PII entirely. The resolution: PII is present only where functionally necessary and only for the duration of the operation. GDPR data minimization is satisfied. The blast radius of a data breach in A5 is minimized — compromising A5's database or state stores reveals transaction patterns but not account holder names.

---

## D12 — State Store Sizing and Recovery

### Context

Velocity windows and account profiles accumulate significant state in RocksDB-backed Kafka Streams state stores.

### Decision

- **Standby replicas:** `num.standby.replicas=1` for production (near-instant failover, decided in D4)
- **Account profile eviction:** Oldest profiles by `lastUpdated` evicted when capacity exceeded. `typicalCreditors` uses LRU (max 100 per account, per requirements)
- **RocksDB tuning:** Start with Kafka Streams defaults. Monitor metrics. Tune only if monitoring shows problems
- **Recovery time:** Near-instant with standby replicas. Seconds to minutes without (development)

### Rationale

Premature RocksDB tuning is a common trap — defaults are good for most workloads. Standby replicas are the primary mechanism for fast failover, not RocksDB configuration.

---

## D13 — Audit Trail Integrity

### Context

Every scoring decision is legally significant. AML requires 5-year retention. The trail must be immutable and complete.

### Decision

- **Primary store:** Kafka `audit.events` topic (append-only, ACL-protected)
- **Archival:** Separate consumer writes to long-term storage. Production: S3 with Object Lock (WORM). Portfolio: PostgreSQL audit table
- **Tamper evidence:** Kafka append-only + ACLs sufficient for portfolio. Cryptographic chaining (hash of previous event) documented as production hardening option
- **Audit event schema:** transactionId, decision, fraudScore, sanctionsResult, sanctionsListVersion, rulesTriggered (with weights and explanations), anomalyScore, mlScore, mlModelVersion, vopResult, vopOverride, scoringLatencyMs, timestamp (UTC), processingInstanceId, path (batch/instant)

### Rationale

Cryptographic chaining adds complexity (ordering guarantees across partitions) with limited benefit unless the threat model includes a compromised Kafka administrator — explicitly out of scope (D3). The audit event schema captures every input to the scoring decision, enabling complete forensic reconstruction.

---

## D14 — Testing Architecture

### Context

The scoring pipeline involves sanctions screening (legal sensitivity of real lists), ML inference (reproducibility), and time-dependent velocity rules (determinism).

### Decision — Four-Level Testing Pyramid

**Level 1 — Unit tests (per-layer, no infrastructure):**
- Each `ScoringLayer` tested independently (Chain of Responsibility enables this — D1)
- Sanctions: synthetic list with fictional names exercising all paths (exact, fuzzy, transliteration, negative)
- Velocity: deterministic test clock (`java.time.Clock` injected)
- ML: frozen test model with known input→output mappings

**Level 2 — Topology tests (no running Kafka):**
- Kafka Streams `TopologyTestDriver`: input fraud-check-request, verify output, verify state store updates

**Level 3 — Integration tests (Testcontainers):**
- Kafka + PostgreSQL in containers
- End-to-end: produce request → consume response → verify alert in PostgreSQL

**Level 4 — Architecture tests (ArchUnit):**
- Package dependency enforcement
- PII-handling class isolation to sanctions package
- ScoringLayer implementations in expected package

### Rationale

The Chain of Responsibility (D1) enables Level 1 — each layer is independently testable. TopologyTestDriver (Level 2) gives the fastest feedback for pipeline wiring. Testcontainers (Level 3) verify real infrastructure integration. ArchUnit (Level 4) prevents structural drift.

### References

- Testcontainers: used by Tietoevry (tech stack alignment)
- TopologyTestDriver: Kafka Streams standard for topology unit testing
- ArchUnit: enforces "security by construction" at the package level (P1)

---

## Decision Cross-Reference

| Decision | Depends On | Enables |
|----------|-----------|---------|
| D1 (Pipeline) | — | D5, D6, D10, D14 |
| D2 (Sanctions Index) | — | D11 |
| D3 (Security) | — | All decisions (constrains) |
| D4 (Crash Recovery) | D1 | D8, D12 |
| D5 (Batch/Instant) | D1 | — |
| D6 (Idempotency) | D1, D4 | — |
| D7 (ML Lifecycle) | — | — |
| D8 (Dual-Write) | D4 | — |
| D9 (Observability) | D1 | — |
| D10 (Rules Engine) | D1 | — |
| D11 (PII) | D2 | — |
| D12 (State Stores) | D4 | — |
| D13 (Audit Trail) | D3 | — |
| D14 (Testing) | D1 | — |
