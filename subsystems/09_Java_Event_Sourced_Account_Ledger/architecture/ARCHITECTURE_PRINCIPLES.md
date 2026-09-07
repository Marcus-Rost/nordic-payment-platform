# A1 Event-Sourced Account Ledger — Architectural Principles

*These principles guide every architectural decision for the A1 sub-system.*
*When two principles conflict, the higher-ranked one wins.*
*Date: 2026-04-11*

---

## Principle Priority Order

P1 > P2 > P3 > P4 > P5 > P6 > P7

---

## P1: Financial Integrity Above All

**Statement:** The accounting identity — sum of all debits equals sum of all credits, at all times, under all conditions — is the system's non-negotiable invariant. No crash, no race condition, no bug in another service, no operational error may violate it. If a design choice risks the accounting identity, that choice is wrong, regardless of how it scores on other criteria.

**Rationale:** This is what makes a ledger a ledger. If debits don't equal credits, money has appeared or vanished. Every other property (performance, availability, usability) is worthless if the books don't balance.

**Sources:**
- Square's "Books": "All journal entries must balance to zero" — enforced at the database level, not application level
- TigerBeetle: The accounting invariant is enforced by the database engine itself, not by application code
- Fowler's Accounting Patterns: The double-entry constraint is the foundational integrity mechanism
- Nubank: Property-based testing generates thousands of random event sequences to verify the invariant holds regardless of ordering

**What this drives:**
- Atomic double-entry events — structurally impossible to publish a debit without its matching credit
- Idempotent processing — a duplicated event must not double-count
- Reversal adjustments, never deletion — corrections are new offsetting entries, preserving the complete trail
- Continuous verification — the invariant can be checked at any time by summing all events

---

## P2: Design for Crash, Not Around Crash

**Statement:** The system must tolerate a hard kill (SIGKILL, power loss, OOM) at any point in any operation without losing money, duplicating transactions, or leaving the system in an unrecoverable state. Every intermediate state must have a defined, automatic recovery path. No financial state may exist only in memory.

**Rationale:** Payment systems are high-value targets for denial-of-service attacks, and hardware fails without warning. "What happens if we crash right now?" must have a safe answer at every line of code that touches money.

**Sources:**
- TigerBeetle: Entire design philosophy — no dynamic allocation, deterministic simulation of crashes, hash-chaining for integrity
- Nygard (*Release It!*, 2nd ed., 2018): Timeouts, steady state, fail fast — stability patterns as architectural primitives
- Nubank: Circuit breakers that convert exceptions into Kafka lag — the backpressure mechanism IS the recovery mechanism
- Master architecture RES-01 through RES-11

**What this drives:**
- Durable reservation timeouts (database-scheduled, not in-memory timers)
- Startup recovery scan for stale intermediate states
- Kafka replay from last committed offset with idempotent consumers
- Graceful shutdown that completes or compensates in-flight operations
- No two-phase state where a crash between phases leaves the system inconsistent

---

## P3: Security by Construction

**Statement:** Security is a structural property of the architecture, not a feature to be added. The system must make the insecure path harder than the secure path. Invalid financial values must be structurally impossible through the type system. Authentication must be default-on. The audit trail must be tamper-evident by design.

**Rationale:** Security retrofitted onto a financial system is always incomplete. Domain primitives eliminate entire classes of vulnerabilities at the type level.

**Sources:**
- Johnsson, Deogun & Sawano — *Secure by Design* (Manning, 2019): Domain primitives encapsulate validation and invariants
- OWASP ASVS Level 3: Required for high-value transaction systems
- OWASP Microservices Security: Defense in depth, mTLS, decouple external tokens from internal representations
- NIST SP 800-57: Cryptographic key management lifecycle

**What this drives:**
- Domain primitives for IBAN, MonetaryAmount, AccountId, Currency — validated at construction, immutable
- Authenticated-by-default (Platform Starter enforces `anyRequest().authenticated()`)
- mTLS for all internal REST calls, HMAC-SHA256 on all Kafka messages
- Per-account encryption keys for crypto-shredding
- Hash-chained audit events for tamper evidence
- Command API accessible only to internal services (never from external zone)

---

## P4: The Event Log Is the Only Source of Truth

**Statement:** The Kafka `ledger.events` topic, with infinite retention, is the single authoritative record of all financial state. Everything else — PostgreSQL projections, caches, snapshots, derived views — is disposable and rebuildable from the event log. No authoritative financial data may exist only in a derived store.

**Rationale:** This is the core event sourcing principle. For a ledger it has regulatory weight: the event log IS the audit trail. If PostgreSQL is corrupted or diverges, we can detect it by comparing against the event log. If PostgreSQL is destroyed, we can rebuild it completely.

**Sources:**
- Kleppmann (*DDIA*, Ch. 11): Immutable event logs as the foundation, mutable state as materialized views
- Greg Young: The event store serves as both store and queue, eliminating two-phase commit
- Griffin: "Balance is never stored as a mutable figure — it is calculated from the net sum of debits and credits"
- Fowler: "Accounting Entries are event sourcing" — natural synergy between accounting and event sourcing

**What this drives:**
- `ledger.events` topic: infinite retention, replication factor 3, min.insync.replicas 2, acks=all
- PostgreSQL projections are derived — rebuild from scratch is a testable operation
- No "shadow writes" to PostgreSQL that bypass the event log
- Crypto-shredding encrypts PII in the events themselves, not just in the projection
- Snapshots are an optimization, not a replacement for the event stream

---

## P5: Immediate Consistency Where Money Moves, Eventual Consistency Where It Doesn't

**Statement:** Operations that change account balances or create financial commitments (reservations) require immediate consistency — the system must reflect the change before acknowledging it. Read-only queries (balance lookups, transaction history, statements) may tolerate eventual consistency with bounded lag.

**Rationale:** CQRS inherently introduces a consistency gap between write model and read model. For most queries, this gap (typically <50ms) is invisible. But for balance reservations — where two concurrent requests race against the same available balance — stale data causes over-reservation, which means real money lost.

**Sources:**
- Modern Treasury: Two operational modes — "recording" (async, high-throughput) and "authorizing" (real-time with balance locks)
- Monzo: Balance read optimization was a critical project when P99 hit 400-500ms — stale balances threatened card authorization SLAs
- Master architecture: The "hot-path exception" for balance reservations — synchronous projection update in the command handler

**What this drives:**
- Balance reservation path: synchronous projection update in the command handler (bypasses consumer lag)
- Balance queries: read from async projection (eventual consistency, bounded by consumer lag)
- Reservation concurrency: PostgreSQL row-level locking on the projection (the consistency gatekeeper)
- Monitoring: projection lag metric with alerting threshold

---

## P6: Events Are Public Contracts

**Statement:** Once an event schema is published to `ledger.events`, it becomes a permanent, versioned API. Events must be modeled after business concepts (BALANCE_RESERVED, CREDIT, DEBIT), not technical operations (ROW_UPDATED, CACHE_INVALIDATED). Schema evolution must be strictly additive. Breaking changes require a new topic.

**Rationale:** The event log has infinite retention. Every schema version ever published must remain readable forever, because projection rebuilds replay all events from the beginning.

**Sources:**
- Greg Young: "Events are public contracts. Once published, they become durable APIs."
- InfoQ (Event-Driven Banking, 2024): "Event contracts are permanent — treat as public APIs"
- Kleppmann: Avro with Schema Registry for backward compatibility

**What this drives:**
- Avro with Schema Registry, BACKWARD compatibility mode
- CI/CD enforced compatibility check on every schema change
- Allowed: add optional field with default, add new event type, add new enum value
- Forbidden: remove field, rename field, change field type, change optional to required
- Breaking changes: new topic (`ledger.events.v2`) with migration replay

---

## P7: Simplest Correct Solution

**Statement:** When multiple approaches satisfy the higher-priority principles, choose the simplest one. Three similar lines of code are better than a premature abstraction. Configuration where parameters change; code where structure changes. No plugin frameworks, no speculative extensibility, no infrastructure we don't need yet.

**Rationale:** Complexity is the enemy of correctness in financial systems. Every unnecessary abstraction is a place for bugs to hide. But "simplest" never overrides "correct" — P1 through P6 define correctness.

**Sources:**
- Square "Books": Built by a 3-person team managing ~20TB. Simple three-table model
- TigerBeetle: NASA's Power of Ten Rules — simplicity as a safety mechanism
- Master architecture P3: "Use the simplest correct solution"

**What this drives:**
- Kafka-first for A1 (no Transactional Outbox — Kafka IS the single write target, no dual-write problem)
- No Axon Framework (roll our own ES — simpler, more transparent, better portfolio signal)
- No distributed cache (Caffeine in-process where needed, PostgreSQL as the shared read model)
- No separate key management service for the portfolio (KeyProvider abstraction with deployment-time implementation)

---

## Principle Conflict Resolution

When principles conflict, the ordering resolves it:

| Conflict | Resolution |
|----------|-----------|
| P1 (integrity) vs. P7 (simplicity) | Add complexity for financial integrity. Example: compound Kafka events for atomic double-entry add complexity but prevent accounting violations |
| P2 (crash recovery) vs. P7 (simplicity) | Add complexity for crash safety. Example: durable reservation timeout via database polling adds a scheduled task, but prevents permanent fund locking |
| P3 (security) vs. P5 (consistency) | Security takes precedence. Example: encrypting PII in events adds latency to the write path, but protecting PII is non-negotiable |
| P5 (immediate consistency) vs. P7 (simplicity) | The synchronous projection update for the reservation hot path adds a second write path. Justified by P1 — over-reservation loses money |
| P4 (events as truth) vs. P5 (immediate consistency) | Both can be satisfied: publish event to Kafka (P4) AND update projection synchronously (P5). The event is the truth; the synchronous update is a performance optimization that doesn't change what's authoritative |
