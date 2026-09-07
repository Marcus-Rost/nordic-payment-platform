# Cross-Project Architecture Review — Checklist

*Purpose: Verify that the architecture across the master project and all 7 subprojects is correct, consistent, and complete before proceeding to detailed design and implementation.*
*Date: 2026-04-12*
*Scope: 76 documents, ~25,000 lines across payment_processing/ and 7 subproject folders*

---

## How to Use This Checklist

Each review item has:
- **What to verify** — the specific check
- **Where to look** — which documents to cross-reference
- **Why it matters** — what goes wrong if this is inconsistent

Items are grouped by category. Within each category, items are ordered by risk (highest impact first). Each item should be marked PASS, FAIL (with details), or N/A.

---

## Category 0: Intra-Subproject Internal Consistency

*Do each subproject's own documents agree with each other?*

This must be checked for every subproject (A1-A6 + Starter) individually.

### 0.1 — Decisions-to-Design Faithfulness

**What:** For each subproject, verify that the ARCHITECTURAL_DESIGN document faithfully reflects what ARCHITECTURE_DECISIONS decided. No decision should be silently overridden, contradicted, or ignored by the design.

**Where:** Each subproject's ARCHITECTURE_DECISIONS.md vs. its ARCHITECTURAL_DESIGN.md

**Why:** If DECISIONS says "Kafka-first" but DESIGN describes an outbox pattern, implementation will follow the design and silently violate the decision. This is especially dangerous because the documents were often written in sequence within a single session — later sections may drift from earlier decisions.

Specific checks per subproject:
- Does every decision ID referenced in the design match the actual decision text?
- Are configuration values (thresholds, timeouts, pool sizes) consistent between decisions and design?
- Are Kafka topic names, API endpoints, and data model fields identical in both documents?

### 0.2 — Requirements-to-Architecture Traceability

**What:** For each subproject, verify that every requirement in requirements.md has a corresponding architectural element in the design. Walk the requirement IDs systematically: e.g., for A1, check LED-F01 through LED-F42, LED-NF01 through LED-NF06, LED-S01 through LED-S07, LED-RES01 through LED-RES04. For A6, check VOP-R01 through VOP-S06.

**Where:** Each subproject's requirements.md vs. its ARCHITECTURAL_DESIGN.md

**Why:** An unaddressed requirement means either a gap in the architecture (it was forgotten) or an implicit decision to defer (which should be documented). Line-by-line traceability is the only way to be sure.

### 0.3 — Challenges-to-Decisions Completeness

**What:** For each subproject that has an ARCHITECTURE_CHALLENGES.md, verify that every challenge raised has a corresponding decision in ARCHITECTURE_DECISIONS.md. No challenge should be left open or unresolved.

**Where:** Each subproject's ARCHITECTURE_CHALLENGES.md vs. its ARCHITECTURE_DECISIONS.md

**Why:** An unresolved challenge is an unresolved architectural question. It will surface as a "what should we do here?" during implementation — exactly the wrong time to make architectural decisions.

### 0.4 — Data Model Consistency Within Each Subproject

**What:** Verify that data model descriptions (Avro schemas, database schemas, API request/response formats) are consistent between the requirements, decisions, and design documents within each subproject.

**Where:** Data model sections across all documents within each subproject

**Why:** The requirements may define a field as `transaction_id: UUID` while the design uses `txn_id: String`. If these refer to the same field, implementation will be ambiguous.

---

## Category 1: Structural Consistency

*Do all subprojects follow the same documentation structure and naming conventions?*

### 1.1 — Document Set Completeness

**What:** Every subproject (A1-A6 + Starter) should have the same set of architecture documents: `ARCHITECTURE_CHALLENGES.md`, `ARCHITECTURE_DECISIONS.md`, `ARCHITECTURE_DESIGN.md` (or `ARCHITECTURAL_DESIGN.md`), and `architecture_draft.md`.

**Where:** `*/architecture/` directories for all 7 subprojects

**Why:** A missing document means decisions or design details for that subproject exist only in conversation history, not in a durable artifact. The A2 (Clearing Engine) inventory shows only `architecture_draft.md` and `ARCHITECTURE_DESIGN.md` — no CHALLENGES or DECISIONS file. Is this intentional or an omission?

### 1.2 — File Naming Convention

**What:** Architecture design files should use a consistent name across all subprojects. Currently there is a mix: some use `ARCHITECTUR_DESIGN.md` (typo — missing E), some use `ARCHITECTURE_DESIGN.md`, and at least one uses `ARCHITECTURAL_DESIGN.md`.

**Where:** All `*/architecture/` directories

**Why:** Inconsistent naming makes navigation harder and creates confusion about whether two differently-named files serve the same purpose. The TOC.md and NAVIGATION.md may reference incorrect filenames.

### 1.3 — Document Header Consistency

**What:** Each architecture document should have a consistent header format: title, version/date, input references, and master architecture reference.

**Where:** First 10 lines of every architecture document

**Why:** A document without a date could be stale. A document without input references is hard to trace back to requirements.

---

## Category 2: Master-to-Subproject Decision Propagation

*Does every subproject acknowledge, comply with, and correctly interpret the 32 master architecture decisions?*

### 2.1 — Master Decision Coverage

**What:** Each subproject's architecture should reference the relevant master decisions. Verify that no subproject silently ignores a master decision that applies to it.

**Where:** Master `ARCHITECTURE_DECISIONS.md` (32 decisions: A.1a through I.2a) vs. each subproject's DECISIONS and DESIGN documents

**Why:** A subproject that ignores a master decision (e.g., A.1a zone model, E.1a error format, B.2a dual-write strategy) will implement an inconsistent architecture. The master decisions are the system's constitution — they must be applied everywhere.

Key master decisions that apply to ALL subprojects:
- A.1a: Three-zone model with HMAC + mTLS
- A.1b: Deny-all default in Starter
- E.1a: RFC 9457 error format
- E.1b: Kafka error classification (retryable / DLQ / DLQ+alert)
- E.1c: Exception hierarchy (DomainException + InfrastructureException)
- E.2a: Micrometer Tracing via Starter
- H.1a: Rolling deployment with Cooperative Sticky Assignor
- H.2a: Env vars for infrastructure, application.yml for operational params
- I.1a: @ConfigurationProperties with Bean Validation

### 2.2 — Dual-Write Strategy Per Service

**What:** Master decision B.2a assigns a specific dual-write strategy to each service. Verify each subproject's architecture implements the assigned strategy.

**Where:** Master B.2a table vs. each subproject's DESIGN document

**Why:** Using the wrong dual-write strategy can cause data loss (Kafka publish succeeds but DB write fails, or vice versa).

Expected mapping:
| Service | Master Decision | To verify in subproject |
|---------|----------------|------------------------|
| A1 | Kafka-first | Event sourcing → Kafka is source of truth, PostgreSQL projections derived |
| A2 | Transactional Outbox (batch); Kafka Streams internal (instant) | Spring Batch uses outbox; Kafka Streams uses exactly_once_v2 |
| A3 | Transactional Outbox | R2P status updates in DB + outbox relay to Kafka |
| A4 | Transactional Outbox | Consent/payment status in DB + outbox relay to Kafka |
| A5 | Kafka Streams internal (instant); Transactional Outbox (case management) | Scoring via KStreams; case management API uses outbox |
| A6 | Not applicable | No Kafka writes in payment flow; audit is fire-and-forget |

### 2.3 — Deviations Documented

**What:** Where a subproject consciously deviates from a master decision, the deviation must be explicitly documented with justification (not silently ignored).

**Where:** Each subproject's DECISIONS document — look for deviation notes

**Why:** A6's TTL-only cache invalidation deviates from VOP-S05 ("invalidated immediately"). This deviation is documented. Verify all other subprojects document their deviations similarly.

---

## Category 3: Inter-Service Interface Contracts

*Do producers and consumers agree on every shared interface (Kafka topics, REST APIs, message schemas)?*

### 3.1 — Kafka Topic Registry

**What:** Verify that every Kafka topic listed in `NAVIGATION.md` (Section: Kafka Topics) has a consistent definition across the producing and consuming subprojects. Check: topic name, message format, key strategy, and partitioning.

**Where:** `payment_processing/NAVIGATION.md` (topic registry) vs. each subproject's DESIGN document

**Why:** A mismatch in topic name or message format between producer and consumer causes silent message loss or deserialization failure.

Topics to verify (from NAVIGATION.md):
| Topic | Producer | Consumer |
|-------|----------|----------|
| `payment.instructions` | A3, A4 | A2 (batch) |
| `payment.instant-instructions` | A3, A4 | A2 (instant) |
| `payment.fraud-check-request` | A2 | A5 |
| `payment.instant-fraud-check-request` | A2 | A5 |
| `payment.fraud-check-response` | A5 | A2 |
| `payment.instant-fraud-check-response` | A5 | A2 |
| `payment.settlement-events` | A2 | A1 |
| `payment.instant-settlement-events` | A2 | A1 |
| `payment.status-updates` | A2 | A3, A4 |
| `payment.balance-reservation` | A2 | A1 |
| `payment.balance-reservation-response` | A1 | A2 |
| `payment.reconciliation-results` | A1 | A2, monitoring |
| `ledger.events` | A1 | A1 (projections) |
| `sanctions.list-updates` | External | A5 |
| `account.freeze-commands` | A5 | A1 |
| `audit.events` | All | Audit consumer |

### 3.2 — REST API Contracts Between Services

**What:** Verify that every inter-service REST call has matching endpoint definitions in both the calling and serving subproject.

**Where:** Each subproject's API design section in their DESIGN document

**Why:** A caller expecting `GET /internal/accounts/{iban}/holder-name` while the server exposes `GET /internal/accounts/{iban}/holder` causes a 404 that may only surface during integration testing.

Known inter-service REST calls:
| Caller | Server | Endpoint | Purpose |
|--------|--------|----------|---------|
| A6 | A1 | `GET /internal/accounts/{iban}/holder-name` | VoP name lookup |
| A3/A4 | A6 | `POST /internal/vop/verify` | VoP verification |
| A4 | A1 | Account/balance queries | PSD2 AISP |
| A2 | A1 | Balance reservation queries | Settlement |

### 3.3 — Avro Schema Agreement

**What:** The `PaymentInstruction` Avro schema is produced by A3/A4 and consumed by A2. The `vopResult` field (added for VoP) must be defined identically in all three subprojects. Similarly, other shared Avro schemas (settlement events, fraud check request/response) must agree.

**Where:** Data model sections in each subproject's DESIGN document and requirements

**Why:** Avro schema mismatch causes deserialization failure. Even with Schema Registry compatibility checks, semantic mismatches (same field name, different meaning) can cause data corruption.

### 3.4 — Authentication Mechanism Agreement

**What:** For each inter-service call (REST and Kafka), verify that both sides agree on the authentication mechanism.

**Where:** Security sections of each subproject's DESIGN document

**Why:** If A6 expects mTLS from A3/A4 but A3/A4 tries to call with a bearer token, the call fails.

Expected authentication:
| Interface | Mechanism | Both sides must agree on |
|-----------|-----------|--------------------------|
| Internal REST (service-to-service) | mTLS | Certificate chain, CN/SAN validation |
| External REST (PSP → A6 responder) | mTLS or API key | Certificate requirements or API key format |
| External REST (TPP → A4) | OAuth2 + mTLS | Token format, scopes, mTLS requirements |
| Kafka | SASL + HMAC on messages | SASL mechanism, HMAC key ID in headers |

### 3.5 — Bilateral Interface Verification for Critical Pairs

**What:** For the most consequential inter-service interfaces, read both sides independently and confirm they describe the same protocol. Don't just check names match — verify that the interaction pattern (synchronous vs. asynchronous, request-response vs. fire-and-forget, timeout expectations, error handling) is described identically on both sides.

**Where:** Both subprojects' DESIGN documents for each pair

**Why:** Each subproject's architecture was designed in a separate session. Even when referencing the master architecture, the two sides may have made different assumptions about how the interface works. This is especially critical for interfaces that use non-obvious patterns (e.g., request-response over Kafka rather than REST).

Critical bilateral pairs to verify:
| Pair | Interface | What Could Go Wrong |
|------|-----------|-------------------|
| **A2 ↔ A1** (reservation) | Request-response over Kafka (`payment.balance-reservation` / `payment.balance-reservation-response`) | A2 sends a reservation request and expects a response within N ms. A1 must consume, process, and publish a response. If A2's timeout doesn't match A1's processing time, reservations silently fail. If A1's response topic name doesn't match A2's subscription, the response is never received. |
| **A2 ↔ A5** (fraud check) | Request-response over Kafka (`payment.fraud-check-request` / `payment.fraud-check-response`) | A2 sends a fraud check and waits. A5 must respond within the time budget. The response message must include the `transactionId` so A2 can correlate. If the correlation field name differs, A2 can't match response to request. |
| **A2 → A1** (settlement) | Fire-and-forget Kafka (`payment.settlement-events`) | A2 publishes settlement events. A1 consumes and applies to ledger. If A2 assumes settlement is confirmed but A1 rejects (insufficient balance after reservation expired), the system is inconsistent. |
| **A3/A4 → A6** (VoP) | Synchronous REST (`POST /internal/vop/verify`) | A3/A4 call A6 before publishing to Kafka. If A6's request format doesn't match what A3/A4 sends, VoP fails. If A3/A4's timeout is shorter than A6's worst-case latency, VoP is prematurely aborted. |
| **A3/A4 → A2** (payment instructions) | Fire-and-forget Kafka (`payment.instructions` / `payment.instant-instructions`) | A3/A4 publish payment instructions with VoP result attached. A2 consumes. The `vopResult` field must be present and correctly populated. |

---

## Category 4: End-to-End Payment Flow Consistency

*Can you trace a payment from initiation to settlement through all subproject architectures without hitting a gap or contradiction?*

### 4.1 — Instant Payment Happy Path (UC08)

**What:** Trace the instant payment flow step by step through all subproject architectures. At each handoff, verify: the message format, the expected response, the timeout, and the error handling.

**Where:** Master UC08 vs. A3/A4 DESIGN (Phase 1) → A6 DESIGN (VoP) → A2 DESIGN (Phase 2) → A5 DESIGN (fraud) → A1 DESIGN (settlement)

**Why:** This is the most complex flow, touching all 6 services. Any gap here will surface as a bug during integration.

### 4.2 — Instant Payment Failure Paths

**What:** For each failure scenario in UC08 (VoP timeout, fraud timeout, sanctions hit, balance insufficient, A1 unavailable, 7-second hard limit), verify that the failure is handled consistently across all involved subprojects.

**Where:** Resilience sections of each subproject's DESIGN, plus master C.1a (Saga pattern) and C.2a (time budget)

**Why:** Failure paths are where inconsistencies hide. If A2 expects A5 to respond within 500ms but A5's architecture allows 800ms for combined fraud+sanctions, the timeout will fire incorrectly.

### 4.3 — Payment State Machine Alignment

**What:** Verify that the payment state names and transitions described in each subproject are consistent with the master state machine (B.1a: Phase 1 → Phase 2 → Phase 3 ownership).

**Where:** State machine sections in A3, A2, A1 DESIGN documents vs. master B.1a

**Why:** If A3 uses `PAYMENT_INITIATED` but A2 expects `INSTRUCTION_RECEIVED`, the state handoff is ambiguous.

### 4.4 — Batch Payment Flow (UC04)

**What:** Same as 4.1/4.2 but for the batch clearing cycle. Verify consistency across A2 (batch path), A5 (batch fraud scoring), A1 (batch settlement), A6 (VoP for batch).

**Where:** Master UC04 vs. relevant subproject DESIGN documents

**Why:** The batch path uses different mechanisms (Spring Batch, netting, different Kafka topics) and may have inconsistencies that the instant path doesn't.

---

## Category 5: Timing and Performance Budget

*Do the per-service latency SLAs compose correctly within the 5-second instant payment budget?*

### 5.1 — Time Budget Arithmetic

**What:** Sum the per-service latency targets from each subproject's architecture and verify the total is within 5 seconds (execution target) with adequate safety margin.

**Where:** Master C.2a static budget vs. each subproject's NF requirements and architecture

**Why:** If the sum exceeds the budget, the system cannot meet the EPC SLA.

Expected budget (from master C.2a):
```
VoP (A6):            500ms
A2 receive+validate:  200ms
Balance reserve (A1): 200ms
Fraud+sanctions (A5): 800ms
Settlement (A2):      300ms
Ledger confirm (A1):  200ms
Confirmation:         200ms
Network/Kafka:        600ms
Safety margin:      2,000ms
Total:              5,000ms
```

Verify each subproject's NF requirements match these allocations.

### 5.2 — Timeout Consistency

**What:** For each synchronous call between services, verify that the caller's timeout matches the callee's expected processing time. The caller's timeout should be >= the callee's processing time + network overhead.

**Where:** Resilience/configuration sections of each subproject's DESIGN

**Why:** If A6 sets a 200ms timeout on A1, but A1's architecture says balance queries can take 300ms under load, the timeout will fire during normal operation.

### 5.3 — Circuit Breaker Configuration Compatibility

**What:** Where services use circuit breakers on downstream dependencies, verify that the circuit breaker configuration (failure threshold, window size, wait duration) is reasonable given the downstream service's actual failure characteristics.

**Where:** Resilience sections of each subproject's DESIGN

**Why:** A circuit breaker that opens too eagerly causes unnecessary degradation. One that opens too slowly doesn't protect the caller.

---

## Category 6: Security Architecture Consistency

*Is the security model applied uniformly across all subprojects?*

### 6.1 — Trust Boundary Compliance

**What:** Verify that each subproject correctly implements its role in the three-zone model (A.1a). DMZ services (A3 external endpoints, A4, A6 responder) handle external authentication. Internal services (A1, A2, A5) trust mTLS + HMAC.

**Where:** Security sections of each subproject's DESIGN vs. master A.1a

**Why:** A service in the internal zone that accidentally exposes an unauthenticated endpoint creates a security hole.

### 6.2 — Kafka HMAC Signing

**What:** Master decision KM-06 requires HMAC signing on Kafka messages. Verify that every Kafka producer in every subproject signs messages and every consumer verifies signatures.

**Where:** Kafka/messaging sections of each subproject's DESIGN

**Why:** Without consistent HMAC signing, a compromised Kafka broker could inject fraudulent messages.

### 6.3 — Platform Starter Adoption

**What:** Verify that every subproject declares the Platform Starter as a dependency and uses its auto-configured security (deny-all, audit logging, metrics, tracing).

**Where:** Technology stack / dependency sections of each subproject's DESIGN

**Why:** A service that doesn't use the Starter loses the "security by construction" guarantee.

### 6.4 — PII Handling Consistency

**What:** Verify that every service that handles PII (account holder names, IBANs, addresses) follows the same encryption/minimization approach (master F.1a).

**Where:** Data architecture / privacy sections of each subproject's DESIGN

**Why:** Inconsistent PII handling means one service could leak data that another service carefully protects.

---

## Category 7: Data Architecture Consistency

### 7.1 — Crypto-Shredding Chain

**What:** Trace the crypto-shredding lifecycle across all services. When an account is deleted in A1 and its keys are destroyed: what happens in A2 (clearing history), A3 (R2P records), A4 (consent records), A5 (fraud cases), A6 (query logs)? Does each service handle the shredded PII correctly?

**Where:** Privacy/GDPR sections of each subproject's DESIGN vs. master F.1a/F.1b

**Why:** If A1 shreds PII but A3 still holds unencrypted R2P records with the same data, the GDPR erasure is incomplete.

### 7.2 — Idempotency Key Chain

**What:** Verify that the hierarchical idempotency key scheme (master B.3a) is consistently implemented. A3/A4 generates `transactionId`, A2 derives `fraudCheckRequestId` and `settlementEventId`, each consumer deduplicates by the correct key.

**Where:** Idempotency sections of each subproject's DESIGN vs. master B.3a

**Why:** Incorrect deduplication key = double-processed payments or missed payments.

### 7.3 — Database-Per-Service Isolation

**What:** Verify that each service uses its own PostgreSQL database (or schema) per master OPS-05. No service should directly access another service's database.

**Where:** Data architecture sections of each subproject's DESIGN, port assignments in SUBSYSTEM_MAP.md

**Why:** Shared databases create coupling, lock contention, and make independent deployment impossible.

---

## Category 8: Resilience and Recovery

### 8.1 — Recovery Procedure Per Service

**What:** Verify that each service implements the correct recovery procedure on restart (master B.1b: Kafka replay + database recovery scan).

**Where:** Resilience/recovery sections of each subproject's DESIGN

**Why:** A service that doesn't recover correctly after a crash can leave payments in limbo.

### 8.2 — Investigation Queue Integration

**What:** Verify that stuck payments from all services can be surfaced through the investigation queue in A2 (master B.4a).

**Where:** A2 DESIGN (investigation queue implementation) vs. each other service's stuck-payment detection

**Why:** Stuck payments that are invisible to operators accumulate until reconciliation catches them — potentially hours later.

### 8.3 — Graceful Shutdown Consistency

**What:** Verify that all services implement graceful shutdown: drain in-flight requests, complete or abort in-flight Kafka processing, then terminate (master OPS-02, RES-10).

**Where:** Deployment sections of each subproject's DESIGN

**Why:** Ungraceful shutdown during rolling deployment causes lost messages or partial transactions.

---

## Category 9: Observability Consistency

### 9.1 — Metric Naming Convention

**What:** Verify that metrics across all subprojects follow a consistent naming convention (namespace, tags, types).

**Where:** Observability sections of each subproject's DESIGN

**Why:** Inconsistent metric names make cross-service dashboards and alerts impossible to build.

### 9.2 — End-to-End Tracing Completeness

**What:** Verify that trace context is propagated through every hop in the payment flow: REST → REST, REST → Kafka → REST. Every service must participate in the trace.

**Where:** Observability/tracing sections of each subproject's DESIGN

**Why:** A gap in tracing means an operator cannot see the full latency breakdown for a payment that missed the SLA.

### 9.3 — Alert Threshold Alignment

**What:** Verify that alert thresholds in each service's architecture are consistent with the system-level SLAs (e.g., A6's p95 > 100ms alert vs. A2's end-to-end p99 > 5s alert).

**Where:** Alerting sections of each subproject's DESIGN vs. master E.2b

**Why:** Conflicting thresholds cause either alert fatigue (too sensitive) or missed SLA violations (too lenient).

---

## Category 10: Testing Architecture Consistency

### 10.1 — Contract Test Coverage

**What:** Verify that every inter-service boundary (REST and Kafka) is covered by a Pact contract test. The producer-side and consumer-side tests must reference the same contract.

**Where:** Testing sections of each subproject's DESIGN vs. master G.1a

**Why:** An untested boundary is where integration bugs hide.

### 10.2 — Test Data Consistency

**What:** Verify that test data used across subprojects is consistent. The same test IBANs, account IDs, and names should appear in all services' test fixtures to enable end-to-end testing.

**Where:** Testing sections and demo script references in each subproject's DESIGN vs. master G.2a

**Why:** If A3's test fixture uses IBAN "NO93 8601 1117 947" but A1's test data doesn't include that account, the end-to-end demo fails.

---

## Category 11: Deployment Consistency

### 11.1 — Port Assignment Uniqueness

**What:** Verify that port assignments in SUBSYSTEM_MAP.md are unique and consistent with each subproject's deployment configuration.

**Where:** `payment_processing/SUBSYSTEM_MAP.md` vs. each subproject's deployment section

**Why:** Port conflicts prevent services from running simultaneously in docker-compose.

### 11.2 — Database Migration Compatibility

**What:** Verify that all subprojects use the expand-contract migration pattern (master H.1a) to ensure backward-compatible database changes during rolling deployment.

**Where:** Deployment sections of each subproject's DESIGN

**Why:** A migration that breaks running instances during rolling deployment causes downtime.

---

## Category 12: Requirements Traceability

### 12.1 — Must Requirements Coverage

**What:** For every Must-priority requirement in every subproject's requirements.md, verify that the architectural design explicitly addresses it. No Must requirement should be unaccounted for.

**Where:** Each subproject's requirements.md vs. their DESIGN document

**Why:** An unaddressed Must requirement is a guaranteed compliance gap.

### 12.2 — Cross-Cutting Requirements in All Services

**What:** Verify that cross-cutting requirements from the master system_requirements.md (SEC-*, OPS-*, DATA-*) are addressed in every subproject, either directly or via the Platform Starter.

**Where:** Master system_requirements.md (Sections 5-8) vs. each subproject's DESIGN

**Why:** A security requirement (e.g., SEC-07: structured audit logging) that is missing from one service creates an audit gap.

### 12.3 — Should Requirements — Explicitly Addressed or Deferred

**What:** For every Should-priority requirement, verify it is either addressed in the architecture or explicitly listed as deferred with justification.

**Where:** Each subproject's requirements.md vs. their DESIGN document

**Why:** Silently ignoring a Should requirement creates ambiguity about whether it was considered.

---

## Category 13: Regulatory Compliance Verification

### 13.1 — EU Instant Payment Regulation (2024/886)

**What:** Verify that all Article 5c mandates (VoP), Article 5a (instant payment availability), and Article 5d (sanctions screening) are reflected in the architecture.

**Where:** A6 DESIGN (VoP), A2 DESIGN (instant payments), A5 DESIGN (sanctions)

**Why:** Regulatory non-compliance is not a bug — it's a legal liability.

### 13.2 — PSD2 / PSD3 Compliance

**What:** Verify that A4's architecture implements all PSD2 mandates: SCA, consent management, AISP/PISP, and SCA exemptions.

**Where:** A4 DESIGN vs. PSD2 requirements in A4 requirements.md

**Why:** PSD2 compliance is mandatory for operating as a payment service provider.

### 13.3 — AML / Sanctions Completeness

**What:** Verify that sanctions screening cannot be bypassed for any payment path (batch or instant). Master requirement: "sanctions failure = payment rejection, never auto-pass."

**Where:** A5 DESIGN (sanctions pipeline), A2 DESIGN (sanctions integration), master constraint

**Why:** A sanctions screening gap is a criminal liability, not just a compliance gap.

### 13.4 — GDPR Data Minimization

**What:** Verify that every service minimizes PII collection and storage according to the principle of data minimization. No service should store PII it doesn't need.

**Where:** Data architecture sections of each subproject's DESIGN

**Why:** Over-collecting PII increases breach impact and GDPR liability.

---

## Category 14: Naming and Terminology

### 14.1 — Service Names

**What:** Verify that service names (A1-A6) and their descriptive names are used consistently across all documents. No document should refer to "the clearing service" in one place and "the settlement engine" in another when meaning A2.

**Where:** All documents

**Why:** Inconsistent naming causes confusion during implementation ("which service is being discussed here?").

### 14.2 — Status/Result Enumerations

**What:** Verify that enumeration values (payment states, VoP results, fraud results, R2P lifecycle states) are spelled consistently across all documents that reference them.

**Where:** Data model and API sections across all subprojects

**Why:** "PAYMENT_INITIATED" vs "INITIATED" vs "PAYMENT_CREATED" referring to the same state causes mapping bugs.

### 14.3 — Configuration Property Names

**What:** Where multiple services share configuration concepts (e.g., reservation timeout, SCA exemption thresholds), verify the property names and default values are consistent.

**Where:** Configuration sections of each subproject's DESIGN vs. master H.2a shared-config guidance

**Why:** Inconsistent shared parameters (A1 uses 30s reservation timeout, A2 assumes 60s) cause silent failures.

---

## Category 15: Overlapping and Duplicated Functionality

*Is there functionality implemented in multiple subprojects that should be factored out into the Platform Starter or a shared library?*

### 15.1 — Platform Starter vs. Per-Service Implementation

**What:** Verify that cross-cutting functionality (audit logging, security headers, metrics, health checks, error handling, correlation ID propagation, tracing) is implemented in the Platform Starter and not re-implemented independently in each subproject.

**Where:** Platform Starter DESIGN (what it provides) vs. each subproject's DESIGN (what it uses vs. what it implements locally)

**Why:** Duplicated cross-cutting logic leads to inconsistent behavior across services and maintenance burden when the logic needs to change. If A5 implements its own audit logging while the Starter also provides it, the two implementations may diverge.

Specific areas to check:
- Audit event publishing — Starter provides `AuditLogger`? Or does each service build its own Kafka producer?
- Error response formatting — Starter provides RFC 9457 global exception handler? Or does each service implement its own?
- HMAC signing/verification on Kafka — Starter provides interceptor? Or each service does it independently?
- Health/readiness/startup probes — Starter auto-configures? Or each service configures its own?
- Correlation ID propagation — Starter handles? Or each service implements its own?

### 15.2 — Shared Domain Model Boundaries

**What:** Verify that domain concepts shared across services (PaymentInstruction, VoPResult, FraudCheckRequest, SettlementEvent) are defined in a shared library (`payment-domain-model`) and not independently defined in each subproject.

**Where:** Data model sections across all subprojects — look for duplicated class/schema definitions

**Why:** If A3 defines `PaymentInstruction` with field `beneficiary_name` and A2 defines it with `beneficiaryName`, the Avro schema won't match. A shared definition prevents this.

### 15.3 — Responsibility Overlap Between Services

**What:** Verify that each piece of business logic lives in exactly one service. No two services should independently implement the same logic.

**Where:** Functional descriptions across all subprojects' DESIGN documents

**Why:** Duplicated logic means two places that can diverge, two places to fix bugs, and two places that can produce different results.

Areas at risk of overlap:
| Functionality | Expected Owner | Risk of Duplication |
|---|---|---|
| IBAN validation | A6 (input validation layer) + Platform Starter (reusable validator) | Could also be in A3, A4 for their own input validation |
| Name normalization (diacritics, whitespace) | A6 (matching engine) | Could leak into A1 if A1 pre-normalizes stored names |
| Sanctions screening logic | A5 (exclusive owner) | A2 might be tempted to add basic screening as a "fast path" |
| Payment status tracking | A2 (Phase 2 orchestrator) | A3 tracks its own R2P states — verify these don't duplicate A2's states |
| Rate limiting | Per-service (Bucket4j) | Verify the Starter provides a reusable rate limiting component rather than each service implementing its own |
| Circuit breaker configuration | Per-service (Resilience4j) | Verify the Starter provides common configuration patterns rather than each service configuring from scratch |

### 15.4 — Exception Hierarchy Ownership

**What:** Verify that the shared exception hierarchy (master E.1c: `DomainException` + `InfrastructureException`) is defined in one place (`payment-domain-model`) and all subprojects reference it, not redefine it.

**Where:** Exception handling sections across all subprojects vs. master E.1c

**Why:** If A5 defines its own `SanctionsHitException` that doesn't extend the shared `DomainException`, the Starter's global exception handler won't catch it, and the RFC 9457 error format won't be applied.

---

## Review Execution Plan

The review should be executed in this order:

**Phase 1 — Internal consistency (each subproject in isolation)**
1. **Category 0 (Intra-subproject)** — verify each subproject's own documents agree with each other. Do this for all 7 subprojects. This is the foundation — if a subproject contradicts itself, cross-project checks will produce confusing results.

**Phase 2 — Structural and foundational**
2. **Category 1 (Structural)** — document completeness, naming conventions
3. **Category 2 (Master propagation)** — verify all subprojects comply with master decisions

**Phase 3 — Integration (highest risk)**
4. **Category 3 (Interfaces)** — Kafka topics, REST APIs, Avro schemas, bilateral verification of critical pairs
5. **Category 4 (Payment flows)** — trace end-to-end flows through all subproject architectures
6. **Category 5 (Timing)** — verify per-service SLAs compose within the 5-second budget

**Phase 4 — Quality attributes**
7. **Category 6 (Security)** — trust boundaries, HMAC, Starter adoption
8. **Category 7 (Data)** — crypto-shredding, idempotency, database isolation
9. **Category 8 (Resilience)** — recovery procedures, investigation queues, graceful shutdown
10. **Category 15 (Duplication)** — overlapping functionality, shared model boundaries

**Phase 5 — Operational and compliance**
11. **Categories 9-11 (Observability, Testing, Deployment)** — operational readiness
12. **Category 12 (Requirements)** — traceability against all requirement IDs
13. **Categories 13-14 (Regulatory, Naming)** — compliance and terminology

Each category should be reviewed by reading the relevant documents in parallel and cross-checking. Findings should be recorded as PASS/FAIL with specific file locations and line numbers for any issues found.

**Note:** Phase 1 (Category 0) is critical to do first — it catches internal contradictions within each subproject before we start comparing across subprojects. A decision that says "Kafka-first" while the design describes an outbox is a bug that would confuse every subsequent cross-project check.
