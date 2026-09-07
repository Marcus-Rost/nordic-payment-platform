# A3 — Bill Payment & Request-to-Pay Service: Architectural Challenges

*Brainstorming document — identifies challenges and solution options specific to A3*
*Not binding decisions. Input for the architecture discussion.*
*Date: 2026-04-11*

---

## How to Read This Document

Each challenge describes a problem that A3's architecture must resolve. For each challenge we list:
- **The problem** and why it matters specifically for A3
- **Typical solutions** applied in industry for this class of problem
- **Key question** — the open design decision for A3

Challenges are ordered by criticality (high first). Where the master architecture (payment_processing/architecture/) already commits to a solution, we note it — but we re-examine whether the commitment fits A3's specifics.

---

## 1. State Machine Correctness and Enforcement

**Criticality: High**

**Challenge:** A3 owns the richest lifecycle of any subsystem: CREATED -> PENDING -> ACCEPTED -> PAYMENT_INITIATED -> SETTLED / REJECTED / DECLINED / DEFERRED / EXPIRED / AMENDED. Transitions come from multiple actors (consumer API, merchant API, Kafka consumer, scheduler). Invalid transitions (e.g., accepting an already-declined request) must be impossible — not just "unlikely."

**Why it matters for A3:** Other subsystems have simpler state models. A1 has reservation states (3 states). A2 has implicit processing states. A3 has 9+ states with 4 distinct trigger sources (consumer, merchant, Kafka, scheduler). The combinatorial space of "who can trigger what transition from which state" is large enough that ad-hoc guard clauses will miss edge cases.

**Typical solutions:**

| Approach | Description | Tradeoffs |
|----------|-------------|-----------|
| **Explicit state machine with guarded transitions** | A state machine library (Spring Statemachine, or simpler hand-rolled enum-based) where each transition has a precondition. The service rejects any transition that violates the machine. | Clear, auditable, testable. Spring Statemachine adds dependency weight; a hand-rolled enum approach is lighter but less formal. |
| **Optimistic locking on the entity** | A version column prevents two concurrent transitions from both succeeding (e.g., consumer accepts while scheduler expires). | Handles concurrency but does not enforce which transitions are valid — only that they are serialized. Must be combined with application-level guards. |
| **Database-level CHECK constraints** | PostgreSQL CHECK constraint or trigger enforces legal status values. Safety net behind application logic. | Catches bugs that slip through the application layer. Cannot express transition rules (only valid values), so limited on its own. |
| **Compare-and-swap at SQL level** | `UPDATE ... SET status = 'ACCEPTED' WHERE id = ? AND status = 'PENDING'`. Transition only succeeds if precondition holds. | Simple, effective, widely used. Encodes transition rules in SQL, which can drift from application logic if not carefully maintained. |
| **Event-sourced state** | Instead of mutable status, accumulate events (RequestCreated, RequestAccepted, ...). Current state is derived. Illegal transitions rejected by the event handler. | Strongest correctness guarantee. Heavier approach — may be overkill for A3 given that A1 already uses event sourcing for the ledger. |

**Key question:** How much enforcement is in the application layer vs. the database layer? Is a simple enum + guard clauses sufficient, or do we need a formal state machine? Should we combine compare-and-swap SQL with application-level guards for defense in depth?

---

## 2. The Dual-Write Problem (Database + Kafka)

**Criticality: High**

**Challenge:** When a consumer accepts a request, A3 must atomically update the database AND publish a Kafka message. These are two separate systems — no distributed transaction spans them. If the database write succeeds but the Kafka publish fails (or vice versa), the system is in an inconsistent state.

**Why it matters for A3:** A3 is the entry point to the payment flow. A lost Kafka message after a consumer accepts means the consumer believes their payment is in progress, the merchant may receive a webhook saying "accepted," but no payment instruction ever reaches A2. The payment silently disappears.

**Master architecture context:** Challenge B.2 in the master ARCHITECTURE_CHALLENGES.md. The master ARCHITECTURE_DECISIONS.md recommends the Transactional Outbox pattern. The A3 draft (architecture_draft.md, Section 2) commits to this. However, the relay mechanism is not fully decided at the master level.

**Typical solutions:**

| Approach | Description | Tradeoffs |
|----------|-------------|-----------|
| **Transactional Outbox with polling relay** | Write Kafka message as an outbox row in the same DB transaction as the status update. A scheduled job polls the outbox table and publishes to Kafka. | Simple, reliable. Polling interval introduces latency (configurable: 100ms-1s typical). |
| **Transactional Outbox with CDC (Debezium)** | Same outbox table, but a Change Data Capture system tails the PostgreSQL WAL and publishes to Kafka in near-real-time. | Lower latency than polling. Adds Debezium as infrastructure dependency. More complex to operate. |
| **Transactional Outbox with in-process relay** | Same outbox table, but after the transaction commits, the same thread immediately attempts to publish to Kafka. Falls back to polling on failure. | Lowest latency in happy path. Couples the relay to the application process — if the process crashes after commit but before publish, the polling fallback catches it. |
| **Kafka-first (log as source of truth)** | Write to Kafka first, derive DB state from the consumed event. | Strong consistency. Read-your-own-writes problem: consumer accepts, immediately queries status, but the Kafka consumer hasn't processed yet. Poor UX for A3's synchronous API. |
| **Listen-to-yourself pattern** | Publish to Kafka, consume your own topic to update DB. | Clean separation. Adds latency to API responses. Same read-your-own-writes problem. |

**Key question:** Which relay mechanism for the outbox? The draft mentions in-process relay. Is the added coupling acceptable, or should we use polling (simpler, slightly higher latency) as the primary mechanism with in-process relay as an optimization?

---

## 3. Concurrency and Race Conditions

**Criticality: High**

**Challenge:** Multiple actors can act on the same entity concurrently:
- Consumer accepts while the scheduler tries to expire the same request
- Two concurrent accept calls for the same request (mobile app retry / double-tap)
- Merchant submits a mandate-based request while the consumer simultaneously revokes the mandate
- Kafka status update (SETTLED) arrives while the consumer is viewing the request

**Why it matters for A3:** A3 has the highest concurrency surface of any subsystem because it serves both merchants and consumers (two external actor types) while also processing Kafka events and running scheduled jobs — four concurrent mutation sources on the same entities.

**Typical solutions:**

| Approach | Description | Tradeoffs |
|----------|-------------|-----------|
| **Optimistic locking** | `@Version` column. First writer wins, second gets a conflict error (HTTP 409). | Good for low-contention scenarios. Requires the client to handle retries on conflict. |
| **Pessimistic locking** | `SELECT FOR UPDATE` on the row. Serialize access. | Guarantees consistency. Higher contention cost. The draft uses this for mandates. |
| **Compare-and-swap at SQL level** | `UPDATE ... WHERE status = 'PENDING'`. Succeeds only if precondition holds. Returns affected row count — 0 means someone else got there first. | Simple, effective. Combines state machine enforcement with concurrency control in one statement. |
| **Idempotency tokens** | Consumer's accept call carries an idempotency key; a duplicate accept is a no-op returning the existing result, not a conflict. | Eliminates double-tap problems. Does not address cross-actor races (accept vs. expire). |

**Key question:** Which combination? Compare-and-swap for state transitions (handles cross-actor races naturally), optimistic locking as a general safety net, pessimistic locking only for mandate operations (higher contention risk due to auto-acceptance checks)?

---

## 4. Webhook Delivery Reliability

**Criticality: High**

**Challenge:** Outbound HTTP calls to merchant endpoints are inherently unreliable. Merchants may be down, slow, behind rate limiters, or misconfigured. Webhooks must not be lost — they are the merchant's only mechanism to learn about status changes.

**Why it matters for A3:** Unlike internal Kafka communication (where consumers can replay from offset), webhooks are fire-and-forget HTTP to external systems. If a webhook is lost, the merchant has no way to know a payment was settled unless they poll (which is not a required API). This makes webhook reliability a first-class architectural concern, not an afterthought.

**Typical solutions:**

| Approach | Description | Tradeoffs |
|----------|-------------|-----------|
| **Durable webhook table with retry scheduler** | Persist every delivery attempt in `webhook_delivery` table. A scheduler retries failed deliveries with exponential backoff (1s, 2s, 4s, 8s, 16s). After max retries, mark FAILED and alert. | Reliable, simple. Survives crashes (state is in DB). The requirements specify this (RTP-W01-W08). |
| **Circuit breaker per merchant endpoint** | If a merchant endpoint fails consistently (e.g., 5 consecutive failures), open the circuit — stop trying for a cooldown period. Prevents a single failing merchant from consuming all retry resources. | Protects system resources. Risk: a merchant in an extended outage accumulates undelivered webhooks that all fire on circuit close (thundering herd). |
| **Outbox pattern for webhooks** | Webhook delivery records go through the same outbox mechanism as Kafka messages. The status change and the webhook record are created in the same DB transaction. | Consistent with the Kafka outbox approach. Prevents "status changed but webhook record lost." |
| **Webhook delivery as a separate worker / deployment unit** | Decouple webhook sending from the core R2P service. A dedicated worker reads the webhook_delivery table and sends. | Scales independently. Isolates webhook latency/failures from the core API. Adds operational complexity (another deployable). |
| **Merchant polling API as fallback** | Provide GET `/api/v1/requests/{id}/events` so merchants can poll for missed webhooks. | Defense in depth. Does not replace push webhooks but provides a recovery path when webhooks fail. |

**Key question:** Is the scheduler-based retry sufficient, or do we need a circuit breaker? Should webhook delivery be in-process or a separate worker? Should we offer a polling fallback API for merchants?

---

## 5. Multi-Tenant Data Isolation

**Criticality: High**

**Challenge:** Multiple merchants share the same database. A merchant must only see their own requests. A consumer must only see requests targeting their accounts. An authorization leak (merchant A sees merchant B's data, or a consumer sees another consumer's requests) is a critical security vulnerability in a payment system.

**Why it matters for A3:** A3 is the only subsystem with two distinct external-facing API surfaces (merchant + consumer) querying the same data store. Other subsystems either have a single external API (A4) or no external API at all (A1, A2, A5 internal only). The dual-audience makes data isolation more nuanced — a merchant can see request status but not debtor details; a consumer can see their own requests but not other consumers'.

**Typical solutions:**

| Approach | Description | Tradeoffs |
|----------|-------------|-----------|
| **Repository-level filtering** | Every query includes `WHERE merchant_id = :authenticatedMerchantId` (merchant API) or `WHERE consumer_id = :authenticatedConsumerId` (consumer API). Enforced in the repository/DAO layer. | Simple, testable. Risk: a developer forgets the filter on a new query. |
| **Spring Security method-level authorization** | `@PreAuthorize("@authz.isOwner(#id)")` on controller methods. Verifies the authenticated principal owns the resource. | Defense in depth. Catches missing repository filters. Adds annotation overhead. |
| **PostgreSQL Row-Level Security (RLS)** | Database-enforced policies: `CREATE POLICY merchant_isolation ON payment_request USING (merchant_id = current_setting('app.merchant_id'))`. Set the session variable on each connection. | Strongest isolation guarantee — even raw SQL cannot bypass it. Adds PostgreSQL-specific complexity. Connection pool interaction needs care (must reset session variable). |
| **Separate schemas per merchant** | Physical isolation. Each merchant gets their own schema or database. | Maximum isolation. Operational nightmare at scale (thousands of merchants). Overkill for A3. |

**Key question:** Is application-level filtering (repository + Spring Security) sufficient, or should we add PostgreSQL Row-Level Security as a defense-in-depth layer? What is the cost/benefit for a portfolio project?

---

## 6. API Design: Dual API Surface

**Criticality: Medium**

**Challenge:** A3 exposes two distinct API surfaces with different authentication, different consumers, and different evolution rates:
- **Merchant API** (API key in `X-API-Key` header): create requests, submit batches, get QR codes, respond to amendments
- **Consumer API** (OAuth2 bearer token): list pending requests, accept/decline/defer/amend, manage mandates

These must be clearly separated in code, documentation, security configuration, and rate limiting.

**Typical solutions:**

| Approach | Description | Tradeoffs |
|----------|-------------|-----------|
| **Separate URL prefixes** | `/api/v1/merchant/...` vs. `/api/v1/consumer/...` | Clear separation. Easy to apply different security filter chains per prefix. Explicit in the URL which audience is targeted. |
| **Shared URL paths, different auth** | `/api/v1/requests` serves both audiences. Spring Security determines which filter chain applies based on the presence of API key vs. Bearer token. | Simpler URL structure. But ambiguous — a request to `/api/v1/requests` could be either merchant or consumer context. Harder to reason about. |
| **Separate Spring Security filter chains** | Two `SecurityFilterChain` beans, each with its own request matcher, authentication provider, and authorization rules. | Standard Spring Security pattern. Clean separation regardless of URL scheme. |
| **Contract-first (OpenAPI spec first)** | Write the OpenAPI spec before the code. Use springdoc-openapi to generate the spec or validate against it. The spec is the contract. | Prevents accidental API changes. Enables code generation for client SDKs. Adds a spec-maintenance step. |

**Key question:** Separate URL prefixes or shared paths with auth-based routing? How strictly do we separate the two API surfaces in the codebase (separate controllers, separate packages)?

---

## 7. Bulk Operations and Backpressure

**Criticality: Medium**

**Challenge:** The bulk invoice endpoint (RTP-F12) accepts thousands of requests in a single HTTP call (NDJSON or CSV). This must not overwhelm the database, Kafka, or downstream services. It must also handle partial failures gracefully (some lines valid, others not).

**Why it matters for A3:** This is a distinctly A3 concern — no other subsystem has a bulk external-facing endpoint. The bulk path interacts with the outbox relay (thousands of outbox records created at once) and with rate limiting (does a 5,000-line batch count as 1 request or 5,000?).

**Typical solutions:**

| Approach | Description | Tradeoffs |
|----------|-------------|-----------|
| **Synchronous batch with size limit** | Accept the batch, validate all lines, persist all valid requests in one DB transaction, return full per-line result. Cap batch size (e.g., 10,000 lines). | Simple. Client gets full result immediately. Large transactions can be slow and hold locks. |
| **Asynchronous batch with polling** | Accept the batch, validate syntax, return batch ID immediately (HTTP 202). Process in background. Client polls `GET /api/v1/batches/{id}` for status. | Responsive API. Decouples acceptance from processing. More complex client integration (must poll). |
| **Streaming parse (NDJSON)** | Parse line by line without loading entire body into memory. Persist in chunks (e.g., 100 at a time). | Memory-efficient. Enables processing very large batches. Partial commit semantics need design. |
| **Rate-limited outbox relay** | Even if 5,000 outbox records are created at once, the relay publishes at a controlled rate. Prevents a burst of Kafka messages that could overwhelm A2. | Natural backpressure. Adds latency to the tail of a batch. |
| **Separate batch processing pipeline** | Batch submissions go to a dedicated table/queue, processed by a dedicated worker (possibly Spring Batch). Core R2P path is unaffected. | Full isolation from the real-time path. Additional component to build and operate. |

**Key question:** Synchronous (simpler) or asynchronous (more scalable)? Should bulk processing be isolated from the core request path? How does bulk interact with rate limiting?

---

## 8. Scheduling and Time-Based Triggers

**Criticality: Medium**

**Challenge:** A3 has multiple time-based concerns:
- **Request expiry**: requests not responded to by due date auto-transition to EXPIRED
- **Deferred request reactivation**: deferred requests return to PENDING on the specified pay date
- **VoP cache invalidation**: cached VoP results for mandates expire after 90 days

**Why it matters for A3:** Time-based transitions must be reliable (an invoice must expire on its due date, not days later) and must interact correctly with the state machine (cannot expire an already-accepted request) and concurrency controls (expiry vs. simultaneous accept).

**Typical solutions:**

| Approach | Description | Tradeoffs |
|----------|-------------|-----------|
| **Spring `@Scheduled` with database query** | Periodic job (e.g., every 60 seconds) queries for items whose trigger date has passed. Transitions them in a loop. | Simple, reliable. Granularity limited by polling interval. |
| **Database-driven scheduling with `pg_cron` or similar** | PostgreSQL extension runs scheduled queries inside the database. | Eliminates application-level scheduling. Ties logic to the database. Non-portable. |
| **Delayed Kafka messages** | Publish a "reminder" message with a future timestamp. A consumer processes it when the time arrives. | More real-time. Complex to implement correctly with Kafka (no native delay support — requires a delay topic pattern). |
| **Partition-based scheduling for scale** | Multiple scheduler instances each handle a subset of the data (by hash of request ID, or by date range). | Prevents a single scheduler from being overwhelmed. Adds coordination complexity. |

**Key question:** Is minute-level granularity (Spring `@Scheduled` polling) acceptable for expiry and reactivation? What happens in a multi-instance deployment — do we need leader election to prevent duplicate processing, or is compare-and-swap sufficient (only one instance successfully transitions the state)?

---

## 9. VoP Integration: Synchronous Dependency in the Accept Path

**Criticality: Medium**

**Challenge:** Before publishing a payment instruction to Kafka, A3 must call A6 (VoP) synchronously to verify the creditor name matches the IBAN. This adds latency to the consumer's accept operation and introduces a runtime dependency on another service.

**Why it matters for A3:** The VoP call happens in the critical path of the consumer's accept action. If A6 is slow or down, the consumer cannot accept payments. The 500ms timeout means the accept operation can take up to 500ms longer than it would without VoP. For instant payments, this time is outside A2's 5-second budget (it happens before A2 is involved), but it still affects consumer-perceived latency.

**Typical solutions:**

| Approach | Description | Tradeoffs |
|----------|-------------|-----------|
| **Synchronous call with timeout and fallback** | Call A6 with 500ms timeout. On timeout, proceed with `VOP_TIMEOUT` flag in the Kafka message. A2/A5 may treat this differently. | Simple. The draft specifies this approach. Risk: proceeding without VoP may allow payments to mismatched beneficiaries. |
| **Circuit breaker (Resilience4j)** | If A6 fails repeatedly, open the circuit — skip VoP calls for a cooldown period. All accepts during this window proceed with `VOP_UNAVAILABLE`. | Prevents cascading failures. Risk: during circuit-open, no VoP checks happen at all. |
| **VoP result caching for mandates** | For recurring payments to the same beneficiary, cache the VoP result (90-day TTL per RTP-RES03). Only re-check on mandate modification or account holder name change. | Reduces A6 dependency for recurring payments. Does not help for first-time payments. |
| **Async VoP with compensation** | Publish payment instruction immediately. Check VoP in parallel. If VoP returns NO_MATCH, publish a cancellation to Kafka. | Faster accept. Risk: a payment instruction may briefly exist in the pipeline before being cancelled. Increases complexity in A2. |

**Key question:** The draft commits to synchronous VoP with timeout. Should we add a circuit breaker? What should A3 do when VoP returns CLOSE_MATCH (not exact, not no-match) — block the payment, warn the consumer, or pass through?

---

## 10. Testing Strategy for Event-Driven Interactions

**Criticality: Medium**

**Challenge:** A3 publishes to Kafka and consumes from Kafka. Testing the happy path requires verifying that the right messages are published with the right content (correct Avro schema, correct topic, correct key), and that incoming status updates correctly transition the local state.

**Why it matters for A3:** A3 is both a Kafka producer (payment instructions) and a Kafka consumer (status updates). It also calls an external REST service (A6 VoP) and sends outbound HTTP (webhooks). This gives it the widest integration surface of any subsystem — four distinct external interaction types.

**Typical solutions:**

| Approach | Description | Tradeoffs |
|----------|-------------|-----------|
| **Testcontainers (Kafka + PostgreSQL)** | Start real Kafka and PostgreSQL in Docker containers for integration tests. Verifies full serialization, topic routing, consumer behavior. | Realistic. Slower test execution (~10-30s startup). |
| **In-memory test doubles** | Replace KafkaTemplate with a mock/spy that captures published messages. Assert on message content in-memory. | Fast. Does not test serialization/deserialization or Kafka-specific behavior (offsets, partitions). |
| **Contract tests (Pact for messages)** | Define expected Avro message schema. Producer test verifies conformance. Consumer test verifies it can process the schema. Tests run independently. | Tests inter-service contracts without running both services. Does not test runtime behavior. |
| **WireMock for outbound HTTP** | WireMock simulates A6 (VoP) and merchant webhook endpoints. Test A3's timeout handling, retry behavior, HMAC signing. | Industry standard for HTTP integration testing. Does not test the real A6 behavior. |
| **End-to-end with multiple services** | Start A3 + A6 (or mock) + Kafka + PostgreSQL. Run a full accept flow. | Most realistic. Slowest. Fragile if A6 implementation changes. |

**Key question:** What is the right balance? A recommended testing pyramid for A3 could be: unit tests for state machine logic, Testcontainers for Kafka/DB integration, WireMock for VoP and webhooks, Pact for inter-service message contracts. Is this the right mix, or should we invest more in end-to-end?

---

## Summary: Challenge Index

| # | Challenge | Criticality | Core Question |
|---|-----------|-------------|---------------|
| 1 | State machine correctness | **High** | Formal state machine vs. guard clauses + compare-and-swap? |
| 2 | Dual-write (DB + Kafka) | **High** | Which outbox relay mechanism? |
| 3 | Concurrency / race conditions | **High** | Which locking strategy per entity type? |
| 4 | Webhook reliability | **High** | Circuit breaker? Separate worker? Polling fallback? |
| 5 | Multi-tenant data isolation | **High** | Application-level filtering + PostgreSQL RLS? |
| 6 | API design: dual surface | **Medium** | Separate URL prefixes or shared paths? |
| 7 | Bulk operations | **Medium** | Synchronous or asynchronous? Isolated pipeline? |
| 8 | Scheduling | **Medium** | Polling granularity? Leader election? |
| 9 | VoP integration resilience | **Medium** | Circuit breaker? CLOSE_MATCH behavior? |
| 10 | Testing strategy | **Medium** | Testing pyramid balance? |

---

## Relationship to Master Architecture

Several A3 challenges map to master-level challenges (payment_processing/architecture/ARCHITECTURE_CHALLENGES.md):

| A3 Challenge | Master Challenge | Status |
|-------------|-----------------|--------|
| #1 State machine | B.1 Payment state machine | Master describes the cross-service state machine. A3 must define its internal portion. |
| #2 Dual-write | B.2 Dual-write problem | Master recommends Transactional Outbox. A3 must choose the relay mechanism. |
| #3 Concurrency | B.3 Exactly-once semantics | Master defines idempotency key strategy. A3 must implement it for its entities. |
| #4 Webhooks | (A3-specific) | No master equivalent — webhooks are unique to A3. |
| #5 Data isolation | A.1 Trust boundaries | Master defines zones. A3 must implement tenant isolation within its zone. |
| #6 API design | D.2 API versioning | Master leaves versioning strategy open. A3 must decide for its APIs. |
| #7 Bulk operations | (A3-specific) | No master equivalent — bulk submission is unique to A3. |
| #8 Scheduling | (A3-specific) | No master equivalent — time-based lifecycle is A3's responsibility. |
| #9 VoP integration | C.2 Time budget | VoP is outside A2's time budget but affects consumer-perceived latency. |
| #10 Testing | G.1 Integration testing | Master defines the testing strategy. A3 must apply it to its specific integration surface. |
