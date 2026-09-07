# A2 -- Payment Clearing & Settlement Engine: Architecture Decisions

*Decisions from architecture challenge resolution and design analysis*
*Date: 2026-04-12*
*Input: ARCHITECTURE_CHALLENGES.md, ARCHITECTURE_DESIGN.md, master ARCHITECTURE_DECISIONS.md, INTERFACE_CONTRACTS.md*

---

## How to Read This Document

Each decision records: what was decided, which options were considered, why this option was chosen, and what the consequences are. Decisions are grouped by theme and reference the challenge IDs from `ARCHITECTURE_CHALLENGES.md`.

### Relationship to Master Architecture

A2 inherits 13 system-level decisions from the master `ARCHITECTURE_DECISIONS.md` (listed in Section 15 of ARCHITECTURE_DESIGN.md). All were reviewed for A2 compatibility -- all are compatible, and several are central to A2's design (C.3a batch/instant isolation, C.1a saga pattern, C.2a time budget, B.2a transactional outbox). This document covers only A2-specific decisions.

---

## Theme 1: Deployment and Process Isolation

### Decision D1 -- Separate JVM Instances for Batch and Instant

**Challenge:** C1 -- Dual-mode processing with fundamentally different availability, failure, and scaling profiles.

**Options considered:**
- (A) Single JVM with thread-pool isolation
- (B) Separate JVM instances from same codebase, activated by Spring profiles
- (C) Entirely separate services with separate databases

**Decision: Option B.**

**Rationale:** Process isolation ensures that a batch OOM, GC pause, or I/O saturation cannot impact the instant SLA. The same codebase avoids duplication of shared domain logic (netting engine, duplicate detection, liquidity management). The shared database preserves cross-path duplicate detection (`payment_fingerprints` table) and unified liquidity management (`psp_settlement_accounts`). Option A lacks fault isolation -- a batch failure kills the instant path. Option C over-separates, breaking cross-path concerns and requiring distributed coordination.

**Implementation:** Spring profiles `instant` and `batch`. Separate application ports (8092/9092 and 8192/9192). Separate HikariCP connection pools: instant (min 10, max 20, 2s timeout), batch (min 5, max 30, 5s timeout).

*Master architecture reference: Decision C.3a.*

---

## Theme 2: Instant Path Processing Model

### Decision D2 -- Kafka Streams Processor API (Not DSL)

**Challenge:** C2 -- Kafka Streams topology design for multi-step orchestration.

**Options considered:**
- (A) Kafka Streams DSL with KStream.join()
- (B) Kafka Streams Processor API with shared persistent state store
- (C) Custom orchestration framework or workflow engine

**Decision: Option B.**

**Rationale:** The DSL's join operators are designed for windowed correlation of concurrent streams, not for sequential request-response orchestration where actions depend on the current state. The Processor API gives explicit control over state transitions -- each processor reads the current state, evaluates the input, and writes the next state. The state machine is plain Java if/else logic, not a framework DSL. No additional dependencies (workflow engines) are needed -- Kafka Streams already provides state stores, exactly-once semantics, and crash recovery.

**Implementation:** Three source processors (`InstructionProcessor`, `ReservationResponseProcessor`, `FraudResponseProcessor`) sharing a persistent `KeyValueStore` (`pending-payments`, RocksDB + changelog topic). Key: `transactionId`. Value: `InstantPaymentState` (Avro-serialized). Co-partitioning requirement: all source topics keyed by `transactionId`. Exception: the `payment.balance-reservation` request topic uses `accountId` as Kafka key (required by A1 per INTERFACE_CONTRACTS.md Section 2.3); A1's response is keyed by `transactionId`, restoring co-partitioning.

### Decision D3 -- Orchestrated Saga for Instant Path

**Challenge:** C3 -- State machine for multi-step orchestration with compensation.

**Options considered:**
- (A) Orchestrated saga with explicit compensation table
- (B) Choreography-based saga

**Decision: Option A.**

**Rationale:** The instant path requires ordered, multi-step coordination (A1 reservation, then A5 screening, then liquidity check, then settlement) with compensation on failure at each step. Pure choreography cannot express this ordering without becoming a distributed state machine. The orchestrated saga centralizes the state machine in A2, making compensation logic explicit and testable per state.

**Compensation table:**

| State at failure | Compensation |
|------------------|-------------|
| AWAITING_RESERVATION (validation/duplicate failure) | None -- no reservation made |
| AWAITING_RESERVATION (timeout) | Publish release (idempotent -- safe even if A1 never created it) |
| AWAITING_FRAUD (rejection or timeout) | Release reservation |
| SETTLING (crash, detected by DB scan) | Check settlement events; if unpublished: release + reject; if published: complete PostgreSQL update |
| SETTLED (late sanctions HIT) | Automatic pacs.004 return |

The release command is always published to Kafka before the REJECTED status update, ensuring durability even if A2 crashes immediately after.

*Master architecture reference: Decision C.1a.*

### Decision D4 -- Request-Response Over Kafka via State Store Correlation

**Challenge:** C2/C3 -- A2 needs request-response interactions with A1 and A5 over Kafka (pub/sub, not request-response).

**Options considered:**
- (A) KStream.join() for request-response correlation
- (B) State store lookup on each response event

**Decision: Option B.**

**Rationale:** The built-in join operators correlate records from two streams within a time window. A2's pattern is sequential -- the response can only arrive after the request, and the processor must take different actions depending on the current state (AWAITING_RESERVATION vs. AWAITING_FRAUD). A state-store lookup with explicit state transitions is more natural and more readable.

**Implementation:** A2 publishes a request with `transactionId` as both Kafka key and `correlationId` in payload. The responding service (A1 or A5) publishes its response using the same `transactionId` as key. A2's response processor looks up the `transactionId` in the state store and advances the state machine.

| Request topic | Response topic | Responder |
|--------------|---------------|-----------|
| `payment.balance-reservation` | `payment.balance-reservation-response` | A1 |
| `payment.instant-fraud-check-request` | `payment.instant-fraud-check-response` | A5 |

*INTERFACE_CONTRACTS.md: Section 2.3 (balance reservation), Section 3 (fraud check).*

---

## Theme 3: Timeout and SLA Enforcement

### Decision D5 -- Static Timeouts + Global 7-Second Hard-Limit Punctuator

**Challenge:** C4 -- Time budget allocation within the EPC SLA.

**Options considered:**
- (A) Dynamic timeouts (adjust based on remaining budget)
- (B) Static per-stage timeouts + global hard-limit punctuator

**Decision: Option B.**

**Rationale:** Static timeouts are simple, predictable, and testable. The time budget allocates 1,500ms to Phase 2 orchestration plus 500ms for network overhead, totaling 2,800ms -- well within the 5,000ms execution target with a 2,200ms safety margin. Dynamic timeouts add complexity and are difficult to reproduce in testing. The 500ms punctuation interval means worst-case detection delay is 500ms, easily absorbed by the safety margin.

**Per-stage budgets:**

| Stage | Budget |
|-------|--------|
| Consume + validate | 200ms |
| Semantic duplicate check | 50ms |
| Balance reservation (A1 round-trip) | 2,000ms (timeout) |
| Fraud + sanctions (A5 round-trip) | 800ms (timeout) |
| Liquidity check | 50ms |
| Settlement + publish | 200ms |

**Punctuator:** `TimeoutPunctuator` runs every 500ms on wall-clock time, scanning the state store. Any entry exceeding the 7-second hard limit is rejected unconditionally. Entries exceeding per-stage timeouts (reservation: 2s, fraud: 800ms) are rejected with appropriate reasons.

*Master architecture reference: Decision C.2a.*

---

## Theme 4: Batch Path

### Decision D6 -- Transactional Outbox for Batch Path

**Challenge:** The batch path must publish settlement events and status updates to Kafka after committing database changes. Dual-write (database + Kafka) without coordination risks data loss.

**Options considered:**
- (A) Direct Kafka publish after database commit (fire-and-forget)
- (B) Transactional Outbox -- write events to an outbox table in the same database transaction, then publish to Kafka

**Decision: Option B.**

**Rationale:** The outbox guarantees at-least-once delivery: the event is persisted in the same transaction as the business data, so it cannot be lost. In the happy path (>99.9% of the time), the in-process relay publishes events with near-zero latency. A scheduled relay (every 500ms) picks up any unpublished events as a safety net. Option A risks losing events if Kafka is temporarily unavailable at the moment of publish.

**Implementation:** The `outbox` table stores topic, key, payload, and a `published` boolean. After the transaction commits, the same thread attempts immediate publish. A `@Scheduled(fixedDelay = 500)` relay retries unpublished events.

*Master architecture reference: Decisions B.2a, B.2b.*

### Decision D7 -- Multilateral Netting with BigDecimal Precision

**Challenge:** C5 -- Netting algorithm design for financial correctness, determinism, and auditability.

**Options considered:**
- (A) Simple aggregation (sum debits/credits per PSP)
- (B) Bilateral matrix with derived net positions, pure function, BigDecimal arithmetic

**Decision: Option B.**

**Rationale:** The bilateral matrix preserves which PSP owes which -- essential for reconciliation and audit. Simple aggregation loses this bilateral detail. `BigDecimal` eliminates floating-point imprecision. `TreeMap` ensures deterministic iteration order. The `NettingEngine` is a pure function with no injected dependencies, no I/O, and no Spring annotations -- its purity is enforced by an ArchUnit test. A postcondition check verifies that net positions sum to zero; violation throws `NettingIntegrityException` and halts the cycle.

Determinism is both a correctness property and a security property: a non-deterministic algorithm could be exploited by an attacker who controls instruction ordering.

---

## Theme 5: Sanctions and Fraud Handling

### Decision D8 -- Sanctions Timeout = Always REJECT

**Challenge:** C6 -- Whether to reject or auto-pass when sanctions screening status is unknown.

**Options considered:**
- (A) Always reject on any timeout
- (B) Distinguish sanctions vs. fraud timeout
- (C) Auto-pass on all timeouts

**Decision: Option B -- with the rule that sanctions unknown = always REJECT.**

**Rationale:** EU AML regulations prohibit skipping sanctions screening. Option C is unacceptable for a regulated system. Option A is overly conservative -- it rejects payments even when sanctions have been confirmed clear and only fraud scoring is incomplete. Option B distinguishes the two cases:

- A5 responds `{sanctions: CLEAR, fraudScore: UNKNOWN}`: proceed to settlement with post-settlement review flag. Sanctions obligation is met; fraud scoring is a risk management concern, not a legal violation.
- A5 sends nothing (punctuator fires): sanctions status unknown, REJECT with reason `SANCTIONS_UNAVAILABLE`.
- A5 responds `{sanctions: UNAVAILABLE}`: same as no response, REJECT.

This distinction is hardcoded in the `FraudResponseProcessor` and the `TimeoutPunctuator` -- it is not a configurable threshold.

**Late sanctions HIT on settled payment:** Automatic pacs.004 return initiated -- regulatory obligation. Late fraud BLOCK: escalated to case management for human decision.

### Decision D9 -- A5 Response Model: Single Response with Definitive Sanctions Result

**Challenge:** C6 -- A2 needs to distinguish "sanctions confirmed but fraud unknown" from "A5 completely unresponsive."

**Decision:** A5 guarantees a single response per request with a definitive sanctions result. If sanctions screening completes (<50ms per A5-NF02) but fraud scoring is incomplete, A5 publishes `{sanctions: CLEAR, fraudScore: UNKNOWN}` rather than waiting for fraud scoring to complete. A5 may continue scoring internally and publish a supplementary response with the actual fraud score -- this is the "late fraud response" case handled by the `FraudResponseProcessor`.

**Rationale:** This model lets A2 distinguish three cases unambiguously: (1) all clear, (2) sanctions clear but fraud incomplete, (3) A5 unreachable. Without this contract, A2 cannot safely auto-pass any timeout.

*INTERFACE_CONTRACTS.md: Section 3 (A5 fraud check contract).*

---

## Theme 6: Liquidity and Financial Integrity

### Decision D10 -- Atomic Conditional Update for PSP Settlement

**Challenge:** C7 -- Preventing negative balances, TOCTOU races, and deadlocks in liquidity management.

**Options considered:**
- (A) Application-level read-check-write
- (B) Atomic conditional SQL UPDATE (`WHERE balance >= :amount`)

**Decision: Option B.**

**Rationale:** The atomic `UPDATE ... WHERE balance >= :amount` combines check and debit in a single SQL statement -- no TOCTOU gap. If affected rows = 0, insufficient funds. A `CHECK (balance >= 0)` database constraint provides a safety net even if application logic has a bug.

**Deadlock prevention:** Both paths always update PSP rows in ascending PSP ID order. The instant path determines the lower PSP ID and processes that row first. The batch path sorts the PSP list before iterating (`Collections.sort`). All operations for a single payment (debit, credit, two audit trail inserts) execute in a single database transaction.

### Decision D11 -- Batch Liquidity Failure: Entire Cycle Fails

**Challenge:** C7 -- What happens when a net debtor PSP has insufficient funds during batch settlement.

**Options considered:**
- (A) Entire cycle fails, no partial settlement
- (B) Partial settlement with re-netting (exclude defaulting PSP, recalculate)

**Decision: Option A.** Future enhancement: Option B.

**Rationale:** The netting result is a closed system where all net positions are interdependent -- partial settlement requires re-running the netting algorithm on a reduced instruction set. This adds significant complexity for an edge case that PSP liquidity threshold alerts are designed to prevent. Production clearing systems (e.g., NICS) do implement partial settlement with default management procedures; this is documented as a future enhancement.

---

## Theme 7: Duplicate Detection and Idempotency

### Decision D12 -- Semantic Duplicate Detection via Fingerprint Store

**Challenge:** C8 -- Detecting semantically identical payments with different technical IDs.

**Options considered:**
- (A) Rely on upstream (A3/A4) dedup only
- (B) A2-side PostgreSQL fingerprint store, shared between both instances

**Decision: Option B.**

**Rationale:** A3 and A4 may not have cross-channel visibility (a payment submitted via web portal and API simultaneously). A2's shared PostgreSQL table provides cross-path detection -- an instant submission catches a batch duplicate and vice versa.

**Implementation:** Fingerprint = `SHA-256(debtor_iban | creditor_iban | amount | currency)`. 10-minute detection window. Detection before balance reservation (no funds locked for duplicates). Override flow: payer resubmits with `duplicateOverride: true`, logged to audit trail with +0.2 risk factor to A5.

### Decision D13 -- Deterministic Downstream Request IDs

**Challenge:** Ensuring idempotent retry when A2 re-sends requests to A1 or A5 after crash recovery.

**Decision:** All downstream request IDs are deterministically derived: `SHA-256(transactionId + operation-suffix)`. Suffixes: `"balance-reservation"`, `"fraud-check"`, `"settlement"`. If A2 retries after crash recovery, the downstream service receives the same ID and deduplicates.

*Master architecture reference: Decision B.3a.*

---

## Theme 8: Crash Recovery

### Decision D14 -- Database Recovery Scan + Framework-Native Recovery

**Challenge:** C9 -- Reconciling two independent recovery mechanisms (Kafka Streams state store vs. Spring Batch ExecutionContext).

**Options considered:**
- (A) Framework-native recovery only
- (B) Framework-native recovery + database recovery scan on startup

**Decision: Option B.**

**Rationale:** The database scan catches edge cases that framework-native recovery might miss -- for example, a payment whose `clearing_records` row was written to PostgreSQL but whose Kafka Streams state store entry was lost due to an unclean shutdown before the changelog was committed. The scan runs before the readiness probe passes, resolving stale records (reject, release reservations) before Kafka replay proceeds. Idempotent processing ensures no conflicts between the database scan and Kafka replay.

**Instant path recovery:** State store rebuilt from changelog topic (automatic by Kafka Streams). `TimeoutPunctuator` detects stale entries on first scan.

**Batch path recovery:** Spring Batch detects incomplete `JobExecution` (status: `STARTED`) and resumes from the last committed chunk. Step idempotency: chunk reader resumes from last committed offset; screening re-sends requests (A5 deduplicates by deterministic ID); outbox relay re-publishes unpublished events; report generation is idempotent.

*Master architecture reference: Decision B.1b.*

---

## Theme 9: Investigation and Operational Visibility

### Decision D15 -- Investigation Queue on Dedicated Kafka Topic

**Challenge:** C10 -- Providing structured operational visibility into stuck or problematic payments.

**Options considered:**
- (A) Log-based investigation only
- (B) Dedicated Kafka topic (`payment.investigation`) + operator REST API

**Decision: Option B.**

**Rationale:** Structured investigation events on a dedicated topic are queryable, alertable, and integrable with operational tooling. Log-based investigation requires operators to grep logs, which is fragile and does not support structured queries. The REST API (`/api/v1/admin/investigations`) provides detail on current state, timestamps, screening results, and reservation status. Admin endpoints require the `clearing:admin` OAuth2 scope.

**Events published to `payment.investigation`:**
- `SETTLED_BEFORE_SCREENING` (highest severity) -- automatic pacs.004 return
- Late fraud BLOCK -- escalated to case management
- Recurring screening timeout (3+ consecutive failures)
- Reconciliation discrepancy (Kafka vs. PostgreSQL)

*Master architecture reference: Decision B.4a.*

---

## Theme 10: Instant Path Dual-Write Consistency

### Decision D16 -- Kafka as Source of Truth, PostgreSQL Reconciled

**Challenge:** The instant path has a residual dual-write gap between Kafka Streams (atomic: state store + output + offset commit) and PostgreSQL (separate: clearing records, liquidity). These are two independent systems.

**Options considered:**
- (A) Accept the gap, rely on crash recovery only
- (B) Periodic reconciliation: compare Kafka settlement events against PostgreSQL clearing records

**Decision: Option B.**

**Rationale:** The problematic scenario is rare (PostgreSQL write succeeds but Kafka Streams transaction commit fails, or vice versa), but architecturally possible. A scheduled reconciliation task (every 5 minutes) compares recent settlement events on Kafka against `clearing_records` in PostgreSQL. Missing records are created from the Kafka event data. Kafka is the source of truth for the instant path; PostgreSQL is the queryable projection that eventually converges.

This is lightweight (comparing a few hundred records), runs on the instant instance, and closes the gap within minutes. The REST API may show stale data during the gap window, but the financial settlement (in Kafka and A1's ledger) is correct.

---

## Adopted Master Decisions Summary

| Master ID | Decision | A2 Application |
|-----------|----------|---------------|
| C.3a | Batch/instant isolation | Separate JVM instances, shared DB, separate connection pools |
| C.1a | Saga pattern | Orchestrated for instant (A2 as coordinator), choreographed for batch |
| C.2a | Time budget | Static per-stage + global 7s hard-limit punctuator |
| B.2a | Dual-write (batch) | Transactional Outbox |
| B.2b | Outbox relay | In-process publish after commit, scheduled relay as fallback |
| B.1b | Crash recovery | Kafka replay + database recovery scan on startup |
| B.3a | Idempotency | Hierarchical transaction ID + deterministic per-service operation IDs |
| B.4a | Investigation queues | Dedicated topic + operator API in A2 |
| E.1a | REST errors | RFC 9457 Problem Details |
| E.1b | Kafka errors | Three-category (retryable / DLQ / DLQ+alert) |
| E.2a | Tracing | Micrometer Tracing with Kafka header propagation |
| E.2b | SLA metric | `payment.instant.e2e.duration` histogram with p95/p99 alerts |
| A.1a | Authentication | HMAC-SHA256 on Kafka messages, mTLS for REST, three-zone model |
