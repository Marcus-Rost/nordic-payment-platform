# A2 — Payment Clearing & Settlement Engine: Architecture

*Version 1.0*
*Date: 2026-04-11*

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Architectural Principles](#2-architectural-principles)
3. [Deployment Architecture](#3-deployment-architecture)
4. [Instant Payment Path](#4-instant-payment-path)
5. [Batch Clearing Path](#5-batch-clearing-path)
6. [Liquidity Management](#6-liquidity-management)
7. [Idempotency and Duplicate Detection](#7-idempotency-and-duplicate-detection)
8. [ISO 20022 Message Handling](#8-iso-20022-message-handling)
9. [Data Architecture](#9-data-architecture)
10. [Crash Recovery](#10-crash-recovery)
11. [Security](#11-security)
12. [Observability](#12-observability)
13. [REST API](#13-rest-api)
14. [Testing Strategy](#14-testing-strategy)
15. [Architectural Decision Log](#15-architectural-decision-log)

---

## 1. Introduction

### 1.1 Purpose and Scope

This document describes the architecture of A2, the Payment Clearing & Settlement Engine — the central orchestrator of the Nordic Payment Processing Infrastructure. It translates the functional requirements (CLR-B01 through CLR-B15, CLR-I01 through CLR-I13, CLR-L01 through CLR-L06) and non-functional requirements (CLR-NF01 through CLR-NF06) into structural decisions that guide implementation.

This document is self-contained for A2. System-level decisions (trust zones, key management, schema evolution, API versioning) are adopted from the master architecture and cross-referenced, not repeated.

### 1.2 Role in the System

A2 is the central orchestrator during Phase 2 of the payment lifecycle. It receives payment instructions from A3 (Bill Payment / R2P) and A4 (PSD2 API Gateway) via Kafka, coordinates fraud/sanctions screening with A5, manages balance reservations in A1, executes clearing (batch netting or instant gross settlement), and publishes settlement events.

A2 is architecturally the most complex subsystem because it runs two fundamentally different processing models (batch and instant) and owns the time budget for the EPC 2025 instant payment SLA.

**Interaction summary:**

| Direction | Channel | Counterpart | Purpose |
|-----------|---------|-------------|---------|
| Inbound | Kafka | A3, A4 | Payment instructions (batch + instant topics) |
| Inbound | Kafka | A5 | Fraud/sanctions check responses |
| Inbound | Kafka | A1 | Balance reservation responses |
| Outbound | Kafka | A5 | Fraud/sanctions check requests |
| Outbound | Kafka | A1 | Balance reservation requests, settlement events |
| Outbound | Kafka | A3, A4 | Payment status updates |
| Outbound | Kafka | Audit consumer | Audit events |
| Outbound | Kafka | Monitoring | Investigation queue events |
| Inbound | REST | Operators, monitoring | Clearing cycle queries, liquidity positions, investigation |

### 1.3 Key Constraints

| Constraint | Source | Impact on A2 |
|-----------|--------|-------------|
| Instant: 5s execution / 7s hard limit / 9s confirmation | EPC 2025 SCT Inst rulebook v1.1 | Drives time budget, timeout strategy, deployment isolation |
| Sanctions screening cannot be skipped | EU AML, IPR Art. 5d | Sanctions timeout = REJECT. Late sanctions hit = automatic return |
| 24/7/365 availability (instant path) | EU IPR, OPS-08 | Drives process isolation, zero-downtime deployment, graceful shutdown |
| Zero payment loss | CLR-NF06 | Every instruction must reach SETTLED or REJECTED. No limbo states |
| Batch: 10,000 instructions in < 30s | CLR-NF01 | Drives netting algorithm efficiency, Spring Batch chunk sizing |
| Instant: ≥ 500 TPS | CLR-NF05 | Drives Kafka partitioning, connection pool sizing |
| Netting determinism | CLR-S07 | Same input → same output. BigDecimal, no side effects, sorted processing |
| ISO 20022 compliance | CLR-B02, CLR-B08 | JAXB from XSD, secure XML processing |

### 1.4 Related Documents

| Document | Location | Relationship |
|----------|----------|-------------|
| Requirements | `requirements/requirements.md` | What A2 must do |
| Use cases | `use_cases/UC01_batch_and_instant_clearing.md` | Scenarios with timing |
| Gap analysis | `extensions/gap_analysis.md` | Comparison with TIPS, RT1, NICS, P27 |
| Architecture draft | `architecture/architecture_draft.md` | Earlier draft confirming master decisions apply to A2 |
| Master architecture | `../../payment_processing/architecture/ARCHITECTURAL_DESIGN.md` | System-level design |
| Master decisions | `../../payment_processing/architecture/ARCHITECTURE_DECISIONS.md` | Options analysis for system-level choices |
| Navigation | `../../payment_processing/NAVIGATION.md` | Cross-references to all sub-projects |

---

## 2. Architectural Principles

These principles are inherited from the master architecture (section 2 of ARCHITECTURAL_DESIGN.md) and applied to A2. Where A2 adds specificity, it is noted.

### P1: Security by Construction

Sanctions screening is a mandatory pipeline stage — payments physically cannot bypass it. The Kafka Streams topology does not have a code path that skips the fraud check processor. The netting algorithm is a pure function with no external dependencies that could be manipulated. Settlement reports are signed.

### P2: Crash Recovery as a First-Class Concern

Every intermediate state in the instant payment state machine has a defined recovery path. The Kafka Streams state store survives restarts. Spring Batch's `ExecutionContext` is persisted to PostgreSQL. Balance reservations have a durable 30-second timeout. No in-memory-only state for in-flight payments.

### P3: Quality Over Complexity

The batch path uses Spring Batch — a mature, well-understood framework. The instant path uses Kafka Streams with a straightforward state machine pattern. The netting algorithm is a pure function. No custom orchestration framework, no workflow engine, no state machine library — the state transitions are explicit `if/else` logic in a single processor class.

### P4: Justified Separation

The batch and instant paths are separate JVM instances because they have fundamentally different availability requirements (24/7/365 vs. scheduled), failure modes (GC pause vs. I/O wait), and scaling profiles (fixed schedule vs. demand-driven). This is not premature optimization — it is driven by the constraint that a batch cycle must never violate the instant SLA.

### P5: Async for State Changes, Sync for Queries

All payment processing (instructions, fraud checks, balance reservations, settlement events, status updates) flows through Kafka — durable, auditable, recoverable. The REST API is read-only: clearing cycle queries, liquidity positions, investigation status.

---

## 3. Deployment Architecture

### 3.1 Two-Instance Model

A2 deploys as two separate JVM instances from the same codebase, activated by Spring profiles:

```
┌─────────────────────────────────────────────────────────┐
│  clearing-engine-instant (profile: instant)              │
│                                                          │
│  Kafka Streams topology                                  │
│  Consumes: payment.instant-instructions                  │
│            payment.instant-fraud-check-response           │
│            payment.balance-reservation-response           │
│  Produces: payment.instant-fraud-check-request           │
│            payment.balance-reservation                    │
│            payment.instant-settlement-events              │
│            payment.status-updates                         │
│  24/7/365, EPC 2025 SLA                                  │
│  Global 7-second hard-limit timer (punctuator)           │
│  PostgreSQL connection pool: min 10, max 20               │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  clearing-engine-batch (profile: batch)                  │
│                                                          │
│  Spring Batch job + Kafka consumers                      │
│  Consumes: payment.instructions                          │
│            payment.fraud-check-response                   │
│  Produces: payment.fraud-check-request                   │
│            payment.settlement-events                      │
│            payment.status-updates                         │
│  Scheduled (e.g., every 15 min or end-of-day)            │
│  Transactional Outbox for Kafka publishing               │
│  PostgreSQL connection pool: min 5, max 30                │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  PostgreSQL (shared)                                     │
│                                                          │
│  Tables: clearing_records, psp_settlement_accounts,      │
│          psp_liquidity_movements, payment_fingerprints,  │
│          outbox, processed_message_ids,                   │
│          BATCH_JOB_EXECUTION, BATCH_STEP_EXECUTION,      │
│          clearing_cycles, settlement_reports              │
│                                                          │
│  Port: 5442                                               │
└─────────────────────────────────────────────────────────┘
```

**Why separate JVMs:** Process isolation ensures that a batch OOM, GC pause, or I/O saturation cannot impact the instant SLA. The instant instance can be restarted independently of batch processing. They can scale independently (more instant replicas during peak hours).

*Master architecture reference: Decision C.3a — Separate JVM instances, shared database with connection pool separation.*

### 3.2 Shared Database with Connection Pool Separation

Both instances connect to the same PostgreSQL database. The instant instance uses a smaller, guaranteed connection pool (min 10, max 20). The batch instance uses a larger, burstable pool (min 5, max 30) for batch processing.

The instant instance's pool has `min = 10` to ensure connections are always available — no cold-start latency on the first payment after idle. The batch instance's pool has `min = 5` because batch cycles are scheduled, and connections can be acquired on demand.

Connection pool configuration is via HikariCP (Spring Boot default):

```yaml
# instant profile
spring.datasource.hikari.minimum-idle: 10
spring.datasource.hikari.maximum-pool-size: 20
spring.datasource.hikari.connection-timeout: 2000  # fail fast

# batch profile
spring.datasource.hikari.minimum-idle: 5
spring.datasource.hikari.maximum-pool-size: 30
spring.datasource.hikari.connection-timeout: 5000   # batch can wait longer
```

### 3.3 Port and Profile Configuration

| Instance | App Port | Management Port | Spring Profile |
|----------|----------|----------------|----------------|
| Instant | 8092 | 9092 | `instant` |
| Batch | 8192 | 9192 | `batch` |

Both instances share the database at port 5442. The Kafka bootstrap server is shared infrastructure at port 9092 (broker).

Note: The instant instance's management port (9092) is the same number as the Kafka broker port (9092). In Docker/Kubernetes, these are on different containers/pods with different hostnames, so there is no conflict (`clearing-engine-instant:9092` vs. `kafka:9092`). In a non-containerized development environment, one of these would need to be changed. The port assignments follow SUBSYSTEM_MAP.md from the master project.

---

## 4. Instant Payment Path

The instant path is the most architecturally demanding component of A2. It must process each payment individually (gross settlement), coordinate with two external services (A1 and A5) via Kafka, and guarantee a definitive outcome (SETTLED or REJECTED) within 7 seconds.

### 4.1 Kafka Streams Topology — Event-Driven State Machine

The instant path is implemented as a Kafka Streams application using the Processor API (not the DSL), because the multi-step orchestration requires explicit state management that the DSL's declarative operators do not naturally express.

The topology has three source processors (one per input topic) that all access a shared state store:

```
Sources:
  payment.instant-instructions           → InstructionProcessor
  payment.balance-reservation-response   → ReservationResponseProcessor
  payment.instant-fraud-check-response   → FraudResponseProcessor

State store: "pending-payments"
  Type: persistent KeyValueStore (RocksDB + changelog topic)
  Key:  transactionId (String)
  Value: InstantPaymentState (Avro-serialized)

Sinks:
  payment.balance-reservation            (requests to A1)
  payment.instant-fraud-check-request    (requests to A5)
  payment.instant-settlement-events      (settlement events to A1)
  payment.status-updates                 (status to A3/A4)
  payment.investigation                  (stuck payment alerts)
  audit.events                           (audit trail)

Punctuator: TimeoutPunctuator
  Schedule: every 500ms (wall-clock time)
  Action: scan state store for entries exceeding timeout thresholds
```

**InstructionProcessor** (triggered by each new payment instruction):
1. Validate the instruction against business rules (ISO 20022 pacs.008 semantics)
   **Amount Limit:** Instant payment instructions exceeding the SCT Inst maximum (EUR 100,000 default, configurable) are rejected with ISO 20022 reason code `AM02` (Not Allowed Amount).
2. Check for semantic duplicates (PostgreSQL query — see section 7.2)
3. If duplicate detected: insert `clearing_records` row with status `REJECTED`, reason `PROBABLE_DUPLICATE`. Forward rejection to `payment.status-updates`. End.
4. Insert `clearing_records` row in PostgreSQL with status `AWAITING_RESERVATION` and `received_at = now()`. This row serves as the durable record for crash recovery (section 10.1) — if the service crashes, the database scan finds it.
5. Store in state store: `{state: AWAITING_RESERVATION, receivedAt: now(), instruction: ...}`
6. Forward to sink `payment.balance-reservation`: reservation request with `correlationId = transactionId`

**ReservationResponseProcessor** (triggered by A1's response):
1. Look up `transactionId` in state store
2. If not found: late response for completed payment — log and discard (see section 4.6)
3. If reservation failed (insufficient balance, account frozen, account closed): update `clearing_records` status to `REJECTED`, update state store, forward rejection to `payment.status-updates`, remove from state store. End.
4. Update `clearing_records` status to `AWAITING_FRAUD`. Update state store to `AWAITING_FRAUD`.
5. Forward to sink `payment.instant-fraud-check-request`: fraud check request with `correlationId = transactionId`

**FraudResponseProcessor** (triggered by A5's response):
1. Look up `transactionId` in state store
2. If not found: late response — handle per section 4.6
3. Evaluate response:
   - `sanctions = HIT` → REJECT, release reservation (forward to `payment.balance-reservation` with release command), update `clearing_records` to REJECTED, forward rejection to `payment.status-updates`, remove from state store
   - `sanctions = UNAVAILABLE` → REJECT (same as HIT — sanctions cannot be skipped)
   - `sanctions = CLEAR, fraud = BLOCK` → REJECT, release reservation, update `clearing_records`, forward rejection
   - `sanctions = CLEAR, fraud = PASS or UNKNOWN` → proceed to settlement
   - `sanctions = CLEAR, decision = TIMEOUT` → treat as `fraud = UNKNOWN`, proceed to settlement with post-settlement review flag (A5 confirmed sanctions clear but could not complete fraud scoring)
   - `decision = TIMEOUT` with `sanctions` != CLEAR → REJECT (sanctions status unknown, cannot skip — same as UNAVAILABLE)
4. Store A5's screening result in `clearing_records.screening_result` (JSONB)
5. Check PSP liquidity (PostgreSQL query — see section 6.2)
6. If insufficient liquidity: REJECT, release reservation, update `clearing_records`, forward rejection
7. Update state store to `SETTLING` and `clearing_records` to `SETTLING`. This persisted state ensures that if A2 crashes during settlement, the database scan (section 10.1) detects the in-progress settlement.
8. Execute gross settlement:
   - Update PSP liquidity (debit originator, credit beneficiary — section 6.2)
   - Insert `psp_liquidity_movements` audit rows for both debit and credit
   - Update `clearing_records` to `SETTLED` with `settlement_ref` and `settled_at`
   - Forward settlement event to `payment.instant-settlement-events`
   - Forward confirmation (reservation confirm) to `payment.balance-reservation`
   - Forward status SETTLED to `payment.status-updates`
9. Remove from state store

**Co-partitioning requirement:** Source topics `instant-instructions`, `balance-reservation-response`, and `instant-fraud-check-response` must all be keyed by `transactionId` to ensure co-partitioning with the Kafka Streams state store. A3/A4 and A5 partition by `transactionId` directly. **Exception:** The `payment.balance-reservation` request topic uses `accountId` as the Kafka key (required by A1 for per-account serialization — see INTERFACE_CONTRACTS.md Section 2.3). A2 includes `transactionId` in the message payload for correlation. A1's response on `payment.balance-reservation-response` is keyed by `transactionId`, restoring co-partitioning for A2's state store lookup.

### 4.2 Payment State Machine (Phase 2)

```
Persisted states are in CAPS. Decision branches are in lowercase.

              validate + dedup (transient — before first state store write)
                           │
                    ┌──────┴──────┐
                    │             │
              validation OK   → REJECTED (SCHEMA_VALIDATION_FAILED,
                    │                      PROBABLE_DUPLICATE, AMOUNT_EXCEEDED)
                    │
             AWAITING_RESERVATION ← first persisted state
                    │
              ┌─────┴─────┐
              │            │
         A1: OK        A1: failed → REJECTED (INSUFFICIENT_FUNDS,
              │                      ACCOUNT_FROZEN, ACCOUNT_CLOSED)
              │
          AWAITING_FRAUD
              │
        ┌─────┴──────────────────┐
        │              │         │
   A5: sanctions     A5: no   A5: sanctions HIT
   CLEAR +           response   or UNAVAILABLE
   fraud PASS        (punctuator  → REJECTED + release reservation
   or UNKNOWN        timeout)
        │              │
        │         → REJECTED + release reservation
        │           (SANCTIONS_UNAVAILABLE)
        │
   liquidity check (transient)
        │
   ┌────┴────┐
   │         │
   OK     → REJECTED (INSUFFICIENT_PSP_LIQUIDITY) + release reservation
   │
   SETTLING ← persisted before settlement begins
   │
   settlement execution (PostgreSQL + Kafka publishes)
   │
   SETTLED ← terminal state
   │
   remove from state store
```

**Persisted states in the state store:** `AWAITING_RESERVATION`, `AWAITING_FRAUD`, `SETTLING`, `SETTLED`, `REJECTED`. Only these values appear in the state store. Decision branches (validation, liquidity check) are transient — they execute within a single processor invocation and result in a state store write to one of the persisted states.

Every terminal state (SETTLED or REJECTED with any reason) results in:
- A definitive status published to `payment.status-updates`
- Removal from the state store (the payment is no longer in-flight)
- If a reservation was made and the payment is rejected: a release command published to A1

There is no state from which the payment cannot reach a terminal state. The timeout punctuator (section 4.4) ensures that even if a downstream service never responds, the payment reaches REJECTED within 7 seconds.

### 4.3 Request-Response Over Kafka

A2 needs request-response interactions with A1 (balance reservation) and A5 (fraud check). Kafka is pub/sub, not request-response. A2 implements request-response using the state store as correlation mechanism:

1. A2 publishes a request to the outbound topic with the `transactionId` as the Kafka message key and as a `correlationId` field in the payload
2. A2 stores the payment's state in the `pending-payments` state store, keyed by `transactionId`
3. The responding service (A1 or A5) publishes its response to the response topic, using the same `transactionId` as the message key and including the `correlationId` in the payload
4. A2's response processor consumes the response, looks up the `transactionId` in the state store, and advances the state machine

This is not a Kafka Streams join operation (KStream-KStream join). It is a state-store lookup on each response event. The "correlation" is the state store entry that links the request to the expected response.

**Why not use KStream.join():** The built-in join operators are designed for correlating records from two streams that arrive within a time window. Our pattern is sequential — the response can only arrive after the request, and we need to take different actions depending on the current state (AWAITING_RESERVATION vs. AWAITING_FRAUD). A state-store lookup with explicit state transitions is more natural and more readable for this orchestration pattern.

**Kafka topics for request-response:**

| Request topic | Response topic | Responder |
|--------------|---------------|-----------|
| `payment.balance-reservation` | `payment.balance-reservation-response` | A1 |
| `payment.instant-fraud-check-request` | `payment.instant-fraud-check-response` | A5 |

Note: The canonical interface contracts for all Kafka topics, including `payment.balance-reservation-response`, are defined in `payment_processing/architecture/INTERFACE_CONTRACTS.md`.

### 4.4 Timeout Strategy

**Per-stage static timeouts + global hard-limit punctuator.**

*Master architecture reference: Decision C.2a — Static baselines with global hard-limit enforcement.*

Individual stages do not have explicit timers in the Kafka Streams topology. Instead, a single `TimeoutPunctuator` runs every 500ms and scans the state store for entries that have exceeded their expected duration:

```java
class TimeoutPunctuator implements Punctuator {
    
    static final Duration HARD_LIMIT = Duration.ofSeconds(7);
    static final Duration RESERVATION_TIMEOUT = Duration.ofSeconds(2);
    static final Duration FRAUD_CHECK_TIMEOUT = Duration.ofMillis(800);
    
    @Override
    public void punctuate(long timestamp) {
        try (var iterator = stateStore.all()) {
            while (iterator.hasNext()) {
                var entry = iterator.next();
                InstantPaymentState state = entry.value;
                Duration elapsed = Duration.between(state.receivedAt(), Instant.now());
                
                if (elapsed.compareTo(HARD_LIMIT) > 0) {
                    // Global hard limit — REJECT unconditionally
                    rejectWithTimeout(entry.key, state);
                } else if (state.phase() == AWAITING_RESERVATION 
                           && elapsed.compareTo(RESERVATION_TIMEOUT) > 0) {
                    // A1 hasn't responded — REJECT
                    rejectWithTimeout(entry.key, state);
                } else if (state.phase() == AWAITING_FRAUD 
                           && Duration.between(state.fraudRequestedAt(), Instant.now())
                                      .compareTo(FRAUD_CHECK_TIMEOUT) > 0) {
                    // A5 hasn't responded at all — sanctions status unknown — REJECT
                    rejectAndReleaseReservation(entry.key, state, "SANCTIONS_UNAVAILABLE");
                }
            }
        }
    }
}
```

**Clock Assumption — Implementation Note:** The elapsed time calculation for timeout detection should use monotonic time (`System.nanoTime()` or `Duration` based on monotonic source) rather than wall-clock time (`Instant.now()`). Wall-clock time is subject to NTP corrections — a forward jump can prematurely reject in-flight payments, a backward jump can delay timeout detection. For the Kafka Streams punctuator specifically, `PunctuationType.WALL_CLOCK_TIME` is acceptable if the elapsed time comparison uses `System.nanoTime()` internally (capturing nanoTime at message receipt, computing elapsed at punctuation). Alternatively, `PunctuationType.STREAM_TIME` (based on record timestamps) provides monotonic behavior within a partition but may lag behind real time during consumer catch-up. Recommendation: use `System.nanoTime()` for the 7-second hard-limit check; use `PunctuationType.WALL_CLOCK_TIME` for the punctuator schedule. NTP synchronization (chrony or ntpd) is an infrastructure requirement for all nodes.

**Fraud timeout policy:** When the punctuator fires for an `AWAITING_FRAUD` entry, it means A5 has not delivered any response within the timeout. Since A2 cannot confirm that sanctions screening completed, the payment is **always REJECTED** (sanctions cannot be skipped — CLR-I07).

The "auto-pass fraud" scenario does NOT happen in the punctuator. It happens in the `FraudResponseProcessor` when A5 delivers a response with `{sanctions: CLEAR, fraudScore: UNKNOWN}` — meaning A5 confirmed sanctions cleared but fraud scoring was incomplete. That response is consumed normally by the processor, which sees `sanctions = CLEAR, fraud = UNKNOWN` and proceeds to settlement with a post-settlement review flag. The punctuator is the fallback for when A5 sends nothing at all.

**Time budget breakdown:**

```
EPC 2025 SCT Inst Rulebook v1.1:
  Execution target:       5,000ms
  Hard limit:             7,000ms
  Confirmation:           9,000ms

Phase 1 (A3/A4, before A2 — not in A2's budget):
  VoP check (A6):                   ≤ 500ms
  Validation + Kafka publish:       ≤ 300ms

Phase 2 (A2 orchestration):
  Kafka consume + validate:         ≤ 200ms
  Semantic duplicate check:         ≤  50ms
  Balance reservation (A1 r/t):    ≤ 200ms
  Fraud + sanctions (A5 r/t):     ≤ 800ms  (500ms target + 300ms margin)
  Liquidity check:                 ≤  50ms
  Settlement logic + publish:      ≤ 200ms
  ──────────────────────────────────────────
  Phase 2 subtotal:                ≤ 1,500ms

Network/Kafka overhead:             ≤ 500ms
  ──────────────────────────────────────────
  Total execution path:            ≤ 2,800ms  (well within 5,000ms target)
  Safety margin:                    2,200ms → 7,000ms hard limit
```

Note: This breakdown differs slightly from the master architecture (ARCHITECTURAL_DESIGN.md section 9.1), which shows Phase 2 at ≤ 1,800ms and total at ≤ 3,000ms. The differences: this breakdown adds explicit steps for semantic duplicate check (50ms) and liquidity check (50ms) that were not broken out in the master, and consolidates the status publish step into settlement. Both totals are well within the 5,000ms execution target — the difference is in granularity, not in the architectural conclusion.

**Punctuator precision:** With a 500ms punctuation interval, the worst-case detection delay is 500ms (a payment that exceeded the hard limit 1ms after the last punctuation runs). To ensure the REJECT always fires before 7 actual seconds have elapsed from the payer's perspective, the hard limit threshold in the punctuator is set conservatively — the Phase 1 time consumed by A3/A4 is not included in A2's 7-second measurement, because A2 measures from its own consumption timestamp. The EPC 9-second confirmation window provides additional margin for the pacs.002 delivery after rejection.

### 4.5 Orchestrated Saga — Compensation on Failure

*Master architecture reference: Decision C.1a — Orchestrated Saga for instant path.*

A2 manages compensation explicitly for the instant path. When a payment is rejected after a balance reservation has been made, A2 must release the reservation before publishing the rejection.

**Compensation actions by state:**

| Current state | Failure | Compensation |
|--------------|---------|-------------|
| AWAITING_RESERVATION | Validation failure, duplicate detected | None — no reservation made |
| AWAITING_RESERVATION | Reservation timeout | Publish release command (idempotent — safe even if A1 never created the reservation). A1 either releases the held funds or ignores the no-op. This is strictly better than relying solely on A1's 30-second timeout (RES-01) because it frees funds immediately rather than after up to 30 seconds. |
| AWAITING_FRAUD | Fraud/sanctions rejection | Release reservation: publish release command to `payment.balance-reservation` |
| AWAITING_FRAUD | A5 timeout (no response received — sanctions status unknown) | Release reservation |
| AWAITING_FRAUD → settlement | Liquidity insufficient | Release reservation |
| SETTLING | Crash during settlement (detected by DB scan on restart) | Check whether settlement events were published (reconciliation). If not: release reservation, REJECT. If yes: complete the PostgreSQL update to match Kafka state |
| SETTLED | Late SANCTIONS_HIT | Initiate pacs.004 return (section 4.6) |

The release command is a message published to `payment.balance-reservation` with `operation: RELEASE` and the `transactionId`. A1 processes it by releasing the held amount back to available balance.

**Ordering guarantee:** The release is published to Kafka before the REJECTED status update. Since both go through Kafka (durable), the release cannot be lost even if A2 crashes immediately after publishing. A1 processes the release on its own consumer timeline.

**Double-release safety:** A1's release operation is idempotent — releasing an already-released or already-confirmed reservation is a no-op. This means A2 can safely publish a release even if unsure whether the reservation was actually made (e.g., if A2 timed out waiting for A1's response — A1 may or may not have received the request).

### 4.6 Late-Arriving Fraud Responses

After a payment reaches a terminal state (SETTLED or REJECTED) and is removed from the state store, A5 may still publish a response. The FraudResponseProcessor handles this:

1. Look up `transactionId` in the state store
2. **Not found** → this is a late response. Query PostgreSQL `clearing_records` table.
3. If the payment was REJECTED: log the late response at INFO level. No action needed — the payment was already rejected.
4. If the payment was SETTLED: evaluate the late response.

**Late response evaluation for settled payments:**

| Late response content | Action |
|----------------------|--------|
| `sanctions: CLEAR, fraud: PASS` | No action — result confirms the settlement was correct |
| `sanctions: CLEAR, fraud: BLOCK` | Create review alert. Upgrade the existing post-settlement review (if fraud was auto-passed). Case management team decides on return |
| `sanctions: HIT` | **SETTLED_BEFORE_SCREENING alert** (highest severity). Automatically initiate pacs.004 payment return. Publish to `payment.investigation`. Compliance notification |

**pacs.004 return flow for late SANCTIONS_HIT:**

```
A2 detects late SANCTIONS_HIT for settled payment
  → A2 creates clearing_record with type = RETURN, referencing original transactionId
  → A2 publishes return settlement event to payment.instant-settlement-events:
      {operation: RETURN, originalTransactionId, amount, reason: SANCTIONS_LATE_HIT}
  → A1 processes: credit debtor account (refund), debit beneficiary account
  → A2 publishes status update: RETURN_INITIATED
  → A2 updates liquidity: reverse the PSP debit/credit
  → If A1 reports beneficiary insufficient funds: RETURN_FAILED alert, manual intervention
```

**A5 response model:** A5 guarantees a response within its own internal timeout budget. If sanctions screening completes (fast, < 50ms per A5-NF02) but fraud scoring is incomplete, A5 publishes `{sanctions: CLEAR, fraudScore: UNKNOWN}`. This allows A2 to distinguish "sanctions confirmed but fraud unknown" from "A5 completely unresponsive." A5 may continue scoring internally and publish a supplementary response with the actual fraud score — this is the "late fraud response" case handled above.

### 4.7 Additional Instant Path Capabilities (Should-level)

Three Should-level requirements are not part of the core instant path but fit naturally into the architecture:

**CLR-I11 — pacs.028 Payment Status Request:**

A pacs.028 allows an originating PSP to query the status of an in-flight payment. In our architecture, this is handled in two ways:
- **REST:** The investigation API (section 13.3) provides `GET /api/v1/admin/investigations/{transactionId}` which returns the current state, timestamps, and screening result. This serves internal operators and monitoring.
- **Kafka (enhancement):** A dedicated consumer on a `payment.status-request` topic could accept pacs.028 messages, look up the transactionId in the state store (for in-flight) or clearing_records (for completed), and publish a pacs.002 status response. This mirrors the request-response pattern already used for fraud checks.

For the portfolio implementation, the REST endpoint is sufficient. The Kafka-based pacs.028 handler is documented as an extension point.

**CLR-I12 — pacs.004 Payment Return (inbound):**

Section 4.6 covers A2 *initiating* returns (for late sanctions hits). CLR-I12 additionally requires handling *incoming* return requests — where an originating PSP requests that a previously settled payment be reversed.

Architectural fit:
- A2 consumes pacs.004 return requests from a `payment.return-requests` topic (or via REST API)
- A2 validates the return: the original payment must exist, must be SETTLED, and the return must be within the allowed return window
- A2 publishes a return settlement event to `payment.instant-settlement-events` (same mechanism as the late-sanctions return in section 4.6)
- A1 processes the reversal: credit debtor, debit beneficiary
- A2 publishes status update: `RETURN_INITIATED` → `RETURNED`
- If the beneficiary has insufficient funds: `RETURN_FAILED` alert

The return flow reuses the same pacs.004 mechanism from section 4.6 — the only difference is the trigger (external request vs. internal late-sanctions detection).

**CLR-I13 — Settlement Finality Event:**

Settlement finality is a legally distinct concept from settlement completion. A `SETTLED` status means A2 has executed the settlement. A `SETTLEMENT_FINAL` event means the settlement is legally irrevocable — no recall or return can undo it.

For instant payments, settlement finality is immediate (the EPC SCT Inst rulebook defines finality at the moment of credit to the beneficiary PSP). For batch payments, finality occurs when the clearing cycle closes and all net positions are funded.

Architectural fit:
- After publishing the `SETTLED` status update, A2 publishes a distinct `SETTLEMENT_FINAL` event to `payment.status-updates` (with a different event type, not a status change)
- For instant: the finality event is published immediately after settlement (same processing step, separate message)
- For batch: the finality event is published after the clearing cycle reaches `COMPLETED` status (Step 3 completion)
- A3/A4 can relay the finality event to the payer/merchant as a separate notification

The gap analysis (extensions/gap_analysis.md) identified this as a key gap vs. TIPS. Adding it as a separate event type on the existing `payment.status-updates` topic requires no new infrastructure.

---

## 5. Batch Clearing Path

The batch path collects payment instructions over a configurable window, runs them through fraud/sanctions screening, calculates multilateral net positions, verifies liquidity, and settles — producing settlement events and a camt.053 report.

### 5.1 Spring Batch Job Architecture

The batch clearing cycle is a Spring Batch job with four steps:

```
┌──────────────────────────────────────────────────┐
│  ClearingCycleJob                                │
│                                                  │
│  Step 1: CollectAndValidate                      │
│    Reader:  Kafka consumer (payment.instructions)│
│    Processor: ISO 20022 validation               │
│    Writer:  PostgreSQL (clearing_records)         │
│    Chunk size: 100                               │
│                                                  │
│  Step 2: FraudScreening (TaskletStep)             │
│    Publish all requests to A5 via outbox          │
│    Background consumer collects responses         │
│    Tasklet polls until all responses or timeout   │
│    Timeout: 30 seconds (design decision)          │
│                                                  │
│  Step 3: NettingAndSettlement                    │
│    Reader:  PostgreSQL (screened, passed records) │
│    Processor: Netting algorithm (section 5.3)    │
│    Writer:  Settlement events (via outbox),       │
│             liquidity updates, cycle record       │
│                                                  │
│  Step 4: ReportGeneration                        │
│    Reader:  Clearing cycle result                │
│    Processor: camt.053 XML generation            │
│    Writer:  PostgreSQL (settlement_reports)       │
└──────────────────────────────────────────────────┘
```

**Triggering:** The job is triggered by Spring Scheduler at configurable intervals (default: every 15 minutes). The trigger checks if there are uncleared instructions before starting a job — no empty cycles.

**Chunk processing:** Step 1 uses chunk-oriented processing (reader-processor-writer) with a chunk size of 100. Each chunk is a transaction — if A2 crashes mid-step, the restart resumes from the last committed chunk (see section 10.3).

### 5.2 Clearing Cycle Lifecycle

```
CREATED → COLLECTING → SCREENING → NETTING → SETTLING → COMPLETED
                                                    └──→ FAILED (with reason)
```

| State | Entered when | Exit condition |
|-------|-------------|---------------|
| CREATED | Scheduler fires, creates cycle record | Immediately transitions to COLLECTING |
| COLLECTING | Step 1 begins | All instructions in window consumed and validated |
| SCREENING | Step 2 begins | All A5 responses received (or timed out) |
| NETTING | Step 3 begins | Net positions calculated, postcondition verified |
| SETTLING | Liquidity verified, settlement events being published | All events published, liquidity updated |
| COMPLETED | Step 4 complete, report generated | Terminal state |
| FAILED | Any step fails irrecoverably | Terminal state. Reason recorded: `INTEGRITY_CHECK_FAILED`, `LIQUIDITY_INSUFFICIENT`, `SCREENING_TIMEOUT`, `SYSTEM_ERROR` |

The cycle record is persisted to PostgreSQL (`clearing_cycles` table) and updated at each state transition. This provides an auditable lifecycle.

### 5.3 Netting Algorithm

The netting algorithm is a **pure function** that takes a list of cleared payment instructions and produces a bilateral matrix with derived net positions.

**Input:** `List<ClearedInstruction>` — instructions that passed validation and fraud/sanctions screening. Each has: `debtorPsp`, `creditorPsp`, `amount` (BigDecimal), `currency`.

**Preconditions** (validated before netting, exception thrown if violated):
- All amounts are positive
- No self-transfers (debtorPsp ≠ creditorPsp)
- All instructions have the same currency (one netting cycle per currency)

**Algorithm:**

```java
public class NettingEngine {

    /**
     * Pure function. No injected dependencies. No side effects.
     * Input: list of cleared instructions.
     * Output: netting result with bilateral matrix and net positions.
     */
    public NettingResult calculate(List<ClearedInstruction> instructions) {
        // 1. Build bilateral matrix
        // Key: ordered pair (min(debtor,creditor), max(debtor,creditor))
        // Value: gross amount in each direction
        Map<PspPair, BilateralPosition> matrix = new TreeMap<>();
        
        for (ClearedInstruction instruction : instructions) {
            PspPair pair = PspPair.of(instruction.debtorPsp(), instruction.creditorPsp());
            matrix.computeIfAbsent(pair, BilateralPosition::new)
                  .addFlow(instruction.debtorPsp(), instruction.creditorPsp(), 
                           instruction.amount());
        }
        
        // 2. Calculate net positions from matrix
        Map<PspId, NetPosition> netPositions = new TreeMap<>();
        for (BilateralPosition bilateral : matrix.values()) {
            bilateral.contributeToNetPositions(netPositions);
        }
        
        // 3. Postcondition: net positions sum to zero
        BigDecimal sum = netPositions.values().stream()
            .map(NetPosition::netAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        if (sum.compareTo(BigDecimal.ZERO) != 0) {
            throw new NettingIntegrityException(
                "Net positions sum to " + sum + ", expected 0. " +
                "Instruction count: " + instructions.size());
        }
        
        return new NettingResult(instructions.size(), matrix, netPositions);
    }
}
```

**Data structures:**

- `PspPair`: ordered pair of PSP IDs (always `min, max` to ensure consistency). Implements `Comparable` for deterministic `TreeMap` ordering.
- `BilateralPosition`: holds gross flows in both directions between two PSPs. Fields: `pspA`, `pspB`, `aToB` (BigDecimal), `bToA` (BigDecimal).
- `NetPosition`: per-PSP summary. Fields: `pspId`, `totalDebits` (BigDecimal), `totalCredits` (BigDecimal), `netAmount` (credits - debits).
- `NettingResult`: immutable value object containing the instruction count, bilateral matrix, and net positions.

**Determinism guarantees:**
- `BigDecimal` for all arithmetic — no floating-point imprecision
- `TreeMap` for all maps — deterministic iteration order (sorted by key)
- No side effects — the `NettingEngine` class has no fields, no injected dependencies, no I/O
- The same `List<ClearedInstruction>` produces the same `NettingResult` regardless of processing order (addition is commutative)

**Purity enforcement (ArchUnit):**

```java
@ArchTest
void nettingEngine_hasNoDependencies(JavaClasses classes) {
    noClasses()
        .that().resideInAPackage("..netting..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("..repository..", "..kafka..", "..service..", 
                            "..spring..", "..javax.inject..")
        .check(classes);
}
```

### 5.4 Batch Fraud/Sanctions Screening Flow

Step 2 of the batch job sends all validated instructions to A5 for screening. Unlike the instant path (one-at-a-time via Kafka Streams), the batch path sends all instructions and waits for all responses.

Step 2 is implemented as a **TaskletStep** (not chunk-oriented), because the processing pattern is inherently asynchronous: publish all requests, then wait for all responses. This does not fit the chunk reader-processor-writer model.

**Mechanism:**
1. Read all unscreened instructions from `clearing_records` (status: `COLLECTING`)
2. For each instruction, publish a fraud check request to `payment.fraud-check-request` (batch topic, separate from instant) via the outbox
3. A background `@KafkaListener` consumer in the batch instance listens on `payment.fraud-check-response`. As responses arrive, it updates the corresponding `clearing_records` row with the screening result (PASS, BLOCK, MANUAL_REVIEW, SANCTIONS_HOLD)
4. The tasklet polls `clearing_records` at short intervals (e.g., every 500ms), counting how many instructions have received responses. It blocks until either all responses are received or the screening timeout (30 seconds, a design decision — not a requirement-specified value) expires
5. When the wait completes, the tasklet reads the final screening results: instructions with PASS proceed to netting. Instructions with BLOCK or SANCTIONS_HOLD are set to REJECTED. Instructions with MANUAL_REVIEW are set to HELD (excluded from netting, pending analyst action)

**Timeout handling:** If A5 does not respond for some instructions within 30 seconds, those instructions are excluded from the current cycle and their `clearing_records` status is set to `SCREENING_TIMEOUT`.

**Recovery of timed-out instructions:** These instructions have already been consumed from Kafka (the offset has moved past them), so they cannot be re-read from the Kafka topic. Instead, Step 1 of each new clearing cycle queries `clearing_records` for instructions with status `SCREENING_TIMEOUT` from previous cycles and includes them alongside newly consumed instructions. This ensures timed-out instructions are automatically retried in the next cycle without requiring resubmission by the originator.

```java
// Step 1 reader: Kafka + PostgreSQL recovery
List<ClearingRecord> collectInstructions() {
    List<ClearingRecord> fromKafka = consumeFromKafka();
    List<ClearingRecord> fromPreviousCycles = clearingRecordRepository
        .findByStatus(SCREENING_TIMEOUT);
    // Reset their status so they re-enter the pipeline
    fromPreviousCycles.forEach(r -> r.setStatus(COLLECTING));
    return concat(fromKafka, fromPreviousCycles);
}
```

If an instruction times out in multiple consecutive cycles, it should be escalated after a configurable retry count (default: 3 cycles) — status set to `SCREENING_FAILED`, alert raised, and the originator notified via `payment.status-updates`.

### 5.5 Settlement and Report Generation

After netting:

1. **Liquidity verification:** For each net debtor PSP, verify sufficient balance. All checks and updates in a single transaction (see section 6.3). If any PSP has insufficient funds, the entire cycle fails (status: `LIQUIDITY_INSUFFICIENT`).

2. **Settlement event publishing:** For each settled instruction, write a settlement event to the outbox table (same transaction as the liquidity update). The outbox relay publishes to `payment.settlement-events`. Each event includes:
   - `settlementReference` (UUID — unique per settled payment)
   - `clearingCycleId`
   - `originalEndToEndId` (from the pacs.008)
   - `settlementTimestamp`

3. **Status updates:** For each instruction, write a status update to the outbox. Statuses: SETTLED, REJECTED (with reason), HELD (pending manual review).

4. **camt.053 report generation:** The `SettlementReportGenerator` (section 8.5) takes the `NettingResult` and produces a camt.053 XML document. The report is stored in PostgreSQL (`settlement_reports` table) and available via the REST API.

### 5.6 Restartability

*Requirement: CLR-RES01.*

Spring Batch persists job and step execution state to PostgreSQL (`BATCH_JOB_EXECUTION`, `BATCH_STEP_EXECUTION`, `BATCH_STEP_EXECUTION_CONTEXT` tables). If A2 crashes mid-batch:

- The job is in state `STARTED` in the database
- On restart, Spring Batch detects the incomplete job and resumes from the last committed chunk
- Step 1 (collect and validate): resumes from the last committed chunk offset. Instructions already written to `clearing_records` are not re-processed (idempotent by primary key)
- Step 2 (screening): re-sends fraud check requests for unscreened instructions. A5 deduplicates by the deterministic `fraudCheckRequestId` (section 7.3)
- Step 3 (netting and settlement): if the crash was during settlement, the outbox ensures no events are lost — the outbox relay re-publishes unpublished events on restart
- Step 4 (report generation): idempotent — regenerating the same report produces the same output

---

## 6. Liquidity Management

### 6.1 PSP Settlement Account Model

Each participating PSP has a pre-funded settlement account per currency. The model represents settlement in commercial bank money (similar to RT1/EBA Clearing, not central bank money like TIPS).

```sql
CREATE TABLE psp_settlement_accounts (
    psp_id           VARCHAR(36)    NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    balance          DECIMAL(18,2)  NOT NULL CHECK (balance >= 0),
    initial_funding  DECIMAL(18,2)  NOT NULL,
    low_threshold    DECIMAL(18,2)  NOT NULL,
    updated_at       TIMESTAMP      NOT NULL DEFAULT NOW(),
    PRIMARY KEY (psp_id, currency)
);

CREATE TABLE psp_liquidity_movements (
    id                 UUID           PRIMARY KEY,
    psp_id             VARCHAR(36)    NOT NULL,
    currency           VARCHAR(3)     NOT NULL,
    amount             DECIMAL(18,2)  NOT NULL,
    balance_after      DECIMAL(18,2)  NOT NULL,
    movement_type      VARCHAR(30)    NOT NULL,
    reference_id       VARCHAR(36),
    created_at         TIMESTAMP      NOT NULL DEFAULT NOW()
);
```

The `CHECK (balance >= 0)` constraint is a database-level safety net — even if application logic has a bug, PostgreSQL rejects a negative balance.

The `psp_liquidity_movements` table is a complete audit trail. Movement types: `INSTANT_DEBIT`, `INSTANT_CREDIT`, `BATCH_NET_DEBIT`, `BATCH_NET_CREDIT`, `FUNDING`, `WITHDRAWAL`.

### 6.2 Instant Path: Atomic Conditional Update

For each instant payment, A2 debits the originator PSP and credits the beneficiary PSP:

```sql
BEGIN;

-- Debit: check and deduct atomically
UPDATE psp_settlement_accounts
SET balance = balance - :amount, updated_at = NOW()
WHERE psp_id = :debtorPsp AND currency = :currency AND balance >= :amount;
-- affected rows = 0 → insufficient liquidity → ROLLBACK and REJECT

-- Credit: always succeeds
UPDATE psp_settlement_accounts
SET balance = balance + :amount, updated_at = NOW()
WHERE psp_id = :creditorPsp AND currency = :currency;

-- Audit trail: record both movements
INSERT INTO psp_liquidity_movements (id, psp_id, currency, amount, balance_after, 
                                     movement_type, reference_id)
VALUES (:uuid1, :debtorPsp, :currency, -:amount, 
        (SELECT balance FROM psp_settlement_accounts WHERE psp_id = :debtorPsp AND currency = :currency),
        'INSTANT_DEBIT', :transactionId);

INSERT INTO psp_liquidity_movements (id, psp_id, currency, amount, balance_after,
                                     movement_type, reference_id)
VALUES (:uuid2, :creditorPsp, :currency, :amount,
        (SELECT balance FROM psp_settlement_accounts WHERE psp_id = :creditorPsp AND currency = :currency),
        'INSTANT_CREDIT', :transactionId);

COMMIT;
```

All four statements execute in a single database transaction. The atomic `UPDATE ... WHERE balance >= :amount` is both the check and the debit — no TOCTOU gap. The liquidity movement inserts provide a complete audit trail (section 6.1).

### 6.3 Batch Path: Single-Transaction Net Settlement

After netting, all net position updates execute in a single transaction:

```java
@Transactional
void settleNetPositions(Map<PspId, BigDecimal> netPositions, String currency) {
    List<PspId> orderedPsps = new ArrayList<>(netPositions.keySet());
    Collections.sort(orderedPsps);  // consistent lock ordering
    
    for (PspId psp : orderedPsps) {
        BigDecimal netAmount = netPositions.get(psp);
        if (netAmount.signum() < 0) {
            int rows = debitPsp(psp, currency, netAmount.abs());
            if (rows == 0) {
                throw new InsufficientLiquidityException(psp, currency, netAmount.abs());
            }
        } else if (netAmount.signum() > 0) {
            creditPsp(psp, currency, netAmount);
        }
    }
}
```

If any net debtor has insufficient funds, the `InsufficientLiquidityException` triggers a transaction rollback — no PSP positions are modified. The cycle is marked FAILED with reason `LIQUIDITY_INSUFFICIENT` and the specific PSP identified. Operations is alerted.

### 6.4 Deadlock Prevention

Both the instant path and the batch path always update PSP rows in ascending PSP ID order. This prevents deadlocks because all transactions acquire row locks in the same global order.

**Instant path:** The `settleLiquidity` method determines which PSP ID is lower and processes that row first, regardless of whether it is the debtor or creditor.

```java
void settleLiquidity(PspId debtorPsp, PspId creditorPsp, 
                     BigDecimal amount, String currency) {
    PspId first  = debtorPsp.compareTo(creditorPsp) < 0 ? debtorPsp : creditorPsp;
    PspId second = debtorPsp.compareTo(creditorPsp) < 0 ? creditorPsp : debtorPsp;
    
    if (first.equals(debtorPsp)) {
        debitPsp(first, currency, amount);
        creditPsp(second, currency, amount);
    } else {
        creditPsp(first, currency, amount);
        debitPsp(second, currency, amount);
    }
}
```

**Batch path:** The sorted iteration (`Collections.sort(orderedPsps)`) processes all PSPs in ascending order.

### 6.5 Batch Liquidity Failure Handling

If a batch net debtor has insufficient liquidity, the entire cycle fails. No partial settlement. This is the simplest correct approach — the netting result is a closed system where all net positions are interdependent.

**Future enhancement (documented, not implemented):** Partial settlement with re-netting — exclude the defaulting PSP's instructions, re-run the netting algorithm on the reduced set, settle the remaining PSPs. This is what production clearing systems do (NICS has default management procedures), but it adds significant complexity for an edge case that PSP liquidity threshold alerts (section 6.6) are designed to prevent.

### 6.6 Threshold Alerts

After every liquidity debit (instant or batch), check if the PSP's balance has crossed the low threshold:

```java
void checkLiquidityThreshold(PspId pspId, String currency, BigDecimal balanceAfter) {
    PspSettlementAccount account = accountRepository.find(pspId, currency);
    if (balanceAfter.compareTo(account.lowThreshold()) < 0) {
        auditLogger.warn("PSP {} {} balance {} below threshold {}", 
                         pspId, currency, balanceAfter, account.lowThreshold());
        meterRegistry.counter("clearing.liquidity.threshold.breach", 
                              "psp", pspId.value(), "currency", currency).increment();
    }
}
```

The alert goes to structured logs (captured by Grafana Loki) and a Prometheus counter (alertable in Grafana). This is a post-commit operation — it does not affect the settlement transaction.

---

## 7. Idempotency and Duplicate Detection

### 7.1 Technical Idempotency

**Instant path:** Kafka Streams `exactly_once_v2` (`processing.guarantee=exactly_once_v2`) ensures that the topology's state store updates and output publications are atomic. If the Kafka Streams task fails and restarts, it replays from the last committed offset without duplicate processing.

Additionally, the `InstructionProcessor` checks the state store before processing: if a `transactionId` already exists in `pending-payments`, the instruction is a duplicate and is discarded.

**Batch path:** The batch instance maintains a `processed_message_ids` table in PostgreSQL:

```sql
CREATE TABLE processed_message_ids (
    message_id    VARCHAR(64)  PRIMARY KEY,
    processed_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Periodic cleanup
DELETE FROM processed_message_ids WHERE processed_at < NOW() - INTERVAL '24 hours';
```

Before processing an instruction in Step 1, the batch job checks if the `transactionId` exists in this table. If yes, the instruction is skipped. After processing, the `transactionId` is inserted. The TTL (24 hours) is far longer than any legitimate retry window.

### 7.2 Semantic Duplicate Detection

Semantic duplicates are different messages that represent the same real-world payment (e.g., same invoice submitted from two channels with different idempotency keys).

**Fingerprint store:**

```sql
CREATE TABLE payment_fingerprints (
    fingerprint_hash  VARCHAR(64)  NOT NULL,
    transaction_id    VARCHAR(36)  NOT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (fingerprint_hash, transaction_id)
);

CREATE INDEX idx_fingerprints_lookup 
ON payment_fingerprints (fingerprint_hash, created_at);

-- Periodic cleanup
DELETE FROM payment_fingerprints WHERE created_at < NOW() - INTERVAL '20 minutes';
```

**Fingerprint calculation:** `SHA-256(debtor_iban + "|" + creditor_iban + "|" + amount + "|" + currency)`

The delimiter prevents collisions (e.g., debtor `NO123` + creditor `NO456` vs. debtor `NO12` + creditor `3NO456`).

**Detection flow (both paths):**

```java
Optional<String> checkSemanticDuplicate(PaymentInstruction instruction) {
    String fingerprint = sha256(
        instruction.debtorIban() + "|" + 
        instruction.creditorIban() + "|" + 
        instruction.amount().toPlainString() + "|" + 
        instruction.currency()
    );
    
    Instant windowStart = Instant.now().minus(duplicateWindow);  // default: 10 min
    
    Optional<String> existingTxId = fingerprintRepository
        .findByFingerprintAndCreatedAfter(fingerprint, windowStart);
    
    if (existingTxId.isPresent()) {
        return existingTxId;  // probable duplicate of this transaction
    }
    
    fingerprintRepository.save(fingerprint, instruction.transactionId());
    return Optional.empty();
}
```

**Detection ordering:** Before balance reservation. If a duplicate is detected, the payment is rejected with reason `PROBABLE_DUPLICATE` and a `duplicateOf` reference to the earlier transaction. No reservation is made, no funds are locked.

**Confirmation flow:** The payer receives the duplicate warning via A3/A4 and can:
- **Cancel:** the duplicate payment is discarded
- **Confirm:** A3/A4 resubmits the instruction with `duplicateOverride: true`. A2 skips the semantic duplicate check for overridden instructions. The override is logged to the audit trail, and a `+0.2` risk factor is passed to A5 in the fraud check request.

**Cross-path detection:** The PostgreSQL `payment_fingerprints` table is shared between both instances. A duplicate submitted via instant is caught even if the original came via batch, and vice versa.

### 7.3 Downstream Idempotency

When A2 sends requests to downstream services, the request ID is deterministically derived from the `transactionId`:

| Downstream request | ID derivation | Dedup by |
|-------------------|---------------|----------|
| Balance reservation (A1) | `SHA-256(transactionId + "balance-reservation")` | A1 |
| Fraud check (A5) | `SHA-256(transactionId + "fraud-check")` | A5 |
| Settlement event (A1) | `SHA-256(transactionId + "settlement")` | A1 |

If A2 retries any of these (after crash recovery or timeout), the downstream service receives the same deterministic ID and deduplicates.

*Master architecture reference: Decision B.3a — Hierarchical transaction ID with per-service operation IDs.*

---

## 8. ISO 20022 Message Handling

### 8.1 Message Types and Directions

| Message | ISO 20022 ID | Direction for A2 | Purpose |
|---------|-------------|-----------------|---------|
| FI-to-FI Credit Transfer | pacs.008.001.10 | Input (Avro from Kafka) | Payment instruction — A2 receives as Avro, validates against pacs.008 business rules |
| Payment Status Report | pacs.002.001.12 | Output (XML) | Confirmation (ACSC) or rejection (RJCT) with reason code |
| Payment Return | pacs.004.001.11 | Input (Avro from Kafka) + Output (XML) | Return requests for settled payments |
| Payment Status Request | pacs.028.001.04 | Input (Avro from Kafka) | Status queries for in-flight payments |
| Bank-to-Customer Statement | camt.053.001.10 | Output (XML) | Settlement report per clearing cycle |

**Clarification on input format:** Payment instructions arrive at A2 as Avro messages on Kafka (serialized by A3/A4 from the original ISO 20022 XML). A2 does not parse raw ISO 20022 XML from external sources. A2's ISO 20022 concerns are primarily on the output side (generating pacs.002 and camt.053 XML).

### 8.2 JAXB Generation Strategy

The `payment-iso20022` shared library contains:
- Source XSD files for the five message types listed above, plus their shared type dependencies (extracted from the ISO 20022 message schema package, CBPR+ 2023 edition)
- A Gradle task that runs `xjc` (JAXB schema compiler) to generate Java classes from the XSD
- A `SecureXmlFactory` for hardened XML processing (section 8.4)

Only the schemas we use are included — not the full ISO 20022 universe. This keeps the generated codebase manageable (hundreds of classes, not thousands) and demonstrates understanding of the standard (knowing which schemas to extract and how they relate).

### 8.3 Internal Domain Model and Boundary Mappers

A2 does not use the JAXB-generated classes as its internal domain model. The JAXB classes have poor ergonomics for business logic (deeply nested, abbreviated field names, XML-centric nullable fields).

A2 has its own domain objects:

| Domain class | Purpose | Used by |
|-------------|---------|---------|
| `PaymentInstruction` | Deserialized from Avro, validated | All paths |
| `ClearingCycle` | Cycle lifecycle and metadata | Batch path |
| `ClearedInstruction` | Instruction that passed screening | Netting |
| `NettingResult` | Bilateral matrix + net positions | Settlement |
| `NetPosition` | Per-PSP net debit/credit | Settlement, report |
| `BilateralPosition` | Gross flows between two PSPs | Netting, report |
| `SettlementEvent` | Published to A1 for ledger update | Both paths |
| `InstantPaymentState` | Kafka Streams state store value | Instant path |

**Mappers at the boundary:**
- `AvroToPaymentInstructionMapper`: Avro `PaymentInstructionRecord` → domain `PaymentInstruction`
- `NettingResultToSettlementReportMapper`: domain `NettingResult` → JAXB `BankToCustomerStatement` (camt.053)
- `SettlementToStatusReportMapper`: domain settlement/rejection → JAXB `PaymentStatusReport` (pacs.002)

Mappers are unit-tested to verify that every relevant field is mapped correctly.

### 8.4 XML Security Hardening

*Requirement: CLR-RES06.*

All XML processing in the `payment-iso20022` library uses a hardened factory:

```java
public final class SecureXmlFactory {

    private SecureXmlFactory() {}

    public static XMLInputFactory createSecureInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        return factory;
    }

    public static Marshaller createSecureMarshaller(JAXBContext context) 
            throws JAXBException {
        Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
        return marshaller;
    }

    /**
     * Unmarshals XML securely. Callers pass the InputSource — this method
     * handles all security hardening internally. No way to bypass it.
     */
    public static <T> T unmarshalSecurely(JAXBContext context, InputSource inputSource) 
            throws JAXBException, SAXException, ParserConfigurationException {
        SAXParserFactory spf = SAXParserFactory.newInstance();
        spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        spf.setNamespaceAware(true);
        
        XMLReader xmlReader = spf.newSAXParser().getXMLReader();
        SAXSource secureSource = new SAXSource(xmlReader, inputSource);
        
        Unmarshaller unmarshaller = context.createUnmarshaller();
        @SuppressWarnings("unchecked")
        T result = (T) unmarshaller.unmarshal(secureSource);
        return result;
    }
}
```

Direct instantiation of `XMLInputFactory`, `DocumentBuilderFactory`, or `SAXParserFactory` outside `SecureXmlFactory` is prohibited. This is enforced by an ArchUnit rule in the shared library's test suite.

### 8.5 camt.053 Report Generation

The `SettlementReportGenerator` is a dedicated component that takes a `NettingResult` and cycle metadata, and produces a camt.053 XML document:

```java
public class SettlementReportGenerator {

    private final JAXBContext camt053Context;
    
    /**
     * Generates camt.053 settlement report.
     * Pure transformation — no I/O, no side effects.
     */
    public byte[] generate(ClearingCycle cycle, NettingResult nettingResult) {
        BankToCustomerStatement statement = buildStatement(cycle, nettingResult);
        return marshalToXml(statement);
    }
}
```

The report includes:
- Report header: cycle ID, creation timestamp, reporting period
- Per-PSP account entries: opening balance, closing balance, net movement
- Per-instruction transaction details: amount, debtor, creditor, settlement reference, end-to-end ID
- Bilateral summary (extension): gross flows between each PSP pair

The report is generated once per cycle, stored as a `BYTEA` column in `settlement_reports`, and served immutably via the REST API. It is not regenerated on each request.

**Signing (CLR-S02):** The report XML is hashed (SHA-256) and the hash is stored alongside the report. In a production deployment, the hash would be digitally signed with A2's signing key via the `KeyProvider`. For the portfolio implementation, the hash provides integrity verification without requiring PKI infrastructure.

---

## 9. Data Architecture

### 9.1 Database Schema Overview

```sql
-- Clearing records (one per payment instruction)
CREATE TABLE clearing_records (
    transaction_id      VARCHAR(36)    PRIMARY KEY,
    clearing_cycle_id   VARCHAR(36),                -- NULL for instant
    path_type           VARCHAR(10)    NOT NULL,     -- INSTANT, BATCH
    status              VARCHAR(30)    NOT NULL,
    rejection_reason    VARCHAR(50),
    instruction_payload JSONB          NOT NULL,     -- deserialized instruction
    screening_result    JSONB,                       -- A5 response
    settlement_ref      UUID,                        -- for settled payments
    duplicate_override  BOOLEAN        DEFAULT FALSE,
    screening_retry_count INTEGER      DEFAULT 0,       -- for batch screening timeout escalation (section 5.4)
    received_at         TIMESTAMP      NOT NULL,
    settled_at          TIMESTAMP,
    updated_at          TIMESTAMP      NOT NULL DEFAULT NOW()
);

-- Clearing cycles (batch only)
CREATE TABLE clearing_cycles (
    cycle_id            VARCHAR(36)    PRIMARY KEY,
    currency            VARCHAR(3)     NOT NULL,
    status              VARCHAR(20)    NOT NULL,
    failure_reason      VARCHAR(100),
    instruction_count   INTEGER,
    settled_count       INTEGER,
    rejected_count      INTEGER,
    held_count          INTEGER,
    gross_amount        DECIMAL(18,2),
    net_amount          DECIMAL(18,2),
    started_at          TIMESTAMP      NOT NULL,
    completed_at        TIMESTAMP,
    netting_result      JSONB,                       -- bilateral matrix snapshot
    created_at          TIMESTAMP      NOT NULL DEFAULT NOW()
);

-- Settlement reports (batch only)
CREATE TABLE settlement_reports (
    cycle_id            VARCHAR(36)    PRIMARY KEY REFERENCES clearing_cycles(cycle_id),
    report_xml          BYTEA          NOT NULL,
    report_hash         VARCHAR(64)    NOT NULL,     -- SHA-256 for integrity
    generated_at        TIMESTAMP      NOT NULL DEFAULT NOW()
);

-- PSP settlement accounts (section 6.1)
-- psp_settlement_accounts
-- psp_liquidity_movements

-- Idempotency and dedup (section 7)
-- processed_message_ids
-- payment_fingerprints

-- Transactional outbox (section 9.3)
CREATE TABLE outbox (
    id                  UUID           PRIMARY KEY,
    topic               VARCHAR(100)   NOT NULL,
    key                 VARCHAR(100),
    payload             BYTEA          NOT NULL,
    headers             JSONB,
    published           BOOLEAN        DEFAULT FALSE,
    created_at          TIMESTAMP      NOT NULL DEFAULT NOW(),
    published_at        TIMESTAMP
);

CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published = FALSE;

-- Spring Batch tables (auto-created by Spring Batch)
-- BATCH_JOB_INSTANCE
-- BATCH_JOB_EXECUTION
-- BATCH_JOB_EXECUTION_PARAMS
-- BATCH_STEP_EXECUTION
-- BATCH_STEP_EXECUTION_CONTEXT
-- BATCH_JOB_EXECUTION_CONTEXT
```

### 9.2 Dual-Write Strategy

*Master architecture reference: Decision B.2a.*

| Path | Strategy | Mechanism |
|------|----------|-----------|
| Instant | Kafka Streams `exactly_once_v2` + PostgreSQL best-effort | Kafka Streams handles produce-and-commit atomically within its topology (state store updates, output publications, offset commits). PostgreSQL writes (clearing records, liquidity movements) happen within the processor using standard JDBC — these are **NOT atomic** with the Kafka transaction. See 9.2.1 for the implications and mitigation. |

### 9.2.1 Instant Path Dual-Write Gap

The instant path has a residual dual-write gap between Kafka Streams and PostgreSQL:

- **Kafka Streams transaction** (atomic): consume instruction, update state store, produce settlement/status events, commit consumer offset
- **PostgreSQL writes** (separate): insert/update `clearing_records`, update `psp_settlement_accounts`, insert `psp_liquidity_movements`

These are two independent systems. Three failure scenarios:

| Scenario | Kafka state | PostgreSQL state | Impact | Mitigation |
|----------|------------|-----------------|--------|-----------|
| Both succeed | Settlement events published, offset committed | clearing_record = SETTLED, liquidity updated | Normal — no issue | — |
| PostgreSQL fails, Kafka succeeds | Settlement events published, offset committed | clearing_record missing or stale | Payment settled in Kafka but PostgreSQL doesn't reflect it. REST API shows stale data. Crash recovery database scan won't find the record. | Reconciliation (below) |
| PostgreSQL succeeds, Kafka fails | Kafka Streams transaction rolls back, offset NOT committed | clearing_record = SETTLED (stale) | On restart, Kafka replays the message. Idempotent processing detects the state store entry (re-created from changelog) and the existing clearing_record. Reprocessing corrects the state. | Kafka replay + idempotency |

**Scenario 2 is the problematic case.** It is rare (requires PostgreSQL to be available for the write but Kafka Streams to fail its transaction commit), but architecturally possible.

**Mitigation — periodic reconciliation:**

A scheduled reconciliation task (e.g., every 5 minutes) compares recent settlement events on the `payment.instant-settlement-events` Kafka topic against `clearing_records` in PostgreSQL. For any settlement event without a corresponding `SETTLED` clearing_record, the task creates or updates the record from the Kafka event. This ensures PostgreSQL eventually converges with Kafka — Kafka is the source of truth for the instant path.

```java
@Scheduled(fixedDelay = 300_000)  // every 5 minutes
void reconcileInstantSettlements() {
    // Read recent settlement events from Kafka (last 10 minutes)
    // For each, verify a SETTLED clearing_record exists in PostgreSQL
    // If missing: create from the Kafka event data
    // Metric: payment.instant.reconciliation.missing_records
}
```

This reconciliation is lightweight (comparing a few hundred records), runs on the instant instance, and closes the gap within minutes. The REST API and investigation endpoints may show stale data during the gap window, but the financial settlement (in Kafka and A1's ledger) is correct.

### 9.2.2 Batch Path Dual-Write Strategy

| Path | Strategy | Mechanism |
|------|----------|-----------|
| Batch | Transactional Outbox | DB update + outbox insert in the same transaction. Outbox relay publishes to Kafka (section 9.3). No dual-write gap — the outbox guarantees at-least-once delivery. |

### 9.3 Outbox Relay Mechanism

*Master architecture reference: Decision B.2b — In-process publish with outbox fallback.*

After the batch path commits a database transaction that includes outbox records, the same thread immediately attempts to publish the outbox records to Kafka:

```java
@Transactional
void settleAndPublish(ClearingCycle cycle, NettingResult result) {
    // 1. Update clearing records, liquidity, cycle status — all in same transaction
    updateClearingRecords(cycle, result);
    updateLiquidity(result);
    
    // 2. Write events to outbox — same transaction
    List<OutboxRecord> events = createSettlementEvents(cycle, result);
    outboxRepository.saveAll(events);
}

// After transaction commits — NOT in the transaction
void afterCommit(List<OutboxRecord> events) {
    for (OutboxRecord event : events) {
        try {
            kafkaProducer.send(event.topic(), event.key(), event.payload());
            outboxRepository.markPublished(event.id());
        } catch (Exception e) {
            log.warn("Failed to publish outbox event {}, relay will retry", event.id(), e);
        }
    }
}
```

A scheduled relay task runs every 500ms and picks up any outbox records that were not successfully published:

```java
@Scheduled(fixedDelay = 500)
void relayUnpublishedEvents() {
    List<OutboxRecord> unpublished = outboxRepository
        .findByPublishedFalseOrderByCreatedAt();
    for (OutboxRecord event : unpublished) {
        kafkaProducer.send(event.topic(), event.key(), event.payload());
        outboxRepository.markPublished(event.id());
    }
}
```

In the happy path (Kafka available, > 99.9% of the time), events are published with near-zero latency. The outbox table is the safety net — it guarantees at-least-once delivery even if Kafka is temporarily unavailable.

### 9.4 GDPR Erasure

*Master architecture reference: Decision F.1a (crypto-shredding scope), INTERFACE_CONTRACTS.md Section 4 (GDPR Erasure Protocol).*

A2's `clearing_records` table stores IBANs (`instruction_payload` JSONB contains `debtorIban` and `creditorIban`). These are pseudonymous personal data under GDPR.

**On receiving an `AccountErasedEvent` from A1** (via `ledger.events` or a dedicated notification topic):

1. Identify all `clearing_records` where the erased IBAN appears in `instruction_payload`
2. For records **within the AML retention period** (5 years from `received_at`): retain with legal basis (5th Anti-Money Laundering Directive, Article 40 — payment service providers must retain payment records for 5 years)
3. For records **beyond the retention period**: anonymize — replace IBANs in `instruction_payload` with SHA-256 hashes

**Legal basis for retention:** AML record-keeping obligations (5-year minimum) take precedence over GDPR erasure for payment transaction records. This is an established regulatory interpretation (GDPR Article 17(3)(b) — "compliance with a legal obligation"). The retention is justified, documented, and bounded.

Settlement reports (camt.053 XML) containing IBANs follow the same retention policy — retained for 5 years with AML legal basis, anonymized after.

---

## 10. Crash Recovery

### 10.1 Recovery on Restart

*Master architecture reference: Decision B.1b — Kafka replay + database recovery scan.*

Both instances follow a two-phase recovery on startup:

**Phase 1: Database scan** (before readiness probe passes)
- Query `clearing_records` for records in intermediate states
- Instant path: records with status in (`AWAITING_RESERVATION`, `AWAITING_FRAUD`, `SETTLING`) that are older than their expected timeout
- Batch path: Spring Batch detects incomplete jobs automatically (section 10.3)
- Resolve stale records: set status to `REJECTED` with reason `RECOVERY_TIMEOUT`, publish release commands for any outstanding reservations

**Phase 2: Kafka replay** (consumer starts from last committed offset)
- Kafka Streams (instant) or Kafka consumer (batch) resumes from the last committed offset
- Any messages not yet committed are reprocessed
- Idempotent processing (section 7.1) ensures no double-processing even if the database scan already handled some cases

Both phases are idempotent. The service does not pass the readiness probe until recovery is complete — it does not accept traffic during recovery.

### 10.2 Instant Path: State Store Recovery

The Kafka Streams state store (`pending-payments`) is backed by a changelog topic. On restart:

1. Kafka Streams replays the changelog topic to rebuild the state store (RocksDB)
2. In-flight payments reappear in the state store with their last persisted state
3. The `TimeoutPunctuator` starts running and detects any entries that have exceeded their timeouts
4. Entries that are now stale (e.g., a payment in `AWAITING_FRAUD` that was persisted 30 seconds ago) are rejected by the punctuator

This recovery is automatic — Kafka Streams handles state store restoration as part of its startup protocol. The startup probe should allow sufficient time for changelog replay (depends on the state store size, typically seconds for a small store of in-flight payments).

### 10.3 Batch Path: Spring Batch Restartability

*Requirement: CLR-RES01.*

Spring Batch persists `ExecutionContext` to PostgreSQL after each chunk commit. On restart:

1. Spring Batch detects the incomplete `JobExecution` (status: `STARTED`)
2. For each incomplete step, Spring Batch resumes from the last committed chunk
3. Step 1 (collect): the Kafka consumer offset for the batch topic is committed per chunk. Restart resumes from the last committed offset.
4. Steps 2-4: each step checks whether it has already completed (the step execution status) and skips if so

Spring Batch Test provides utilities to verify restartability: interrupt a job at a specific chunk, restart, verify the final result is identical to an uninterrupted run. This is a critical integration test for A2.

### 10.4 Graceful Shutdown with In-Flight Payments

*Requirement: CLR-RES03.*

On SIGTERM, the instant instance must not leave orphaned reservations:

1. **Stop accepting new instructions:** The Kafka Streams topology stops polling for new records from `payment.instant-instructions`. Records already consumed continue processing.
2. **Complete or abort in-flight payments:** For each entry in the `pending-payments` state store:
   - If in `SETTLING`: attempt to complete within the shutdown timeout
   - If in `AWAITING_RESERVATION` or `AWAITING_FRAUD`: REJECT immediately, publish release command if reservation was made
3. **Drain response consumers:** Continue consuming from response topics for a bounded period (2 seconds) to catch any in-flight responses
4. **Commit all Kafka Streams state and offsets**
5. **Close consumers and producers**

The shutdown timeout is configured via `spring.lifecycle.timeout-per-shutdown-phase` (default: 30 seconds). Step 2 should complete quickly (each rejection is a few Kafka publishes), well within this budget.

For the batch instance, the graceful shutdown stops Spring Batch jobs using `JobOperator.stop()`. The job enters `STOPPING` state, completes the current chunk, and persists the `ExecutionContext`. On next startup, the job resumes from where it stopped.

### 10.5 Dead Letter Queues

*Requirement: CLR-RES02.*

Every Kafka consumer routes unprocessable messages to a DLQ after 3 retries:

| Source topic | DLQ topic | Retry policy |
|-------------|-----------|-------------|
| `payment.instant-instructions` | `payment.instant-instructions.dlq` | 3 retries, exponential backoff (100ms, 500ms, 2s) |
| `payment.instructions` | `payment.instructions.dlq` | 3 retries, exponential backoff |
| `payment.instant-fraud-check-response` | `payment.instant-fraud-check-response.dlq` | 3 retries |
| `payment.fraud-check-response` | `payment.fraud-check-response.dlq` | 3 retries |
| `payment.balance-reservation-response` | `payment.balance-reservation-response.dlq` | 3 retries |

DLQ messages include: original payload, error details (exception class, message, stack trace), retry count, original topic/partition/offset, timestamp.

DLQ depth is monitored via Prometheus: `payment.dlq.depth` gauge per topic. Alert if > 0.

The error classification follows the Platform Starter's three-category model (decision E.1b):
- **Retryable:** temporary infrastructure failures (Kafka producer timeout, database connection failure) → retry with backoff
- **Permanent → DLQ:** deserialization errors, schema mismatches, business rule violations with no recovery path → DLQ, continue processing
- **Critical → DLQ + alert:** sanctions screening failure, data integrity violation → DLQ + PagerDuty/Slack alert

---

## 11. Security

### 11.1 Authentication and Authorization

*Master architecture reference: Decision A.1a — Three-zone model with HMAC + mTLS internally.*

A2 resides in the **internal zone**. It does not handle external authentication (no OAuth2 tokens, no API keys from external parties). Its security posture:

- **Kafka messages:** HMAC-SHA256 signatures verified on consumption (KM-06). Per-producer HMAC keys — A2 verifies that messages on `payment.instructions` came from A3 or A4, not from a compromised internal service.
- **Kafka transport:** SASL/SSL (SEC-01)
- **REST API:** mTLS for internal callers (management/monitoring tools). The REST API is read-only (clearing cycle queries, liquidity positions) — no state-changing operations via REST.
- **Database:** TLS connection, dedicated credentials, database-per-service isolation (OPS-05)

### 11.2 Sanctions Screening as Mandatory Pipeline Stage

Sanctions screening is not a configurable option — it is a hardcoded step in both the instant and batch paths. There is no feature flag, no configuration parameter, and no code path that bypasses A5.

**Instant path:** The `InstructionProcessor` always transitions to `AWAITING_RESERVATION` (then `AWAITING_FRAUD`). The `FraudResponseProcessor` always evaluates the sanctions result before proceeding to settlement. The `TimeoutPunctuator` always rejects payments without a sanctions clearance.

**Batch path:** Step 2 (FraudScreening) is a required step in the Spring Batch job. Steps cannot be skipped or reordered — the job configuration is declarative and sequential.

**No auto-pass for sanctions:** CLR-I07 is explicit: fraud timeout → auto-pass with UNKNOWN score. Sanctions timeout → REJECT. If sanctions returns UNAVAILABLE, the payment is rejected. This distinction is hardcoded in the `FraudResponseProcessor` and the `TimeoutPunctuator` — it is not a configurable threshold.

### 11.3 Netting Algorithm Determinism as Security Property

Determinism (CLR-S07) is a security and audit property, not just a correctness property. A non-deterministic netting algorithm could be exploited: if the result depends on processing order, an attacker who controls the ordering of instructions could influence net positions in their favor.

The netting algorithm's purity (no external state, no ordering dependencies, BigDecimal arithmetic, sorted maps) eliminates this attack vector. The ArchUnit test (section 5.3) enforces that the netting package has no dependencies on repositories, Kafka, services, or Spring — making it structurally impossible to introduce ordering-dependent behavior.

### 11.4 Settlement Report Signing

*Requirement: CLR-S02.*

Settlement reports (camt.053 XML) include a SHA-256 integrity hash stored alongside the report. On retrieval via the REST API, the consumer can verify the hash against the report content.

For production: the hash would be digitally signed with A2's signing key (RSA-2048 via `KeyProvider`), providing non-repudiation — proof that A2 generated the report and it has not been modified. The `KeyProvider` abstraction (decision A.2a) makes this a configuration change, not a code change.

### 11.5 XML Parser Hardening

*Requirement: CLR-RES06.*

All ISO 20022 XML processing uses `SecureXmlFactory` (section 8.4), which disables:
- DTD processing (`SUPPORT_DTD = false`)
- External entities (`IS_SUPPORTING_EXTERNAL_ENTITIES = false`)
- Entity reference replacement (`IS_REPLACING_ENTITY_REFERENCES = false`)
- DOCTYPE declaration (SAXParserFactory: `disallow-doctype-decl = true`)

This prevents XXE injection, billion laughs DoS, and external entity information disclosure. The hardening is in the shared `payment-iso20022` library — A2 inherits it automatically.

### Threat Model Traceability (Master A.3a)

| Interface | Threat | Control | Reference |
|-----------|--------|---------|-----------|
| Kafka inbound (payment instructions) | Spoofing | HMAC-SHA256 verifies A3/A4 origin | KM-06 |
| Kafka inbound | Tampering | HMAC + Avro schema validation | KM-06, D.1a |
| Payment flow | Repudiation | All state transitions logged to audit.events with hash chain | F.2a |
| Payment flow | Elevation of Privilege | Sanctions screening structurally mandatory — no bypass path | Section 11.2 |
| Admin endpoints (/api/v1/admin/*) | Spoofing | mTLS + clearing:admin OAuth2 scope | A.1a |
| Admin endpoints | Elevation of Privilege | Scope-based authorization; NetworkPolicy restricts to internal zone | A.1b |
| Netting algorithm | Tampering | Deterministic algorithm (ArchUnit enforced); netting results audited | Section 11.3 |
| Settlement events | Denial of Service | Separate JVM instances isolate batch from instant path | C.3a |

---

## 12. Observability

### 12.1 Instant Path Metrics

**End-to-end SLA metric:**

`payment.instant.e2e.duration` — Micrometer Timer (histogram). Measured from instruction consumption to pacs.002 confirmation or rejection published. Tags: `outcome` (SETTLED, REJECTED, TIMEOUT), `rejectionReason` (when rejected).

**Per-stage metrics:**

| Metric | What it measures |
|--------|-----------------|
| `payment.instant.validation.duration` | Business rule validation |
| `payment.instant.duplicate_check.duration` | Semantic duplicate detection (PostgreSQL query) |
| `payment.instant.reservation.duration` | Balance reservation round-trip (A2 → A1 → A2) |
| `payment.instant.fraud_check.duration` | Fraud/sanctions check round-trip (A2 → A5 → A2) |
| `payment.instant.liquidity_check.duration` | PSP liquidity verification |
| `payment.instant.settlement.duration` | Settlement logic + event publishing |
| `payment.instant.kafka_transport.duration` | Kafka transport latency (round-trip minus remote processing) |

All are Micrometer Timers (histograms). No per-`transactionId` tags — that would be high-cardinality. Transaction-level debugging uses distributed traces.

### 12.2 Batch Path Metrics

| Metric | What it measures |
|--------|-----------------|
| `payment.batch.cycle.duration` | Total cycle time |
| `payment.batch.cycle.instruction_count` | Instructions per cycle |
| `payment.batch.cycle.settled_count` | Settled per cycle |
| `payment.batch.cycle.rejected_count` | Rejected per cycle (with reason tag) |
| `payment.batch.netting.duration` | Netting algorithm execution time |
| `payment.batch.fraud_screening.duration` | Total wait time for A5 responses |
| `payment.batch.settlement.duration` | Settlement (liquidity + outbox + status) |
| `payment.batch.report_generation.duration` | camt.053 generation time |

### 12.3 Distributed Tracing

Micrometer Tracing (auto-configured by the Platform Starter) propagates W3C Trace Context (`traceparent` header) through Kafka headers. For the Kafka Streams instant path, trace context is injected by the topology's producer interceptor and extracted by the consumer interceptor.

**Trace span structure for an instant payment:**

```
Trace: payment-instant (A2)
├── validate-instruction         (A2, ~5ms)
├── semantic-duplicate-check     (A2, ~2ms)
├── balance-reservation          (A2 → A1 round-trip)
│   ├── kafka-produce            (A2, ~1ms)
│   ├── process-reservation      (A1, ~15ms)
│   └── kafka-consume-response   (A2, ~3ms)
├── fraud-check                  (A2 → A5 round-trip)
│   ├── kafka-produce            (A2, ~1ms)
│   ├── sanctions-screening      (A5, ~25ms)
│   ├── velocity-rules           (A5, ~10ms)
│   ├── ml-scoring               (A5, ~80ms)
│   └── kafka-consume-response   (A2, ~2ms)
├── liquidity-check              (A2, ~3ms)
├── execute-settlement           (A2, ~10ms)
└── publish-confirmation         (A2, ~5ms)
```

Tracing backend: Grafana Tempo (integrates with existing Grafana + Prometheus stack).

### 12.4 Alert Rules

| Rule | Condition | Severity |
|------|-----------|----------|
| Instant p95 approaching SLA | `e2e.duration` p95 > 4.0s for 2 min | Warning |
| Instant p99 breaching SLA | `e2e.duration` p99 > 5.0s for 1 min | Critical |
| Hard limit violated | Any single transaction > 7.0s | Incident |
| Instant error rate | `outcome=REJECTED` rate > 5% for 5 min | Warning |
| Instant timeout rate | `outcome=TIMEOUT` rate > 1% for 2 min | Critical |
| Batch cycle exceeds budget | `cycle.duration` > 30s | Warning |
| Batch fraud screening slow | `fraud_screening.duration` > 15s | Warning |
| Kafka consumer lag (instant) | Consumer lag > 100 messages for 1 min | Critical |
| DLQ depth | `payment.dlq.depth` > 0 | Warning |
| Liquidity threshold | `clearing.liquidity.threshold.breach` counter > 0 | Warning |
| Investigation queue | Stuck payments > 5 for 5 min | Warning |
| Late fraud response | `payment.late_fraud_response.count` > 0 | Warning |
| Settled before screening | `payment.settled_before_screening.count` > 0 | Critical |

### 12.5 Grafana Dashboards

**Dashboard 1: Instant Payment SLA**
- Row 1: Current p50/p95/p99 e2e latency (stat panels, color-coded: green < 3s, yellow 3-4.5s, red > 4.5s)
- Row 2: Per-stage latency breakdown (stacked bar or heatmap)
- Row 3: Throughput (TPS), error rate, timeout rate (time series)
- Row 4: Kafka consumer lag, Kafka transport latency (time series)
- Row 5: Recent alerts (table)

**Dashboard 2: Batch Clearing Operations**
- Row 1: Last cycle summary (stat panels: duration, count, settled/rejected)
- Row 2: Per-stage timing trend for last N cycles (time series)
- Row 3: Netting summary (PSP count, gross vs. net reduction ratio)
- Row 4: Rejection reasons breakdown (pie chart), fraud screening wait time
- Row 5: Liquidity positions per PSP (bar chart with threshold line)

Dashboard definitions are Grafana JSON provisioning files committed to the repository — version-controlled and deployed automatically with `docker-compose up`.

---

## 13. REST API

A2's REST API is read-only — all state-changing operations flow through Kafka. The API serves operators, monitoring tools, and the demonstration script.

### 13.1 Clearing Cycle Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/v1/clearing-cycles` | List clearing cycles (paginated, filterable by status, date range) |
| GET | `/api/v1/clearing-cycles/{cycleId}` | Cycle detail: status, counts, netting summary, timestamps |
| GET | `/api/v1/clearing-cycles/{cycleId}/report` | Download camt.053 settlement report (XML, with integrity hash in response header) |
| GET | `/api/v1/clearing-cycles/{cycleId}/reconciliation-status` | Reconciliation status: matched, discrepancies, unmatched (CLR-B15) |

### 13.2 Liquidity Position Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/v1/liquidity/positions` | All PSP settlement account balances |
| GET | `/api/v1/liquidity/positions/{pspId}` | Single PSP balance per currency, with recent movements |

### 13.3 Investigation / Stuck Payment Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/v1/admin/investigations` | Payments in intermediate states beyond timeout thresholds |
| GET | `/api/v1/admin/investigations/{transactionId}` | Detail: current state, timestamps, A5 response (if any), A1 reservation status |

*Master architecture reference: Decision B.4a — Dedicated investigation topic + operator API in A2.*

For a full implementation, this would include operator actions (force-complete, force-reject, retry). For the portfolio implementation, read-only investigation is sufficient to demonstrate operational awareness.

**Admin Endpoint Authorization:** All endpoints under `/api/v1/admin/*` require the `clearing:admin` OAuth2 scope in addition to mTLS authentication. This scope is assigned only to operator service accounts, not to TPPs or merchants. The Platform Starter's deny-all default ensures that adding a new admin endpoint without explicit scope configuration results in 403 Forbidden.

**Request DTO Restrictions:** All REST request DTOs use explicit field allowlists. Fields not in the DTO are silently ignored (Spring's default Jackson behavior with `@JsonIgnoreProperties(ignoreUnknown = true)`). System-controlled fields (e.g., `cycle_status`, `netting_result`, `created_at`, `updated_at`) are never exposed in request DTOs — they are always server-assigned.

### 13.4 Error Response Format

All REST error responses follow RFC 9457 (Problem Details for HTTP APIs), auto-configured by the Platform Starter:

```json
{
  "type": "https://api.nordpay.example/errors/clearing-cycle-not-found",
  "title": "Clearing Cycle Not Found",
  "status": 404,
  "detail": "No clearing cycle with ID 'abc-123'",
  "instance": "/api/v1/clearing-cycles/abc-123",
  "correlationId": "req-7f3a8b2c",
  "timestamp": "2026-04-11T14:32:00.000Z"
}
```

---

## 14. Testing Strategy

### 14.1 Unit Tests

| Component | What is tested | Key assertions |
|-----------|---------------|----------------|
| `NettingEngine` | Netting algorithm correctness | Net positions sum to zero. Bilateral matrix correct. BigDecimal precision. Determinism (same input → same output). Precondition violations throw. Empty input handled |
| `TimeoutPunctuator` | Timeout detection | Payments exceeding 7s are rejected. A5 timeout (no response) → always REJECT (sanctions unknown). Reservation timeout → REJECT. Edge: payment at exactly 7.000s. Verify release commands published for all rejections where reservation was made |
| `InstructionProcessor` | Validation + state transitions | Invalid instructions rejected. Valid instructions stored. Duplicate detection called |
| `FraudResponseProcessor` | Fraud result evaluation | SANCTIONS_HIT → reject. SANCTIONS_CLEAR + FRAUD_PASS → settle. SANCTIONS_CLEAR + FRAUD_UNKNOWN → settle with review. SANCTIONS_CLEAR + DECISION_TIMEOUT → settle with review. DECISION_TIMEOUT + sanctions != CLEAR → reject. Late response handling |
| Liquidity locking | Lock ordering | debitPsp/creditPsp always in ascending PSP ID order |
| Boundary mappers | Avro ↔ domain, domain → JAXB | Every field mapped. Null handling. Currency precision |
| Semantic duplicate | Fingerprint calculation | Same inputs → same hash. Delimiter prevents collisions |

Unit tests use JUnit 5 and Mockito. No Spring context, no Testcontainers. Target: < 1 second per test class.

### 14.2 Integration Tests

| Test | Infrastructure | What is verified |
|------|---------------|-----------------|
| Instant path end-to-end | Testcontainers: Kafka + PostgreSQL | Publish instruction, mock A1 and A5 responses on Kafka, verify settlement event and status update published. Verify state store populated and cleared. Verify clearing record in PostgreSQL |
| Batch cycle end-to-end | Testcontainers: Kafka + PostgreSQL | Publish N instructions, provide A5 responses, verify netting result, verify settlement events, verify camt.053 generated |
| Liquidity concurrency | Testcontainers: PostgreSQL | Concurrent instant payments for same PSP — verify no negative balance, no deadlock |
| Outbox relay | Testcontainers: Kafka + PostgreSQL | Write to outbox, stop Kafka, restart Kafka, verify messages delivered |
| DLQ routing | Testcontainers: Kafka | Publish malformed message, verify it arrives on DLQ topic after 3 retries |
| Crash recovery | Testcontainers: Kafka + PostgreSQL | Process instruction, kill JVM mid-flight, restart, verify payment is recovered (rejected or resumed) |

Integration tests use Spring Boot Test with Testcontainers. Target: 10-30 seconds per test class.

### 14.3 Contract Tests (Pact)

| Contract | Provider | Consumer | Type |
|----------|----------|----------|------|
| Payment instructions | A3, A4 | A2 | Kafka message (Avro) |
| Fraud check request/response | A2 | A5 | Kafka message (Avro) |
| Balance reservation request/response | A2 | A1 | Kafka message (Avro) |
| Settlement events | A2 | A1 | Kafka message (Avro) |
| Status updates | A2 | A3, A4 | Kafka message (Avro) |

Pact for message-based contracts: A2 defines what messages it produces and consumes. Provider services (A1, A3, A4, A5) verify they match. Contract tests run in CI/CD — a breaking schema change in any service fails the build.

### 14.4 Timing Tests

Timing-sensitive scenarios use the `awaitility` library:

```java
@Test
void instantPayment_settlesWithinSla() {
    publishInstantInstruction(testInstruction);
    mockA1ReservationResponse(SUCCESS, Duration.ofMillis(50));
    mockA5FraudResponse(PASS, Duration.ofMillis(100));
    
    await().atMost(5, SECONDS).untilAsserted(() -> {
        assertThat(getPaymentStatus(testInstruction.transactionId()))
            .isEqualTo(SETTLED);
    });
}

@Test
void instantPayment_rejectsOnHardLimitTimeout() {
    publishInstantInstruction(testInstruction);
    mockA1ReservationResponse(SUCCESS, Duration.ofMillis(50));
    // A5 never responds
    
    await().atMost(10, SECONDS).untilAsserted(() -> {
        assertThat(getPaymentStatus(testInstruction.transactionId()))
            .isEqualTo(REJECTED);
        assertThat(getPaymentRejectionReason(testInstruction.transactionId()))
            .isEqualTo(TIMEOUT);
    });
}
```

Injected delays in mock A5 responses allow testing all timeout scenarios:
- Normal response (100ms, sanctions CLEAR, fraud PASS) → SETTLED
- A5 responds with sanctions CLEAR + fraud UNKNOWN (A5's internal fraud timeout) → SETTLED with post-settlement review flag
- Complete timeout (A5 sends no response) → REJECTED with reason SANCTIONS_UNAVAILABLE
- 7-second hard limit exceeded (all downstream services slow) → REJECTED with reason TIMEOUT
- Late A5 response for already-settled payment (sanctions HIT) → RETURN_INITIATED

### 14.5 Spring Batch Tests

Spring Batch Test utilities verify:

| Test | What is verified |
|------|-----------------|
| Complete cycle | Job completes with COMPLETED status. Correct counts. Netting correct |
| Restartability | Interrupt at chunk N, restart, final result identical to uninterrupted run |
| Empty cycle | No instructions → job does not start (scheduler skip) |
| Partial screening failure | Some A5 responses timeout → those instructions excluded, rest net correctly |
| Validation rejection | Malformed instructions → rejected in Step 1, not passed to Step 2 |

---

### Configuration Architecture
*Master architecture reference: Decision H.2a*

| Category | Mechanism | Examples |
|----------|-----------|---------|
| **Infrastructure** (env vars) | Injected at deployment, immutable at runtime | `KAFKA_BOOTSTRAP_SERVERS`, `DATABASE_URL`, `SASL_CREDENTIALS` |
| **Operational** (application.yml, hot-reloadable) | `@ConfigurationProperties` with Bean Validation | Timeout budgets, fraud margin, netting schedule, semantic dedup window, liquidity thresholds |
| **Structural** (code) | Requires redeployment | State machine transitions, Kafka topic names, Avro schemas |

All operational parameters use `@ConfigurationProperties` with `@Validated` and Bean Validation annotations. Invalid configuration causes startup failure (master I.1a).

## 15. Architectural Decision Log

Summary of all decisions made in this architecture. Detailed options analysis is in the challenge discussions that preceded this document.

### A2-Specific Decisions

| ID | Decision | Choice | Rationale |
|----|----------|--------|-----------|
| A2-01 | Instant path processing model | Kafka Streams event-driven state machine (Processor API) | Crash-resilient state store, exactly-once semantics, natural fit for multi-source correlation |
| A2-02 | Request-response over Kafka | State store lookup on response (not KStream.join) | Sequential multi-step orchestration needs explicit state, not windowed joins |
| A2-03 | Timeout mechanism | Punctuator scanning state store every 500ms | Integrates with Kafka Streams model. 500ms precision acceptable with 2.2s safety margin |
| A2-04 | Semantic duplicate store | PostgreSQL `payment_fingerprints` table, shared by both instances | Cross-path detection (instant catches batch duplicates and vice versa). <1ms query latency |
| A2-05 | Semantic duplicate flow | Reject with PROBABLE_DUPLICATE, resubmit with override | No SLA impact — each submission is a fresh 5-second window |
| A2-06 | Netting data structure | Bilateral matrix (N×N), net positions derived | Preserves bilateral detail for audit. Negligible space cost for realistic PSP counts |
| A2-07 | Netting purity enforcement | Plain Java class, no Spring dependencies, ArchUnit test | Determinism is a regulatory and security requirement |
| A2-08 | Liquidity locking | Atomic conditional UPDATE (`WHERE balance >= :amount`) | Check and debit in one statement. No TOCTOU gap |
| A2-09 | Deadlock prevention | Consistent lock ordering (ascending PSP ID) | Both paths follow same order. Provably deadlock-free |
| A2-10 | Batch liquidity failure | Entire cycle fails, no partial settlement | Simplest correct approach. Threshold alerts prevent the scenario |
| A2-11 | ISO 20022 binding | Targeted JAXB from 5 message schemas | Manageable scope. Demonstrates standard familiarity |
| A2-12 | Internal domain model | Separate from JAXB, mappers at boundary | Clean business logic. JAXB classes have poor ergonomics for domain code |
| A2-13 | A5 response model | A5 guarantees one response with definitive sanctions result | Allows A2 to distinguish "sanctions confirmed, fraud unknown" from "A5 unreachable" |
| A2-14 | Late sanctions response | Automatic pacs.004 return | Regulatory obligation — cannot ignore post-settlement sanctions hit |
| A2-15 | Late fraud response | Escalate to case management, human decides | Risk management concern, not legal violation |
| A2-16 | Balance reservation response topic | `payment.balance-reservation-response` (new, omitted from master topology) | Required for A1 → A2 reservation confirmation. Identified as master topology omission |
| A2-17 | Instant path PostgreSQL consistency | Periodic reconciliation (Kafka → PostgreSQL) every 5 min | Kafka is source of truth for instant settlements. PostgreSQL is best-effort, reconciled |
| A2-18 | Reservation timeout compensation | Always publish release command (idempotent) | Frees funds immediately rather than waiting for A1's 30-second timeout |
| A2-19 | Batch screening timeout recovery | Next cycle queries clearing_records for SCREENING_TIMEOUT | Re-enters timed-out instructions automatically. Escalates after 3 consecutive failures |
| A2-20 | pacs.028 status queries (CLR-I11) | REST investigation API (portfolio). Kafka handler documented as enhancement | Should-level. REST sufficient for demo |
| A2-21 | Incoming pacs.004 returns (CLR-I12) | Same mechanism as late-sanctions return. Inbound topic or REST endpoint | Reuses existing return flow |
| A2-22 | Settlement finality event (CLR-I13) | Distinct SETTLEMENT_FINAL event on payment.status-updates | Immediate for instant, after cycle close for batch. No new infrastructure |

### Adopted from Master Architecture

| Master ID | Decision | Choice |
|-----------|----------|--------|
| C.3a | Batch/instant isolation | Separate JVM instances, shared DB, separate connection pools |
| C.1a | Saga pattern | Orchestrated for instant, choreographed for batch |
| C.2a | Time budget | Static per-stage + global 7s hard-limit punctuator |
| B.2a | Dual-write (batch) | Transactional Outbox |
| B.2a | Dual-write (instant) | Kafka Streams `exactly_once_v2` |
| B.2b | Outbox relay | In-process publish after commit, scheduled relay as fallback |
| B.1b | Crash recovery | Kafka replay + database recovery scan on startup |
| B.3a | Idempotency | Hierarchical transaction ID + deterministic per-service operation IDs |
| B.4a | Investigation queues | Dedicated topic + operator API in A2 |
| E.1a | REST errors | RFC 9457 Problem Details |
| E.1b | Kafka errors | Three-category (retryable / DLQ / DLQ+alert) |
| E.2a | Tracing | Micrometer Tracing with Kafka header propagation |
| E.2b | SLA metric | `payment.instant.e2e.duration` histogram with p95/p99 alerts |
