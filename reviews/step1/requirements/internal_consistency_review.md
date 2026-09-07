# Internal Consistency Review: Nordic Payment Processing Infrastructure

**Reviewer:** Claude Opus 4.6
**Date:** 2026-04-11
**Scope:** Cross-document consistency analysis across system requirements, 10 use cases, 7 sub-project requirements, and extension documents
**Method:** Systematic cross-referencing of requirement IDs, status enums, numerical values, Kafka topics, API endpoints, flow ordering, and terminology

---

## Executive Summary

The requirements corpus contains **27 cross-document inconsistencies**, of which **12 are high severity** (would cause implementation defects if developers work from different documents), **10 are medium severity** (create confusion but are resolvable with clarification), and **5 are low severity** (cosmetic or unlikely to cause defects).

The most concerning pattern is that **use cases and sub-project requirements diverge on specifics** — threshold values, API endpoints, status enums, and flow ordering. This is a natural consequence of a corpus that evolved iteratively, but it means that a developer building from the sub-project requirements alone will produce a system that does not match the use case descriptions, and vice versa.

### Critical Recommendation

Establish a single source of truth for each data element:
- **Status enums and data models:** Define once in a shared `payment-domain-model` specification and reference from all documents.
- **Kafka topic topology:** The system_requirements.md section 9.1 should be authoritative; sub-project documents should reference it, not redefine topics.
- **Numerical thresholds:** Define in the sub-project requirements (the "how") and reference from use cases (the "what happens"), not the reverse.
- **API endpoint paths:** Define in sub-project requirements; use cases should cite the requirement ID, not repeat the path.

---

## Table of Contents

1. [Contradictions](#1-contradictions)
2. [Dangling References and Missing Status Values](#2-dangling-references)
3. [Ambiguities](#3-ambiguities)
4. [Terminology Inconsistencies](#4-terminology)
5. [Missing Integration Points](#5-missing-integrations)
6. [Priority Conflicts](#6-priority-conflicts)
7. [Numerical Inconsistencies](#7-numerical)
8. [Flow Ordering Issues](#8-flow-ordering)
9. [Consolidated Fix List](#9-fix-list)

---

## 1. Contradictions

These are cases where two documents make incompatible statements about the same thing.

---

### CON-1: Velocity Rule VEL-02 — Absolute vs. Relative Threshold (HIGH)

**Document A:** `payment_processing/use_cases/UC05_fraud_detection.md`
> VEL-02: "Single transaction > NOK 100,000 from account with no prior transaction > NOK 10,000"

**Document B:** `01_Real_time_Transaction_Fraud_Detection_Engine/requirements/requirements.md`
> VEL-02: "Single transaction > 20x account's average transaction amount"

**Impact:** These are fundamentally different rules. The UC05 version uses absolute thresholds (NOK 100,000 / NOK 10,000) and would never fire for corporate accounts with large transaction histories. The A5 requirements version uses a relative threshold (20x average) which is account-adaptive. An implementation following one document will behave differently from one following the other.

**Fix:** The A5 requirements version (relative threshold) is the better design. Update UC05 to match, or explicitly document that VEL-02 uses a relative threshold and the use case example illustrates one instantiation.

---

### CON-2: Fraud Score Aggregation Weights — Anomaly 0.4 vs. 0.3 (HIGH)

**Document A:** `payment_processing/use_cases/UC05_fraud_detection.md`
> "Combined score = weighted_average(velocity=0.3 x 0.3, anomaly=0.4 x 0.4, ml=0.25 x 0.3) = 0.325"

Anomaly weight = **0.4**

**Document B:** `01_Real_time_Transaction_Fraud_Detection_Engine/requirements/requirements.md`
> "Default weights: w_velocity=0.3, w_anomaly=0.3, w_ml=0.3"

Anomaly weight = **0.3**

**Impact:** Using the A5 requirements weights on the UC05 example: 0.3x0.3 + 0.3x0.4 + 0.3x0.25 = 0.285. Using UC05 weights: 0.325. Both produce AUTO_PASS in this example, but for borderline scores near 0.5 the weight difference could flip the decision tier (PASS vs. MANUAL_REVIEW).

**Fix:** Adopt a single authoritative weight set in the A5 requirements. Update UC05 worked example to use those weights.

---

### CON-3: Sanctions Hit — Does Fraud Scoring Still Run? (HIGH)

**Document A:** `payment_processing/use_cases/UC05_fraud_detection.md`
> "SANCTIONS_HOLD — payment immediately HELD. Overrides ALL fraud scoring. Fraud scoring does NOT run."

**Document B:** `01_Real_time_Transaction_Fraud_Detection_Engine/requirements/requirements.md`
> Combined score formula includes all components simultaneously: `combined_score = w_sanctions * sanctions_result + w_velocity * velocity_score + ...` with `w_sanctions=1.0 (overrides everything)`

**Impact:** UC05 says the pipeline short-circuits — sanctions hit means fraud layers are skipped entirely. The A5 requirements describe a combined formula where all layers always run but sanctions dominates numerically. This affects: resource usage (unnecessary ML inference on sanctioned payments), latency (extra processing), and audit trail content (fraud scores present or absent for sanctioned payments).

**Fix:** The UC05 interpretation (short-circuit) is more efficient and operationally correct — there is no value in fraud-scoring a sanctions-blocked payment. Update the A5 requirements to explicitly state: "If sanctions result is POTENTIAL_HIT or CONFIRMED, fraud scoring layers are bypassed. The decision is SANCTIONS_HOLD regardless of fraud score."

---

### CON-4: Case Management API Endpoint Path (HIGH)

**Document A:** `payment_processing/use_cases/UC05_fraud_detection.md` (fraud alerts)
> `PUT /api/v1/fraud/alerts/{id}` with `{decision: CONFIRMED_FRAUD, ...}`

**Document B:** `01_Real_time_Transaction_Fraud_Detection_Engine/requirements/requirements.md`
> `PUT /api/v1/fraud/alerts/{id}/resolve`

**Impact:** The `/resolve` path segment is present in the A5 requirements but absent in the UC05 fraud alert examples. (UC05 does use `/resolve` for sanctions alerts.) An implementation built from UC05 would expose a different endpoint than one built from A5 requirements. Results in 404 errors during integration testing.

**Fix:** Standardise on `PUT /api/v1/fraud/alerts/{id}/resolve` across all documents.

---

### CON-5: Sanctions List Update Latency — 1 Hour vs. 5 Minutes (HIGH)

**Within the same document:** `01_Real_time_Transaction_Fraud_Detection_Engine/requirements/requirements.md`

- SAN-F07: "List ingestion: sanctions lists updated within **1 hour** of publication" (Must)
- NFR-06: "Sanctions list update ingestion: **< 5 minutes** from download to active"

**Impact:** Internal contradiction. SAN-F07 is the publication-to-active window (1 hour, including download time). NFR-06 appears to be the processing time only (download already complete to active in memory). But both descriptions say "from download to active" / "updated within X of publication," making the distinction unclear.

**Fix:** Clarify: SAN-F07 covers the end-to-end window (publication by EU/UN to active in screening engine, including polling interval and download). NFR-06 covers processing time only (parsing downloaded file, building Bloom filter, swapping into active screening). Rename NFR-06 to "Sanctions list processing time: < 5 minutes from file download completion to active screening."

---

### CON-6: Balance Reservation Confirmed State — "SETTLED" vs. "BALANCE_CONFIRMED" (HIGH)

**Document A:** `payment_processing/requirements/system_requirements.md` A1-F11:
> "Three-state: AVAILABLE → RESERVED → **SETTLED** (or RELEASED)"

**Document B:** `09_Event_Sourced_Account_Ledger/requirements/requirements.md` (Avro schema):
> `"symbols": ["ACCOUNT_CREATED", "DEBIT", "CREDIT", "BALANCE_RESERVED", "BALANCE_CONFIRMED", "BALANCE_RELEASED", ...]`

**Impact:** The Avro event schema (the actual wire format) uses `BALANCE_CONFIRMED`. The system requirements use `SETTLED`. Any code that deserialises events and checks for `SETTLED` will miss `BALANCE_CONFIRMED` events. This is a production defect waiting to happen.

**Fix:** Adopt the Avro schema terminology as authoritative (it is the implementation contract). Update system_requirements.md A1-F11 to: "Three-state: AVAILABLE → RESERVED → CONFIRMED (or RELEASED)."

---

## 2. Dangling References and Missing Status Values

These are status values, topics, or endpoints used in one document but not defined in the document that owns them.

---

### DANG-1: `account.freeze-commands` Kafka Topic — No Consumer Defined (HIGH)

**Producer:** `01_Real_time_Transaction_Fraud_Detection_Engine/requirements/requirements.md` — A5 publishes to `account.freeze-commands` on CONFIRMED_FRAUD.

**Consumer:** Not specified. The A1 requirements (`09_Event_Sourced_Account_Ledger/requirements/requirements.md`) describe the FROZEN state (LED-F21, LED-F22) but never mention consuming this topic.

**System topology:** `payment_processing/requirements/system_requirements.md` section 9.1 lists this topic with "A5" as producer and "A1" as consumer — but this is only in the NAVIGATION.md Kafka cross-reference, not in A1's own requirements.

**Fix:** Add to A1 requirements: "LED-F23: Consume `account.freeze-commands` topic. On receipt, transition the specified account to FROZEN state. Log the originating alert_id and reason."

---

### DANG-2: `sanctions.list-updates` Kafka Topic — No Producer Defined (MEDIUM)

**Consumer:** A5 requirements specify consuming this topic.

**Producer:** No document specifies who publishes to it. Presumably the sanctions list ingestion scheduler within A5 itself, but this makes the Kafka topic unnecessary (an internal event within one service).

**Fix:** Either remove the topic (internal signal within A5, no Kafka needed) or specify the producer as an external adapter / scheduled task that publishes after downloading new list files.

---

### DANG-3: `REQUIRES_SCA` and `REQUIRES_CONFIRMATION` Payment Statuses (HIGH)

**Used in:** `payment_processing/use_cases/UC03_psd2_payment_initiation.md`
- Step 4: status `REQUIRES_SCA`
- AF-05: status `REQUIRES_CONFIRMATION`

**Defined in:** `02_Open_Banking_PSD2_API/requirements/requirements.md` PISP-F06:
> "Payment status: PENDING, PROCESSING, SETTLED, REJECTED"

**Impact:** The A4 requirements define four statuses. The UC03 use case introduces two more that are not in the A4 data model. An A4 implementation cannot return statuses it doesn't define.

**Fix:** Extend PISP-F06 status enum to: `REQUIRES_SCA, REQUIRES_CONFIRMATION, PENDING, PROCESSING, HELD, SETTLED, REJECTED, CANCELLED`. Update the A4 data model to include all statuses used in any use case.

---

### DANG-4: `HELD` Status Not Published by A2 (HIGH)

**Used in:** UC01 AF-02 (sanctions hit → HELD), UC08 AF-02 (sanctions hit → HELD), UC03 AF-08 (compliance review → HELD)

**Published by A2:** `11_Payment_Clearing_Settlement_Engine/requirements/requirements.md` CLR-B09:
> "Publish payment status updates to payment.status-updates (**SETTLED, REJECTED**)"

**Impact:** A2 only publishes SETTLED and REJECTED. When a payment is HELD for sanctions review, this status is never published to A3/A4 via `payment.status-updates`. A3 and A4 have no way to know a payment is under compliance review.

**Fix:** Extend CLR-B09 and CLR-I09 to publish: `SETTLED, REJECTED, HELD, PROCESSING`. Add CLR-B09a: "When a payment is HELD for sanctions or fraud review, publish HELD status to `payment.status-updates` with reason code."

---

### DANG-5: `CANCELLED` Status Not Defined in A3 or A4 Requirements (MEDIUM)

**Used in:** UC03 AF-06: "A4 transitions payment to CANCELLED (reason: SCA_TIMEOUT)"

**Defined in:** Neither A4 requirements (PISP-F06: PENDING/PROCESSING/SETTLED/REJECTED) nor A3 requirements (A3-F05: CREATED/PENDING/ACCEPTED/PAYMENT_INITIATED/SETTLED/REJECTED/DECLINED/EXPIRED/DEFERRED).

**Fix:** Add CANCELLED to the A4 payment status enum. Trigger: SCA timeout (>5 minutes), consent revocation during payment processing.

---

### DANG-6: `ledger.events` Kafka Topic Missing from System Topology (LOW)

**Defined in:** A1 requirements (LED-F03, LED-F04) — A1 produces and consumes for internal CQRS projection building.

**Missing from:** System requirements section 9.1 Kafka topic topology.

**Fix:** Add to section 9.1: "`ledger.events` | A1 | A1 (projections) | Internal event store for CQRS projection building. Partition key: account_id."

---

## 3. Ambiguities

---

### AMB-1: Who Initiates the VoP Check — A2, A3, or A4? (HIGH)

Three different answers appear in different documents:

| Source | VoP Caller |
|--------|-----------|
| UC01 step 4 | A3 (after consumer accepts, before Kafka publish) |
| UC03 step 2 | A4 (after TPP submits, before SCA) |
| A2 overview paragraph | A2 (during clearing, implied but never specified in A2 functional requirements) |
| A6 requirements VOP-R02 | "calling service (A2, A3, or A4)" — all three are valid |

**The real problem:** A2's functional requirements (CLR-B01 through CLR-I12) contain **zero VoP-related requirements**. No REST call to A6, no Kafka message, no latency budget. The overview text says A2 routes through VoP, but no requirement implements this.

**Impact:** For the instant path, if A3/A4 already perform VoP before publishing to Kafka, A2 does not need to repeat it. For the batch path (UC04), VoP checks are described as happening during batch processing — which would be A2's responsibility. But A2 has no requirement for this.

**Fix:** Clarify the architectural decision:
- A3 and A4 perform VoP before publishing payment instructions (covers the originator-side check)
- A2 does NOT repeat VoP (it receives the VoP result embedded in the payment instruction)
- Add to A2 requirements: "CLR-B01a: Payment instruction schema must include `vopResult` field (MATCH/CLOSE_MATCH/NO_MATCH/NOT_FOUND/TIMEOUT/NOT_CHECKED). A2 forwards this field to A5 fraud-check-request."
- Remove the misleading A2 overview text about "routing through A6"

---

### AMB-2: Semantic Duplicate Check — Before or After Balance Reservation? (MEDIUM)

UC01 step 6 lists both actions in the same step:
> "A2 validates ISO 20022 pacs.008. Semantic duplicate check... Requests balance reservation from A1"

UC01 AF-11 shows a consumer warning and override flow, implying the payment pauses at the duplicate check. But it is unclear whether balance reservation happens before or after the consumer decides.

**Impact:** If reserved before consumer decides: funds are locked during human decision time, must be released on cancel. If reserved after: a concurrent genuine payment could succeed, and the "duplicate" check loses its window validity.

**Fix:** Add explicit ordering to A2 requirements: "CLR-I04a: For instant payments, semantic duplicate detection runs BEFORE balance reservation. If a duplicate warning is generated and requires user confirmation, the reservation is deferred until confirmation is received."

---

### AMB-3: VoP for Recurring Mandates — Every Execution or Only at Creation? (MEDIUM)

UC06 says "Same flow as UC-01 steps 3-11" which includes the VoP check (step 4). SEC-12 says "Every credit transfer must pass VoP check before execution."

But for AvtaleGiro mandates where the same beneficiary is auto-debited monthly, re-checking VoP on every execution is redundant — the IBAN and name haven't changed.

**Fix:** Add an explicit exception or caching rule: "VoP check may be cached for recurring mandates to the same beneficiary. Re-check is required if: (a) more than 90 days since last VoP check, (b) the mandate beneficiary details are modified, or (c) the beneficiary PSP notifies of an account holder name change."

---

## 4. Terminology Inconsistencies

---

### TERM-1: Request Status "APPROVED" vs. "ACCEPTED" (HIGH)

| Source | Term Used |
|--------|----------|
| system_requirements.md A3-F05 | ACCEPTED |
| UC01 narrative (step 3) | ACCEPTED |
| UC01 data model | APPROVED |

**Impact:** Enum value mismatch between the use case data model and the requirements lifecycle definition. Code generated from the data model will use `APPROVED`; code following the lifecycle will use `ACCEPTED`.

**Fix:** Standardise on `ACCEPTED` (matches the system requirements and is used in the SEPA R2P specification). Update UC01 data model.

---

### TERM-2: VoP Inbound Direction Label (MEDIUM)

| Source | Enum Value |
|--------|-----------|
| UC09 VoP audit log entity | `INBOUND_RESPONSE` |
| A6 requirements VoP query log | `INBOUND_QUERY` |

**Impact:** Same enum field, different value. Interoperability broken if both values are used.

**Fix:** Standardise on `INBOUND_QUERY` (semantically accurate — it is an inbound query, not a response).

---

### TERM-3: Balance Reservation Naming (LOW)

Multiple terms used for the same concept across documents:
- "Balance reservation" (system_requirements.md A1-F11)
- "Balance hold" (system_requirements.md architecture diagram)
- "Tentative hold" (UC01, UC08)
- "Reserve funds" (UC01 step 7)
- "Balance reserved" (A1 Avro schema)
- "Funds reservation" (UC08 time budget)

**Impact:** Low — all documents clearly describe the same concept. But the inconsistency could confuse new developers.

**Fix:** Standardise on "balance reservation" (the term used in the requirement ID) with "hold" as an acceptable synonym. Avoid "tentative hold" (redundant — all reservations are tentative by definition).

---

## 5. Missing Integration Points

---

### INT-1: A5 → A1 Account Freeze — No Consumption Requirement in A1 (HIGH)

A5 publishes to `account.freeze-commands`. A1 defines FROZEN state. But A1's requirements never specify consuming this topic. The mechanism by which A5's fraud confirmation causes A1 to freeze is unspecified.

**Fix:** See DANG-1 above.

---

### INT-2: VoP Result Propagation Chain — Unspecified at Each Hop (MEDIUM)

The VoP result must travel: A3/A4 → Kafka instruction → A2 → A5 fraud-check-request.

- A3/A4 perform VoP and (presumably) embed the result in the PaymentInstruction Kafka message. But the PaymentInstruction schema in the shared `payment-avro-schemas` is never defined in detail.
- A2 consumes the instruction and publishes to `payment.fraud-check-request`. A2's requirements never mention forwarding the VoP result to A5.
- A5 expects to receive the VoP result (FRD-F06: "NO_MATCH override adds +0.3").
- A6 requirements say: "VoP result included in fraud-check-request message to A5" (VOP-I01) — but A6 does not produce the fraud-check-request; A2 does.

**Fix:** Make the propagation explicit:
1. PaymentInstruction Avro schema must include a `vopResult` field.
2. A2 requirement: "CLR-I07a: Forward `vopResult` from payment instruction to `payment.fraud-check-request`."
3. A5 requirement: "FRD-F01a: Parse `vopResult` field from fraud-check-request message."

---

### INT-3: A4 ↔ A1 Communication Protocol — REST or Kafka? (MEDIUM)

A4 requirements AISP-F04: "Account data sourced from A1 via internal REST call **or** Kafka."

The "or" is unresolved. A1 defines REST APIs (LED-F30-F35). A1 does not define a Kafka topic for AISP consumption. If A4 uses REST, latency is synchronous. If Kafka, A4 needs a local projection — fundamentally different architecture.

**Fix:** Resolve: "A4 accesses A1 account data via synchronous internal REST calls (LED-F30-F35). The A4 requirement AISP-F04 is updated to remove the 'or Kafka' option."

---

## 6. Priority Conflicts

---

### PRIO-1: VoP Timeout Behaviour — Must vs. Should (MEDIUM)

- SEC-12 (cross-cutting, Must): "Every credit transfer **must** pass VoP check before execution"
- A6-F10 (sub-system, Should): "If VoP check times out... proceed with payment but flag for review"

**Impact:** The Must requirement (SEC-12) says VoP is mandatory. The Should requirement (A6-F10) says it's OK to skip VoP on timeout. These conflict.

**Fix:** Clarify SEC-12: "Every credit transfer must **initiate** a VoP check before execution. If the VoP check times out or fails, the payment may proceed with a VoP_TIMEOUT flag and must be flagged for post-settlement review (A6-F10)." This preserves the intent of both requirements.

---

### PRIO-2: Balance Reservation Auto-Release — Should but Safety-Critical (HIGH)

A1 requirements LED-F14: "Reservation timeout: if not confirmed/released within configurable time (e.g., 30 seconds), auto-release | **Should**"

LED-S07 describes this as essential for preventing permanent fund locking.

**Impact:** If LED-F14 is not implemented (valid under Should), a failed instant payment where the clearing engine crashes after reserving but before confirming could permanently reduce a customer's available balance. This is a data integrity issue.

**Fix:** Elevate LED-F14 to **Must**. A reservation without auto-release is a correctness bug, not an optional feature.

---

## 7. Numerical Inconsistencies

---

### NUM-1: Instant Payment Elapsed Time Estimate (LOW)

| Source | Estimate |
|--------|---------|
| UC08 | ~2 seconds |
| system_requirements.md section 9.3 | ~3-4 seconds |
| UC01 | ~3-4 seconds |

**Impact:** Low — these are illustrative estimates, not SLA targets (the SLA is 10 seconds). But the inconsistency is confusing.

**Fix:** Standardise on "~2-4 seconds typical" across all documents.

---

### NUM-2: VoP Override Fraud Score Bonus — +0.2 vs. +0.3 (MEDIUM)

| Source | NO_MATCH Override Bonus |
|--------|----------------------|
| UC05 Scenario 5F | +0.3 |
| A5 requirements FRD-F06 | +0.3 |
| UC01 AF-01 | +0.3 (implied via "HIGH_RISK flag") |
| system_requirements.md A5-F11 (semantic duplicate) | +0.2 |
| UC01 AF-11 (semantic duplicate) | +0.2 |

These are actually consistent: VoP NO_MATCH = +0.3, semantic duplicate = +0.2. No inconsistency here upon closer inspection. Documented for completeness.

---

## 8. Flow Ordering Issues

---

### FLOW-1: Liquidity Check — Late in the Flow (MEDIUM)

A2 requirements CLR-L02: "Before instant settlement: verify PSP liquidity >= payment amount."

In the use case flows (UC08, system_requirements.md section 9.3), the liquidity check appears **after** sanctions screening and fraud scoring. This means a payment can consume the full fraud+sanctions latency budget (~500ms) before failing on a liquidity check that could be done in <1ms upfront.

**Impact:** Wasted latency on payments that will be rejected for insufficient liquidity. In normal operation this is rare, but during liquidity stress it could affect SLA compliance.

**Fix:** Either (a) move liquidity check before fraud/sanctions (fail fast), or (b) add an ADR explaining the ordering rationale: "Liquidity is checked late because sanctions screening is legally mandatory regardless — even if liquidity is insufficient, we must screen for sanctions and report any hits. Therefore, sanctions runs first."

---

### FLOW-2: UC05 Case Lifecycle — Missing INVESTIGATING Transition (LOW)

UC05 Scenario 5B shows a case officer going directly from viewing alerts (OPEN) to confirming fraud (CONFIRMED_FRAUD), skipping the INVESTIGATING state defined as mandatory in A5 CASE-F04.

**Fix:** Add an explicit step in UC05 Scenario 5B: "Step 3a: Case officer assigns alert to themselves. A5 transitions alert to INVESTIGATING."

---

## 9. Consolidated Fix List

Ordered by severity and effort.

### High Severity — Must Fix

| # | Issue | Fix | Effort |
|---|-------|-----|--------|
| 1 | CON-6: SETTLED vs. BALANCE_CONFIRMED in Avro schema | Update system_requirements.md A1-F11 to match Avro terminology | Trivial |
| 2 | DANG-3: Missing payment statuses in A4 data model | Extend PISP-F06 enum: add REQUIRES_SCA, REQUIRES_CONFIRMATION, HELD, CANCELLED | Trivial |
| 3 | DANG-4: A2 only publishes SETTLED/REJECTED, not HELD | Extend CLR-B09 and CLR-I09 to publish HELD and PROCESSING | Trivial |
| 4 | TERM-1: APPROVED vs. ACCEPTED in A3 lifecycle | Standardise on ACCEPTED; update UC01 data model | Trivial |
| 5 | CON-4: Alert resolution endpoint /alerts/{id} vs. /alerts/{id}/resolve | Standardise on /alerts/{id}/resolve | Trivial |
| 6 | DANG-1: account.freeze-commands has no A1 consumer requirement | Add LED-F23 consuming freeze commands from A5 | Low |
| 7 | AMB-1: VoP caller ambiguity (A2 vs. A3 vs. A4) | Clarify: A3/A4 perform VoP; A2 forwards result. Remove A2 VoP claim. | Low |
| 8 | INT-2: VoP result propagation not specified at each hop | Add vopResult field to schemas; add forwarding requirements | Low |
| 9 | CON-1: VEL-02 absolute vs. relative threshold | Standardise on relative (20x average); update UC05 | Low |
| 10 | CON-2: Anomaly weight 0.4 vs. 0.3 | Adopt A5 weights (0.3); update UC05 worked example | Low |
| 11 | CON-3: Sanctions hit — short-circuit vs. combined formula | Adopt short-circuit; update A5 combined_score docs | Low |
| 12 | PRIO-2: Reservation auto-release is Should but safety-critical | Elevate LED-F14 to Must | Trivial |

### Medium Severity — Should Fix

| # | Issue | Fix | Effort |
|---|-------|-----|--------|
| 13 | CON-5: Sanctions list update 1hr vs. 5min | Clarify SAN-F07 (end-to-end) vs. NFR-06 (processing only) | Trivial |
| 14 | PRIO-1: VoP Must (SEC-12) vs. Should (A6-F10) timeout | Clarify SEC-12 to allow timeout fallback | Trivial |
| 15 | DANG-5: CANCELLED status not in A4 data model | Add CANCELLED to PISP-F06 | Trivial |
| 16 | TERM-2: INBOUND_RESPONSE vs. INBOUND_QUERY | Standardise on INBOUND_QUERY | Trivial |
| 17 | AMB-2: Duplicate check vs. reservation ordering | Add explicit ordering in A2 requirements | Low |
| 18 | AMB-3: VoP for recurring mandates — every execution? | Add caching/exception rule | Low |
| 19 | INT-3: A4↔A1 REST vs. Kafka | Resolve: synchronous REST | Trivial |
| 20 | FLOW-1: Liquidity check late in flow | Add ADR or reorder | Low |
| 21 | DANG-2: sanctions.list-updates no producer | Clarify or remove topic | Trivial |
| 22 | NUM-2: Verify VoP/duplicate score bonuses are consistent | Confirmed consistent; no action | None |

### Low Severity — Nice to Fix

| # | Issue | Fix | Effort |
|---|-------|-----|--------|
| 23 | NUM-1: Elapsed time estimate 2s vs. 3-4s | Standardise on ~2-4s | Trivial |
| 24 | TERM-3: Balance reservation naming variants | Standardise on "balance reservation" | Low |
| 25 | DANG-6: ledger.events missing from system topology | Add to section 9.1 | Trivial |
| 26 | FLOW-2: UC05 missing INVESTIGATING step | Add step 3a | Trivial |
| 27 | General: Use case data models should reference requirement IDs, not duplicate enum definitions | Systematic update | Medium |

---

## End of Internal Consistency Review

**Total findings:** 27
**High severity:** 12 (would cause implementation defects)
**Medium severity:** 10 (create confusion, resolvable)
**Low severity:** 5 (cosmetic)

**Most impactful single fix:** Establishing a single authoritative payment status enum shared across all documents (addresses DANG-3, DANG-4, DANG-5, TERM-1, and prevents future status divergence).
