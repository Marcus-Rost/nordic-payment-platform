# UC-G07 — Schedule a Future-Dated Payment

*Owner: Frontend / Payments*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

The customer schedules a single payment to execute on a future date (up to 90 days ahead). The scheduling, VoP, and SCA happen now; the actual debit, clearing, and settlement happen on the execution date. Cancellation is possible any time before execution-day T-1h.

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A4 (schedule store), A3 (scheduler — shares the R2P/mandate scheduler), A1 (reservation NOT placed at schedule time; balance only checked at execution), A6 (VoP at schedule time)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | |
| Client Web | yes | |
| Client iOS | yes | |
| Client Android | yes | |

## 4. Preconditions

- Customer is authenticated with `payments:initiate`.
- Chosen debtor account is ACTIVE.

## 5. Postconditions

- A schedule record exists in A3 with `status=SCHEDULED` and execution timestamp.
- A dynamic-linking SCA proof is attached to the schedule and stored alongside it for verification at execution time (the proof re-binds to amount + creditor only — PSD2 RTS Art. 5 does not require re-SCA for pre-scheduled payments, *provided* amount and creditor are unchanged).
- A `payment.scheduled` audit event is recorded.
- A notification is placed in the customer's inbox.

## 6. Main flow (happy path)

1. In the payment form (UC-G05 step 2), customer toggles **Planlagt / Scheduled** and picks an execution date (date picker, min = tomorrow, max = today + 90 days) and optional execution time (default 09:00 local, in 15-min buckets for batch rail; ignored for instant since instant always executes immediately on the date).
2. Instant + future-date combination: supported; on the execution date at the chosen time, A2's instant rail executes.
3. VoP runs now (same as UC-G05 step 3), so the customer is warned about a name mismatch before committing the schedule.
4. Customer taps **Continue**; GUI calls `POST /api/v1/customer/payments/schedules` with the payload (includes `executeAt` ISO-8601).
5. SCA flow runs now (UC-G51); the resulting proof is stored with the schedule.
6. Confirmation screen: "Planlagt til 15. mai kl. 09:00" / "Scheduled for 15 May at 09:00", summary of the payment, and a **Avbryt / Cancel** action.
7. The schedule appears in the dashboard's "Kommende betalinger / Upcoming payments" section (UC-G02 extension).
8. On execution date at the scheduled time, A3 emits an internal trigger to A4; A4 submits the payment with the stored SCA proof; flow resumes in UC-G05 §6 step 11 onward (or UC-G06 equivalent for batch).
9. On successful execution, the schedule transitions to `EXECUTED`; a notification confirms.

## 7. Alternative flows

### AF-01 — Insufficient balance at execution time

1. A1 reservation at execution fails → A2 publishes `REJECTED reason=INSUFFICIENT_FUNDS`.
2. Schedule transitions to `EXECUTION_FAILED`; customer is notified with a clear copy and "Try again" button that re-opens the form pre-filled.

### AF-02 — Customer cancels before execution

1. From the schedule detail (or the dashboard card), customer taps **Avbryt**.
2. GUI calls `DELETE /api/v1/customer/payments/schedules/{scheduleId}` (no SCA — cancellation is always customer-positive).
3. Schedule transitions to `CANCELLED`; audit event recorded.

### AF-03 — Customer attempts to cancel within T-1h (hard cutoff)

1. Backend returns 409 `too-late-to-cancel`. GUI shows copy "Kan ikke avbryte nå — betalingen er under behandling. Om den mislykkes, kan du be om retur."

### AF-04 — Account status changes before execution (FROZEN/CLOSED)

1. A3 detects at execution and aborts; `EXECUTION_FAILED reason=ACCOUNT_UNAVAILABLE`; customer is notified.

### AF-05 — Creditor IBAN becomes invalid between schedule and execution

1. A6 VoP is re-run at execution time (belt-and-suspenders); on `NOT_FOUND`, execution fails and the customer is notified.

### AF-06 — Customer edits the schedule (change amount or creditor)

1. Editing is modelled as cancel + create. GUI offers a "Edit" action that guides the user through cancel → re-open form pre-filled → re-schedule (new SCA).
2. This is deliberate: changing amount/creditor invalidates the stored dynamic-linking proof.

### AF-07 — Execution-day falls on a non-business day (batch path only)

1. Backend rolls forward to the next business day; GUI displays the rolled date on scheduling ("Merknad: 17. mai er fridag, kjøres 18. mai") and again in the confirmation.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 5xx on scheduling POST | Response | Retryable with same idempotency key |
| SCA timeout | Same as UC-G05 AF-06 | AF-06 applies |
| Schedule not found on cancel (already executed) | 404 | Copy "Betalingen er allerede gjennomført." and link to UC-G03 row |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F40`, `CLI-F20..F28`, `GUI-SEC-08`
- `system_requirements.md` — A3-F20..F24 (scheduler)

## 10. API operations involved

- `POST   /api/v1/customer/payments/schedules` (`customer-payments.yaml`)
- `GET    /api/v1/customer/payments/schedules` (`customer-payments.yaml`)
- `GET    /api/v1/customer/payments/schedules/{scheduleId}`
- `DELETE /api/v1/customer/payments/schedules/{scheduleId}`

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `.../schedules/__tests__/date-picker.test.ts`, `eta.test.ts` | `.../Tests/ScheduleDateTests.swift` | `.../schedules/ScheduleDateTest.kt` |
| Component | `.../schedules/__tests__/schedule-form.test.tsx` | `.../Tests/ScheduleFormViewTests.swift` | `.../ScheduleFormScreenTest.kt` |
| Contract | `.../schedules.pact.test.ts` | `.../SchedulesPactTests.swift` | `.../SchedulesPactTest.kt` |
| E2E | `client-web/e2e/schedule-payment.spec.ts` | `.../UITests/SchedulePaymentUITests.swift` | `.../androidTest/.../SchedulePaymentE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/schedule_next_week.json`, `schedule_cancel.json`, `schedule_edit_as_recreate.json` | (same) | (same) |

## 12. UX notes

- The execution date picker disables non-business days visually if batch rail is chosen; instant rail allows any date including weekends.
- "Upcoming payments" card on the dashboard lists at most the next 3 scheduled payments with a "See all" link.
- The schedule detail has a visual timeline: Scheduled (now) → Execution (date) → Settlement (live-updating on and after execution).

## 13. Open questions

- Whether to offer a simple recurrence option ("Repeat monthly") at this UC or keep recurrence in UC-G08 (AvtaleGiro mandate). *Decision: keep recurrence in UC-G08; a customer who wants repeating payments is better served by a mandate.*
