# UC-G11 — Receive and Act on an R2P (Accept Now)

*Owner: Frontend / Payments / R2P*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

An incoming Request-to-Pay (SRTP per EPC024) arrives in the customer's inbox. The customer reviews it and chooses **Accept & Pay Now** — the GUI pre-fills the payment form from the R2P record, runs VoP, SCA, and settlement as per UC-G05. Other R2P actions are UC-G12 (decline), UC-G13 (defer), UC-G14 (amend).

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A3 (R2P store), A4 (payment surface), A6 (VoP), A2 (clearing), A1 (ledger)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | |
| Client Web | yes | |
| Client iOS | yes | Deep-linkable from push notification (UC-G29) |
| Client Android | yes | Deep-linkable from push notification (UC-G29) |

## 4. Preconditions

- Customer has at least one R2P in state `PENDING` in A3.
- Customer is authenticated with `payments:initiate`.

## 5. Postconditions

- The selected R2P has `status=ACCEPTED` and a linked `paymentId`.
- A payment transitioned through the standard UC-G05 lifecycle.
- Audit events `r2p.accepted` and the UC-G05 payment events.

## 6. Main flow (happy path)

1. From the inbox (UC-G80-style panel — actually inbox is UC-G80 in notifications; R2P list is at `CLI-F50`), customer taps an R2P row.
2. R2P detail page renders:
   - Merchant (name, logo, verified badge if the merchant's eIDAS signature on the R2P is valid)
   - Amount + currency
   - Due date (with countdown if < 3 days)
   - Description, invoice reference
   - Link to invoice PDF (if merchant supplied one)
   - Actions: **Accept & Pay Now** (primary), **Accept & Pay Later** (secondary; UC-G13), **Decline** (tertiary; UC-G12), **Request amendment** (UC-G14).
3. Customer taps **Accept & Pay Now**.
4. GUI reads the R2P envelope and pre-fills the UC-G05 payment form:
   - Creditor IBAN + name from the R2P
   - Amount, currency, reference (prefilled with the R2P's structured reference, typically a KID)
   - Debtor account: pre-selects the customer's primary; customer can change
   - Execution mode: defaults to the R2P's requested rail (usually instant)
5. Flow continues from UC-G05 step 3 onward (VoP → SCA → SCA confirm → submit → status).
6. A3 listens for the `payment.status-updates` event and, on `SETTLED`, marks the R2P `ACCEPTED` with `paymentId` linked.
7. Confirmation screen is UC-G05's confirmation screen, with an added "Referanse / Invoice reference" block summarising the R2P.
8. The R2P disappears from the "Pending" filter and appears under "Completed".

## 7. Alternative flows

### AF-01 — R2P changes state concurrently (e.g., merchant cancelled it)

1. A3 returns 410 Gone on attempted accept.
2. GUI refreshes the R2P (its state shows `CANCELLED_BY_MERCHANT`) and shows the appropriate copy.

### AF-02 — R2P amount differs from current account available balance

1. Proceed to UC-G05 submission; AF-01 (insufficient funds) of UC-G05 will trigger if the balance is too low.

### AF-03 — Merchant signature invalid

1. Red warning at the top of the detail: "⚠ Vi kunne ikke bekrefte avsenderen. Sjekk fakturaen før betaling."
2. All actions remain enabled but the VoP result is pre-marked with extra caution.

### AF-04 — R2P already accepted (race / double-click)

1. A3 returns 409 with the existing payment reference. GUI shows the existing payment's status instead.

### AF-05 — VoP result NO_MATCH

1. UC-G05 AF-05 applies. The override dialog mentions "Dette forslaget kom fra en R2P — vi anbefaler å kontakte avsenderen før du fortsetter."

### AF-06 — R2P expired (due date passed)

1. GUI shows "Forfalt / Expired" badge. Accept action still works (banks allow late payment of R2Ps) but copy warns "Forfallsdato har passert; kontakt eventuell mottaker om avtale."

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 5xx on accept POST | Response | Retryable with idempotency key (same as UC-G05 §8) |
| SCA flow fails | UC-G51 | R2P remains `PENDING`; customer can retry |
| Merchant R2P record fetch 500 | Initial detail fetch | Retry button; if persistent, surfaced to operations |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F50`, `CLI-F51`, `CLI-F52`, `GUI-SEC-08`
- `system_requirements.md` — A3-F50..F57 (R2P)

## 10. API operations involved

- `GET  /api/v1/customer/r2p` (`customer-r2p.yaml`)
- `GET  /api/v1/customer/r2p/{requestId}` (`customer-r2p.yaml`)
- `POST /api/v1/customer/r2p/{requestId}/accept` (`customer-r2p.yaml`)
- Plus UC-G05's payment endpoints.

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `.../r2p/__tests__/prefill.test.ts` | `.../Tests/R2pPrefillTests.swift` | `.../r2p/R2pPrefillTest.kt` |
| Component | `.../r2p/__tests__/r2p-detail.test.tsx` | `.../Tests/R2pDetailViewTests.swift` | `.../R2pDetailScreenTest.kt` |
| Contract | `.../r2p.pact.test.ts` | `.../R2pPactTests.swift` | `.../R2pPactTest.kt` |
| E2E | `client-web/e2e/r2p-accept.spec.ts` | `.../UITests/R2pAcceptUITests.swift` | `.../androidTest/.../R2pAcceptE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/r2p_accept_happy.json`, `r2p_accept_merchant_unverified.json` | (same) | (same) |

## 12. UX notes

- Merchant verified-badge copy is consistent with TPP verification in UC-G10 — bound to eIDAS certificate validation.
- Due-date countdown uses colour + text (`GUI-A11Y-03`): < 3 days amber, overdue red.
- The detail page's "Invoice PDF" link opens in a new tab / system browser, never inline — avoids rendering untrusted content in-app.

## 13. Open questions

- Whether to offer "Accept with a note to merchant" (attach a short text to the payment reference). *Defer — reference already carries the R2P identifier; merchant's free text doesn't pass through SCT Inst.*
