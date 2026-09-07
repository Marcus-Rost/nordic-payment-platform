# Phase 3 Findings — Inter-Service Interface Contracts

*Categories 3, 3.5 from REVIEW_CHECKLIST.md*
*Date: 2026-04-12*

---

## Summary

14 interface checks performed across 5 critical service pairs.

- **PASS:** 8
- **FAIL:** 4 (2 Critical, 2 High)
- **CONDITIONAL PASS:** 1
- **PASS with caveat:** 1

**This phase found the most serious issues in the review.** Two critical integration failures would prevent the system from working if implemented as documented.

---

## Critical Findings

### F3.1 — Reservation Partition Key Conflict: A2 vs A1 [CRITICAL]

**A2** (`ARCHITECTURE_DESIGN.md` line 266): Publishes to `payment.balance-reservation` with `transactionId` as Kafka message key. A2's Kafka Streams topology requires `transactionId` partitioning for co-partitioning with the state store.

**A1** (`ARCHITECTUR_DESIGN.md` line 124): Expects `payment.balance-reservation` with partition key = `accountId`. A1 uses `accountId` partitioning for per-account serialization — ensuring concurrent reservations for the same account are processed sequentially.

**Both sides have valid architectural reasons, but they disagree.** This is a fundamental conflict:
- If key = `transactionId`: A1 loses per-account serialization → concurrent reservations on the same account can race
- If key = `accountId`: A2's Kafka Streams co-partitioning breaks → A2 can't correlate responses with state store entries

**Resolution options:**
1. A2 publishes with `accountId` as key (satisfying A1). A2 repartitions the response stream internally using a KStream.through() with `transactionId` key.
2. A1 repartitions internally after consumption.
3. Include both keys: `accountId` as Kafka key (for partitioning), `transactionId` in payload (for correlation). A2 uses `transactionId` from payload for state store lookup.

**Action:** Resolve this conflict. Option 3 seems most natural — partition by `accountId` for A1's serialization guarantee, correlate by `transactionId` from the message payload.

### F3.2 — A3 VoP Endpoint Mismatch: Wrong Method and Path [CRITICAL]

**A3** (`ARCHITECTUR_DESIGN.md` line 861): Calls `GET /api/v1/vop/verify?name=...&iban=...`

**A6** (`ARCHITECTURAL_DESIGN.md` line 540): Exposes `POST /internal/vop/verify` with JSON body `{beneficiary_iban, stated_name, request_id}`

**Two mismatches:**
1. HTTP method: A3 uses GET, A6 expects POST
2. Path: A3 uses `/api/v1/vop/verify` (external-style), A6 exposes `/internal/vop/verify` (internal)

**A4 correctly uses** `POST /internal/vop/verify` — so the issue is specific to A3.

**Action:** Fix A3's design to use `POST /internal/vop/verify` with JSON body, matching A6's internal API.

---

## High Findings

### F3.3 — A6→A1 REST Path Prefix Mismatch [HIGH]

**A6** (`ARCHITECTURAL_DESIGN.md` lines 112-113): Calls `GET /internal/accounts/{iban}/holder-name`

**A1** (`ARCHITECTUR_DESIGN.md` line 144): Exposes `GET /api/v1/accounts/{id}/holder-name`

**Two mismatches:**
1. Path prefix: `/internal/accounts/` vs `/api/v1/accounts/`
2. Path parameter naming: `{iban}` vs `{id}` (semantically, IBAN is the account identifier — same thing, but naming differs)

**Action:** Decide on the convention. Internal service-to-service endpoints could use `/internal/` prefix (as A6 assumes) or `/api/v1/` prefix (as A1 defines). Then align both documents. The prefix convention should be consistent system-wide.

### F3.4 — A2 Cannot Handle A5's `decision: TIMEOUT` Response [HIGH]

**A5** (`ARCHITECTUR_DESIGN.md` line 445): On graceful shutdown, if scoring cannot complete, A5 publishes `{decision: TIMEOUT}`.

**A2** (`ARCHITECTURE_DESIGN.md` lines 248-252): FraudResponseProcessor has branches for `sanctions = CLEAR/HIT/UNAVAILABLE` and `fraud = PASS/BLOCK/UNKNOWN`. **No branch for `decision: TIMEOUT`.**

If A5 publishes `decision: TIMEOUT`, A2's state machine has undefined behavior for this input.

**Action:** Add a `TIMEOUT` branch to A2's FraudResponseProcessor. Logical behavior: treat as sanctions UNAVAILABLE (reject — sanctions cannot be skipped) if the sanctions result is not CLEAR.

---

## Medium Findings

### F3.5 — A2-A5 Timeout Margin Convention Undocumented [MEDIUM]

A2 budgets 800ms for fraud+sanctions (500ms target + 300ms margin). A5 targets 500ms. The margin concept exists only in A2's design. A5 doesn't know about the extra 300ms buffer.

**Action:** Document the margin convention bilaterally — in both A2 and A5 designs.

### F3.6 — A4→A1 REST Paths Not Explicitly Documented [MEDIUM]

A4's design shows abbreviated controller annotations (`GET /accounts`, `GET /balances`) without full paths. A1 exposes `/api/v1/accounts/{id}/balances`. Probably correct but not verified.

**Action:** Add explicit full paths in A4's design for all A1 REST calls.

### F3.7 — Settlement Event Avro Schema Not Formally Defined [LOW]

A2 references a `SettlementEvent` domain class but doesn't detail its full Avro schema. A1's settlement handler expects specific fields. Both sides appear compatible from flow descriptions, but the exact schema should be in the shared domain model.

**Action:** Define the SettlementEvent Avro schema formally, either in A2's design or in a shared schema document.

---

## Positive Findings

- Settlement event flow (A2→A1): topic names, partition keys, and processing logic are consistent
- Fraud check topic names: all four topic names (request/response × batch/instant) match perfectly between A2 and A5
- Fraud check message keys and correlation: both sides use `transactionId` consistently
- Reservation timeout: both sides agree on 30-second expiry
- Reservation compensation: A2's release is idempotent, A1's safety net timeout is consistent
- A4→A6 VoP call: correctly uses `POST /internal/vop/verify`
