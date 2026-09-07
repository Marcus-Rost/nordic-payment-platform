# A1 Event-Sourced Account Ledger — Architectural Design

*Version 1.0*
*Date: 2026-04-11*

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Architectural Principles](#2-architectural-principles)
3. [System Context](#3-system-context)
4. [Internal Architecture](#4-internal-architecture)
5. [Event Model](#5-event-model)
6. [Data Architecture](#6-data-architecture)
7. [Command Flow](#7-command-flow)
8. [Consistency and Crash Recovery](#8-consistency-and-crash-recovery)
9. [Security Architecture](#9-security-architecture)
10. [Performance Architecture](#10-performance-architecture)
11. [Reconciliation](#11-reconciliation)
12. [Testing Architecture](#12-testing-architecture)
13. [Deployment and Operations](#13-deployment-and-operations)
14. [Decision Log](#14-decision-log)

---

## 1. Introduction

### 1.1 Purpose

This document describes the internal architecture of A1, the Event-Sourced Account Ledger. It translates the functional requirements from `requirements/requirements.md` and the architectural decisions from `ARCHITECTURE_DECISIONS.md` into a coherent structural design that guides implementation.

### 1.2 Scope

A1 is the single source of truth for all account balances in the Nordic Payment Processing Infrastructure. It stores every balance change as an immutable event, computes current balances as CQRS read projections, supports balance reservations for instant payments, and provides the regulatory audit trail for all financial movements.

This document covers A1's internal structure, data flows, consistency mechanisms, security model, and operational characteristics. It does not cover the other sub-systems (A2-A6) except where they interact with A1.

### 1.3 Key Constraints

| Constraint | Source | Impact on A1 |
|-----------|--------|-------------|
| Balance reservation latency < 100ms | LED-NF03, instant payment time budget | Drives synchronous projection update, Kafka consumer tuning |
| Balance query latency < 50ms | LED-NF01 | Drives PostgreSQL projection design, indexing |
| Projection rebuild from 1M events < 5 min | LED-NF05 | Drives snapshot mechanism, parallel replay |
| Event throughput >= 1,000 events/sec | LED-NF02 | Drives Kafka partition count, consumer parallelism |
| Accounting identity at all times | P1, LED-NF06 | Drives compound events, idempotent processing, property-based testing |
| Survive crash at any point | P2, LED-RES01-04 | Drives Kafka-first, durable timeouts, startup recovery |
| GDPR erasure without breaking balances | P3, master Section 2.6 | Drives crypto-shredding with selective field encryption |
| 24/7 availability for instant path | EU IPR, OPS-08 | Drives zero-downtime deployment, graceful shutdown |

### 1.4 Related Documents

| Document | Location | Relationship |
|----------|----------|-------------|
| Requirements | `requirements/requirements.md` | What A1 must do |
| Use Cases | `use_cases/UC01_ledger_operations.md` | Scenarios A1 must support |
| Gap Analysis | `extensions/gap_analysis.md` | Comparison with Axon, EventStoreDB, Vault, Mambu |
| Principles | `architecture/ARCHITECTURE_PRINCIPLES.md` | The principles guiding this design |
| Challenges | `architecture/ARCHITECTURE_CHALLENGES.md` | The problems this design resolves |
| Decisions | `architecture/ARCHITECTURE_DECISIONS.md` | Options analysis for each decision |
| Draft | `architecture/architecture_draft.md` | Early orientation draft (superseded by this document) |
| Master Architecture | `../payment_processing/architecture/ARCHITECTURAL_DESIGN.md` | System-wide architecture |
| Master Navigation | `../payment_processing/NAVIGATION.md` | Cross-references to all sub-projects |

---

## 2. Architectural Principles

Seven principles guide this design, in priority order. When principles conflict, the higher-ranked one wins. The full statement and rationale for each is in `ARCHITECTURE_PRINCIPLES.md`.

| Priority | Principle | One-line summary |
|----------|-----------|-----------------|
| **P1** | Financial Integrity Above All | Debits = credits, at all times, under all conditions |
| **P2** | Design for Crash, Not Around Crash | Safe answer to "what if we crash right now?" at every point |
| **P3** | Security by Construction | The insecure path must be harder than the secure path |
| **P4** | The Event Log Is the Only Source of Truth | Kafka `ledger.events` is authoritative; everything else is derived |
| **P5** | Immediate Consistency Where Money Moves | Reservations need immediate consistency; queries tolerate lag |
| **P6** | Events Are Public Contracts | Published schemas are permanent APIs |
| **P7** | Simplest Correct Solution | Simplicity, but never at the expense of P1-P6 |

---

## 3. System Context

### 3.1 A1's Place in the Master System

A1 is in the **Internal zone** (master architecture decision A.1a). It is never directly exposed to external traffic. All external access reaches A1 through DMZ services (A4 for PSD2 queries, A6 for VoP name lookups).

```
                    EXTERNAL ZONE
                    (TPPs, merchants, other PSPs)
                          │
                    ┌─────┴─────┐
                    │ DMZ ZONE  │
                    │ A3  A4  A6│
                    └─────┬─────┘
                          │ mTLS + OAuth2
                          v
┌─────────────────────────────────────────────────────┐
│                   INTERNAL ZONE                      │
│                                                      │
│  ┌─────────────┐   Kafka    ┌───────────────────┐   │
│  │ A2 Clearing │──────────→│ A1 Account Ledger  │   │
│  │ Engine      │←──────────│ (this service)     │   │
│  └─────────────┘            └───────────────────┘   │
│                                      ↑              │
│  ┌─────────────┐   Kafka             │              │
│  │ A5 Fraud &  │────────────────────┘              │
│  │ Sanctions   │                                    │
│  └─────────────┘                                    │
│                                                      │
│  Infrastructure: Kafka (3 brokers), PostgreSQL,      │
│  Prometheus, Grafana, Grafana Tempo                  │
└─────────────────────────────────────────────────────┘
```

### 3.2 Interactions with Other Sub-systems

**Inbound (Kafka — state-changing commands):**

| Topic | Producer | Content | Partition Key |
|-------|----------|---------|---------------|
| `payment.balance-reservation` | A2 | Reserve / confirm / release balance | accountId |
| `payment.settlement-events` | A2 (batch) | Debit + credit for settled payments | transactionId |
| `payment.instant-settlement-events` | A2 (instant) | Debit + credit for instant settlements | transactionId |
| `account.freeze-commands` | A5 | Freeze / unfreeze account | accountId |

**Outbound (Kafka — events and results):**

| Topic | Content | Partition Key |
|-------|---------|---------------|
| `ledger.events` | All ledger events (source of truth, infinite retention) | mixed |
| `payment.balance-reservation-response` | Reservation results for A2 | transactionId |
| `payment.reconciliation-results` | Reconciliation discrepancies | clearingCycleId |
| `audit.events` | Audit trail (via Platform Starter) | serviceId |

**Inbound (REST — queries):**

| Caller | Endpoint | Purpose | Auth |
|--------|----------|---------|------|
| A4 | `GET /api/v1/accounts/{id}/balances` | AISP balance queries | mTLS + `accounts:read` |
| A4 | `GET /api/v1/accounts/{id}/transactions` | AISP transaction history | mTLS + `accounts:read` |
| A6 | `GET /api/v1/accounts/{id}/holder-name` | VoP name lookup | mTLS + `vop:read` |
| A5 case mgmt | `GET /api/v1/accounts/{id}` | Analyst display (PII enrichment) | mTLS + `accounts:read` |
| Admin | `POST /api/v1/accounts` | Account creation | mTLS + `accounts:admin` |
| Admin | `POST /api/v1/reconciliation/run` | Trigger reconciliation | mTLS + `reconciliation:admin` |

---

## 4. Internal Architecture

### 4.1 Component Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│  A1: Event-Sourced Account Ledger                                   │
│                                                                     │
│  COMMAND SIDE                              QUERY SIDE               │
│  ═══════════                               ══════════               │
│                                                                     │
│  ┌──────────────────────┐                  ┌──────────────────────┐ │
│  │ Reservation Handler  │                  │ Query API            │ │
│  │ (Kafka consumer)     │                  │ (REST, OAuth2)       │ │
│  │                      │                  │                      │ │
│  │ payment.balance-     │                  │ GET balances         │ │
│  │ reservation          │                  │ GET transactions     │ │
│  └──────────┬───────────┘                  │ GET statements       │ │
│             │                              │ GET holder-name      │ │
│  ┌──────────┴───────────┐                  └──────────┬───────────┘ │
│  │ Settlement Handler   │                             │             │
│  │ (Kafka consumer)     │                             │ reads from  │
│  │                      │                             │             │
│  │ payment.settlement-  │                             v             │
│  │ events (batch+inst)  │                  ┌──────────────────────┐ │
│  └──────────┬───────────┘                  │ PostgreSQL           │ │
│             │                              │ Projections          │ │
│  ┌──────────┴───────────┐                  │                      │ │
│  │ Freeze Handler       │                  │ account_balances     │ │
│  │ (Kafka consumer)     │   writes to      │ transaction_history  │ │
│  │                      │──────────────────│ reservations         │ │
│  │ account.freeze-      │                  │ account_encryption_  │ │
│  │ commands             │                  │   keys               │ │
│  └──────────┬───────────┘                  │ processed_event_ids  │ │
│             │                              │ account_snapshots    │ │
│             │ publishes to                 └──────────┬───────────┘ │
│             v                                         │             │
│  ┌──────────────────────┐                             │ writes to   │
│  │ ledger.events        │                             │             │
│  │ (Kafka, infinite     │                  ┌──────────┴───────────┐ │
│  │  retention)          │─────────────────→│ Projection Consumer  │ │
│  │                      │                  │ (Kafka consumer,     │ │
│  │  SOURCE OF TRUTH     │                  │  async, idempotent)  │ │
│  └──────────────────────┘                  └──────────────────────┘ │
│                                                                     │
│  SUPPORTING COMPONENTS                                              │
│  ═════════════════════                                              │
│                                                                     │
│  ┌───────────────────┐  ┌──────────────┐  ┌──────────────────────┐ │
│  │ Reservation       │  │ Snapshot     │  │ Reconciliation       │ │
│  │ Timeout Scanner   │  │ Manager      │  │ Engine               │ │
│  │ (scheduled, 5s)   │  │ (every 1K   │  │ (REST-triggered)     │ │
│  │                   │  │  events/acct)│  │                      │ │
│  └───────────────────┘  └──────────────┘  └──────────────────────┘ │
│                                                                     │
│  ┌───────────────────┐  ┌──────────────────────────────────────┐   │
│  │ Crypto-Shredding  │  │ Platform Starter                     │   │
│  │ Layer             │  │ (security defaults, @Audited,        │   │
│  │ (KeyProvider +    │  │  hash-chain, tracing, error handling,│   │
│  │  AES-256-GCM)     │  │  HMAC verification, health checks)  │   │
│  └───────────────────┘  └──────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.2 Component Responsibilities

| Component | Responsibility | Technology |
|-----------|---------------|------------|
| **Reservation Handler** | Processes balance reserve/confirm/release commands. Validates against projection, publishes events, updates projection synchronously. Natural per-account serialization via Kafka partitioning. See **Idempotency of Reservation Operations** below | Spring Kafka consumer, consumer group `a1-reservation-handler` |
| **Settlement Handler** | Processes settlement events from A2. Publishes compound DoubleEntryTransaction events. Updates projection synchronously | Spring Kafka consumer, consumer group `a1-settlement-handler` |
| **Freeze Handler** | Processes account freeze/unfreeze commands from A5. Publishes AccountLifecycleEvent events | Spring Kafka consumer, consumer group `a1-freeze-handler` |
| **Projection Consumer** | Async consumer of `ledger.events`. Updates PostgreSQL projections. Idempotent — skips events already applied by command handlers. Used for catch-up, rebuild, and events from other handlers | Spring Kafka consumer, consumer group `a1-projection-consumer` |
| **Query API** | REST endpoints for balance queries, transaction history, statements, holder name lookups. Reads from PostgreSQL projections. No state changes | Spring MVC, OAuth2 + mTLS |
| **Reservation Timeout Scanner** | Periodic scan (every 5s) for stale reservations. Publishes BALANCE_RELEASED events for reservations older than 30s. See **Concurrency Control** in Section 8.3 | Spring `@Scheduled`, PostgreSQL query |
| **Snapshot Manager** | Creates balance snapshots every 1,000 events per account. Verifies snapshot integrity via SHA-256 hash | Triggered by projection consumer event counter |
| **Reconciliation Engine** | Compares internal ledger entries against external settlement reports via SQL join. Triggered by REST endpoint | Spring MVC, JAXB (camt.053 parsing) |
| **Crypto-Shredding Layer** | Encrypts PII at event creation, decrypts at event consumption. Manages per-account encryption keys. Handles key deletion for GDPR erasure | AES-256-GCM, `KeyProvider` interface, Caffeine cache |
| **Platform Starter** | Cross-cutting: security defaults, `@Audited`, audit hash chain, distributed tracing, error handling (RFC 9457 + DLQ), HMAC verification, Prometheus metrics, health checks, graceful shutdown | Spring Boot auto-configuration |

**Idempotency of Reservation Operations:** All reservation operations (RESERVE, CONFIRM, RELEASE) are idempotent:
- RESERVE on an already-reserved transaction: returns cached CONFIRMED result
- RELEASE on an already-released reservation: no-op, returns RELEASED
- RELEASE on an already-confirmed reservation: no-op, returns CONFIRMED  
- **CONFIRM on an already-released reservation: no-op, returns RELEASED** — this case occurs when A2 crashes after publishing a settlement event but before sending CONFIRM, and A1's 30-second auto-release fires before A2 recovers. The settlement is already applied (via the settlement event); the reservation cleanup happened via release instead of confirm. The final balance is correct in both cases.

This bidirectional idempotency is critical for crash recovery and must be covered by integration tests.

---

## 5. Event Model

### 5.1 Three Event Categories

The `ledger.events` topic carries three categories of events, each with a distinct Avro record type. Schema Registry uses `TopicRecordNameStrategy` — each record type has independent schema evolution.

**Category 1: DoubleEntryTransaction**

Compound event for financial movements. Contains both debit and credit entries in a single Kafka message. Atomic by construction — it is structurally impossible to publish one entry without the other.

Partition key: `transactionId`

```
DoubleEntryTransaction {
  transactionId       string          // partition key
  eventType           "DOUBLE_ENTRY"  // fixed
  debitAccountId      string
  debitAmount         string          // BigDecimal as string
  debitCurrency       string          // ISO 4217
  creditAccountId     string
  creditAmount        string
  creditCurrency      string
  externalReference   string|null     // clearing cycle + settlement ref
  clearingCycleId     string|null
  description         string|null     // ENCRYPTED (crypto-shredding)
  duplicateContext     map|null        // {duplicateFlag, duplicateOf, payerOverride}
  idempotencyKey      string
  timestamp           long            // millis since epoch, UTC
  metadata            map|null
}
```

**Category 2: ReservationEvent**

Per-account events for the balance reservation lifecycle. The three event types must be ordered per account (RESERVED before CONFIRMED/RELEASED), guaranteed by partition key = accountId.

Partition key: `accountId`

```
ReservationEvent {
  eventType           enum {BALANCE_RESERVED, BALANCE_CONFIRMED, BALANCE_RELEASED}
  accountId           string          // partition key
  reservationId       string
  transactionId       string          // links to the payment
  amount              string
  currency            string
  idempotencyKey      string
  timestamp           long
}
```

**Category 3: AccountLifecycleEvent**

Per-account events for account state changes.

Partition key: `accountId`

```
AccountLifecycleEvent {
  eventType           enum {ACCOUNT_CREATED, ACCOUNT_FROZEN,
                            ACCOUNT_UNFROZEN, ACCOUNT_CLOSED}
  accountId           string          // partition key
  holderName          string|null     // ENCRYPTED (crypto-shredding)
  holderAddress       string|null     // ENCRYPTED (crypto-shredding)
  iban                string|null     // plaintext (pseudonymous)
  currency            string|null
  reason              string|null     // for freeze: CONFIRMED_FRAUD, etc.
  idempotencyKey      string
  timestamp           long
}
```

### 5.2 Partition Key Strategy

| Event Category | Partition Key | Rationale |
|---------------|---------------|-----------|
| DoubleEntryTransaction | transactionId | Both legs in one message on one partition. Atomicity by construction |
| ReservationEvent | accountId | Per-account ordering guaranteed. RESERVED before CONFIRMED |
| AccountLifecycleEvent | accountId | Per-account ordering guaranteed. CREATED before FROZEN |

**Consequence:** Financial movement events for the same account may arrive on different partitions (depending on transactionId). Per-account ordering for financial movements is NOT guaranteed by Kafka. It is enforced at the projection level via row-level locking (`SELECT FOR UPDATE`) when the projection consumer applies compound events. Balance correctness is maintained because debit/credit operations commute on the sum.

### 5.3 Topic Configuration

| Topic | Retention | Replication | min.insync.replicas | acks | Partitions |
|-------|-----------|-------------|--------------------|----|------------|
| `ledger.events` | **Infinite** (`retention.ms=-1`) | 3 | 2 | all | 12 |
| `payment.balance-reservation` | 7 days | 3 | 2 | all | 12 |
| `payment.balance-reservation-response` | 7 days | 3 | 2 | all | 12 |
| `payment.settlement-events` | 7 days | 3 | 2 | all | 12 |
| `payment.instant-settlement-events` | 7 days | 3 | 2 | all | 12 |
| `account.freeze-commands` | 7 days | 3 | 2 | all | 6 |
| `payment.reconciliation-results` | 30 days | 3 | 2 | all | 6 |
| `audit.events` | 5 years (AML) | 3 | 2 | all | 6 |
| `audit.checkpoints` | 5 years | 3 | 2 | all | 3 |

---

## 6. Data Architecture

### 6.1 Kafka-First: The Event Log as Source of Truth

A1 is the natural Kafka-first service in the system. The data flow is:

```
Command (reserve / settle / freeze)
  → Validate (business rules, balance check against projection)
  → Publish event to ledger.events (Kafka is the write)
  → Update PostgreSQL projection synchronously (hot path optimization)
  → Projection Consumer also reads ledger.events (catch-up, rebuild, dedup)
  → PostgreSQL projections are derived, disposable, rebuildable
```

**There is no dual-write problem in A1.** The Kafka event is always published first. The PostgreSQL update is a derived, idempotent consequence. If the PostgreSQL update fails, the async projection consumer catches up. If PostgreSQL is destroyed, it is rebuilt from Kafka (LED-F09).

### 6.2 PostgreSQL as Derived Read Model

PostgreSQL holds six tables, all derived from the event log:

| Table | Purpose | Rebuildable? |
|-------|---------|-------------|
| `account_balances` | Current booked and available balances per account | Yes — from events |
| `transaction_history` | Per-account transaction log with reconciliation status | Yes — from events |
| `reservations` | Active balance reservations | Yes — from events |
| `processed_event_ids` | Idempotency tracking for projection consumer | Truncated on rebuild |
| `account_snapshots` | Periodic balance snapshots for fast rebuild | Recreated during rebuild |
| `account_encryption_keys` | Per-account encryption keys for crypto-shredding | **Not rebuildable from events** — this is operational state managed separately |

The `account_encryption_keys` table is the one exception to the "everything derived from events" rule. Encryption keys are not stored in events (that would defeat the purpose of crypto-shredding). They are managed as a separate concern.

### 6.3 PostgreSQL Schema

The full schema is in the appendix of `ARCHITECTURE_DECISIONS.md`. Key design choices:

- `account_balances.version` — used by the async projection consumer for idempotent updates
- `account_balances.last_event_offset` — tracks the most recent event applied to this row
- `reservations` with partial index on `(status, created_at) WHERE status = 'RESERVED'` — optimized for the timeout scanner
- `transaction_history.idempotency_key` with UNIQUE constraint — structural deduplication
- `transaction_history.external_reference` indexed — for reconciliation joins
- All monetary columns use `NUMERIC` (arbitrary precision) — never float/double
- **Database Defense-in-Depth:** The `account_balances` projection table should include `CHECK (available_balance >= 0 AND booked_balance >= 0)` as a defense-in-depth constraint, matching A2's approach for `psp_settlement_accounts`. Even if application logic has a bug, PostgreSQL rejects a negative balance.
- `account_balances.holder_name` — stores the account holder name (encrypted, crypto-shredding) for VoP lookups by A6. `holder_address` is NOT projected — it exists in the `AccountLifecycleEvent` in Kafka (the source of truth) but is not needed by any query endpoint. If a future requirement needs address lookup, it can be added to the projection without an event model change

### 6.4 Data Ownership

A1 owns:
- All account balances (booked and available)
- All transaction history
- All balance reservations
- Account holder names (for VoP lookups by A6)
- Per-account encryption keys
- Reconciliation status per transaction

No other service reads A1's database directly. All access is through A1's Kafka events or REST API.

---

## 7. Command Flow

### 7.1 The Consumer-as-Command-Handler Pattern

Every state-changing command arrives via Kafka. The Kafka consumer IS the command handler. It reads state from the PostgreSQL projection, validates, publishes the resulting event, and updates the projection synchronously — all within a single sequential message processing cycle.

This pattern provides:
- **Durability:** Commands persist in Kafka before processing. Crash-safe.
- **Serialization:** Kafka partitioning (key = accountId) ensures commands for the same account are processed sequentially. No concurrency control needed.
- **Immediate consistency:** The command handler reads its own writes (it updates the projection before moving to the next message).

### 7.2 Balance Reservation Flow (Instant Payment Hot Path)

```
A2                     Kafka                        A1 Reservation Handler
│                      │                            │
│  Reserve 5000 NOK    │                            │
│  account A, tx T1    │                            │
│─────────────────────→│ payment.balance-reservation │
│                      │───────────────────────────→│
│                      │                            │ 1. Check: reservation for T1 exists?
│                      │                            │    → No (first attempt)
│                      │                            │
│                      │                            │ 2. Read: available_balance for A
│                      │                            │    → 10,000 NOK
│                      │                            │
│                      │                            │ 3. Validate: 10,000 >= 5,000? → Yes
│                      │                            │
│                      │                            │ 4. Publish BALANCE_RESERVED event
│                      │   ledger.events            │    to ledger.events (acks=all)
│                      │←───────────────────────────│
│                      │                            │
│                      │                            │ 5. Update PostgreSQL:
│                      │                            │    INSERT reservation
│                      │                            │    UPDATE available_balance
│                      │                            │      = 10,000 - 5,000 = 5,000
│                      │                            │
│                      │ balance-reservation-response│ 6. Publish response: RESERVED
│  RESERVED            │←───────────────────────────│
│←─────────────────────│                            │
│                      │                            │ 7. Commit Kafka offset
```

**Latency budget:** ~60-80ms total (Kafka hops + processing). Within LED-NF03 target of 100ms.

**Crash recovery at each step:**

| Crash Point | State | Recovery |
|-------------|-------|----------|
| After step 1 (dedup check) | Nothing happened | Message redelivered, reprocessed |
| After step 4 (event published) | Event in Kafka, projection not updated | On restart: step 1 finds no reservation → re-validates → re-publishes (duplicate event, deduplicated by async consumer) → updates projection |
| After step 5 (projection updated) | Event in Kafka AND projection updated | On restart: step 1 finds existing reservation → returns cached result |
| After step 6 (response published) | Complete | On restart: step 1 finds existing reservation → returns cached result (duplicate response, A2 deduplicates) |

### 7.3 Settlement Flow (Batch and Instant)

```
A2                     Kafka                        A1 Settlement Handler
│                      │                            │
│  Settle: debit A     │                            │
│  5000 NOK, credit B  │                            │
│  5000 NOK            │                            │
│─────────────────────→│ payment.settlement-events   │
│                      │───────────────────────────→│
│                      │                            │ 1. Check: transaction T1 processed?
│                      │                            │    → No
│                      │                            │
│                      │                            │ 2. Validate: account A exists,
│                      │                            │    status ACTIVE, balance >= 5000
│                      │                            │
│                      │                            │ 3. Publish DoubleEntryTransaction
│                      │   ledger.events            │    (compound: debit A + credit B)
│                      │←───────────────────────────│
│                      │                            │
│                      │                            │ 4. Update PostgreSQL (single tx):
│                      │                            │    - account A: booked -= 5000
│                      │                            │    - account B: booked += 5000
│                      │                            │    - INSERT 2 transaction_history rows
│                      │                            │      (idempotency_key = dedup)
│                      │                            │
│                      │                            │ 5. Commit Kafka offset
```

The compound DoubleEntryTransaction event ensures atomicity: both entries exist in the event log or neither does (P1). The PostgreSQL update applies both legs in a single database transaction (P1).

### 7.4 Account Freeze Flow

```
A5                     Kafka                        A1 Freeze Handler
│                      │                            │
│  Freeze account A    │                            │
│  reason: FRAUD       │                            │
│─────────────────────→│ account.freeze-commands     │
│                      │───────────────────────────→│
│                      │                            │ 1. Validate: account A exists,
│                      │                            │    status = ACTIVE
│                      │                            │
│                      │                            │ 2. Publish ACCOUNT_FROZEN event
│                      │   ledger.events            │
│                      │←───────────────────────────│
│                      │                            │
│                      │                            │ 3. Update PostgreSQL:
│                      │                            │    status = FROZEN
│                      │                            │
│                      │                            │ 4. Commit Kafka offset
```

After freeze: debits rejected, credits allowed (LED-F22).

---

## 8. Consistency and Crash Recovery

### 8.1 Invariants

The system maintains three invariants at all times:

| Invariant | Verification | Enforcement |
|-----------|-------------|-------------|
| **Accounting identity:** sum(debits) = sum(credits) | Continuous: verified after every DoubleEntryTransaction. Verified in property-based tests | Compound events (both legs in one message). Idempotent processing (no double-counting) |
| **Available balance:** available = booked - sum(active reservations) | Verified on every reservation operation | Synchronous projection update in command handler. Per-account serialization via Kafka partitioning |
| **No orphaned reservations:** every reservation is eventually confirmed or released | Verified by timeout scanner (every 5s) and startup scan | Durable timeout mechanism (PostgreSQL-scheduled) |

### 8.2 Idempotent Processing — Three-Layer Defense

```
Layer 1: Command Handler                    Catches: replayed Kafka messages
─────────────────────────                   after crash or rebalance
  Check: "Does this reservation/transaction
  already exist in the database?"
  → Yes: return cached result, skip processing
  → No: proceed

Layer 2: Kafka Idempotent Producer          Catches: network-level retries
──────────────────────────────              within a single session
  enable.idempotence=true
  Broker deduplicates by PID + sequence number

Layer 3: Projection Consumer                Catches: duplicate events in
─────────────────────────────               ledger.events (from crash between
  INSERT INTO processed_event_ids           event publish and offset commit)
  ON CONFLICT DO NOTHING
  → 0 rows: already processed, skip
  → 1 row: new event, process
```

Each layer independently prevents duplicate processing. No layer trusts the others.

### 8.3 Reservation Timeout — Durable Expiry

```
Normal Operation (every 5 seconds):
──────────────────────────────────
  SELECT FROM reservations
  WHERE status = 'RESERVED'
  AND created_at < now() - interval '30 seconds'

  For each stale reservation:
  → Publish BALANCE_RELEASED event to ledger.events
  → UPDATE reservation SET status = 'RELEASED'
  → UPDATE account_balances SET available_balance += amount
  → Log WARN: "Reservation {id} auto-released after timeout"

Startup (before readiness probe passes):
────────────────────────────────────────
  Same scan runs once. Catches reservations that became
  stale while the service was down.
```

Both scans are idempotent. The released reservation publishes an event (P4).

**Clock Assumption:** The reservation timeout scanner uses SQL `now()` (wall clock). For the 30-second reservation timeout, a clock jump of a few seconds is acceptable (the 30-second window is generous). However, NTP synchronization is an infrastructure requirement.

**Concurrency Control:** The `@Scheduled` timeout scanner and the Kafka consumer reservation handler both modify the `reservations` table. Concurrency is controlled by `SELECT ... FOR UPDATE SKIP LOCKED` on the reservation row. The scanner acquires a row lock before releasing; if the consumer is currently processing a CONFIRM for the same reservation, the scanner skips it (SKIP LOCKED). Conversely, if the scanner has locked the row for release, the consumer's CONFIRM blocks briefly, then sees the RELEASED status and treats it as a no-op (per bidirectional idempotency above).

### 8.4 Startup Recovery Sequence

```
1. Database connection established
2. Reservation timeout scan (release stale reservations)
3. Kafka consumers created (not yet polling)
4. Kafka consumers resume from last committed offsets
5. Readiness probe passes → service accepts traffic
6. Normal operation begins
```

Steps 2-4 complete before the readiness probe passes. The service does not accept traffic until recovery is complete.

### 8.5 Graceful Shutdown Sequence

```
1. SIGTERM received
2. Readiness probe fails → no new traffic routed
3. All Kafka consumers: wakeup() → stop polling for new messages
4. In-flight message processing completes (up to 10 seconds)
5. Kafka offsets committed for fully processed messages
6. Kafka producers closed
7. PostgreSQL connections closed
8. Process exits
```

If in-flight operations exceed 10 seconds: uncommitted messages will be redelivered on restart. Orphaned reservations will be cleaned up by the timeout mechanism.

---

## 9. Security Architecture

### 9.1 Trust Boundaries

```
                         EXTERNAL ZONE (untrusted)
                              │
                    ┌─────────┴─────────┐
                    │    DMZ ZONE       │
                    │  A3, A4, A6       │
                    └─────────┬─────────┘
                              │ mTLS + OAuth2 (scoped)
                              v
┌─────────────────────────────────────────────────────┐
│  INTERNAL ZONE                                       │
│                                                      │
│  Kafka: SASL/SSL + per-producer HMAC-SHA256          │
│  REST:  mTLS + scoped OAuth2 tokens                  │
│  DB:    TLS + per-service credentials                │
│                                                      │
│  A1 verifies:                                        │
│  - HMAC signature on every Kafka message             │
│  - mTLS certificate on every REST request            │
│  - OAuth2 scope on every REST endpoint               │
└─────────────────────────────────────────────────────┘
```

### 9.2 Domain Primitives

All financial value types are validated at construction and immutable:

| Type | Invariants | Example |
|------|-----------|---------|
| `AccountId` | Non-empty, valid UUID | `AccountId.of("acct-uuid-001")` — throws on empty/malformed |
| `IBAN` | Valid structure, mod-97 check digits | `IBAN.of("NO9386011117947")` — throws on invalid |
| `MonetaryAmount` | Non-negative, 2 decimal places, immutable | `MonetaryAmount.of("5000.00", Currency.NOK)` — throws on negative |
| `Currency` | ISO 4217, from fixed set {NOK, EUR, SEK, DKK, USD} | `Currency.of("NOK")` — throws on unknown |
| `TransactionId` | Non-empty, valid UUID | `TransactionId.of("tx-001")` — throws on empty |

Invalid values cannot exist in memory. This eliminates entire classes of bugs and attacks at the type level.

**Rounding Policy:** All arithmetic in MonetaryAmount uses `RoundingMode.HALF_EVEN` (Banker's Rounding). Division operations require explicit rounding mode specification. The `MoneyMath` utility class in `payment-domain-model` enforces this — direct `BigDecimal.divide()` calls are prohibited by ArchUnit rule. Intermediate calculations use currency scale + 4 digits; final results rounded to currency scale.

**Implementation Note:** The decimal scale for `MonetaryAmount` should be derived from the `Currency` type using ISO 4217 minor unit metadata, not hardcoded to 2. Current currencies {NOK, EUR, SEK, DKK, USD} all use 2 minor units, but hardcoding prevents future currency expansion (JPY=0, BHD=3). The `Currency` enum should expose a `getMinorUnits()` method that `MonetaryAmount` uses for scale enforcement.

### 9.3 Crypto-Shredding

**What is encrypted in events:**

| Field | Encrypted | Rationale |
|-------|-----------|-----------|
| Account holder name | Yes | Direct PII |
| Account holder address | Yes | Direct PII |
| Transaction description | Yes | May contain PII |
| IBAN | No | Pseudonymous. Needed for routing, dedup, reconciliation |
| Amount, currency | No | Financial data. Required for balance calculations |
| Timestamps, IDs | No | Operational data |

**Key lifecycle:**
- **Creation:** AES-256-GCM key generated per account at account creation. Stored encrypted with the master key in `account_encryption_keys`. Per-account AES-256-GCM keys are generated using `java.security.SecureRandom` (CSPRNG). The JVM's default `SecureRandom` provider (typically NativePRNG backed by `/dev/urandom` on Linux) provides sufficient entropy for key generation.
- **Usage:** Loaded from DB (Caffeine cache, 5-minute TTL), used to encrypt PII before publishing events and decrypt PII when reading events.
- **Erasure:** Master key reference cleared (`encrypted_key = NULL`). All encrypted PII across all Kafka replicas and backups becomes irrecoverable. Balances remain correct (amounts are plaintext).

### 9.4 Audit Trail

All state-changing operations produce audit events via the Platform Starter's `@Audited` annotation. Events are published to the `audit.events` Kafka topic with:
- SHA-256 hash chain (each event hashes the previous — tamper-evident)
- Daily signed checkpoint anchored to `audit.checkpoints` topic

### Threat Model Traceability (Master A.3a)

| Interface | Threat | Control | Reference |
|-----------|--------|---------|-----------|
| Kafka inbound (reservation, settlement, freeze) | Spoofing | HMAC-SHA256 per-producer keys verify message origin | KM-06, Starter |
| Kafka inbound | Tampering | HMAC signature detects modified messages | KM-06 |
| Kafka inbound | Repudiation | All events logged to tamper-evident audit trail | F.2a, Starter hash chain |
| REST API (account queries) | Spoofing | mTLS with service-specific certificates | A.1a |
| REST API | Information Disclosure | OAuth2 scopes restrict access per endpoint; PII encrypted (crypto-shredding) | LED-S01, F.1a |
| REST API | Denial of Service | Platform Starter deny-all default; Kubernetes NetworkPolicy restricts to internal zone | A.1b |
| Event store (ledger.events) | Tampering | Kafka append-only topic; audit hash chain detects modifications | F.2a |
| Event store | Information Disclosure | PII fields encrypted with per-account AES-256-GCM keys | F.1a |
| Balance reservation | Elevation of Privilege | Per-account serialization via Kafka partitioning prevents concurrent manipulation | A1 C.3 |

### Security Requirements Traceability

| Requirement | Description | Architectural Element |
|------------|-------------|----------------------|
| LED-S01 | OAuth2 scope-based access control | REST endpoints with `accounts:read`, `vop:read`, `accounts:admin` scopes |
| LED-S02 | Service-to-service authentication | mTLS for REST, HMAC-SHA256 for Kafka (KM-06) |
| LED-S03 | Event immutability | Kafka append-only topic (`retention.ms=-1`), no compaction |
| LED-S04 | PII encryption at rest | Crypto-shredding: AES-256-GCM per-account keys on name, address, description |
| LED-S05 | Audit trail for all state changes | All events published to `audit.events` with hash chain |
| LED-S06 | Balance integrity | Compound events (atomic debit+credit), per-account Kafka serialization, `MonetaryAmount` domain type |
| LED-S07 | Reservation safety | 30-second auto-release, idempotent reserve/release operations |

---

## 10. Performance Architecture

### 10.1 Latency Targets

| Operation | Target | Mechanism |
|-----------|--------|-----------|
| Balance reservation (end-to-end A2→A1→A2) | < 100ms | Kafka consumer with low `fetch.max.wait.ms`, synchronous projection update |
| Balance query (REST) | < 50ms | PostgreSQL indexed read from `account_balances` |
| Event processing throughput | >= 1,000/sec | 12 Kafka partitions, parallel consumers |
| Projection rebuild from 1M events | < 5 min | Snapshots + 12 parallel threads |

### 10.2 Snapshot Mechanism

Every 1,000 events per account, the projection consumer writes a snapshot:

```
account_snapshots: {
  account_id, booked_balance, available_balance,
  currencies, event_offset, snapshot_timestamp, snapshot_hash
}
```

**Rebuild from snapshots:**
1. Load latest snapshot per account → initialize `account_balances`
2. Seek Kafka to earliest post-snapshot offset
3. Replay remaining events (12 parallel threads, one per partition)
4. Verify: sum(debits) = sum(credits)

### 10.3 Burst Isolation

Separate consumer groups ensure batch settlement bursts do not affect instant payment latency:

| Consumer Group | Topic | Impact of Lag |
|---------------|-------|---------------|
| `a1-reservation-handler` | `payment.balance-reservation` | Reservation path — immediate. Updates projection synchronously |
| `a1-settlement-handler` | settlement topics | Settlement path — processes as fast as possible. Lag is acceptable |
| `a1-projection-consumer` | `ledger.events` | Async catch-up. Lag visible in balance queries but NOT in reservations |

**Monitoring:**
- `ledger.projection.lag` — consumer lag for `a1-projection-consumer`. Alert: >1s warning, >5s critical.
- `ledger.reservation.duration` — histogram. Alert: p99 >80ms.

---

## 11. Reconciliation

### 11.1 Flow

1. `POST /api/v1/reconciliation/run` with settlement report (camt.053 or JSON)
2. Load report into temporary PostgreSQL table
3. Full outer join against `transaction_history` by `external_reference`
4. Classify: MATCHED, AMOUNT_DISCREPANCY, UNMATCHED_INTERNAL, UNMATCHED_EXTERNAL
5. Update `reconciliation_status` on matched/discrepant entries
6. Publish discrepancies to `payment.reconciliation-results`
7. Return summary

For large reports (>10,000 entries): `202 Accepted` with job ID, async processing.

**Discrepancy Handling:** Reconciliation results are published to `payment.reconciliation-results`. Consumers:
- **A2 operator dashboard:** displays UNMATCHED_INTERNAL, UNMATCHED_EXTERNAL, and AMOUNT_DISCREPANCY entries for investigation
- **Alerting (Prometheus/Grafana):** `reconciliation.discrepancy.count` metric triggers alert:
  - Any AMOUNT_DISCREPANCY → P1 alert (immediate investigation — money mismatch)
  - UNMATCHED_INTERNAL or UNMATCHED_EXTERNAL → P2 alert (investigate within 1 hour — eventual consistency may resolve)
  - UNMATCHED entries older than 1 hour → P1 escalation (consistency has not self-resolved)

### 11.2 Why Event Sourcing Helps

Traditional systems reconcile by querying mutable database records that may have been altered since the settlement. Our event sourcing architecture offers a stronger reconciliation: we can replay events for the reconciliation period directly from the immutable event log, comparing against external reports using the source of truth rather than a derived view.

---

## 12. Testing Architecture

### 12.1 Testing Pyramid

| Level | What | Tools | Speed |
|-------|------|-------|-------|
| **Unit** | Domain primitives, command validation, event application, accounting invariant | JUnit 5, AssertJ, in-memory event store | <1s/class |
| **Integration** | Kafka round-trip, schema compat, reservation cycle, idempotency, crash recovery, crypto-shredding | Testcontainers, Spring Boot Test, Awaitility | 10-30s/class |
| **Architectural** | CQRS separation, event serialization, projection write restrictions, idempotency checks | ArchUnit | <5s |
| **Property-based** | Accounting invariant across random command sequences | jqwik | 5-30s |
| **Contract** | A2→A1 message contracts, A1→A4 REST contracts | Pact | 5-10s/contract |

### 12.2 Key Test Strategies

**Accounting invariant verified at every level:**
- Unit: `assertThat(sumDebits()).isEqualTo(sumCredits())` after every operation
- Property-based: random command sequences, invariant verified after each sequence
- Integration: full Kafka→projection round-trip, invariant verified after batch

**Crash recovery tested explicitly:**
- Testcontainers: stop container mid-processing, restart, verify projection correctness

**Crypto-shredding round-trip:**
- Create account → publish events with encrypted PII → verify decryption → delete key → verify `[REDACTED]`

**Implementation Note — Enforcing PII Encryption:** To prevent accidental plaintext PII in events, add an ArchUnit test that verifies all event publisher methods call the crypto-shredding layer before publishing. Additionally, consider a custom Avro logical type `encrypted-string` that wraps the encryption/decryption and makes it structurally impossible to serialize plaintext PII through the Avro schema.

**Projection Rebuild Test:** Integration tests must include a full projection rebuild scenario: drop all PostgreSQL projection tables, replay all events from `ledger.events` offset 0, verify final state matches expected. This validates that projections are truly rebuildable and that crypto-shredded events (PII → `[REDACTED]`) are handled correctly during rebuild.

---

## 13. Deployment and Operations

### 13.1 Single JVM Deployment

A1 runs as a single Spring Boot application containing all components (command handlers, projection consumer, query API, scheduled tasks). Unlike A2 (which splits into batch and instant JVMs), A1's workload does not require process-level isolation — the consumer groups provide sufficient isolation at the thread level.

### 13.2 Configuration

| Category | Parameters | Mechanism |
|----------|-----------|-----------|
| **Infrastructure** | Kafka bootstrap, DB URL, SASL credentials | Environment variables |
| **Operational** | Reservation timeout (30s), scan interval (5s), snapshot frequency (1000), Kafka `fetch.max.wait.ms` | `application.yml` + `@ConfigurationProperties` |
| **Shared** | Reservation timeout must match A2's expectation | Documented in `shared-config.md` |

All `@ConfigurationProperties` use Bean Validation — the service fails to start if a required parameter is missing or out of range.

### 13.3 Health Checks

| Check | Type | What it verifies |
|-------|------|-----------------|
| PostgreSQL connectivity | Readiness | Can query `account_balances` |
| Kafka broker connectivity | Readiness | Broker reachable, consumer groups active |
| Projection consumer lag | Readiness | Lag < 10 seconds (configurable) |
| Startup recovery complete | Startup | Stale reservations scanned, consumers initialized |

### 13.4 Prometheus Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `ledger.projection.lag` | Gauge | Kafka consumer lag for `a1-projection-consumer` |
| `ledger.reservation.duration` | Histogram | End-to-end reservation processing time |
| `ledger.reservation.active` | Gauge | Count of active (RESERVED) reservations |
| `ledger.events.published` | Counter | Events published to `ledger.events`, by event type |
| `ledger.snapshot.count` | Counter | Snapshots created |
| `ledger.reconciliation.discrepancies` | Counter | Reconciliation discrepancies found |
| `ledger.dlq.depth` | Gauge | Dead letter queue depth per topic |
| `ledger.crypto.encrypt.duration` | Histogram | PII encryption time |
| `ledger.processed_events.count` | Gauge | Size of the `processed_event_ids` table |

### 13.5 Graceful Deployment

Rolling deployment with safeguards:

| Safeguard | Purpose |
|-----------|---------|
| **Cooperative Sticky Assignor** | Minimizes Kafka partition reassignment during rolling update |
| **Expand-contract DB migrations** (Flyway) | Migration N is backward-compatible with application version N-1 |
| **Readiness probe gating** | New instance completes startup recovery before accepting traffic |
| **Graceful shutdown** (10s drain) | Completes in-flight operations before closing |
| **Reservation timeout** (30s) | Safety net for any reservations orphaned during deployment |

### 13.6 Port Assignments

| Port | Purpose |
|------|---------|
| 8091 | Application (REST API) |
| 9091 | Management (actuator: health, metrics, readiness) |
| 5441 | PostgreSQL |

---

## 14. Dead Letter Queue Mechanism

All Kafka consumers in A1 use the Platform Starter's `PaymentErrorHandler` for error classification:

| Error Category | Action | Examples |
|---------------|--------|---------|
| **Retryable** | 3 retries with exponential backoff (100ms, 500ms, 2500ms) | Transient DB failure, temporary Kafka unavailability |
| **Non-retryable → DLQ** | Immediate route to `{topic}.dlq` | Deserialization failure, HMAC failure, unknown event type |
| **Non-retryable → DLQ + alert** | DLQ + WARN log + Prometheus increment | Schema incompatibility, unexpected exception |

DLQ messages carry headers: `dlq.original.topic`, `dlq.original.partition`, `dlq.original.offset`, `dlq.error.message`, `dlq.error.class`, `dlq.retry.count`, `dlq.timestamp`.

Monitoring: `ledger.dlq.depth` gauge per DLQ topic. **Any DLQ depth > 0 triggers an alert** — in a financial system, no message may be silently lost.

---

## 15. Known Gaps and Deferred Items

### LED-F35 — Point-in-Time Balance Query (Deferred)

The requirements specify a `GET /api/v1/accounts/{id}/balances?at=<timestamp>` endpoint (priority: Should). This is **deferred to a later implementation phase**. The event sourcing foundation makes this architecturally straightforward — replay events from the nearest snapshot to the target timestamp. No structural changes are needed. The endpoint is deferred because no other sub-system depends on it, and the primary consumers (regulatory reporting) can use snapshots as an interim solution. See `ARCHITECTURE_DECISIONS.md` for full rationale.

### Divergences from Use Case Scenarios

The architecture introduces two deliberate divergences from the use case document (`UC01_ledger_operations.md`):

1. **Scenario 1A** (Standard Credit Transfer): the use case shows DEBIT and CREDIT as two separate events published sequentially. The architecture uses a single compound `DoubleEntryTransaction` event — driven by P1 (Financial Integrity) and P2 (Design for Crash). The observable behavior (balance changes) is identical.

2. **Scenario 1D** (Concurrent Reservations): the use case shows `SELECT FOR UPDATE SKIP LOCKED` for concurrency control. The architecture uses Kafka-based per-account serialization instead, eliminating the need for row locking entirely. The observable behavior (one winner, one rejection) is identical.

---

## 16. Master Architecture Updates Required

The following items in the master architecture documents need updating to align with A1's detailed design. These are noted here for tracking; changes to the master documents require separate review.

| Master Document | Issue | Required Update |
|----------------|-------|----------------|
| `NAVIGATION.md` Kafka topic table | `payment.balance-reservation-response` topic is missing | Add: producer A1, consumer A2, purpose "Reservation results" |
| `ARCHITECTURAL_DESIGN.md` Section 5.2 | Kafka topology diagram omits the reservation response topic | Add `payment.balance-reservation-response` (A1 → A2) |
| `ARCHITECTURAL_DESIGN.md` Section 16.1 | States A1 uses "SELECT FOR UPDATE SKIP LOCKED" for reservation concurrency | Update: A1 uses Kafka partition-based per-account serialization. SELECT FOR UPDATE is used only by the async projection consumer for compound events |
| `ARCHITECTURAL_DESIGN.md` Section 8.1 / `ARCHITECTURE_DECISIONS.md` B.2a | States "No dual-write" and "single write target (Kafka)" for A1 | Clarify: A1 writes to Kafka first, then updates PostgreSQL synchronously as a derived optimization. Kafka remains the sole source of truth. The characterization is accurate in spirit but the wording is too absolute |

---

## 17. Decision Log

Complete analysis with options and tradeoffs is in `ARCHITECTURE_DECISIONS.md`. This table summarizes the final decisions.

| ID | Challenge | Decision |
|----|-----------|----------|
| A.1+A.3 | Double-entry atomicity + event granularity | Compound events for financial movements; fine-grained for reservations and lifecycle |
| C.1 | Command entry points | Kafka for all commands; REST for queries |
| A.2+B.1 | Command validation + read-your-own-writes | Kafka consumer IS the command handler. Synchronous projection update. Per-account serialization via partitioning |
| B.2 | Reservation timeout | Periodic DB scan (5s) + startup scan |
| B.3 | Idempotent processing | Three-layer defense: command dedup + idempotent producer + consumer dedup |
| B.4 | Graceful shutdown | 10s drain, reservation timeout as safety net |
| D.1 | Trust boundaries | HMAC on Kafka, mTLS + scoped OAuth2 on REST, domain primitives |
| D.2 | Crypto-shredding | Encrypt PII fields per-account AES-256-GCM. Key in A1 PostgreSQL |
| D.3 | Audit tamper evidence | Hash chain via Platform Starter, daily signed checkpoint |
| E.1 | Projection rebuild | Snapshots every 1K events/account + parallel replay |
| E.2 | Burst lag isolation | Separate consumer groups. Reservation path unaffected by async lag |
| F.1 | Schema evolution | Avro BACKWARD compat, TopicRecordNameStrategy, CI/CD enforcement |
| F.2 | Reconciliation | REST-triggered SQL join against projection |
| G.1 | Testing | 4-level pyramid: unit, integration, ArchUnit, property-based + Pact contracts |
| LED-RES03 | Dead Letter Queue | 3 retries, exponential backoff, then `{topic}.dlq`. Platform Starter mechanism |
| LED-F35 | Point-in-time balance query | Deferred. Architecturally straightforward, no structural changes needed |
