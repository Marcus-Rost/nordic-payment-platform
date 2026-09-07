# Phase 6 Findings — Operational, Requirements, Regulatory

*Categories 9-14 from REVIEW_CHECKLIST.md*
*Date: 2026-04-12*

---

## Summary

5 checks performed: metric naming, port assignments, sanctions bypass, status enumerations, contract test coverage.

- **PASS:** 1 (sanctions cannot be bypassed — clean regulatory result)
- **FAIL/Issues:** 4 areas with findings

---

## High Finding

### F6.1 — A3/A4 Don't Handle `RETURNED` Payment Status [HIGH]

A2 publishes `RETURN_INITIATED` and `RETURNED` status updates when a settled payment is reversed (e.g., late sanctions hit triggers pacs.004 return). Neither A3 nor A4 handle these statuses:
- A3 only handles `SETTLED` and `REJECTED` from `payment.status-updates`
- A4 handles `SETTLED`, `REJECTED`, and `HELD` — but not `RETURNED`

**Impact:** A consumer/TPP could believe a payment is settled when it has actually been reversed. The R2P request or PSD2 payment record stays in `SETTLED` state permanently even though funds were reversed.

**Action:** Add `RETURN_INITIATED` and `RETURNED` status handling to both A3 and A4. A3 should transition the R2P request to a `RETURNED` state and notify the merchant via webhook. A4 should update the payment status for the TPP.

---

## Medium Findings

### F6.2 — A3 Doesn't Handle `HELD` Status From A2 Batch Path [MEDIUM]

When a batch payment for an R2P request gets `HELD` for manual review, A3 has no transition for it. The status update goes to DLQ.

**Action:** Add `HELD` status handling to A3's state machine.

### F6.3 — Port 9092 Conflict: A2 Management Port vs Kafka Broker [MEDIUM]

SUBSYSTEM_MAP.md assigns port 9092 to both A2's management endpoint and the Kafka broker. Both needed in docker-compose.

**Action:** Change A2's management port to 9082 (or similar) in SUBSYSTEM_MAP.md and A2's design.

### F6.4 — A5 Has No Contract Test (Pact) References [MEDIUM]

A5 is the provider for the fraud-check-response contract that A2 defines as a Pact consumer contract. A5's design document has zero references to Pact. Without provider-side verification, A5 could publish responses that don't match A2's expectations.

**Action:** Add Pact provider verification to A5's testing section.

### F6.5 — A6↔A1 REST Contract Not Pact-Tested [MEDIUM]

The A6→A1 holder-name REST call has no contract test in either service's design. A3 uses WireMock for VoP testing rather than Pact.

**Action:** Add Pact contracts for A6→A1 (REST) and A3→A6 (REST) interfaces.

---

## Low Findings

### F6.6 — Inconsistent Metric Namespace Prefixes [LOW]

Services use different prefix patterns: `ledger.*`, `payment.*`/`psp.*`, `rtp.*`, `fraud.*`, `vop.*`. A2 uses two prefixes (`payment.*` and `psp.*`). No common root namespace.

**Action:** Consider adding a common root (e.g., `nordpay.ledger.*`) or at least make A2 consistent (merge `psp.*` into `payment.*`).

### F6.7 — A5 Metric Table Missing Type Column [LOW]

A5's metric table lists metric names and alert thresholds but omits whether each is a histogram, counter, or gauge.

**Action:** Add metric type column to A5's observability section.

### F6.8 — `SETTLEMENT_FINAL` Event Not Consumed [LOW]

A2 publishes `SETTLEMENT_FINAL` on `payment.status-updates` but no downstream service mentions consuming it.

**Action:** Either remove from A2 or document which service should consume it (likely A3 for merchant notification).

---

## Positive Finding

### Sanctions Screening Cannot Be Bypassed [PASS — REGULATORY]

Comprehensive verification:
- Instant path: structurally mandatory, no feature flag or code path bypasses A5
- Batch path: declarative Spring Batch step, cannot be skipped
- VRP via A4: uses same Kafka topic, A2 processes identically
- Mandate auto-accept in A3: skips consumer approval, NOT sanctions screening
- Duplicate override: skips duplicate check, NOT sanctions
- Timeout: always REJECT (sanctions cannot be skipped)
- Late sanctions hit on settled payment: automatic pacs.004 return

**No regulatory gaps found.** This is the cleanest result in the review.
