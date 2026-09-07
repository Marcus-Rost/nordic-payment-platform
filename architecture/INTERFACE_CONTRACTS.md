# Nordic Payment Processing Infrastructure — Interface Contracts

*Version 1.0*
*Date: 2026-04-12*
*Status: Authoritative — all subproject architectures must conform to this document*

---

## Purpose

This document defines the canonical interface contracts between services in the Nordic Payment Processing Infrastructure. It is the **single source of truth** for:

- Kafka message contracts (topic, key, schema, timeout, error handling)
- Inter-service REST API contracts (path, method, request/response, auth, timeout)
- Shared data schemas (Avro message structures)
- Shared enumerations (canonical values with semantics)
- GDPR erasure protocol (cross-service notification and action)

Each subproject's architecture must comply with these contracts. When a subproject's design conflicts with this document, this document takes precedence.

### Relationship to Other Documents

- `NAVIGATION.md` — lists Kafka topics with producer/consumer assignments. This document adds message keys, schemas, timeouts, and error handling.
- `ARCHITECTURE_DECISIONS.md` — system-level architectural decisions. This document implements those decisions at the interface level.
- Subproject `ARCHITECTURE_DESIGN.md` — must reference and comply with contracts defined here.

---

## 1. Shared Enumerations

These enumerations are defined once here and used by all services. They live in the `payment-domain-model` shared library.

### 1.1 PaymentStatus

Published by A2 on `payment.status-updates`. Consumed by A3, A4.

```
SETTLED              — Payment completed successfully, funds transferred
REJECTED             — Payment rejected (sanctions hit, insufficient balance, timeout, etc.)
HELD                 — Payment held for manual review (batch path only)
RETURN_INITIATED     — Reversal in progress (late sanctions hit, pacs.004 received)
RETURNED             — Reversal completed, funds returned to debtor
```

**All consumers (A3, A4) must handle all five values.** An unrecognized value must be logged and sent to DLQ with alert — it indicates a schema evolution that the consumer hasn't adopted yet.

Note: A2 may publish additional operational statuses (e.g., `SETTLEMENT_FINAL` for batch cycle completion confirmations) that are not part of the core payment lifecycle. These are informational — consumers should log and ignore unrecognized statuses rather than failing.

### 1.2 VoPResult

Returned by A6. Carried in `PaymentInstruction` messages. Used by A3, A4, A5.

```
MATCH                — Name matches account holder (≥0.95 Jaro-Winkler)
CLOSE_MATCH          — Near match (≥0.85, <0.95). actual_name provided to caller.
NO_MATCH             — Name does not match (<0.85). actual_name NEVER provided.
NOT_FOUND            — IBAN does not exist (or account crypto-shredded)
TIMEOUT              — A6 could not complete verification in time
ERROR                — A6 internal error
```

**External API mapping** (A6 responder → external PSPs): `TIMEOUT` → `ERROR`. `ACCOUNT_CLOSED` (internal only) → `NOT_FOUND`. All others map directly.

**EPC Scheme Mapping:** The EPC VoP Scheme (EPC218-23) uses `MTCH`, `CMTCH`, `NMTCH`, `NOAP` (Not Applicable / Not Possible). Our internal vocabulary maps as: MATCH→MTCH, CLOSE_MATCH→CMTCH, NO_MATCH→NMTCH, NOT_FOUND→NOAP, TIMEOUT→NOAP, ERROR→NOAP. The external responder API should use the EPC codes when communicating with scheme participants.

### 1.3 FraudDecision

Published by A5 in `FraudCheckResponse`. Consumed by A2.

```
PASS                 — Below fraud threshold, payment may proceed
BLOCK                — Above fraud threshold, payment should be rejected
REVIEW               — Borderline, payment proceeds but flagged for post-settlement review
TIMEOUT              — A5 could not complete scoring (e.g., graceful shutdown)
```

**A2 must handle all four values.** On `TIMEOUT`: treat as sanctions UNAVAILABLE if sanctions result is not CLEAR → REJECT.

### 1.4 SanctionsResult

Published by A5 as part of `FraudCheckResponse`. Consumed by A2.

```
CLEAR                — No sanctions match
HIT                  — Sanctions match found, payment must be blocked
UNAVAILABLE          — Sanctions screening could not be performed
```

**A2 behavior by sanctions result:**
- `CLEAR` → proceed to fraud scoring evaluation
- `HIT` → REJECT immediately, no further processing
- `UNAVAILABLE` → REJECT immediately (sanctions cannot be skipped)

### 1.5 ReservationResult

Published by A1 on `payment.balance-reservation-response`. Consumed by A2.

```
CONFIRMED            — Balance reserved successfully
REJECTED             — Insufficient balance or account frozen/closed
RELEASED             — Reservation released (A2 requested release, or 30-second timeout)
```

---

## 2. Kafka Message Contracts

### 2.1 General Conventions

- **Serialization:** Avro with Schema Registry (BACKWARD compatibility mode)
- **Message integrity:** HMAC-SHA256 in Kafka headers (per master KM-06). Producer signs, consumer verifies.
- **Correlation:** All payment-related messages carry `transactionId` (UUID) in the payload for end-to-end correlation.
- **Idempotency:** Consumers deduplicate by `transactionId` (or derived operation ID per master B.3a).
- **Kafka broker configuration:** `unclean.leader.election.enable=false` (mandatory). Without this, a broker that is behind on replication can become leader after a failure and lose acknowledged writes, voiding both `acks=all` durability and `exactly_once_v2` guarantees.

**Partition Scaling Procedure:** Partition count (default 12 per topic) can be increased via `kafka-topics --alter --partitions N`. Considerations:
- Stateless consumers (A3, A4, A6): seamless — new partitions are assigned to existing or new consumer instances.
- Stateful consumers (A2, A5 with Kafka Streams): increasing partitions triggers state store rebalancing. Standby replicas reduce restoration time. Plan for ~5 minutes of degraded performance during rebalancing.
- Key-based partitioning: after partition increase, messages with the same key may be assigned to different partitions than before. For `transactionId` (UUID), this is harmless (no ordering dependency across transactions). For `accountId`, existing in-flight reservations complete on old partitions; new reservations route to potentially different partitions. The per-account ordering guarantee is maintained within each partition.
- **Never decrease partition count** — this is not supported by Kafka.

#### 2.1.1 Kafka ACL Specification (FS5.2)

Kafka broker-level ACLs restrict which services may produce to and consume from each topic. A service that attempts to produce to an unauthorized topic receives an `AuthorizationException`. This prevents a compromised DMZ service (A3, A4) from producing to settlement or fraud-response topics.

| Service | Produce ACL (allowed topics) | Consume ACL (allowed topics) |
|---------|-----|------|
| A1 | `ledger.events`, `payment.balance-reservation-response`, `payment.reconciliation-results`, `audit.events`, `audit.checkpoints` | `payment.balance-reservation`, `payment.settlement-events`, `payment.instant-settlement-events`, `account.freeze-commands` |
| A2 | `payment.fraud-check-request`, `payment.instant-fraud-check-request`, `payment.balance-reservation`, `payment.settlement-events`, `payment.instant-settlement-events`, `payment.status-updates`, `payment.investigation`, `audit.events` | `payment.instructions`, `payment.instant-instructions`, `payment.fraud-check-response`, `payment.instant-fraud-check-response`, `payment.balance-reservation-response` |
| A3 | `payment.instructions`, `payment.instant-instructions`, `audit.events` | `payment.status-updates` |
| A4 | `payment.instructions`, `payment.instant-instructions`, `audit.events` | `payment.status-updates` |
| A5 | `payment.fraud-check-response`, `payment.instant-fraud-check-response`, `account.freeze-commands`, `audit.events` | `payment.fraud-check-request`, `payment.instant-fraud-check-request`, `sanctions.list-updates` |
| A6 | `audit.events` | (none in payment flow) |

#### 2.1.2 Request Body Size Limits (FS5.3)

Default request body size limits are enforced to prevent denial-of-service via oversized payloads.

| API Type | Max Request Body | Rationale |
|----------|-----------------|-----------|
| Standard REST APIs (A3 consumer, A4 PISP/AISP, A6 VoP) | 1 MB | Payment requests and VoP queries are small JSON (<1KB typical) |
| A3 Bulk Operations (`/api/v1/merchant/batches`) | 10 MB | NDJSON with up to 10K invoice lines |
| Webhook callbacks (A3 outbound) | N/A (outbound) | --- |

**Enforcement:** `server.tomcat.max-http-form-content-size=1048576` and a custom `OncePerRequestFilter` that rejects requests exceeding the limit with `413 Payload Too Large` before parsing. The Platform Starter should auto-configure this with the 1 MB default, overridable per service via `payment.security.request.max-body-size`.

### 2.2 payment.instructions / payment.instant-instructions

| Attribute | Value |
|-----------|-------|
| Producer | A3, A4 |
| Consumer | A2 |
| Kafka key | `transactionId` (UUID as String) |
| Partitions | 12 (configurable) |
| Retention | 7 days |

**Schema: PaymentInstruction**

```
transactionId:       UUID        — generated by A3/A4, immutable through lifecycle
paymentType:         Enum        — CREDIT_TRANSFER, DIRECT_DEBIT, VRP
schemeType:          Enum        — SCT, SCT_INST, SDD
debtorIban:          String      — payer's IBAN
debtorName:          String      — payer's name (encrypted per F.1a)
creditorIban:        String      — payee's IBAN
creditorName:        String      — payee's name (encrypted per F.1a)
amount:              BigDecimal  — payment amount
currency:            String      — ISO 4217 (e.g., "EUR", "NOK")

**Maximum transaction amount:** EUR 100,000 per the EPC SCT Inst 2025 v1.1 rulebook default. Configurable per scheme via `payment.clearing.instant.max-amount` (application.yml). Amounts exceeding the limit are rejected at A2's InstructionProcessor validation step with reason code `AM02` (Not Allowed Amount).
remittanceInfo:      String      — payment reference (encrypted per F.1a)
vopResult:           VoPResult   — from A6 verification (Section 1.2)
vopSimilarityScore:  Double      — Jaro-Winkler score (nullable if VoP not performed)
vopPayerOverride:    Boolean     — payer proceeded despite NO_MATCH/CLOSE_MATCH warning
originService:       String      — "A3" or "A4"
idempotencyKey:      String      — client-provided dedup key (A3: merchant key, A4: TPP key)
duplicateOverride:   Boolean     — payer confirmed despite semantic duplicate warning
timestamp:           Instant     — creation time (UTC)
```

**Funds Transfer Regulation (2023/1113) Compliance:** The following fields are mandatory per FTR Article 4 and must be preserved through ALL Kafka topic transformations — from `payment.instructions` through `payment.settlement-events` through `payment.status-updates`. No enrichment or transformation step may strip these fields:
- Payer: full name, IBAN, address or national ID or customer ID
- Payee: full name, IBAN
- PSP: BIC or LEI

Per FTR Article 5, for transfers exceeding EUR 1,000, the payer's identity must be verified against CDD records before execution.

### 2.3 payment.balance-reservation / payment.balance-reservation-response

**Request: payment.balance-reservation**

| Attribute | Value |
|-----------|-------|
| Producer | A2 |
| Consumer | A1 |
| **Kafka key** | **`accountId` (debtor account ID)** |
| Partitions | 12 (configurable) |
| Retention | 7 days |

**Key strategy rationale:** `accountId` is the Kafka key because A1 requires per-account serialization — concurrent reservations for the same account must be processed sequentially (A1's architectural guarantee). A2 uses `transactionId` from the **message payload** (not the Kafka key) for state store correlation.

```
transactionId:       UUID        — for correlation with A2 state store
accountId:           String      — debtor account (= Kafka key for A1 serialization)
operation:           Enum        — RESERVE, RELEASE
amount:              BigDecimal  — amount to reserve/release
currency:            String      — ISO 4217
reservationId:       String      — deterministic: SHA-256(transactionId + "balance-reservation")
timestamp:           Instant     — UTC
```

**Response: payment.balance-reservation-response**

| Attribute | Value |
|-----------|-------|
| Producer | A1 |
| Consumer | A2 |
| **Kafka key** | **`transactionId`** |
| Partitions | 12 (configurable) |
| Retention | 7 days |

**Key strategy rationale:** `transactionId` is the Kafka key for the response because A2 needs co-partitioning with its Kafka Streams state store (keyed by `transactionId`).

```
transactionId:       UUID        — correlation with A2 state store (= Kafka key)
accountId:           String      — debtor account
result:              ReservationResult  — CONFIRMED, REJECTED, RELEASED (Section 1.5)
availableBalance:    BigDecimal  — current available balance after operation (nullable on REJECTED)
reason:              String      — rejection reason (nullable, e.g., "INSUFFICIENT_BALANCE", "ACCOUNT_FROZEN")
reservationId:       String      — echo of request's reservationId
timestamp:           Instant     — UTC
```

**Timeout contract:**
- A2 budgets **200ms** round-trip for reservation (request → A1 processing → response delivery)
- A1 targets **<100ms** processing time
- If A2 does not receive a response within 200ms: state machine transitions to timeout handling
- A1's safety net: unreleased reservations auto-expire after **30 seconds** (publishes `RELEASED` event)

### 2.4 payment.fraud-check-request / payment.instant-fraud-check-request

| Attribute | Value |
|-----------|-------|
| Producer | A2 |
| Consumer | A5 |
| Kafka key | `transactionId` |
| Partitions | 12 (configurable) |
| Retention | 7 days |

```
transactionId:       UUID
debtorAccountId:     String
creditorAccountId:   String
creditorName:        String      — transient PII, used for sanctions only, never persisted by A5
amount:              BigDecimal
currency:            String
paymentMethod:       String      — "INSTANT", "BATCH"
vopResult:           VoPResult   — from A6 (Section 1.2)
vopPayerOverride:    Boolean     — payer proceeded despite VoP warning
timestamp:           Instant
```

### 2.5 payment.fraud-check-response / payment.instant-fraud-check-response

| Attribute | Value |
|-----------|-------|
| Producer | A5 |
| Consumer | A2 |
| Kafka key | `transactionId` |
| Partitions | 12 (configurable) |
| Retention | 7 days |

```
transactionId:       UUID
sanctionsResult:     SanctionsResult    — CLEAR, HIT, UNAVAILABLE (Section 1.4)
sanctionsMatchedEntry: String           — matched list entry name (nullable, only on HIT)
sanctionsListVersion: String            — version ID of sanctions list used
decision:            FraudDecision      — PASS, BLOCK, REVIEW, TIMEOUT (Section 1.3)
fraudScore:          Double             — composite fraud score [0.0, 1.0]
velocityScore:       Double             — velocity rule contribution
velocityTriggeredRules: List<String>    — rule IDs that fired
anomalyScore:        Double             — anomaly detection contribution
mlScore:             Double             — ML model contribution (nullable if ML disabled)
mlModelVersion:      String             — ML model version (nullable)
vopResult:           VoPResult          — echo of input VoP result
vopPayerOverride:    Boolean            — echo of input override flag
scoringLatencyMs:    Long               — total scoring time
timestamp:           Instant
```

**Timeout contract:**
- A2 budgets **800ms** round-trip (500ms target + 300ms margin)
- A5 targets **<500ms** total processing for instant path
- A5 alert thresholds: p95 > 400ms WARN, > 480ms CRITICAL
- If A2 does not receive a response within 800ms (punctuator fires):
  - REJECT with reason `SANCTIONS_UNAVAILABLE` (sanctions cannot be skipped)
- If A5 completes sanctions but fraud scoring is slow:
  - A5 publishes partial response: `{sanctionsResult: CLEAR, decision: TIMEOUT}` 
  - A2 treats `decision: TIMEOUT` with `sanctionsResult: CLEAR` as fraud auto-pass (proceed, flag for post-settlement review)
  - A2 treats `decision: TIMEOUT` with `sanctionsResult` != CLEAR as REJECT

### 2.6 payment.settlement-events / payment.instant-settlement-events

| Attribute | Value |
|-----------|-------|
| Producer | A2 |
| Consumer | A1 |
| Kafka key | `transactionId` |
| Partitions | 12 (configurable) |
| Retention | 7 days |

```
transactionId:       UUID
operation:           Enum        — SETTLE, RETURN
debtorAccountId:     String
creditorAccountId:   String
amount:              BigDecimal
currency:            String
clearingCycleId:     String      — batch only (nullable for instant)
settlementEventId:   String      — deterministic: SHA-256(transactionId + "settlement")
timestamp:           Instant
```

A1 processes settlement events as atomic compound `DoubleEntryTransaction` events (debit + credit in one Kafka message).

### 2.7 payment.status-updates

| Attribute | Value |
|-----------|-------|
| Producer | A2 |
| Consumer | A3, A4 |
| Kafka key | `transactionId` |
| Partitions | 12 (configurable) |
| Retention | 7 days |

```
transactionId:       UUID
status:              PaymentStatus   — SETTLED, REJECTED, HELD, RETURN_INITIATED, RETURNED (Section 1.1)
reason:              String          — rejection/return reason (nullable)
settlementTimestamp:  Instant         — when settlement completed (nullable for non-SETTLED)
timestamp:           Instant         — event timestamp
```

**Consumer requirements:** A3 and A4 must handle all five `PaymentStatus` values (Section 1.1).

### 2.8 account.freeze-commands

| Attribute | Value |
|-----------|-------|
| Producer | A5 (via Transactional Outbox in Case Management) |
| Consumer | A1 |
| Kafka key | `accountId` |
| Partitions | 12 |
| Retention | 7 days |

```
accountId:           String
operation:           Enum        — FREEZE, UNFREEZE
reason:              String      — e.g., "FRAUD_CASE_123", "SANCTIONS_HIT"
alertId:             UUID        — reference to A5 fraud case
operatorId:          String      — analyst who approved the freeze (nullable for auto-freeze)
timestamp:           Instant
```

### 2.9 audit.events

| Attribute | Value |
|-----------|-------|
| Producer | All services (via Platform Starter) |
| Consumer | Audit consumer (separate service or log aggregation) |
| Kafka key | `correlationId` |
| Partitions | 6 |
| Retention | Infinite (5-year regulatory minimum) |

Schema defined by Platform Starter. Includes hash chain fields (`previousHash`, `instanceId`, `sequenceNumber`).

### 2.10 Additional Topics

| Topic | Producer | Consumer | Kafka Key | Purpose |
|-------|----------|----------|-----------|---------|
| `ledger.events` | A1 | A1 (projections) | `transactionId` | Event sourcing source of truth. Infinite retention. |
| `sanctions.list-updates` | External | A5 | — | Trigger sanctions index rebuild |
| `payment.reconciliation-results` | A1 | A2, monitoring | `reconciliationId` | Daily reconciliation results |
| `audit.checkpoints` | A1 | Audit verification | `instanceId` | Daily signed audit hash chain checkpoints. 5-year retention. |
| `payment.investigation` | A2 | Operator dashboard | `transactionId` | Stuck payment alerts for operator review |

---

## 3. Inter-Service REST Contracts

### 3.1 Path Convention

**Internal service-to-service REST calls use the `/api/v1/` prefix** — the same prefix as external APIs but protected by mTLS and Kubernetes NetworkPolicy. There is no separate `/internal/` prefix.

Rationale: A1 exposes a single set of REST endpoints under `/api/v1/`. Creating a parallel `/internal/` hierarchy would duplicate endpoint definitions. Access control is handled at the network level (NetworkPolicy), not by URL prefix.

**Implication for A6:** A6's design references `/internal/vop/verify` for the requester endpoint. Per this contract, the canonical path is `/api/v1/vop/verify`. A6 serves both internal callers (A3/A4) and external callers (PSPs) on `/api/v1/vop/*` — differentiated by authentication mechanism (mTLS service cert for internal, mTLS client cert or API key for external), not by URL path.

**Note:** This is a design simplification. An alternative (separate `/internal/` prefix) is valid but adds complexity. The key requirement is that both sides agree — this document establishes the agreement.

### 3.2 A6 → A1: Account Holder Name Lookup

| Attribute | Value |
|-----------|-------|
| Caller | A6 |
| Server | A1 |
| Method | `GET` |
| Path | `/api/v1/accounts/{accountId}/holder-name` |
| Path parameter | `accountId` — the IBAN (A1 uses IBAN as account identifier) |
| Auth | mTLS (service certificate) |
| Caller timeout | 200ms |
| Server target | <100ms (p95) |

**Response (200 OK):**
```json
{
  "accountId": "NO9386011117947",
  "holderName": "Ola Nordmann",
  "holderNames": ["Ola Nordmann"],
  "lastUpdated": "2026-04-01T10:00:00Z"
}
```

**Response (404 Not Found):** IBAN does not exist.

**Response (410 Gone):** Account crypto-shredded (PII irrecoverable). A6 maps this to `ACCOUNT_CLOSED` internally, `NOT_FOUND` externally.

### 3.3 A3/A4 → A6: VoP Verification

| Attribute | Value |
|-----------|-------|
| Caller | A3, A4 |
| Server | A6 |
| Method | `POST` |
| Path | `/api/v1/vop/verify` |
| Auth | mTLS (service certificate) |
| Caller timeout | **500ms** (both A3 and A4) |
| Server target | <500ms end-to-end, <100ms responder p95 |

**Request:**
```json
{
  "beneficiary_iban": "NO9386011117947",
  "stated_name": "Ola Nordmann",
  "request_id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response (200 OK):**
```json
{
  "request_id": "550e8400-e29b-41d4-a716-446655440000",
  "result": "CLOSE_MATCH",
  "actual_name": "Ola Nordmann AS",
  "similarity_score": 0.95,
  "response_time_ms": 12
}
```

`actual_name` is included only for `CLOSE_MATCH` (and optionally `MATCH`). **Never** included for `NO_MATCH`, `NOT_FOUND`, `TIMEOUT`, or `ERROR`.

### 3.4 A4 → A1: Account and Balance Queries (AISP)

| Attribute | Value |
|-----------|-------|
| Caller | A4 |
| Server | A1 |
| Method | `GET` |
| Paths | `/api/v1/accounts/{accountId}`, `/api/v1/accounts/{accountId}/balances`, `/api/v1/accounts/{accountId}/transactions` |
| Auth | mTLS (service certificate) + OAuth2 scope propagation (`accounts:read`) |
| Caller timeout | 2000ms |
| Server target | <100ms (p95) |

Response formats follow BerlinGroup NextGenPSD2 resource representations.

### 3.5 External PSP → A6: VoP Responder

| Attribute | Value |
|-----------|-------|
| Caller | External PSPs |
| Server | A6 |
| Method | `POST` |
| Path | `/api/v1/vop/check` |
| Auth | mTLS (PSP client certificate) or API key |
| Rate limit | 100 queries/minute per PSP (configurable) |
| Server target | <100ms (p95) |

**Request:**
```json
{
  "iban": "NO9386011117947",
  "name": "Ola Nordmann"
}
```

**Response (200 OK):**
```json
{
  "result": "MATCH"
}
```

Response uses external VoP vocabulary only: `MATCH`, `CLOSE_MATCH`, `NO_MATCH`, `NOT_FOUND`, `ERROR`. `name_suggestion` included only for `CLOSE_MATCH`.

---

## 4. GDPR Erasure Protocol

When an account holder exercises the right to erasure and the account is closed, A1 initiates a cross-service erasure cascade.

### 4.1 Trigger

A1 crypto-shreds per-account encryption keys, making PII in `ledger.events` irrecoverable. A1 then publishes an `AccountErasedEvent` to `ledger.events`:

```
eventType:           "ACCOUNT_ERASED"
accountId:           String      — the erased account's IBAN
erasureTimestamp:    Instant
```

### 4.2 Required Actions Per Service

Each service that persists data referencing the erased account must take action:

| Service | Data Affected | Required Action | Legal Basis for Retention |
|---------|--------------|-----------------|---------------------------|
| **A1** | `ledger.events` (PII fields) | Crypto-shred: delete per-account encryption key. PII becomes `[REDACTED]` on replay. | AML: 5-year retention of anonymized records |
| **A2** | `clearing_records` (IBANs) | Anonymize: IBAN → hash in clearing records older than AML retention period. For records within retention period: retain with legal basis (AML 5th Directive, Article 40). | AML: 5-year retention of payment records |
| **A3** | `payment_request` (IBANs, consumer IDs) | Anonymize: IBAN → hash, consumer_id → hash. Revoke active mandates for erased account. | AML: 5-year retention of payment records |
| **A4** | `consents`, `payments` (account holder IDs, IBANs) | Revoke active consents for erased account. Anonymize: account_holder_id → hash, IBANs → hash in payment records. | AML: 5-year retention; PSD2: consent records retained for dispute resolution |
| **A5** | No PII stored | No action required. Fraud cases reference account IDs (not PII). | N/A — A5 stores no PII by design |
| **A6** | `vop_query_log` (IBANs, names) | Anonymize: IBAN → hash, stated_name → `[REDACTED]`, actual_name → `[REDACTED]`. | AML: 5-year retention of anonymized audit records |

### 4.3 Notification Mechanism

**Portfolio implementation:** Services poll for erasure events by periodically checking a shared erasure table or consuming `ledger.events` filtered by `eventType = ACCOUNT_ERASED`.

**Production upgrade:** Dedicated `account.erasure-notifications` topic consumed by A2, A3, A4, A6. A5 does not need to consume (no PII stored).

### 4.4 Timing

Erasure is not real-time. Services should process erasure within **24 hours** of the A1 event. This is acceptable because:
- The account is already closed (no new transactions)
- GDPR does not mandate instantaneous erasure — "without undue delay" (Article 17) is interpreted as within 30 days, typically implemented as within days
- AML retention obligations mean most data is retained in anonymized form regardless

---

## 5. Platform Starter Contract

All services in the system must depend on the Platform Starter (`payment-security-starter`). The Starter auto-configures:

| Feature | What the Starter Provides | Service Responsibility |
|---------|--------------------------|----------------------|
| Security headers | CSP, HSTS, X-Frame-Options, etc. | None — auto-configured |
| Deny-all default | `anyRequest().authenticated()` | Declare public endpoints explicitly |
| RFC 9457 error handling | `PaymentExceptionHandler` (`@ControllerAdvice`) | Throw exceptions from shared hierarchy |
| Audit logging | `@Audited` annotation + `AuditLogger` API | Annotate auditable methods; use API for programmatic events |
| Audit hash chain | Per-instance SHA-256 chain with crash recovery | None — auto-configured |
| Kafka HMAC | Producer interceptor (sign) + consumer interceptor (verify) | None — auto-configured via `payment-security-starter-kafka` |
| Kafka error handler | Three-category: TRANSIENT → retry, POISONED → DLQ, FATAL → alert | Configure exception classifier per service |
| Micrometer Tracing | W3C Trace Context propagation across REST + Kafka | None — auto-configured |
| Correlation ID | `X-Correlation-ID` header propagation | None — auto-configured |
| Health/readiness/startup probes | `/actuator/health`, `/actuator/readiness` | None — auto-configured |
| Graceful shutdown | Connection drain, Kafka offset commit | None — auto-configured |
| Structured logging | JSON format with correlation ID, redaction | Annotate sensitive fields with `@Redacted` |

**Each subproject's ARCHITECTURE_DESIGN document should include a section listing which Starter features it uses and any overrides it applies.**

---

## 6. Summary: Interface Inventory

### Kafka Interfaces (16 topics)

| # | Topic | Producer | Consumer | Key | Pattern |
|---|-------|----------|----------|-----|---------|
| 1 | `payment.instructions` | A3, A4 | A2 | transactionId | Fire-and-forget |
| 2 | `payment.instant-instructions` | A3, A4 | A2 | transactionId | Fire-and-forget |
| 3 | `payment.balance-reservation` | A2 | A1 | **accountId** | Request (of req-resp) |
| 4 | `payment.balance-reservation-response` | A1 | A2 | transactionId | Response (of req-resp) |
| 5 | `payment.fraud-check-request` | A2 | A5 | transactionId | Request (of req-resp) |
| 6 | `payment.instant-fraud-check-request` | A2 | A5 | transactionId | Request (of req-resp) |
| 7 | `payment.fraud-check-response` | A5 | A2 | transactionId | Response (of req-resp) |
| 8 | `payment.instant-fraud-check-response` | A5 | A2 | transactionId | Response (of req-resp) |
| 9 | `payment.settlement-events` | A2 | A1 | transactionId | Fire-and-forget |
| 10 | `payment.instant-settlement-events` | A2 | A1 | transactionId | Fire-and-forget |
| 11 | `payment.status-updates` | A2 | A3, A4 | transactionId | Fire-and-forget |
| 12 | `account.freeze-commands` | A5 | A1 | accountId | Fire-and-forget |
| 13 | `ledger.events` | A1 | A1 | transactionId | Event sourcing (infinite retention) |
| 14 | `audit.events` | All | Audit consumer | correlationId | Audit trail (infinite retention) |
| 15 | `audit.checkpoints` | A1 | Audit verification | instanceId | Tamper evidence |
| 16 | `payment.investigation` | A2 | Operator dashboard | transactionId | Operational |
| 17 | `sanctions.list-updates` | External | A5 | — | External feed |
| 18 | `payment.reconciliation-results` | A1 | A2, monitoring | reconciliationId | Operational |

### REST Interfaces (4 inter-service contracts)

| # | Caller | Server | Path | Method | Timeout |
|---|--------|--------|------|--------|---------|
| 1 | A3, A4 | A6 | `/api/v1/vop/verify` | POST | 500ms |
| 2 | A6 | A1 | `/api/v1/accounts/{accountId}/holder-name` | GET | 200ms |
| 3 | A4 | A1 | `/api/v1/accounts/{accountId}/balances` | GET | 2000ms |
| 4 | A4 | A1 | `/api/v1/accounts/{accountId}/transactions` | GET | 2000ms |

### External API Interfaces (3)

| # | Clients | Server | Path | Auth |
|---|---------|--------|------|------|
| 1 | External PSPs | A6 | `/api/v1/vop/check` | mTLS or API key |
| 2 | TPPs | A4 | `/api/v1/*` (BerlinGroup NextGenPSD2) | OAuth2 + mTLS |
| 3 | Merchants | A3 | `/api/v1/merchant/*` | API key |
