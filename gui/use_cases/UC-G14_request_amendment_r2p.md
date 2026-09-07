# UC-G14 — Request Amendment to an R2P (Counter-Offer)

*Owner: Frontend / Payments / R2P*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

The customer asks the merchant to change some aspect of the R2P — typically amount or due date — and waits for a response. The R2P moves into `AMENDMENT_REQUESTED` state; the merchant may issue a new R2P (replacing the old) or refuse, in which case the original remains open with an "amendment refused" annotation.

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A3 (R2P store + outbound amendment request), the merchant (async response)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | |
| Client Web | yes | |
| Client iOS | yes | |
| Client Android | yes | |

## 4. Preconditions

- R2P exists in `PENDING` state.
- R2P's envelope allows amendments (a flag in the original R2P record; some merchants disallow).

## 5. Postconditions

- R2P `status=AMENDMENT_REQUESTED`.
- A3 has emitted an amendment request to the merchant (via the R2P scheme's `pain.014` amendment-request variant or equivalent).
- Audit event `r2p.amendment-requested`.

## 6. Main flow (happy path)

1. From R2P detail, customer taps **Be om endring / Request amendment**.
2. Dialog:
   - Proposed amount (defaults to original; editable)
   - Proposed due date (defaults to original; editable up to original + 30 days)
   - Reason (optional free-text, ≤ 280 chars, merchant-visible)
3. Customer submits.
4. GUI calls `POST /api/v1/customer/r2p/{requestId}/amend` with `{proposedAmount, proposedDueDate, reason}`.
5. A3 transitions R2P to `AMENDMENT_REQUESTED` and forwards the amendment request to the merchant.
6. Inbox shows the R2P under "Waiting for merchant" filter.
7. When the merchant responds:
   - **ACCEPTED** → A3 generates a new R2P with the amended values, the old R2P is marked `REPLACED`, new R2P appears in Pending (customer can accept/decline/defer again).
   - **DECLINED** → A3 transitions the old R2P back to `PENDING` with an `amendmentRejected` flag that surfaces a banner on the detail page: "Avsenderen har avslått endringen. Du kan fortsatt betale, avslå eller be om ny endring."

## 7. Alternative flows

### AF-01 — Merchant's R2P disallowed amendment from the start

1. The **Be om endring** button is disabled; tooltip explains "Denne forespørselen tillater ikke endring."

### AF-02 — Proposed amount or date unchanged from original

1. Inline validation: at least one of `amount`, `dueDate` must differ.

### AF-03 — Merchant does not respond within 72 h (soft SLA)

1. Inbox banner: "Ingen svar — du kan fortsatt betale eller avslå."
2. No automatic state change; customer decides.

### AF-04 — R2P expires while awaiting amendment response

1. R2P transitions to `EXPIRED`; customer notified; no payment occurs.

### AF-05 — Customer changes mind before merchant responds

1. Customer can cancel the amendment: `POST /api/v1/customer/r2p/{requestId}/amend/cancel` → R2P returns to `PENDING`.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 5xx on POST | Response | Retryable with idempotency key |
| Merchant transport failure (A3 internal) | Delayed internal event | UI unchanged at submit time; A3 retries; if retries exhausted, customer is notified to try again later |
| Merchant rejects amendment and also cancels original | Merchant pattern | GUI handles cascaded events: UI shows final state (REPLACED with blank amount, or CANCELLED_BY_MERCHANT) |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F51` (the amend option)
- `system_requirements.md` — A3-F56 (amendment flow)

## 10. API operations involved

- `POST /api/v1/customer/r2p/{requestId}/amend` (`customer-r2p.yaml`)
- `POST /api/v1/customer/r2p/{requestId}/amend/cancel`

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `.../r2p/__tests__/amend-dialog.test.tsx` | `.../Tests/R2pAmendTests.swift` | `.../r2p/R2pAmendTest.kt` |
| Component | shares `.../r2p/__tests__/r2p-detail.test.tsx` | (same) | (same) |
| Contract | shares `.../r2p.pact` | (same) | (same) |
| E2E | `client-web/e2e/r2p-amend.spec.ts` | `.../UITests/R2pAmendUITests.swift` | `.../androidTest/.../R2pAmendE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/r2p_amend_amount.json`, `r2p_amend_rejected.json`, `r2p_amend_accepted_replaced.json` | (same) | (same) |

## 12. UX notes

- Amendment feels heavier than decline or defer — copy makes the merchant-in-the-loop nature explicit.
- "Waiting for merchant" inbox section has a clock/hourglass icon to distinguish from Pending.
- When the merchant accepts and replaces, the new R2P is prominently flagged "Erstatter forrige / Replaces previous" with a link back to the old record for history.

## 13. Open questions

- Whether to allow amendment of the creditor IBAN (e.g., merchant changed accounts). *Decision: no — changing the payee effectively creates a different relationship; that's a new R2P, not an amendment.*
