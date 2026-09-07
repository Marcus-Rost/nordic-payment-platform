# Phase 4 Findings — Payment Flows + Timing Budget

*Categories 4, 5 from REVIEW_CHECKLIST.md*
*Date: 2026-04-12*

---

## Summary

4 checks performed: happy path trace, time budget arithmetic, timeout consistency, failure path.

- **PASS:** 1 (time budget arithmetic — numbers are safe)
- **FAIL:** 2 (happy path trace, timeout consistency)
- **CONDITIONAL PASS:** 1 (failure path — correct implementation, ambiguous master wording)

---

## Critical/High Findings

### F4.1 — A4 VoP Timeout (3000ms) Is 6x A3's (500ms) [HIGH]

**A3** uses 500ms timeout for A6 VoP calls. **A4** uses 3000ms for the same call. A6 targets <500ms end-to-end.

A4's 3s timeout means Phase 1 could block for 3s on VoP alone, leaving only 2s for the entire Phase 2 orchestration. This violates the Phase 1 time budget (~800ms).

**Action:** Reduce A4's VoP timeout to 500ms (matching A3). File: A4 `ARCHITECTURE_DESIGN.md` line 744.

### F4.2 — A3/A6 VoP Endpoint Mismatch [CRITICAL — duplicate of F3.2]

Already captured in Phase 3. A3 uses `GET /api/v1/vop/verify`, A6 exposes `POST /internal/vop/verify`.

### F4.3 — Master UC08 AF-05 Ambiguous Wording [MEDIUM]

Master UC08 says: "A5 does not respond within 500ms → AUTO_PASS with score=UNKNOWN."

A2's actual implementation distinguishes two cases:
1. A5 delivers **partial response** (sanctions CLEAR, fraud UNKNOWN) → auto-pass fraud. Correct.
2. A5 **completely unresponsive** → REJECT (sanctions unknown, cannot skip). Correct.

The master UC08 wording doesn't distinguish these cases. "Does not respond" sounds like case 2 (reject), but the master says "auto-pass" which is case 1.

**Action:** Clarify master UC08 AF-05 to distinguish partial response from complete timeout. File: `payment_processing/use_cases/UC08_instant_payment.md` lines 109-116.

### F4.4 — Two Different Time Budget Breakdowns [MEDIUM]

Master UC08 allocates 1,050ms processing subtotal. A2's design allocates 1,500ms for Phase 2 alone. Both result in totals well within 5,000ms, but having two different canonical budgets creates confusion.

**Action:** Reconcile to one authoritative budget. A2's budget is more detailed and realistic — recommend adopting it as canonical and updating master UC08 to match.

### F4.5 — A5 Partial-Response Mechanism Not Explicitly Documented [MEDIUM]

A2 expects A5 to send `{sanctions: CLEAR, fraudScore: UNKNOWN}` when fraud scoring times out internally. A5's design implies this through FRD-RES03/04 but doesn't show it as an explicit code path.

**Action:** Add explicit documentation of the partial-response path to A5's design.

---

## Positive Findings

- **Time budget is safe:** Total worst-case is ~2,800ms vs 5,000ms target — 2,200ms margin
- **Kafka topic names are consistent** throughout the entire instant payment flow
- **A2's failure handling is correct:** sanctions timeout = reject, fraud timeout with sanctions confirmed = auto-pass
- **A2 implements reservation-then-fraud sequentially** (not parallel), which is simpler and still within budget
- **Reservation timeout:** both A1 and A2 agree on 30 seconds
