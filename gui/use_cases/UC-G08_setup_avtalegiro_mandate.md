# UC-G08 — Set Up an AvtaleGiro Mandate

*Owner: Frontend / Payments*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

The customer creates an AvtaleGiro mandate — an authorisation for a specific merchant to debit the customer's account, up to a configurable maximum per payment, at the frequency the merchant requests invoices. AvtaleGiro is the Norwegian domestic direct-debit scheme; the contract and execution are per Finansnæringens Autorisasjons- og Godkjenningsordning (FinansAGO) and Bits' AvtaleGiro rule book.

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A3 (mandate store + merchant registry), A6 (VoP on the merchant), identity service (SCA)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | |
| Client Web | yes | |
| Client iOS | yes | |
| Client Android | yes | |

## 4. Preconditions

- Customer is authenticated with `mandates:manage`.
- The merchant exists in the AvtaleGiro merchant registry (looked up by name or avtaleID).

## 5. Postconditions

- A mandate record in A3 with `status=ACTIVE`, the merchant, debtor account, maxAmount, frequency, start date, optional end date.
- A `mandate.created` audit event recorded.
- An inbox notification.

## 6. Main flow (happy path)

1. Customer navigates to **Faste betalinger / Recurring payments** → **Opprett AvtaleGiro / Create mandate**.
2. GUI shows a merchant search: text input searches `GET /api/v1/customer/mandates/merchants?q=…`. Autocomplete dropdown shows merchant name, logo, avtaleID.
3. Customer selects a merchant.
4. Form renders:
   - Debtor account (dropdown of ACTIVE accounts)
   - Max amount per payment (numeric, required) — with an info tooltip explaining AvtaleGiro protects the customer by bounding each debit
   - Frequency hint (read-only, supplied by the merchant: `MONTHLY`, `QUARTERLY`, `ON_INVOICE`)
   - Start date (default today; max: today + 6 months)
   - End date (optional; blank = until revoked)
   - Notification preferences: "Varsel før trekk / Notify before each debit" (default on; 3 days prior)
5. GUI runs VoP on the merchant's IBAN (from the merchant registry, not user input — reduces NO_MATCH class). In practice MATCH is expected since the merchant is known; CLOSE_MATCH/NO_MATCH would indicate a registry issue and block creation.
6. Customer reviews a summary and taps **Opprett / Create**.
7. GUI calls `POST /api/v1/customer/mandates` with the payload (including `Idempotency-Key`).
8. A3 responds with a `PENDING_SCA` mandate + SCA challenge metadata.
9. SCA flow (UC-G51) runs; dynamic linking is bound to `{merchantId, maxAmount, debtorAccount}`.
10. On success, A3 transitions mandate to ACTIVE; GUI shows a confirmation screen with the mandate summary and a "Se mine AvtaleGiro / See my mandates" link (UC-G09).
11. Notification sent.

## 7. Alternative flows

### AF-01 — Merchant not found in registry

1. GUI shows "Denne bedriften finnes ikke i AvtaleGiro-registeret." / "This merchant is not in the AvtaleGiro registry." and offers to search by avtaleID instead (a 7-digit numeric the merchant typically prints on invoices).

### AF-02 — Merchant exists but not enrolled for AvtaleGiro (only e-invoice)

1. GUI explains the limitation and suggests UC-G11 (R2P) instead.

### AF-03 — Customer selects end date before start date

1. Inline validation message; submit blocked until corrected.

### AF-04 — Max amount set to 0 or negative

1. Inline validation.

### AF-05 — Customer has an existing ACTIVE mandate with the same merchant

1. GUI warns "Du har allerede AvtaleGiro med denne bedriften. Vil du endre eksisterende i stedet?" / "You already have an AvtaleGiro with this merchant. Do you want to modify the existing one instead?" with links to UC-G09.

### AF-06 — Account FROZEN

1. 403 `account-frozen`; GUI shows the canonical copy and a link to support.

### AF-07 — SCA failed / timeout

1. UC-G51 failure paths; mandate remains `PENDING_SCA` on server for 10 minutes then is garbage-collected.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 5xx on POST | Response | Retryable with same idempotency key |
| Merchant registry stale (VoP NOT_FOUND) | VoP response | Hard fail; show "Registeret er midlertidig utilgjengelig. Prøv igjen senere." |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F41`, `CLI-F42`, `GUI-SEC-08`, `GUI-I18N-03`
- `system_requirements.md` — A3-F30..F36 (AvtaleGiro mandates)

## 10. API operations involved

- `GET  /api/v1/customer/mandates/merchants` (`customer-mandates.yaml`)
- `POST /api/v1/customer/mandates` (`customer-mandates.yaml`)
- `GET  /api/v1/customer/mandates` (`customer-mandates.yaml`) — used by UC-G09
- `GET  /api/v1/customer/mandates/{mandateId}`
- SCA endpoints via identity service.

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `.../mandates/__tests__/merchant-search.test.ts`, `validation.test.ts` | `.../Tests/MerchantSearchTests.swift` | `.../mandates/MerchantSearchTest.kt` |
| Component | `.../mandates/__tests__/mandate-create.test.tsx` | `.../Tests/MandateCreateViewTests.swift` | `.../MandateCreateScreenTest.kt` |
| Contract | `.../mandates.pact.test.ts` | `.../MandatesPactTests.swift` | `.../MandatesPactTest.kt` |
| E2E | `client-web/e2e/mandate-create.spec.ts` | `.../UITests/MandateCreateUITests.swift` | `.../androidTest/.../MandateCreateE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/mandate_avtalegiro_monthly.json` | (same) | (same) |

## 12. UX notes

- Merchant logos are rendered at a size fitting the platform; absent logo → merchant initials in a coloured circle.
- The tooltip on "Max amount" uses an example with the customer's locale: "F.eks. hvis strømregningen pleier å være kr 1200 om sommeren og kr 3000 om vinteren, sett maks til kr 4000 for å være trygg."
- Notifications preference is prominent — customers consistently want pre-debit notifications, and the default is on.

## 13. Open questions

- Whether to offer a "propose new merchant for registry" flow if not found. *Defer; out of MVP.*
