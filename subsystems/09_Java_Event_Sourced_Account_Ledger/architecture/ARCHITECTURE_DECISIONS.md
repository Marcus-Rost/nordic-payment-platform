# A1 Event-Sourced Account Ledger — Architectural Decisions

*For each open challenge from ARCHITECTURE_CHALLENGES.md: options, tradeoffs, decision, and rationale.*
*Decisions are grounded in the principles from ARCHITECTURE_PRINCIPLES.md (P1-P7).*
*Date: 2026-04-11*

---

## How to Read This Document

Decisions follow the dependency order established in ARCHITECTURE_CHALLENGES.md:

1. **A.1 + A.3** — Event model (determines everything downstream)
2. **A.2 + B.1 + C.1** — Command flow (determines how operations execute)
3. **B.2, B.3, B.4** — Crash recovery mechanisms
4. **D.1, D.2, D.3** — Security architecture
5. **E.1, E.2** — Performance
6. **F.1, F.2** — Data integrity over time
7. **G.1** — Testing

Each decision includes:
- The challenge ID and summary
- The decision
- The rationale (which principles drive it, which research supports it)
- The implications for downstream decisions

---

## Decision Group 1: The Event Model

### Decision A.1 + A.3 — Double-Entry Atomicity and Event Granularity

**Challenge:** How do we ensure a debit and its matching credit are never published separately? And what granularity should events have?

**Decision: Hybrid event granularity with compound events for financial movements.**

The topic `ledger.events` carries three categories of events, each with a different structure:

**Category 1: Financial movements (double-entry)**

A single Kafka message containing both the debit entry and the credit entry. Published as one atomic message. Partition key: `transactionId`.

```
DoubleEntryTransaction {
  transactionId: string           // primary key, partition key
  eventType: "DOUBLE_ENTRY"
  debitAccountId: string
  debitAmount: string             // BigDecimal as string
  debitCurrency: string           // ISO 4217
  creditAccountId: string
  creditAmount: string
  creditCurrency: string
  externalReference: string|null  // clearing cycle ID + settlement ref
  clearingCycleId: string|null
  description: string|null        // encrypted PII (crypto-shredding)
  duplicateContext: map|null      // {duplicateFlag, duplicateOf, payerOverride}
  idempotencyKey: string
  timestamp: long                 // millis since epoch, UTC
  metadata: map|null
}
```

If the publish fails, neither entry exists. If it succeeds, both exist. Atomicity by construction.

**Category 2: Balance reservations (single-account)**

Per-account events for the reservation lifecycle. Partition key: `accountId`. This preserves per-account ordering for the reservation sequence (RESERVED → CONFIRMED or RELEASED).

```
ReservationEvent {
  eventType: enum {BALANCE_RESERVED, BALANCE_CONFIRMED, BALANCE_RELEASED}
  accountId: string               // partition key
  reservationId: string
  transactionId: string           // links to the payment
  amount: string
  currency: string
  idempotencyKey: string
  timestamp: long
}
```

**Category 3: Account lifecycle (single-account)**

Per-account events for account state changes. Partition key: `accountId`.

```
AccountLifecycleEvent {
  eventType: enum {ACCOUNT_CREATED, ACCOUNT_FROZEN,
                   ACCOUNT_UNFROZEN, ACCOUNT_CLOSED}
  accountId: string               // partition key
  holderName: string|null         // encrypted PII (crypto-shredding)
  holderAddress: string|null      // encrypted PII
  iban: string|null
  currency: string|null
  reason: string|null             // for freeze: CONFIRMED_FRAUD, etc.
  idempotencyKey: string
  timestamp: long
}
```

**Multiple Avro record types on the same topic**, using Confluent Schema Registry's `TopicRecordNameStrategy`. Each record type has independent schema evolution. The consumer dispatches by record type.

**Rationale:**

- **P1 (Financial Integrity):** Compound events make it structurally impossible to publish a debit without its matching credit. There is no code path, no crash scenario, no race condition that produces an unbalanced entry. This is the strongest atomicity guarantee, stronger than Kafka transactions (which can time out in-doubt) or saga compensation (which has a window of inconsistency).

- **P2 (Design for Crash):** One message, one Kafka produce call, one ack. If the service crashes before the ack, the message is not committed — neither entry exists. If the ack is received, both entries are durable. No intermediate state.

- **P7 (Simplest Correct Solution):** Compound events are simpler than Kafka transactions (no transaction coordinator, no `read_committed` configuration on consumers, no transaction timeout handling). They are simpler than saga compensation (no compensation logic, no intermediate states).

- **Research support:** Kleppmann (*DDIA*): "Compound events should be recorded atomically in raw form, then split downstream." TigerBeetle: transfers (spanning two accounts) are the primitive, account balances are derived. Square "Books": journal entries contain multiple book entries. Master architecture LED-RES01: "debit and credit as a single compound Kafka message."

- **Reservation events are per-account** because they affect only the debtor's account and must be ordered relative to each other (RESERVED before CONFIRMED). Same-partition guarantees this ordering.

**Implications:**

- The projection consumer must handle compound events: it applies both legs (debit + credit) in a single PostgreSQL transaction, using row-level locking to serialize per-account updates.
- Per-account ordering for financial movements is NOT guaranteed by Kafka partitioning (different transactions for the same account may land on different partitions, since partition key = transactionId). Per-account ordering is enforced at the projection level: the projection consumer uses `SELECT FOR UPDATE` on the account row when applying each leg. Balance correctness is maintained because debit/credit operations commute on the sum — the final balance is the same regardless of processing order.
- Per-account ordering for reservations and lifecycle events IS guaranteed by Kafka (partition key = accountId).
- Transaction history display: sorted by event timestamp, not by Kafka offset, since events from different partitions may be consumed in non-chronological order.

---

## Decision Group 2: The Command Flow

### Decision C.1 — Command Entry Points

**Challenge:** Should state-changing commands arrive via REST, Kafka, or a hybrid?

**Decision: Kafka for all state-changing commands. REST for all queries and admin operations.**

| Source | Command | Kafka Topic | Partition Key |
|--------|---------|-------------|---------------|
| A2 (instant) | Reserve / Confirm / Release balance | `payment.balance-reservation` | accountId |
| A2 (batch) | Settlement (debit + credit) | `payment.settlement-events` | transactionId |
| A2 (instant) | Settlement (debit + credit) | `payment.instant-settlement-events` | transactionId |
| A5 | Freeze / Unfreeze account | `account.freeze-commands` | accountId |

| Source | Query/Admin | Protocol |
|--------|-------------|----------|
| A4 | Balance, transaction history, statements | REST (mTLS + OAuth2) |
| A6 | Account holder name lookup | REST (mTLS + restricted scope) |
| A5 case mgmt | Account details for analyst display | REST (mTLS + restricted scope) |
| Admin | Create account, trigger reconciliation | REST (mTLS + admin scope) |

**Rationale:**

- **P4 (Events as Source of Truth):** All state changes flow through the event infrastructure. Every command is a durable Kafka message before it is processed. If A1 crashes, the command is replayed from Kafka on restart — nothing is lost.

- **P2 (Design for Crash):** REST requests are not durable. If A1 crashes after receiving a REST command but before processing it, the command is lost and the caller must retry. Kafka commands are durable — the message persists regardless of A1's state.

- **P5 (Immediate Consistency for Reservations):** Kafka partition key = accountId for reservations. Kafka guarantees per-partition ordering. A single consumer thread processes one partition. This means **concurrent reservation requests for the same account are naturally serialized** — no row locking needed for concurrency control. The second reservation waits in the partition queue until the first completes.

- **Master architecture alignment:** Section 5.1: "State changes flow through Kafka. Queries use synchronous REST." Section 9.1: A2 communicates with A1 via Kafka for settlement and reservations.

**Implications:**

- A1 has no REST endpoints for state-changing operations (except account creation and reconciliation triggers, which are admin/setup operations, not payment-path operations).
- The reservation path uses a **request-response pattern over Kafka**: A2 publishes a request to `payment.balance-reservation`, A1 publishes a response to `payment.balance-reservation-response` with a correlation ID (the transactionId). A2 matches responses to requests by transactionId.
- Latency budget for Kafka-based reservations: A2 publish (~5ms) + Kafka broker (~5ms) + A1 consumer poll (~10-20ms, configurable via `fetch.max.wait.ms`) + A1 processing (~20ms) + A1 response publish (~5ms) + Kafka broker (~5ms) + A2 consumer poll (~10-20ms) = **~60-80ms total**. Within the 100ms budget (LED-NF03). Requires tuning `fetch.max.wait.ms` to a low value (e.g., 10-50ms) on both sides.
- Account creation remains REST because it is an administrative setup operation, not a high-throughput payment-path operation. It is idempotent (creating an account that already exists returns the existing account).

---

### Decision A.2 + B.1 — Command Validation and Read-Your-Own-Writes

**Challenge:** Where does the command handler read current balance for validation? How do concurrent reservations see the latest available balance?

**Decision: The Kafka consumer IS the command handler. It reads from the PostgreSQL projection, validates, publishes the event, and updates the projection synchronously. Natural per-account serialization via Kafka partitioning eliminates the concurrency problem.**

The command flow for a balance reservation:

```
Step 1: A1 reservation consumer reads message from payment.balance-reservation
        (partition key = accountId → serialized per account)

Step 2: Consumer checks: SELECT * FROM reservations
        WHERE transaction_id = :txId
        → Already exists? Return cached result (idempotent replay)

Step 3: Consumer reads: SELECT available_balance FROM account_balances
        WHERE account_id = :accountId
        → No row lock needed (single-threaded per partition)

Step 4: Validate: available_balance >= requested_amount?
        → No: publish REJECTED response, commit offset, done
        → Yes: continue

Step 5: Consumer publishes BALANCE_RESERVED event to ledger.events
        (Kafka ack = all, waits for ack)

Step 6: Consumer updates PostgreSQL in a single transaction:
        - INSERT INTO reservations (reservation_id, transaction_id,
          account_id, amount, currency, created_at, status)
        - UPDATE account_balances
          SET available_balance = available_balance - :amount
          WHERE account_id = :accountId

Step 7: Consumer publishes response to payment.balance-reservation-response

Step 8: Consumer commits Kafka offset
```

**Why this ordering (publish event BEFORE updating projection):**

- If crash after Step 5 (event published) but before Step 6 (projection updated): On restart, the consumer re-reads the request. Step 2 finds no existing reservation in the DB. Steps 3-5 execute again — a duplicate event is published to Kafka. Step 6 creates the reservation in the DB. The duplicate event in Kafka is harmless: the async projection consumer deduplicates via idempotency key (B.3). The projection rebuild also deduplicates.

- If we reversed the order (update projection BEFORE publishing event): A crash after the projection update but before the Kafka publish would leave a reservation in the DB with no corresponding event in Kafka. The event log — the source of truth (P4) — would be incomplete. A projection rebuild from events would not include this reservation. **This violates P4. Therefore: Kafka publish first, projection update second.**

**Why the read-your-own-writes problem is eliminated:**

Because the same consumer thread that processes reservation N for account A also processes reservation N+1 for account A (same partition = same thread = serialized). Step 6 updates the projection before the consumer moves to the next message. So Step 3 of the next reservation reads the updated balance. There is no lag, no eventual consistency, no race condition.

**The async projection consumer's role:**

A separate consumer group reads from `ledger.events` and updates the PostgreSQL projection for ALL event types (compound financial movements, lifecycle events, and reservation events). For reservation events that were already applied by the command handler in Step 6, the async consumer detects the duplicate via idempotency key and skips. The async consumer is essential for:
- Processing compound financial movement events (the command handler for settlement events also updates the projection synchronously, but the async consumer serves as the catch-up mechanism)
- Projection rebuild (LED-F09): replaying all events from offset 0
- Handling events that arrive while the command handler is down

**Rationale:**

- **P1 (Financial Integrity):** No over-reservation possible. Per-account serialization means two concurrent reservations never race against the same balance.

- **P5 (Immediate Consistency):** The command handler reads and writes the projection synchronously. No lag between the write and the next read for the same account.

- **P4 (Events as Source of Truth):** The Kafka event is published before the projection is updated. The event log is always complete. The projection can always be rebuilt from it.

- **P2 (Design for Crash):** Every crash scenario has a safe outcome. The idempotency check in Step 2 catches replayed requests. The duplicate event in Kafka is deduplicated by the async consumer.

- **Research support:** Modern Treasury's "authorizing" mode: real-time balance validation with immediate consistency. The natural per-account serialization via Kafka partitioning is the same principle as TigerBeetle's single-threaded execution — contended accounts are serialized, which is both correct and sufficient.

**Implications:**

- A1 has multiple Kafka consumer groups, each with a specific role:
  - `a1-reservation-handler`: consumes `payment.balance-reservation`. The command handler.
  - `a1-settlement-handler`: consumes `payment.settlement-events` and `payment.instant-settlement-events`. Processes settlement commands.
  - `a1-freeze-handler`: consumes `account.freeze-commands`. Processes freeze/unfreeze.
  - `a1-projection-consumer`: consumes `ledger.events`. The async projection updater (catch-up, rebuild, dedup).
- Each handler follows the same pattern: read from projection → validate → publish event → update projection → publish response → commit offset.
- Row locking (`SELECT FOR UPDATE`) is NOT needed for the command handler (single-threaded per partition). It IS needed in the async projection consumer when applying compound financial movement events (which affect two accounts and arrive on non-account partitions where another consumer thread might be processing a different event for the same account concurrently).

---

## Decision Group 3: Crash Recovery Mechanisms

### Decision B.2 — Reservation Timeout

**Challenge:** How do stale reservations auto-expire, surviving crashes?

**Decision: Periodic database scan (every 5 seconds) + startup recovery scan.**

**Normal operation:**
A scheduled task runs every 5 seconds:
```sql
SELECT reservation_id, account_id, amount, currency, transaction_id
FROM reservations
WHERE status = 'RESERVED'
AND created_at < now() - interval '30 seconds'
```

For each stale reservation:
1. Publish `BALANCE_RELEASED` event to `ledger.events` (maintains P4)
2. Update reservation status to `RELEASED`
3. Update `available_balance += amount` in `account_balances`
4. Log at WARN level: "Reservation {id} auto-released after timeout"

**On startup (before readiness probe passes):**
The same scan runs once before the service accepts traffic. This catches reservations that became stale while the service was down.

**Rationale:**

- **P2 (Design for Crash):** The database persists across restarts. The scan finds stale reservations regardless of how the service stopped (graceful, crash, OOM). No in-memory state is required.

- **P4 (Events as Source of Truth):** The timeout produces a `BALANCE_RELEASED` event. The event log records the release. A projection rebuild correctly reflects the released reservation.

- **P7 (Simplest Correct Solution):** One SQL query on a small table (active reservations), every 5 seconds. No Kafka delayed messages, no pg_cron extension, no custom scheduler. The polling interval (5 seconds) means a reservation might live up to 35 seconds (30 + 5) — acceptable.

- **Research support:** Master architecture decision B.1b: "database scan for stale intermediate states." Nygard (*Release It!*): steady-state pattern — systems must clean up after themselves.

**Implications:**

- The `reservations` table must have an index on `(status, created_at)` for the scan query.
- The timeout value (30 seconds) is externalized to configuration (`@ConfigurationProperties`).
- The scan interval (5 seconds) is externalized to configuration.
- The scan is idempotent: if it runs twice for the same stale reservation, the second run finds the reservation already RELEASED and skips it.

---

### Decision B.3 — Idempotent Event Processing

**Challenge:** How do we prevent duplicate events from double-counting balances?

**Decision: Three-layer defense in depth.**

**Layer 1 — Command-level deduplication:**
Every command handler checks its own domain table for an existing result before processing:
- **Reservation handler:** `SELECT * FROM reservations WHERE transaction_id = :txId`
- **Settlement handler:** `SELECT * FROM transaction_history WHERE idempotency_key = :key`
- **Freeze handler:** Idempotent by nature — freezing an already-frozen account is a no-op (check `account_balances.status`)

If found, return the cached result without re-processing. This catches replayed Kafka messages after a crash or rebalance.

**Layer 2 — Kafka idempotent producer:**
All Kafka producers in A1 use `enable.idempotence=true`. The broker deduplicates messages from the same producer session (PID + sequence number). This catches network-level retries within a single session. It does NOT catch cross-session duplicates (after restart, a new PID is assigned).

**Layer 3 — Projection consumer deduplication:**
The async projection consumer (which reads from `ledger.events`) checks before applying each event:
```sql
INSERT INTO processed_event_ids (idempotency_key, processed_at)
VALUES (:key, now())
ON CONFLICT (idempotency_key) DO NOTHING
```
If the insert returns 0 rows affected, the event was already processed — skip it. The insert and the projection update are in the same PostgreSQL transaction, ensuring atomicity.

**TTL for the deduplication table:** 7 days. The master architecture suggests 24h for batch and 1h for instant, but these values assume normal operational replay windows. A 7-day TTL provides a wider safety margin: it covers the full retention period of non-`ledger.events` topics (7 days), meaning no replay from any retained message can bypass deduplication. The cost is modest — the table holds one row per processed event, cleaned up weekly. For `ledger.events` (infinite retention), projection rebuilds reset the dedup table as part of the rebuild process (truncate + replay).

**Rationale:**

- **P1 (Financial Integrity):** A duplicated CREDIT event would create money from nothing. Three independent layers ensure this cannot happen. Each layer catches what the others miss.

- **P2 (Design for Crash):** Layer 1 catches replayed commands after crash. Layer 2 catches network retries within a session. Layer 3 catches duplicate events that slip through the first two layers (e.g., compound events that were published twice due to crash between publish and offset commit).

- **Research support:** Stripe: "Three-layer defense in depth — API dedup, outbox, consumer dedup. Each layer independently tolerates duplicates. No layer trusts the others." Kleppmann: "Exactly-once across system boundaries requires at-least-once + idempotent consumers."

**Implications:**

- Every event carries an `idempotencyKey` field (already in the Avro schema).
- The `processed_event_ids` table in PostgreSQL: `(idempotency_key VARCHAR PRIMARY KEY, processed_at TIMESTAMP)`.
- A scheduled cleanup task deletes rows older than 7 days.
- Projection rebuild: truncate `processed_event_ids` before replaying from offset 0 (since we're rebuilding from scratch, all events are "new").

---

### Decision B.4 — Graceful Shutdown

**Challenge:** How do we stop cleanly without orphaning reservations?

**Decision: Drain with bounded timeout. Reservation timeout (B.2) as safety net.**

On SIGTERM:

1. **Mark not ready** — fail the Kubernetes readiness probe. No new traffic is routed.
2. **Stop polling Kafka** — `consumer.wakeup()` on all consumer groups. In-flight message processing continues.
3. **Wait for in-flight operations** — up to 10 seconds. Each command handler completes its current message (validate → publish → update projection → commit offset).
4. **Commit offsets** — for all consumer groups, commit the offset of the last fully processed message.
5. **Close connections** — Kafka producers, PostgreSQL connection pool.
6. **Exit.**

If in-flight operations do not complete within 10 seconds:
- Kafka offsets are committed for completed messages only. Uncompleted messages will be redelivered on restart.
- Any reservation that was created (event published, projection updated) but whose response was not published: the reservation exists and is valid. A2 will timeout and treat it as failed. The reservation will be cleaned up by the timeout mechanism (B.2).
- Any reservation that was validated but whose event was not published: the reservation does not exist. A2 will timeout and retry.

**Rationale:**

- **P2 (Design for Crash):** The shutdown logic is designed so that every outcome is safe. The reservation timeout (B.2) is the unconditional safety net. Even if shutdown is not graceful (SIGKILL, OOM), the timeout mechanism eventually releases stale reservations.

- **P7 (Simplest Correct Solution):** 10-second drain + reservation timeout covers all cases. No complex state serialization or checkpoint logic.

- **Configuration:** Kubernetes `terminationGracePeriodSeconds` = 30 seconds (generous margin beyond the 10-second drain). Spring Boot `spring.lifecycle.timeout-per-shutdown-phase` = 10 seconds.

---

## Decision Group 4: Security Architecture

### Decision D.1 — Trust Boundaries and Access Control

**Challenge:** Who can access what in A1, enforced structurally?

**Decision: Three-zone model with HMAC on Kafka and mTLS + scoped OAuth2 on REST. Access enforced at two levels: transport (who can connect) and authorization (what they can do).**

**Kafka message authentication:**

Every inbound Kafka message is verified by checking the HMAC-SHA256 signature in the message header against the expected producer key. Per-producer HMAC keys:

| Topic | Expected Producer | HMAC Key ID |
|-------|------------------|-------------|
| `payment.settlement-events` | A2 | `hmac-a2` |
| `payment.instant-settlement-events` | A2 | `hmac-a2` |
| `payment.balance-reservation` | A2 | `hmac-a2` |
| `account.freeze-commands` | A5 | `hmac-a5` |

A message with an invalid or missing HMAC is rejected and routed to the DLQ with error `HMAC_VERIFICATION_FAILED`. This prevents a compromised service from injecting messages into topics it should not write to.

**REST API authorization:**

| Endpoint | Required Scope | Consumers |
|----------|---------------|-----------|
| `GET /api/v1/accounts` | `accounts:read` | A4 |
| `GET /api/v1/accounts/{id}/balances` | `accounts:read` | A4 |
| `GET /api/v1/accounts/{id}/transactions` | `accounts:read` | A4 |
| `GET /api/v1/accounts/{id}/statement` | `accounts:read` | A4, reconciliation |
| `GET /api/v1/accounts/{id}/holder-name` | `vop:read` | A6 only |
| `POST /api/v1/accounts` | `accounts:admin` | Admin |
| `POST /api/v1/reconciliation/run` | `reconciliation:admin` | Admin, scheduler |

The `vop:read` scope is restricted: it grants access to the holder name endpoint only. A6 cannot access balances or transaction history. This enforces the principle of least privilege.

**Platform Starter enforcement:**

The Starter configures `anyRequest().authenticated()`. A1 overrides this to additionally require specific scopes per endpoint group. Actuator endpoints (`/actuator/health`, `/actuator/readiness`, `/actuator/prometheus`) are pre-permitted.

**Domain primitives (P3):**

All financial value types are implemented as domain primitives in the `payment-domain-model` shared library:

| Type | Invariants | Prevents |
|------|-----------|----------|
| `AccountId` | Non-empty, valid UUID format | Empty/malformed account references |
| `IBAN` | Valid structure, correct check digits (mod-97) | Invalid IBANs passing through the system |
| `MonetaryAmount` | Non-negative, bounded precision (2 decimal places for fiat), immutable | Negative amounts, floating-point errors, currency confusion |
| `Currency` | Valid ISO 4217 code from a fixed set (NOK, EUR, SEK, DKK, USD) | Unknown or unsupported currencies |
| `TransactionId` | Non-empty, valid UUID format | Empty/malformed transaction references |
| `ReservationId` | Non-empty, valid UUID format | Empty/malformed reservation references |

These types are validated at construction. An invalid `MonetaryAmount` cannot exist in memory — the constructor throws. This moves validation from scattered runtime checks to the type system.

**Rationale:**

- **P3 (Security by Construction):** Domain primitives make invalid values structurally impossible. HMAC verification makes unauthorized Kafka messages structurally rejected. Scoped OAuth2 makes unauthorized REST access structurally denied. Security is not a checklist — it is a property of the types and the infrastructure.

- **Research support:** Johnsson, Deogun & Sawano (*Secure by Design*): domain primitives as the core security technique. OWASP Microservices Security: mTLS + scoped authorization. Master architecture A.1a: three-zone model with HMAC internally.

---

### Decision D.2 — Crypto-Shredding

**Challenge:** How does GDPR erasure work in an immutable event log without breaking financial integrity?

**Decision: Encrypt PII fields in the event payload with per-account AES-256-GCM keys. Delete the key for erasure.**

**What is encrypted:**

| Field | Encrypted | Rationale |
|-------|-----------|-----------|
| Account holder name | Yes | Direct PII identifier |
| Account holder address | Yes | Direct PII |
| Transaction description / remittance info | Yes | May contain PII |
| IBAN | No | Pseudonymous. Needed for routing, deduplication, reconciliation, VoP |
| Amount, currency | No | Financial data, not PII. Required for balance calculations |
| Timestamps, IDs, event types | No | Operational / technical data |

**Key storage:**

Per-account AES-256-GCM keys stored in A1's PostgreSQL:

```sql
CREATE TABLE account_encryption_keys (
    account_id   VARCHAR PRIMARY KEY,
    key_id       VARCHAR NOT NULL,         -- for key rotation tracking
    encrypted_key BYTEA NOT NULL,          -- AES key encrypted with master key
    created_at   TIMESTAMP NOT NULL,
    deleted_at   TIMESTAMP                 -- set on erasure
);
```

The per-account key is itself encrypted with the master key, managed via the `KeyProvider` abstraction. In the portfolio: the master key is a deployment-time secret. In production: the master key lives in a KMS.

**Encryption flow (at event creation):**

1. Load per-account encryption key from `account_encryption_keys` (cached in-process with Caffeine, TTL 5 minutes)
2. Encrypt PII fields: `AES-256-GCM(plaintext, accountKey, randomIV)`
3. Store the ciphertext + IV in the event's PII fields
4. Publish event to Kafka

**Decryption flow (at event consumption / query):**

1. Load per-account encryption key
2. If key exists: decrypt PII fields, return plaintext
3. If key is deleted (`deleted_at IS NOT NULL`): return `[REDACTED]`

**Erasure flow:**

1. Precondition: account status = CLOSED, mandatory retention period expired
2. Delete the per-account key: `UPDATE account_encryption_keys SET encrypted_key = NULL, deleted_at = now() WHERE account_id = :id`
3. Update projection: set holder name to `[REDACTED]`
4. VoP queries for this account: return `ACCOUNT_CLOSED`

**Projection rebuild after erasure:**

Events with deleted keys: PII fields decrypt to `[REDACTED]`. Balance calculations use unencrypted amounts — **financial integrity is preserved** (P1). The projection contains correct balances with redacted names.

**Rationale:**

- **P3 (Security by Construction):** PII is encrypted at the earliest point (event creation) and remains encrypted in Kafka, in all consumers, and in all copies. The only way to access PII is through the key. Deleting the key makes PII irrecoverable across all copies, including Kafka replicas and backup tapes.

- **P1 (Financial Integrity):** Amounts and IBANs are NOT encrypted. Balance calculations, reconciliation, and deduplication work without decryption. Erasure does not affect financial correctness.

- **P7 (Simplest Correct Solution):** Encrypting PII fields in the event is simpler than the Forgettable Payloads pattern (which requires a separate PII store and a join on every read). The encryption overhead is negligible — AES-256-GCM processes >1 GB/sec on modern hardware.

- **Research support:** Verraes: crypto-shredding pattern for event-sourced systems. Kurrent: per-data-subject symmetric keys. Master architecture decision F.1a: encrypt name, address, description; plaintext IBAN, amounts, timestamps.

---

### Decision D.3 — Audit Trail Tamper Evidence

**Challenge:** How do we prove the event log hasn't been tampered with?

**Decision: Per-service hash chain, implemented by the Platform Starter. Periodic signed checkpoint.**

**Hash chain mechanism:**

Each audit event published by A1 includes:
- `previousEventHash`: SHA-256 hash of the previous audit event from this service
- `eventHash`: SHA-256 hash of the current event (including `previousEventHash`)

This forms a per-service hash chain. Modifying any event invalidates all subsequent hashes.

**Integrity verification:**

A scheduled task (daily, or on-demand via admin API) walks the hash chain from the first event to the last, verifying each link. Any break is logged at ERROR level and published as an alert.

**Signed checkpoint (daily):**

At the end of each day, A1 computes the hash chain head (the hash of the most recent event) and publishes a signed checkpoint to a separate `audit.checkpoints` topic. The checkpoint is signed with A1's service key. This anchors the hash chain to an external record — even if the entire `audit.events` topic is rewritten, the checkpoint in `audit.checkpoints` (on a separate, more restricted topic) detects the tampering.

**Rationale:**

- **P3 (Security by Construction):** Tamper evidence is a structural property of the event stream, not a runtime check. The hash chain makes tampering detectable, not just prohibited.

- **P7 (Simplest Correct Solution):** Hash chains are simpler than Merkle trees (no tree structure to maintain) and sufficient for our threat model (detect tampering, not provide inclusion proofs to third parties). If third-party verification is needed in the future, Merkle trees can be layered on top.

- **Research support:** Crosby & Wallach (USENIX 2009): hash-chain and Merkle tree logging. Master architecture F.2a: hash chaining for portfolio, signatures + external anchoring for production target. TigerBeetle: "hash-chaining everything."

- **Platform Starter:** The hash chain mechanism is implemented in the Platform Starter's audit module, not in A1 specifically. A1 uses the `@Audited` annotation and the `AuditLogger` API; the Starter handles the hash chaining transparently.

---

## Decision Group 5: Performance

### Decision E.1 — Projection Rebuild Performance

**Challenge:** How do we rebuild projections from millions of events in minutes?

**Decision: Snapshots every 1,000 events per account + parallel replay across Kafka partitions.**

**Snapshot mechanism:**

After every 1,000 events for a given account, the projection consumer writes a snapshot:

```sql
INSERT INTO account_snapshots
    (account_id, booked_balance, available_balance, currencies,
     event_offset, snapshot_timestamp, snapshot_hash)
VALUES (:accountId, :booked, :available, :currencies,
        :offset, now(), :hash)
```

`snapshot_hash` = SHA-256 of the snapshot fields. Used to verify snapshot integrity before using it for rebuild.

**Rebuild procedure:**

1. Stop the async projection consumer.
2. Truncate projection tables (`account_balances`, `transaction_history`, `reservations`, `processed_event_ids`).
3. Load latest snapshot per account from `account_snapshots`. Initialize `account_balances` from snapshots.
4. For each Kafka partition (in parallel, one thread per partition): seek to the earliest event offset that is after the oldest loaded snapshot. Replay events, applying each to the projection. Deduplicate via idempotency key.
5. Verify: `SUM(debits) = SUM(credits)` across all accounts (the accounting invariant, P1).
6. Restart the async projection consumer from the current end offset.

**Performance estimate:**

- 10,000 accounts, 100 events/account since last snapshot = 1M events to replay
- 12 Kafka partitions, 12 parallel threads
- ~83K events per thread at 1K events/sec = ~83 seconds per thread
- Total wall-clock: ~1.5 minutes. Well within the 5-minute target (LED-NF05).

**Without snapshots (worst case for verification):**

- Full replay from offset 0: 10M events at 1K/sec/thread, 12 threads = ~14 minutes
- Exceeds the 5-minute target. Snapshots are necessary at scale.

**Rationale:**

- **P4 (Events as Source of Truth):** Projection rebuild from events is a testable, regular operation. Snapshots optimize it without replacing it — a full replay without snapshots must also work (just slower).

- **P7 (Simplest Correct Solution):** Snapshots are a simple key-value store (account → balance + offset). Parallel replay uses standard Kafka consumer assignment. No exotic infrastructure.

- **Research support:** Vernon: snapshots as optimization for aggregate rehydration. Monzo: learned that O(n) balance calculation does not scale — pre-computed balance blocks were critical. LED-F40-F42: snapshot requirements.

---

### Decision E.2 — Projection Lag Under Burst Load

**Challenge:** How do batch settlement bursts affect instant payment reservations?

**Decision: Separate consumer groups with independent processing. The reservation hot path is unaffected by async consumer lag.**

- **Reservation commands**: processed by `a1-reservation-handler` consumer group, reading from `payment.balance-reservation`. The command handler updates the projection synchronously (Decision A.2). Not affected by lag in the async projection consumer.

- **Settlement events**: processed by `a1-settlement-handler` consumer group, reading from `payment.settlement-events` and `payment.instant-settlement-events`. The handler publishes compound `DoubleEntryTransaction` events to `ledger.events` and updates the projection synchronously.

- **Async projection consumer** (`a1-projection-consumer`): reads from `ledger.events`, applies events to the projection. During a settlement burst, this consumer may lag. The lag is visible in balance queries (slight staleness) but does NOT affect reservations (which are handled by the reservation consumer's synchronous path).

**Monitoring:**
- `ledger.projection.lag`: Kafka consumer lag for `a1-projection-consumer`, exposed as Prometheus metric
- Alert: lag > 1 second → warning. Lag > 5 seconds → critical.
- `ledger.reservation.duration`: histogram of reservation processing time. Alert if p99 > 80ms.

**Rationale:**

- **P5 (Immediate Consistency for Reservations):** The reservation path's consistency is guaranteed by the synchronous projection update in the command handler, independent of the async consumer's speed.

- **P1 (Financial Integrity):** Even if the async consumer is minutes behind, the reservation handler always reads the latest balance (it wrote the latest balance itself, Decision A.2).

---

## Decision Group 6: Data Integrity Over Time

### Decision F.1 — Event Schema Evolution

**Challenge:** How do immutable events handle schema changes over time?

**Decision: Avro with Schema Registry, BACKWARD compatibility, TopicRecordNameStrategy, with strict allowed/forbidden rules enforced in CI/CD.**

**Schema Registry configuration:**

- Compatibility mode: `BACKWARD` (default). A new schema can read data written with the previous schema.
- Subject naming: `TopicRecordNameStrategy`. Each Avro record type (`DoubleEntryTransaction`, `ReservationEvent`, `AccountLifecycleEvent`) has its own subject in the registry. They evolve independently.

**Allowed changes:**
- Add a new field with a default value
- Add a new enum symbol (with a reader default for unknown values)
- Add a new record type (new event category)

**Forbidden changes:**
- Remove or rename a field
- Change a field's type (e.g., string → int)
- Change a field from optional to required
- Remove an enum symbol

**Enforcement:**
- CI/CD: every PR that modifies an Avro schema runs `schema-registry:test-compatibility` against the currently registered schema. Build fails on incompatible change.
- Code review: semantic changes (a field that previously meant X now means Y) cannot be caught by automated checks. These require explicit code review and documentation.

**Breaking changes (rare, last resort):**
- Create a new topic (`ledger.events.v2`) with the new schema.
- A migration consumer replays `ledger.events` through a transformer into `ledger.events.v2`.
- After migration, consumers switch to `ledger.events.v2`. The old topic is retained but no longer actively consumed.

**Rationale:**

- **P6 (Events Are Public Contracts):** Once an event is published, its schema is permanent. Backward compatibility ensures that the projection consumer can always read historical events, regardless of how the schema has evolved since they were written.

- **P4 (Events as Source of Truth):** Projection rebuild replays all events from the beginning. If any historical event is unreadable due to a schema change, the rebuild fails and the event log is no longer a useful source of truth.

- **Research support:** Kleppmann: Avro with Schema Registry, `TopicRecordNameStrategy` for multiple event types per topic. Greg Young: "Events are public contracts."

---

### Decision F.2 — Reconciliation Architecture

**Challenge:** How do we compare internal ledger events against external settlement reports?

**Decision: REST-triggered, SQL-based matching against the PostgreSQL projection. Discrepancies published to Kafka.**

**Flow:**

1. Operator or scheduler calls `POST /api/v1/reconciliation/run` with the settlement report (camt.053 XML or structured JSON).
2. A1 loads the settlement report into a temporary PostgreSQL table:
   ```sql
   CREATE TEMP TABLE settlement_entries (
       external_reference VARCHAR,
       amount NUMERIC,
       currency VARCHAR,
       settlement_date DATE,
       entry_type VARCHAR  -- DEBIT or CREDIT
   );
   ```
3. A1 performs a full outer join:
   ```sql
   SELECT
       s.external_reference AS ext_ref,
       t.external_reference AS int_ref,
       s.amount AS ext_amount,
       t.amount AS int_amount,
       CASE
           WHEN t.external_reference IS NULL THEN 'UNMATCHED_EXTERNAL'
           WHEN s.external_reference IS NULL THEN 'UNMATCHED_INTERNAL'
           WHEN s.amount != t.amount THEN 'AMOUNT_DISCREPANCY'
           ELSE 'MATCHED'
       END AS result
   FROM settlement_entries s
   FULL OUTER JOIN transaction_history t
       ON s.external_reference = t.external_reference
       AND s.currency = t.currency
   WHERE t.transaction_date BETWEEN :from AND :to
   ```
4. For matched entries: update reconciliation status to RECONCILED.
5. For discrepancies: update status to DISCREPANCY, publish to `payment.reconciliation-results` Kafka topic, log at WARN.
6. Return summary: `{matched: N, discrepancies: [...], unmatchedInternal: N, unmatchedExternal: N}`.

**For large reports (>10,000 entries):** The REST endpoint returns `202 Accepted` with a reconciliation job ID. The processing runs asynchronously. A `GET /api/v1/reconciliation/{jobId}` endpoint returns the status and results.

**Rationale:**

- **P1 (Financial Integrity):** Reconciliation is the external verification of the accounting identity. It catches errors that internal checks (debits = credits) cannot: missed events, settlement errors, integration bugs.

- **P7 (Simplest Correct Solution):** SQL-based matching leverages PostgreSQL's query engine. No custom matching algorithm. The full outer join naturally classifies all four cases (matched, amount discrepancy, unmatched internal, unmatched external).

- **P4 (Events as Source of Truth):** Reconciliation runs against the projection (fast). For verification, a separate reconciliation run can replay events for the period and compare — this uses the source of truth directly but is slower.

---

## Decision Group 7: Testing

### Decision G.1 — Testing Strategy

**Challenge:** How do we test event-sourcing-specific failure modes?

**Decision: Four-level testing pyramid with event-sourcing-specific additions at each level.**

**Level 1 — Unit tests (in-memory, no infrastructure):**

- **Domain primitives:** `MonetaryAmount`, `IBAN`, `AccountId` — construction with invalid values throws. Arithmetic correctness (no floating-point errors).
- **Command validation logic:** given a balance of X, a reservation of Y succeeds/fails.
- **Event application logic:** given a `DoubleEntryTransaction`, the projection update computes the correct balance changes.
- **In-memory event store:** A `List<LedgerEvent>` replaces Kafka. Commands produce events, events are applied to an in-memory projection. Assertions on the projection state.
- **Accounting invariant after every test:** `assertThat(sumDebits()).isEqualTo(sumCredits())`

Tools: JUnit 5, AssertJ. Speed: <1 second per test class.

**Level 2 — Service integration tests (Testcontainers):**

- **Kafka round-trip:** Publish an event to `ledger.events`, verify the projection consumer updates PostgreSQL correctly.
- **Schema compatibility:** Serialize an event with schema V1, deserialize with schema V2 — verify backward compatibility.
- **Reservation full cycle:** Publish reservation request to `payment.balance-reservation`, verify BALANCE_RESERVED event published, projection updated, response published.
- **Idempotency:** Publish the same event twice, verify the balance changes only once.
- **Crash recovery simulation:** Stop the consumer mid-batch (Testcontainers `container.stop()`), restart, verify projections are correct after recovery.
- **Crypto-shredding round-trip:** Create account, publish events with encrypted PII, delete key, verify `[REDACTED]` in projection.

Tools: Testcontainers (Kafka, PostgreSQL, Schema Registry), Spring Boot Test, Awaitility. Speed: 10-30 seconds per test class.

**Level 3 — Architectural fitness tests (ArchUnit):**

- "No class in the `projection` package may import classes from the `command` package" — enforces CQRS separation.
- "All event classes must be Avro-generated from schemas in `payment-avro-schemas`" — prevents hand-crafted events that bypass schema evolution.
- "All methods that update `account_balances` must be in classes annotated with `@ProjectionUpdater`" — prevents shadow writes.
- "All command handlers must call `idempotencyCheck()` before processing" — enforces deduplication.

Tools: ArchUnit. Speed: <5 seconds.

**Level 4 — Property-based tests for the accounting invariant:**

- Generate random sequences of commands: credits, debits, reservations, confirmations, releases, freezes, unfreezes.
- After every sequence (regardless of length, ordering, or composition): `sumDebits == sumCredits`.
- After every reservation + confirmation: `available == booked - activeReservations`.
- After every projection rebuild from events: balances match pre-rebuild balances.

Tools: jqwik (property-based testing library for JUnit 5). Speed: 5-30 seconds (configurable number of trials).

**Contract tests (cross-service, Level 2.5):**

- **Pact (message-based):** A1 (consumer) defines expectations for messages on `payment.settlement-events` — A2 (producer) verifies.
- **Pact (REST):** A4 (consumer) defines expectations for A1's balance query API — A1 (provider) verifies.

Tools: Pact. Speed: 5-10 seconds per contract.

**Rationale:**

- **P1 (Financial Integrity):** The accounting invariant is verified at every level. Unit tests verify it per-operation. Property-based tests verify it across arbitrary operation sequences. Integration tests verify it across Kafka/PostgreSQL boundaries.

- **P2 (Design for Crash):** Crash recovery is tested explicitly in integration tests (stop container, restart, verify correctness).

- **Research support:** Nubank: property-based testing for accounting invariants. SpareBank 1: TDD with DDD fakes. Tietoevry: Testcontainers for integration testing. Master architecture: Pact for contract testing, ArchUnit for architectural fitness.

---

## Supplementary Decisions

### Decision LED-RES03 — Dead Letter Queue Mechanism

**Challenge:** LED-RES03 requires that Kafka consumers route unprocessable messages to DLQ after 3 retries, with original payload and error details, monitored via Prometheus.

**Decision: Three retries with exponential backoff, then route to `{originalTopic}.dlq`. Implemented via Platform Starter's `PaymentErrorHandler`.**

**Retry policy:**
- Retries: 3 attempts with exponential backoff (100ms, 500ms, 2500ms)
- Retryable errors: transient database failures, temporary Kafka unavailability
- Non-retryable errors (immediate DLQ): deserialization failure, HMAC verification failure, unknown event type, schema incompatibility

**DLQ message structure:**
- Original message payload (unmodified)
- Headers: `dlq.original.topic`, `dlq.original.partition`, `dlq.original.offset`, `dlq.error.message`, `dlq.error.class`, `dlq.retry.count`, `dlq.timestamp`

**DLQ topics:**
- `payment.balance-reservation.dlq`
- `payment.settlement-events.dlq`
- `payment.instant-settlement-events.dlq`
- `account.freeze-commands.dlq`
- `ledger.events.dlq`

**Monitoring:** `ledger.dlq.depth` gauge metric per DLQ topic. Alert if depth > 0 for any DLQ.

**Rationale:** The Platform Starter provides the `PaymentErrorHandler` bean that classifies errors into retryable, DLQ, and DLQ+alert categories (master architecture decision E.1b). A1 uses this standard mechanism. The three-retry policy balances recovery from transient errors against latency (total retry window: ~3 seconds). Non-retryable errors go directly to DLQ without wasting time on doomed retries.

---

### Decision LED-F35 — Point-in-Time Balance Query (Deferred)

**Challenge:** LED-F35 (priority: Should) specifies a `GET /api/v1/accounts/{id}/balances?at=<timestamp>` endpoint returning the balance at a historical moment by replaying events up to that timestamp.

**Decision: Deferred to a later implementation phase.**

**Rationale:** Point-in-time balance queries require replaying events from the last snapshot before the target timestamp, filtering events up to the timestamp, and computing the balance at that moment. This is architecturally straightforward given our event sourcing foundation — the event log contains all the data needed. However, the implementation requires:
- A query mechanism that seeks to a specific Kafka offset by timestamp (`offsetsForTimes`)
- An in-memory aggregate builder that replays events and computes the balance
- Handling of crypto-shredded events (amounts are plaintext, so balance calculation works even after key deletion)

None of these require architectural changes — they build on the existing event model and snapshot mechanism. The endpoint is deferred because:
1. It is "Should" priority, not "Must"
2. No other sub-system depends on it for the instant payment path
3. The primary consumers (regulatory reporting, quarter-end balances) can use the snapshot mechanism as an interim solution

**When implemented:** The endpoint will replay events from the nearest snapshot before the target timestamp, applying events up to the timestamp. Performance: bounded by events-since-snapshot (typically <1,000 events per account), completing in <100ms for most accounts.

---

### Divergence Note: Use Case UC01 Scenario 1A

The use case document (`use_cases/UC01_ledger_operations.md`, Scenario 1A) describes a standard credit transfer as two separate events — a DEBIT event (Event #1) and a CREDIT event (Event #2) published sequentially. The architecture replaces this with a single compound `DoubleEntryTransaction` event containing both legs, driven by P1 (Financial Integrity) and P2 (Design for Crash) — the compound event makes it structurally impossible to publish a debit without its matching credit.

Similarly, Scenario 1D (Concurrent Reservations) describes `SELECT FOR UPDATE SKIP LOCKED` for concurrency control. The architecture replaces this with Kafka-based per-account serialization, which eliminates the need for row locking on the reservation path entirely.

These divergences from the use case document are deliberate architectural improvements. The use cases describe the *what* (functional behavior); the architecture determines the *how* (structural mechanism). The observable behavior (balance changes, rejection of over-reservation) is identical.

---

## Decision Summary

| ID | Challenge | Decision | Key Principle |
|----|-----------|----------|---------------|
| A.1+A.3 | Double-entry atomicity + event granularity | Compound events for financial movements (both legs in one message); fine-grained per-account events for reservations and lifecycle | P1, P2 |
| C.1 | Command entry points | Kafka for all state-changing commands; REST for queries and admin | P4, P2 |
| A.2+B.1 | Command validation + read-your-own-writes | Kafka consumer IS the command handler. Reads from projection, publishes event, updates projection synchronously. Per-account serialization via Kafka partitioning | P1, P5 |
| B.2 | Reservation timeout | Periodic DB scan (5s) + startup scan. Publishes BALANCE_RELEASED event | P2, P4 |
| B.3 | Idempotent event processing | Three-layer: command dedup + idempotent producer + consumer dedup table | P1, P2 |
| B.4 | Graceful shutdown | Drain with 10s timeout. Reservation timeout as safety net | P2 |
| D.1 | Trust boundaries | HMAC on Kafka, mTLS + scoped OAuth2 on REST, domain primitives | P3 |
| D.2 | Crypto-shredding | Encrypt PII fields (name, address, description) per-account AES-256-GCM. Key in A1 PostgreSQL | P3, P1 |
| D.3 | Audit tamper evidence | Hash chain (Platform Starter), daily signed checkpoint | P3 |
| E.1 | Projection rebuild | Snapshots every 1K events/account + parallel replay | P4, P7 |
| E.2 | Burst lag isolation | Separate consumer groups. Reservation path unaffected by async lag | P5, P1 |
| F.1 | Schema evolution | Avro BACKWARD compat, TopicRecordNameStrategy, CI/CD enforcement | P6, P4 |
| F.2 | Reconciliation | REST-triggered SQL join. Discrepancies to Kafka. Async for large reports | P1, P7 |
| G.1 | Testing | 4-level pyramid: unit (in-memory), integration (Testcontainers), ArchUnit, property-based (jqwik). Pact for contracts | P1, P2 |
| LED-RES03 | Dead Letter Queue | 3 retries with exponential backoff, then `{topic}.dlq`. Platform Starter's `PaymentErrorHandler` | P2 |
| LED-F35 | Point-in-time balance query | Deferred. Architecturally straightforward, no structural changes needed | P7 |

---

## Appendix: Consumer Group Architecture

A1 runs the following Kafka consumer groups:

| Consumer Group | Topic(s) | Role | Partition Key | Notes |
|---------------|----------|------|---------------|-------|
| `a1-reservation-handler` | `payment.balance-reservation` | Command handler for reservations | accountId | Serialized per account. Synchronous projection update |
| `a1-settlement-handler` | `payment.settlement-events`, `payment.instant-settlement-events` | Command handler for settlements | transactionId | Publishes DoubleEntryTransaction. Synchronous projection update |
| `a1-freeze-handler` | `account.freeze-commands` | Command handler for freeze/unfreeze | accountId | Publishes AccountLifecycleEvent |
| `a1-projection-consumer` | `ledger.events` | Async projection updater | mixed (txId for compounds, accountId for others) | Idempotent. Catch-up and rebuild. Uses SELECT FOR UPDATE for compound events |

```
                        Kafka Topics                    A1 Consumers
                    ┌─────────────────────┐
  A2 ──────────────→│ payment.balance-    │────→ a1-reservation-handler
                    │ reservation         │      (serialized per account)
                    ├─────────────────────┤           │
  A2 ──────────────→│ payment.settlement- │────→ a1-settlement-handler
                    │ events              │           │
                    ├─────────────────────┤           │
  A2 ──────────────→│ payment.instant-    │────→ a1-settlement-handler
                    │ settlement-events   │           │
                    ├─────────────────────┤           │   publishes to
  A5 ──────────────→│ account.freeze-     │────→ a1-freeze-handler ──────→ ledger.events
                    │ commands            │           │
                    └─────────────────────┘           │
                                                      v
                    ┌─────────────────────┐    ┌──────────────────┐
                    │ ledger.events       │───→│ a1-projection-   │
                    │ (source of truth)   │    │ consumer         │
                    └─────────────────────┘    │ (idempotent,     │
                                               │  catch-up,       │
                              REST              │  rebuild)        │
                    ┌─────────────────────┐    └──────┬───────────┘
  A4 ──────────────→│ Query API           │           │
  A6 ──────────────→│ (read from PG)      │←──────────┘ updates
  A5 case ─────────→│                     │         PostgreSQL
                    └─────────────────────┘
```

---

## Appendix: PostgreSQL Schema (Core Tables)

```sql
-- Account balances (CQRS projection, disposable, rebuildable)
CREATE TABLE account_balances (
    account_id        VARCHAR PRIMARY KEY,
    iban              VARCHAR UNIQUE NOT NULL,
    holder_name       VARCHAR,          -- encrypted (crypto-shredding)
    currency          VARCHAR NOT NULL,  -- ISO 4217
    booked_balance    NUMERIC NOT NULL DEFAULT 0,
    available_balance NUMERIC NOT NULL DEFAULT 0,
    status            VARCHAR NOT NULL DEFAULT 'ACTIVE',
    version           BIGINT NOT NULL DEFAULT 0,
    last_event_offset BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMP NOT NULL,
    updated_at        TIMESTAMP NOT NULL
);

-- Transaction history (CQRS projection, disposable, rebuildable)
CREATE TABLE transaction_history (
    id                  BIGSERIAL PRIMARY KEY,
    transaction_id      VARCHAR NOT NULL,
    account_id          VARCHAR NOT NULL,
    entry_type          VARCHAR NOT NULL,  -- DEBIT, CREDIT
    amount              NUMERIC NOT NULL,
    currency            VARCHAR NOT NULL,
    counterparty_id     VARCHAR,
    description         VARCHAR,          -- encrypted (crypto-shredding)
    external_reference  VARCHAR,
    clearing_cycle_id   VARCHAR,
    reconciliation_status VARCHAR DEFAULT 'UNRECONCILED',
    idempotency_key     VARCHAR UNIQUE NOT NULL,
    transaction_date    TIMESTAMP NOT NULL,
    created_at          TIMESTAMP NOT NULL
);
CREATE INDEX idx_txn_account_date ON transaction_history (account_id, transaction_date);
CREATE INDEX idx_txn_external_ref ON transaction_history (external_reference);

-- Balance reservations
CREATE TABLE reservations (
    reservation_id   VARCHAR PRIMARY KEY,
    transaction_id   VARCHAR UNIQUE NOT NULL,
    account_id       VARCHAR NOT NULL,
    amount           NUMERIC NOT NULL,
    currency         VARCHAR NOT NULL,
    status           VARCHAR NOT NULL DEFAULT 'RESERVED',  -- RESERVED, CONFIRMED, RELEASED
    created_at       TIMESTAMP NOT NULL,
    resolved_at      TIMESTAMP
);
CREATE INDEX idx_res_status_created ON reservations (status, created_at)
    WHERE status = 'RESERVED';

-- Per-account encryption keys (for crypto-shredding)
CREATE TABLE account_encryption_keys (
    account_id    VARCHAR PRIMARY KEY,
    key_id        VARCHAR NOT NULL,
    encrypted_key BYTEA,                -- NULL after erasure
    created_at    TIMESTAMP NOT NULL,
    deleted_at    TIMESTAMP             -- set on erasure
);

-- Idempotency tracking for projection consumer
CREATE TABLE processed_event_ids (
    idempotency_key VARCHAR PRIMARY KEY,
    processed_at    TIMESTAMP NOT NULL
);

-- Account balance snapshots (for fast projection rebuild)
CREATE TABLE account_snapshots (
    id                BIGSERIAL PRIMARY KEY,
    account_id        VARCHAR NOT NULL,
    booked_balance    NUMERIC NOT NULL,
    available_balance NUMERIC NOT NULL,
    currencies        VARCHAR NOT NULL,  -- JSON array
    event_offset      BIGINT NOT NULL,
    snapshot_timestamp TIMESTAMP NOT NULL,
    snapshot_hash     VARCHAR NOT NULL   -- SHA-256 for integrity verification
);
CREATE INDEX idx_snap_account ON account_snapshots (account_id, event_offset DESC);
```
