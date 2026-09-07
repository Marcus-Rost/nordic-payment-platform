# A5 Fraud Detection & Sanctions Engine — Architectural Design

*System role: A5 — Fraud scoring + sanctions screening for all payment transactions*
*Date: 2026-04-11*
*Decisions basis: ARCHITECTURE_DECISIONS.md (D1-D14), ARCHITECTURE_CHALLENGES.md (P1-P3, C1-C14)*
*Requirements basis: requirements.md, UC01 (Transaction Scoring), UC02 (Case Management)*

---

## 1. Architectural Overview

A5 is the compliance-critical subsystem of the Payment Processing platform. Every payment — both batch and instant — must pass through A5 before settlement. A5 makes two categories of decisions:

1. **Sanctions screening** (legal obligation, cannot be skipped): Is any party to this transaction on an EU or UN sanctions list?
2. **Fraud scoring** (risk management): How likely is this transaction to be fraudulent, based on behavioral patterns, statistical anomalies, and ML model inference?

A5 also provides a **Case Management API** for human analysts to review and resolve flagged transactions.

### 1.1 Architectural Principles

Three foundational principles constrain every design decision:

**P1 — Security by Construction.** The system is secure by design. Every endpoint is authenticated by default. Sanctions screening is structurally mandatory. PII never enters durable stores in the scoring pipeline. A developer cannot accidentally create an insecure path.

**P2 — Resilience by Design.** Crashes are normal operating conditions. The Kafka Streams scoring pipeline uses exactly-once semantics. Dead Letter Queues capture every unprocessable message. The system degrades gracefully when components are unavailable.

**P3 — Regulatory Compliance as Structure.** AML, GDPR, DORA, and EU IPR requirements are enforced by the architecture. Every scoring decision is immutably logged with the specific sanctions list version and model version used. PII minimization is enforced by data flow design, not developer discipline.

### 1.2 System Context

```
                                 ┌──────────────────────────────────────┐
                                 │  A5: Fraud & Sanctions Engine        │
                                 │                                      │
   A2 Clearing ──── Kafka ──────►│  Scoring Pipeline (Kafka Streams)    │──── Kafka ────► A2 Clearing
   Engine           fraud-check- │                                      │    fraud-check-  Engine
                    request      │  Alert Consumer                      │    response
                                 │                                      │
                                 │  Sanctions List Manager              │──── Kafka ────► A1 Account
                                 │                                      │    account.      Ledger
   EU/UN List ──── HTTP ────────►│  Case Management API (REST)          │    freeze-
   Endpoints        (scheduled)  │                                      │    commands
                                 └──────────────────────────────────────┘
                                          │              ▲
                                          ▼              │
                                    PostgreSQL      OAuth2 (JWT)
                                    (alerts,        Analysts
                                     outbox)
```

*Note: `account.freeze-commands` are produced by the Case Management API (via Transactional Outbox, D8), not by the Scoring Pipeline. The diagram shows the aggregate system boundary.*

### 1.3 Interaction Patterns

| Interface | Direction | Protocol | Latency Budget |
|-----------|-----------|----------|----------------|
| A2 → A5 (fraud check requests) | Inbound | Kafka consumer | — |
| A5 → A2 (fraud check responses) | Outbound | Kafka producer | < 500ms total (instant) — this is A5's share of the 5-second EPC SLA, allocated by A2 |
| A5 → A1 (account freeze commands) | Outbound | Kafka producer | Async |
| A5 → audit (scoring decisions) | Outbound | Kafka producer | Async |
| EU/UN sanctions list endpoints | Outbound | HTTP (scheduled polling) | Async (hourly) |
| Case Management API | Inbound | REST (OAuth2) | < 200ms |
| Prometheus | Outbound | HTTP scrape | — |

---

## 2. Deployment Architecture

### 2.1 Three Deployment Units

A5 deploys as three independent units (Decision D5):

```
┌─────────────────────────────┐  ┌─────────────────────────────┐  ┌─────────────────────────────┐
│  a5-fraud-instant           │  │  a5-fraud-batch             │  │  a5-case-management         │
│                             │  │                             │  │                             │
│  KafkaStreams instance      │  │  KafkaStreams instance      │  │  Spring MVC (REST API)      │
│  Consumes: instant topic    │  │  Consumes: batch topic      │  │  Case Management API        │
│  Scoring Pipeline           │  │  Scoring Pipeline           │  │  Outbox Relay               │
│  Alert Consumer             │  │  Alert Consumer             │  │  Alert Consumer*            │
│  Sanctions List Manager     │  │  Sanctions List Manager     │  │                             │
│  ML Model (DJL)             │  │  ML Model (DJL)             │  │  OAuth2 Resource Server     │
│                             │  │                             │  │                             │
│  Guaranteed CPU/memory      │  │  Burstable resources        │  │  Standard resources         │
│  num.standby.replicas=1     │  │  num.standby.replicas=1     │  │                             │
└─────────────────────────────┘  └─────────────────────────────┘  └─────────────────────────────┘
         │                                │                                │
         └────────── Shared ──────────────┘                                │
         │  Kafka (topics, changelog)     │                                │
         │  PostgreSQL (alerts, outbox)   ├────────────────────────────────┘
         └────────────────────────────────┘
```

*The Alert Consumer may run in all three units or only in the case-management unit — a deployment-time choice. Running it in all three provides redundancy.*

**Why separate JVM instances for batch and instant:**
- The instant path's 500ms latency budget is non-negotiable. GC pauses, CPU contention, and memory bus saturation from batch processing must not affect instant scoring.
- Each instance builds its own Kafka Streams state stores from changelog topics. Both see all transactions (changelog records all state changes regardless of source topic). Propagation delay is milliseconds — insignificant for 60-minute and 24-hour velocity windows.
- Independent scaling: instant path can have more replicas or guaranteed resources.

**Why a separate case-management unit:**
- The Case Management API is a standard Spring MVC application with database access. It does not run a Kafka Streams topology.
- Its resource profile (handling analyst REST requests) is different from the scoring pods (streaming Kafka processing).
- It hosts the Transactional Outbox relay for freeze commands.

### 2.2 Startup Sequence

Each scoring pod (instant and batch) follows this startup sequence:

```
1. Connect to Kafka ────────────────────────────────────► Kafka available
                                                          then in parallel:
2. Rebuild sanctions index from cached list file ──┐
3. Load ML model (or enter ML_DISABLED mode) ──────┤──► All three run in parallel
4. Initialize Kafka Streams (state stores from     │    (all depend on step 1)
   changelog) ─────────────────────────────────────┘
                                                          then sequentially:
5. Kubernetes readiness probe → HEALTHY ───────────────► Only after 2+3+4 complete
6. Start consuming fraud-check-requests
```

A5 does not consume requests until the sanctions index is built (P1 — structural guarantee). Messages accumulate in Kafka during startup.

---

## 3. Scoring Pipeline Architecture

### 3.1 Chain of Responsibility (Decision D1)

The scoring pipeline is implemented as a Chain of Responsibility. Each layer implements a `ScoringLayer` interface:

```
interface ScoringLayer {
    ScoringContext score(ScoringContext ctx);
    boolean shouldShortCircuit(ScoringContext ctx);
}
```

A `ScoringPipeline` chains the layers in fixed order:

Layer numbering follows requirements.md: sanctions = Layer 0, velocity = Layer 1, anomaly = Layer 2, ML = Layer 3. The deduplication check is a pre-pipeline guard, not a scoring layer (it does not implement `ScoringLayer`).

```
Transaction
    │
    ▼
┌──────────────────────────────────────────────────────┐
│  Pre-check: Deduplication (D6)                       │
│  Check transactionId in state store                  │
│  If duplicate → return cached result, skip pipeline  │
└──────────────┬───────────────────────────────────────┘
               │ new transaction
               ▼
┌──────────────────────────────────────────────────────┐
│  Layer 0: Sanctions Screening (SAN-F01..F14)         │
│  blockedAccounts set → Bloom filter → Aho-Corasick   │
│  → Jaro-Winkler fuzzy match                          │
│                                                      │
│  HIT → short-circuit: SANCTIONS_HOLD                 │
│  CLEAR → continue                                    │
└──────────────┬───────────────────────────────────────┘
               │ CLEAR
               ▼
┌──────────────────────────────────────────────────────┐
│  Layer 1: Velocity Rules (FRD-F02, VEL-01..VEL-10)   │
│  Evaluate ALL 10 rules against Kafka Streams state   │
│  stores (WindowStore per window duration)             │
│  Rules loaded from YAML configuration (D10)          │
│  All triggered rules recorded (no short-circuit)     │
└──────────────┬───────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────┐
│  Layer 2: Anomaly Detection (FRD-F03)                │
│  Fetch AccountProfile from KeyValueStore             │
│  Compute z-scores (amount, frequency)                │
│  Check unusual hour, new creditor                    │
│  anomaly_score = sigmoid(weighted sum)               │
└──────────────┬───────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────┐
│  Layer 3: ML Scoring (FRD-F04, ML-F01..F07)          │
│  If ML_DISABLED → skip (weights redistributed)       │
│  Construct feature vector → DJL inference            │
│  ml_score = model output (0.0-1.0)                   │
│                                                      │
│  **EU AI Act (2024/1689) Classification:**            │
│  A5's ML model is used exclusively for fraud          │
│  detection, which is explicitly exempt from high-risk │
│  classification under AI Act Annex III, Section 5(b). │
│  To maintain this exemption, the fraud score output   │
│  must NOT be used for creditworthiness assessment,    │
│  account eligibility decisions, or any purpose other  │
│  than fraud/AML detection. This boundary is enforced  │
│  by the API contract: the fraud score is consumed     │
│  only by A2 for payment accept/reject decisions and   │
│  by the Case Management dashboard for analyst review. │
└──────────────┬───────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────┐
│  Score Aggregation + Decision                        │
│  combined = w_vel * velocity + w_anom * anomaly      │
│           + w_ml * ml + vop_override_bonus            │
│                                                      │
│  Default weights (ML enabled):                       │
│    w_vel=0.2, w_anom=0.2, w_ml=0.2                   │
│  Fallback weights (ML disabled):                     │
│    w_vel=0.35, w_anom=0.35                            │
│  VoP override bonus: +0.3 if VoP NO_MATCH overridden │
│                                                      │
│  Sanctions HIT → SANCTIONS_HOLD (bypasses this step) │
│  < 0.5 → AUTO_PASS                                   │
│  0.5-0.7 → MANUAL_REVIEW (alert created)             │
│  > 0.7 → AUTO_BLOCK (alert created)                  │
└──────────────┬───────────────────────────────────────┘
               │
               ▼
        Publish to Kafka:
        - fraud-check-response (to A2)
        - fraud.alert-events (if alert needed)
        - audit.events (every decision)
        Update state stores:
        - AccountProfile (rolling statistics)
        - VelocityWindow (increment counts)
        - Dedup cache (store result)
```

### 3.2 ScoringContext

The `ScoringContext` is the data structure that flows through the chain:

```
ScoringContext:
  // Input (immutable)
  transaction:              Transaction (id, accountId, creditorName, amount, timestamp, vopResult, ...)
  
  // Accumulated by layers (each layer writes its section)
  sanctionsResult:          SanctionsResult (decision, matchedEntity, listVersion, latencyMs)
  velocityResult:           VelocityResult (score, triggeredRules: List<TriggeredRule>, latencyMs)
  anomalyResult:            AnomalyResult (score, zAmount, zFrequency, unusualHour, newCreditor, latencyMs)
  mlResult:                 MlResult (score, modelVersion, latencyMs) | null if ML_DISABLED
  
  // Final (computed by aggregation)
  combinedScore:            double
  decision:                 Decision (AUTO_PASS, MANUAL_REVIEW, AUTO_BLOCK, SANCTIONS_HOLD)
  totalLatencyMs:           long
```

### 3.3 Kafka Streams Topology

```
Source: payment.instant-fraud-check-request (instant pod) OR payment.fraud-check-request (batch pod)
    │
    ▼
┌──────────────────────────────────────────────────────┐
│  ScoringProcessor                                    │
│                                                      │
│  State Stores:                                       │
│  - dedup-store (KeyValueStore<String, ScoringResult>)│
│  - velocity-60min (WindowStore)                      │
│  - velocity-24h (WindowStore)                        │
│  - account-profiles (KeyValueStore<String, Profile>) │
│                                                      │
│  Injects: ScoringPipeline (chain of ScoringLayers)   │
│           SanctionsIndex (AtomicReference              │
│             <SanctionsSnapshot>)                      │
│           ModelProvider (AtomicReference<Model>)      │
└──────────┬───────────────────────────────────────────┘
           │
           ├──► Sink: payment.fraud-check-response (instant or batch)
           ├──► Sink: fraud.alert-events (if MANUAL_REVIEW, AUTO_BLOCK, or SANCTIONS_HOLD)
           └──► Sink: audit.events (every decision)
```

Processing guarantee: `exactly_once_v2`. Consumer offsets, state store changes, and output records are committed atomically.

---

## 4. Sanctions Screening Architecture

### 4.1 Dual Screening Model (Decision D2a)

A5 implements two complementary sanctions screening modes:

**Mode 1 — Daily Batch Screening (EU IPR compliance):**
A scheduled job screens the PSP's entire customer base against the sanctions index. Flagged accounts are added to a `Set<String> blockedAccounts` (held in an `AtomicReference`, same pattern as the sanctions index). This satisfies EU Regulation 2024/886 (Instant Payments Regulation).

**Mode 2 — Per-Transaction Screening (beneficiary check):**
The sanctions layer in the scoring pipeline screens the beneficiary name using the three-tier pipeline. This catches sanctioned beneficiaries who are not the PSP's own customers.

**Fast-path:** Before running the full three-tier pipeline, the sanctions layer checks if the originator or beneficiary accountId is in `blockedAccounts`. If yes, instant SANCTIONS_HOLD — no fuzzy matching needed.

**EU IPR Article 5d Compliance — Explicit Screening Path:**
- **Originator screening (instant path):** Uses Mode 1 only — the `blockedAccounts` fast-path check (O(1) HashSet lookup, <0.01ms). The originator is NOT re-screened through the full fuzzy-match pipeline on the instant path. The daily batch customer screening (Mode 1) ensures the `blockedAccounts` set is current.
- **Beneficiary screening (instant path):** Uses Mode 2 — the full three-tier pipeline (Bloom filter → Aho-Corasick → Jaro-Winkler fuzzy match). This is a legitimate AML control for checking unknown counterparties, not prohibited by Article 5d.
- **Originator screening (batch path):** Uses both Mode 1 fast-path and Mode 2 full pipeline (batch has no time constraint).
This distinction is critical for Article 5d compliance: the originator on the instant path is never delayed by full fuzzy matching.

### 4.2 Three-Tier Screening Pipeline

```
Transaction
    │
    ├── Check blockedAccounts set ── HIT → SANCTIONS_HOLD (< 0.01ms)
    │
    ▼
┌────────────────────────────────────────────────────┐
│  Tier 1: Bloom Filter                              │
│  Google Guava BloomFilter                          │
│  ~15,000 entries (EU + UN lists)                   │
│  O(1) check. False positive rate: ~1%              │
│  "Definitely NOT in list" → CLEAR (< 1ms)          │
│  "Maybe in list" → proceed to Tier 2               │
└──────────────┬─────────────────────────────────────┘
               │ maybe
               ▼
┌────────────────────────────────────────────────────┐
│  Tier 2: Aho-Corasick Multi-Pattern Match          │
│  org.ahocorasick library                           │
│  Simultaneous search against all sanctioned names  │
│  Exact match → HIT (< 5ms)                         │
│  No exact match → proceed to Tier 3                │
└──────────────┬─────────────────────────────────────┘
               │ no exact match
               ▼
┌────────────────────────────────────────────────────┐
│  Tier 3: Fuzzy Match                               │
│  Apache Commons Text JaroWinklerSimilarity         │
│  + Soundex/Metaphone for phonetic matching         │
│  Handles: transliterations (Cyrillic→Latin,        │
│  Arabic→Latin), reversed name order, aliases,      │
│  diacritics, prefixes (van, de, al-)               │
│  Score ≥ 0.85 → POTENTIAL_HIT (< 30ms)             │
│  Score < 0.85 → CLEAR                              │
└────────────────────────────────────────────────────┘
```

### 4.3 Sanctions Index Lifecycle

```
                    ┌─────────────────────────────────────┐
                    │  SanctionsIndex                      │
                    │                                      │
                    │  AtomicReference<SanctionsSnapshot>   │
                    │       │                              │
                    │       ▼                              │
                    │  SanctionsSnapshot:                  │
                    │    bloomFilter: BloomFilter           │
                    │    ahoCorasick: AhoCorasickTrie       │
                    │    fuzzyIndex: FuzzyMatchIndex        │
                    │    listVersion: String                │
                    │    builtAt: Instant                   │
                    └─────────────────────────────────────┘
                              ▲
                              │ atomic swap
                    ┌─────────┴─────────────────────────────┐
                    │  Sanctions List Manager                │
                    │                                        │
                    │  1. Poll EU/UN endpoints (hourly)       │
                    │  2. Verify checksum (SAN-F08)           │
                    │  3. Validate size/format                │
                    │  4. Cache raw file to disk (atomic)     │
                    │  5. Build new snapshot in background    │
                    │  6. Swap AtomicReference                │
                    │                                        │
                    │  On startup: build from cached file     │
                    │  On list change: rebuild + swap          │
                    └────────────────────────────────────────┘
```

**Version traceability (D2b):** The `SanctionsSnapshot` carries a `listVersion` field (e.g., "EU-2026-04-11T08:00:00Z"). The sanctions layer reads `snapshot.listVersion()` and writes it into the `ScoringContext`. Every audit record includes this version — traceable to the specific list used.

**Crash recovery (D2c):** Downloaded list files are cached on disk (atomic write: temp file + rename). On startup, the index is rebuilt from the cache — no external network dependency. The system then immediately polls for updates.

**Startup readiness (D2d):** A5 does not consume fraud-check-requests until the sanctions index is built. Kubernetes readiness probe reflects this.

---

## 5. Crash Recovery Architecture

### 5.1 Two-Path Recovery Model (Decision D4)

A5 has two processing paths with different recovery models:

```
┌─────────────────────────────────────────────────────────────────┐
│  PATH 1: Scoring Pipeline (Kafka Streams)                       │
│                                                                 │
│  Recovery: Kafka Streams exactly_once_v2                        │
│  - Atomic commit: offsets + state stores + output records       │
│  - On crash: replay from last committed offset                  │
│  - Uncommitted state changes discarded                          │
│                                                                 │
│  Writes ONLY to Kafka (no PostgreSQL on hot path):              │
│  - fraud-check-response → A2                                    │
│  - fraud.alert-events → Alert Consumer                          │
│  - audit.events → Audit                                         │
│                                                                 │
│  PostgreSQL unavailability does NOT affect scoring               │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  PATH 2: Case Management (REST + PostgreSQL + Outbox)           │
│                                                                 │
│  Recovery: Transactional Outbox (D8)                            │
│  - Single DB transaction: alert update + outbox record          │
│  - Polling relay (100ms) publishes outbox to Kafka              │
│  - If relay crashes: next poll re-publishes (idempotent)        │
│  - Downstream consumers handle duplicates                       │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  Alert Consumer (bridges Path 1 → PostgreSQL)                   │
│                                                                 │
│  Reads: fraud.alert-events (Kafka)                              │
│  Writes: alerts table (PostgreSQL)                              │
│  Idempotent: INSERT ... ON CONFLICT (transaction_id) DO NOTHING │
│  On crash: Kafka replays; duplicates handled by constraint      │
└─────────────────────────────────────────────────────────────────┘
```

### 5.2 Crash Scenario Resolution

| Scenario | What Happens | Recovery Mechanism |
|----------|-------------|-------------------|
| Scoring processor crashes mid-scoring | Transaction partially processed | exactly_once_v2 replays from last committed offset; uncommitted state discarded |
| Scoring processor crashes after computing score, before Kafka commit | Score computed but not published | exactly_once_v2 treats this as uncommitted; transaction re-scored on replay |
| Alert Consumer crashes after consuming event, before PostgreSQL write | Alert event consumed but not persisted | Kafka replays from last committed offset; idempotent INSERT handles re-delivery |
| PostgreSQL unavailable during scoring | Cannot persist alerts | Scoring unaffected (Kafka-first); alert events accumulate in Kafka; Alert Consumer retries when PostgreSQL recovers |
| PostgreSQL unavailable during case management | Cannot resolve alerts | REST API returns 503; analyst retries; no data loss (transaction not committed) |
| Outbox relay crashes after Kafka publish, before marking as published | Outbox record published but not marked | Next poll re-publishes; downstream idempotent consumers handle duplicate |
| Sanctions index rebuild interrupted by crash | New snapshot partially built | AtomicReference still points to old snapshot; on restart, rebuild from cached file |
| ML model fails to load | No model available | ML_DISABLED mode; rules + anomaly only; weights redistributed (velocity=0.35, anomaly=0.35) |
| Full pod crash and restart | All in-memory state lost | Startup sequence: sanctions from disk cache, ML from filesystem, state stores from changelog; standby replicas provide near-instant state recovery |
| A2 times out waiting for A5 response | A5 slow or overloaded | A5 still completes scoring and publishes response (FRD-RES04). If late result is SANCTIONS_HIT, A5 publishes a LATE_SANCTIONS_HIT alert for immediate analyst review |
| Graceful shutdown (SIGTERM) | In-flight scoring operations | Drain Kafka consumer buffers, complete in-flight scoring. If scoring cannot complete before shutdown, publish response with `decision: TIMEOUT` so A2 can handle it (FRD-RES03) |

### 5.3 State Store Failover

Production configuration: `num.standby.replicas=1`. Standby replicas pre-replicate state stores to another instance. On failover, the standby takes over without full changelog replay — near-instant recovery.

### 5.4 Error Classification and Dead Letter Queue (FRD-RES02)

Every Kafka consumer in A5 classifies errors and routes unprocessable messages to DLQ after 3 retries:

| Error Type | Action | Rationale |
|-----------|--------|-----------|
| Avro schema validation failure | DLQ immediately (no retry) | Structurally invalid — retrying won't help |
| HMAC verification failure | DLQ immediately + WARN alert | Potential tampering — security event |
| Business logic exception (e.g., unknown accountId format) | DLQ after 3 retries | Might be a transient data issue |
| Transient Kafka error (broker unavailable, timeout) | Retry with backoff (indefinite) | Infrastructure issue — will resolve |
| Deserialization error (corrupt message bytes) | DLQ immediately | Unrecoverable |

DLQ topic naming: `{source-topic}.dlq` (e.g., `payment.fraud-check-request.dlq`). Each DLQ message includes the original payload, error details, retry count, and timestamp. DLQ depth monitored via `fraud.dlq.depth` metric.

### 5.5 Partial Response on Scoring Timeout

When the sanctions layer completes but fraud scoring layers (velocity, anomaly, ML) exceed the latency budget, A5 publishes a partial response: `{sanctionsResult: CLEAR, decision: TIMEOUT, fraudScore: null, scoringLatencyMs: <elapsed>}`. This allows A2 to auto-pass fraud (payment proceeds with post-settlement review flag) while confirming sanctions cleared. Per INTERFACE_CONTRACTS.md Section 2.5, A2 treats `decision: TIMEOUT` with `sanctionsResult: CLEAR` as fraud auto-pass. If sanctions have NOT completed when the overall timeout fires, A5 publishes `{sanctionsResult: UNAVAILABLE, decision: TIMEOUT}` — A2 rejects the payment.

### 5.6 Graceful Shutdown (FRD-RES03)

On SIGTERM:
1. Stop accepting new Kafka messages (pause consumers)
2. Complete all in-flight scoring operations (drain buffers)
3. If any scoring cannot complete within the shutdown grace period, publish a response with `decision: TIMEOUT` so A2 can handle it (reject or queue for re-scoring)
4. Commit final offsets
5. Close state stores and Kafka producers
6. Exit

### 5.7 Late-Arriving Responses (FRD-RES04)

If A5 completes scoring after A2 has already timed out and auto-passed the payment:
- A5 still publishes the response (it will be logged by A2 but not acted upon for the already-passed payment)
- If the late response is `SANCTIONS_HIT`, A5 additionally publishes a `LATE_SANCTIONS_HIT` alert to `fraud.alert-events` for immediate analyst review — the payment may have already settled and may require reversal

---

## 6. Security Architecture

### 6.1 Defense-in-Depth (Decision D3)

A5 implements layered defenses on every attack surface:

```
┌──────────────────────────────────────────────────────────────┐
│  INFRASTRUCTURE LAYER                                         │
│  Kafka ACLs: only A2 can produce to fraud-check-request      │
│  Filesystem encryption: RocksDB state stores (production)     │
│  Network policies: A5 pods reachable only from internal zone  │
└──────────────────────────────────────┬───────────────────────┘
                                       │
┌──────────────────────────────────────▼───────────────────────┐
│  STRUCTURAL LAYER                                             │
│  Avro Schema Registry: validates message structure            │
│  Platform Starter: deny-all security default                  │
│  Parameterized queries (JPA): SQL injection prevented         │
│  Sanctions screening: structurally mandatory (SAN-F12)        │
│  PII minimization: enforced by data flow (D11)                │
└──────────────────────────────────────┬───────────────────────┘
                                       │
┌──────────────────────────────────────▼───────────────────────┐
│  APPLICATION LAYER                                            │
│  HMAC verification on Kafka messages (KM-06)                  │
│  OAuth2 Resource Server (fraud:read, fraud:manage scopes)     │
│  Bean Validation on REST request bodies                       │
│  ML model SHA-256 checksum at load time                       │
│  Sanctions list checksum + size/format validation             │
│  Rate limiting on Case Management API                         │
└──────────────────────────────────────────────────────────────┘
```

### 6.2 PII Data Flow (Decision D11)

PII minimization is enforced by architecture, not policy:

```
fraud-check-request (contains creditorName for sanctions)
    │
    ▼
┌─ Sanctions Layer ──────────────────────────────────────────┐
│  creditorName used TRANSIENTLY for name matching           │
│  Result recorded: CLEAR / HIT / POTENTIAL_HIT              │
│  Name NOT persisted anywhere                               │
└────────────────────────────────────────────────────────────┘
    │
    ▼  (name discarded from context)
┌─ Velocity + Anomaly + ML ─────────────────────────────────┐
│  Operate on: accountId (opaque), amount, timestamp         │
│  Never see creditorName                                    │
└────────────────────────────────────────────────────────────┘
    │
    ▼
┌─ Alert Event / Audit Event ────────────────────────────────┐
│  Contains: accountId, transactionId, scores, rules         │
│  Does NOT contain: creditorName, account holder name       │
└────────────────────────────────────────────────────────────┘
    │
    ▼
┌─ PostgreSQL (alerts table) ────────────────────────────────┐
│  Stores: opaque IDs, scores, rules, timestamps             │
│  Does NOT store: names                                     │
└────────────────────────────────────────────────────────────┘
    │
    ▼ (analyst opens alert)
┌─ Case Management API ──────────────────────────────────────┐
│  Queries A1 Account Ledger for account holder name         │
│  PII displayed to analyst at query time only               │
│  PII never cached in A5                                    │
└────────────────────────────────────────────────────────────┘
```

**State stores contain no names:**
- `AccountProfile.typicalCreditors`: IBANs (opaque identifiers)
- Velocity windows: account IDs, amounts, counts
- Dedup cache: transactionId → ScoringResult (no names in result)

### Threat Model Traceability (Master A.3a)

| Interface | Threat | Control | Reference |
|-----------|--------|---------|-----------|
| Kafka inbound (fraud-check-request) | Spoofing | HMAC-SHA256 verifies A2 origin | KM-06 |
| Kafka inbound | Tampering | HMAC + Avro schema validation | KM-06 |
| Scoring pipeline | Information Disclosure | PII (creditor name) transient in memory only, never persisted | Section 4 |
| Scoring pipeline | Tampering | Sanctions list version recorded per decision for audit | D2 |
| Case Management API | Spoofing | OAuth2 with fraud:read/fraud:manage scopes | Section 11.4 |
| Case Management API | Elevation of Privilege | Scope-based authorization; internal zone NetworkPolicy | Section 11.4 |
| Case Management API | Repudiation | All analyst actions logged to audit.events | Section 7.2 |
| Sanctions screening | Denial of Service | Separate JVM instances for instant/batch; cannot affect each other | C.3a |
| Account freeze commands | Tampering | HMAC signing on outbound Kafka messages | KM-06 |

### Security Requirements Traceability

| Requirement | Description | Architectural Element |
|------------|-------------|----------------------|
| FRD-S01 | Sanctions screening mandatory | Kafka Streams topology has no bypass path; timeout = REJECT |
| FRD-S02 | Sanctions list version audited | Every screening decision includes `sanctionsListVersion` |
| FRD-S03 | PII minimization | Creditor name transient in memory only, never persisted in state stores |
| FRD-S04 | Fraud case audit trail | All analyst actions logged to `audit.events` |
| FRD-S05 | Case management authorization | OAuth2 scopes: `fraud:read`, `fraud:manage` |
| FRD-S06 | Scoring pipeline integrity | Exactly-once semantics, deterministic scoring, idempotent dedup |
| SAN-S01 | Sanctions list update integrity | Signed list files, version tracking, atomic swap via AtomicReference |

---

## 7. Kafka Topics and Data Flow

### 7.1 Topic Overview

| Topic | Direction | Purpose | Key | Value |
|-------|-----------|---------|-----|-------|
| `payment.fraud-check-request` | Consume (batch) | Batch payment transactions to score | transactionId | FraudCheckRequest (Avro) |
| `payment.instant-fraud-check-request` | Consume (instant) | Instant payment transactions to score | transactionId | FraudCheckRequest (Avro) |
| `payment.fraud-check-response` | Produce | Scoring results for batch | transactionId | FraudCheckResponse (Avro) |
| `payment.instant-fraud-check-response` | Produce | Scoring results for instant | transactionId | FraudCheckResponse (Avro) |
| `fraud.alert-events` | Internal (produce + consume) | Alert creation events | transactionId | AlertEvent (Avro) |
| `audit.events` | Produce | Immutable audit trail | transactionId | AuditEvent (Avro) |
| `account.freeze-commands` | Produce (via outbox) | Freeze account on CONFIRMED_FRAUD | accountId | FreezeCommand (Avro) |
| `sanctions.list-updates` | Consume | Notification that sanctions lists have been updated (triggers immediate re-download and index rebuild, complementing the hourly poll) | — | ListUpdateNotification (Avro) |
| `fraud.scoring.dlq` | Produce | Dead letter queue for unprocessable fraud-check-request messages (after 3 retries per FRD-RES02) | transactionId | Original message + error details |

**Note on `sanctions.list-updates`:** This topic complements the hourly HTTP polling mechanism. When an external system detects that the EU/UN sanctions lists have changed, it publishes a notification to this topic. A5's Sanctions List Manager consumes the notification and triggers an immediate download and index rebuild — without waiting for the next hourly poll. Both mechanisms coexist: polling provides the baseline, the topic provides urgency.

### 7.2 Kafka Message Schemas

**FraudCheckRequest** (consumed from A2):
```
transactionId:      UUID
debtorAccountId:    String (opaque account identifier)
creditorAccountId:  String (opaque account identifier)
creditorName:       String (needed for sanctions screening — transient PII, see D11)
creditorCountry:    String (ISO 3166-1 alpha-2)
amount:             BigDecimal
currency:           String (ISO 4217)
timestamp:          Instant (UTC)
vopResult:          Enum (MATCH, CLOSE_MATCH, NO_MATCH, NOT_FOUND)
vopOverride:        Boolean
```

**FraudCheckResponse** (produced to A2):
```
transactionId:          UUID
sanctionsResult:        Enum (CLEAR, HIT, POTENTIAL_HIT)
sanctionsMatchedEntry:  String (nullable — which list entry matched)
sanctionsListVersion:   String
fraudScore:             Double (0.0-1.0)
decision:               Enum (AUTO_PASS, MANUAL_REVIEW, AUTO_BLOCK, SANCTIONS_HOLD)
rulesTriggered:         List<{ruleId, ruleName, weight, explanation}>
anomalyScore:           Double (0.0-1.0)
anomalyDetail:          String (summary: "z_amount=X, z_frequency=Y, unusual_hour=Z, new_creditor=W")
mlScore:                Double (nullable, 0.0-1.0)
mlModelVersion:         String (nullable)
vopResult:              Enum (pass-through from request)
vopOverride:            Boolean (pass-through from request)
scoringLatencyMs:       Long
scoringPath:            Enum (BATCH, INSTANT)
timestamp:              Instant (UTC)
```

**AlertEvent** (internal, fraud.alert-events):
```
transactionId:          UUID
alertType:              Enum (FRAUD, SANCTIONS)
accountId:              String
transactionAmount:      BigDecimal
currency:               String
fraudScore:             Double
decision:               Enum (MANUAL_REVIEW, AUTO_BLOCK, SANCTIONS_HOLD)
rulesTriggered:         JSONB
sanctionsResult:        Enum (nullable)
sanctionsMatchedEntry:  String (nullable)
sanctionsListVersion:   String (nullable)
vopResult:              Enum (nullable)
vopOverride:            Boolean
mlModelVersion:         String (nullable)
mlScore:                Double (nullable)
scoringLatencyMs:       Long
scoringPath:            Enum (BATCH, INSTANT)
timestamp:              Instant (UTC)
```

**AuditEvent** (audit.events — every scoring decision, not just alerts):
```
transactionId:          UUID
decision:               Enum (AUTO_PASS, MANUAL_REVIEW, AUTO_BLOCK, SANCTIONS_HOLD)
fraudScore:             Double
sanctionsResult:        Enum
sanctionsListVersion:   String
rulesTriggered:         List<{ruleId, weight, explanation}>
anomalyScore:           Double
mlScore:                Double (nullable)
mlModelVersion:         String (nullable)
vopResult:              Enum
vopOverride:            Boolean
scoringLatencyMs:       Long
scoringPath:            Enum (BATCH, INSTANT)
processingInstanceId:   String (pod identifier)
timestamp:              Instant (UTC)
```

Audit events published to `audit.events` use the Platform Starter's tamper-evident hash chain mechanism (per-instance SHA-256 chain with crash recovery from Kafka). See Platform Starter ARCHITECTURE_DESIGN.md.

### 7.3 End-to-End Data Flow (Instant Payment)

```
A2 publishes FraudCheckRequest to payment.instant-fraud-check-request
    │
    ▼
a5-fraud-instant consumes (HMAC verified, schema validated)
    │
    ▼
ScoringProcessor:
    1. Dedup check (state store, 1ms) → new transaction
    2. Sanctions screening (12ms) → CLEAR
    3. Velocity rules (35ms) → VEL-02 triggered (0.4)
    4. Anomaly detection (28ms) → anomaly_score = 0.7
    5. ML scoring (8ms) → ml_score = 0.6
    6. Aggregation (1ms) → combined = 0.52 → MANUAL_REVIEW
    │
    ├──► Publish FraudCheckResponse to payment.instant-fraud-check-response
    │    {decision: MANUAL_REVIEW, score: 0.52, scoringLatencyMs: 88}
    │
    ├──► Publish AlertEvent to fraud.alert-events
    │    {transactionId, accountId, score: 0.52, rules: [VEL-02], ...}
    │
    └──► Publish AuditEvent to audit.events
         {transactionId, decision, score, sanctionsListVersion, ...}

    Alert Consumer reads fraud.alert-events
    │
    └──► INSERT INTO fraud_alerts (...) ON CONFLICT DO NOTHING
```

---

## 8. Component Architecture

### 8.1 Internal Component Structure

```
a5-fraud-engine/
├── scoring-pipeline/
│   ├── ScoringLayer (interface)
│   ├── ScoringPipeline (chain orchestrator)
│   ├── ScoringContext (data carrier)
│   ├── sanctions/
│   │   ├── SanctionsScreeningLayer (implements ScoringLayer)
│   │   ├── SanctionsIndex (AtomicReference<SanctionsSnapshot>)
│   │   ├── SanctionsSnapshot (Bloom + AhoCorasick + Fuzzy + version)
│   │   ├── SanctionsListManager (polling, caching, rebuilding)
│   │   └── BlockedAccountsRegistry (AtomicReference<Set<String>>)
│   ├── velocity/
│   │   ├── VelocityRulesLayer (implements ScoringLayer)
│   │   ├── VelocityRule (YAML-configured rule definition)
│   │   └── VelocityRuleEvaluator (evaluates one rule against state)
│   ├── anomaly/
│   │   ├── AnomalyDetectionLayer (implements ScoringLayer)
│   │   └── AccountProfileStore (Kafka Streams state store access)
│   ├── ml/
│   │   ├── MlScoringLayer (implements ScoringLayer)
│   │   ├── ModelProvider (interface)
│   │   ├── LocalFileModelProvider (loads from filesystem)
│   │   └── FeatureVectorBuilder (constructs ML input)
│   └── aggregation/
│       ├── ScoreAggregator (weighted combination + decision)
│       └── DecisionThresholds (configurable thresholds)
│
├── kafka-topology/
│   ├── ScoringProcessor (Kafka Streams processor, invokes ScoringPipeline)
│   ├── TopologyBuilder (wires topology: source → processor → sinks)
│   └── StateStoreConfig (registers dedup, velocity, profile stores)
│
├── alert-consumer/
│   ├── AlertConsumer (Kafka consumer → PostgreSQL)
│   └── AlertRepository (JPA, idempotent INSERT)
│
├── case-management/
│   ├── AlertController (REST endpoints)
│   ├── AlertService (business logic)
│   ├── OutboxRecord (entity)
│   ├── OutboxRelay (Spring @Scheduled, 100ms polling)
│   └── SecurityConfig (OAuth2, deny-all default)
│
├── audit/
│   ├── AuditEventPublisher (publishes to audit.events)
│   └── AuditEventSchema (defines required fields)
│
└── config/
    ├── application.yml (velocity rules, thresholds, Kafka config)
    ├── KafkaStreamsConfig (exactly_once_v2, state store config)
    └── ObservabilityConfig (Micrometer metrics, OpenTelemetry)
```

### 8.2 Key Internal Interfaces

```
// Core pipeline interface (D1)
interface ScoringLayer {
    ScoringContext score(ScoringContext ctx);
    boolean shouldShortCircuit(ScoringContext ctx);
}

// ML model abstraction (D7)
interface ModelProvider {
    Optional<Model> getModel();          // empty if ML_DISABLED
    String getModelVersion();
    boolean isAvailable();
}

// Sanctions screening abstraction
// SanctionsIndex implements this interface; holds the AtomicReference<SanctionsSnapshot>
// and delegates to the active snapshot's Bloom/AhoCorasick/Fuzzy structures
interface SanctionsScreener {
    SanctionsResult screen(String name, String iban, String country);
    String getListVersion();
    boolean isReady();  // false during startup until index is built
}
```

---

## 9. Velocity Rules Configuration (Decision D10)

Rules are defined in YAML, loaded via Spring `@ConfigurationProperties`:

```yaml
fraud:
  velocity-rules:
    - id: VEL-01
      name: "High frequency"
      description: "More than {threshold} transactions from same account in {windowMinutes} minutes"
      windowMinutes: 60
      threshold: 10
      severity: 0.2
      enabled: true
    - id: VEL-02
      name: "Large sudden amount"
      description: "Single transaction > {threshold}x account average"
      windowMinutes: 0  # single transaction, no window
      threshold: 20
      severity: 0.4
      enabled: true
    - id: VEL-03
      name: "Rapid drain"
      description: "Total outflow > {threshold}% of balance in {windowMinutes} minutes"
      windowMinutes: 1440  # 24 hours
      threshold: 80
      severity: 0.4
      enabled: true
    # ... VEL-04 through VEL-10
```

All rules are evaluated for every transaction (no short-circuit). All triggered rules are recorded in the ScoringContext for complete alert explanations and audit trail (P3).

---

## 10. Observability Architecture (Decision D9)

### 10.1 Distributed Tracing

OpenTelemetry with Kafka client instrumentation. Trace context propagated in Kafka headers automatically. Each scoring layer creates a child span — operators see per-layer timing in the trace.

```
Trace: txn-uuid-001
├── Span: a2.fraud-check.publish (A2)
├── Span: a5.scoring.consume (1ms)
├── Span: a5.scoring.dedup-check (1ms)
├── Span: a5.scoring.sanctions (12ms)
├── Span: a5.scoring.velocity (35ms)
├── Span: a5.scoring.anomaly (28ms)
├── Span: a5.scoring.ml (8ms)
├── Span: a5.scoring.aggregation (1ms)
└── Span: a5.scoring.publish-response (2ms)
    Total: 88ms
```

### 10.2 Metrics and Alerting

Nine key Prometheus metrics (via Micrometer):

| Metric | Type | Alert Threshold |
|--------|------|----------------|
| `fraud.scoring.latency{path=instant}` | Histogram | p95 > 400ms WARN, > 480ms CRITICAL |
| `fraud.ml.available` | Gauge | = 0 → WARN |
| `fraud.dlq.depth` | Gauge | > 0 → WARN |
| `fraud.consumer.lag{topic=instant}` | Gauge | > 100 → CRITICAL |
| `fraud.sanctions.hit.rate` | Gauge | Spike detection (anomaly-based) |
| `fraud.decision` | Counter | Volume shift detection |
| `fraud.false.positive.rate` | Gauge | Trend monitoring |
| `fraud.sanctions.index.version` | Gauge | Informational |
| `fraud.state.store.size` | Gauge | Capacity threshold |

---

## 11. Data Model

### 11.1 PostgreSQL Schema (Case Management)

```sql
-- Alerts (persisted by Alert Consumer from fraud.alert-events)
CREATE TABLE fraud_alerts (
    alert_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id      UUID NOT NULL UNIQUE,  -- idempotent INSERT key
    alert_type          VARCHAR(20) NOT NULL,  -- FRAUD, SANCTIONS
    account_id          VARCHAR(64) NOT NULL,
    transaction_amount  NUMERIC(19,4) NOT NULL,
    currency            VARCHAR(3) NOT NULL,
    fraud_score         DOUBLE PRECISION NOT NULL,
    decision            VARCHAR(30) NOT NULL,
    rules_triggered     JSONB,
    sanctions_result    VARCHAR(20),
    sanctions_matched_entry VARCHAR(500),
    sanctions_list_version VARCHAR(100),
    vop_result          VARCHAR(20),
    vop_override        BOOLEAN,
    ml_model_version    VARCHAR(50),
    ml_score            DOUBLE PRECISION,
    scoring_latency_ms  BIGINT NOT NULL,
    scoring_path        VARCHAR(10) NOT NULL,  -- BATCH, INSTANT
    priority            VARCHAR(10) NOT NULL,  -- CRITICAL, HIGH, MEDIUM, LOW
    assigned_to         VARCHAR(100),
    status              VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at         TIMESTAMPTZ,
    resolved_by         VARCHAR(100),
    resolution_notes    TEXT
);

CREATE INDEX idx_alerts_status ON fraud_alerts(status);
CREATE INDEX idx_alerts_score ON fraud_alerts(fraud_score DESC);
CREATE INDEX idx_alerts_account ON fraud_alerts(account_id);
CREATE INDEX idx_alerts_created ON fraud_alerts(created_at);
CREATE INDEX idx_alerts_priority ON fraud_alerts(priority) WHERE status = 'OPEN';

-- Priority derivation (set by Alert Consumer on INSERT):
--   SANCTIONS alert → CRITICAL
--   fraud_score > 0.7 (AUTO_BLOCK) → HIGH
--   fraud_score 0.5-0.7 (MANUAL_REVIEW) → MEDIUM

-- Transactional Outbox (D8)
CREATE TABLE outbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      VARCHAR(50) NOT NULL,   -- ACCOUNT_FREEZE, AUDIT_EVENT
    aggregate_id    VARCHAR(100) NOT NULL,  -- accountId or transactionId
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMPTZ           -- NULL until relay publishes
);

CREATE INDEX idx_outbox_unpublished ON outbox(created_at) WHERE published_at IS NULL;
```

### 11.2 Kafka Streams State Stores

| Store | Type | Key | Value | Purpose |
|-------|------|-----|-------|---------|
| `dedup-store` | KeyValueStore | transactionId (String) | ScoringResult | Cross-retry dedup (D6), 1-hour TTL |
| `velocity-60min` | WindowStore | accountId (String) | VelocityAggregate (count, totalAmount) | 60-minute tumbling window |
| `velocity-24h` | WindowStore | accountId (String) | VelocityAggregate (count, totalAmount) | 24-hour tumbling window |
| `account-profiles` | KeyValueStore | accountId (String) | AccountProfile | Rolling 90-day statistics |

### 11.3 State Store Sizing (Decision D12)

| Store | Size Estimate | Retention |
|-------|--------------|-----------|
| `dedup-store` | ~3.6M entries at 1,000 TPS * 1h TTL * ~200 bytes = ~720MB peak (RocksDB on disk, not all in memory) | 1-hour TTL, purged by punctuation |
| `velocity-60min` | Per-account windows, ~100K accounts * ~100 bytes = ~10MB active | Expired windows purged by Kafka Streams |
| `velocity-24h` | Per-account windows, ~100K accounts * ~100 bytes = ~10MB active | Expired windows purged by Kafka Streams |
| `account-profiles` | ~100K accounts * ~200 bytes = ~20MB | Oldest profiles evicted by `lastUpdated` when capacity exceeded |

**Total estimated state:** ~760MB on disk (dominated by the dedup store). RocksDB block cache keeps hot data in memory; cold data on SSD.

**RocksDB configuration:** Start with Kafka Streams defaults. Monitor `fraud.state.store.size` and RocksDB block cache hit rate. Tune only if monitoring shows latency impact. SSD storage required for state store directories.

### 11.4 Case Management REST Endpoints

All endpoints require OAuth2 token. Scopes: `fraud:read` for read-only, `fraud:manage` for state-changing operations.

| Method | Path | Scope | Purpose |
|--------|------|-------|---------|
| GET | `/api/v1/fraud/alerts` | fraud:read | List alerts (paginated, filterable by status, type, score range, date range, accountId) |
| GET | `/api/v1/fraud/alerts/{id}` | fraud:read | Alert detail (transaction, score breakdown, rules, account profile via A1 lookup) |
| PUT | `/api/v1/fraud/alerts/{id}/resolve` | fraud:manage | Resolve alert: CONFIRMED_FRAUD, DISMISSED, CONFIRMED_SANCTION, or FALSE_POSITIVE |
| GET | `/api/v1/fraud/dashboard` | fraud:read | Dashboard metrics (open alerts, resolved today, avg resolution time, false positive rate) |
| PUT | `/api/v1/fraud/alerts/{id}/assign` | fraud:manage | Assign alert to analyst (CASE-F07) |
| POST | `/api/v1/fraud/alerts/bulk-resolve` | fraud:manage | Bulk resolve related alerts (CASE-F09) |

Detailed request/response schemas are defined in UC02 (use_cases/UC02_case_management.md).

All REST error responses follow RFC 9457 (Problem Details for HTTP APIs), auto-configured by the Platform Starter's `PaymentExceptionHandler`.

**Zone Model Note:** The Case Management API uses OAuth2 with scopes (`fraud:read`, `fraud:manage`) for user-level authorization despite A5 being in the internal zone (master decision A.1a). This is justified because the API serves human fraud analysts who require user-level access control — not service-to-service authentication. Network policies restrict the API to internal zone traffic only. mTLS is used for service-to-service calls (e.g., A5 querying A1 for PII enrichment), consistent with the zone model.

**Anti-Tipping-Off Controls (AML Directive Article 39):** The Case Management API must not expose investigation status to the subject of the investigation. Controls: (1) alerts and case details are accessible only via OAuth2 scopes `fraud:read`/`fraud:manage` — not via any customer-facing API. (2) Account freeze notifications to the account holder must not reference the sanctions/fraud investigation as the reason — use a generic 'regulatory hold' message. (3) All analyst access is audited to `audit.events`. (4) No webhook or status update to A3/A4 reveals the investigation — only the account freeze command to A1, which A1 implements without disclosing the reason to external queries.

---

## 12. Testing Architecture (Decision D14)

### 12.1 Four-Level Pyramid

```
                    ┌──────────┐
                    │ ArchUnit │  Level 4: Architecture rules
                    │ (~10)    │  Package dependencies, PII isolation
                    ├──────────┤
                  ┌─┤ Testcont │  Level 3: Integration (Kafka + PostgreSQL)
                  │ │ (~15)    │  End-to-end: request → response → alert
                  ├─┼──────────┤
                ┌─┤ │ Topology │  Level 2: Kafka Streams TopologyTestDriver
                │ │ │ (~20)    │  Pipeline wiring, state store updates
                ├─┼─┼──────────┤
              ┌─┤ │ │   Unit   │  Level 1: Per-layer, no infrastructure
              │ │ │ │  (~100)  │  Sanctions, velocity, anomaly, ML, aggregation
              └─┴─┴─┴──────────┘
```

**Sanctions testing:** Synthetic sanctions list with fictional names:
- "TESTPERSON, Exact Match" — triggers Aho-Corasick exact match
- "Тестов Сергей" (Cyrillic) — triggers transliteration + fuzzy match
- "van der TEST, Jan" — triggers prefix handling + reversed name order
- "CLEARNAME, Definitely" — guaranteed Bloom filter negative

**Velocity testing:** Deterministic `java.time.Clock` injected. Tests advance time programmatically.

**ML testing:** Frozen model with known input→output mappings. Model loading, checksum verification, and fallback behavior tested.

**Contract tests (Pact):** A5 runs Pact provider verification for the fraud-check-response contract defined by A2. This ensures A5's response messages match A2's expectations (field names, types, enum values). Provider verification runs in CI on every PR that modifies the response schema.

---

## 13. Identified Risks and Mitigations

| Risk | Severity | Mitigation |
|------|----------|------------|
| Sanctions list rebuild > 5 min (NFR-06) | Medium | Benchmark with full EU list (~15K entries); O(n) build time; disk cache avoids download delay |
| Kafka Streams rebalancing during instant scoring | Medium | Cooperative Sticky Assignor; `num.standby.replicas=1`; separate pods for batch/instant |
| Changelog propagation delay between batch and instant instances | Low | Milliseconds delay; insignificant for 60-min/24-hour velocity windows |
| ML model latency spike (cold start, JIT) | Low | Warm-up inference on synthetic data during startup before readiness probe |
| False positive rate for sanctions fuzzy matching (~73%) | Expected | By design — 100% recall is the goal. Case management handles false positives |
| State store disk I/O affecting latency | Low | SSD required; monitor p99; standby replicas on separate nodes |

---

## 14. Future Extensions (Documented, Not Built)

| Extension | Origin | Effort | Trigger to Build |
|-----------|--------|--------|-----------------|
| ML model hot-reload via AtomicReference swap | D7, ING Bank pattern | Medium | Model changes become frequent (> monthly) |
| Champion/challenger model evaluation | D7, Danske Bank | High | Multiple competing models need evaluation |
| Kafka control topic for dynamic rule updates | D10, Flink reference | Medium | Rule set grows > 20 or rules change frequently |
| Graph-based network analysis (money laundering) | DNB gap analysis | Very High | Compliance requires network-level AML detection |
| Cryptographic chaining of audit events | D13 | Medium | Threat model includes compromised Kafka administrator |
| LLM-enhanced sanctions screening (cascade) | Fed Reserve FEDS 2025-092 | High | False positive rate becomes operationally unsustainable |
| Debezium CDC for outbox relay | D8 | Low | Case management volume increases significantly |
| S3 model provider | D7 | Low | Need to decouple model deployment from application deployment |

---

## 15. Cross-References

| Document | Purpose |
|----------|---------|
| [requirements.md](../requirements/requirements.md) | Functional and non-functional requirements |
| [UC01_transaction_scoring.md](../use_cases/UC01_transaction_scoring.md) | Scoring pipeline use cases and scenarios |
| [UC02_case_management.md](../use_cases/UC02_case_management.md) | Case management use cases and API design |
| [ARCHITECTURE_CHALLENGES.md](ARCHITECTURE_CHALLENGES.md) | Challenges identified and decisions recorded |
| [ARCHITECTURE_DECISIONS.md](ARCHITECTURE_DECISIONS.md) | Decision rationale with options and tradeoffs |
| [architecture_draft.md](architecture_draft.md) | Initial draft (superseded by this document) |
| Master [NAVIGATION.md](../../payment_processing/NAVIGATION.md) | Cross-references to other subsystems |
| Master [ARCHITECTURE_DECISIONS.md](../../payment_processing/architecture/ARCHITECTURE_DECISIONS.md) | System-wide architectural decisions |

*All previously identified divergences between this document and requirements/use cases have been resolved. Documents are aligned as of 2026-04-11.*
