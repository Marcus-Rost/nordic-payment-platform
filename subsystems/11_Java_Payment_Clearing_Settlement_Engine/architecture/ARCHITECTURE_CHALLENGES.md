# A2 — Payment Clearing & Settlement Engine: Architectural Challenges

*Extracted from ARCHITECTURE_DESIGN.md challenge analysis*
*Date: 2026-04-12*
*Status: All 10 challenges decided (2026-04-12)*

---

## How to Read This Document

Each challenge describes an architectural decision that A2 had to make. For each challenge we present:
- **The problem** -- what needs to be decided and why it matters
- **Solution options** -- realistic alternatives with pros and cons
- **Decision** -- the chosen option with rationale (all challenges decided 2026-04-12)

Challenges are ordered by priority: foundational decisions that other decisions depend on come first.

### Relationship to Master Architecture

The master project's `ARCHITECTURE_DECISIONS.md` contains 32 system-level decisions. We reviewed all of them for compatibility with A2. Result: **all 32 are compatible**. A2 adopts 13 master decisions directly (C.3a, C.1a, C.2a, B.2a, B.2b, B.1b, B.3a, B.4a, E.1a, E.1b, E.2a, E.2b, and others -- see ARCHITECTURE_DESIGN.md Section 15 for the full list).

This document covers only **A2-specific challenges** -- decisions where A2 must make its own choice, either because the master architecture doesn't prescribe a solution at the subsystem level, or because A2 has specific constraints (dual-mode processing, EPC SLA ownership, netting correctness) that require subsystem-level decisions.

---

## Priority 1 -- Foundational Decisions

These must be decided first. Other decisions depend on them.

---

### C1 -- Dual-Mode Processing: Batch vs. Instant in One Service

**The problem:** A2 must run two fundamentally different processing models. The instant path requires 24/7/365 availability, sub-5-second execution, and gross settlement of individual payments. The batch path runs on a schedule, processes thousands of instructions in a cycle, and performs multilateral netting. These have different availability requirements (24/7 vs. scheduled), failure modes (GC pause vs. I/O wait), and scaling profiles (demand-driven vs. fixed schedule).

**Why it matters:** If both modes run in the same JVM, a batch cycle that triggers a GC pause or OOM can violate the instant SLA. A batch cycle that saturates the database connection pool starves the instant path. Conversely, over-separating them (entirely separate services with separate databases) breaks cross-path duplicate detection and liquidity management. The isolation decision cascades into deployment, connection pools, Kafka consumer groups, and monitoring.

**Options considered:**

- **(A) Single JVM with thread-pool isolation** -- Both modes in one process, separated by dedicated thread pools and connection pools. Simpler deployment, but no process-level fault isolation. A batch OOM kills the instant path.

- **(B) Separate JVM instances from same codebase** -- Spring profiles activate batch or instant mode. Separate processes, separate connection pools, shared database. Process isolation ensures batch failures cannot impact instant SLA. Same codebase reduces duplication.

- **(C) Entirely separate services with separate databases** -- Maximum isolation, but breaks cross-path concerns (duplicate detection, liquidity management) and requires distributed coordination.

**Decision: Option B -- Separate JVM instances from the same codebase, activated by Spring profiles (`instant` and `batch`).**

Process isolation ensures that a batch OOM, GC pause, or I/O saturation cannot impact the instant SLA. The instant instance can be restarted independently. They scale independently (more instant replicas during peak hours). The shared database preserves cross-path duplicate detection (PostgreSQL `payment_fingerprints` table) and unified liquidity management (`psp_settlement_accounts`). Connection pools are separated: instant gets a smaller guaranteed pool (min 10, max 20, 2s timeout -- fail fast), batch gets a larger burstable pool (min 5, max 30, 5s timeout).

*Master architecture reference: Decision C.3a.*
*ARCHITECTURE_DESIGN.md: Section 3.1, 3.2.*

---

### C2 -- Kafka Streams Topology Design for Instant Path

**The problem:** The instant path must orchestrate a multi-step sequence (validate, reserve balance, screen fraud/sanctions, check liquidity, settle) across three Kafka input topics, coordinating responses from A1 and A5 via asynchronous Kafka messages. The topology needs explicit state management -- each payment is in one of several states (`AWAITING_RESERVATION`, `AWAITING_FRAUD`, `SETTLING`, etc.) and the processing logic depends on the current state. Kafka Streams offers two programming models: the DSL (declarative, operator-based) and the Processor API (imperative, explicit state management).

**Why it matters:** The DSL's declarative operators (map, filter, join) are designed for stateless transformations or windowed joins between two streams. A2's pattern is sequential multi-step orchestration where a response can only arrive after its request, and the action taken depends on which step the payment is in. Choosing the wrong abstraction leads to fighting the framework -- contorting join semantics to express state machine transitions.

**Options considered:**

- **(A) Kafka Streams DSL with KStream.join()** -- Use windowed joins to correlate requests and responses. Declarative and concise for simple cases, but KStream-KStream joins are designed for correlating concurrent streams within a time window, not for sequential request-response orchestration where actions depend on state.

- **(B) Kafka Streams Processor API with state store** -- Three source processors (one per input topic) sharing a persistent `KeyValueStore`. State transitions are explicit if/else logic in processor classes. State store backed by RocksDB + changelog topic for crash recovery.

- **(C) Custom orchestration framework** -- Build a state machine library or use an existing workflow engine (e.g., Temporal, Camunda). Adds a dependency that must be learned, operated, and debugged. Kafka Streams already provides the primitives (state stores, exactly-once, crash recovery).

**Decision: Option B -- Kafka Streams Processor API with a shared persistent state store.**

The Processor API gives explicit control over state transitions -- each processor reads the current state, evaluates the input (instruction, reservation response, or fraud response), and writes the next state. The state machine is plain Java if/else logic in a single processor class per input topic, not a framework DSL. The state store (`pending-payments`, keyed by `transactionId`, Avro-serialized) is backed by RocksDB with a changelog topic, providing crash recovery without additional infrastructure. This also naturally supports the punctuator pattern for timeout enforcement.

*ARCHITECTURE_DESIGN.md: Section 4.1.*

---

### C3 -- State Machine for Multi-Step Orchestration Over Kafka

**The problem:** Each instant payment passes through multiple asynchronous steps: validation, balance reservation (A1 round-trip), fraud/sanctions screening (A5 round-trip), liquidity check, and settlement. Each step can succeed, fail, or time out. The payment must reach a terminal state (SETTLED or REJECTED) under all conditions -- there must be no state from which the payment cannot reach a terminal state. The state machine must survive crashes (persisted states) and handle compensation (releasing reservations on rejection).

**Why it matters:** "Zero payment loss" (CLR-NF06) means every instruction must reach SETTLED or REJECTED. A state machine with a dead-end state -- where a crash leaves a payment in a non-recoverable intermediate state -- violates this requirement. Compensation logic (releasing reservations) must be correct for every possible failure path, or funds remain locked until A1's 30-second reservation timeout.

**Options considered:**

- **(A) Orchestrated saga with explicit compensation table** -- Each state transition has a documented compensation action. The state machine is in the Kafka Streams state store. Terminal states result in removal from the state store. A timeout punctuator ensures no payment stays in-flight indefinitely.

- **(B) Choreography-based saga** -- Each service reacts to events and publishes its own events. No central coordinator. Works well for simple flows, but the multi-step A2 orchestration (A1, then A5, then liquidity, then settle) requires ordering guarantees that pure choreography cannot express without becoming a distributed state machine anyway.

**Decision: Option A -- Orchestrated saga with the state machine in the Kafka Streams state store.**

The state machine has five persisted states: `AWAITING_RESERVATION`, `AWAITING_FRAUD`, `SETTLING`, `SETTLED`, `REJECTED`. Decision branches (validation, liquidity check) are transient -- they execute within a single processor invocation. Every terminal state (SETTLED or REJECTED) results in: a definitive status on `payment.status-updates`, removal from the state store, and a reservation release if applicable. The `TimeoutPunctuator` guarantees that even if a downstream service never responds, the payment reaches REJECTED within 7 seconds.

Compensation is explicit per state: AWAITING_RESERVATION failures need no compensation (no reservation yet); AWAITING_FRAUD failures release the reservation; SETTLING crashes are detected by the database recovery scan and either completed or rolled back.

*Master architecture reference: Decision C.1a -- Orchestrated saga for instant, choreographed for batch.*
*ARCHITECTURE_DESIGN.md: Sections 4.2, 4.5.*

---

### C4 -- Time Budget Allocation Within the 5-Second EPC SLA

**The problem:** The EPC 2025 SCT Inst rulebook specifies a 5-second execution target, 7-second hard limit, and 9-second confirmation window. A2 owns Phase 2 of the payment lifecycle -- the orchestration between instruction consumption and settlement confirmation. A2 must allocate time budgets to each sub-step (validation, duplicate check, balance reservation, fraud screening, liquidity check, settlement) and enforce a hard limit that prevents any single payment from exceeding 7 seconds.

**Why it matters:** Without explicit budgets, a single slow downstream service (A1 or A5) can consume the entire SLA. Without a hard-limit enforcer, a payment could remain in-flight indefinitely if both downstream services are unresponsive. The time budget determines the per-stage timeout thresholds and the punctuator's enforcement precision.

**Options considered:**

- **(A) Dynamic timeouts** -- Adjust per-stage timeouts based on remaining budget (e.g., if reservation took 500ms, give fraud screening more time). Adaptive, but complex to reason about and difficult to reproduce in testing.

- **(B) Static per-stage timeouts + global hard-limit punctuator** -- Fixed budgets per stage (reservation: 2s, fraud: 800ms). A punctuator scans the state store every 500ms and rejects anything exceeding the 7-second hard limit. Simple, predictable, testable.

**Decision: Option B -- Static per-stage timeouts with a global 7-second hard-limit punctuator.**

The time budget breakdown allocates 1,500ms to Phase 2 orchestration (200ms consume+validate, 50ms duplicate check, 200ms reservation, 800ms fraud, 50ms liquidity, 200ms settlement) plus 500ms for network/Kafka overhead, totaling 2,800ms -- well within the 5,000ms target with a 2,200ms safety margin to the 7,000ms hard limit. The punctuator runs every 500ms on wall-clock time, scanning the state store for entries exceeding their threshold. The 500ms punctuation interval means worst-case detection delay is 500ms, which the 2,200ms safety margin easily absorbs.

*Master architecture reference: Decision C.2a.*
*ARCHITECTURE_DESIGN.md: Section 4.4.*

---

## Priority 2 -- Domain-Critical Decisions

These have significant impact on financial correctness, regulatory compliance, or operational quality.

---

### C5 -- Netting Algorithm Design for Batch Clearing

**The problem:** The batch path must calculate multilateral net positions from thousands of individual payment instructions. The netting result determines how much each PSP owes or is owed. This is a financial calculation with regulatory implications: it must be deterministic (same input produces same output -- CLR-S07), precise (no floating-point rounding errors), and auditable (the bilateral matrix must be preservable for reconciliation).

**Why it matters:** A non-deterministic netting algorithm is not just a correctness bug -- it is a security vulnerability. If the result depends on processing order, an attacker who controls instruction ordering could influence net positions. Non-determinism also breaks auditability: re-running the algorithm on the same input must produce the same output for regulatory verification.

**Options considered:**

- **(A) Simple aggregation** -- Sum debits and credits per PSP directly from the instruction list. Produces correct net positions but loses bilateral detail (which PSP owes which).

- **(B) Bilateral matrix with derived net positions** -- Build an N-by-N matrix of gross flows between each PSP pair, then derive net positions. Preserves bilateral detail for audit and reconciliation.

**Decision: Option B -- Bilateral matrix with derived net positions, implemented as a pure function.**

The `NettingEngine` is a plain Java class with no injected dependencies, no I/O, and no Spring annotations. It uses `BigDecimal` for all arithmetic (no floating-point), `TreeMap` for deterministic iteration order, and `PspPair` with consistent ordering (`min, max`) to ensure the bilateral matrix is identical regardless of instruction order. A postcondition check verifies that net positions sum to zero -- if they don't, a `NettingIntegrityException` halts the cycle. An ArchUnit test enforces that the netting package has no dependencies on repositories, Kafka, services, or Spring.

*ARCHITECTURE_DESIGN.md: Section 5.3.*

---

### C6 -- Sanctions Timeout Handling: Reject vs. Auto-Pass

**The problem:** When A5 (Fraud & AML) does not respond within the timeout, A2 must decide whether to reject the payment (blocking a potentially legitimate transaction) or auto-pass it (risking a sanctions violation). The instant path makes this decision in two places: the `FraudResponseProcessor` (when A5 responds with partial results) and the `TimeoutPunctuator` (when A5 sends nothing at all). The batch path faces a similar decision when the 30-second screening timeout expires.

**Why it matters:** EU AML regulations and IPR Article 5d prohibit skipping sanctions screening. A payment settled without sanctions clearance -- even due to a timeout -- is a regulatory violation. Conversely, rejecting all payments during an A5 outage causes service disruption. The decision must distinguish between "sanctions confirmed clear but fraud scoring incomplete" and "sanctions status completely unknown."

**Options considered:**

- **(A) Always reject on any timeout** -- Conservative. No regulatory risk. But rejects payments even when sanctions have been confirmed clear and only fraud scoring is incomplete.

- **(B) Distinguish sanctions vs. fraud timeout** -- If A5 confirms sanctions CLEAR but fraud score is UNKNOWN (A5's internal timeout), proceed to settlement with a post-settlement review flag. If sanctions status is unknown (A5 completely unresponsive), reject.

- **(C) Auto-pass on all timeouts** -- Maximizes availability but risks sanctions violations. Unacceptable for a regulated payment system.

**Decision: Option B -- Distinguish sanctions from fraud timeout. Sanctions unknown = always REJECT. Sanctions clear + fraud unknown = proceed with review flag.**

A5's response model guarantees a single response with a definitive sanctions result. If sanctions screening completes (fast, <50ms per A5-NF02) but fraud scoring is incomplete, A5 publishes `{sanctions: CLEAR, fraudScore: UNKNOWN}`. A2's `FraudResponseProcessor` sees `sanctions = CLEAR, fraud = UNKNOWN` and proceeds to settlement with a post-settlement review flag. The `TimeoutPunctuator` fires only when A5 sends nothing at all -- sanctions status unknown -- and always rejects with reason `SANCTIONS_UNAVAILABLE`. This distinction is hardcoded, not configurable.

For late-arriving sanctions HITs on already-settled payments: A2 automatically initiates a pacs.004 return (regulatory obligation). Late fraud BLOCKs escalate to case management for human decision.

*ARCHITECTURE_DESIGN.md: Sections 4.1 (FraudResponseProcessor), 4.4 (TimeoutPunctuator), 4.6 (late responses).*

---

### C7 -- Liquidity Management and PSP Settlement Accounts

**The problem:** A2 must manage pre-funded PSP settlement accounts for both paths. The instant path debits and credits PSP accounts for each individual payment (gross settlement). The batch path updates net positions after netting. Both paths operate on the same `psp_settlement_accounts` table, potentially concurrently. The design must prevent negative balances (a PSP spending more than its funded amount), deadlocks (both paths locking PSP rows in conflicting order), and TOCTOU races (checking balance then debiting in separate operations).

**Why it matters:** A negative PSP balance means the system has extended unsecured credit -- a financial risk. A deadlock between the instant and batch paths blocks payment processing. A TOCTOU race between the balance check and the debit allows a PSP to overdraw if two payments are processed concurrently.

**Options considered:**

- **(A) Application-level balance check, then update** -- Read balance, verify sufficient, write new balance. Simple but vulnerable to TOCTOU: two concurrent payments both see sufficient balance, both debit, result is negative.

- **(B) Atomic conditional UPDATE in SQL** -- `UPDATE ... SET balance = balance - :amount WHERE balance >= :amount`. Check and debit in one atomic statement. Affected rows = 0 means insufficient funds.

**Decision: Option B -- Atomic conditional UPDATE with consistent lock ordering.**

The `UPDATE ... WHERE balance >= :amount` eliminates the TOCTOU gap -- the check and debit are a single atomic SQL statement. If affected rows = 0, the payment is rejected for insufficient PSP liquidity. A `CHECK (balance >= 0)` constraint on the table provides a database-level safety net. Deadlock prevention: both paths always update PSP rows in ascending PSP ID order. The instant path determines the lower PSP ID and processes that row first. The batch path sorts PSPs before iterating. All operations for a single payment (debit, credit, audit trail inserts) execute in a single database transaction.

*ARCHITECTURE_DESIGN.md: Sections 6.1-6.4.*

---

### C8 -- Semantic Duplicate Detection

**The problem:** Different messages can represent the same real-world payment -- for example, the same invoice submitted via both the web portal (A3) and the API (A4) with different technical `transactionId` values. Technical idempotency (Kafka Streams `exactly_once_v2`, `processed_message_ids` table) catches redeliveries of the same message, but not semantically identical payments with different IDs. A2 must detect these before locking funds in a balance reservation.

**Why it matters:** Without semantic dedup, a user who accidentally submits the same payment twice gets charged twice. The duplicate must be caught before the balance reservation -- otherwise the reservation locks funds for both submissions, even if one will eventually be rejected. The detection must work across paths (instant catches batch duplicates and vice versa).

**Options considered:**

- **(A) Debtor-side dedup only** -- Rely on A3/A4 to detect duplicates before publishing to Kafka. Simpler for A2, but pushes the responsibility to upstream services that may not have cross-channel visibility.

- **(B) A2-side fingerprint store** -- SHA-256 hash of (debtor_IBAN + creditor_IBAN + amount + currency), stored in PostgreSQL with a time window (10 minutes). Shared between both instances for cross-path detection.

**Decision: Option B -- PostgreSQL `payment_fingerprints` table, shared between both JVM instances.**

The fingerprint is `SHA-256(debtor_iban | creditor_iban | amount | currency)` with a pipe delimiter to prevent collisions. Detection happens before balance reservation -- a probable duplicate is rejected with reason `PROBABLE_DUPLICATE` and a reference to the earlier transaction. The payer can override the duplicate warning by resubmitting with `duplicateOverride: true`; the override is logged to the audit trail and a +0.2 risk factor is passed to A5. The PostgreSQL table is shared between instant and batch instances, providing cross-path detection. Entries are cleaned up after 20 minutes.

*ARCHITECTURE_DESIGN.md: Section 7.2.*

---

## Priority 3 -- Operational and Recovery Decisions

---

### C9 -- Recovery on Crash: Kafka Streams State Store vs. Spring Batch ExecutionContext

**The problem:** A2 has two entirely different recovery mechanisms because the instant and batch paths use different frameworks. The instant path relies on Kafka Streams' state store (RocksDB + changelog topic) -- on restart, the changelog is replayed to rebuild in-flight payment states, and the timeout punctuator detects stale entries. The batch path relies on Spring Batch's `ExecutionContext` persisted to PostgreSQL -- on restart, Spring Batch resumes from the last committed chunk. Both paths also need a database recovery scan to catch edge cases (e.g., a payment whose clearing record was written to PostgreSQL but whose state store entry was lost).

**Why it matters:** The dual recovery mechanism is a consequence of the dual-mode architecture (C1). Each mechanism is well-proven individually, but the interaction between them -- and the database recovery scan that supplements both -- must be carefully designed. The recovery must be idempotent: a payment handled by the database scan must not be re-processed when Kafka replay delivers the same message.

**Options considered:**

- **(A) Framework-native recovery only** -- Trust Kafka Streams and Spring Batch to handle their own recovery. No database scan.

- **(B) Framework-native recovery + database recovery scan on startup** -- Before passing the readiness probe, scan `clearing_records` for records in intermediate states beyond their expected timeout. Resolve stale records (reject, release reservations). Then let Kafka replay proceed. Idempotent processing ensures no conflicts.

**Decision: Option B -- Framework-native recovery supplemented by a database recovery scan.**

On startup, the database scan queries `clearing_records` for records in intermediate states (`AWAITING_RESERVATION`, `AWAITING_FRAUD`, `SETTLING`) older than their expected timeout. Stale records are set to `REJECTED` with reason `RECOVERY_TIMEOUT`, and release commands are published for outstanding reservations. Then Kafka replay proceeds -- if it redelivers a message for a record already resolved by the database scan, idempotent processing detects the existing state and skips. The service does not pass the readiness probe until recovery is complete.

For the instant path, the state store is rebuilt from the changelog topic automatically by Kafka Streams. For the batch path, Spring Batch detects incomplete `JobExecution` records and resumes from the last committed chunk.

*Master architecture reference: Decision B.1b.*
*ARCHITECTURE_DESIGN.md: Sections 10.1-10.3.*

---

### C10 -- Investigation Queues for Stuck Payments

**The problem:** Despite the timeout punctuator and crash recovery mechanisms, payments can become "stuck" in ways that require human investigation -- for example, a late sanctions HIT on a settled payment, a recurring screening timeout for the same instruction, or a reconciliation discrepancy between Kafka and PostgreSQL. These need a dedicated alerting and investigation path separate from the normal processing flow.

**Why it matters:** Operational visibility into stuck payments is a regulatory expectation. Without a dedicated investigation mechanism, operators must grep logs to find problematic payments. A dedicated topic and API provide structured, queryable access to stuck payment state.

**Options considered:**

- **(A) Log-based investigation** -- Operators search structured logs for stuck payments. No dedicated infrastructure.

- **(B) Dedicated Kafka topic + REST API** -- Stuck payments are published to `payment.investigation` with full context (current state, timestamps, screening results, reservation status). A REST API (`/api/v1/admin/investigations`) provides queryable access.

**Decision: Option B -- Dedicated `payment.investigation` Kafka topic plus operator REST API.**

Events published to `payment.investigation` include: settled-before-screening alerts (highest severity, triggers automatic pacs.004 return), late fraud blocks (escalated to case management), recurring screening timeouts (after 3 consecutive failures), and reconciliation discrepancies. The REST API at `/api/v1/admin/investigations` is read-only for the portfolio implementation, providing detail on current state, timestamps, A5 response, and A1 reservation status. Admin endpoints require the `clearing:admin` OAuth2 scope.

*Master architecture reference: Decision B.4a.*
*ARCHITECTURE_DESIGN.md: Sections 4.6, 13.3.*
