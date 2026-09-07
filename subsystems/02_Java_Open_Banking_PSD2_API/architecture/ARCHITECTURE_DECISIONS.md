# A4 — PSD2 Open Banking API Gateway: Architecture Decisions

*Date: 2026-04-11*
*Firm decisions derived from architectural challenge analysis (see ARCHITECTURE_CHALLENGES.md for full discussion)*
*Status: All 14 decisions made and ratified*

---

## Guiding Principles

Three principles drove all architectural decisions for A4. They emerged from brainstorming and proved mutually reinforcing:

1. **Security as architecture, not as afterthought.** A4 is the DMZ entry point — the system's attack surface. Security must drive the component structure, not be bolted on. We build a fortress from the beginning.

2. **Fortress mentality: defense in depth with independent layers.** Multiple layers of protection, each standing on its own. A failure or bypass of any single layer does not collapse the defense. Assume breach — design so that a compromised component has contained blast radius.

3. **Crash resilience by design.** The system may crash at any point — hardware failure, denial-of-service attack, or any other reason. Recovery must be principled, not ad-hoc. State management is designed so that after restart, the system knows exactly where it was and what to do next.

---

## Decision Summary

| ID | Challenge | Decision |
|----|-----------|----------|
| C-01 | Security as architecture | Hybrid: defense-in-depth layer model (L1-L6) with functional decomposition inside |
| C-02 | Crash resilience | State machines for money flows (payment, VRP); simple transactions for rest |
| C-03 | OAuth2 Authorization Server | Deep customization of Spring Authorization Server; business logic isolated |
| C-04 | Defense layer independence | Selective re-validation: trust crypto, always re-check mutable state from DB |
| C-05 | Internal service trust | mTLS + metadata headers; each service validates its own business invariants |
| C-06 | mTLS/TPP identity | Application-level mTLS via Spring Boot SSL bundles; pre-generated test certs |
| C-07 | JWT key management | Database-stored keys with application-managed rotation; KEK from env var |
| C-08 | SCA integration | Database-backed SCA state; durable lockout; dynamic link bound at creation |
| C-09 | Exemption engine | Chain of Responsibility (evaluate all); pessimistic locking on counters |
| C-10 | Dependency resilience | Resilience4j circuit breaker; timeouts; Caffeine cache for AISP only |
| C-11 | Consent storage | Database hit every request; no cache; immediate revocation effectiveness |
| C-12 | Testing strategy | Four-level pyramid: unit + integration + Pact contract + security property tests |
| C-13 | Rate limiting | In-memory Bucket4j per-TPP; Redis-backed documented as production upgrade |
| C-14 | Audit trail | Local PostgreSQL append-only table + Kafka via outbox relay |

---

## Foundational Decisions

### C-01: Defense-in-Depth Layer Model

**Decision:** The architecture is organized around six explicit defense layers, with functional component decomposition inside each layer.

**The six layers:**

| Layer | Mechanism | What it protects against |
|-------|-----------|------------------------|
| L1: Network | mTLS (client certificates) | Unauthorized network access; TPP impersonation |
| L2: Protocol | OAuth2/PKCE, JWT validation | Unauthorized API access; token theft; authorization code interception |
| L3: Business | Consent enforcement (database check) | Over-authorization; access beyond granted scopes/accounts |
| L4: Transaction | SCA with dynamic linking | Unauthorized money movement; SCA phishing |
| L5: Payload | JWE encryption (optional) | Response interception; log leakage of sensitive data |
| L6: Detection | Append-only audit trail | Post-breach investigation; regulatory evidence |

**Key property — layer independence:** Each layer functions independently. Removing or bypassing any single layer does not collapse the defense. The architecture document includes a "what happens if this layer fails?" analysis for each layer:

- L1 bypassed → L2 still requires valid JWT → attacker blocked
- L2 compromised (key leak) → L3 checks consent in database → forged JWT only works if consent exists; L1 independently identifies TPP via certificate
- L3 bypassed (bug) → L4 requires fresh SCA for payments → read-only exposure possible, no money movement
- L4 bypassed → fraud/sanctions screening (A5) still runs downstream → secondary protection for money movement
- L5 disabled → TLS still protects transport; data exposure only if TLS also compromised
- L6 disabled → other layers still prevent attacks; but detection capability lost

**Reasoning:** A4 is the system's attack surface. Organizing around defense layers (rather than pure functional decomposition) makes security structural and verifiable. Every request passes through all layers. Security coverage is complete by construction, not by checklist. The functional decomposition (controllers, services, repositories) exists within this framework — navigable for developers, defensible for reviewers.

---

### C-02: Crash Resilience — Selective State Machines

**Decision:** Explicit state machines for money-moving flows. Simple database transactions for everything else.

**State machines apply to:**

- **Payment initiation** — states extending PISP-F06 with internal states for crash recovery: `CREATED, VOP_CHECKING, VOP_COMPLETE, VOP_TIMEOUT, REQUIRES_CONFIRMATION, REQUIRES_SCA, SCA_IN_PROGRESS, SCA_COMPLETE, PUBLISHING, PROCESSING, SETTLED, REJECTED, HELD, CANCELLED`. PISP-F06 defines the TPP-visible subset; internal states (CREATED through PUBLISHING) provide operational granularity. See ARCHITECTURE.md Section 5.1 for the full state transition diagram
- **VRP payment** — same state model with additional VRP parameter validation before SCA

**Simple transactions apply to:**

- Token refresh (single atomic DB transaction — rolls back cleanly on crash)
- AISP queries (stateless reads — no crash risk)
- Consent revocation (single transaction — mark revoked, invalidate tokens)

**Recovery model per state:**

| State at crash | Recovery action |
|---------------|----------------|
| `VOP_CHECKING` | VoP call times out (3s) → payment transitions to `VOP_TIMEOUT`, proceeds with flag |
| `REQUIRES_SCA` | SCA timeout (5 min per PSD2-F21) → `CANCELLED` |
| `SCA_IN_PROGRESS` | SCA timeout → `CANCELLED` |
| `SCA_COMPLETE` | Outbox row exists → relay publishes on restart → `PROCESSING` |
| `PUBLISHING` | Same as SCA_COMPLETE — outbox relay is idempotent |

**Reasoning:** The payment state machine serves double duty: crash resilience AND the TPP-facing payment status API (`GET /payments/{id}`). The states are not added complexity — they are required by PISP-F06. Making them explicit and durable means crash recovery is correct by construction. Simpler flows (token refresh, AISP reads) don't benefit from state machines — database transactions and timeouts are sufficient.

---

### C-03: Spring Authorization Server — Deep Customization with Isolated Business Logic

**Decision:** Use Spring Authorization Server (SAS) for OAuth2 protocol mechanics. Customize via documented extension points. Isolate all business logic in framework-independent services.

**SAS handles (framework-managed):**
- PKCE verification (code_challenge / code_verifier)
- JWT signing and issuance (RS256, configurable via JWKSource)
- Token exchange at `/oauth2/token` endpoint
- JWKS endpoint (`/.well-known/jwks.json`)
- OpenID discovery metadata

**Custom implementations (our code):**
- `OAuth2AuthorizationConsentService` — PSD2 consent model (90-day expiry, account selection, VRP parameters, revocation)
- `OAuth2AuthorizationService` — PostgreSQL-backed authorization storage with refresh token family tracking and reuse detection
- `OAuth2TokenCustomizer<JwtEncodingContext>` — adds consent_id, granted scopes, account_holder_id to JWT claims
- Custom `AuthenticationProvider` for authorization endpoint — injects multi-step SCA into the authorization code grant flow

**Isolation principle:** Business logic (SCA engine, consent manager, exemption engine, token family manager) is implemented as plain Spring services with no dependency on SAS classes. Only the thin SAS integration layer (configurers, custom providers) imports SAS types. If SAS changes in a future version, only the integration layer needs updating — business logic is unaffected.

**Reasoning:** PKCE verification, JWT signing, and token exchange are security-critical protocol mechanics where spec compliance matters. Delegating to SAS inherits their correctness — we don't risk introducing OAuth2 spec violations. The complex parts (SCA, consent, exemptions) are custom regardless of approach — SAS doesn't attempt them. Isolating business logic limits the blast radius of framework coupling.

---

## Security Architecture Decisions

### C-04: Selective Re-validation Across Defense Layers

**Decision:** Trust cryptographic verification (JWT signatures, certificate validation). Never trust mutable state (consent status, token revocation) from anything other than the database.

**Concretely:**

| Layer | What it trusts | What it re-validates from database |
|-------|---------------|-----------------------------------|
| L1 (mTLS) | Certificate chain against trust store | — (crypto only, no mutable state) |
| L2 (JWT) | JWT signature, expiry, issuer | — (crypto only) |
| L3 (Consent) | consent_id from JWT (signature verified at L2) | Consent status (ACTIVE?), scope coverage, account coverage — always from database |
| L4 (SCA) | — | Exemption counters, SCA session state — always from database |
| L5 (JWE) | — (response-side, no trust dependency) | — |
| L6 (Audit) | — (records everything, no trust dependency) | — |

**Key security property:** Revoking a consent immediately blocks access, regardless of valid JWTs in the wild. A revoked consent is detected at L3 on the next request — the JWT's 15-minute lifetime does not create a revocation window.

**Reasoning:** Pure re-validation at every layer (ignoring JWT claims entirely) would make JWT issuance pointless. Pure trust-forward (trusting JWT claims for consent status) is fragile — a compromised signing key or a stale JWT would bypass consent checks. Selective re-validation gets the critical benefit: mutable state is always checked from the source of truth, while cryptographic proofs are trusted once verified.

---

### C-05: Internal Service Trust Contracts

**Decision:** mTLS for authentication between internal services. Metadata headers for auditability. Each service validates the business invariants it owns.

**Trust contracts:**

| Service | Guarantees | Trusts |
|---------|------------|--------|
| A4 | Consent enforcement before any call to A1 or A6. Request-level metadata headers (X-Account-Holder-Id, X-Request-Id, X-Correlation-Id) on all outbound calls | A1 to return only data for the requested account. A6 to limit response to match/no-match/close-match |
| A1 | Only responds to internal callers (mTLS). Validates account existence and status (ACTIVE/FROZEN). Logs all queries with correlation ID | A4 to have verified consent before calling |
| A6 | Only responds to internal callers (mTLS). Returns VoP result without leaking information beyond match category | A4 to have verified the request is legitimate |

**Metadata headers are for auditability, not authorization.** A1 does not validate consent — that is A4's responsibility. A1 validates its own invariants (does this account exist? is it active?). Each service owns its own business rules.

**Production upgrade path:** Service mesh (Istio/Linkerd) with authorization policies. Scoped service tokens become valuable when more services with differentiated access patterns exist.

**Reasoning:** A4's legitimate operations are "read any balance" and "check any VoP" — because it serves all TPPs and account holders. Service tokens scoped to these operations wouldn't restrict anything meaningful. The real protection is that A4 enforces consent before calling, and A1/A6 validate their own invariants. Making the trust contracts explicit and documented is more valuable than a token that can't actually restrict anything.

---

### C-06: Application-Level mTLS for TPP Identity

**Decision:** mTLS configured directly in Spring Boot 3 via SSL bundles. Pre-generated test certificates shipped with the project.

**Implementation:**
- Project-local CA and 2-3 test TPP certificates in `certs/dev/` (clearly marked test-only)
- `scripts/generate-certs.sh` for regeneration
- Spring Boot SSL bundle: `spring.ssl.bundle.jks.tpp-mtls`
- `TppCertificateExtractor` component extracts Distinguished Name (DN) from the client certificate and maps it to the registered TPP entity
- `docker-compose` mounts certificates automatically — zero manual setup for reviewers

**Production deployment model (documented, not implemented):** mTLS terminates at the load balancer or API gateway. Client certificate DN passed as a header (e.g., `X-Client-Certificate-DN`). Application trusts the header only from the internal reverse proxy. Certificates issued by eIDAS qualified trust service providers (QTSPs).

**Reasoning:** Self-contained portfolio deployment — `docker-compose up` works immediately with no certificate infrastructure. Demonstrates understanding of certificate-based TPP authentication end-to-end. Spring Boot 3's SSL bundle support makes this clean and modern. Production deployment model is documented to show architectural awareness without adding Nginx to the demo stack.

---

### C-07: Database-Managed JWT Key Rotation

**Decision:** Signing keys stored in PostgreSQL with application-managed rotation lifecycle.

**Data model:**
```
signing_keys
  key_id:                 UUID (PK)
  algorithm:              VARCHAR (RS256)
  public_key_pem:         TEXT
  private_key_encrypted:  BYTEA (AES-256-GCM)
  valid_from:             TIMESTAMPTZ
  valid_until:            TIMESTAMPTZ
  status:                 ENUM (ACTIVE, RETIRED, REVOKED)
```

**Rotation lifecycle:**
- Daily scheduled job checks: if newest ACTIVE key expires within 30 days, generate new RSA key pair
- New key: `valid_from = now`, `valid_until = now + 90 days`, `status = ACTIVE`
- Old key remains ACTIVE for 30-day overlap (TPPs may cache JWKS)
- After overlap expires: status transitions to RETIRED (no longer in JWKS, but kept for audit)
- JWKS endpoint (`/.well-known/jwks.json`) returns all keys where `status = ACTIVE AND valid_until > now()`
- Token signing always uses the newest ACTIVE key

**Key compromise response:**
1. Set compromised key `status = REVOKED` — immediately removed from JWKS
2. Generate and activate new key
3. Affected JWTs have max 15-minute lifetime — expire naturally
4. Consent checks (C-04) catch forged tokens even during the 15-minute window (forged JWT references a consent_id; if the consent doesn't exist or is revoked, access is denied)

**Key encryption:** Private keys encrypted with AES-256-GCM. Key Encryption Key (KEK) derived from environment variable (portfolio). Production: KEK from KMS (AWS KMS, Azure Key Vault, or HashiCorp Vault) per KM-04.

**Reasoning:** Database storage makes the rotation lifecycle demonstrable and testable — an integration test can trigger rotation and verify JWKS updates. File-based or KeyStore-based approaches make rotation an operational event rather than an application-managed lifecycle. The full rotation lifecycle (generate → overlap → retire) is the architecturally interesting part — it's what shows security engineering depth.

---

## Component Design Decisions

### C-08: Database-Backed SCA with Durable Lockout

**Decision:** SCA state persisted in PostgreSQL. Stateless controllers — no server-side sessions. Lockout survives server restarts.

**Data model:**
```
sca_sessions
  session_id:         UUID (PK)
  payment_id:         UUID (nullable — null for consent-only SCA)
  context_type:       ENUM (CONSENT, PAYMENT, VRP)
  account_holder_id:  VARCHAR
  step:               ENUM (AWAITING_PIN, AWAITING_TOTP, COMPLETED, FAILED, LOCKED)
  attempts:           INTEGER
  locked_until:       TIMESTAMPTZ (nullable)
  dynamic_link_data:  JSONB (amount + creditor for payments; null for consent)
  created_at:         TIMESTAMPTZ
  expires_at:         TIMESTAMPTZ (created_at + 5 min)
  completed_at:       TIMESTAMPTZ (nullable)
```

**Flow:** Each HTTP request reads session from database → validates step → processes input → updates step → returns next page. No in-memory session state.

**Security properties:**
- **Durable lockout:** After 3 failed attempts, `locked_until = now + 5 min`. Checked on every attempt regardless of session or server state. An attacker cannot reset the counter by starting a new session or waiting for a server restart.
- **Dynamic link immutability:** `dynamic_link_data` is written at session creation and never updated. Prevents an attacker from starting SCA for a EUR 1 payment and completing it for a EUR 10,000 payment (amount-switching attack).
- **Timeout:** `expires_at` checked on every access. Expired sessions are treated as CANCELLED. Background cleanup optional (check-on-access is sufficient).
- **Audit:** Every SCA attempt (success, failure, lockout) is written to the audit trail.

**Integration with C-03:** The custom `AuthenticationProvider` in Spring Authorization Server reads and writes the `sca_sessions` table. The consent page controller drives the multi-step flow using database state.

**Reasoning:** Session-based SCA lockout is trivially bypassable — an attacker starts a new session or waits for a server restart. PSD2 RTS requires that SCA lockout be effective. Database-backed lockout is the only correct implementation. The database round-trip per SCA step (~1-2ms) is negligible against the 5-minute human interaction time.

---

### C-09: SCA Exemption Engine — Chain of Responsibility with Pessimistic Locking

**Decision:** Chain of Responsibility pattern, modified to evaluate all handlers. Pessimistic locking on cumulative counters.

**Evaluation pattern:**
- Each exemption type (LOW_VALUE, TRUSTED_BENEFICIARY, RECURRING, MIT, TRA) is a handler implemented as a Spring bean
- All handlers are evaluated for every payment — no short-circuit on first match
- Each handler returns `EXEMPT` or `NOT_EXEMPT` with a rationale string
- Engine collects all `EXEMPT` results and selects primary by priority: `LOW_VALUE > TRUSTED_BENEFICIARY > RECURRING > MIT > TRA`
- All evaluated results logged to audit trail, including: exemption type, rationale, cumulative counter values at decision time, fraud rate (for TRA)

**Concurrency — pessimistic locking:**
- `SELECT ... FOR UPDATE` on the `sca_exemption_counters` row for the account, within the same database transaction as the payment record
- Second concurrent payment blocks until the first commits
- After first commits with count=5, second reads count=5 and forces SCA
- Counter reset to zero after successful SCA, in the same transaction as SCA completion

**Reasoning:** The 50ms latency budget (PSD2-NF03) is generous for an in-memory evaluation + one database round-trip with pessimistic lock. Contention on a single consumer account (two payments in the same millisecond) is extremely rare. Pessimistic locking is correct by construction — no retry logic, no race conditions. Evaluating all handlers (not just first match) provides complete auditability: the audit record shows every exemption that was considered, not just the one applied. This is valuable for regulatory scrutiny.

---

### C-10: Resilience Strategy for A1 and A6 Dependencies

**Decision:** Resilience4j circuit breakers, configurable timeouts, Caffeine cache for AISP balance queries only.

**Configuration:**

| Dependency | Timeout | Circuit breaker | Cache | Failure behavior |
|-----------|---------|----------------|-------|-----------------|
| A1 (AISP balance queries) | 2 seconds | Resilience4j, 50% failure threshold, 30s open duration | Caffeine, 30-second TTL | HTTP 503 with Retry-After |
| A1 (AISP transaction queries) | 2 seconds | Same circuit breaker | No cache (paginated, low hit rate) | HTTP 503 with Retry-After |
| A6 (VoP check) | 3 seconds | Separate circuit breaker | No cache (per-payment, low hit rate) | Payment proceeds with VOP_TIMEOUT flag per SEC-12, flagged for review |

**Why async VoP is not viable:** VoP must complete before the SCA redirect. A VoP NO_MATCH changes the flow — the payer must confirm or cancel before proceeding to SCA (UC-02 Scenario 2F). The VoP result also affects the SCA page content (additional warning on NO_MATCH override). This makes VoP synchronous on the critical path.

**Production enhancement (documented):** Bulkhead isolation — separate thread pools for A1 and A6 calls. Prevents A6 slowness from exhausting threads needed for A1 AISP queries. Not implemented in the portfolio single-instance deployment.

---

### C-11: Consent Validation — Database on Every Request, No Cache

**Decision:** Every authenticated API request checks consent status against the database. No caching.

**Implementation:**
- Consent lookup by primary key (`consent_id`) — sub-millisecond on PostgreSQL with index
- Query: `WHERE consent_id = ? AND status = 'ACTIVE' AND expires_at > now()`
- HikariCP connection pool sized for 200 concurrent requests (PSD2-NF05)
- Consent revocation is immediately effective — zero staleness window

**Why no cache:** A consent check is a security-critical decision. Introducing any staleness window (even 30 seconds) means a revoked consent could still be honored during that window. The PSD2 expectation is that consent revocation is immediate. A primary key lookup on PostgreSQL is ~0.5ms with a warm buffer pool — well within the 300ms AISP latency budget (PSD2-NF01). There is no performance justification for trading security correctness for sub-millisecond savings.

**Upgrade path (documented):** If performance profiling under production load shows consent checks as a bottleneck, upgrade to a Caffeine cache with 30-second TTL and explicit eviction on revocation. This is a localized service implementation swap, not an architectural change.

**Principle:** Don't introduce staleness on security-critical checks without a measured performance need. (Fortress principle applied to caching.)

---

### C-12: Four-Level Testing Pyramid with Security Property Tests

**Decision:** Four distinct test levels. Security property tests are an explicit, named category — not incidental assertions scattered across integration tests.

**Test levels:**

**1. Unit tests** (fast, no Spring context):
- Exemption engine handlers — each handler, priority selection, counter logic
- Consent validation — expiry checks, scope matching, account coverage
- VRP parameter enforcement — bounds checking, cumulative tracking
- Token lifecycle — family ID generation, reuse detection logic
- TppCertificateExtractor — DN parsing, TPP mapping

**2. Integration tests** (Spring Boot test slices, Testcontainers, NAV mock-oauth2-server):
- Full OAuth2 consent flow end-to-end
- Payment initiation state machine — happy path and crash recovery
- Kafka outbox relay — write outbox row, verify Kafka message published
- Key rotation lifecycle — trigger rotation, verify JWKS updates, verify old tokens valid during overlap
- A1/A6 resilience — WireMock simulating timeout/failure, verify circuit breaker behavior

**3. Contract tests** (Pact):
- AISP API response format (accounts, balances, transactions)
- PISP API request/response format (initiation, status query)
- VRP API (consent creation, payment execution, parameter exceeded error)
- Error response formats (401, 403, 422, 429)

**4. Security property tests** (explicit test class per defense layer):
- **L1:** Request without client certificate → connection rejected
- **L2:** Tampered JWT → 401. Expired JWT → 401. Missing PKCE on authorize → 400
- **L3:** Valid JWT but revoked consent → 403. Valid JWT but unauthorized account → 403
- **L4:** SCA lockout persists after application restart. Dynamic link data immutable mid-flow. SCA timeout cancels payment
- **L5:** JWE-encrypted response fields unreadable without TPP private key
- **L6:** Consent grant, consent revocation, SCA attempt, exemption decision — all produce audit records

**Reasoning:** Most portfolio projects have unit and integration tests. Very few have explicit security property tests organized by defense layer. This directly demonstrates the fortress architecture from C-01 — the defense layers are not just a documentation concept, they are verified by tests. The Pact contract tests demonstrate production practices (Tietoevry alignment). The NAV mock-oauth2-server demonstrates Norwegian ecosystem awareness.

---

### C-13: In-Memory Rate Limiting with Production Upgrade Path

**Decision:** Per-instance Bucket4j rate limiting. Distributed rate limiting documented as production upgrade.

**Implementation:**
- Bucket4j token bucket per TPP, keyed by `client_id` (extracted from mTLS certificate or OAuth2 token)
- Default: 100 requests/minute per TPP (configurable per KM-09)
- HTTP 429 response with `Retry-After` header
- Single per-TPP limit initially; per-endpoint-type limits (AISP vs PISP) as optional refinement

**Production upgrade path (documented):** Redis-backed Bucket4j (`bucket4j-redis`) for distributed rate limiting. Rate limit state shared across all A4 instances. Configuration change only — no architectural change. Required when A4 runs multiple instances behind a load balancer.

**Reasoning:** For a single-instance portfolio deployment, distributed rate limiting adds a Redis dependency with no benefit. The architecture supports the upgrade (rate limit is a pluggable filter, not embedded in business logic), and the upgrade path is documented.

---

### C-14: Dual-Store Audit Trail — Local PostgreSQL + Kafka

**Decision:** Audit events stored locally in PostgreSQL (append-only) and relayed to Kafka via the Transactional Outbox pattern.

**Data model:**
```
audit_events
  event_id:        UUID (PK)
  event_type:      ENUM (CONSENT_GRANTED, CONSENT_REVOKED, SCA_ATTEMPTED,
                         SCA_COMPLETED, SCA_FAILED, SCA_LOCKED, TOKEN_ISSUED,
                         TOKEN_REFRESHED, TOKEN_FAMILY_REVOKED, PAYMENT_INITIATED,
                         EXEMPTION_EVALUATED, API_ACCESS, VOP_CHECKED, ...)
  timestamp:       TIMESTAMPTZ
  actor_type:      ENUM (TPP, ACCOUNT_HOLDER, SYSTEM)
  actor_id:        VARCHAR
  resource_type:   VARCHAR (consent, payment, token, account)
  resource_id:     VARCHAR
  action:          VARCHAR
  outcome:         ENUM (SUCCESS, FAILURE, DENIED)
  detail:          JSONB (exemption rationale, counter values, error details)
  correlation_id:  UUID
  ip_address:      VARCHAR
```

**Properties:**
- **Append-only:** Repository interface exposes only `save()` — no `update()` or `delete()` methods
- **Transactional co-location:** Audit events written in the same database transaction as the action they record. If the action commits, the audit record exists. If the action rolls back, the audit record does not exist. Audit and action are always consistent.
- **Kafka relay:** Outbox relay (same mechanism as payment instruction outbox) publishes audit events to `audit.events` Kafka topic for system-wide aggregation
- **Local queryability:** SQL queries for incident investigation ("all SCA failures for account X in the last 24 hours", "all exemption decisions for this payment")
- **Integration:** Works with Platform Starter's `@Audited` annotation (Project #4) for automatic audit of annotated methods

**Reasoning:** The audit trail is the last defense layer (L6). It must be durable even when Kafka is temporarily unavailable — the local PostgreSQL table provides this. It must be queryable for A4-specific investigations — SQL provides this. The Transactional Outbox pattern is already used for payment instructions (PSD2-RES01), so reusing it for audit events adds no new infrastructure. Fortress principle: audit data survives any single infrastructure failure.

---

## Cross-Cutting Findings

### Items Already Resolved by Requirements (Not Architectural Challenges)

The following were considered but excluded from the challenge list because the requirements already mandate specific solutions:

| Item | Resolution | Requirement |
|------|-----------|-------------|
| Dual-write consistency (DB + Kafka) | Transactional Outbox pattern | RES-03, PSD2-RES01 |
| JWT signing algorithm | RS256 asymmetric | Requirements Section 7 |
| Kafka topics and direction | 4 topics defined (2 produce, 1 consume, 1 audit) | Requirements Section 10 |
| Dead Letter Queue | Required on all consumers, 3 retries, DLQ depth monitored | PSD2-RES02 |
| API versioning | URL path (`/api/v1/...`) | architecture_draft.md Decision D.2a |
| Data model | Consent, VRP extension, refresh token, SCA counter tables defined | Requirements Section 8 |
| Graceful shutdown | Complete in-flight SCA flows before shutdown | PSD2-RES03 |
| Message-level integrity | HMAC-SHA256 on Kafka messages | KM-06 |

### Production vs. Portfolio Boundaries

Several decisions have a "portfolio implementation" and a "production upgrade path." These are collected here for clarity:

| Decision | Portfolio Implementation | Production Upgrade |
|----------|------------------------|-------------------|
| C-05 (service trust) | mTLS + metadata headers | Service mesh + scoped service tokens |
| C-06 (TPP mTLS) | Application-level SSL bundles | Load balancer terminates mTLS, header forwarding |
| C-07 (key storage) | KEK from environment variable | KMS (AWS/Azure/HashiCorp Vault) |
| C-10 (resilience) | Circuit breaker + timeout | Add bulkhead isolation (separate thread pools) |
| C-11 (consent cache) | Database every request | Caffeine cache with eviction (if profiling justifies) |
| C-13 (rate limiting) | In-memory Bucket4j | Redis-backed Bucket4j |

In every case, the portfolio implementation is architecturally sound — the production upgrade is a configuration or implementation swap, not a structural change. This is deliberate: the architecture is production-ready; the implementation makes portfolio-appropriate trade-offs.
