# Architectural Decisions — Options, Tradeoffs, and Recommendations

*Based on open challenges identified in ARCHITECTURE_CHALLENGES.md*
*Date: 2026-04-11*

---

## How to Read This Document

For each open architectural challenge, this document presents:
- **Options** — the realistic alternatives
- **Pros / Cons** — for each option, in the context of our system
- **Recommendation** — a specific choice with rationale

Recommendations are proposals for discussion, not final decisions. Where the recommendation depends on information we do not yet have, we note the dependency.

Cross-references use the challenge IDs from ARCHITECTURE_CHALLENGES.md (e.g., A.1, B.2).

---

## A.1 — Trust Boundaries and Defense-in-Depth Layers

### Decision A.1a: Zone Model

**Option 1: Three-zone model (External / DMZ / Internal)**

```
EXTERNAL ZONE          DMZ ZONE                    INTERNAL ZONE
(untrusted)            (partially trusted)         (trusted)
                       ┌─────────────────┐
TPPs ──────────────────┤  A4 PSD2 API    │
                       │  Gateway        ├─────── A2 Clearing Engine
Merchants ─────────────┤  A3 R2P API     │         A5 Fraud/Sanctions
                       │  (external      ├─────── A1 Account Ledger
Other PSPs ────────────┤   endpoints)    │
                       │  A6 VoP         │         Kafka (infrastructure)
                       │  (responder)    │         PostgreSQL (per service)
                       └─────────────────┘
```

- DMZ services handle authentication, input validation, rate limiting
- Internal services trust messages from DMZ services (validated upstream)
- Infrastructure (Kafka, PostgreSQL) accessible only from Internal zone

Pros:
- Clear security responsibility — validation happens once at the boundary
- Internal services are simpler (no OAuth2 token validation, no rate limiting)
- Mirrors how banks actually deploy (DMZ proxies, internal backend)

Cons:
- If a DMZ service is compromised, all internal services are exposed
- Internal services cannot distinguish "message from A2" from "message from a compromised A3"

**Option 2: Zero-trust model (every service validates everything)**

- Every service validates authentication on every request (even internal)
- Kafka messages carry signed tokens; consumers verify signatures
- No distinction between zones — every boundary is treated as untrusted

Pros:
- Defense in depth — compromise of one service does not give access to others
- Aligned with modern "zero trust" security guidance (NIST SP 800-207)

Cons:
- Every service carries the complexity of authentication and authorization
- Latency overhead on every internal call (token validation, signature verification)
- Higher development effort across all services

**Option 3: Three-zone model with HMAC verification on internal messages (hybrid)**

- DMZ services handle external authentication (OAuth2, API keys, mTLS)
- Internal services do NOT validate OAuth2 tokens (no external tokens flow internally)
- But Kafka messages carry HMAC signatures (KM-06, already required) — consumers verify that the message came from an authorized producer
- Internal REST calls use mTLS with service-specific certificates

Pros:
- DMZ handles the expensive external validation
- Internal services have lightweight but meaningful authentication (HMAC, mTLS) — not blind trust
- Compromise of one service does not allow arbitrary message injection into Kafka (HMAC key is per-producer)
- Aligns with KM-06 which is already a requirement
- Practical level of security without zero-trust overhead

Cons:
- HMAC key management adds operational complexity (key distribution, rotation)
- Does not protect against a compromised service using its own valid HMAC key to send fraudulent messages (but this is an insider threat that even zero-trust cannot fully prevent without multi-party authorization)

**Recommendation: Option 3 — Three-zone with HMAC and mTLS internally.**

Rationale: Option 1 is insufficient for a financial system (no internal verification at all). Option 2 is overkill for a portfolio project with 7 services — the complexity would dominate development. Option 3 strikes the right balance: meaningful internal security (HMAC on Kafka, mTLS on REST) without full OAuth2 token propagation through every hop. It also builds on KM-06 which is already required.

### Decision A.1b: Authenticated-by-Default in the Platform Starter

**Option 1: Spring Security "deny all" default — services opt-in to permit specific endpoints**

The Starter configures Spring Security with `.anyRequest().authenticated()` as the default. Each service declares its public endpoints explicitly (e.g., health checks, Prometheus metrics).

Pros:
- A forgotten endpoint is inaccessible, not exposed — safe default
- Mirrors Spring Security's own recommendation
- Forces developers to think about each endpoint's security posture

Cons:
- Slight friction when adding new endpoints (must add security configuration)

**Option 2: Services configure their own security — Starter provides utilities only**

The Starter provides HMAC verification utilities, audit logging, etc., but does not configure Spring Security's filter chain.

Pros:
- Maximum flexibility for each service
- No risk of the Starter's defaults conflicting with service-specific security needs

Cons:
- A service can accidentally leave endpoints unauthenticated
- Inconsistency across services — each implements security differently
- Violates the "security by construction" principle

**Recommendation: Option 1 — Deny-all default in the Starter.**

Rationale: This is the "fortress" principle from the brainstorming. An accidentally exposed endpoint in a payment system is far worse than the friction of adding an explicit permit. The Starter should pre-permit `/actuator/health`, `/actuator/readiness`, `/actuator/prometheus` (standard operational endpoints) and require authentication for everything else. Services override this via standard Spring Security configuration when they need to.

---

## A.2 — Secrets, Keys, and Cryptographic Material Lifecycle

### Decision A.2a: Key Management Topology

**Option 1: Centralized KMS — all services contact a central key management service**

A dedicated key management service (HashiCorp Vault, or a custom Spring Boot service wrapping a KMS) holds all keys. Services request keys at startup and on rotation events.

Pros:
- Single source of truth for all cryptographic material
- Key rotation is centralized — change once, all services pick up
- Audit trail of key access in one place
- Aligned with KM-04 (production KMS)

Cons:
- Single point of failure — if KMS is down, no service can encrypt/decrypt
- Network latency for key retrieval on every operation (mitigated by local caching)
- Additional infrastructure to operate

**Option 2: Deployment-time key injection — keys distributed as environment variables or mounted secrets**

Keys are injected at deployment time (Kubernetes Secrets, Docker secrets, or environment variables). Services hold keys in memory for the process lifetime.

Pros:
- No runtime dependency on external KMS
- Simple to implement and operate
- Sufficient for development and portfolio demonstration

Cons:
- Key rotation requires redeployment (or rolling restart to pick up new secrets)
- No centralized audit of key access
- Kubernetes Secrets are base64-encoded, not encrypted, by default (need encryption at rest for the etcd store)

**Option 3: Two-tier — deployment-time injection for development, centralized KMS for production**

Development and portfolio demo use Option 2. Architecture documents specify Option 1 for production. The application code abstracts key retrieval behind an interface (`KeyProvider`) with two implementations.

Pros:
- Practical for portfolio (no KMS infrastructure needed to run the demo)
- Production-grade architecture documented and designed for
- Clean abstraction makes the production upgrade path obvious

Cons:
- The KMS integration is designed but not implemented (could be seen as incomplete)
- The interface abstraction adds a small amount of code

**Recommendation: Option 3 — Two-tier with `KeyProvider` abstraction.**

Rationale: KM-04 already specifies this split ("Production keys stored in KMS... Development/portfolio: keys in environment variables or Docker secrets"). The architecture should formalize this with a clean interface so the codebase is KMS-ready without requiring Vault infrastructure for the demo. The `KeyProvider` interface is small (perhaps 3 methods: `getKey(keyId)`, `getSigningKey(purpose)`, `rotateKey(keyId)`) and makes the design intention explicit.

### Decision A.2b: Crypto-Shredding Key Storage

**Option 1: Per-account encryption keys stored in A1's PostgreSQL database**

A `account_encryption_keys` table in A1's schema. Columns: `account_id`, `key_id`, `encrypted_key` (encrypted with a master key), `created_at`, `deleted_at`.

Pros:
- Co-located with the account data — simple to manage
- No additional infrastructure
- Key deletion is a database UPDATE (set `deleted_at`, clear `encrypted_key`)

Cons:
- A1's database compromise exposes all keys (mitigated by master key encryption)
- Key retrieval is A1-local — other services cannot decrypt events without calling A1
- Ties crypto-shredding lifecycle to A1's availability

**Option 2: Separate key management store (dedicated PostgreSQL schema or Vault transit engine)**

A separate database or Vault's transit secrets engine holds per-account keys. All services that need to encrypt/decrypt call this store.

Pros:
- Separation of concerns — A1 manages accounts, key store manages keys
- Multiple services can encrypt/decrypt independently
- Key store can have stricter access controls than A1's main database

Cons:
- Additional infrastructure component
- Network latency for every encrypt/decrypt operation
- More complex operationally

**Recommendation: Option 1 — Keys in A1's database, encrypted with a master key.**

Rationale: In our system, A1 is the single service that writes and reads ledger events (the primary consumer of crypto-shredding). Other services interact with account data through A1's APIs, not by reading Kafka events directly. Since A1 is the only service that needs per-account decryption keys, co-locating them in A1's database is the simplest correct design. The master key that encrypts the per-account keys is managed via the `KeyProvider` abstraction (A.2a). If a future service needs direct event decryption, we can extract the key store then — but we should not build that infrastructure speculatively.

---

## A.3 — Threat Model

### Decision A.3a: Threat Modeling Approach

**Option 1: Full STRIDE analysis per component**

Systematically apply STRIDE (Spoofing, Tampering, Repudiation, Information Disclosure, Denial of Service, Elevation of Privilege) to every component, data flow, and trust boundary in the system.

Pros:
- Comprehensive — unlikely to miss a threat category
- Structured — produces a traceable mapping from threats to mitigations
- Industry standard (Microsoft SDL)

Cons:
- Very time-consuming for a 7-service system with ~15 Kafka topics and ~20 API endpoints
- Produces a large document that may be hard to maintain
- Many entries will be "mitigated by SEC-XX" — routine confirmation, not insight

**Option 2: Attack-tree analysis focused on high-value targets**

Identify the 3-5 highest-value assets (payment execution, account balances, sanctions screening completeness, PII, audit trail) and build attack trees showing how an adversary could compromise each. Map existing controls to each attack path.

Pros:
- Focused on what matters — prioritizes by impact
- Produces actionable findings (gaps are visible as unmitigated attack paths)
- More readable than exhaustive STRIDE tables

Cons:
- May miss threats to lower-value targets
- Subjective — depends on correctly identifying "high-value" assets
- Less systematic than STRIDE

**Option 3: Lightweight STRIDE on trust boundaries only, full attack trees for top 3 assets**

Apply STRIDE only at the trust boundary crossings (external->DMZ, DMZ->internal, service->Kafka, service->PostgreSQL). Then do detailed attack trees for: (1) unauthorized payment execution, (2) sanctions screening bypass, (3) PII exfiltration.

Pros:
- Balances thoroughness with focus
- Trust boundaries are where most attacks succeed — concentrating STRIDE there is efficient
- Attack trees on the highest-impact assets catch the critical gaps
- Manageable scope for a portfolio project

Cons:
- Not exhaustive — could miss intra-zone threats

**Recommendation: Option 3 — Boundary STRIDE + targeted attack trees.**

Rationale: A full STRIDE analysis of every component would produce a document larger than the requirements. The threats that matter most in a payment system are at trust boundaries (where external input enters) and against high-value assets (payment execution, sanctions completeness, PII). Option 3 covers both efficiently.

### Decision A.3b: Explicit Scope Exclusions

We should document which threats are **out of scope** for this portfolio project, so that reviewers understand the boundary is deliberate, not an oversight.

**Recommended exclusions:**
- Physical security of hardware (data center access controls)
- Social engineering attacks against bank employees
- Nation-state-level attacks on cryptographic algorithms (quantum computing)
- DDoS at the network/infrastructure level (L3/L4) — we handle application-level rate limiting (L7) but not volumetric attacks
- Supply chain attacks on JVM runtime or OS — we scan dependencies (SEC-06) but do not audit the JDK itself
- Insider threat by a person with root access to all systems simultaneously (this defeats any software-level control)

Rationale: Each exclusion is either outside our control (infrastructure), outside our scope (organizational), or defeats any software architecture (omnipotent insider). Documenting them shows security maturity — we know the boundaries of our defense.

---

## B.1 — Payment State Machine and Ownership Transitions

### Decision B.1a: Unified State Machine Design

**Option 1: Single global state machine — one service tracks the complete lifecycle**

A2 (Clearing Engine) maintains the authoritative payment state from INSTRUCTION_RECEIVED through SETTLED or REJECTED. Other services report back to A2, which advances the state.

Pros:
- Single source of truth — any query about "where is this payment?" goes to A2
- Timeout management is centralized — A2 handles all deadlines
- Simple to reason about and debug

Cons:
- A2 becomes a bottleneck and single point of failure for state queries
- A2 cannot track states that occur before it receives the payment (VoP in A3/A4)
- Tight coupling — other services must report to A2 rather than acting autonomously

**Option 2: Per-service local state, reconciled through events**

Each service maintains its own view of payment state. A3 tracks R2P states. A2 tracks clearing states. A1 tracks reservation states. The `payment.status-updates` Kafka topic is the shared event stream from which any service can build a composite view.

Pros:
- Loose coupling — each service is autonomous
- No single point of failure for state queries
- Natural fit for event sourcing (each service projects its own view from events)

Cons:
- No single service can answer "what is the current state of payment X?" without aggregating across multiple sources
- State may be temporarily inconsistent across services (A2 says SETTLED, A1 hasn't received the event yet)
- Harder to implement timeout management (who owns the timer?)

**Option 3: Orchestrator state in A2, supplemented by per-service local states (formalized hybrid)**

A2 is the authoritative state owner from the moment a payment instruction arrives on Kafka until settlement or rejection. Before that (in A3/A4 during VoP and submission), the originating service owns the state. After settlement, A1 owns the ledger truth and A3/A4 own the status reported to the payer/merchant.

State ownership transitions explicitly:
```
Phase 1: A3 or A4 owns → VoP, validation, submission to Kafka
   Handoff: payment.instructions or payment.instant-instructions published
Phase 2: A2 owns → clearing, fraud/sanctions coordination, settlement decision
   Handoff: payment.settlement-events or payment.status-updates published
Phase 3: A1 owns ledger truth, A3/A4 own reporting to external parties
```

Pros:
- Clear ownership at each phase — no ambiguity about who handles recovery
- A2 manages timeouts only for Phase 2 (its responsibility)
- A3/A4 manage timeouts for Phase 1 (their responsibility)
- Each service's local state is the authority for its own phase
- Natural fit for the flow already described in Section 9.3

Cons:
- The handoff points must be precisely defined (what if a Kafka publish fails during handoff?)
- Need a composite query mechanism for operators who want the full payment history

**Recommendation: Option 3 — Formalized hybrid with explicit ownership transitions.**

Rationale: This is already the implicit model in our requirements. Formalizing it makes the handoff points explicit, assigns timeout responsibility clearly, and tells each development team exactly what they own. The handoff mechanism is Kafka publication — Kafka's durability guarantees (RES-07: acks=all, replication 3) make the handoff reliable. A composite query for operators can be built from the `audit.events` topic (which captures all state changes across all services).

### Decision B.1b: Recovery Procedure on Service Restart

**Option 1: Kafka replay from last committed offset**

On restart, each Kafka consumer resumes from its last committed offset. Any messages not yet committed are reprocessed. Combined with idempotent processing (RES-05), this ensures no messages are lost.

Pros:
- Simple — Kafka's consumer group protocol handles this automatically
- No additional recovery logic needed
- Idempotent processing makes reprocessing safe

Cons:
- Does not handle in-flight state that was in memory but not yet published to Kafka (e.g., A2 was computing a netting result, crashed before publishing)
- Does not handle durable timers — if A2 had a 30-second timeout running for a balance reservation, the timer is lost on crash

**Option 2: Database-driven recovery scan**

On startup, each service queries its database for records in intermediate states (e.g., A2 scans for payments in FRAUD_CHECK_PENDING older than 30 seconds). These are either retried or timed out.

Pros:
- Catches in-flight state that was persisted to the database but not yet completed
- Handles timer recovery (the database timestamp shows when the operation started)
- Works regardless of Kafka consumer state

Cons:
- Requires discipline: every intermediate state must be persisted to the database before proceeding
- Recovery scan adds startup time
- Risk of double-processing if Kafka replay also delivers the same message (mitigated by idempotency)

**Option 3: Kafka replay + database recovery scan (belt and suspenders)**

On restart: (1) scan database for stale intermediate states and resolve them, (2) resume Kafka consumption from last committed offset. Both paths are idempotent, so overlap is safe.

Pros:
- Catches both categories: messages not yet consumed (Kafka replay) and in-flight operations not yet completed (database scan)
- Idempotency makes the overlap safe
- Maximum robustness

Cons:
- Slightly more complex startup logic
- Recovery scan may trigger actions that Kafka replay also triggers (but idempotency handles this)

**Recommendation: Option 3 — Kafka replay + database recovery scan.**

Rationale: In a payment system, "belt and suspenders" is appropriate. The database scan catches the case Kafka replay cannot: a service that consumed a message, started processing, persisted intermediate state, but crashed before completing. The Kafka replay catches the case the database scan cannot: a message that was never consumed. Since both paths are idempotent (RES-05), the overlap is not harmful. The recovery scan should run during startup, before the service marks itself as ready (STARTER-F33: startup probe).

---

## B.2 — Dual-Write Problem

### Decision B.2a: Strategy Per Service

Rather than one strategy for all services, the choice should match each service's data flow:

| Service | Recommended Strategy | Rationale |
|---------|---------------------|-----------|
| **A1 (Account Ledger)** | **Kafka-first** | A1 is event-sourced by design. Kafka IS the source of truth (`ledger.events` with infinite retention). PostgreSQL projections are derived from consuming Kafka events. There is no dual-write — Kafka is the single write target. |
| **A2 (Clearing Engine)** | **Transactional Outbox** | A2's batch path uses Spring Batch, which is database-centric (job state, step execution context). A2 must update its clearing status in PostgreSQL and publish events to Kafka. The Outbox pattern writes the event to an outbox table in the same database transaction as the status update. |
| **A3 (R2P Service)** | **Transactional Outbox** | A3 updates request status in PostgreSQL (ACCEPTED, DECLINED, etc.) and publishes to Kafka. Same pattern as A2. |
| **A4 (PSD2 Gateway)** | **Transactional Outbox** | A4 updates consent status in PostgreSQL and publishes payment instructions to Kafka. Same pattern. |
| **A5 (Fraud/Sanctions)** | **Kafka Streams internal** | A5's instant path uses Kafka Streams with `exactly_once_v2`. Kafka Streams handles the produce-and-commit atomically within its own framework. For the case management API (PostgreSQL updates), use Transactional Outbox. |
| **A6 (VoP)** | **Not applicable** | A6 is called synchronously (REST) by A3/A4. It queries A1 for name data and returns a result. It does not write to Kafka or PostgreSQL in the payment flow. |

### Decision B.2b: Outbox Relay Mechanism

For services using the Transactional Outbox, we need a relay to move events from the outbox table to Kafka.

**Option 1: Polling relay (scheduled task reads outbox table periodically)**

A scheduled Spring task (e.g., every 100ms) queries `SELECT * FROM outbox WHERE published = false ORDER BY created_at`, publishes to Kafka, marks as published.

Pros:
- Simple to implement — a few lines of Spring code
- No additional infrastructure
- Easy to understand and debug
- Latency: 0-100ms added (half the poll interval on average)

Cons:
- Polling interval creates a latency floor (even if configurable down to 50ms, it's not zero)
- Under high load, frequent polling can add database pressure
- Need to handle concurrent relays if multiple instances run the same service (distributed lock or leader election)

**Option 2: CDC with Debezium (Change Data Capture from PostgreSQL WAL)**

Debezium monitors PostgreSQL's Write-Ahead Log and publishes outbox records to Kafka as they are committed.

Pros:
- Near-zero latency (event published as soon as transaction commits)
- No polling — event-driven relay
- Proven pattern (Debezium has a dedicated "outbox" transform)

Cons:
- Significant infrastructure addition (Debezium connector, Kafka Connect)
- Operational complexity (monitoring Debezium, handling connector failures, WAL retention)
- Overkill for a portfolio project with moderate throughput

**Option 3: In-process event publishing with transactional retry**

After committing the database transaction, the same thread immediately publishes to Kafka. If the Kafka publish fails, a retry mechanism (Spring Retry or scheduled reprocessing of unpublished outbox records) picks it up.

Pros:
- Near-zero latency in the happy path (publish immediately after commit)
- No additional infrastructure
- Falls back to polling-like retry on failure (outbox table as safety net)

Cons:
- The immediate publish can fail (Kafka temporarily unavailable) — must handle gracefully
- Slightly more complex than pure polling (two code paths: immediate + retry)

**Recommendation: Option 3 — In-process publish with outbox fallback.**

Rationale: This gives us the best of both worlds. In the happy path (Kafka available, which is >99.9% of the time), events are published with near-zero latency. The outbox table is the safety net — a scheduled task picks up any records that failed immediate publishing. This avoids the infrastructure overhead of Debezium while being faster than pure polling. The outbox table also serves as an audit trail of all published events.

For the instant payment path specifically, latency matters: the 100ms average delay of pure polling would consume 2% of our 5-second budget on every internal step. In-process publishing avoids this.

---

## B.3 — Cross-Service Exactly-Once

### Decision B.3a: Idempotency Key Strategy

**Recommendation: Hierarchical transaction ID with per-service operation IDs.**

The idempotency strategy should work as follows:

1. **A3 or A4 generates the top-level `transactionId`** (UUID) when a payment is first initiated. This ID is immutable and travels with the payment through its entire lifecycle.

2. **Each service generates operation-specific IDs** for its downstream interactions:
   - A2 generates a `fraudCheckRequestId` when requesting fraud scoring from A5
   - A2 generates a `settlementEventId` when publishing settlement events to A1
   - These are deterministically derived from the `transactionId` (e.g., `SHA-256(transactionId + "fraud-check")`) so that retries produce the same operation ID

3. **Each service deduplicates by the relevant ID:**
   - A2 deduplicates incoming payment instructions by `transactionId`
   - A5 deduplicates fraud check requests by `fraudCheckRequestId`
   - A1 deduplicates settlement events by `settlementEventId`

4. **Deduplication store:** Each service keeps a deduplication table in PostgreSQL (`processed_message_ids`) with TTL. For A5's Kafka Streams path, the Kafka Streams state store provides deduplication naturally.

Pros:
- Every service has a clear deduplication key specific to its input
- Deterministic derivation means retries produce the same ID — no coordination needed
- The `transactionId` provides end-to-end correlation
- TTL on the deduplication store prevents unbounded growth

Tradeoff — TTL duration:
- Too short: a very late retry could be processed as new (double-processing)
- Too long: deduplication table grows large
- Recommendation: TTL = 24 hours for batch path, 1 hour for instant path. These are far longer than any legitimate retry window.

---

## B.4 — Investigation Queues

### Decision B.4a: Investigation Queue Architecture

**Option 1: Extend the existing DLQ with operator tooling**

DLQ topics (`{topic}.dlq`) already capture unprocessable messages. Add an operator API that reads DLQ messages, displays them in a dashboard, and allows resubmission to the original topic after manual correction.

Pros:
- Builds on existing infrastructure (DLQ topics)
- No new message format or routing
- Simple to implement

Cons:
- DLQ captures deserialization failures and unexpected errors — not the same as "stuck payments"
- A payment stuck in FRAUD_CHECK_PENDING is not in a DLQ — it's in A2's database in an intermediate state
- Conflates two different concerns (broken messages vs. stuck payments)

**Option 2: Dedicated investigation topic + operator service**

A new Kafka topic `payment.investigation` receives payments that are stuck in intermediate states (detected by the database recovery scan from B.1b or by timeout). A dedicated operator service (or an extension of the existing management APIs) provides:
- Dashboard: list stuck payments with current state, timestamp, and reason
- Actions: force-complete, force-reject, retry, escalate
- Audit: all operator actions logged to `audit.events`

Pros:
- Clean separation: DLQ is for broken messages, investigation queue is for stuck payments
- First-class concept — not an afterthought
- Mirrors FinTP's investigation queues (proven pattern in production payment systems)
- Operator actions are audited (regulatory requirement for manual interventions)

Cons:
- Additional infrastructure (new topic, new service or API endpoints)
- Need to define the exact conditions that trigger investigation (which states, which timeouts)

**Option 3: Database-only investigation — no Kafka topic, just a queryable view**

Each service exposes an API endpoint: `GET /api/v1/admin/stuck-payments` that returns payments in intermediate states older than a configurable threshold. No dedicated Kafka topic — the operator queries each service directly.

Pros:
- Simplest implementation — no new infrastructure
- Each service knows its own stuck payments best

Cons:
- Operator must query 3 services (A2, A3/A4, A1) to get the full picture
- No unified view
- Doesn't scale well if more services are added

**Recommendation: Option 2 — Dedicated investigation topic + operator API in A2.**

Rationale: Stuck payments are a critical operational concern in payment processing. FinTP treats investigation queues as first-class — and for good reason. A payment stuck in FRAUD_CHECK_PENDING for 5 minutes is not a DLQ problem (the message was consumed successfully) and cannot be found by querying A5 (A5 may have already responded, the response just wasn't processed). Centralizing investigation in A2 makes sense because A2 is the orchestrator during Phase 2 (the most complex phase). The investigation API should also aggregate Phase 1 stuck payments from A3/A4 via REST calls, so operators have a single entry point.

Escalation thresholds (recommended):
- Instant payment stuck >30 seconds: auto-escalate (should have completed or timed out)
- Batch payment stuck >2x clearing cycle window: alert
- Any payment stuck >1 hour: page on-call

---

## C.1 — Orchestration Model

### Decision C.1a: Saga Pattern

**Option 1: Orchestrated Saga — A2 manages all compensation**

A2 tracks payment state and, on failure, sends explicit compensation commands: "release balance reservation," "cancel fraud check," "update status to REJECTED."

Pros:
- Centralized compensation logic — easy to reason about and test
- A2 already tracks payment state (B.1a, Option 3)
- Compensation order is controlled (release balance before reporting rejection)

Cons:
- A2 must know about every service's compensation API
- Tight coupling between A2 and other services' internals

**Option 2: Choreographed Saga — each service compensates on failure events**

On failure, A2 publishes a failure event (`payment.status-updates: REJECTED`). Each service listens and compensates independently: A1 releases the reservation, A3/A4 updates the payer.

Pros:
- Loose coupling — each service decides how to compensate
- Adding a new service doesn't require modifying A2

Cons:
- Compensation may happen out of order or be missed
- Harder to guarantee that all compensations complete
- No single service has visibility into whether compensation is finished

**Option 3: Orchestrated Saga for instant path, choreographed for batch path**

The instant path has a tight SLA and must complete or fully roll back within 7 seconds. A2 orchestrates compensation explicitly. The batch path has more time and can use event-driven choreography.

Pros:
- Instant path gets the determinism it needs (bounded time, controlled rollback)
- Batch path gets the flexibility of loose coupling (can add steps without modifying A2)
- Matches the fundamentally different processing models

Cons:
- Two different patterns in one system — developers need to understand both
- Slightly higher complexity than a uniform approach

**Recommendation: Option 3 — Orchestrated for instant, choreographed for batch.**

Rationale: The instant path must guarantee that a failed payment is fully rolled back within the 7-second hard limit. This requires deterministic compensation: A2 releases the balance reservation, then publishes the rejection. There is no time for eventual consistency. The batch path, by contrast, operates in cycles (minutes to hours) and can tolerate the small delay of event-driven compensation. This dual approach reflects a genuine difference in the problem, not unnecessary complexity.

---

## C.2 — Time Budget Allocation

### Decision C.2a: Static vs. Dynamic Timeouts

**Option 1: Static timeouts — each service gets a fixed budget**

```
VoP check (A6):           500ms
A2 receive + validate:    200ms
Balance reservation (A1): 200ms
Fraud + sanctions (A5):   800ms (500ms fraud + 300ms sanctions with margin)
Settlement logic (A2):    300ms
Ledger confirm (A1):      200ms
Confirmation (pacs.002):  200ms
Network/Kafka overhead:   600ms
Safety margin:          2,000ms
──────────────────────────��─────
Total:                  5,000ms (execution target)
```

Pros:
- Simple to implement — each service configures its own timeout
- Predictable — no dynamic adjustment logic
- Easy to test and reason about

Cons:
- Wastes time: if A5 responds in 20ms, the saved 780ms is not usable
- Rigid: if network latency is higher than expected, there's no way to compensate

**Option 2: Dynamic remaining-budget — A2 tracks elapsed time and adjusts downstream timeouts**

A2 records a start timestamp when it receives the payment instruction. Before each downstream call, it calculates: `timeout = hardLimit - elapsed - safetyMargin`. Downstream calls use `CompletableFuture.orTimeout(remainingBudget)`.

Pros:
- Maximally efficient use of the time budget
- Adapts to real conditions (fast A5 response leaves more time for A1)
- Handles the case where Kafka delivery is slow (less budget for processing, but we still try)

Cons:
- More complex to implement (clock management, margin calculation)
- Harder to test (outcomes depend on actual timing)
- Risk: if early steps are slow, later steps get impossibly short timeouts

**Option 3: Static baselines with dynamic hard-limit enforcement**

Each service has a static timeout (Option 1) for its individual operations. But A2 also maintains a global timer against the 7-second hard limit. If the global timer fires, A2 immediately rejects regardless of which step is in progress.

Pros:
- Simple per-service timeouts (easy to configure and test)
- Global hard-limit enforcement prevents SLA violation under any circumstance
- If early steps are fast, the time "savings" automatically flow to the global margin
- Best of both: predictable individual timeouts + guaranteed global SLA

Cons:
- A step may be interrupted mid-operation by the global timer (must handle cleanly)

**Recommendation: Option 3 — Static baselines with global hard-limit enforcement.**

Rationale: Option 2 is theoretically optimal but introduces complexity that is hard to test and debug. Option 3 provides the critical guarantee (the 7-second hard limit is never violated) while keeping per-service timeouts simple and predictable. The key insight: we don't need to optimize every millisecond. Our happy path is ~3-4 seconds. The static budget sums to 5 seconds. The hard limit is 7 seconds. There is 2-3 seconds of margin — enough that we don't need dynamic optimization.

The global timer implementation: `CompletableFuture.orTimeout(7, SECONDS)` wrapping the entire instant payment processing pipeline in A2. When it fires, A2 executes the orchestrated Saga compensation (release reservation, publish REJECTED).

---

## C.3 — Backpressure and Load Isolation

### Decision C.3a: Batch vs. Instant Isolation Within A2

**Option 1: Separate thread pools within a single JVM**

A2 runs as one Spring Boot application with two thread pools: one for the batch path (Spring Batch), one for the instant path (Kafka Streams). They share the same JVM but have isolated threads.

Pros:
- Simplest deployment — one service to manage
- Thread pools provide CPU isolation
- Kafka Streams already manages its own threads

Cons:
- Shared JVM means shared heap — a batch OOM kills instant too
- Shared PostgreSQL connection pool unless explicitly separated
- No memory isolation

**Option 2: Separate JVM instances (same codebase, different profiles)**

A2 is deployed as two instances: `clearing-engine-batch` (Spring profile `batch`) and `clearing-engine-instant` (Spring profile `instant`). Same codebase, different entrypoints. Each subscribes only to its respective Kafka topics.

Pros:
- Full process isolation — batch OOM does not affect instant
- Independent scaling (more instant replicas during peak hours)
- Independent deployment (batch can be restarted without affecting instant SLA)
- Clean separation aligns with the fundamentally different processing models

Cons:
- Two deployments to manage instead of one
- Shared database still requires connection pool discipline
- If both paths need to update the same database tables (clearing history), they need coordination

**Option 3: Separate JVM instances with separate database schemas**

Like Option 2, but each instance has its own PostgreSQL schema (or database). Batch clearing state and instant clearing state are stored independently. Shared data (e.g., liquidity positions) is communicated via Kafka or a shared read-only view.

Pros:
- Complete isolation: process, memory, database I/O
- No contention between batch inserts and instant queries
- Maximum protection of the instant SLA

Cons:
- Most complex operationally
- Liquidity management becomes harder (both paths affect the same settlement accounts)
- Reconciliation between batch and instant settlement records adds complexity

**Recommendation: Option 2 — Separate JVM instances, shared database with connection pool separation.**

Rationale: Process isolation is essential for the 24/7/365 SLA. A batch clearing job processing 10,000 payments must not be able to affect instant payment latency. Option 2 provides this while keeping the database unified (important for liquidity management — both paths need to see the same settlement account balances). Within the shared database, we use separate connection pools: the instant instance gets a reserved pool with guaranteed minimum connections. The instant instance also gets Kubernetes resource limits (CPU, memory) separate from the batch instance.

Option 3's full database separation creates more problems than it solves — liquidity management across two databases requires distributed coordination, which is exactly the problem we're trying to avoid.

---

## D.1 — Event Schema Evolution

### Decision D.1a: Schema Evolution Rules

**Recommendation: Codify explicit allowed/forbidden changes, enforced by Schema Registry in CI/CD.**

**Allowed changes (backward-compatible under Avro rules):**
- Add a new field with a default value
- Add a new enum value (if reader uses a default for unknown values)
- Add a new event type (new Avro schema on the same topic)

**Forbidden changes:**
- Remove a field
- Rename a field (Avro treats this as remove + add)
- Change a field's type (e.g., `int` to `long`, `string` to `bytes`)
- Change a field from optional to required
- Remove an enum value

**Semantic versioning for event schemas:**
- Minor version (1.0 -> 1.1): backward-compatible addition — existing consumers continue to work
- Major version (1.x -> 2.0): breaking change — requires a new topic (e.g., `ledger.events.v2`) and a migration period where both topics are active

**Enforcement:**
- Schema Registry configured with `BACKWARD` compatibility (already decided in DATA-06)
- CI/CD pipeline: on every PR that modifies an Avro schema, run `mvn schema-registry:test-compatibility` (or Gradle equivalent) against the registered schema. Fail the build if incompatible.
- Code review checklist: any schema change must be reviewed for semantic compatibility (not just Avro compatibility)

**Handling multiple schema versions in projection code:**
- Avro's schema resolution handles this transparently: the consumer reads with the latest schema, Avro resolves old events against it (missing fields get defaults, extra fields are ignored). This is exactly what BACKWARD compatibility guarantees.
- For semantic changes (rare, major version): the projection must contain version-specific logic. This is the cost of event sourcing — but it should be extremely rare if we design schemas carefully from the start.

---

## D.2 — API Versioning

### Decision D.2a: Versioning Strategy

**Option 1: URL path versioning (`/api/v1/payments`, `/api/v2/payments`)**

Pros:
- Explicit and visible — the version is in the URL
- Easy to route in a load balancer or API gateway
- BerlinGroup NextGenPSD2 uses path versioning (`/v1/`)
- Most common pattern in banking APIs (DNB, SpareBank 1 both use it)

Cons:
- Changing the version requires updating all client URLs
- Multiple versions mean multiple controller implementations (or a routing layer)

**Option 2: Header versioning (`Accept: application/vnd.nordpay.v2+json`)**

Pros:
- URL stays stable — less disruptive for clients
- Cleaner REST semantics (the resource is the same, the representation differs)

Cons:
- Less discoverable — clients must know to set the header
- Harder to test with browser/curl (must remember the header)
- Not the banking industry convention

**Recommendation: Option 1 — URL path versioning.**

Rationale: This is the convention in the banking/payment API space. BerlinGroup NextGenPSD2 (which A4 implements) uses `/v1/`. DNB and SpareBank 1 use path versioning. Following convention matters for PSD2 APIs because TPP developers expect it. For our portfolio, it also makes APIs easy to demonstrate (curl commands are self-documenting).

**Version support policy:** Support at most 2 concurrent versions. When `v2` is released, `v1` enters a 6-month deprecation period (12 months for PSD2 APIs due to regulatory requirements). After deprecation, `v1` returns `410 Gone`.

**Internal APIs:** Internal service-to-service REST calls (e.g., A4 -> A1 for balance queries) do NOT need versioning. We control both sides and can evolve them in lockstep. Versioning is for external consumers only.

---

## E.1 — Error Handling Strategy

### Decision E.1a: Standard REST Error Response

**Recommendation: RFC 9457 (Problem Details for HTTP APIs) — implemented by the Platform Starter.**

```json
{
  "type": "https://api.nordpay.example/errors/insufficient-balance",
  "title": "Insufficient Balance",
  "status": 422,
  "detail": "Account NO9386011117947 has available balance NOK 450.00, requested NOK 1,250.00",
  "instance": "/api/v1/payments/e3b0c442-98fc-1c14-b39f-f73158a2b1cf",
  "correlationId": "req-7f3a8b2c-4d1e-4f5a-8c9d-2e1f3a4b5c6d",
  "timestamp": "2026-04-11T14:32:00.000Z"
}
```

Rationale: RFC 9457 is an IETF standard specifically for HTTP API error responses. Spring Framework 6+ has built-in support (`ProblemDetail` class, `ErrorResponse` interface). Using it gives us:
- Standard format across all services (via Starter auto-configuration)
- Machine-readable error types (the `type` URI)
- Human-readable explanation (the `detail` field)
- Correlation ID for tracing (our addition, not in the RFC but a standard extension)
- Spring does most of the work — the Starter's global exception handler maps exceptions to `ProblemDetail` instances

### Decision E.1b: Kafka Error Classification

**Recommendation: Three-category classification enforced by a shared consumer error handler in the Starter.**

| Category | Behavior | Examples |
|----------|----------|---------|
| **Retryable** | Retry with exponential backoff (max 3 retries, initial delay 100ms, max delay 5s) | Database temporarily unavailable, Kafka producer timeout, HTTP 503 from downstream service |
| **Permanent failure → DLQ** | Send to `{topic}.dlq` with error metadata, continue processing | Deserialization error (corrupt message), schema mismatch, business rule violation with no recovery path |
| **Critical → DLQ + alert** | Send to DLQ AND trigger PagerDuty/Slack alert | Sanctions screening failure (cannot skip), balance reservation on a non-existent account, data integrity violation |

The Starter provides a `PaymentErrorHandler` that implements Spring Kafka's `CommonErrorHandler` interface with this three-category logic. Services configure which exceptions are retryable vs. permanent by providing an exception classifier.

**Terminology note:** The Platform Starter implements the three categories as `TRANSIENT` (retryable), `POISONED` (permanent → DLQ), and `FATAL` (critical → stop consumer + alert). The FATAL category is stricter than this document's 'DLQ + alert' — it stops the consumer to prevent further damage. Services can override the FATAL behavior to use DLQ + alert instead of consumer stop, depending on their domain requirements.

### Decision E.1c: Exception Hierarchy

**Recommendation: Two-layer hierarchy in the shared domain model.**

```
PaymentException (abstract)
├── DomainException (business rule violations — expected, handled)
│   ├── InsufficientBalanceException
│   ├── AccountFrozenException
│   ├── SanctionsHitException
│   ├── VoPNoMatchException
│   ├── SCARequiredException
│   ├── DuplicatePaymentException
│   └── PaymentTimeoutException
└── InfrastructureException (system failures — unexpected, operational)
    ├── KafkaUnavailableException
    ├── DatabaseUnavailableException
    └── DownstreamServiceException
```

- **Domain exceptions** map to specific HTTP status codes and RFC 9457 problem types. They are expected and handled by business logic.
- **Infrastructure exceptions** map to 503 (Service Unavailable) and trigger retries or circuit breakers. They are unexpected and indicate operational issues.

This hierarchy lives in `payment-domain-model` (the shared library) so all services use the same exceptions. The Starter's global exception handler maps them to appropriate HTTP responses and Kafka error categories automatically.

---

## E.2 — Distributed Observability

### Decision E.2a: Tracing Across Kafka Boundaries

**Option 1: OpenTelemetry with Kafka instrumentation (auto-instrumented)**

Use the OpenTelemetry Java agent (`-javaagent:opentelemetry-javaagent.jar`). It automatically instruments Spring MVC, Spring Kafka, and Kafka Streams — propagating trace context through Kafka headers without code changes.

Pros:
- Zero application code changes — the Java agent handles everything
- Automatic span creation for Kafka produce/consume, HTTP requests, database queries
- Industry standard (CNCF project, widely adopted)
- Exports to any backend (Jaeger, Zipkin, Grafana Tempo)

Cons:
- Java agent adds ~5-10% overhead in some benchmarks (needs measurement for our latency-sensitive instant path)
- Less control over what is traced (can be noisy)
- Agent compatibility must be verified with our Spring Boot and Kafka versions

**Option 2: Manual trace context propagation via Kafka headers**

Use Micrometer Tracing (Spring Boot's built-in tracing abstraction) with explicit propagation: when producing a Kafka message, copy `traceparent` header from the current context. When consuming, extract and set as the current trace.

Pros:
- Full control over what is traced
- No Java agent — no overhead on non-traced operations
- Guaranteed compatibility with Spring Boot

Cons:
- Requires code in every Kafka producer and consumer (mitigated if centralized in the Starter)
- Must handle Kafka Streams differently (interceptors)
- More development effort

**Option 3: Starter auto-configures Micrometer Tracing with Kafka propagation**

The Platform Starter configures Micrometer's `ObservationRegistry` with Kafka observation handlers that propagate trace context through Kafka headers. Spring Boot 3.x has built-in support for this via `spring-kafka` and `micrometer-tracing`.

Pros:
- Native Spring Boot integration — no external agent
- The Starter ensures all services get it automatically
- Micrometer Tracing is the official Spring approach (replacing Spring Cloud Sleuth)
- Lower overhead than the Java agent (only instruments what Spring manages)
- Correlation ID (STARTER-F14) can be the trace ID — unifying logging and tracing

Cons:
- Kafka Streams instrumentation is less mature in Micrometer than in OpenTelemetry
- Needs Kafka Streams-specific interceptor configuration for A2 and A5

**Recommendation: Option 3 — Micrometer Tracing auto-configured by the Starter.**

Rationale: The Platform Starter is our mechanism for cross-cutting concerns. Adding Micrometer Tracing to the Starter ensures all services get distributed tracing without code changes. Spring Boot 3.x's native tracing support is sufficient for our needs. The correlation ID from STARTER-F14 becomes the trace ID, unifying logs and traces. For Kafka Streams (A2 instant path, A5), we add interceptors in the Starter's Kafka Streams auto-configuration.

For the tracing backend: Grafana Tempo (free, integrates with our existing Grafana + Prometheus stack) for the demo. The architecture is backend-agnostic — any OTLP-compatible backend works.

### Decision E.2b: SLA Monitoring Metric

**Recommendation: Composite end-to-end metric published by A2.**

A2 records a histogram metric: `payment.instant.e2e.duration` with tags `outcome=SETTLED|REJECTED|TIMEOUT`. This measures from "message consumed from `payment.instant-instructions`" to "pacs.002 confirmation published or rejection published."

Alert rules:
- p95 > 4.0s: warning (approaching execution target)
- p99 > 5.0s: critical (breaching execution target)
- Any single transaction > 7.0s: incident (hard limit violated — should be impossible with global timer, but monitor anyway)

This metric lives in A2 because A2 orchestrates the instant payment lifecycle (Phase 2). The VoP check (Phase 1) is measured separately by A3/A4.

---

## F.1 — GDPR vs. Event Sourcing

### Decision F.1a: Which Fields Are Encrypted

**Recommendation: Encrypt PII fields only, leave financial and operational fields in plaintext.**

| Field | Encrypted? | Rationale |
|-------|-----------|-----------|
| Account holder name | Yes | Direct PII identifier |
| Account holder address | Yes | Direct PII |
| Transaction description / remittance info | Yes | May contain PII (invoices with names) |
| IBAN | **No** | Pseudonymous identifier — needed for routing, deduplication, reconciliation. Without IBAN in plaintext, the event stream is unusable for its core purposes. IBAN alone does not identify a person without cross-referencing a bank's customer database |
| Amount, currency | No | Financial data, not PII |
| Timestamps | No | Operational data |
| Transaction ID, clearing cycle ID | No | Technical identifiers |
| Fraud score | No | Computed metric, already anonymized (A5-F12) |

Rationale: The goal of crypto-shredding is to make the data subject unidentifiable after key deletion. Encrypting name + address + description achieves this — an IBAN alone cannot identify a person without access to the bank's internal systems (which we also control). Encrypting the IBAN would make the event stream unusable for balance queries, reconciliation, and routing.

### Decision F.1b: Consumer Behavior After Key Deletion

**Recommendation: Consumers encounter `DecryptionFailedException`, store the field as `[REDACTED]`.**

When A1 rebuilds projections and encounters an event where the encryption key has been deleted:
- PII fields decrypt to `[REDACTED]` (handled by the crypto library, not by each consumer)
- Non-PII fields (IBAN, amount, timestamps) remain intact
- The projection is rebuilt with redacted PII — account balance is correct (amounts are unencrypted), account holder name shows `[REDACTED]`
- A6 VoP queries for a redacted account return `ACCOUNT_CLOSED` (the account should be closed before erasure — GDPR erasure requires a legitimate basis, and an active payment account has a legal obligation to retain data)

This means: GDPR erasure is only available for closed accounts (after the mandatory retention period). For active accounts, the legal basis for data processing is the contractual relationship — GDPR right to erasure does not override it.

---

## F.2 — Audit Trail Immutability

### Decision F.2a: Tamper-Evidence Mechanism

**Option 1: Cryptographic hash chaining**

Each audit event includes `previousHash = SHA-256(previousEvent)`. This creates a chain: tampering with any event invalidates the hash of every subsequent event. A periodic integrity check walks the chain and verifies consistency.

Pros:
- Mathematically provable tamper evidence
- Lightweight — one SHA-256 computation per event
- Can be verified independently by any party with access to the events

Cons:
- Requires sequential event processing (cannot parallelize hash chain computation)
- A gap (deleted event) is detectable but not recoverable
- Adds ordering dependency: events must be stored and read in exact order

**Option 2: Per-event digital signatures (each service signs its audit events)**

Each service signs its audit events with its private key. Consumers (or auditors) verify signatures with the service's public key.

Pros:
- Non-repudiation: proves which service generated the event
- Independent of event ordering
- Can be verified per-event (no chain dependency)

Cons:
- Higher computational cost (RSA or ECDSA signature per event)
- Does not detect deletion (a missing event has no signature to verify)
- Key management: signing keys must be protected from the same adversary

**Option 3: Hash chaining + digital signatures + periodic audit snapshots**

Combine: hash chaining for tamper detection, digital signatures for non-repudiation. Periodically (daily), write a signed checkpoint to a separate, append-only store (e.g., a separate PostgreSQL table with write-only access, or S3 with object lock). The checkpoint contains the latest hash and event count, signed by a dedicated audit key.

Pros:
- Hash chaining detects tampering
- Signatures prove provenance
- Periodic snapshots to a separate store mean that even if the primary Kafka topic is compromised, the snapshots reveal the discrepancy
- Defense in depth

Cons:
- Most complex option
- Periodic snapshots add infrastructure (but the snapshot store is simple)

**Recommendation: Option 1 for the portfolio project (hash chaining), with Option 3 documented as the production target.**

Rationale: Hash chaining is the most impactful measure for the effort — it makes tampering detectable without significant infrastructure. For a portfolio project, this demonstrates the concept. The architecture document should describe Option 3 as the production-grade target and explain why (auditors need both tamper detection and non-repudiation). The Starter library implements the hash chain computation — services call `AuditLogger.logEvent(event)` and the Starter handles hashing, chaining, and publishing to `audit.events`.

Retention periods:
- Payment transaction audit events: 5 years (AML regulation)
- Sanctions screening decisions: 5 years (AML)
- Consent/authorization events: 5 years (PSD2)
- Operational audit (health checks, deployments): 1 year (DORA)
- PII-containing audit data: subject to crypto-shredding timeline

---

## G.1 — Integration Testing

### Decision G.1a: Testing Pyramid

**Recommendation: Four-level pyramid.**

| Level | What | Tools | Speed |
|-------|------|-------|-------|
| **Unit** | Domain logic, algorithms, state machines, validation rules. No Spring context, no I/O. | JUnit 5, Mockito | <1s per test class |
| **Service integration** | Single service with real Kafka + PostgreSQL. Tests full request→Kafka→consume→database flow within one service. | Testcontainers (Kafka, PostgreSQL), Spring Boot Test | 10-30s per test class |
| **Contract** | Verify that messages and APIs conform to agreed schemas. Producer verifies it sends what consumers expect. Consumer verifies it can read what producers send. | Pact (REST APIs + Kafka messages), Schema Registry compatibility tests | 5-10s per test class |
| **End-to-end** | Full system (all 6 services) with scripted payment flows. Run as part of CI/CD and as the demo. | docker-compose, bash/httpie scripts or Spring Boot Test with @SpringBootTest | 2-5 min for full suite |

**Key decisions:**
- **Pact for Kafka messages:** Yes. Pact supports message-based contract testing. A2 (consumer of payment instructions) defines a Pact: "I expect messages on `payment.instructions` with fields X, Y, Z." A3 and A4 (producers) verify they generate matching messages. This catches schema mismatches before integration.
- **Timing tests:** Use `awaitility` library for testing async assertions (e.g., "within 5 seconds, payment should be SETTLED"). For timeout scenarios, inject a mock A5 that introduces configurable delay — verify that A2 rejects after the timeout.
- **Sanctions test data:** Synthetic test list with ~100 entries covering: exact match names, common transliterations, partial name matches, name order variations, and a few Nordic names with diacritics. Do NOT use real EU sanctions lists in CI (data protection and legal risk). Document: "production sanctions list is EU Consolidated List; test list is synthetic and does not represent real persons."

---

## G.2 — End-to-End Testing

### Decision G.2a: Demo Script Implementation

**Recommendation: Shell script (`run-demo.sh`) calling `httpie` or `curl`, with timing verification.**

```bash
# Simplified structure of run-demo.sh:
1. docker-compose up -d --wait    # Start all services, wait for health checks
2. ./seed-test-data.sh            # Create accounts, register merchant, register TPP, load test sanctions list
3. Submit payment via curl → capture response + timestamp
4. Poll for settlement → capture completion timestamp
5. Calculate elapsed time → assert < 5 seconds
6. Verify balances via A1 API → assert debtor decreased, creditor increased
7. Submit payment with wrong name → verify VoP NO_MATCH
8. Submit suspicious transaction → verify fraud flag
9. Submit with sanctioned name → verify BLOCKED
10. Run PSD2 consent flow → verify OAuth2 token issuance
11. Print summary with timings and pass/fail per step
```

Rationale for shell script over Spring Boot Test:
- **Demonstrability:** An interviewer can read a shell script and understand the demo flow. A Spring Boot test class requires Java context.
- **Independence:** The demo script uses only HTTP APIs — it verifies the system as an external consumer would.
- **Timing accuracy:** Wall-clock time from the script's perspective is the true end-to-end latency including network.

The seed script (`seed-test-data.sh`) calls POST endpoints to create: 2 accounts (payer + payee) with initial balances, 1 merchant with API key, 1 TPP with client credentials, 1 synthetic sanctions list entry. All seed data uses deterministic IDs so the demo is reproducible.

---

## H.1 — Zero-Downtime Deployment

### Decision H.1a: Deployment Strategy

**Option 1: Rolling deployment (Kubernetes default)**

Kubernetes starts new pods before terminating old ones. Old pods receive a SIGTERM, drain connections (graceful shutdown from STARTER-F40-F43), then terminate.

Pros:
- Native Kubernetes behavior — no extra tooling
- Minimal resource overhead (only 1 extra pod during deployment)
- Well-understood

Cons:
- Kafka consumer rebalancing during rolling updates causes a brief processing pause
- Database migrations must be backward-compatible (old pods still running)

**Option 2: Blue-green deployment**

Two full environments. Deploy to the idle environment, run smoke tests, switch traffic.

Pros:
- Zero-risk rollback (switch back to the old environment)
- No mixed-version processing

Cons:
- Double the infrastructure cost
- Kafka consumers in two environments create two consumer groups — complex to manage
- Overkill for a portfolio project

**Recommendation: Option 1 — Rolling deployment with the following safeguards:**

1. **Cooperative Sticky Assignor** for Kafka consumers: `partition.assignment.strategy=org.apache.kafka.clients.consumer.CooperativeStickyAssignor`. This minimizes partition reassignment during rolling updates — only partitions owned by the shutting-down instance are reassigned, not all partitions.

2. **Expand-contract database migrations:** Every Flyway migration must be backward-compatible with the previous application version. Adding a column: add it as nullable in migration N, start using it in application version N+1, make it NOT NULL in migration N+2 (after all old instances are gone).

3. **ReadinessProbe gating:** A new instance's Kafka consumers only start processing after the readiness probe passes (STARTER-F32). This prevents a half-initialized instance from consuming messages it cannot fully process.

4. **Startup probe:** The startup probe (STARTER-F33) gives the application time to complete initialization (projection loading, cache warming) before liveness checks begin. Prevents Kubernetes from killing a slowly-starting instance.

---

## H.2 — Configuration Management

### Decision H.2a: Configuration Architecture

**Option 1: Environment variables only (12-factor)**

All configuration via environment variables or Spring profiles. Changes require redeployment.

Pros:
- Simplest possible approach
- No additional infrastructure
- Sufficient for a portfolio project

Cons:
- Cannot change fraud thresholds without redeployment
- No centralized view of all configuration
- Shared parameter consistency relies on documentation discipline

**Option 2: Spring Cloud Config Server**

A centralized Git-backed configuration server. Services fetch configuration on startup and can be refreshed at runtime (`/actuator/refresh`).

Pros:
- Centralized configuration — single source of truth
- Runtime refresh without redeployment
- Git-backed — configuration changes are versioned and auditable

Cons:
- Additional infrastructure (the Config Server itself)
- Config Server is a single point of failure for startup (mitigated by local cache)
- Operational complexity for a portfolio project

**Option 3: Environment variables for infrastructure config, application.yml for operational parameters with hot-reload**

Infrastructure configuration (database URLs, Kafka bootstrap servers, topic names) via environment variables. Operational parameters (fraud thresholds, rate limits, timeout values) in `application.yml` files with Spring Boot's `@ConfigurationProperties` and `@RefreshScope`. Hot-reload via file watcher or `/actuator/refresh` endpoint.

Pros:
- No additional infrastructure beyond what Spring Boot provides
- Operational parameters can be changed at runtime (ConfigMap change + refresh)
- Infrastructure config is immutable (changes require redeployment — correct, because changing a database URL is a deployment event)
- Clean separation of concerns

Cons:
- Less centralized than a Config Server (each service has its own config)
- Shared parameters still need documentation discipline

**Recommendation: Option 3 — Split config with Spring Boot native hot-reload.**

Rationale: A Spring Cloud Config Server is unnecessary infrastructure for a portfolio project. Spring Boot's `@ConfigurationProperties` with `@RefreshScope` provides runtime changeability where needed. The key discipline: **shared parameters that must be consistent** (e.g., reservation timeout, SLA thresholds) are documented in a central `shared-config.md` file and each service's `application.yml` references the canonical value with a comment: `# Must match A2's expectation — see shared-config.md`.

For auditing configuration changes: Kubernetes ConfigMap changes are tracked via `kubectl` audit logging. Application-level refresh events are logged by the Starter's audit system.

**Categorization of parameters:**

| Category | Change mechanism | Examples |
|----------|-----------------|---------|
| **Infrastructure** (env vars) | Redeployment | DB URLs, Kafka bootstrap, topic names, Kafka SASL credentials |
| **Operational** (application.yml, hot-reloadable) | ConfigMap update + refresh | Fraud thresholds, rate limits, VoP cache TTL, SCA exemption limits, batch clearing schedule |
| **Structural** (code) | Code change + redeployment | Service boundaries, API contracts, Avro schemas, state machine transitions |

---

## I.1 — Configuration-Driven Behavior

### Decision I.1a: Configuration Validation

**Recommendation: Startup validation via `@ConfigurationProperties` with Bean Validation annotations.**

```java
@ConfigurationProperties(prefix = "payment.clearing.instant")
@Validated
public record InstantClearingConfig(
    @NotNull @Max(10000) Duration executionTimeout,   // default: 5s
    @NotNull @Max(10000) Duration hardLimitTimeout,    // default: 7s
    @NotNull @Min(0) @Max(1000000) BigDecimal maxAmount, // default: 100000 EUR
    @NotNull @Min(1) @Max(100) int maxRetries          // default: 3
) {}
```

Spring Boot validates `@ConfigurationProperties` beans at startup. If a required parameter is missing or out of range, the application fails to start with a clear error message. This prevents a misconfigured service from processing payments.

**Auditing configuration changes:** When `/actuator/refresh` is called, the Starter logs the before/after values of all changed `@ConfigurationProperties` to `audit.events`. This creates an audit trail of who changed what and when.

---

## I.2 — Adding New Services or Payment Schemes

### Decision I.2a: Extension Point Design

**Recommendation: Document extension points, don't build a plugin framework.**

The architecture document should explicitly describe how to extend the system in three common scenarios:

1. **Adding a new payment scheme** (e.g., SEPA Direct Debit): Add a new `schemeType` enum value to the `PaymentInstruction` Avro schema (backward-compatible: new optional field with default). A2 routes based on `schemeType` to the appropriate processing logic. New topic if needed for isolation.

2. **Adding a new service** (e.g., a reporting service): Subscribe to existing Kafka topics (`payment.status-updates`, `ledger.events`, `audit.events`). No modification to existing services required — this is the Kafka extensibility model.

3. **Adding a new fraud rule**: Add a new `VEL-XX` rule in A5's configuration. Rules are parameterized (threshold, window, weight) — adding a rule is a configuration change, not a code change.

No plugin API, no runtime class loading, no extension registry. These add complexity without benefit for a portfolio project. Extensibility comes from: (a) Kafka's pub/sub model, (b) Avro schema evolution, (c) configuration-driven behavior.

---

## Summary: Decision Index

| ID | Decision | Recommendation |
|----|----------|---------------|
| A.1a | Zone model | Three-zone with HMAC + mTLS internally |
| A.1b | Starter security default | Deny-all default, explicit opt-out |
| A.2a | Key management topology | Two-tier with `KeyProvider` abstraction |
| A.2b | Crypto-shredding key storage | Per-account keys in A1's PostgreSQL, encrypted with master key |
| A.3a | Threat modeling approach | Boundary STRIDE + attack trees for top 3 assets |
| A.3b | Scope exclusions | Document: physical security, social engineering, nation-state, L3/L4 DDoS, omnipotent insider |
| B.1a | State machine design | Formalized hybrid: A3/A4 own Phase 1, A2 owns Phase 2, A1+A3/A4 own Phase 3 |
| B.1b | Recovery on restart | Kafka replay + database recovery scan (belt and suspenders) |
| B.2a | Dual-write strategy per service | A1: Kafka-first. A2/A3/A4: Transactional Outbox. A5 instant: Kafka Streams internal. A6: N/A |
| B.2b | Outbox relay | In-process publish with outbox fallback |
| B.3a | Idempotency keys | Hierarchical: top-level transactionId + deterministic per-service operation IDs |
| B.4a | Investigation queues | Dedicated investigation topic + operator API in A2 |
| C.1a | Saga pattern | Orchestrated for instant path, choreographed for batch path |
| C.2a | Time budget | Static baselines with global 7-second hard-limit enforcement |
| C.3a | Batch/instant isolation | Separate JVM instances (Spring profiles), shared database with separate connection pools |
| D.1a | Schema evolution | Codified allowed/forbidden changes, Schema Registry enforcement in CI/CD |
| D.2a | API versioning | URL path versioning (`/v1/`), max 2 concurrent versions |
| E.1a | REST error format | RFC 9457 (Problem Details), auto-configured by Starter |
| E.1b | Kafka error classification | Three-category (retryable / DLQ / DLQ+alert), shared error handler in Starter |
| E.1c | Exception hierarchy | Two-layer: DomainException + InfrastructureException in shared domain model |
| E.2a | Distributed tracing | Micrometer Tracing auto-configured by Starter, Grafana Tempo backend |
| E.2b | SLA metric | `payment.instant.e2e.duration` histogram in A2 with p95/p99 alerts |
| F.1a | Crypto-shredding scope | Encrypt: name, address, description. Plaintext: IBAN, amounts, timestamps, IDs |
| F.1b | Post-shredding consumer behavior | `[REDACTED]` for deleted PII, balances remain correct, VoP returns ACCOUNT_CLOSED |
| F.2a | Audit tamper evidence | Hash chaining for portfolio, hash chaining + signatures + snapshots for production |
| G.1a | Testing pyramid | Four levels: unit, service integration, contract (Pact for REST + Kafka), end-to-end |
| G.2a | Demo script | Shell script with httpie/curl, timing assertions, reproducible seed data |
| H.1a | Deployment strategy | Rolling deployment with Cooperative Sticky Assignor + expand-contract migrations |
| H.2a | Configuration architecture | Env vars for infrastructure, application.yml with hot-reload for operational params |
| I.1a | Configuration validation | `@ConfigurationProperties` with Bean Validation, startup failure on invalid config |
| I.2a | Extension points | Documented extension patterns (no plugin framework) |
