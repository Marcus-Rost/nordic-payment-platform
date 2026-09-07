# UC-G16 — Manage Trusted Beneficiaries

*Owner: Frontend / Payments*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

The customer maintains a list of trusted beneficiaries. Trusted beneficiaries are exempt from per-payment SCA for amounts up to the configured low-value threshold (per PSD2 RTS Art. 13). Adding a trusted beneficiary requires SCA; editing/removing does not. Payment initiations to a trusted beneficiary present a visual badge and may skip SCA if the exemption criteria are met.

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A3 (beneficiary store), identity service (SCA on add)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | |
| Client Web | yes | |
| Client iOS | yes | |
| Client Android | yes | |

## 4. Preconditions

- Customer is authenticated with `beneficiaries:manage`.

## 5. Postconditions

- Beneficiary record exists/updated/removed in A3.
- Audit events `beneficiary.created` / `beneficiary.updated` / `beneficiary.removed`.

## 6. Main flow (happy path)

### 6.1 List

1. Customer navigates to **Mottakere / Beneficiaries**.
2. GUI fetches `GET /api/v1/customer/beneficiaries`.
3. List rows: name, masked IBAN, bank (derived from BIC/BankCode), last paid, total YTD.
4. Actions: **Legg til / Add** (top-right), **Edit**, **Remove** (per row, via overflow menu).

### 6.2 Add

1. Customer taps **Legg til**.
2. Form: name (140 chars), IBAN (mod-97 live validation), optional notes.
3. GUI runs VoP (`CLI-F22`) on the entered IBAN + name.
4. VoP outcome gates the flow:
   - MATCH → proceed
   - CLOSE_MATCH → offer suggestion; customer can override or accept
   - NO_MATCH → override required (dialog + log)
   - NOT_FOUND → hard stop
5. Customer confirms; `POST /api/v1/customer/beneficiaries` issued.
6. A3 responds with `PENDING_SCA`; SCA (UC-G51) runs with dynamic linking to `{name, iban}`.
7. On success, beneficiary added; list refreshes.

### 6.3 Edit (notes only)

1. Customer taps Edit on a row.
2. Dialog edits the `notes` field (name and IBAN are immutable — to change those, customer removes and re-adds).
3. `PATCH /api/v1/customer/beneficiaries/{id}` with `{notes}`. No SCA.

### 6.4 Remove

1. Customer taps Remove.
2. Confirmation dialog: "Fjern mottakeren fra dine lagrede mottakere? Dette avbryter IKKE eventuelle aktive AvtaleGiro eller VRP — disse må revokeres separat."
3. `DELETE /api/v1/customer/beneficiaries/{id}`. No SCA.
4. Removal cascades: any payment form pre-filled with this beneficiary loses its "trusted" badge on next render.

## 7. Alternative flows

### AF-01 — Duplicate IBAN at add

1. A3 returns 409 `duplicate-iban` with existing beneficiary reference. GUI offers to open the existing record instead.

### AF-02 — Search for a beneficiary in a large list

1. Search bar filters client-side by name substring or server-side if list > 100 entries.

### AF-03 — Mass-import via CSV

1. *Out of MVP.*

### AF-04 — Export beneficiary list

1. Available via UC-G24 GDPR export; no UI-level export.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 5xx on POST/DELETE/PATCH | Response | Retryable with idempotency key |
| VoP NOT_FOUND at add | VoP card | Cannot save; customer prompted to re-check IBAN |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F60`, `CLI-F61`, `CLI-F62`, `GUI-SEC-08`, `GUI-SEC-09`
- `system_requirements.md` — A3-F70..F74 (beneficiaries)

## 10. API operations involved

- `GET    /api/v1/customer/beneficiaries` (`customer-beneficiaries.yaml`)
- `POST   /api/v1/customer/beneficiaries`
- `PATCH  /api/v1/customer/beneficiaries/{id}`
- `DELETE /api/v1/customer/beneficiaries/{id}`

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `.../beneficiaries/__tests__/row.test.tsx`, `add-dialog.test.tsx` | `.../Tests/BeneficiaryAddTests.swift` | `.../beneficiaries/BeneficiaryAddTest.kt` |
| Component | `.../beneficiaries/__tests__/beneficiary-list.test.tsx` | `.../Tests/BeneficiaryListViewTests.swift` | `.../BeneficiaryListScreenTest.kt` |
| Contract | `.../beneficiaries.pact.test.ts` | `.../BeneficiariesPactTests.swift` | `.../BeneficiariesPactTest.kt` |
| E2E | `client-web/e2e/beneficiaries-manage.spec.ts` | `.../UITests/BeneficiariesManageUITests.swift` | `.../androidTest/.../BeneficiariesManageE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/beneficiary_add_match.json`, `beneficiary_remove.json` | (same) | (same) |

## 12. UX notes

- The "Trusted" badge in the payment form (UC-G05) links to this screen.
- Notes field suggestion: "Mormor", "Leverandør X, prosjekt Y" — personal reminders. Not merchant-visible.
- Removal copy is emphasised because deletion does not undo mandates (see UC-G09).

## 13. Open questions

- Whether to automatically surface frequently paid counterparties as "suggested beneficiaries". *Defer — privacy-sensitive auto-suggestion needs design review.*
