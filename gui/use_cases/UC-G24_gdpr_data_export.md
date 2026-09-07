# UC-G24 — GDPR Data Export (Right to Access, Art. 15)

*Owner: Frontend / Privacy*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

The customer requests a complete export of all personal data the bank holds about them. The export is delivered as a downloadable ZIP containing structured files (JSON for primary data, CSV for transactions, camt.053 XML for statements, PDF summaries) covering: profile, accounts, transactions (full history), consents granted/revoked (UC-G26 log), fraud-case involvements (counterparts anonymised), audit events about the customer. The export is produced within 30 days (GDPR Art. 12 §3 maximum; portfolio target: same day).

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A new GDPR helper service consuming Kafka topics from A1..A6; identity service for the signed-download-link issuance

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | Compliance officers view the queue / intervene in UC-G41 |
| Client Web | yes | Request + status + download |
| Client iOS | yes | Same; download goes through share sheet |
| Client Android | yes | Same |

## 4. Preconditions

- Customer is authenticated with `gdpr:export`.

## 5. Postconditions

- Export request persisted with `status=PENDING`, then `IN_PROGRESS`, then `READY`, then `DOWNLOADED`/`EXPIRED`.
- Customer has downloaded the ZIP (or the link has expired).
- Audit event `gdpr.export.requested`, and later `.delivered`.

## 6. Main flow (happy path)

1. Customer navigates to **Min personvern** → **Last ned mine data / Download my data**.
2. Informational page explains: what's included, turnaround time, format, security of the link. A single-click **Start nedlasting / Start export** button starts the flow.
3. GUI calls `POST /api/v1/customer/gdpr/export` (idempotent — multiple rapid taps collapse into one request).
4. Backend returns `{requestId, estimatedReady}`.
5. GUI shows the **status page** with:
   - State (PENDING / IN_PROGRESS with % / READY / EXPIRED)
   - Estimated completion
   - "Vi sender e-post når filen er klar" / "We'll email you when it's ready"
6. Customer can navigate away. Status also visible from the Privacy menu card ("1 eksport pågår").
7. When READY, notification service sends an inbox + email message with a deep link.
8. Customer opens link → status page shows **Last ned / Download** button → GUI calls `GET /api/v1/customer/gdpr/export/{requestId}/download` (a one-time signed URL).
9. File downloads; deep link expires; customer opens the ZIP (mobile: save to Files; web: native browser download).
10. ZIP structure:
    ```
    profile.json
    accounts.json
    transactions.csv       (+ camt.053.xml per account)
    consents.json          (includes UC-G26 events)
    fraud-involvements.json(counterpart names anonymised per GDPR Art. 11)
    audit-events.json      (events about this customer only)
    README.md              (index + field dictionary in customer's language)
    ```

## 7. Alternative flows

### AF-01 — Customer requests again while one is IN_PROGRESS

1. Backend returns the existing request; GUI shows the existing status.

### AF-02 — Customer requests export shortly after completing one

1. Allowed but rate-limited to once per 7 days per GDPR-Art.12(5) "manifestly unfounded or excessive" clause. Rate-limited responses show "Du lastet ned en eksport 2 dager siden. Vi tillater én forespørsel per uke."

### AF-03 — Account has very large history (millions of transactions)

1. Backend splits the ZIP into volumes (`transactions.part01.csv.gz`, etc.) — the customer still gets a single ZIP.

### AF-04 — Download link expires (24h TTL)

1. GUI status page shows **Be om ny lenke / Request new link** which reissues the URL without regenerating the export (cheap — just sign a new URL pointing to the same artefact).

### AF-05 — Export fails (some service unavailable mid-generation)

1. Status transitions to `FAILED`; customer is notified with a support reference; the failed export is retried by compliance staff in UC-G41.

### AF-06 — Customer on mobile, limited storage

1. GUI warns before download if the ZIP > 500 MB (typical customer ~5 MB so this is edge).

### AF-07 — GDPR export includes admin PII reveals (audit of operators who viewed this customer)

1. Yes — audit-events.json includes all `pii-revealed` events where this customer was the subject, with the operator's identifier. This fulfils GDPR Art. 15(1)(c) "recipients of the data".

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| POST fails 5xx | Response | Retryable with idempotency key |
| Download 410 Gone | Link expired | AF-04 |
| ZIP integrity check mismatch | Post-download (ETag vs. computed) | Auto-retry once; if persistent, surface to support |
| Service dependency timeout during generation | Backend | Backend logic handles; status becomes `IN_PROGRESS_RETRYING`; customer sees a small note |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F92`, `GUI-PRV-03`, `GUI-PRV-05`
- `system_requirements.md` — GDPR Art. 15, Art. 11 (anonymisation of third parties)
- `PROJECT_DECISIONS.md` — D5

## 10. API operations involved

- `POST /api/v1/customer/gdpr/export` (`customer-gdpr.yaml`)
- `GET  /api/v1/customer/gdpr/export/{requestId}`
- `GET  /api/v1/customer/gdpr/export/{requestId}/download`

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `.../gdpr/__tests__/request.test.ts`, `status-poller.test.ts`, `zip-opener.test.ts` | `.../Tests/GdprExportTests.swift` | `.../gdpr/GdprExportTest.kt` |
| Component | `.../gdpr/__tests__/export-screen.test.tsx` | `.../Tests/GdprExportViewTests.swift` | `.../GdprExportScreenTest.kt` |
| Contract | `.../gdpr-export.pact.test.ts` | `.../GdprExportPactTests.swift` | `.../GdprExportPactTest.kt` |
| E2E | `client-web/e2e/gdpr-export.spec.ts` | `.../UITests/GdprExportUITests.swift` | `.../androidTest/.../GdprExportE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/gdpr_export_happy.json`, `gdpr_export_ratelimited.json`, `gdpr_export_link_expired.json` | (same) | (same) |

## 12. UX notes

- The informational page is plain-language and includes a "What will I see in the ZIP?" expandable section describing each file.
- The one-time-link is highlighted: "Lenken kan bare brukes én gang og utløper etter 24 timer."
- The downloaded ZIP is encrypted at rest but NOT password-protected (simpler UX; the signed URL is the access control). Customers who want additional protection can re-encrypt themselves.
- **Screen-capture protection (Phase 1 FM.1 / GUI-SEC-05a):** the export request screen, status poller, and confirmation all display PII-adjacent information (account last-four, transaction counts); mobile sets `FLAG_SECURE` + iOS obscuring overlay.

## 13. Open questions

- Whether to ZIP-password-protect the file (customer-chosen password at request time). *Defer — adds UX friction; revisit in Phase-1 privacy review.*
