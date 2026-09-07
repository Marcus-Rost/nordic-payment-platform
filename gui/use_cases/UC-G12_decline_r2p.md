# UC-G12 — Decline an R2P

*Owner: Frontend / Payments / R2P*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

The customer refuses an incoming R2P with a reason code and optional explanation. Decline is final; the merchant receives a `pain.014` rejection message and must take any further action out-of-band.

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A3 (R2P store + outbound rejection message)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | |
| Client Web | yes | |
| Client iOS | yes | |
| Client Android | yes | |

## 4. Preconditions

- R2P exists in `PENDING` state.
- Customer holds `payments:initiate` (same scope bucket as accept).

## 5. Postconditions

- R2P `status=DECLINED` with `reasonCode` and `reasonNote` persisted.
- A3 emits a rejection message via `pain.014` to the upstream merchant network.
- Audit event `r2p.declined`.
- Inbox notification (customer-side, informational).

## 6. Main flow (happy path)

1. From the R2P detail (UC-G11 §6.2), customer taps **Avslå / Decline**.
2. Dialog appears: reason dropdown and free-text field:
   - `ALREADY_PAID` — already paid by other means
   - `WRONG_AMOUNT` — disputes the amount
   - `INVOICE_INCORRECT` — invoice has wrong details
   - `OTHER` — must include mandatory free-text ≥ 10 chars
3. Customer selects a reason, writes an optional note (max 280 chars — merchant-visible), confirms.
4. GUI calls `POST /api/v1/customer/r2p/{requestId}/decline` with `{reasonCode, reasonNote}`.
5. A3 transitions to `DECLINED`, emits `pain.014` with the chosen rejection reason code (`BE05 UnrecognisedInitiatingParty`, `BE09 Insufficient` → mapped where applicable; mapping table in A3 docs), and sends the inbox notification.
6. GUI shows a confirmation and a link back to the inbox.

## 7. Alternative flows

### AF-01 — R2P changes state concurrently (already cancelled by merchant)

1. A3 returns 410. GUI shows "Denne forespørselen er ikke lenger aktiv." and refreshes.

### AF-02 — Repeat decline (double-click or two devices)

1. A3 returns 409. GUI shows the latest state.

### AF-03 — Customer selects OTHER without filling the free-text

1. Inline validation; submit disabled.

### AF-04 — Free-text contains PII-like content (e.g., a phone number)

1. GUI does not parse/filter; all text goes through to the merchant. A disclaimer is shown under the field: "Teksten blir synlig for avsenderen."

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 5xx on POST | Response | Retryable with idempotency key |
| Stale R2P (expired) | 410 on POST | Refresh + appropriate copy |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F51`, `CLI-F53`, `GUI-I18N-02`
- `system_requirements.md` — A3-F54, A3-F58 (outbound rejection)

## 10. API operations involved

- `POST /api/v1/customer/r2p/{requestId}/decline` (`customer-r2p.yaml`)

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `.../r2p/__tests__/decline-dialog.test.tsx` | `.../Tests/R2pDeclineTests.swift` | `.../r2p/R2pDeclineTest.kt` |
| Component | shares `.../r2p/__tests__/r2p-detail.test.tsx` | (same) | (same) |
| Contract | shares `.../r2p.pact` | (same) | (same) |
| E2E | `client-web/e2e/r2p-decline.spec.ts` | `.../UITests/R2pDeclineUITests.swift` | `.../androidTest/.../R2pDeclineE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/r2p_decline_already_paid.json` | (same) | (same) |

## 12. UX notes

- The "Avslå" button uses the warning tone but not red — decline is a normal customer action.
- The note field has a char-counter and advisory on merchant visibility.
- After decline, the detail page still shows the R2P's history (for recordkeeping).

## 13. Open questions

- Whether to offer "undo within 30 s" for accidental declines. *Decision: no — decline fires a `pain.014` immediately; undo would require a revert message the spec doesn't define.*
