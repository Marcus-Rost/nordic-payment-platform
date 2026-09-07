# UC-G04 — Account Statement Export (PDF, JSON, camt.053)

*Owner: Frontend / Accounts*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

The customer requests a statement of an account for a chosen period and downloads it in one of three formats: PDF (human-readable, bank-letterhead), JSON (machine-readable, the canonical transaction record), or camt.053 XML (ISO 20022 bank-to-customer statement for accounting-software import).

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A4 (export orchestration), A1 (source data), a rendering worker for PDF / XML

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | Admin export is part of UC-G40 |
| Client Web | yes | Direct download |
| Client iOS | yes | Native share sheet after download |
| Client Android | yes | Native share sheet after download |

## 4. Preconditions

- Customer is authenticated with `transactions:read`.
- The account exists and the customer owns it (A1 enforces).

## 5. Postconditions

- A statement file is available to the customer for download (synchronous if small, asynchronous via ready-link for large periods).
- A `export.statement` audit event is recorded with actor, account, period, format, and bytes emitted.

## 6. Main flow (happy path)

1. From the account detail (UC-G03), customer taps **Eksporter kontoutdrag** / **Export statement**.
2. GUI opens a dialog / sheet with:
   - Period: presets (This month, Last month, This year, Last year, Custom) + custom date pickers (start, end; both inclusive)
   - Format: `PDF` (default) / `JSON` / `camt.053 XML`
   - Include: `All transactions` (default) / `Booked only` / `Booked + reservations`
3. Customer confirms.
4. GUI calls `POST /api/v1/customer/accounts/{accountId}/statements` with payload `{startDate, endDate, format, scope}`. Response:
   - Small result (< 500 transactions or < 2 MB): synchronous — `200 OK` with `Location` header pointing to the download URL. GUI initiates a direct download.
   - Larger result: `202 Accepted` with `{requestId, estimatedReady}` + `Location` to the status URL. GUI shows "Exporting…" with progress indicator and polls `GET /api/v1/customer/statements/{requestId}` every 2 s with exponential backoff to 10 s.
5. When status is `READY`, GUI triggers the download from the `downloadUrl`.
6. The download URL is a signed, single-use, expiring URL (10 minutes TTL, one-time-token). A second access returns 410 Gone; customer requests again.
7. The file opens / saves:
   - Web: browser native download (PDF/XML/JSON served with correct `Content-Type` and `Content-Disposition: attachment`).
   - Mobile: file is written to a sandboxed `Documents/Statements/` and the native share sheet is invoked.

## 7. Alternative flows

### AF-01 — Empty period (no transactions)

1. A4 returns 200 but with `txCount=0`. GUI shows a confirmation dialog: "Ingen transaksjoner i perioden. Vil du likevel generere et dokument?" / "No transactions in period. Generate document anyway?" If yes, a zero-transaction statement is produced with opening and closing balance.

### AF-02 — Period crosses an account status change (FROZEN start/end)

1. The statement body notes the status change with a highlighted row and the date.

### AF-03 — Multi-currency account (future)

1. Out of scope for MVP — accounts are single-currency (A1-F02). Dialog currency defaults from the account; no currency picker shown.

### AF-04 — Customer cancels async export

1. While polling, customer taps Cancel. GUI calls `DELETE /api/v1/customer/statements/{requestId}`. Backend cancels the worker if still running.

### AF-05 — Direct-request from a URL / deep link (marketing or external bookkeeping app)

1. Supported via the OAuth2 flow (UC-G17) — an accounting tool redirects the customer to the bank to grant `accounts:read` + `transactions:read` and then requests statements via the public API. Within the app, no special flow change.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 413 Payload Too Large (period > 2 years) | On POST | Dialog "Velg en periode på 2 år eller mindre" / "Choose a period of 2 years or less" |
| 503 worker unavailable | On POST or poll | Retryable error; request can be retried with the same idempotency key |
| Signed URL 410 Gone | On download | Dialog "Nedlastingslinken er utløpt. Be om ny." / "Download link expired. Request a new one." (one-tap regenerate) |
| Corrupted file (checksum mismatch, rare) | Client post-download check vs. ETag / integrity header | Discard; auto-retry once |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F13`, `GUI-SEC-11`, `GUI-OPS-03`
- `system_requirements.md` — A1-F14 (statement generation), A1-F15 (camt.053 format compliance)

## 10. API operations involved

- `POST /api/v1/customer/accounts/{accountId}/statements` (`customer-accounts.yaml`)
- `GET  /api/v1/customer/statements/{requestId}` (`customer-accounts.yaml`)
- `GET  /api/v1/customer/statements/{requestId}/download` (`customer-accounts.yaml`) — signed URL
- `DELETE /api/v1/customer/statements/{requestId}` (`customer-accounts.yaml`)

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `.../statements/__tests__/dialog.test.tsx`, `poller.test.ts` | `.../Tests/StatementDialogTests.swift` | `.../statements/StatementDialogTest.kt` |
| Component | `.../statements/__tests__/flow.test.tsx` | `.../Tests/StatementFlowTests.swift` | `.../StatementFlowTest.kt` |
| Contract | `.../statements.pact.test.ts` | `.../StatementsPactTests.swift` | `.../StatementsPactTest.kt` |
| E2E | `client-web/e2e/statement-export.spec.ts` | `.../UITests/StatementExportUITests.swift` | `.../androidTest/.../StatementExportE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/statement_pdf_last_month.json`, `statement_camt053_custom_range.json` | (same) | (same) |

## 12. UX notes

- The format picker shows a one-line hint per option: "PDF — til utskrift / for printing", "JSON — for egne systemer / for your own systems", "camt.053 — til regnskapsprogram / for accounting software".
- The progress indicator on async exports uses a determinate bar if the server supplies `progress` in the status response; otherwise indeterminate.
- Receipt PDFs (UC-G03) and statement PDFs share a rendering library and the same bank-letterhead template; statements add opening/closing balance and a period summary row.

## 13. Open questions

- Whether to offer CSV as a fourth format. *Defer — JSON + a tiny client-side converter covers this; not priority.*
- Whether the signed URL TTL should be shorter than 10 min for portfolio default. *Defer to Phase-1 security review.*
