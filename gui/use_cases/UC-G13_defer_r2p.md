# UC-G13 — Defer an R2P (Accept & Pay Later)

*Owner: Frontend / Payments / R2P*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

The customer accepts the obligation to pay an R2P but at a future date of their choice (on or before the due date). The defer operation schedules the payment (using UC-G07's mechanism) and marks the R2P as `DEFERRED` so it does not continue to appear in the Pending queue and is not sent as DECLINED to the merchant.

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A3 (R2P store + scheduler), A4 (scheduled payment creation), identity service (SCA at scheduling time)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | |
| Client Web | yes | |
| Client iOS | yes | |
| Client Android | yes | |

## 4. Preconditions

- R2P exists in `PENDING` state.
- Customer holds `payments:initiate`.

## 5. Postconditions

- R2P `status=DEFERRED`, `scheduledPaymentId` linked.
- A scheduled payment exists (same record type as UC-G07).
- Audit event `r2p.deferred`.
- Inbox confirmation.

## 6. Main flow (happy path)

1. From R2P detail, customer taps **Betal senere / Pay Later**.
2. Dialog: date picker (min = tomorrow, max = R2P due date), optional time (default 09:00), optional note.
3. GUI calls `POST /api/v1/customer/r2p/{requestId}/defer` with `{executeAt, note}`.
4. A3 creates a schedule via A4 (passes the R2P envelope — creditor IBAN, name, amount, reference — so the scheduled payment mirrors the R2P's immutable data).
5. A4 returns `PENDING_SCA`; SCA (UC-G51) runs with dynamic linking to the R2P's `{creditorIban, creditorName, amount}`.
6. On success, the scheduled payment is created and the R2P is linked. GUI shows confirmation with the execution date and the R2P's reference.
7. The R2P moves out of "Pending" and appears in "Deferred" filter.
8. On execution date, UC-G07 §6 step 8 onward applies (instant or batch depending on the R2P's requested rail).
9. On successful execution, A3 transitions the R2P to `ACCEPTED` with the linked `paymentId`.

## 7. Alternative flows

### AF-01 — Customer attempts to defer past due date

1. Inline validation; submission blocked.

### AF-02 — Scheduled payment fails at execution (insufficient funds, account FROZEN)

1. R2P transitions to `DEFER_FAILED` (new state); customer notified; merchant is *not* automatically informed (customer should decide: retry with a new defer, decline, or accept now).
2. The R2P detail shows the failure and offers three buttons: **Try again now** (UC-G11), **Reschedule** (open defer dialog again), **Decline** (UC-G12).

### AF-03 — Customer cancels the defer before execution

1. Opening the R2P detail, customer taps **Avbryt planlagt / Cancel scheduled**.
2. GUI calls `POST /api/v1/customer/r2p/{requestId}/defer/cancel` — removes the scheduled payment and returns R2P to `PENDING`.

### AF-04 — Merchant cancels R2P while deferred

1. A3 receives `camt.056` cancellation; cascades to cancel the scheduled payment; R2P moves to `CANCELLED_BY_MERCHANT`; customer notified.

### AF-05 — SCA fails at scheduling

1. UC-G51 failure paths; R2P remains `PENDING`.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 5xx on POST | Response | Retryable with idempotency key |
| Race with merchant cancellation | 410 on POST | Refresh + cancellation copy |
| Scheduler backend outage | 503 on scheduling | Retryable; if persistent, customer is offered UC-G11 (pay now) as alternative |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F51`, `GUI-SEC-08`
- `system_requirements.md` — A3-F50..F60, A3-F20..F24 (scheduler)

## 10. API operations involved

- `POST /api/v1/customer/r2p/{requestId}/defer` (`customer-r2p.yaml`)
- `POST /api/v1/customer/r2p/{requestId}/defer/cancel`
- Reuses UC-G07 scheduled-payment endpoints internally (A4 call from A3 is an internal Kafka event).

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `.../r2p/__tests__/defer-dialog.test.tsx` | `.../Tests/R2pDeferTests.swift` | `.../r2p/R2pDeferTest.kt` |
| Component | shares `.../r2p/__tests__/r2p-detail.test.tsx` | (same) | (same) |
| Contract | shares `.../r2p.pact` | (same) | (same) |
| E2E | `client-web/e2e/r2p-defer.spec.ts` | `.../UITests/R2pDeferUITests.swift` | `.../androidTest/.../R2pDeferE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/r2p_defer_two_days.json`, `r2p_defer_failed_at_execution.json` | (same) | (same) |

## 12. UX notes

- The defer date picker visually marks the due date (e.g., a red ring around it); dates after are disabled.
- "Deferred" filter has its own tab in the inbox, equal weight with "Pending".
- A failure at execution surfaces prominently in the inbox (high-priority notification) to avoid the customer missing a payment obligation.

## 13. Open questions

- Whether a deferred R2P counts toward the customer's "upcoming payments" tile on the dashboard. *Decision: yes — same tile as UC-G07 schedules.*
