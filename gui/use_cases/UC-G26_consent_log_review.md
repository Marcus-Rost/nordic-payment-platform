# UC-G26 — Consent Log Review

*Owner: Frontend / Privacy*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

The customer sees a chronological log of every consent event attributable to them — PSD2 and VRP grants, revocations, expirations, renewals, and marketing/notification-preference changes — as required by GDPR Art. 7(1) (proof of consent) and Art. 13/14 (information about processing). The log is read-only from the customer's perspective.

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** Audit query service (new `jrpay-audit-query` per gui_requirements §6.5) filtered on consent-related event types for this customer

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | Admin equivalent is UC-G40 (audit log search) |
| Client Web | yes | |
| Client iOS | yes | |
| Client Android | yes | |

## 4. Preconditions

- Customer is authenticated. No extra scope beyond basic login is required — GDPR requires free access to one's own consent history.

## 5. Postconditions

- The customer has viewed their consent history. A `read.consent-log` audit event is recorded (self-audit; the customer is *both* subject and actor).

## 6. Main flow (happy path)

1. Customer navigates to **Min personvern / My privacy** → **Samtykkelogg / Consent log**.
2. GUI fetches `GET /api/v1/customer/consent-log?from=<>&to=<>&page=0&size=50` (defaults to last 12 months).
3. Response rows:
   ```json
   {
     "eventId": "...",
     "timestamp": "2026-03-01T09:15:00Z",
     "type": "CONSENT_GRANTED | CONSENT_REVOKED | CONSENT_EXPIRED | CONSENT_RENEWED | MARKETING_OPTED_IN | MARKETING_OPTED_OUT",
     "category": "PSD2_AIS | PSD2_PIS | PSD2_CBPII | VRP | MARKETING | NOTIFICATION_PREF | COOKIE",
     "counterparty": "<TPP name | merchant | internal>",
     "scopes": ["accounts:read", ...],
     "accounts": ["<accountId>", ...],
     "outcome": "SUCCESS | DENIED | CANCELLED",
     "initiatedBy": "CUSTOMER | TPP | SYSTEM"
   }
   ```
4. List renders one row per event: icon per category, one-line summary ("Du ga tilgang til Fiken AS — AIS for 3 kontoer — 1. mars 2026 09:15"), and a chevron to expand full details.
5. Filters: category (multi-select), type (multi-select), counterparty search, date range.
6. Export: **Last ned som CSV / Download as CSV** produces a CSV of the current filtered set (GDPR Art. 15 machine-readable requirement). Uses the same signed-URL pattern as UC-G04.

## 7. Alternative flows

### AF-01 — Empty log (new customer)

1. Empty state: "Ingen samtykkehendelser ennå. Når du gir tilgang til tredjeparter eller endrer varselinnstillinger, dukker hendelsen opp her."

### AF-02 — Customer filters to only PSD2 events

1. Query filters `category=PSD2_*`; list updates; counts in filter chips update.

### AF-03 — Customer expands a row

1. Inline detail panel shows the full event payload, an i18n-localised human summary, the current consent status (if the event was a grant), a link to UC-G18 detail if still active.

### AF-04 — Customer searches by counterparty substring

1. Free-text search against the `counterparty.name` field (case-insensitive).

### AF-05 — Customer requests extended history (beyond 12 months)

1. A date picker allows up to 7 years (the bank's audit retention). Beyond 12 months, query runs as async — GUI shows a progress indicator and delivers results via inbox / email link.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 5xx on log fetch | Response | Retryable error card; last-known cache on mobile |
| Empty response with ill-formed query | 400 | Show "Juster filtrene og prøv igjen." |
| CSV export 5xx | Response | Retryable |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F94`, `GUI-PRV-04`, `GUI-I18N-*`
- `system_requirements.md` — audit events (existing), GDPR Art. 7(1), Art. 12
- `gui_requirements.md` §6.5 — audit-query service

## 10. API operations involved

- `GET  /api/v1/customer/consent-log` (`customer-privacy.yaml`)
- `POST /api/v1/customer/consent-log/export` (+ download + status endpoints mirroring UC-G04)

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `.../consent-log/__tests__/row.test.tsx`, `filters.test.ts` | `.../Tests/ConsentLogRowTests.swift` | `.../consents/ConsentLogRowTest.kt` |
| Component | `.../consent-log/__tests__/log.test.tsx` | `.../Tests/ConsentLogViewTests.swift` | `.../ConsentLogScreenTest.kt` |
| Contract | `.../consent-log.pact.test.ts` | `.../ConsentLogPactTests.swift` | `.../ConsentLogPactTest.kt` |
| E2E | `client-web/e2e/consent-log.spec.ts` | `.../UITests/ConsentLogUITests.swift` | `.../androidTest/.../ConsentLogE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/consent_log_browse.json`, `consent_log_export_csv.json` | (same) | (same) |

## 12. UX notes

- Category icons are WCAG-compliant and consistent with the equivalent icons in UC-G18.
- The log explicitly includes cookie-consent events for the web GUI so the customer has a complete record of what they agreed to (even if minimal per GUI-PRV-02).
- The detail panel includes a plain-language "What this means" paragraph per event type, not just the raw JSON.

## 13. Open questions

- Whether to include implicit consents (the "accept T&C at sign-up" event) in this log. *Decision: yes — that's the prototypical GDPR-relevant event.*
- Whether to show SYSTEM-initiated expirations as separate events. *Decision: yes — they are consent-status changes and the customer benefits from the visibility.*
