# Project #9: Event-Sourced Double-Entry Account Ledger — Requirements

*System A role: A1 — The single source of truth for all account balances*
*Target companies: DNB (event-driven), SpareBank 1 (Kafka backbone), Tietoevry (event sourcing/CQRS), Mastercard Oslo (P27 multi-currency)*
*Architecture: Event Sourcing + CQRS over Kafka + PostgreSQL*

---

## 1. Overview

An immutable, event-sourced double-entry bookkeeping ledger. Every balance change is stored as an append-only event. Current balances are computed as read-model projections over the event stream. The ledger supports multi-currency accounts and balance reservations (holds) for instant payments.

This is the **architectural heart** of the payment platform — all other sub-systems depend on it for balance data, and it is the ultimate audit trail for every financial movement.

### 1.1 Target Company Alignment

| Company | Alignment |
|---------|-----------|
| **DNB** | Event-driven microservices with Kafka. CQRS in some services. Domain-Driven Design (DDD). This is exactly their architecture pattern |
| **Tietoevry Banking** | Explicitly uses event sourcing and CQRS in transaction-heavy domains. DDD as organizational principle. Contract-first API design. Our architecture is a direct match |
| **SpareBank 1** | Kafka backbone, continuous deployment, TDD. Their JavaZone 2024 talk: "Using Fakes for DDD and fast feedback loops" — event sourcing enables this testing pattern |
| **Mastercard Oslo** | P27 is the world's first multi-currency real-time payment platform. Our multi-currency ledger supports the same concept. Also relevant for NICS settlement |

---

## 2. Functional Requirements

### 2.1 Core Ledger

| ID | Requirement | Priority |
|----|------------|----------|
| LED-F01 | Every balance change stored as an immutable, append-only event in Kafka | Must |
| LED-F02 | Double-entry: every transaction creates exactly two entries (debit from one account, credit to another). Sum of all debits = sum of all credits (accounting identity) | Must |
| LED-F03 | Events published to Kafka topic `ledger.events` with partition key = account_id (guarantees per-account ordering) | Must |
| LED-F04 | Current balances computed as CQRS read projections stored in PostgreSQL. Updated by consuming from `ledger.events` | Must |
| LED-F05 | Multi-currency: each account has a primary currency (NOK, EUR, SEK, DKK, USD). Balances tracked per currency | Must |
| LED-F06 | Money handling: all amounts as `BigDecimal` or JSR 354 `MonetaryAmount` (Moneta). Never float/double | Must |
| LED-F07 | Event schema versioned with Avro (Schema Registry) for forward/backward compatible evolution | Must |
| LED-F08 | Idempotent event processing: if the same event is consumed twice (Kafka rebalance, retry), balance must not change | Must |
| LED-F09 | Projection rebuild: ability to rebuild all PostgreSQL projections from the complete Kafka event log (delete projections, replay from offset 0) | Must |

### 2.2 Balance Reservations (Holds) — For Instant Payments

| ID | Requirement | Priority |
|----|------------|----------|
| LED-F10 | **Reserve**: tentatively hold funds for an in-flight instant payment. Reduces available balance but not booked balance | Must |
| LED-F11 | **Confirm**: after successful settlement, convert hold to confirmed debit (final, irrevocable) | Must |
| LED-F12 | **Release**: if payment fails/times out, release the hold (restore available balance) | Must |
| LED-F13 | Two balance types exposed: **booked** (all confirmed transactions) and **available** (booked minus reservations) | Must |
| LED-F14 | **Reservation timeout**: if not confirmed/released within configurable time (default: 30 seconds), auto-release. Timeout mechanism must be **durable** (database-scheduled or Kafka-delayed message, NOT in-memory timer) to survive service restarts. See also master system RES-01 | **Must** |
| LED-F15 | Reservation concurrency: concurrent reserve requests for the same account must not result in over-reservation (use PostgreSQL SELECT FOR UPDATE SKIP LOCKED or optimistic locking) | Must |

### 2.3 Account Lifecycle

| ID | Requirement | Priority |
|----|------------|----------|
| LED-F20 | Account creation: POST /api/v1/accounts → generates IBAN, sets status ACTIVE, balance 0.00 | Must |
| LED-F21 | Account states: ACTIVE → FROZEN → ACTIVE (unfreeze) or ACTIVE → CLOSED (terminal, balance must be 0) | Must |
| LED-F22 | Frozen account: debits rejected, credits allowed (don't lose incoming payments) | Must |
| LED-F23 | Account holder name stored (used by A6 VoP for name matching) | Must |

### 2.4 Query API

| ID | Requirement | Priority |
|----|------------|----------|
| LED-F30 | GET /api/v1/accounts — list all accounts (paginated) | Must |
| LED-F31 | GET /api/v1/accounts/{id}/balances — available and booked balances per currency | Must |
| LED-F32 | GET /api/v1/accounts/{id}/transactions — transaction history (paginated, filterable by date range, type) | Must |
| LED-F33 | GET /api/v1/accounts/{id}/statement?from=...&to=...&format=json — account statement | Must |
| LED-F34 | GET /api/v1/accounts/{id}/statement?format=camt053 — ISO 20022 camt.053 XML statement | Should |
| LED-F35 | **Point-in-time balance query**: GET /api/v1/accounts/{id}/balances?at=2026-03-31T23:59:59Z — returns balance at a historical moment by replaying events up to that timestamp. For regulatory reporting (quarter-end, year-end balances) | Should |
| LED-F36 | **External reference on events**: every balance change event carries `externalReference` (clearing cycle ID + settlement reference from A2). Required for reconciliation — links internal events to external settlement confirmations | Must |
| LED-F37 | **Reconciliation status**: each transaction has status UNRECONCILED → RECONCILED → DISCREPANCY. Updated when reconciliation runs against A2 settlement reports | Must |
| LED-F38 | **Reconciliation API**: POST `/api/v1/reconciliation/run` — accepts settlement report (camt.053 from A2 or structured JSON), matches against internal ledger entries by `externalReference`. Returns: `{matched: N, discrepancies: [{reference, internalAmount, externalAmount, type}], unmatched_internal: N, unmatched_external: N}` | Must |
| LED-F39 | **Reconciliation discrepancy alerts**: on discrepancy detection, publish to `payment.reconciliation-results` Kafka topic and log at WARN level. Discrepancies are operationally critical — they mean money is unaccounted for | Must |

### 2.5 Snapshot Mechanism

| ID | Requirement | Priority |
|----|------------|----------|
| LED-F40 | Periodically snapshot account balances to PostgreSQL (e.g., every 1,000 events per account) | Should |
| LED-F41 | On projection rebuild, start from latest snapshot instead of replaying all events from beginning | Should |
| LED-F42 | Snapshot metadata: account_id, balance, event_offset, snapshot_timestamp | Should |

### 2.6 Resilience and Crash Recovery

*Propagated from master system requirements (section 2.7)*

| ID | Requirement | Priority |
|----|------------|----------|
| LED-RES01 | **Atomic double-entry events**: debit and credit for a single transaction must be published as a **single compound Kafka message** (both entries in one event). Never publish debit without credit. This prevents accounting invariant violation if the service crashes between two separate publishes. Use Kafka transactions if separate messages are required | Must |
| LED-RES02 | **Transactional Outbox Pattern**: when A1 writes to PostgreSQL (projection update) AND publishes to Kafka, use the Transactional Outbox — write the event to an `outbox` table in the same DB transaction, then a relay publishes to Kafka. Alternatively, use Kafka as the sole source of truth (command → Kafka → projection consumer → PostgreSQL). Choose one approach and document the decision | Must |
| LED-RES03 | **Dead Letter Queue**: Kafka consumers (projection consumer, freeze-command consumer) must route unprocessable messages to `{topic}.dlq` after 3 retries. DLQ messages include original payload + error details. Monitor DLQ depth via Prometheus metric `ledger.dlq.depth` | Must |
| LED-RES04 | **Graceful shutdown**: on SIGTERM, complete processing of any in-flight balance reservations (confirm or release) before closing Kafka consumers. Do not leave reservations orphaned during deployment. Coordinate with RES-01 timeout as safety net | Must |

---

## 3. Non-functional Requirements

| ID | Requirement | Target |
|----|------------|--------|
| LED-NF01 | Balance query latency | < 50ms (p95) — reads from PostgreSQL projection, not event replay |
| LED-NF02 | Event processing throughput | >= 1,000 events/second |
| LED-NF03 | Balance reservation latency | < 100ms (for instant payment time budget) |
| LED-NF04 | Event ordering | Guaranteed per account (Kafka partition key) |
| LED-NF05 | Projection rebuild from 1M events | < 5 minutes |
| LED-NF06 | Data integrity | Double-entry invariant verified continuously (sum of debits = sum of credits) |

---

## 4. Technology Stack

| Component | Technology | Company Alignment |
|-----------|-----------|-------------------|
| Framework | **Spring Boot 3.x** | Universal |
| Event store | **Apache Kafka** (append-only log) | DNB, SpareBank1, Tietoevry backbone |
| Event serialization | **Avro** + **Confluent Schema Registry** | Production-grade schema evolution |
| Read model | **PostgreSQL** (balance projections, transaction history) | Universal |
| Money handling | **JSR 354 / Moneta** (`javax.money:money-api` + `org.javamoney:moneta`) | Proper multi-currency. Never float/double |
| CQRS | Separate command (Kafka producer) and query (PostgreSQL reader) services/endpoints | Tietoevry pattern |
| Concurrency | **PostgreSQL SELECT FOR UPDATE SKIP LOCKED** for balance reservations | Avoids lock contention on concurrent instant payments |
| ISO 20022 | **JAXB** for camt.053 statement generation | Mastercard Oslo alignment |
| Testing | **Testcontainers** (Kafka, PostgreSQL, Schema Registry), JUnit 5, **ArchUnit** | Tietoevry uses Testcontainers. ArchUnit enforces event sourcing patterns |
| Observability | **Micrometer + Prometheus** | Projection lag, event throughput, reservation count |

---

## 5. Event Schema (Avro)

```json
{
  "type": "record",
  "name": "LedgerEvent",
  "namespace": "com.nordpay.ledger.events",
  "fields": [
    {"name": "eventId", "type": "string"},
    {"name": "eventType", "type": {"type": "enum", "name": "EventType",
      "symbols": ["ACCOUNT_CREATED", "DEBIT", "CREDIT", "BALANCE_RESERVED",
                   "BALANCE_CONFIRMED", "BALANCE_RELEASED", "ACCOUNT_FROZEN",
                   "ACCOUNT_UNFROZEN", "ACCOUNT_CLOSED"]}},
    {"name": "accountId", "type": "string"},
    {"name": "amount", "type": ["null", "string"], "default": null},
    {"name": "currency", "type": ["null", "string"], "default": null},
    {"name": "counterpartyAccountId", "type": ["null", "string"], "default": null},
    {"name": "transactionReference", "type": ["null", "string"], "default": null},
    {"name": "reservationId", "type": ["null", "string"], "default": null},
    {"name": "externalReference", "type": ["null", "string"], "default": null},
    {"name": "clearingCycleId", "type": ["null", "string"], "default": null},
    {"name": "reconciliationStatus", "type": ["null", {"type": "enum", "name": "ReconciliationStatus",
      "symbols": ["UNRECONCILED", "RECONCILED", "DISCREPANCY"]}], "default": null},
    {"name": "idempotencyKey", "type": "string"},
    {"name": "timestamp", "type": "long", "logicalType": "timestamp-millis"},
    {"name": "metadata", "type": ["null", {"type": "map", "values": "string"}], "default": null}
  ]
}
```

---

## 6. Security Requirements

| ID | Requirement |
|----|------------|
| LED-S01 | Balance queries require OAuth2 token with `accounts:read` scope |
| LED-S02 | Commands (debit/credit/reserve) only from internal services (service-to-service auth) |
| LED-S03 | Audit log for every balance change and every balance query |
| LED-S04 | Account holder names stored for VoP (A6) — access restricted to VoP service |
| LED-S05 | Events in Kafka are immutable — once published, they cannot be modified or deleted |
| LED-S06 | PostgreSQL projections can be rebuilt — they are derived data, not the source of truth |
| LED-S07 | Balance reservation creates a time-limited hold — auto-release prevents permanent fund locking |

---

## 7. Acceptance Criteria

| ID | Criterion |
|----|----------|
| AC-01 | Credit 1,000 NOK to account A + debit 1,000 NOK from account A in events → balance = 0 NOK |
| AC-02 | Double-entry invariant: after any number of transactions, total debits across all accounts = total credits |
| AC-03 | Multi-currency: account with NOK and EUR balances correctly tracks both |
| AC-04 | Reserve 5,000 NOK → available balance decreases by 5,000, booked balance unchanged |
| AC-05 | Confirm reservation → booked balance decreases by 5,000, reservation removed |
| AC-06 | Release reservation → available balance restored, booked balance unchanged |
| AC-07 | Replay all events from Kafka → projections rebuilt identically to pre-rebuild state |
| AC-08 | Same event consumed twice → balance unchanged (idempotency) |
| AC-09 | Frozen account → debit rejected, credit accepted |
| AC-10 | ISO 20022 camt.053 statement generated for date range |
