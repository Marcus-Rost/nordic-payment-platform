# A4 — PSD2 Open Banking API Gateway: Architectural Challenges

*Date: 2026-04-11*
*Consolidated brainstorming — all challenges resolved into firm architectural decisions*
*Status: ALL 14 DECISIONS MADE — ready to inform the architecture document*

---

## How to Read This Document

Each challenge has:
- **Context**: why this is a genuine architectural decision (not already resolved)
- **Solution options**: with pros and cons
- **Recommendation**: our suggested direction (to be confirmed in discussion)
- **Decision**: filled in after discussion (currently PENDING)

Items already resolved by the requirements (e.g., Transactional Outbox pattern — mandated by RES-03/PSD2-RES01; JWT RS256 signing — specified in requirements Section 7; Kafka topics and DLQ — specified in Sections 10 and PSD2-RES02) are NOT listed here.

---

## Priority 1 — Foundational Decisions (Shape Everything Else)

### C-01: Security as Architecture — Threat Model-Driven Design

**Context:** A4 is the DMZ-facing entry point for all third-party access to the payment system. Security is not a feature to add to components — it must drive the component structure itself. Every attack against the payment platform will likely come through A4. If we don't make security a foundational architectural concern, it becomes difficult to integrate later. We want to build a fortress from the beginning, not patch holes as they appear.

**The core question:** Should the architecture be organized around functional decomposition (controllers, services, repositories) or around trust boundaries and defense layers?

**Solution options:**

**(a) Threat-model-first architecture**
The architecture document starts with a threat model (attack surfaces, adversary capabilities, trust boundaries). Components are derived from the defense needs. Every component has an explicit security role.

- Pros: Security is structural, not bolted on. Makes it impossible to "forget" a security concern. Demonstrates deep security thinking to reviewers (target companies: Mastercard, DNB).
- Cons: Unusual structure — reviewers expecting standard layered architecture may find it harder to navigate. More work up front.

**(b) Layered architecture with security as a cross-cutting concern**
Standard functional decomposition (API layer, service layer, persistence). Security is applied via Spring Security filters, interceptors, and aspect-oriented audit logging. A security section documents what each layer is responsible for.

- Pros: Familiar to any Spring developer. Easier to implement. Matches how Spring Security works.
- Cons: Security can slip through cracks between layers. Hard to verify completeness ("did we cover everything?").

**(c) Hybrid: Defense-in-depth layer model with functional decomposition inside each layer**
Define explicit defense layers (network/mTLS → protocol/OAuth2 → business/consent → transaction/SCA → payload/JWE → detection/audit). Within each layer, use standard functional decomposition. The architecture document presents both views.

- Pros: Best of both worlds — security is structural AND the code structure is navigable. The defense layers serve as a checklist: every request passes through all layers.
- Cons: More complex documentation. Risk of over-engineering if taken too literally.

**Recommendation:** Option (c). It gives us the fortress mentality without abandoning practical code organization.

**Decision:** DECIDED — Option (c). Architecture document structured with defense-in-depth layer model (L1: Network/mTLS → L2: Protocol/OAuth2 → L3: Business/Consent → L4: Transaction/SCA → L5: Payload/JWE → L6: Detection/Audit) as a first-class structural element. Each layer includes "what happens if this layer fails?" analysis to demonstrate independence. Functional component decomposition presented within this framework. Crash recovery model as a parallel structural section.

---

### C-02: Crash Resilience — State Machine vs. Imperative Flow

**Context:** A4 has several multi-step processes that can be interrupted at any point by hardware failure, denial-of-service attack, or any other reason. If the system crashes in the middle of a payment transaction, we need a principled recovery model — not ad-hoc fixes. This must be considered from the beginning.

The critical multi-step flows:
1. Consent: authorize → SCA step 1 → SCA step 2 → consent grant → code → token
2. Payment: validate → VoP → SCA → record → publish to Kafka
3. Token refresh: validate old → mark used → issue new
4. VRP: validate bounds → update counters → publish to Kafka

**The core question:** How do we model A4's state so that crash recovery is principled rather than ad-hoc?

**Solution options:**

**(a) Explicit state machine with durable state**
Every multi-step process is modeled as a state machine with well-defined states, transitions, and timeout rules. State is persisted in PostgreSQL. After restart, the system looks at the state and knows exactly what happened and what to do next.

Example payment states: `CREATED → VOP_CHECKING → VOP_COMPLETE → REQUIRES_SCA → SCA_IN_PROGRESS → SCA_COMPLETE → PUBLISHING → PUBLISHED`

Each state has a clear recovery action:
- `SCA_IN_PROGRESS` + timeout → `CANCELLED`
- `SCA_COMPLETE` + outbox not yet published → outbox relay picks up
- `PUBLISHING` → outbox relay retries

- Pros: Every failure scenario has a defined recovery path. Makes crash recovery verifiable and testable. State transitions are auditable. Aligns with payment status reporting (PISP-F06 already defines states).
- Cons: More upfront design. State machine must be maintained as logic evolves. Over-engineering risk for simpler flows (e.g., token refresh is a single transaction — it doesn't need a state machine).

**(b) Transaction-per-step with timeout cleanup**
Each step is a database transaction. If a step fails, the transaction rolls back. Long-running processes (SCA) have timeouts that clean up incomplete state. No explicit state machine — state is implicit in which database records exist.

- Pros: Simpler implementation. Works well for short operations (token refresh, exemption check). Leverages PostgreSQL's ACID guarantees.
- Cons: For multi-step flows spanning multiple transactions (payment initiation), recovery is ad-hoc. Hard to answer "what state is this payment in?" without reconstructing it from multiple tables.

**(c) State machine for payment flows, simple transactions for everything else**
Apply the state machine pattern only where the flow spans multiple transactions and has complex failure modes (payment initiation, VRP payment, consent creation). Use simple database transactions for atomic operations (token refresh, exemption check, balance query).

- Pros: Right-sized complexity — state machines where they add value, simplicity where they don't. Payment flows genuinely need state tracking (PISP-F06 already defines the state enum). Token refresh doesn't.
- Cons: Two different patterns in the same codebase. Developers must know which pattern applies where.

**Recommendation:** Option (c). Payment initiation genuinely needs explicit state tracking (the requirements already define the states in PISP-F06). Token refresh and AISP queries do not. Apply the state machine where the flow is multi-step and crash-sensitive; use simple transactions where the operation is atomic.

**Decision:** DECIDED — Option (c). State machines for money-moving flows (payment initiation, VRP payment) extending the states from PISP-F06 with internal states for crash recovery (CREATED, VOP_CHECKING, VOP_COMPLETE, VOP_TIMEOUT, REQUIRES_CONFIRMATION, REQUIRES_SCA, SCA_IN_PROGRESS, SCA_COMPLETE, PUBLISHING, PROCESSING, SETTLED, REJECTED, HELD, CANCELLED). Simple database transactions for non-money flows (token refresh, AISP queries, consent revocation). SCA state durability deferred to C-08. The payment state machine serves double duty: crash resilience AND the TPP-facing payment status API.

---

### C-03: OAuth2 Authorization Server — Customization Depth

**Context:** A4 needs a full OAuth2/OIDC authorization server with consent management, PKCE, JWT signing, and token lifecycle. The challenge is that SCA must be deeply interleaved with the authorization flow — SCA happens *during* the authorization code grant. Spring Authorization Server is specified in the tech stack, but the degree of customization is an architectural decision.

**The core question:** How deeply do we customize Spring Authorization Server, and where do we draw the line between framework-managed and custom-managed behavior?

**Solution options:**

**(a) Deep customization of Spring Authorization Server**
Use Spring Authorization Server as the foundation. Customize the authorization endpoint to inject multi-step SCA. Implement custom `OAuth2AuthorizationConsentService`, `OAuth2TokenGenerator`, `OAuth2AuthorizationService`. The framework handles the OAuth2 protocol mechanics; we inject SCA and consent logic at defined extension points.

- Pros: Leverages the framework's RFC compliance (code exchange, PKCE verification, token format). Avoids reimplementing OAuth2 protocol mechanics. Extension points are well-documented.
- Cons: Requires deep understanding of Spring Authorization Server internals. If the framework's extension points don't accommodate a requirement, workarounds can be brittle. Framework upgrades may break customizations.

**(b) Spring Authorization Server for token issuance only, custom consent/SCA flow**
Use Spring Authorization Server only for the token endpoint (`/token`) and JWKS. Build the consent and SCA flow as a custom Spring MVC application that produces authorization codes. The authorization code is then exchanged at the standard token endpoint.

- Pros: Full control over the consent + SCA UX. The token endpoint is the simplest part of OAuth2 to delegate. Clearer separation: our code handles the complex parts (consent, SCA), the framework handles the mechanical parts (JWT signing, token exchange).
- Cons: Must ensure the custom authorization flow produces codes that the framework's token endpoint can consume. Tighter coupling to framework internals (code storage format).

**(c) Fully custom OAuth2 implementation (no Spring Authorization Server)**
Implement the OAuth2 flows from scratch using Spring Security as the foundation but without the Authorization Server module. Hand-code RFC 6749/7636 flows.

- Pros: Maximum flexibility. No framework constraints. Full understanding of every line.
- Cons: High risk of spec violations. Reimplements solved problems. More code to maintain and audit. Reviewers may question why you didn't use the standard framework.

**Recommendation:** Option (a). Delegate protocol mechanics (PKCE, JWT signing, token exchange) to the framework. Customize via documented extension points for consent and SCA. Isolate business logic (SCA engine, consent manager, exemption engine) in framework-independent services.

**Decision:** DECIDED — Option (a). Deep customization of Spring Authorization Server. SAS handles protocol mechanics (PKCE verification, JWT signing, token exchange, JWKS). Custom implementations for: OAuth2AuthorizationConsentService (PSD2 consent model), OAuth2AuthorizationService (PostgreSQL-backed with refresh token family tracking), OAuth2TokenCustomizer (consent_id, scopes, account_holder in JWT claims), custom AuthenticationProvider for authorization endpoint (injects multi-step SCA). Business logic (SCA engine, consent manager, exemption engine) isolated as framework-independent Spring services — only the thin SAS integration layer depends on the framework. This limits blast radius if SAS changes in future versions.

---

## Priority 2 — Security Architecture Decisions

### C-04: Defense in Depth — Layer Independence and Failure Isolation

**Context:** A4 implements multiple security mechanisms (mTLS, OAuth2, consent enforcement, SCA, JWE, audit). These must function as independent defense layers — removing any single layer should not collapse the whole defense. This is the "fortress" principle applied to architecture: each wall stands on its own.

**The core question:** How do we ensure that security layers are truly independent, and that a failure or bypass of one layer is caught by the next?

**Solution options:**

**(a) Strict layered enforcement in the request pipeline**
Each security layer is a distinct, ordered filter/interceptor in the Spring Security filter chain. Each layer makes its own independent decision (accept/reject) using its own data. No layer trusts the output of a previous layer — each re-validates what it needs.

Example pipeline: mTLS filter → rate limit filter → JWT validation → consent check → scope enforcement → (business logic) → JWE encryption → audit logging

- Pros: Each layer is independently testable. A bypass of one layer is caught by the next. Clear, auditable request pipeline.
- Cons: Some redundancy (e.g., JWT already contains client_id, but consent check re-reads it from the database). Slightly more latency per request.

**(b) Trust-forward pipeline**
Each layer enriches a request context that downstream layers trust. For example, mTLS sets the TPP identity; OAuth2 validation trusts it and adds the consent; SCA check trusts the consent and adds the exemption decision.

- Pros: Less redundancy, faster. Each layer builds on the previous.
- Cons: A compromised early layer poisons everything downstream. Harder to verify each layer independently.

**(c) Layered with selective re-validation**
Most layers trust previous enrichment, but critical security decisions (consent status, token validity) are always re-validated from the source of truth (database), never from in-flight context alone.

- Pros: Pragmatic balance — trusts the pipeline for non-critical enrichment, re-validates for critical decisions. Consent check always hits the database (catches revoked consents even with valid JWTs).
- Cons: Must clearly define which decisions are "critical" (re-validate) vs. "non-critical" (trust pipeline).

**Recommendation:** Option (c). Pure re-validation at every layer (option a) is academically clean but practically wasteful. Pure trust-forward (option b) is fragile. Selective re-validation for the critical decisions (consent status, token validity, rate limits) is the right trade-off.

**Decision:** DECIDED — Option (c). Selective re-validation. Principle: trust cryptographic verification (JWT signature, certificate validation) but never trust mutable state (consent status, token revocation) from anything other than the database. Concretely: L1/mTLS establishes TPP identity independently. L2/JWT validates signature and expiry (trusted for identity claims). L3/Consent reads consent_id from JWT but always checks consent status, scope coverage, and account coverage against the database. L4/SCA checks exemption engine with fresh counter data from database. L5/JWE and L6/Audit have no upstream trust dependencies. Key security property: revoking a consent immediately blocks access regardless of valid JWTs in the wild.

---

### C-05: Trust Boundaries — Internal Service Communication

**Context:** When A4 calls A1 (balance queries) or A6 (VoP check), what does A4 trust these services to have done? What do they trust A4 to have done? If A4 is compromised, should A1/A6 blindly accept its requests? How deep does the fortress extend?

**The core question:** How do we authenticate and authorize inter-service communication, and what trust assumptions does each service make?

**Solution options:**

**(a) Service-to-service mTLS with no additional authorization**
Services identify each other via client certificates. If a request comes from A4's certificate, A1 trusts it. No per-request authorization check.

- Pros: Simple. Standard for internal service mesh. Sufficient if the network is trusted.
- Cons: A compromised A4 can make any request to A1. No fine-grained access control.

**(b) Service-to-service mTLS + scoped service tokens**
Services authenticate via mTLS AND present a short-lived JWT with claims describing what they're authorized to do (e.g., A4 can read balances but not modify them). Each service validates the token and enforces its own authorization rules.

- Pros: Defense in depth for internal communication. A compromised A4 is limited to what its service token allows. Matches zero-trust architecture principles.
- Cons: More infrastructure (who issues service tokens? key management for internal tokens). More complexity. May be over-engineering for a portfolio project where all services run in the same docker-compose.

**(c) mTLS + request-level metadata (not tokens)**
Services authenticate via mTLS. A4 passes metadata (account holder ID, consent ID, requested scopes) in request headers. A1 validates that the metadata is consistent (e.g., the requested account belongs to the claimed account holder) but trusts A4's identity via the certificate.

- Pros: Simpler than full service tokens. A1 can still validate business rules independently (e.g., "is this account accessible?"). No token infrastructure needed.
- Cons: Metadata in headers is less formalized than token claims. Harder to audit.

**Recommendation:** Option (c) for the portfolio implementation with option (b) documented as the production-grade approach. This keeps implementation tractable while demonstrating the architectural awareness.

**Decision:** DECIDED — Option (c). mTLS for authentication between internal services. Metadata headers (X-Account-Holder-Id, X-Request-Id, X-Correlation-Id) for auditability, not authorization. Each service validates the business invariants it owns: A4 enforces consent before calling A1; A1 validates account existence and status; A6 limits response to match/no-match/close-match. Trust contract documented explicitly: A4 guarantees consent enforcement, A1 guarantees internal-only access via mTLS, A6 guarantees no information leakage beyond VoP result. Production upgrade path: service mesh with authorization policies and scoped service tokens.

---

### C-06: mTLS and TPP Identity — Simulation Depth

**Context:** PSD2 requires TPPs to present eIDAS QWAC certificates. The real eIDAS trust chain is complex (qualified trust service providers, ETSI standards). For a portfolio project, we need realistic simulation without building certificate authority infrastructure.

**The core question:** How far do we simulate the eIDAS certificate-based TPP identification?

**Solution options:**

**(a) Project-local CA with self-signed client certificates**
Create a project-local CA (using keytool or OpenSSL). Issue client certificates for test TPPs. Spring Boot's SSL bundle validates client certificates against the local CA trust store. The DN (Distinguished Name) in the certificate maps to the registered TPP.

- Pros: Realistic mTLS handshake. Demonstrates understanding of certificate-based authentication. Testable end-to-end.
- Cons: Certificate management adds operational overhead. Tests need certificate setup.

**(b) mTLS at reverse proxy, application trusts header**
Nginx or Envoy terminates mTLS, passes the client certificate DN as an `X-Client-Certificate-DN` header. A4 application code reads the header and maps to a TPP.

- Pros: Separates infrastructure concern (TLS) from application concern (TPP mapping). Simpler application code. Mirrors production deployments where mTLS terminates at the load balancer.
- Cons: The header can be spoofed if the reverse proxy is bypassed. Must ensure only the proxy can set the header.

**(c) Application-level mTLS (Spring Boot SSL bundles)**
Configure mTLS directly in Spring Boot using SSL bundles (Spring Boot 3.1+). The application itself validates client certificates. No reverse proxy needed for the portfolio deployment.

- Pros: Everything in one place. No additional infrastructure. Demonstrates Spring Boot 3's modern SSL support.
- Cons: In production, mTLS typically terminates at the infrastructure layer, not the application. But for portfolio purposes, this is fine.

**Recommendation:** Option (c) for the portfolio, with (b) documented as the production deployment model. This keeps the demo self-contained (no Nginx dependency) while showing we understand the production architecture.

**Decision:** DECIDED — Option (c). Application-level mTLS via Spring Boot 3 SSL bundles. Pre-generated test CA and 2-3 test TPP certificates shipped in `certs/dev/` (clearly marked test-only). `scripts/generate-certs.sh` for regeneration. Spring Boot SSL bundle config: `spring.ssl.bundle.jks.tpp-mtls`. TppCertificateExtractor component maps certificate DN to registered TPP. docker-compose mounts certificates automatically — zero setup for reviewers. Production deployment model documented: mTLS terminates at load balancer/API gateway (option b), certificates from eIDAS qualified trust service providers.

---

### C-07: JWT Signing Key Management and Rotation

**Context:** JWT signing keys must rotate every 90 days with 30-day overlap (KM-03). Multiple keys are published in the JWKS endpoint. This is specified in requirements but the storage and rotation mechanism is an architectural choice.

**The core question:** Where do signing keys live, and how is rotation orchestrated?

**Solution options:**

**(a) Database-stored keys with application-managed rotation**
Signing keys stored in a `signing_keys` table (key_id, algorithm, public_key, private_key_encrypted, valid_from, valid_until). A scheduled job generates new keys before expiry. The JWKS endpoint reads all non-expired keys. The token signer uses the newest valid key.

- Pros: Simple. All state in PostgreSQL. Rotation is a scheduled task. Multiple A4 instances see the same keys (if sharing DB). Testable.
- Cons: Encrypted private key in the database — the encryption key itself must be managed (bootstrapping problem). Not how production HSM-based systems work.

**(b) File-based keys with external rotation**
Keys stored as PEM files on disk (or mounted from Kubernetes secrets). Rotation is an operational process: generate new key, deploy to all instances, remove old key after overlap period.

- Pros: Simpler application code (just read from file). Matches how many Spring Boot apps handle keys.
- Cons: Multi-instance deployments need synchronized key deployment. Harder to automate rotation. No database query to see current key state.

**(c) KeyStore-based with runtime reload**
Java KeyStore (JKS or PKCS12) containing signing keys. Application watches for KeyStore file changes and reloads. New keys added to the KeyStore during rotation.

- Pros: Standard Java cryptographic key storage. Works with existing Spring Security infrastructure.
- Cons: KeyStore management is operationally clunky. Same multi-instance synchronization problem as (b).

**Recommendation:** Option (a). For a portfolio project, database-managed keys are the most demonstrable and testable. Document that production would use an HSM or KMS (KM-04). The encryption bootstrapping problem is solved by deriving the key encryption key from an environment variable (acceptable for portfolio; production uses KMS).

**Decision:** DECIDED — Option (a). Database-stored keys with application-managed rotation. Table: signing_keys (key_id UUID, algorithm, public_key_pem, private_key_encrypted BYTEA, valid_from, valid_until, status ACTIVE/RETIRED/REVOKED). Private key encrypted AES-256-GCM with KEK from environment variable (production: KMS per KM-04). Scheduled daily job generates new key pair when newest key expires within 30 days. Overlap: 30 days (old key valid for verification, new key used for signing). JWKS endpoint returns all ACTIVE non-expired keys. On compromise: set status=REVOKED (immediate JWKS removal), issue new key, affected tokens expire naturally (15-min lifetime), consent checks (C-04) catch forged tokens. Rotation lifecycle fully testable in integration tests.

---

## Priority 3 — Component Design Decisions

### C-08: SCA Integration Into the Authorization Flow

**Context:** SCA is not a single step — it's multi-factor (PIN → TOTP), with failure handling (3 attempts, 5-min lockout), timeout (5-min cancellation for payments), and dynamic linking (payment details in challenge). SCA must work differently depending on context: consent-only flows show consent details; payment flows show amount + creditor. The SCA mechanism must be polymorphic.

**The core question:** How is SCA structurally integrated into the authorization and payment flows?

**Solution options:**

**(a) SCA as a multi-step sub-flow within the authorization endpoint**
The `/authorize` endpoint serves a multi-page flow managed by Spring MVC (or Spring WebFlux). Server-side session state tracks progress through SCA steps. The SCA "challenge context" (consent-only vs. payment with dynamic linking) is passed as a parameter.

- Pros: Everything in one request/response cycle (from the TPP's perspective, it's a single redirect). Standard OAuth2 pattern. Session state is short-lived (5-min max).
- Cons: Server-side session state is not durable across crashes — if the server restarts mid-SCA, the user must start over. (But: PSD2-F21 defines a 5-min timeout anyway, so this may be acceptable.)

**(b) SCA as a separate service/module with its own API**
SCA is extracted into a dedicated module with its own endpoints (`/sca/start`, `/sca/verify`). The authorization flow redirects to the SCA module, which calls back on completion. The SCA module manages its own state (attempts, lockout, timeout).

- Pros: Clean separation of concerns. SCA module is independently testable. Could be reused if other services need SCA in the future.
- Cons: Adds a network hop (or at minimum a module boundary). SCA state must be shared with the authorization flow (how does the authorization endpoint know SCA completed?). More moving parts.

**(c) SCA state in the database, flow driven by stateless controllers**
SCA progress is persisted in a database table (`sca_sessions`: session_id, context_type, context_data, step, attempts, locked_until, created_at). Each HTTP request reads the state, processes the step, writes the updated state. No server-side session — fully stateless and crash-recoverable.

- Pros: Crash-resilient — SCA state survives server restart. Stateless application servers (good for horizontal scaling). Auditable (every SCA attempt is a database record). Aligns with the state machine approach from C-02.
- Cons: Database round-trip per SCA step. More code than using server-side sessions. SCA sessions must be cleaned up (but timeout handles this).

**Recommendation:** Option (c). Database-backed SCA state for crash resilience, durable lockout enforcement, and audit trail. Follows from C-02 (state machine principle) and C-03 (SAS custom AuthenticationProvider).

**Decision:** DECIDED — Option (c). Database-backed SCA state with stateless controllers. Table: sca_sessions (session_id UUID, payment_id nullable, context_type CONSENT/PAYMENT/VRP, account_holder_id, step AWAITING_PIN/AWAITING_TOTP/COMPLETED/FAILED/LOCKED, attempts INT, locked_until TIMESTAMP nullable, dynamic_link_data JSONB, created_at, expires_at, completed_at). Each HTTP request reads/updates database state — no server-side sessions. Lockout (3 attempts → 5-min lock) is durable: survives server restarts, preventing lockout bypass attacks. Dynamic linking data bound to SCA session at creation — cannot be modified mid-flow (prevents amount-switching attacks). Timeout via expires_at (5 min per PSD2-F21), check-on-access. Every SCA attempt is an audit event. Integrates with C-03: custom AuthenticationProvider reads/writes sca_sessions table.

---

### C-09: SCA Exemption Engine — Design Pattern

**Context:** Every payment must be evaluated against 4-5 exemption rules. The engine must be <50ms, auditable, and correctly handle concurrent payments (cumulative counter updates must be atomic). The exemption types are: low-value, TRA, trusted beneficiary, recurring, MIT.

**The core question:** What design pattern for the exemption evaluation, and how are cumulative counters made concurrent-safe?

**Solution options:**

**(a) Chain of Responsibility**
Each exemption type is a handler in a chain. The chain evaluates in priority order; first match wins. Each handler is a Spring bean, easily testable in isolation. Counter state is read at the start and updated transactionally with the payment.

- Pros: Simple, well-understood pattern. Easy to add/remove exemption types. Each handler is independently testable.
- Cons: First-match semantics may not always be what regulators want (what if multiple exemptions apply — do we log them all or just the one used?).

**(b) Strategy with evaluator registry**
Each exemption type is a strategy. A registry evaluates ALL applicable strategies and returns a ranked list of applicable exemptions. The caller picks the best one (or the one with least regulatory risk). All evaluated exemptions are logged.

- Pros: Evaluates all options (better auditability — "TRA and LOW_VALUE both applied, we chose LOW_VALUE"). More flexible for regulatory scrutiny.
- Cons: Slightly more complex. Evaluating all strategies even after finding a match wastes cycles (though <50ms is generous).

**(c) Decision table / specification pattern**
Exemption rules encoded as a data structure (table or specifications). Evaluated generically. Easy to change rules without code changes.

- Pros: Rule changes don't require code changes. Good for rapid regulatory adaptation.
- Cons: Over-engineering for 4-5 rules. Harder to debug. The rules aren't changing dynamically in a portfolio project.

**Concurrency for cumulative counters:**

All options must address: two concurrent payments from the same account, both under EUR 30, both checking the cumulative counter. If both read count=4 and proceed without SCA, we've violated the 5-payment limit.

- **Pessimistic locking**: `SELECT ... FOR UPDATE` on the counter row. Simple, correct, adds latency if contention is high.
- **Optimistic locking**: read counter, attempt update with version check, retry on conflict. Better throughput, more complex code.
- **Serialized per account**: route all exemption checks for the same account through a single thread/actor. Eliminates races but limits parallelism.

**Recommendation:** Option (a) for the evaluation pattern (Chain of Responsibility — simplest, sufficient, testable). Pessimistic locking for counters (correctness over throughput — the 50ms budget is generous, and contention on a single account is rare in practice). Log all evaluated exemptions, not just the one applied.

**Decision:** DECIDED — Chain of Responsibility, modified to evaluate all handlers (no short-circuit). Each handler returns EXEMPT/NOT_EXEMPT with rationale. Engine collects all EXEMPT results, selects primary by priority (LOW_VALUE > TRUSTED_BENEFICIARY > RECURRING > MIT > TRA), logs all evaluated results to audit trail including counter values and fraud rate. Cumulative counters: pessimistic locking (SELECT ... FOR UPDATE on sca_exemption_counters row) within the payment transaction. Counter reset to zero after successful SCA in the same transaction as SCA completion. Correctness over throughput — contention on a single consumer account is negligible.

---

### C-10: Synchronous Dependencies — Resilience Strategy for A1 and A6

**Context:** AISP queries depend synchronously on A1 (balance/transaction data). Payment initiation depends synchronously on A6 (VoP check). If either is down or slow, A4 degrades. VoP is on the critical path before SCA — it cannot be deferred.

**The core question:** What resilience patterns do we apply to outbound synchronous calls, and what happens when dependencies fail?

**Solution options:**

**(a) Circuit breaker + timeout + cache (where applicable)**
Resilience4j circuit breaker on each outbound call. Configurable timeout (e.g., 2s for A1, 3s for A6). Caffeine short-TTL cache for AISP balance queries (30s). On A1 failure: AISP returns 503 with Retry-After. On A6 failure: payment proceeds with VOP_TIMEOUT flag (per SEC-12).

- Pros: Well-understood patterns. Resilience4j integrates cleanly with Spring Boot. VOP_TIMEOUT is already specified in requirements.
- Cons: Caching balances for 30s means stale data is possible (acceptable for AISP — not used for authoritative balance checks). Circuit breaker tuning requires thought (when to open/close).

**(b) Bulkhead isolation + circuit breaker**
In addition to circuit breakers: separate thread pools for A1 calls and A6 calls. A slow A6 cannot exhaust the thread pool used for A1 AISP queries.

- Pros: Fault isolation between dependencies. A6 degradation doesn't cascade to AISP.
- Cons: More configuration. Thread pool sizing is an art. May be over-engineering for a single-instance portfolio deployment.

**(c) Async with callback for VoP**
Fire VoP check asynchronously when payment is submitted. Return immediately with PENDING status. When VoP completes, update payment state and notify TPP.

- Pros: Faster response time for payment submission. A6 slowness doesn't block the API.
- Cons: Changes the API contract significantly. TPP must poll or use webhooks to get VoP result. VoP must complete before SCA (dynamic linking may need VoP result for warnings). Requirements specify VoP before SCA — this option may violate the flow.

**Recommendation:** Option (a) as the baseline, with (b) as an enhancement if the architecture warrants it. Option (c) is not viable because VoP must complete before SCA redirect (VoP NO_MATCH changes the SCA page — see UC-02 Scenario 2F).

**Decision:** DECIDED — Option (a), with (b) documented as production enhancement. Resilience4j circuit breaker on A1 and A6 REST clients. Timeouts: A1 2 seconds, A6 3 seconds. A1 failure: AISP returns 503 with Retry-After. A6 failure/timeout: payment proceeds with VOP_TIMEOUT flag per SEC-12, flagged for review. Caffeine cache: 30-second TTL for AISP balance queries. No VoP caching (per-payment, low hit rate). Option (c) ruled out: VoP must complete before SCA redirect (UC-02 Scenario 2F). Production enhancement: bulkhead isolation with separate thread pools for A1 and A6 calls.

---

### C-11: Consent and Token Storage — Cache Strategy

**Context:** Every API request requires consent validation (is the consent ACTIVE? does it cover the requested scope and account?). JWT validation is stateless (signature check), but consent checks require database access. This must not violate the <300ms AISP latency target (PSD2-NF01).

**The core question:** How aggressively do we cache consent state, given that consents can be revoked at any time?

**Solution options:**

**(a) Check-on-every-request (database hit)**
Every API request queries the consent table. Use a connection pool and indexed queries for performance. No caching.

- Pros: Always correct. Revocation is immediate. Simplest to reason about.
- Cons: Database round-trip per request. Under high load, consent table becomes a hot spot.

**(b) Short-TTL cache with immediate invalidation on revocation**
Caffeine cache with ~30s TTL for consent lookups. When a consent is revoked (via the revocation API), the cache entry is explicitly evicted. Revocation is near-immediate (within-instance only — see multi-instance note).

- Pros: Reduces database load significantly. 30s staleness window only matters if a consent is revoked during that window AND the TPP makes a request during that window.
- Cons: Multi-instance deployments need cache coordination (or accept the staleness window). Adds cache management complexity.

**(c) Cache consent status in the JWT claims**
Include consent_id and consent_status in the JWT. Validate consent status from the token without a database hit. Revocation doesn't affect already-issued tokens (they expire in 15 minutes).

- Pros: Zero database hits for consent validation. Fastest possible.
- Cons: 15-minute window where revoked consents are still honored. This may violate PSD2 expectations (account holder revokes consent, but the TPP can still access data for up to 15 minutes). Probably unacceptable.

**Recommendation:** Option (a) after deeper analysis. Primary key lookup per request is sub-millisecond — no performance justification for caching. Fortress principle: no staleness window on a security-critical check.

**Decision:** DECIDED — Option (a). Database hit on every authenticated request. Consent lookup by primary key (consent_id), checking status=ACTIVE AND expires_at > now(). HikariCP connection pool sized for 200 concurrent requests (PSD2-NF05). Consent revocation immediately effective — no staleness window. Option (c) ruled out: 15-minute JWT-cached consent status unacceptable for PSD2 revocation requirements. Option (b) documented as upgrade path if performance profiling shows consent checks as bottleneck — localized change (swap service implementation), not architectural. Fortress principle: don't introduce staleness on security-critical checks without a measured performance need.

---

### C-12: Testability — Strategy Across Testing Levels

**Context:** A4 combines OAuth2 flows, SCA, exemption logic, Kafka publishing, and REST calls to A1/A6. Testing the full flow end-to-end is complex. We need a testing strategy that verifies security properties, not just functional correctness.

**The core question:** How do we structure tests so that both functional correctness and security properties are verified?

**Solution options:**

**(a) Four-level testing pyramid**
1. **Unit tests**: exemption engine, token lifecycle, consent validation logic — isolated, fast, high coverage
2. **Integration tests**: Spring Boot test slices — test OAuth2 flow with NAV mock-oauth2-server, test Kafka publishing with embedded Kafka, test PostgreSQL with Testcontainers
3. **Contract tests**: Pact tests for AISP and PISP API surfaces — verify response format matches what TPPs expect
4. **Security tests**: dedicated tests for each defense layer — tampered JWT rejected, expired consent returns 403, refresh token reuse triggers family revocation, rate limiting enforced, PKCE required

- Pros: Comprehensive. Security properties are explicitly tested, not assumed. Matches the testing tools already in the tech stack (JUnit 5, Testcontainers, Pact, mock-oauth2-server).
- Cons: Most test code to write and maintain. Some overlap between integration and security tests.

**(b) Integration-heavy approach**
Fewer unit tests, more integration tests that exercise the full stack (Spring Boot + PostgreSQL + Kafka). Security properties tested as part of integration scenarios ("submit payment without SCA → expect 403").

- Pros: Less test code. Tests are closer to real usage.
- Cons: Slower test suite. Harder to pinpoint failures. Security properties are implicit, not explicitly documented via tests.

**(c) Contract-first with security property tests**
Start from Pact contracts (what TPPs expect). Add security property tests (what attackers might try). Minimal unit tests — only for complex logic (exemption engine).

- Pros: Tests what matters most: API contracts (for TPPs) and security (for attackers). Minimal testing of internal implementation details.
- Cons: Less coverage of internal logic. Refactoring may break things without tests catching it.

**Recommendation:** Option (a). The four-level pyramid gives us the strongest guarantees, and the tools are already specified. Security tests should be an explicit, named test category — not just incidental assertions in integration tests.

**Decision:** DECIDED — Option (a). Four-level testing pyramid. (1) Unit tests: exemption engine handlers, consent validation, VRP bounds, token lifecycle, DN parsing — fast, no Spring context. (2) Integration tests: full OAuth2 flow (NAV mock-oauth2-server), payment state machine with crash recovery, Kafka outbox relay, key rotation lifecycle, A1/A6 resilience via WireMock — Spring Boot test slices + Testcontainers. (3) Contract tests: Pact for AISP, PISP, VRP API surfaces and error response formats. (4) Security property tests: explicit test class per defense layer — L1 (no cert → rejected), L2 (tampered/expired JWT → rejected, missing PKCE → rejected), L3 (revoked consent → 403, wrong account → 403), L4 (lockout survives restart, dynamic link immutable), L5 (JWE fields unreadable without TPP key), L6 (all security events produce audit records). Security tests organized by defense layer directly demonstrate fortress architecture from C-01.

---

### C-13: Rate Limiting — Scope and Distribution

**Context:** Rate limiting is mandated (KM-09: 100 req/min per TPP, configurable, HTTP 429 with Retry-After). Bucket4j is specified. The architectural question is about scope: per-instance or distributed, and whether different endpoints need different limits.

**The core question:** Is per-instance rate limiting sufficient, or do we need distributed rate limiting?

**Solution options:**

**(a) Per-instance Bucket4j (in-memory)**
Each A4 instance maintains its own rate limit counters. A TPP hitting two instances gets double the effective limit.

- Pros: Zero external dependencies. Fast. Simple.
- Cons: Inaccurate in multi-instance deployments. But for a portfolio single-instance deployment, this is moot.

**(b) Distributed Bucket4j (Redis-backed)**
Rate limit state shared across instances via Redis. Accurate regardless of instance count.

- Pros: Accurate in production. Demonstrates production-ready architecture.
- Cons: Adds Redis dependency. More latency per request (Redis round-trip). Over-engineering for portfolio.

**(c) Per-instance with documentation**
Implement per-instance (option a). Document that production requires distributed rate limiting (option b) and the configuration change needed.

- Pros: Portfolio-appropriate simplicity with production awareness documented.
- Cons: None significant.

**Recommendation:** Option (c). Implement in-memory for the portfolio. Document the production upgrade path.

**Decision:** DECIDED — Option (c). In-memory Bucket4j, per-TPP rate limit (100 req/min configurable per KM-09). Rate limit keyed by TPP client_id extracted from mTLS certificate or OAuth2 token. HTTP 429 with Retry-After header. Single per-TPP limit initially; per-endpoint-type limits (AISP vs PISP) as optional refinement. Production upgrade path documented: Redis-backed Bucket4j for distributed rate limiting across instances.

---

### C-14: Audit Trail — Implementation Strategy

**Context:** PSD2 requires full audit trails of all consent, SCA, token, and API access events. Every exemption decision must be logged with rationale. The cross-cutting audit requirement (SEC-07) mandates who/what/when/from-where/outcome. The Starter project (Project #4) provides an @Audited annotation.

**The core question:** How do A4's audit events get to Kafka (`audit.events` topic) and are they also stored locally?

**Solution options:**

**(a) Kafka-only audit trail**
All audit events published to `audit.events` Kafka topic. No local storage. Consumers build queryable stores as needed.

- Pros: Append-only by nature (Kafka). Single source of truth. Other services can consume audit events.
- Cons: Querying A4's audit history requires a separate consumer/store. If Kafka is down, audit events are lost (unless buffered — Transactional Outbox again).

**(b) Local PostgreSQL audit table + Kafka**
Audit events written to a local `audit_events` table AND published to Kafka. Local table enables A4-specific queries. Kafka enables system-wide aggregation.

- Pros: Query A4's audit history without external dependencies. Kafka provides cross-system view. Belt and suspenders.
- Cons: Dual write (same table + Kafka consistency issue). Can solve via Transactional Outbox (write to table, relay publishes to Kafka — but audit events are not as critical as payment instructions).

**(c) Structured logging + Kafka**
Audit events emitted as structured JSON log lines (for local visibility/debugging) AND published to Kafka (for durable, queryable storage). No separate audit table.

- Pros: Leverages existing logging infrastructure. Log aggregation tools (ELK, Loki) provide search. Kafka for durability.
- Cons: Logs are not a reliable audit store (can be rotated, truncated). Must rely on Kafka for the authoritative record.

**Recommendation:** Option (b). The local audit table gives A4 self-contained queryability. The Transactional Outbox pattern (already used for payment instructions) can be reused for audit events heading to Kafka. This aligns with the fortress principle: audit data should be durable locally even if Kafka is temporarily unavailable.

**Decision:** DECIDED — Option (b). Local PostgreSQL audit table + Kafka via outbox relay. Table: audit_events (event_id UUID, event_type ENUM, timestamp TIMESTAMPTZ, actor_type TPP/ACCOUNT_HOLDER/SYSTEM, actor_id, resource_type, resource_id, action, outcome SUCCESS/FAILURE/DENIED, detail JSONB, correlation_id, ip_address). Strictly append-only: repository interface exposes only save(), no update/delete. Audit events written in the same transaction as the action they record (ensures audit exists iff action happened). Outbox relay publishes to audit.events Kafka topic for cross-system aggregation. Queryable locally for incident investigation. Integrates with Platform Starter's @Audited annotation (Project #4). Fortress principle: audit data durable locally even during Kafka downtime.

---

*All 14 challenges resolved. These decisions form the foundation for the A4 architecture document.*

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
