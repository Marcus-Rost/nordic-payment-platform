# UC-G05 — Initiate One-Off Instant Payment With VoP

*Owner: Frontend / Payments*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

A logged-in customer initiates a single instant credit transfer (SCT Inst) to a beneficiary identified by IBAN. The flow includes mandatory Verification of Payee (per EU IPR Art. 5c), a possible semantic-duplicate warning, Strong Customer Authentication with dynamic linking (per PSD2 RTS Art. 5), and real-time status display from `RECEIVED` to `SETTLED` (or terminal alternatives).

This is the canonical narrative of the platform; it exercises A1 (reservation + ledger), A2 (clearing orchestration), A4 (entry point + SSE), A5 (fraud + sanctions), A6 (VoP).

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A4 (PSD2 customer endpoints + SSE), A6 (VoP), A2 (clearing), A1 (reservation + settlement), A5 (fraud + sanctions)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | |
| Client Web | yes | |
| Client iOS | yes | |
| Client Android | yes | |

## 4. Preconditions

- Customer is authenticated with `acr=loa1` at minimum; SCA at `loa3` will be obtained in step 8 of the flow with dynamic linking.
- Customer holds the `payments:initiate` scope.
- The chosen debtor account is `ACTIVE`, has currency that supports instant payments (NOK/EUR/SEK/DKK/USD), and has `availableBalance >= amount`.

## 5. Postconditions (happy path)

- A new payment exists in A2 with status `SETTLED`.
- A1 has applied a compound debit/credit event to the debtor and creditor.
- A `payment.status-updates` Kafka event was published; the GUI received it via SSE/push and surfaced `SETTLED` to the customer within 6 s p95 of "Confirm" tap.
- A receipt is downloadable; a `payment-status` notification is in the customer's inbox.
- An `auth.payment.initiated` audit event is recorded; an `auth.sca.dynamic-linking.bound` event is recorded with the dynamic-linking hash.

## 6. Main flow (happy path)

1. Customer navigates to **New payment** from the home screen.
2. GUI shows the payment form (`CLI-F20`):
   - Debtor account dropdown (own accounts; the primary account is preselected)
   - Creditor IBAN (text input with mod-97 live validation)
   - Creditor name (text input, max 140 chars)
   - Amount (numeric, locale-formatted)
   - Currency (defaults from debtor; may be changed within supported set)
   - Reference (free text or KID with mod-10/11 hint when toggle on)
   - Description (optional)
   - Execution mode toggle: `Instant` (default) / `Standard`
3. Customer fills the fields. As soon as both creditor IBAN (mod-97 valid) and creditor name are present, the GUI auto-runs **Verification of Payee** (`CLI-F22`):
   1. GUI calls `POST /api/v1/customer/payments/vop` (a small server endpoint that proxies A6 with the customer's auth context). Request: `{beneficiaryIban, statedName, requestId: <client-uuid>}`.
   2. A6 returns one of `MATCH | CLOSE_MATCH | NO_MATCH | NOT_FOUND | TIMEOUT | ERROR`.
   3. GUI renders the result as a traffic-light card under the creditor fields:
      - **MATCH (green):** "Verifisert" / "Verified" with a checkmark icon. Continue button enabled.
      - **CLOSE_MATCH (amber):** "Mente du **<actualName>**?" / "Did you mean **<actualName>**?" with two buttons: **Use suggestion** (replaces creditor name with the suggested value) and **Keep my entry** (logs the override; +0.3 fraud risk signal will be sent with the instruction).
      - **NO_MATCH (red):** "Navnet stemmer ikke med kontoen. Å fortsette kan føre til betaling til feil mottaker." / "The name does not match this account. Proceeding may cause payment to the wrong person." Two buttons: **Cancel payment** (default focus, primary visual weight) and **Override anyway** (secondary, with explicit re-confirmation dialog). Override is logged; the creditor name field remains the customer's value; **no actual name is shown**.
      - **NOT_FOUND (red error):** "Vi kan ikke verifisere denne kontoen. Sjekk IBAN-en." / "We cannot verify this account. Please check the IBAN." Continue is disabled. **No override** path.
      - **TIMEOUT / ERROR (amber):** "Verification could not be completed. You may retry or proceed with caution." Retry button or Override.
   4. GUI honours `GUI-SEC-09` structurally: when the API response has no `actualName` field, the rendering layer has no field to render. There is no client-side cache of A6 responses keyed by IBAN.
4. Customer reviews the form and taps **Continue**.
5. GUI computes the dynamic-linking hash candidate locally using the canonical form in `gui/architecture/DYNAMIC_LINKING.md` class `payment` — fields `{paymentId, amount, currency, creditorIban, creditorName, executionMode}`, RFC 8785 JCS + NFC + amount-as-string. Stored for comparison in step 9.
6. GUI calls `POST /api/v1/customer/payments` with the payment payload, the VoP outcome, the override flag (if any), `Idempotency-Key: <client-uuid>` header (`GUI-RES-02`).
7. A4 validates the request, runs semantic-duplicate detection (server-side; CLR-B14). If a probable duplicate is detected, A4 responds 409 Conflict with `Problem` type `duplicate-suspected` and the original payment summary.
   - GUI handles 409 by displaying a non-blocking warning card (`CLI-F23`): "Looks similar to NOK 1.234 to <name>, sent <time> ago." Two buttons: **Cancel** and **Confirm anyway** (sets the override flag and re-submits with the same idempotency key).
8. A4 initiates SCA via the identity service (UC-G51 detail):
   1. A4 → IdP: `POST /sca/start` with the dynamic-linking proof inputs (amount, currency, creditorIban, creditorName, transactionId).
   2. IdP returns `scaSessionId`, computed `dl_hash`, and challenge metadata.
   3. A4 returns to the GUI: `{paymentId, scaSessionId, dl_hash, challenge: {...}}`.
9. GUI renders the **SCA confirmation screen** (`CLI-F24`):
   - Title "Confirm your payment".
   - A read-only, visually distinct region showing **Beløp / Amount: NOK 1.234,56** and **Mottaker / Recipient: Ola Nordmann** (the creditor name as the user typed it). This region must not be obscurable by other UI elements (`GUI-SEC-08`).
   - GUI verifies that its locally computed hash from step 5 matches the server-supplied `dl_hash`; if not, the GUI aborts with `payment.dl-hash-mismatch` (severe — should never happen unless the GUI is compromised).
   - SCA challenge: in portfolio default (simulated BankID), an inline TOTP-style 6-digit field with a 5-min countdown.
10. Customer enters the code; GUI calls `POST /sca/{scaSessionId}/verify`. IdP verifies and (if successful) updates the access-token claim cache to bind `dl_hash` to the customer's session for this transactionId.
11. GUI calls `POST /api/v1/customer/payments/{paymentId}/submit` (or A4 auto-submits on SCA verify; see Open question 1). A4 verifies the access token's `dl_hash` claim matches the payment fields; if mismatched, payment rejected `403 dynamic-linking-mismatch`.
12. A4 publishes to `payment.instant-instructions` (existing Kafka contract). A2 begins orchestration (existing flow: `RECEIVED → AWAITING_RESERVATION → AWAITING_FRAUD → SETTLING → SETTLED`).
13. GUI navigates to the **Status / receipt screen** and opens an SSE connection to `GET /api/v1/customer/payments/stream?token=<streamToken>` (token obtained via a separate one-time-token endpoint per ARCHITECTURE_DECISIONS G.5b).
14. GUI receives status events as A2 progresses; updates a stepper UI:
    ```
    Sent ──► Reserving funds ──► Checking ──► Settling ──► Done
                ●                ○             ○            ○
    ```
    Each event includes `paymentId`, `status`, `transitionTs`, optional `reason`.
15. On `SETTLED`, the stepper shows all checkmarks; the screen reveals payment ID, timestamp, "Download receipt" button (PDF), and a "Done" button to return home. A `payment-status` notification appears in the inbox; on mobile, a system push notification arrives.
16. Audit events emitted at the corresponding service boundaries (existing contracts).

**Wall clock budget for steps 12 → 15:** backend SLA p95 ≤ 5 s (A2 instant path); GUI rendering overhead ≤ 1 s; total ≤ 6 s p95 (`CLI-NF02`).

## 7. Alternative flows

### AF-01 — Insufficient available balance

1. A1 returns `REJECTED reservationFailed` from the reservation step.
2. A2 publishes `payment.status-updates` with `REJECTED` and `reason=INSUFFICIENT_FUNDS`.
3. GUI receives via SSE; stepper turns the **Reserving funds** step red; full screen shows `payments.error.insufficient-funds` with the customer's current available balance and the requested amount, plus a **Top up** button (deep-link to UC-G02 account view) and a **Try smaller amount** button (returns to the form, preserves all fields except amount).

### AF-02 — Sanctions hit (BLOCK)

1. A5 returns `BLOCK` with `sanctionsResult=HIT`. A2 publishes `REJECTED` with `reason=SANCTIONS_HIT`.
2. GUI renders the canonical `payments.error.sanctions-blocked` copy (`CLI-F84`) — no override path visible to customer; reference id displayed.
3. Sanctions hit notification (`sanctions-block`) is delivered to inbox + email + push.

### AF-03 — Fraud HOLD (REVIEW decision)

1. A5 returns `REVIEW`. A2 publishes `HELD` status.
2. GUI shows the **Pending review** state; explains the case will be reviewed within 1 h; offers a **Notify me when complete** toggle (default on).
3. `fraud-hold` notification delivered.
4. Customer can monitor status from the payment detail screen until A5 case is decided (UC-G19 details the customer-side experience; UC-G31..G33 details the analyst experience).

### AF-04 — Fraud BLOCK

Same as AF-02 but with `payments.error.fraud-blocked` copy. Customer cannot retry without contacting support.

### AF-05 — VoP override required (NO_MATCH override)

1. Customer chooses "Override anyway" on the NO_MATCH card.
2. GUI shows a confirmation dialog: "We strongly recommend cancelling. Are you sure you want to send NOK X to an account where the name does not match? Your decision is logged." with options **Cancel** (default) and **Yes, send anyway**.
3. On confirm, GUI proceeds to step 4 of main flow with `vopPayerOverride=true`. The fraud engine treats this as a +0.3 score increment (existing contract). If A5 returns BLOCK, AF-04 applies.

### AF-06 — SCA timeout (5 min elapses)

1. IdP marks the SCA session expired.
2. GUI displays `auth.sca.timeout` with **Try again** and **Cancel** buttons.
3. Try again: re-runs step 8.
4. Cancel: payment is not submitted; A4 had no published instruction yet, so no cleanup is needed beyond clearing client-side state.

### AF-07 — SCA wrong code (3 attempts, then 5-min lockout)

Same as UC-G01 AF-03.

### AF-08 — SSE disconnect during status streaming

1. EventSource error / close (network blip).
2. GUI begins exponential backoff reconnect (1s, 2s, 4s, 8s, 15s cap).
3. On reconnect, GUI calls `GET /api/v1/customer/payments/{paymentId}` once to reconcile (per `GUI-RES-05`); resumes streaming.
4. If reconciliation shows a terminal status (`SETTLED`/`REJECTED`/`HELD`/`RETURNED`), GUI ends the stream and renders the final state.

### AF-09 — A4 outage during submit

1. The submit call returns 503 / fails timeout.
2. GUI shows a retryable error with the **Try again** button. The `Idempotency-Key` is preserved so a retry will not double-submit.
3. After 3 failed retries, GUI offers "Cancel and start over" — the payment is presumed not initiated; no cleanup needed.

### AF-10 — Account becomes FROZEN between form fill and submit

1. A4 returns 403 `account-frozen`.
2. GUI shows `payments.error.account-frozen` with reference id and "Contact support" link.

### AF-11 — Currency not supported on instant rail

1. A4 returns 422 `instant-not-supported-for-currency`.
2. GUI offers to switch execution mode to Standard (UC-G06) or cancel.

### AF-12 — Customer dismisses the SCA prompt deliberately

1. GUI returns to the form with all fields preserved.
2. A4 had not yet received a submit call (SCA precedes submit in our flow); no cleanup needed.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Network timeout on VoP | 1.5 s timeout (UI; backend timeout 500 ms) | Treat as `TIMEOUT`; AF Retry option in card |
| Network timeout on submit | 12 s | Show retryable error; preserve `Idempotency-Key` |
| Network timeout on status stream | EventSource error | AF-08 |
| Server returns RFC 9457 unknown `type` | Unknown problem-type in error map | Show generic `error.unknown` with reference id and the original `instance` URI; log to observability |
| Local hash mismatch with server `dl_hash` (step 9) | Local SHA-256 != server `dl_hash` | Abort flow; severe security event; clear all draft state; log |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F20..F29`, `CLI-F22`, `CLI-NF02`, `GUI-SEC-08..09`, `GUI-RES-01..05`, `GUI-A11Y-06..07`
- `system_requirements.md` — A2-F20..F30 (instant path), A6-F* (VoP), A5-F* (fraud + sanctions)
- `INTERFACE_CONTRACTS.md` — payment.instant-instructions, payment.balance-reservation, payment.instant-fraud-check-*, payment.instant-settlement-events, payment.status-updates
- `architecture/ARCHITECTURE_DESIGN.md` — §10 (real-time status), `gui/architecture/ARCHITECTURE_DESIGN.md` — §10 (status streaming)

## 10. API operations involved

- `POST /api/v1/customer/payments/vop` (`customer-payments.yaml`) — proxies A6
- `POST /api/v1/customer/payments` (`customer-payments.yaml`) — submits
- `POST /api/v1/customer/payments/{paymentId}/submit` (alternative split per Open Q1)
- `POST /sca/start` and `POST /sca/{sessionId}/verify` (`identity.yaml`)
- `POST /api/v1/customer/payments/{paymentId}/stream-token` (`customer-payments.yaml`)
- `GET  /api/v1/customer/payments/stream?token=<>` (SSE) (`customer-payments.yaml`)
- `GET  /api/v1/customer/payments/{paymentId}` (`customer-payments.yaml`)

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `client-web/src/features/payments/__tests__/dl-hash.test.ts`, `vop-card.test.tsx` | `client-ios/.../Tests/PaymentInstantTests.swift` | `client-android/.../payments/PaymentInstantTest.kt` |
| Component | `client-web/src/features/payments/__tests__/payment-form.test.tsx`, `sca-screen.test.tsx`, `status-stream.test.tsx` | `client-ios/.../Tests/PaymentFormViewTests.swift` (ViewInspector) | `client-android/.../payments/PaymentFormScreenTest.kt` (Compose UI test) |
| Contract (Pact) | `client-web/.../payments.pact.test.ts` | `client-ios/.../PaymentsPactTests.swift` | `client-android/.../PaymentsPactTest.kt` |
| E2E | `client-web/e2e/payment-instant-vop.spec.ts` | `client-ios/.../UITests/PaymentInstantUITests.swift` | `client-android/.../androidTest/.../PaymentInstantE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/payment_instant_vop_match.json`, `..._vop_close_match.json`, `..._vop_no_match_override.json`, `..._semantic_duplicate.json` | (same) | (same) |

## 12. UX notes

- The traffic-light VoP card uses both colour and a distinct icon per result (`GUI-A11Y-03`).
- The amount and recipient region on the SCA screen has explicit `aria-label`s in `<currency> <amount>` form for screen readers (`GUI-A11Y-07`).
- The 5-min SCA countdown announces every minute and at 30s/15s remaining (`GUI-A11Y-06`).
- Mobile: the SCA screen sets `FLAG_SECURE` (Android) and obscures fields when `UIScreen.main.isCaptured` is true (iOS) per ARCHITECTURE_DECISIONS G.13b.
- The status stepper uses motion (gentle slide-in per step) but respects `prefers-reduced-motion` (web) and the platform reduce-motion settings (mobile).
- The "Override anyway" buttons in NO_MATCH and semantic-duplicate flows have warning-coloured background (not the primary brand colour) to discourage casual taps.

## 13. Open questions

1. **Single-call vs split-call submit-after-SCA.** Two architectures for steps 8–11:
   - (a) GUI submits payment → A4 returns 401-with-SCA-required → GUI does SCA → GUI re-submits with SCA token claim.
   - (b) GUI initiates payment in `PENDING_SCA` state → SCA → GUI calls `/submit`.
   Option (a) is fewer round-trips; option (b) is clearer state. Defer to Phase-1 review for decision G.21.
2. **What happens on duplicate-suspected after SCA?** Currently we duplicate-check before SCA. If we move duplicate-check after, the dynamic-linking proof needs to be re-issued for the override. *Defer to Phase-1.*
3. **Should we offer "Schedule for later" as a fallback when instant returns `instant-not-supported`?** *Defer.*
