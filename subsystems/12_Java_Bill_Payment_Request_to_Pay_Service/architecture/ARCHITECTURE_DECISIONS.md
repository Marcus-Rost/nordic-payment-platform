# A3 — Bill Payment & Request-to-Pay Service: Architectural Decisions

*Decisions for each challenge identified in ARCHITECTURE_CHALLENGES.md*
*Grounded in the master architecture (payment_processing/architecture/)*
*Date: 2026-04-11*

---

## How to Read This Document

Each section addresses one challenge from ARCHITECTURE_CHALLENGES.md. For each:
- **What's inherited** — decisions already made at the master level that constrain A3
- **What's open** — the genuine design choice A3 must make
- **Decision** — the chosen approach with rationale

Where a decision is fully inherited from the master, we note it and describe A3-specific implementation details.

---

## 1. State Machine Correctness and Enforcement

**Inherited from master:** Phase 1/2/3 ownership model (B.1a). A3 owns Phase 1 completely and receives Phase 3 status updates. The state machine within Phase 1 is A3's to design.

### Decision: Hand-rolled enum-based state machine with explicit transition table + compare-and-swap SQL

The approach has three layers working together:

**Layer 1 — Transition table (single source of truth):**

```java
enum Status { PENDING, ACCEPTED, DECLINED, DEFERRED, EXPIRED, 
              AMENDED, PAYMENT_INITIATED, SETTLED, REJECTED }

enum Trigger { CONSUMER_ACCEPT, CONSUMER_DECLINE, CONSUMER_DEFER, 
               CONSUMER_AMEND, MERCHANT_AMENDMENT_RESPONSE, 
               SCHEDULER_EXPIRE, SCHEDULER_REACTIVATE,
               KAFKA_SETTLED, KAFKA_REJECTED, SYSTEM_VOP_PASSED }
```

A static `Map<Status, Set<Trigger>>` (or `EnumMap`) defines every legal transition. The `RequestLifecycleManager` consults this map before attempting any transition. If the trigger is not in the allowed set for the current status, the operation is rejected with a clear error. This is testable with a simple unit test that exhaustively verifies all combinations.

**Layer 2 — Compare-and-swap SQL:**

```sql
UPDATE payment_request 
SET status = :newStatus, updated_at = :now 
WHERE id = :id AND status = :expectedStatus
```

If affected rows = 0, someone else transitioned first. This handles the race between concurrent actors (consumer accept vs. scheduler expire) without pessimistic locking. The application layer checks the return value and returns an appropriate response (HTTP 409 Conflict, or a no-op if the transition already happened).

**Layer 3 — Database CHECK constraint (safety net):**

```sql
ALTER TABLE payment_request 
ADD CONSTRAINT valid_status CHECK (status IN ('PENDING', 'ACCEPTED', ...));
```

This doesn't enforce transitions (only valid values), but catches any bug that sets an invalid status string.

### Why not Spring Statemachine

Spring Statemachine is a heavyweight framework designed for complex stateful workflows with guards, actions, and hierarchical states. A3's state machine has ~15 transitions — a static map is clearer, lighter, and easier to test. Spring Statemachine adds dependency weight and a learning curve without proportional benefit.

### Why not event sourcing for A3's state

A1 already uses event sourcing for the ledger, which makes sense for immutable financial records. A3's payment requests are CRUD entities with a lifecycle — mutable status with optimistic concurrency is the standard and simpler pattern. Event sourcing would add complexity without a compelling correctness benefit beyond what compare-and-swap provides.

---

## 2. Dual-Write (DB + Kafka)

**Inherited from master:** Fully decided.

- Strategy: Transactional Outbox (B.2a)
- Relay: In-process publish after commit, scheduled relay as fallback (B.2b)

### Decision: Implement master's Transactional Outbox with the following A3-specific details

**Outbox table:**

```sql
CREATE TABLE outbox (
    id            UUID PRIMARY KEY,
    aggregate_id  UUID NOT NULL,           -- payment request ID
    topic         VARCHAR(100) NOT NULL,    -- target Kafka topic
    key           VARCHAR(255),             -- Kafka message key (e.g., debtor IBAN for partitioning)
    payload       BYTEA NOT NULL,           -- serialized Avro message
    created_at    TIMESTAMP NOT NULL,
    published     BOOLEAN DEFAULT FALSE,
    published_at  TIMESTAMP
);
```

**Relay behavior:**

- After the DB transaction commits, the same thread attempts `kafkaTemplate.send()` for each unpublished outbox record from that transaction. On success, mark `published = true`.
- On Kafka failure: no action. The record remains `published = false`.
- Fallback scheduler: every 500ms, query `SELECT * FROM outbox WHERE published = false AND created_at < NOW() - INTERVAL '1 second' ORDER BY created_at LIMIT 100`. Publish and mark. The 1-second delay avoids racing with the in-process relay.
- Retention: delete published outbox records older than 24 hours (they served their purpose).

**Bulk submission note:** When a bulk submission creates thousands of outbox records, the in-process relay publishes them sequentially after the transaction commits. For bulk submissions, this is acceptable — the merchant receives the batch result after all records are persisted, and the outbox relay smooths the Kafka publishing over time. If latency becomes a concern, the in-process relay can be limited to the first N records (e.g., 50), leaving the rest for the scheduled relay.

---

## 3. Concurrency and Race Conditions

**Inherited from master:** Idempotency via hierarchical transactionId (B.3a). Compare-and-swap as the general pattern (implicit in B.1b recovery scan design).

### Decision: Compare-and-swap for state transitions, pessimistic locking for mandates, idempotency keys for request creation

Three distinct concurrency concerns, each with its own mechanism:

### A. State transitions (consumer accept vs. scheduler expire vs. Kafka update)

Compare-and-swap SQL (from Challenge 1, Layer 2). This is the primary mechanism. When the scheduler tries to expire a request that the consumer just accepted, the `UPDATE ... WHERE status = 'PENDING'` returns 0 rows — the scheduler's attempt is a no-op. No lock contention, no blocking.

For the Kafka consumer receiving status updates (SETTLED, REJECTED): the same pattern. `UPDATE ... SET status = 'SETTLED' WHERE id = ? AND status = 'PAYMENT_INITIATED'`. If it returns 0 rows, either the update already happened (idempotent) or the request is in an unexpected state (log a warning, investigate).

### B. Mandate operations (consumer revokes while merchant submits)

Pessimistic locking: `SELECT * FROM recurring_mandate WHERE id = ? FOR UPDATE`. The auto-acceptance check reads the mandate within a transaction that holds the lock. If a consumer is concurrently revoking the mandate, one of them waits.

Rationale for pessimistic locking on mandates:
- Mandates have higher write contention than individual requests (many requests reference the same mandate)
- The cost of an incorrect auto-acceptance (charging a revoked mandate) is higher than the cost of lock contention
- Mandate operations are infrequent — lock contention is rare in practice

### C. Duplicate API calls (mobile app retry, network timeout retry)

Idempotency keys. For request creation: the merchant-provided `idempotency_key` (RTP-F09). A unique constraint on the column; duplicate inserts return the existing request (HTTP 200, not 201). For consumer actions (accept, decline, defer): the request's current status serves as a natural idempotency guard — accepting an already-accepted request is a no-op. No separate idempotency key needed for consumer actions because compare-and-swap handles it.

---

## 4. Webhook Delivery Reliability

**Inherited from master:** Nothing specific — webhooks are A3-specific. The master mentions webhooks only in passing.

### Decision: Durable webhook table + retry scheduler + circuit breaker per merchant + polling fallback API

**Core mechanism (per requirements RTP-W01-W08):**

- `webhook_delivery` table tracks every delivery attempt
- Scheduler retries failed deliveries with exponential backoff (1s, 2s, 4s, 8s, 16s)
- After 5 failures: mark FAILED, raise alert
- HMAC-SHA256 signing with per-merchant secret
- Unique `X-Webhook-ID` per delivery attempt for merchant-side idempotency

**Addition 1 — Circuit breaker per merchant (Resilience4j):**

If a merchant endpoint fails 5 consecutive times, open the circuit for 60 seconds. During the open period, don't attempt delivery — the scheduler skips this merchant's webhooks. After cooldown, attempt one delivery (half-open). On success, close the circuit and resume normal delivery.

Rationale: Without a circuit breaker, a single failing merchant with many pending requests could consume all retry capacity, delaying webhook delivery to healthy merchants. The circuit breaker isolates the blast radius.

Configuration: circuit breaker per `merchant_id`, not per URL (a merchant has one webhook URL). State stored in-memory (lost on restart — the worst case is a few extra retries after restart, which is acceptable).

**Addition 2 — Merchant polling fallback API:**

```
GET /api/v1/merchant/requests/{id}/events
```

Returns the status change history for a request. This gives merchants a recovery path when webhooks fail — they can poll for status changes instead of relying solely on push delivery. This is defense in depth, not a replacement for webhooks.

Low implementation cost (a simple query on the request's status history). High value as a reliability backstop.

**Not a separate deployment unit:** For a portfolio project, the webhook scheduler running in-process is sufficient. Extracting it to a separate deployment unit adds operational complexity without a proportional reliability gain at the expected scale.

---

## 5. Multi-Tenant Data Isolation

**Inherited from master:** Three-zone model (A.1a) places A3 in DMZ. Authenticated-by-default (A.1b) via Starter. The master doesn't prescribe tenant isolation within a service.

### Decision: Repository-level filtering + Spring Security method-level checks. PostgreSQL RLS documented as production target.

**Layer 1 — Repository-level filtering (primary defense):**

Every Spring Data JPA query method includes a tenant filter:

```java
@Query("SELECT r FROM PaymentRequest r WHERE r.merchantId = :merchantId AND r.id = :id")
Optional<PaymentRequest> findByIdForMerchant(UUID id, String merchantId);

@Query("SELECT r FROM PaymentRequest r WHERE r.consumerId = :consumerId AND r.status = :status")
List<PaymentRequest> findByConsumerAndStatus(String consumerId, Status status);
```

No method exists that returns requests across tenants. The authenticated principal (extracted from API key or OAuth2 token by Spring Security) provides the tenant ID.

**Layer 2 — Spring Security method-level authorization (defense in depth):**

```java
@PreAuthorize("@authz.isMerchantOwner(#id, authentication)")
@PutMapping("/api/v1/merchant/requests/{id}/amendment-response")
```

This catches any case where a controller accidentally calls a repository method without the tenant filter. The `authz` bean loads the request, checks ownership, and rejects mismatches with HTTP 403.

### Why not PostgreSQL RLS for the portfolio project

RLS requires setting a session variable (`SET app.merchant_id = '...'`) on every database connection before use. With connection pooling (HikariCP), this means setting the variable on connection checkout and clearing it on return. This adds a PostgreSQL-specific concern that interacts with the connection pool lifecycle. For a portfolio project, application-level filtering + method-level authorization provides sufficient isolation. RLS is the right production target for a real payment system — document it as such, don't implement it now.

---

## 6. API Design — Dual API Surface

**Inherited from master:** URL path versioning (D.2a). RFC 9457 errors (E.1a). Authenticated-by-default Starter (A.1b) — A3 overrides with its own `SecurityFilterChain`.

### Decision: Separate URL prefixes + separate Spring Security filter chains + contract-first OpenAPI

**URL structure:**

```
/api/v1/merchant/requests          (API key auth)
/api/v1/merchant/requests/{id}/qr  (API key auth)
/api/v1/merchant/batches           (API key auth)

/api/v1/consumer/requests          (OAuth2 auth)
/api/v1/consumer/requests/{id}/accept   (OAuth2 auth)
/api/v1/consumer/mandates          (OAuth2 auth)
```

Rationale for separate prefixes: The requirements document uses shared paths (`/api/v1/requests`), but separate prefixes make the security configuration unambiguous. A request to `/api/v1/merchant/...` always uses API key auth. A request to `/api/v1/consumer/...` always uses OAuth2. There is no ambiguity about which filter chain applies. It also makes rate limiting straightforward — different rate limit policies per prefix.

**Two SecurityFilterChain beans:**

```java
@Bean @Order(1)
SecurityFilterChain merchantFilterChain(HttpSecurity http) {
    return http
        .securityMatcher("/api/v1/merchant/**")
        .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .build();
}

@Bean @Order(2)
SecurityFilterChain consumerFilterChain(HttpSecurity http) {
    return http
        .securityMatcher("/api/v1/consumer/**")
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(...))
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .build();
}
```

**Contract-first:** Write the OpenAPI 3.0 spec first (`merchant-api.yaml`, `consumer-api.yaml`). Use springdoc-openapi to validate the implementation against the spec. The spec becomes the contract for Pact tests and external documentation.

**Package structure:** Separate controller packages (`controller.merchant`, `controller.consumer`). Shared service layer underneath — both API surfaces call the same `RequestLifecycleManager`. The separation is at the API boundary, not the business logic.

---

## 7. Bulk Operations and Backpressure

**Inherited from master:** Nothing specific — bulk is A3-specific. The outbox relay (B.2b) provides natural backpressure for Kafka publishing.

### Decision: Synchronous with size limit + streaming NDJSON parse + outbox relay for backpressure

**Approach:**

1. Merchant sends `POST /api/v1/merchant/batches` with NDJSON body (one JSON object per line).
2. A3 parses the body line by line (streaming — never loads the entire body into memory).
3. Each line is validated. Valid requests are collected; invalid lines accumulate errors.
4. All valid requests are persisted in a single database transaction (with outbox records).
5. Response is returned synchronously: `{ batchId, total, valid, invalid, errors: [{line, reason}] }`.
6. The outbox relay publishes payment instructions at its normal pace.

**Size limit:** Maximum 10,000 lines per batch (configurable). Larger batches should be split by the merchant. This bounds the transaction size and response time.

### Why synchronous, not async with polling

For batches up to 10,000 lines, the DB insert + validation takes seconds, not minutes. The merchant gets a complete result immediately — no need for a polling loop. Async adds complexity: a batch status table, a polling endpoint, a background processor. For the expected volume, this overhead isn't justified.

If profiling later shows that synchronous becomes too slow, the outbox pattern already decouples DB persistence from Kafka publishing. The bottleneck would be DB insert time, which can be optimized with batch inserts (`saveAll` with `hibernate.jdbc.batch_size`).

**Rate limiting for bulk:** A batch of 5,000 counts as 1 API request for rate-limiting purposes (it's one HTTP call). But the outbox relay naturally throttles Kafka publishing — A2 doesn't see a burst of 5,000 messages at once. This is the right behavior: the merchant's API rate is limited, and the downstream impact is smoothed by the outbox.

---

## 8. Scheduling and Time-Based Triggers

**Inherited from master:** Recovery scan on startup (B.1b). No specific guidance on ongoing scheduled operations.

### Decision: Spring `@Scheduled` with compare-and-swap SQL. No leader election.

**Two scheduled jobs:**

**Job 1 — Request expiry (every 60 seconds):**

```sql
UPDATE payment_request 
SET status = 'EXPIRED', updated_at = NOW() 
WHERE status = 'PENDING' AND due_date < CURRENT_DATE 
RETURNING id
```

For each expired request, create a webhook delivery record (in the same transaction or immediately after). The `RETURNING id` clause gives the IDs for webhook creation.

**Job 2 — Deferred request reactivation (every 60 seconds):**

```sql
UPDATE payment_request 
SET status = 'PENDING', updated_at = NOW() 
WHERE status = 'DEFERRED' AND defer_date <= CURRENT_DATE 
RETURNING id
```

Same pattern. Reactivated requests appear in the consumer's pending list.

### Multi-instance safety

Compare-and-swap handles it naturally. If two instances run the scheduler concurrently, both execute the same UPDATE. PostgreSQL guarantees that each row is updated exactly once — the first transaction to reach a row locks it, updates it, and commits. The second transaction's WHERE clause no longer matches (status is already EXPIRED/PENDING). No leader election needed. No duplicate processing.

### Granularity

60-second polling is acceptable. Expiry is a daily concept (due_date is a date, not a timestamp). Deferred reactivation is also daily. A 60-second delay in processing is imperceptible.

### Startup recovery

On startup, the same logic runs as part of the B.1b database recovery scan — expire any requests that became overdue while A3 was down, reactivate any deferred requests whose date has passed. This is calling the same repository methods during initialization.

---

## 9. VoP Integration — Synchronous Dependency

**Inherited from master:** VoP call before Kafka publish (Section 7.1, Phase 1). 500ms timeout (Section 5.3). The A3 draft commits to synchronous call with `VOP_TIMEOUT` flag.

### Decision: Synchronous call with timeout (inherited) + circuit breaker (Resilience4j) + pass-through for CLOSE_MATCH

**Circuit breaker configuration:**

```java
@CircuitBreaker(name = "vop", fallbackMethod = "vopFallback")
@TimeLimiter(name = "vop")  // 500ms timeout
public VopResult checkVop(String creditorName, String creditorIban) {
    return vopClient.verify(creditorName, creditorIban);
}

private VopResult vopFallback(String name, String iban, Throwable t) {
    return VopResult.timeout();  // VOP_TIMEOUT flag
}
```

Circuit breaker settings: open after 5 consecutive failures, 30-second cooldown, half-open allows 1 test call. These are conservative — VoP downtime is unlikely to be shorter than 30 seconds, and we don't want to keep hammering a failing service.

### CLOSE_MATCH behavior

When VoP returns CLOSE_MATCH (name is similar but not exact), A3 **passes the result through** in the Kafka message (`vopResult = CLOSE_MATCH` in the PaymentInstruction). A3 does not block the payment.

Rationale:
- The EU VoP regulation allows the payer to proceed after a close-match warning. It is a consumer decision, not an automated gate.
- In the API flow, A3 returns the CLOSE_MATCH result in the accept response, and the consumer-facing application (mobile app) shows a warning: "The name doesn't exactly match. Proceed anyway?" If the consumer confirms, the payment proceeds.
- For mandate auto-acceptance: CLOSE_MATCH on a mandate payment **does not** auto-accept. Route to PENDING for consumer review. This prevents a mandate from automatically paying a beneficiary whose name has changed (possible account takeover indicator).

### NO_MATCH behavior

A3 rejects the accept attempt. The request stays PENDING. The consumer sees "The beneficiary name does not match this account. Please verify the details." No payment instruction is published.

### VoP caching for mandates

Per RTP-RES03, cache the VoP result for mandate payments. Cache key: `(creditorIban, creditorName)`. TTL: 90 days. Invalidation triggers: mandate modification, A6 notification of name change. This avoids calling A6 on every monthly auto-acceptance for the same beneficiary.

---

## 10. Testing Strategy

**Inherited from master:** Four-level testing pyramid (G.1a). Pact for REST + Kafka message contracts. Testcontainers for service integration.

### Decision: Apply the master's pyramid with A3-specific focus areas

**Level 1 — Unit tests (fast, no I/O):**

- State machine transition table: exhaustive test of all (status, trigger) combinations — verify legal transitions succeed and illegal transitions throw.
- Mandate auto-acceptance logic: amount checks, frequency checks, mandate status checks.
- EPC QR code formatting: verify output matches EPC069-12 specification.
- Webhook HMAC signature computation: verify against known test vectors.
- Expiry and reactivation logic (date comparison, status preconditions).

**Level 2 — Service integration tests (Testcontainers: Kafka + PostgreSQL):**

- Accept flow end-to-end: create request → accept → verify outbox record created → verify Kafka message published with correct Avro schema → verify status is PAYMENT_INITIATED.
- Status update consumption: publish SETTLED to `payment.status-updates` → verify request status updated → verify webhook delivery record created.
- Concurrency: two concurrent accept calls on the same request → one succeeds, one gets 409 or no-op.
- Bulk submission: submit 100 requests in NDJSON → verify all persisted → verify outbox records created.
- Outbox relay: create outbox record → verify it appears on Kafka within 2 seconds.
- Expiry scheduler: create request with past due date → trigger scheduler → verify status is EXPIRED.

**Level 3 — Contract tests (Pact):**

- **A3 as producer:** Pact contract with A2 for messages on `payment.instructions` and `payment.instant-instructions`. A2 defines what it expects; A3 verifies it produces conforming messages.
- **A3 as consumer:** Pact contract with A2 for messages on `payment.status-updates`. A3 defines what it expects; A2 verifies it produces conforming messages.
- **A3 REST API contracts:** OpenAPI spec validation. Optionally Pact contracts if external consumers (mobile app) want to define their expectations.

**Level 4 — End-to-end (docker-compose):**

Covered by the master demo script (`run-demo.sh`). A3's contribution: the invoice payment flow (UC-01), the mandate auto-accept flow (UC-06 scenario), and the QR payment flow.

### A3-specific testing additions

- **WireMock for VoP (A6):** Simulate MATCH, CLOSE_MATCH, NO_MATCH, timeout, and 500 responses. Verify A3 handles each correctly.
- **WireMock for merchant webhooks:** Simulate 200 (delivered), 500 (retry), persistent failure (circuit breaker opens), slow response (timeout).
- **Mandate concurrency tests:** Submit a request against a mandate while simultaneously revoking it. Verify exactly one outcome (auto-accept or mandate-revoked, never both).

---

## Decision Summary

| # | Challenge | Decision | Rationale |
|---|-----------|----------|-----------|
| 1 | State machine | Enum transition table + compare-and-swap SQL + DB CHECK constraint | Simple, testable, three layers of defense. No framework needed. |
| 2 | Dual-write | Inherited: Transactional Outbox, in-process relay + scheduled fallback | Master decision B.2a/B.2b. A3 implements outbox table + relay. |
| 3 | Concurrency | Compare-and-swap for transitions, pessimistic lock for mandates, idempotency key for creation | Each mechanism matches its concurrency profile. |
| 4 | Webhooks | Durable table + retry scheduler + circuit breaker per merchant + polling fallback API | Circuit breaker isolates failing merchants. Polling API is defense in depth. |
| 5 | Data isolation | Repository filtering + @PreAuthorize. RLS documented as production target. | Two application layers sufficient for portfolio. RLS for production. |
| 6 | Dual API | Separate URL prefixes (/merchant/, /consumer/) + separate SecurityFilterChains + contract-first OpenAPI | Unambiguous security, clear separation, spec-driven. |
| 7 | Bulk operations | Synchronous with 10K limit + streaming NDJSON parse + outbox for backpressure | Simple, sufficient at expected volume. Outbox smooths Kafka publishing. |
| 8 | Scheduling | Spring @Scheduled (60s) + compare-and-swap SQL. No leader election. | CAS handles multi-instance. Minute-level granularity is fine for daily concepts. |
| 9 | VoP integration | Synchronous + 500ms timeout + circuit breaker + pass-through CLOSE_MATCH (block for mandates) | Circuit breaker prevents cascading failure. CLOSE_MATCH is a consumer decision per EU regulation. |
| 10 | Testing | Master pyramid applied: unit (state machine, HMAC, QR), integration (Testcontainers), contract (Pact), e2e (demo script). WireMock for VoP + webhooks. | Widest integration surface in the system — all four interaction types covered. |

---

## Relationship to Master Decisions

| A3 Decision | Master Decision | Relationship |
|-------------|----------------|-------------|
| #1 State machine | B.1a Phase ownership | A3 designs its internal Phase 1 machine within the master's ownership model |
| #2 Dual-write | B.2a/B.2b Outbox + relay | Directly inherited. A3 implements the master's chosen pattern. |
| #3 Concurrency | B.3a Idempotency | A3 applies the hierarchical ID strategy. Adds CAS and pessimistic locking for A3-specific concerns. |
| #4 Webhooks | (A3-specific) | Extends the outbox principle to HTTP delivery. Not covered by master. |
| #5 Data isolation | A.1a Trust zones | A3 implements tenant isolation within the DMZ zone the master defined. |
| #6 Dual API | D.2a Versioning, E.1a Errors | A3 applies URL path versioning and RFC 9457. Adds dual-audience separation. |
| #7 Bulk operations | (A3-specific) | Unique to A3. Uses the master's outbox for backpressure. |
| #8 Scheduling | B.1b Recovery | Startup recovery scan is inherited. Ongoing scheduling is A3-specific. |
| #9 VoP integration | C.2a Time budget | A3's VoP call is within the Phase 1 budget (≤800ms). Adds circuit breaker. |
| #10 Testing | G.1a Testing pyramid | A3 applies the master's four-level pyramid with A3-specific test targets. |
