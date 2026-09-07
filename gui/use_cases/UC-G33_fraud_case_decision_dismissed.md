# UC-G33 — Fraud Case Decision: DISMISSED (with Release)

*Owner: Frontend / Admin / Fraud*
*Status: DRAFT*
*Implementation status: Admin Web ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

The fraud analyst decides a case as `DISMISSED` — the held payment is released to continue clearing and settlement; no account freeze. The decision is audited and the customer is notified that the payment is proceeding.

## 2. Actors

- **Primary:** FRAUD_ANALYST
- **Secondary:** A5 (case store), A2 (resumes clearing), notification service

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes | |

## 4. Preconditions

- Operator is authenticated with `fraud:manage`.
- Case is `ASSIGNED_TO_ME` or accessible via override.

## 5. Postconditions

- Case `status=DISMISSED`.
- `fraud.decision-response dismissed` published → A2 releases held payment(s) into next step of the instant / batch pipeline.
- Audit `fraud.case.decided dismissed`.
- Customer receives the "Betalingen din går videre / Your payment is proceeding" notification.

## 6. Main flow (happy path)

1. From UC-G31 case detail, operator taps **Avvis mistanke / Dismiss**.
2. Confirmation modal:
   - Title: "Avvis mistanken og slipp betaling"
   - Notes field (required, ≥ 50 chars) — "Forklar hvorfor mistanken avvises."
   - Checkbox: "Jeg har vurdert all kontekst og konkluderer med at det ikke er svindel" — required.
   - Buttons: Cancel, Bekreft.
3. Operator submits; Admin Web POSTs `POST /api/v1/admin/fraud/cases/{caseId}/decide` with `{decision:"DISMISSED", notes}`.
4. Step-up SCA (same pattern as UC-G32).
5. Backend (A5) persists decision, publishes release events, returns final state.
6. Admin Web updates case view → DISMISSED status; toast "Saken er lukket, betaling er sluppet videre."
7. SSE events update related-payment rows as they re-enter the pipeline (`AWAITING_RESERVATION` → `SETTLING` → `SETTLED` for instant; equivalent for batch).

## 7. Alternative flows

### AF-01 — Operator leaves short notes

1. Inline validation; same as UC-G32 AF-02.

### AF-02 — Release fails because account became FROZEN between case creation and decision (e.g., by sanctions in parallel)

1. A2 cannot resume; publishes `REJECTED reason=ACCOUNT_FROZEN`.
2. Case decision still applies (DISMISSED stands); customer sees the alternate rejection reason.
3. Case detail shows a note: "Betalingen kunne ikke slippes videre pga. fryst konto. Sak forblir avvist."

### AF-03 — Related payment expired while case was pending (> 2h for instant path)

1. A2 rejects on resume attempt with `TIMEOUT`.
2. Customer notified of timeout; case still DISMISSED.

### AF-04 — Operator changes mind during modal

1. Cancel button returns to case detail; no decision recorded.

### AF-05 — Bulk DISMISSED (ADM-F15)

1. Operator selects multiple cases from queue, chooses "Dismiss selected" — one modal, notes apply to all, one SCA. Limit 50.

### AF-06 — Concurrent decision by another operator (race)

1. 409 with conflict state; UI refreshes.

## 8. Failure modes

Same as UC-G32 §8.

## 9. Related requirements

- `gui_requirements.md` — `ADM-F13`, `ADM-F15`, `GUI-SEC-14`, `GUI-SEC-08`
- `system_requirements.md` — A5-F16, A2 release path
- UC-05

## 10. API operations involved

- `POST /api/v1/admin/fraud/cases/{caseId}/decide` (`admin-fraud.yaml`) — same endpoint as UC-G32, differs in the `decision` body param.

## 11. Tests realising this use case

| Layer | Admin Web |
|-------|-----------|
| Unit | shares decide-modal unit tests with UC-G32 |
| Component | `admin-web/.../fraud/__tests__/case-detail.test.tsx` (dismissed flow) |
| Contract | shares `admin-fraud.pact` |
| E2E | `admin-web/e2e/fraud-decide-dismissed.spec.ts`, `admin-web/e2e/fraud-decide-bulk.spec.ts` |

## 12. UX notes

- Dismiss button uses neutral tone (not destructive red); dismissal is a positive outcome for the customer.
- Release is visible — the case detail shows a small timeline of "payment resumed" events to confirm the action worked.

## 13. Open questions

- Whether DISMISSED cases should remain assigned to the operator for 24 h (reversal window). *Decision: no formal reversal window; audit trail is the source of truth. Confirm in Phase-1.*
