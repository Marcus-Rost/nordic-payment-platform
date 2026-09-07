# UC-G06 — Initiate One-Off Batch (Standard) Payment

*Owner: Frontend / Payments*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

The customer initiates a single credit transfer on the **standard batch rail** (as opposed to SCT Inst in UC-G05). The batch rail targets the NICS netting cycles for NOK (or the equivalent domestic clearing for other currencies) and settles in the next available cycle — typically within hours rather than seconds. The flow otherwise mirrors UC-G05 (VoP, SCA with dynamic linking) but the status-streaming experience differs (initial status `RECEIVED → AWAITING_CLEARING` and final `SETTLED` can be hours later, not seconds).

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A4 (customer endpoint), A6 (VoP), A2 (clearing — batch path), A1 (reservation + settlement)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | |
| Client Web | yes | |
| Client iOS | yes | |
| Client Android | yes | |

## 4. Preconditions

- Same as UC-G05 §4, with execution mode set to `STANDARD`.
- The amount + currency + time-of-day combination is compatible with the batch rail (e.g., NICS cut-off hours are enforced server-side; see AF-03 below).

## 5. Postconditions

- A payment record exists in A2, initially in `AWAITING_CLEARING` state.
- The customer's debtor account has a reservation equal to the amount (A1 reservation lifetime ≥ expected settlement horizon).
- The customer receives the `payment.queued-for-batch` notification to the inbox.
- The payment ultimately transitions to `SETTLED` (or a terminal alternative) and a second notification is sent.

## 6. Main flow (happy path)

1. Steps 1–8 of UC-G05 apply, with **execution mode = Standard** explicitly selected or set by default when the customer opts out of instant.
2. Confirmation-screen copy differs: instead of a stepper that will complete in seconds, the GUI shows:
   ```
   Queued for next settlement cycle
   Forventet fullført / Expected complete: today 15:30 (NICS cycle 3)
   ```
   with the estimated settlement time derived from A2's cycle schedule (A2 exposes next-cycle metadata).
3. The payment appears in the account's transaction list (UC-G03) as PENDING with the projected settlement date/time.
4. When the NICS cycle closes and the payment settles, A2 publishes `payment.status-updates` with `SETTLED`.
5. A notification (UC-G19 counterpart for success: `payment.settled`) is delivered.
6. On next visit to UC-G03, the row shows SETTLED; detail shows the final settlement timestamp and clearing reference.

## 7. Alternative flows

### AF-01 — Payment picked up by next cycle

1. Happy path: cycle closes within expected window.

### AF-02 — Payment misses the cycle cut-off (submitted 1 minute before cut-off, A2 rejects binding to that cycle for safety)

1. A2 attaches the payment to the next cycle; GUI shows an adjusted ETA with a subtle "Neste kjøring / Next cycle" note.

### AF-03 — After-hours submission

1. Customer submits outside business hours (e.g., Sat 22:00 NOK). A4 accepts; A2 schedules for Monday's first cycle. ETA shown accordingly; copy surfaces "Settling on <next business day>".

### AF-04 — Same-account scenarios (internal transfer)

1. For debtor and creditor both held by this bank (book transfer), A2 short-circuits: no NICS involvement; SETTLED within seconds. GUI falls back to UC-G05's stepper UI on detection (A4 returns `settlementPath=BOOK_TRANSFER` in the submit response).

### AF-05 — Same as UC-G05 AF-01..AF-12 for insufficient funds, sanctions, fraud, SCA timeout, SSE disconnect, 5xx, frozen account, VoP override

- All apply identically, adapting copy where needed (e.g., `payment.queued` rather than `payment.settled`).

### AF-06 — Cycle rejects the payment (NICS operator cleanup / reason codes)

1. A2 publishes `REJECTED` with a reason code per ISO 20022 (`AC01 IncorrectAccountNumber`, etc.).
2. GUI shows the canonical i18n string for the code with reference ID.

## 8. Failure modes

Same table as UC-G05 §8; add:

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Cycle-rejection reason code not in i18n catalogue | Incoming status event with unknown `reasonCode` | Show the generic `payments.error.returned` copy with the raw code + reference ID |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F20..F29`, `CLI-F23`, `GUI-SEC-08..09`, `GUI-RES-01..05`
- `system_requirements.md` — A2-F10..F19 (batch path)

## 10. API operations involved

Same as UC-G05 §10. The only difference is the payload's `executionMode=STANDARD`.

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `.../payments/__tests__/batch-eta.test.ts` | `.../Tests/BatchEtaTests.swift` | `.../payments/BatchEtaTest.kt` |
| Component | `.../payments/__tests__/batch-confirmation.test.tsx` | `.../Tests/BatchConfirmationViewTests.swift` | `.../BatchConfirmationScreenTest.kt` |
| Contract | shares `payments.pact` | (same) | (same) |
| E2E | `client-web/e2e/payment-batch.spec.ts` | `.../UITests/PaymentBatchUITests.swift` | `.../androidTest/.../PaymentBatchE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/payment_batch_submission.json`, `payment_batch_after_cutoff.json` | (same) | (same) |

## 12. UX notes

- The differentiator between instant and batch confirmation is emphasised on-screen — customers familiar with instant payments may find "pending for hours" surprising; copy explicitly explains the NICS cycle model in plain language.
- The "Expected complete" ETA refreshes live if the cycle schedule changes (A2 SSE).
- The toggle between instant and standard is never hidden; the customer can return to the form and re-submit if they change their mind before SCA.

## 13. Open questions

- Whether to pre-compute and display fee differences between instant and standard in the form (portfolio default: no fees). *Defer to fee-schedule integration.*
