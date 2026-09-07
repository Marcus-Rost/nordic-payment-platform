# UC-G09 — Manage Existing Mandates (Modify, Revoke)

*Owner: Frontend / Payments*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

The customer views their list of active AvtaleGiro mandates, inspects details, modifies the max-amount of a mandate, or revokes a mandate. Creation is UC-G08; creation of a new merchant relationship is modelled as a fresh mandate rather than editing an existing one.

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A3 (mandate store), identity service (SCA on modifications)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | |
| Client Web | yes | |
| Client iOS | yes | |
| Client Android | yes | |

## 4. Preconditions

- Customer is authenticated with `mandates:manage`.

## 5. Postconditions

- On modify: mandate has the new max-amount; dynamic-linking proof is re-issued and bound to the new `{merchantId, maxAmount, debtorAccount}`.
- On revoke: mandate has `status=REVOKED`, `revokedAt=now`; no further debits will be honoured.
- Audit events `mandate.modified` / `mandate.revoked`.
- Inbox notification.

## 6. Main flow (happy path)

### 6.1 Listing

1. Customer navigates to **Faste betalinger / Recurring payments**.
2. GUI fetches `GET /api/v1/customer/mandates?status=ACTIVE|PAUSED|REVOKED|EXPIRED` (default ACTIVE).
3. List row per mandate: merchant (name + logo), debtor account, max amount, frequency, last execution, next expected execution.
4. Tap a row → detail page.

### 6.2 Detail page

- Full mandate metadata (merchant avtaleID, start/end dates, created by, last modified by).
- History list: executions that debited the account (each linking to the settlement transaction in UC-G03).
- Actions: **Endre maks beløp / Modify max amount**, **Pause**, **Revoker / Revoke**.

### 6.3 Modify max amount

1. Customer taps **Endre maks beløp**.
2. Dialog with current max; customer enters new value.
3. GUI calls `PATCH /api/v1/customer/mandates/{mandateId}` with `{maxAmount: <new>}`.
4. A3 responds with `PENDING_SCA`; SCA (UC-G51) runs with dynamic linking to the new values.
5. On success, mandate updated; notification sent.

### 6.4 Pause (temporary hold)

1. Customer taps **Pause**, optionally with a future "unpause date".
2. `PATCH /api/v1/customer/mandates/{mandateId}` with `{status: "PAUSED", resumeAt: <date?>}`.
3. No SCA required for pausing (customer-positive operation: less money moves).
4. Mandate shown with PAUSED badge; merchant's debit attempts are refused during pause.

### 6.5 Revoke

1. Customer taps **Revoker**.
2. Confirmation dialog with explicit warning: "Når avtalen er revokert, kan ikke bedriften trekke mer. Eventuelle forfall må betales manuelt." / "Once revoked, the merchant cannot debit further. Outstanding invoices must be paid manually."
3. Customer confirms; GUI calls `DELETE /api/v1/customer/mandates/{mandateId}`.
4. No SCA required for revocation (customer-positive; aligned with PSD2 "customer may always revoke a mandate").
5. Mandate transitions to REVOKED; notification sent; history view remains visible.

## 7. Alternative flows

### AF-01 — Filter by status REVOKED

1. Filter dropdown: customer sees revoked mandates (read-only, for audit-trail/recordkeeping).

### AF-02 — Mandate expired naturally (end-date reached)

1. Listed under the `EXPIRED` filter; no actions except view history.

### AF-03 — Attempt to modify a REVOKED mandate

1. Buttons greyed out; tooltip "Denne avtalen er revokert — opprett en ny for å starte på nytt."

### AF-04 — Trying to reduce max below the last executed debit

1. Warning ("Siste trekk var kr 3200 — en lavere maks kan blokkere fremtidige forfall.") but the modification is allowed; customer accepts the risk.

### AF-05 — Revoke while a debit is in-flight (edge)

1. A3 returns 409 `pending-execution`: "Et trekk er under behandling. Vent et øyeblikk eller kontakt support for hjelp."

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 404 on PATCH/DELETE (mandate concurrently revoked) | Response | Refresh list; show "Avtalen er allerede oppdatert." |
| SCA failed on modify | UC-G51 | Mandate unchanged; return to detail |
| 5xx | Response | Retryable with idempotency key |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F42`, `GUI-SEC-08`
- `system_requirements.md` — A3-F30..F36

## 10. API operations involved

- `GET    /api/v1/customer/mandates` (`customer-mandates.yaml`)
- `GET    /api/v1/customer/mandates/{mandateId}`
- `PATCH  /api/v1/customer/mandates/{mandateId}`
- `DELETE /api/v1/customer/mandates/{mandateId}`

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `.../mandates/__tests__/modify-dialog.test.tsx` | `.../Tests/MandateModifyTests.swift` | `.../mandates/MandateModifyTest.kt` |
| Component | `.../mandates/__tests__/mandate-list.test.tsx`, `mandate-detail.test.tsx` | `.../Tests/MandateListViewTests.swift` | `.../MandateListScreenTest.kt` |
| Contract | shares `mandates.pact` | (same) | (same) |
| E2E | `client-web/e2e/mandate-manage.spec.ts` | `.../UITests/MandateManageUITests.swift` | `.../androidTest/.../MandateManageE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/mandate_modify_max_amount.json`, `mandate_revoke.json` | (same) | (same) |

## 12. UX notes

- Revocation is destructive-feeling; confirmation dialog uses a warning tone (amber, not red — revocation is legitimate and routine).
- Paused mandates are visually distinguishable but not hidden.
- The "History" section of the detail view reuses the transaction row component from UC-G03 for consistency.

## 13. Open questions

- Whether to allow changing the debtor account on an existing mandate. *Decision: no — modelled as revoke + create with the new account. Keeps the dynamic-linking proof simple.*
