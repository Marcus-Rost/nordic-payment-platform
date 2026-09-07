# A1 Event-Sourced Account Ledger — Architectural Challenges

*Each challenge describes a problem that the architecture must resolve.*
*Challenges are grouped by theme. Within each theme, the order reflects conceptual flow, not priority.*
*For each challenge: the core tension, why it matters, which principles apply, solution approaches from research and practice, and what remains open for decision.*
*Date: 2026-04-11*

---

## How to Read This Document

Each challenge follows this structure:

1. **The tension** — what two goals or constraints are in conflict
2. **Why it matters** — what goes wrong if we get this wrong
3. **Principles in play** — which of our architectural principles (P1-P7) bear on this decision
4. **Solution approaches** — options from research literature, reference architectures, and practitioner experience, with source attribution
5. **What remains open** — what the architecture decision document must resolve

This document does NOT make decisions. It maps the problem space. Decisions are made in `ARCHITECTURE_DECISIONS.md`.

---

## Theme A: The Data Model — Events, Entries, and Accounts

### A.1 — Atomicity of Double-Entry Events

**The tension:** Double-entry bookkeeping requires that every transaction creates exactly two entries: a debit and a credit. These touch different accounts. In Kafka, different accounts live on different partitions (partition key = accountId). Writing to two partitions is not atomic without Kafka transactions. If the service crashes between publishing the debit and publishing the credit, the accounting identity is violated — money has vanished.

**Why it matters:** This is the most dangerous failure mode in a financial ledger. A violated accounting identity means the books don't balance. This is not a recoverable error in the normal sense — it requires manual investigation and correction. In a production system, it triggers regulatory alarms. **(P1: Financial Integrity Above All)**

It is also the most direct intersection of P1 with P2 (Design for Crash). The crash scenario is not hypothetical — it is the design case. The architecture must make this failure *structurally impossible*, not merely unlikely. **(P2: Design for Crash)**

**Solution approaches:**

| Approach | How it works | Tradeoffs | Source |
|----------|-------------|-----------|--------|
| **A. Single compound event** | One Kafka message containing both debit and credit entries, published to a single partition (e.g., partitioned by transactionId or a deterministic key). The projection consumer splits it into per-account balance updates | Atomic by construction — one message, one publish, one ack. If the publish fails, neither entry exists. If it succeeds, both exist. But this breaks per-account partitioning: the compound event lives on a transaction partition, not on either account's partition. The projection consumer must handle events arriving on partitions that don't match the account. Per-account ordering is maintained at the projection level, not the Kafka level | Kleppmann (*DDIA*): "Compound events should be recorded atomically in raw form, then split downstream." Master architecture LED-RES01: "debit and credit as a single compound Kafka message" |
| **B. Kafka transactions** | Use `KafkaProducer` in transactional mode. Begin transaction, publish debit to account A's partition, publish credit to account B's partition, commit. Both are visible atomically (to `read_committed` consumers) or neither is | True atomicity across partitions. Preserves per-account partitioning (each event on its account's partition). But Kafka transactions have performance overhead (coordinator round-trips), and all downstream consumers must use `read_committed` isolation level, which adds latency. Kafka transaction timeout defaults to 60 seconds — a long window for in-doubt state during crashes | Confluent: "Exactly-once semantics are possible — here's how Kafka does it." Kleppmann: Kafka transactions only cover Kafka-internal; external systems need separate idempotency |
| **C. Event per account + saga compensation** | Publish debit to account A's partition, then credit to account B's partition, independently. If the credit publish fails, publish a compensating credit to A (reversing the debit) | Simple per-event publishing. Preserves per-account partitioning. But the compensation path is complex, and the intermediate state (debit without credit) is visible to queries. During the window between debit and credit, the accounting identity is violated — even if briefly. This violates P1 | Richardson (*Microservices Patterns*): Saga with compensating transactions. However, Richardson's context is cross-service sagas. Within a single service (A1), we control both sides and don't need a saga |
| **D. Single-partition with account-pair key** | Partition by a deterministic key derived from both accounts: `min(accountA, accountB) + ":" + max(accountA, accountB)`. Both entries go to the same partition in a single message | Ordering preserved for the account pair. But breaks the "partition by accountId" guarantee — events for account A are spread across multiple partitions depending on counterparty. The projection consumer cannot rely on partition = account. Partition count and distribution become unpredictable | No strong reference for this approach in the literature. It solves atomicity but creates new problems |
| **E. Transaction topic + fan-out** | Publish a single `TransactionEvent` (containing debit + credit) to a `ledger.transactions` topic (partitioned by transactionId). A fan-out consumer reads from `ledger.transactions` and writes per-account events to `ledger.events` (or directly updates per-account projections) | Clean separation: the transaction topic is the atomic source of truth, the per-account topic (or projection) is derived. But introduces a second topic layer and a fan-out consumer that must itself be crash-safe and idempotent. The fan-out consumer becomes a critical component — if it fails, per-account views diverge from the transaction log | TigerBeetle uses a similar two-level model: transfers are the primitive (spanning two accounts), account balances are derived. Square "Books": journal entries (transactions) and book entries (per-account legs) as separate tables |

**What remains open:**
- Which approach to use. The decision must weigh: atomicity guarantee (P1, P2), simplicity (P7), impact on per-account ordering, and impact on projection design.
- If we choose compound events (A) or a transaction topic (E): how does the projection consumer maintain per-account ordering when events arrive on non-account partitions?
- If we choose Kafka transactions (B): what is the measured performance overhead, and does it fit within the instant payment time budget (100ms for reservation)?

---

### A.2 — Command Validation: Where Does the Write Path Read State?

**The tension:** To validate a command — "debit 5,000 NOK from account A" — A1 needs the current balance. But in event sourcing, the current balance is derived from events. If we replay events from Kafka for every command, that's impossibly slow at scale. If we read from the PostgreSQL projection, we introduce a dependency on a derived data store for write-path correctness.

**Why it matters:** This is the fundamental challenge of every event-sourced system. The write path needs state, but the state is the output of the read path. Getting this wrong means either:
- Accepting stale balance data for validation (risk: over-reservation, debiting below zero) — violates **P1**
- Adding unacceptable latency to the command path (replaying events per command) — violates performance requirements (LED-NF03: <100ms for reservations)
- Creating a tight coupling between the command handler and the projection consumer — complicates crash recovery (**P2**)

**Principles in play:** P1 (the balance used for validation must be correct), P4 (events are the truth, but the projection is what we can query quickly), P5 (immediate consistency for money-moving operations), P7 (simplest correct solution).

**Solution approaches:**

| Approach | How it works | Tradeoffs | Source |
|----------|-------------|-----------|--------|
| **A. Replay from Kafka per command** | On every command, seek to partition start, filter for the account, build in-memory state | Correct (reads from the source of truth). But impossibly slow: scanning millions of events per command. Only viable with a dedicated event store (EventStoreDB) that indexes per aggregate stream | Greg Young's original model assumes per-aggregate streams. Kafka doesn't provide this — it has partitioned topics. Not viable at scale without EventStoreDB |
| **B. Kafka Streams state store** | A KTable or state store (RocksDB) rebuilt from `ledger.events`. Commands query the local state store | Fast local reads. But the state store is instance-local — no shared access in multi-instance deployment. Rebalancing on restart rebuilds the state store (startup delay). Commands must be routed to the instance that owns the partition (sticky routing) | Kleppmann: Kafka Streams for stream processing with local state. Used by A5 (fraud engine) for velocity rules. Viable but adds deployment constraints |
| **C. PostgreSQL projection as validation source** | Commands read the balance from the PostgreSQL projection, validate, then publish the event | Simple. Fast (PostgreSQL index lookup). Leverages existing infrastructure. But the projection is eventually consistent with the event log. Between the moment an event is published and the projection consumer updates PostgreSQL, the projection is stale. For non-reservation commands (batch settlement), this lag is acceptable. For reservations (concurrent, sub-100ms), it is not — unless we also use approach E | Modern Treasury: reads the balance table to validate. Most production ledger systems use this approach |
| **D. Dedicated command-side projection** | A separate PostgreSQL table maintained specifically for command validation: minimal columns (accountId, availableBalance, bookedBalance, version), optimized for fast reads. Distinct from the query-side projection (which has full transaction history) | Clear separation of concerns. The command-side table can be denormalized for the write path. But it's another projection to maintain, another consumer to keep in sync. The stale-read problem from C still applies unless combined with E | Vernon (*Implementing DDD*): aggregates loaded from a dedicated event stream. In our Kafka model, the "dedicated stream" is approximated by a dedicated projection |
| **E. Synchronous projection update in the command handler** | The command handler validates against the projection, publishes the event to Kafka, and then immediately updates the PostgreSQL projection in the same code path (but not the same database transaction as the Kafka publish — there is no such transaction). The async projection consumer still runs for catch-up, rebuild, and non-hot-path events | Eliminates the lag problem for the write path. The command handler always reads the latest state because it wrote the latest state. But now there are two write paths to the same PostgreSQL table — the synchronous update in the command handler and the async projection consumer. Both must produce identical results. If they diverge, the projection is corrupted. Requires careful idempotent design: the consumer must detect that the synchronous update already applied the event and skip it | Master architecture draft for A1: "The balance reservation command handler can update the projection synchronously for the reservation case." Modern Treasury's "authorizing" mode does the same conceptually |

**What remains open:**
- Which combination of approaches to use. Pure C is insufficient for the reservation hot path. E solves the hot path but adds a second write path.
- If we use E: how do we guarantee that the synchronous update and the async consumer produce identical results? What happens if the synchronous update succeeds but the Kafka publish fails? (The projection shows a reservation that has no corresponding event — violates P4.)
- The ordering of operations in the command handler: validate → publish to Kafka → update projection? Or validate → update projection → publish to Kafka? Each ordering has different failure modes.

---

### A.3 — Event Granularity: What Is an Event?

**The tension:** We must decide the granularity of events in `ledger.events`. Are events fine-grained domain events (BALANCE_RESERVED, BALANCE_CONFIRMED, CREDIT, DEBIT, ACCOUNT_FROZEN) or coarser-grained transaction records (TRANSACTION_COMPLETED containing all legs)? This intersects directly with A.1 (double-entry atomicity) and A.2 (command validation).

**Why it matters:** Event granularity determines:
- How the projection consumer works (one event type per handler vs. a complex handler for compound events)
- How schema evolution works (more event types = more schemas to evolve independently)
- How audit trails read (fine-grained events tell a story; coarse-grained events are opaque)
- How other services consume events (A4 for AISP queries, monitoring for alerting)

**Principles in play:** P1 (events must capture the complete financial picture), P4 (events are the source of truth — they must be self-contained), P6 (events are public contracts — granularity affects the API surface), P7 (simpler event model if possible).

**Solution approaches:**

| Approach | How it works | Tradeoffs | Source |
|----------|-------------|-----------|--------|
| **A. Fine-grained domain events** | Separate event types: ACCOUNT_CREATED, DEBIT, CREDIT, BALANCE_RESERVED, BALANCE_CONFIRMED, BALANCE_RELEASED, ACCOUNT_FROZEN, ACCOUNT_UNFROZEN, ACCOUNT_CLOSED. Each event is self-contained with all fields needed to apply it | Clear, readable audit trail. Each event handler is simple. Easy to add new event types without changing existing ones. But: DEBIT and CREDIT for the same transaction are separate events — how do we guarantee atomicity (A.1)? The link between them is the transactionReference field, not structural | Greg Young: model events after business concepts. Fowler: accounting entries as event sourcing. The current Avro schema in requirements.md uses this approach |
| **B. Compound transaction events** | A single TRANSACTION event containing an array of entries: `[{accountId: A, type: DEBIT, amount: 5000}, {accountId: B, type: CREDIT, amount: 5000}]`. Lifecycle events (ACCOUNT_CREATED, ACCOUNT_FROZEN) remain separate | Atomicity by structure — the transaction is a single event with all legs. The projection consumer applies all legs together. But: the event is more complex (array of entries), and per-account ordering is no longer guaranteed by Kafka partitioning (the event is partitioned by transactionId, not accountId). Schema evolution is more constrained (changing the entry structure affects all transaction types) | Square "Books": journal entries contain multiple book entries. TigerBeetle: transfers are the primitive, spanning two accounts. Kleppmann: "record atomically in raw form, then split downstream" |
| **C. Hybrid: compound for financial movements, fine-grained for lifecycle** | Financial movements (debit+credit pairs, reservation+hold) are compound events ensuring atomicity. Account lifecycle events (created, frozen, closed) are fine-grained single-account events | Best of both: atomicity where it matters (money movements), simplicity where it doesn't (lifecycle). Two categories of events with different structures — adds complexity to the schema and consumer | No single authoritative source, but this is the implied approach in the master architecture (LED-RES01 specifies compound events for debit+credit, while the Avro schema lists fine-grained lifecycle events) |

**What remains open:**
- Which granularity model to adopt. This decision is tightly coupled with A.1 (atomicity).
- If we use compound events for financial movements: what is the Avro schema? An event with an array of entries, or a fixed two-entry structure (always exactly one debit and one credit)?
- How do compound events interact with the partition key? If partitioned by transactionId, the projection consumer must maintain per-account consistency from events arriving on non-account partitions.

---

## Theme B: Consistency and Crash Recovery

### B.1 — Read-Your-Own-Writes for Balance Reservations

**The tension:** A1 publishes a BALANCE_RESERVED event to Kafka. Milliseconds later, a second reservation request arrives for the same account. If the projection consumer hasn't yet processed the first event, the second request reads the old (pre-reservation) available balance and may approve a reservation that should be rejected (insufficient funds after the first reservation).

**Why it matters:** Over-reservation means the system has committed more funds than the account holds. This is real money — the instant payment will settle, and the account will go negative. Unlike a query returning a slightly stale balance (harmless), an over-reservation is a financial loss. **(P1: Financial Integrity)**

This is also a crash recovery concern **(P2)**: if the system crashes between publishing the event and updating the projection, the same stale-read problem occurs on restart.

**Principles in play:** P1 (over-reservation = money lost), P2 (must be correct after crash), P4 (events are truth, but truth must be queryable fast enough), P5 (immediate consistency for money-moving operations).

**Solution approaches:**

| Approach | How it works | Tradeoffs | Source |
|----------|-------------|-----------|--------|
| **A. Synchronous projection update in the command handler** | After publishing the event to Kafka, the command handler immediately updates the PostgreSQL balance projection (available_balance -= reserved amount). The next reservation request reads the updated balance. The async consumer still runs but detects the already-applied event (via idempotency key) and skips it | Solves the read-your-own-writes problem for the hot path. But creates two write paths to the projection: sync (command handler) and async (consumer). Both must be idempotent and produce identical results. If the Kafka publish succeeds but the synchronous projection update fails: the event exists but the projection doesn't reflect it — temporary inconsistency until the async consumer catches up | Master architecture draft: "synchronous projection update for the reservation case." Modern Treasury "authorizing" mode |
| **B. Serialize reservation commands through Kafka** | All reservation requests are published to a Kafka topic (e.g., `payment.balance-reservation`). A1 consumes from this topic and processes reservations sequentially per partition (partition key = accountId). The consumer reads the projection, validates, publishes the result event, and updates the projection — all in sequence | No concurrent access to the same account's balance. Per-account serialization via Kafka partitioning. But adds a Kafka round-trip to the reservation latency (publish request → consume → process → publish response). Estimated overhead: 10-30ms per hop. Within the 100ms budget (LED-NF03) but tight | This is the pattern described in the master architecture Section 9.1: A2 publishes to `payment.balance-reservation`, A1 consumes. It naturally serializes per-account if partition key = accountId |
| **C. PostgreSQL row-level locking (SELECT FOR UPDATE)** | The command handler acquires a row lock on the account balance row, reads the current balance, validates, publishes the event, updates the balance, and releases the lock. Concurrent requests for the same account wait (or SKIP LOCKED and retry) | Strong consistency via database locking. Well-understood pattern. But the lock is held while waiting for the Kafka publish to complete — if Kafka is slow (network hiccup), the lock blocks other operations on that account. Lock duration must be bounded by a timeout | Requirements LED-F15: "PostgreSQL SELECT FOR UPDATE SKIP LOCKED or optimistic locking." This is the approach described in the use cases (Scenario 1D) |
| **D. Optimistic locking with version column** | Each account balance row has a `version` column. The command handler reads balance + version, validates, publishes the event, then `UPDATE ... SET available_balance = X, version = version + 1 WHERE account_id = Y AND version = Z`. If `rows_affected = 0`, another update won the race — retry | No locks held during Kafka publish. But retry logic adds code complexity and can cause starvation under high contention (many concurrent reservations for the same account). The "loser" must re-read, re-validate, re-publish (and the original published event may need to be compensated if validation now fails) | Modern Treasury: chose optimistic locking because reads vastly outnumber writes. Fraktalio: optimistic locking in event-sourced systems. But Modern Treasury's workload is different from instant payments — fewer concurrent writes to the same account |

**What remains open:**
- Which approach or combination. B is natural for the Kafka-based architecture but adds latency. C is simple and proven but couples lock duration to Kafka latency. A solves the read problem but adds complexity. D is elegant but may not handle high contention well.
- If we use B (Kafka serialization): the reservation is already arriving via Kafka from A2. Does A1 process the reservation synchronously in the Kafka consumer (natural serialization) and respond via a Kafka response topic? This eliminates the need for REST-based reservation commands entirely.
- If we use C (row locking): what timeout do we set on the lock? What happens if Kafka publish takes >100ms while the lock is held?

---

### B.2 — Reservation Timeout: Durable Expiry of Stale Holds

**The tension:** LED-F14 requires that unreleased reservations auto-expire after a configurable timeout (default: 30 seconds). The timeout mechanism must be durable — it must survive service restarts. An in-memory timer (`ScheduledExecutorService`, `@Scheduled`) is lost on crash, leaving the reservation permanently locked.

**Why it matters:** A permanent reservation lock means the customer's funds are frozen indefinitely. This is unacceptable — the customer cannot use their own money. If A2 crashes during an instant payment and never sends a confirm or release, the reservation must still expire. **(P2: Design for Crash)**

A stolen reservation (funds locked but payment never completed) also affects the accounting picture — the available balance is permanently lower than it should be. **(P1: Financial Integrity)**

**Principles in play:** P1 (locked funds = incorrect available balance), P2 (must work after crash), P7 (simplest durable mechanism).

**Solution approaches:**

| Approach | How it works | Tradeoffs | Source |
|----------|-------------|-----------|--------|
| **A. Periodic database scan** | Store `created_at` on each reservation row. A scheduled task (every 5-10 seconds) queries: `SELECT * FROM reservations WHERE status = 'RESERVED' AND created_at < now() - interval '30 seconds'`. For each stale reservation: publish BALANCE_RELEASED event, update projection | Simple, durable (survives restarts — the database persists). The polling interval adds granularity: a reservation might live up to 30s + polling interval before expiry. Acceptable for a 30-second timeout. Low overhead (one query every few seconds) | Master architecture decision B.1b: "database scan for stale intermediate states on startup." This extends it to a continuous scan, not just at startup |
| **B. Startup-only recovery scan** | No periodic scan during normal operation. On startup, scan for stale reservations and release them. During normal operation, rely on A2 to confirm or release within the SLA | Simple. But if A2 crashes and A1 doesn't restart, stale reservations persist until A1's next restart. This could be minutes or hours. Violates P2 (the recovery must be automatic, not dependent on restart) | Master architecture decision B.1b includes startup scan. But it is necessary, not sufficient |
| **C. PostgreSQL scheduled job (pg_cron)** | Use pg_cron extension to run the cleanup query on a schedule inside PostgreSQL itself | Durable and database-native. But pg_cron has 1-minute minimum granularity — too coarse for a 30-second timeout. Also adds a PostgreSQL extension dependency | Not recommended for this use case due to granularity limitation |
| **D. Kafka delayed message** | On reservation creation, publish a "check timeout" message with a 30-second delay. When consumed, check if the reservation is still open and release it if so | Durable (in Kafka). But Kafka doesn't natively support delayed delivery. Implementing a delay mechanism requires either a dedicated delay topic with time-based consumption, or a Kafka Streams punctuator, or an external scheduler. Adds significant complexity for a problem that the database scan (A) solves simply | No standard Kafka pattern for delayed delivery. Custom implementations exist but add operational complexity |
| **E. Combination: periodic scan + startup scan** | Periodic scan (A) during normal operation. Startup scan (B) on restart (catches anything the periodic scan might have missed due to scheduling edge cases). Both are idempotent | Belt and suspenders. Slightly more code than A alone, but covers the startup case explicitly. The startup scan runs before the readiness probe passes — the service doesn't accept traffic until recovery is complete | Master architecture: "belt and suspenders is appropriate for a payment system" (decision B.1b) |

**What remains open:**
- Whether approach A (periodic scan alone) is sufficient or E (periodic + startup) is needed. E is safer but A may be sufficient if the periodic scan is reliable.
- The polling interval for the periodic scan: 5 seconds? 10 seconds? Tradeoff between timeliness and database load.
- Whether the released reservation should publish a BALANCE_RELEASED event (maintaining the event log as source of truth) or just update the projection directly (simpler but violates P4).

---

### B.3 — Idempotent Event Processing

**The tension:** Kafka provides at-least-once delivery. A consumer may receive the same event twice — after a rebalance, restart, or network retry. Processing a CREDIT event twice doubles the balance. Processing a BALANCE_RESERVED event twice doubles the reservation.

**Why it matters:** Duplicate processing directly violates the accounting identity. A doubled credit means money appeared from nowhere. **(P1: Financial Integrity)**

This also intersects with crash recovery **(P2)**: after a crash, Kafka replay from the last committed offset may redeliver events that were partially processed (event consumed, projection partially updated, offset not yet committed).

**Principles in play:** P1 (duplicate = incorrect balance), P2 (replay after crash must be safe), P7 (simplest deduplication mechanism).

**Solution approaches:**

| Approach | How it works | Tradeoffs | Source |
|----------|-------------|-----------|--------|
| **A. Idempotency key table** | Every event carries an `idempotencyKey`. Before processing, query a `processed_events` table. If found, skip. If not, process and insert the key in the same database transaction as the projection update | Correct. Simple. The dedup table grows — needs TTL/cleanup (24h for batch, 1h for instant per master architecture). The check adds one query per event | Stripe: idempotency key with 24-hour TTL. Master architecture B.3a: hierarchical idempotency keys |
| **B. Kafka exactly-once semantics** | Use `exactly_once_v2` processing guarantee in Kafka Streams or transactional consumer | Kafka handles deduplication internally for Kafka-to-Kafka processing. But our consumer writes to PostgreSQL — EOS does not cover the PostgreSQL write. A Kafka transaction can atomically commit the consumer offset and produce to another topic, but it cannot include a PostgreSQL write | Kleppmann: "exactly-once across system boundaries requires at-least-once + idempotent consumers." Confluent: Kafka EOS covers Kafka-internal only |
| **C. Conditional database write** | `INSERT INTO transaction_history (..., idempotency_key) ON CONFLICT (idempotency_key) DO NOTHING`. The unique constraint on `idempotency_key` rejects duplicates structurally. For balance updates: `UPDATE account_balances SET balance = balance + amount, last_event_offset = :offset WHERE account_id = :id AND last_event_offset < :offset` | Pushes dedup into the data model. No separate dedup table. The unique constraint is the dedup mechanism. But: the `UPDATE WHERE offset < :offset` approach assumes events arrive in order, which is true per partition (per account) but may not be true after consumer group rebalancing across partitions | CockroachDB engineering blog: "Idempotency and ordering in event-driven systems." Domain Centric: "embedded" dedup variant storing checkpoint in the read model row |
| **D. Three-layer defense in depth** | Layer 1: API-level dedup (unique constraint on command table). Layer 2: Kafka dedup (idempotent producer). Layer 3: consumer-level dedup (idempotency key table or conditional write). Each layer independently tolerates duplicates. No layer trusts the others | Maximum safety. Each layer catches what the others miss. More code, but each layer is individually simple. Aligns with "belt and suspenders" philosophy | Stripe: three-layer defense in depth (API dedup, outbox, consumer dedup) |

**What remains open:**
- Whether approach A (idempotency key table) is sufficient or D (three-layer) is warranted. P2 argues for D; P7 argues for A.
- The TTL for idempotency keys. Too short: replay after a long outage could reprocess events whose keys have been cleaned up. Too long: the table grows large. The TTL must be longer than the worst-case replay window.
- Whether to use the `processed_events` table (A) or the conditional-write approach (C). C is more elegant but more complex to get right.

---

### B.4 — Graceful Shutdown and In-Flight Operations

**The tension:** On SIGTERM (rolling deployment, scaling event), A1 must stop accepting new work and complete in-flight operations. But "in-flight" has multiple meanings: a balance reservation being processed, a batch of settlement events being consumed, a projection update in progress. If A1 shuts down with a reservation half-processed, the reservation is orphaned.

**Why it matters:** An orphaned reservation locks customer funds. An uncommitted Kafka offset means events will be reprocessed on restart (must be idempotent — B.3). A half-written projection update could leave the projection in an inconsistent state. **(P2: Design for Crash)**

**Principles in play:** P2 (in-flight operations must not leave inconsistent state), P1 (orphaned reservations = locked funds), P7 (simple shutdown logic).

**Solution approaches:**

| Approach | How it works | Tradeoffs | Source |
|----------|-------------|-----------|--------|
| **A. Drain and complete** | On SIGTERM: (1) stop accepting new REST commands, (2) stop polling Kafka for new messages, (3) complete all in-flight operations (reservations, projection updates), (4) commit all Kafka offsets, (5) close database connections, (6) exit | Clean shutdown. All work completed. But: how long to wait? If an in-flight operation is blocked on Kafka (slow publish), the shutdown could hang. Need a maximum drain timeout | Master architecture STARTER-F40-F43: graceful shutdown. LED-RES04: complete in-flight balance reservations before closing. Nygard (*Release It!*): graceful shutdown as a stability pattern |
| **B. Drain with timeout, then abort** | Same as A, but with a maximum drain period (e.g., 10 seconds). If operations are still in-flight after the timeout: commit offsets for completed work, abandon incomplete operations. The reservation timeout (B.2) serves as the safety net for abandoned reservations | Bounded shutdown time. The reservation timeout mechanism catches anything that was abandoned. But: abandoned operations may leave the projection temporarily inconsistent until the async consumer catches up on restart | Kubernetes: `terminationGracePeriodSeconds` (default 30s). The reservation timeout (B.2) is explicitly designed as the safety net for this case |

**What remains open:**
- The maximum drain timeout. Must be shorter than Kubernetes' `terminationGracePeriodSeconds` to allow orderly container termination.
- Whether to abort or compensate in-flight operations that exceed the drain timeout. For reservations: abort is safe (the timeout in B.2 will clean up). For projection updates: the idempotent consumer (B.3) will reprocess on restart.

---

## Theme C: The Command Path — How Operations Enter and Execute

### C.1 — Command Entry Points: REST, Kafka, or Hybrid

**The tension:** A1 receives commands from multiple sources with different characteristics:

| Source | Command | Latency Requirement | Arrival Mechanism |
|--------|---------|-------------------|-------------------|
| A2 (instant) | Reserve / Confirm / Release balance | <100ms (LED-NF03) | Kafka topic `payment.balance-reservation` (per master architecture) |
| A2 (batch) | Debit + Credit (settlement) | Seconds acceptable | Kafka topics `payment.settlement-events`, `payment.instant-settlement-events` |
| A5 | Freeze account | Seconds acceptable | Kafka topic `account.freeze-commands` |
| Admin/Operator | Create account, trigger reconciliation | Seconds acceptable | REST API |
| A4 | Balance query (read-only) | <50ms (LED-NF01) | REST API (synchronous) |
| A6 | Account holder name lookup (read-only) | <200ms | REST API (synchronous) |

The question is: should all command paths be unified (everything through Kafka) or should latency-sensitive operations use REST?

**Why it matters:** The command entry point determines:
- The consistency model (Kafka consumers process in partition order — natural serialization; REST handlers process concurrently — need explicit locking)
- The crash recovery model (Kafka messages are durable; REST requests are not)
- The latency budget (Kafka adds at least one publish-consume hop; REST is direct)

**Principles in play:** P2 (durable command delivery), P5 (immediate consistency for reservations), P7 (simplest correct model), P4 (all state changes should ideally flow through the event log).

**Solution approaches:**

| Approach | How it works | Tradeoffs | Source |
|----------|-------------|-----------|--------|
| **A. All commands via Kafka** | Every state-changing command arrives as a Kafka message. A1 is a pure Kafka consumer for commands. REST endpoints exist only for queries and admin operations | Uniform command model. All commands are durable (in Kafka). Natural per-account serialization (partition key = accountId). Crash-safe: uncommitted messages are replayed. But: reservation latency includes a Kafka round-trip (A2 publishes request, A1 consumes, processes, publishes response, A2 consumes response). Estimated: 20-50ms for the Kafka hops, leaving 50-80ms for processing. Tight but possible | This is the model implied by the master architecture Section 9.1: A2 communicates with A1 via Kafka for the clearing path. P4 argues for this: all state changes flow through the event infrastructure |
| **B. REST for reservations, Kafka for everything else** | Balance reserve/confirm/release via synchronous REST. Settlement and freeze commands via Kafka. The REST handler publishes events to Kafka and updates the projection synchronously | Low latency for reservations (no Kafka round-trip for the command). But: REST requests are not durable — if A1 crashes after receiving the REST request but before publishing the event, the reservation is lost (A2 must retry). Two different command entry points with different consistency and durability characteristics | Common in practice. A4 queries A1 via REST (already established in the master architecture). But the master architecture also routes balance reservations through Kafka |
| **C. Kafka for commands, REST for queries** | Commands arrive via Kafka (including reservations). Queries arrive via REST. A2 publishes a reservation request to Kafka, A1 processes it in the Kafka consumer (serialized per account), publishes the result to a response topic. A2 consumes the response | Clean separation: Kafka for all writes, REST for all reads. Natural serialization eliminates the need for row locking (B.1 option B). But: the request-response pattern over Kafka requires correlation ID management, timeout handling, and a response topic | Master architecture Section 9.1 and 5.1: "State changes flow through Kafka. Queries use synchronous REST." This is the stated communication principle |

**What remains open:**
- Whether the latency budget for Kafka-based reservations (A, C) is achievable. This depends on Kafka infrastructure performance (broker latency, consumer poll interval).
- If we use REST for reservations (B): how does A2 handle A1 being unavailable? Retry? Circuit breaker? This must be coordinated with A2's architecture.
- If we use Kafka for reservations (A, C): the correlation ID and response topic mechanism must be designed. How does A2 match a response to its request? Timeout handling: what if A1 doesn't respond within 100ms?

---

## Theme D: Security Architecture

### D.1 — Trust Boundaries and Access Control

**The tension:** A1 holds the most sensitive financial data in the system — account balances, transaction history, account holder names. It serves multiple consumers (A2, A4, A5, A6) with different trust levels and different data needs. The architecture must enforce that each consumer accesses only the data it needs, through authenticated channels.

**Why it matters:** A compromised A4 (exposed to external TPPs) should not be able to debit accounts or access the full transaction history of all accounts. A compromised A5 (processing sanctions data) should not be able to modify balances. The principle of least privilege must be structurally enforced. **(P3: Security by Construction)**

**Principles in play:** P3 (security by construction — structural access control, not just policy), P7 (simplest effective access model).

**Access matrix (what each consumer needs):**

| Consumer | Data Needed | Operation | Auth Mechanism |
|----------|------------|-----------|---------------|
| A2 (Kafka) | Balance reservation, settlement | Reserve, confirm, release, debit, credit | HMAC-signed Kafka messages (per-producer key) |
| A4 (REST) | Balance, transaction history (AISP) | Read-only queries | mTLS + OAuth2 scope `accounts:read` |
| A5 (Kafka) | Account freeze commands | Freeze/unfreeze account | HMAC-signed Kafka messages |
| A5 case mgmt (REST) | Account holder name, balance (for analyst display) | Read-only queries | mTLS + restricted scope |
| A6 (REST) | Account holder name by IBAN | Name lookup | mTLS + restricted scope `vop:read` |
| Reconciliation (REST) | Settlement report comparison | Trigger reconciliation | mTLS + admin scope |

**What remains open:**
- Whether A1 verifies HMAC on every Kafka message or trusts the Kafka SASL layer. The master architecture (KM-06) specifies HMAC verification. But this adds per-message cryptographic overhead.
- How fine-grained the OAuth2 scopes are. Is `accounts:read` sufficient, or do we need `accounts:read:balance`, `accounts:read:transactions`, `accounts:read:holder-name` to enforce A6's restricted access?
- Whether domain primitives (P3) should enforce access control at the type level — e.g., a `HolderNameAccess` type that can only be constructed by services with the `vop:read` scope.

---

### D.2 — Crypto-Shredding: PII Protection in an Immutable Event Log

**The tension:** Events in `ledger.events` are immutable and retained forever (P4). GDPR Article 17 grants the right to erasure. These directly contradict each other. The accepted solution is crypto-shredding: encrypt PII with a per-account key and delete the key when erasure is requested. But this affects every component that reads events.

**Why it matters:** Failure to comply with GDPR erasure requests exposes the system operator to regulatory penalties. But incorrect implementation of crypto-shredding could break balance calculations, projection rebuilds, or VoP queries. **(P3: Security by Construction)** intersects with **(P1: Financial Integrity)** — erasure must not corrupt financial data.

**Principles in play:** P3 (PII protection is non-negotiable), P1 (financial integrity must survive erasure), P4 (events are the source of truth — they must be readable for financial purposes even after erasure), P7 (simplest implementation that satisfies P3 and P1).

**Solution approaches:**

| Approach | How it works | Tradeoffs | Source |
|----------|-------------|-----------|--------|
| **A. Encrypt PII fields in the event payload** | Before publishing, encrypt account holder name, address, and transaction description with per-account AES-256-GCM key. IBAN, amounts, timestamps remain plaintext. On key deletion, encrypted fields become irrecoverable | Balanced: financial calculations work without decryption (amounts are plaintext). PII is protected. But: the encrypted ciphertext is larger than the plaintext, increasing event size. Every consumer must handle decryption (adds dependency on KeyProvider). After key deletion, consumers see `[REDACTED]` — the projection must handle this gracefully | Verraes: crypto-shredding pattern for event-sourced systems. Master architecture decision F.1a: encrypt name, address, description; leave IBAN, amounts, timestamps in plaintext |
| **B. Forgettable Payloads (separate PII store)** | Store PII in a separate, deletable PostgreSQL table (account_pii). Events carry only a reference (`piiRef: "pii-001"`). The projection consumer joins PII from the separate store. For erasure: delete the PII record. Events are unaffected | Clean separation — events contain no PII at all. No encryption overhead on the event path. Projection rebuild doesn't need decryption. But: every consumer that needs PII must query the PII store (join overhead). The PII store becomes a dependency for VoP queries. If the PII store is unavailable, name-dependent operations fail. After PII deletion, projection rebuild produces events with unresolvable references | Verraes recommends this as a complement to crypto-shredding. Kurrent blog: "Forgettable Payloads pattern" |
| **C. Encrypt entire event payload** | Encrypt everything except the partition key and event type | Maximum privacy. But amounts are encrypted — projection rebuild requires decryption for every event. Key deletion breaks balance calculations. Violates P1 | Not recommended. Breaks P1 |

**What remains open:**
- Whether to use approach A (encrypt PII in events) or B (separate PII store). A is simpler (all data in the event) but adds encryption to the event pipeline. B is cleaner (no PII in events) but adds a join dependency.
- If we use A: the key storage model. Per-account keys in A1's PostgreSQL, encrypted with the master key? Or a separate key table?
- How the projection handles erased accounts: `[REDACTED]` for name fields? `ACCOUNT_CLOSED` status? What about VoP queries from A6 for an erased account?
- The encryption performance impact. AES-256-GCM is fast (~1M ops/sec), but the overhead per event should be measured.

---

### D.3 — Audit Trail Tamper Evidence

**The tension:** The event log in Kafka is append-only, which prevents application-level modification. But a Kafka administrator with broker access could delete, modify, or insert messages. The architecture must provide evidence that the audit trail has not been tampered with, for regulatory compliance and legal proceedings.

**Why it matters:** In a regulatory audit or dispute resolution, the system must demonstrate that the event log is complete and unmodified. "We used Kafka" is not sufficient evidence — Kafka does not provide cryptographic proof of integrity. **(P3: Security by Construction)** — tamper evidence must be architectural, not trust-based.

**Principles in play:** P3 (security by construction — tamper evidence, not trust in infrastructure), P4 (events are the source of truth — their integrity must be verifiable), P7 (simplest mechanism that provides meaningful evidence).

**Solution approaches:**

| Approach | How it works | Tradeoffs | Source |
|----------|-------------|-----------|--------|
| **A. Hash chain** | Each event includes a SHA-256 hash of the previous event. Tampering with any event invalidates all subsequent hashes. A periodic integrity check walks the chain and verifies consistency | Simple to implement. Sequential verification: O(n) to verify the full chain. If an attacker modifies an event and recomputes all subsequent hashes, the chain looks valid — mitigated by anchoring (publishing periodic root hashes to external storage) | Master architecture F.2a: hash chaining for the portfolio. Crosby & Wallach (USENIX 2009): efficient tamper-evident logging |
| **B. Merkle tree** | Hierarchical hash structure over the event log. Enables O(log n) inclusion proofs — any client can verify a single event without recomputing the entire log. Root hash anchored externally | More powerful: efficient verification, inclusion proofs, partial verification. But more complex to implement. Requires maintaining the tree structure alongside the event log. Google's Trillian is a reference implementation | Crosby & Wallach: Merkle trees for tamper-evident logging. Google Trillian (transparency.dev) |
| **C. Per-event digital signatures** | Each event is signed by the producing service's private key. Verifiers check the signature with the corresponding public key. Provides non-repudiation: proves which service produced the event | Strong: even if the event is copied or moved, the signature travels with it. But: key management for signing keys (rotation, distribution). Signature verification adds latency to every consumer. Private key compromise means past events' signatures are still valid but future events could be forged | Master architecture F.2a (production target): "signatures + snapshots." NIST SP 800-57 for signing key management |

**What remains open:**
- Which approach for the portfolio implementation. A (hash chain) is simplest and may be sufficient. B (Merkle tree) is more powerful but more complex. C (signatures) adds non-repudiation but significant key management overhead.
- The anchoring mechanism: how frequently, where are root hashes stored? External signed checkpoint? A separate topic? A file?
- Whether A1 implements tamper evidence itself or delegates to the Platform Starter (which auto-configures audit event hash chaining per the master architecture).

---

## Theme E: Performance and Scalability

### E.1 — Projection Rebuild Performance

**The tension:** LED-F09 requires rebuilding all projections from the event log. LED-NF05 targets <5 minutes for 1M events. As the event log grows (infinite retention), rebuild time grows linearly unless optimized.

**Why it matters:** Projection rebuild is not a rare operation — it's the mechanism for recovering from projection corruption, schema changes, and adding new derived views. If rebuild takes hours, the system is effectively unrepairable without downtime. **(P4: events are the source of truth)** is meaningless if we can't practically derive state from them.

**Principles in play:** P4 (projections must be rebuildable from events — and this must be practical, not theoretical), P7 (simplest approach that meets the performance target).

**Solution approaches:**

| Approach | How it works | Tradeoffs | Source |
|----------|-------------|-----------|--------|
| **A. Full replay from offset 0** | Reset consumer offset to 0, replay all events, rebuild all projections | Simple. Correct. But: 1M events at 1K events/sec (LED-NF02) = ~17 minutes. Exceeds the 5-minute target. At 10M events: ~170 minutes. Does not scale | Baseline approach. Insufficient alone |
| **B. Periodic snapshots** | Store (accountId, balance, currencies, lastEventOffset) every N events per account. Rebuild starts from the latest snapshot, replays only subsequent events | Rebuild time bounded by events-since-last-snapshot, not total events. Snapshot creation adds write overhead (one extra write per N events). Snapshot correctness must be verified (a corrupt snapshot corrupts the rebuild) | Vernon: snapshots as optimization for aggregate rehydration. Requirements LED-F40-F42. Monzo: learned that O(n) balance calculation does not scale |
| **C. Parallel replay** | Assign Kafka partitions to multiple consumer threads. Each thread rebuilds its portion independently. With P partitions and P threads, rebuild is ~P times faster | Near-linear speedup with parallelism. Projection writes must be partition-isolated to avoid conflicts. But: partition count is fixed at topic creation — needs sufficient partitions from the start | Standard Kafka parallelism model |
| **D. Snapshots + parallel replay** | Combine B and C: snapshots reduce the event volume, parallel consumers reduce the wall-clock time | Achieves the 5-minute target with large event volumes. More complex operationally | The recommended combination. No single source — standard engineering practice |

**What remains open:**
- Snapshot frequency: every 1,000 events per account (LED-F40) — is this sufficient? With 10,000 accounts and 100 events/account since last snapshot = 1M events to replay. At 1K events/sec single-threaded = 17 minutes. With 12 partitions = ~1.5 minutes. Sufficient.
- How snapshots interact with crypto-shredding: does a snapshot contain encrypted PII? If the key is deleted, can the snapshot still be used? (Yes, if the snapshot stores the encrypted ciphertext, not the decrypted value. But if the snapshot stores plaintext balances + encrypted PII, key deletion doesn't affect balance correctness.)
- Whether to verify snapshot integrity before using it for rebuild (hash of the snapshot compared against the events that produced it).

---

### E.2 — Projection Lag Under Burst Load

**The tension:** During batch settlement, A2 publishes thousands of settlement events in a short burst. The projection consumer must process all of them to keep the PostgreSQL projection current. During this burst, projection lag may grow significantly — meaning balance queries return stale data.

**Why it matters:** Stale balance data during a settlement burst is acceptable for informational queries **(P5: eventual consistency for reads)**. But if instant payment reservations arrive during the burst, they need current data **(P5: immediate consistency for reservations)**. If the reservation hot path uses the synchronous projection update (B.1), it is unaffected by consumer lag. If it reads from the async projection, it may over-reserve.

**Principles in play:** P5 (the reservation path must not be affected by batch lag), P1 (over-reservation during burst = money lost), P7 (don't over-engineer the batch path).

**What remains open:**
- Whether the reservation hot path is fully independent of the async projection consumer. If it uses the synchronous update (B.1 approach A or C), burst lag doesn't affect it.
- Whether to prioritize instant settlement events over batch settlement events in the consumer (separate consumer groups with different priorities, or a single consumer that processes all settlement events in order).
- Monitoring and alerting thresholds for projection lag. The master architecture specifies: alert if lag > 1 second.

---

## Theme F: Data Integrity Across Time

### F.1 — Event Schema Evolution

**The tension:** The Avro schema for `ledger.events` will evolve over the system's lifetime. New fields will be added, event types may be introduced, field semantics may shift. Every schema version ever published must remain readable, because projection rebuilds replay all events from the beginning.

**Why it matters:** A careless schema change can make historical events unreadable, breaking projection rebuild — which breaks P4 (events are the source of truth). Schema evolution failures are insidious: they don't manifest until someone tries to rebuild, which may be months after the breaking change was introduced. **(P6: Events Are Public Contracts)**

**Principles in play:** P6 (events are permanent contracts), P4 (all events must remain readable forever), P7 (simple evolution rules, strictly enforced).

**What remains open:**
- Whether BACKWARD compatibility (new schema can read old data) is sufficient or FULL compatibility (bidirectional) is needed. BACKWARD is less restrictive and is the Schema Registry default. FULL is safer during rolling deployments (old and new instances coexist).
- How the projection consumer handles events from multiple schema versions. Does Avro's schema resolution handle it transparently (reader schema has defaults for new fields), or do we need version-specific deserialization logic?
- How to handle a semantic change (a field that previously meant X now means Y) — this cannot be detected by Schema Registry compatibility checks. Must be caught by code review discipline.

---

### F.2 — Reconciliation Architecture

**The tension:** A1 must daily compare its internal ledger entries against A2's settlement reports to detect discrepancies. This is a batch-oriented, data-intensive process that runs alongside real-time transaction processing. The reconciliation must be thorough (catch all discrepancies) without affecting the real-time path's performance.

**Why it matters:** Reconciliation discrepancies mean money is unaccounted for. A discrepancy could indicate: a missed event (Kafka consumer lag at settlement time), double-processing (idempotency failure), an A2 settlement error, or a system integrity breach. **(P1: Financial Integrity)** — discrepancies are operationally critical.

**Principles in play:** P1 (discrepancies = money unaccounted for), P4 (event log enables replay-based reconciliation — stronger than querying mutable database), P7 (simplest matching mechanism).

**What remains open:**
- Whether to reconcile against the PostgreSQL projection (fast, but projection could be wrong) or against an event replay for the reconciliation period (slower, but uses the source of truth). Or both — projection for speed, event replay for verification.
- The matching key: `externalReference` (clearingCycleId + settlementReference) per LED-F36. What if the external reference is missing or malformed?
- How to handle partial matches (amount discrepancy within tolerance vs. exact match required). Financial systems typically require exact-amount matching.
- Whether reconciliation runs as a synchronous REST-triggered operation or as an asynchronous batch job. The requirements (LED-F38) specify a REST trigger with a response — this suggests synchronous, but large settlement reports may take too long for a synchronous response.

---

## Theme G: Testing Architecture

### G.1 — Testing an Event-Sourced System

**The tension:** Event sourcing introduces testing challenges not present in CRUD systems: event serialization round-trips across schema versions, projection correctness after replay, idempotent processing under duplicate delivery, concurrent reservation behavior under load, crypto-shredding round-trip (encrypt, persist, rebuild, decrypt), and the accounting invariant (debits = credits) across all scenarios.

**Why it matters:** The accounting invariant **(P1)** must be verified not just for happy-path scenarios but for all edge cases: out-of-order events, duplicate events, concurrent reservations, schema-evolved events, and events with shredded PII. Traditional unit tests are insufficient; the testing strategy must cover the event-sourcing-specific failure modes.

**Principles in play:** P1 (the invariant must be testable), P2 (crash scenarios must be tested), P4 (projection rebuild must be tested as a regular operation), P7 (testing infrastructure should be as simple as possible).

**Solution approaches:**

| Approach | What it tests | Tools | Source |
|----------|-------------|-------|--------|
| **In-memory event store for unit tests** | Domain logic: command validation, event generation, projection application. Fast, deterministic, no infrastructure | JUnit 5, custom in-memory event store | Standard event sourcing testing pattern |
| **Testcontainers for integration tests** | Full pipeline: Kafka serialization, Schema Registry compatibility, PostgreSQL projection, consumer behavior | Testcontainers (Kafka, PostgreSQL, Schema Registry), Spring Boot Test | Tietoevry uses Testcontainers. Requirements specify this |
| **ArchUnit for pattern enforcement** | Structural: no direct writes to projection outside consumer, all events Avro-serializable, all command handlers check idempotency | ArchUnit | SpareBank 1 testing culture. Requirements specify this |
| **Property-based testing for invariants** | Correctness: generate random command sequences, verify debits = credits after every sequence, regardless of ordering | JUnit 5 + jqwik or similar | Nubank: property-based testing for accounting invariants |
| **Contract testing (Pact)** | Inter-service: A2's settlement events match A1's consumer expectations. A4's balance query matches A1's response format | Pact (message + REST contracts) | Master architecture: Pact for inter-service contracts |

**What remains open:**
- Whether property-based testing for the accounting invariant is worth the setup effort. It provides the strongest correctness guarantee but is more complex than scenario-based tests.
- How to test crash recovery: inject crashes during event processing (Testcontainers can stop/restart containers). What assertions verify correct recovery?
- How to test the synchronous projection update path (B.1) doesn't diverge from the async consumer path — same input must produce identical output.

---

## Summary: Challenge Index

| ID | Challenge | Theme | Key Principles | Key Question |
|----|-----------|-------|---------------|-------------|
| A.1 | Double-entry atomicity | Data Model | P1, P2 | How do we ensure debit and credit are never published separately? |
| A.2 | Command validation state source | Data Model | P1, P4, P5 | Where does the write path read current balance from? |
| A.3 | Event granularity | Data Model | P1, P4, P6 | Fine-grained domain events, compound transactions, or hybrid? |
| B.1 | Read-your-own-writes for reservations | Consistency | P1, P2, P5 | How do concurrent reservations see the latest available balance? |
| B.2 | Durable reservation timeout | Consistency | P1, P2 | How do stale reservations auto-expire, surviving crashes? |
| B.3 | Idempotent event processing | Consistency | P1, P2 | How do we prevent duplicate events from double-counting? |
| B.4 | Graceful shutdown | Consistency | P1, P2 | How do we stop cleanly without orphaning reservations? |
| C.1 | Command entry points | Command Path | P2, P4, P5 | REST, Kafka, or hybrid for commands? |
| D.1 | Trust boundaries and access control | Security | P3 | Who can access what, enforced structurally? |
| D.2 | Crypto-shredding | Security | P1, P3, P4 | How does GDPR erasure work without breaking financial integrity? |
| D.3 | Audit tamper evidence | Security | P3, P4 | How do we prove the event log hasn't been tampered with? |
| E.1 | Projection rebuild performance | Performance | P4, P7 | How do we rebuild projections from millions of events in minutes? |
| E.2 | Projection lag under burst | Performance | P1, P5 | How do we prevent batch bursts from affecting instant payments? |
| F.1 | Event schema evolution | Data Integrity | P4, P6 | How do immutable events handle schema changes over time? |
| F.2 | Reconciliation architecture | Data Integrity | P1, P4 | How do we compare internal events against external settlement reports? |
| G.1 | Testing strategy | Testing | P1, P2, P4 | How do we test event-sourcing-specific failure modes? |

---

## Challenge Dependencies

Some challenges are tightly coupled — the decision on one constrains the options for another:

```
A.1 (double-entry atomicity) ←──→ A.3 (event granularity)
  │                                     │
  │  The atomicity mechanism determines  │
  │  the event structure and vice versa  │
  │                                     │
  v                                     v
A.2 (command validation) ←──→ B.1 (read-your-own-writes)
  │                                     │
  │  Where commands read state determines│
  │  how reservations see current balance│
  │                                     │
  v                                     v
C.1 (command entry points) ──→ B.1 (read-your-own-writes)
  │                                     │
  │  Kafka vs. REST for reservations     │
  │  determines whether natural Kafka    │
  │  serialization solves B.1            │
  v
B.3 (idempotency) ←── B.4 (graceful shutdown)
  │
  │  Idempotent processing is the safety
  │  net for both replay and shutdown
  v
B.2 (reservation timeout) ←── B.4 (graceful shutdown)
  │
  │  Timeout is the safety net for
  │  abandoned reservations during shutdown

D.2 (crypto-shredding) ──→ E.1 (projection rebuild)
  │
  │  Encryption in events affects
  │  rebuild behavior after key deletion
```

The recommended resolution order follows these dependencies: **A.1 + A.3 first** (they determine the event model), then **A.2 + B.1 + C.1** (they determine the command flow), then **B.2, B.3, B.4** (crash recovery mechanisms), then **D.1, D.2, D.3** (security), then **E.1, E.2, F.1, F.2, G.1** (performance, evolution, testing).
