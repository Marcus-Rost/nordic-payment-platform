# UC-G40 — Audit Log Search and Hash-Chain Verification

*Owner: Frontend / Admin / Compliance*
*Status: DRAFT*
*Implementation status: Admin Web ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

COMPLIANCE_OFFICER and SUPERVISOR operators search the audit log, view hash-chain verification checkpoints, trigger on-demand verification, and export audit bundles. The hash chain (existing backend feature, tied to the AML 5-year retention requirement) provides tamper-evidence; operators can verify any subset is intact.

## 2. Actors

- **Primary:** COMPLIANCE_OFFICER, SUPERVISOR, ADMIN
- **Secondary:** `jrpay-audit-query` service (new per gui_requirements §6.5), KMS/HSM for signing exports (stubbed in portfolio)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes | |

## 4. Preconditions

- Operator has `audit:read` scope (search, view) and/or `audit:verify` (trigger verification).

## 5. Postconditions

- Search results displayed; exports produced if requested.
- Verification run results persisted and visible.
- Audit `audit.searched`, `.verified`, `.exported`.

## 6. Main flow (happy path)

### 6.1 Search

1. Operator navigates to **Compliance** → **Revisjonslogg / Audit log**.
2. Filter bar: date range (default: last 24h), event type (multi-select from the full catalogue), actor (operator / customer), affected resource (account ID, transaction ID, case ID), outcome (SUCCESS / FAILURE / REJECTED).
3. Operator submits; GUI calls `GET /api/v1/admin/audit/events?...`.
4. Paginated table: timestamp, event type, actor, resource, outcome, short summary. Sort by any column.
5. Row click → expanded detail with the full event payload (masked PII; reveal is UC-G36-style for high-sensitivity fields — the PII reveal is audited too, creating meta-audit entries that show up in search).

### 6.2 Checkpoint view

1. Tab "Kjedeintegritet / Chain integrity" shows the latest hash-chain checkpoints.
2. Each checkpoint: from-offset, to-offset, hash (displayed truncated, full on hover), verified-by, verified-at.
3. Gaps (if any): a red row noting the missing range; critical alert.

### 6.3 On-demand verify

1. Operator selects a date range and clicks **Verifiser nå / Verify now**.
2. Step-up SCA.
3. Backend re-computes the chain for that range; returns `{verified: true|false, mismatchOffsets: [...]}`.
4. UI displays the result inline; if mismatches, a red banner with support instructions.

### 6.4 Export

1. Operator selects a filter + date range and clicks **Eksporter / Export**.
2. Format picker: CSV (simple spreadsheet import) or signed JSON bundle.
3. For signed JSON, the backend would sign with KMS; portfolio default logs "signing skipped: no KMS" and produces an unsigned bundle with an explicit `unsignedReason` field.
4. UC-G04-style async flow (status + download URL) for larger exports.
5. Audited.

## 7. Alternative flows

### AF-01 — Very large result (> 10k)

1. Prompt to narrow the filter before export; partial-list display capped at 10k.

### AF-02 — Verification finds mismatch

1. Red alert; notification to compliance officers; detailed diff of the first mismatch is shown.

### AF-03 — Operator searches own activity

1. Allowed — no self-censoring. Audit records the self-search too.

### AF-04 — Search for a specific customer's involvement

1. Filter by `affectedResource=customer:<id>`; the system's own reveal-PII events surface too (the customer's right under GDPR to see these).

### AF-05 — Role mismatch (FRAUD_ANALYST has `audit:read`, but the event contains another customer's PII)

1. PII is masked unless the operator has UC-G36 reveal right; the audit entry itself is visible (metadata only).

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 5xx on search | Response | Retryable |
| Verification throws (corrupt chain state) | Backend | Alert; escalates to SUPERVISOR |
| KMS unavailable for signed export | Adapter | Downgrade to unsigned with explicit note |

## 9. Related requirements

- `gui_requirements.md` — `ADM-F60..F62`, `GUI-SEC-14`, `GUI-SEC-15`
- `system_requirements.md` — SEC-07 (audit hash chain), AML 5-year retention
- gui_requirements §6.5

## 10. API operations involved

- `GET  /api/v1/admin/audit/events` (`admin-audit.yaml`)
- `GET  /api/v1/admin/audit/events/{eventId}`
- `GET  /api/v1/admin/audit/checkpoints`
- `POST /api/v1/admin/audit/verify`
- `GET  /api/v1/admin/audit/export` (async; status + signed-URL download)

## 11. Tests realising this use case

| Layer | Admin Web |
|-------|-----------|
| Unit | `admin-web/.../audit/__tests__/filters.test.ts`, `row-detail.test.tsx`, `chain-verify.test.tsx` |
| Component | `admin-web/.../audit/__tests__/audit-log-screen.test.tsx` |
| Contract | `admin-web/.../admin-audit.pact.test.ts` |
| E2E | `admin-web/e2e/audit-search.spec.ts`, `audit-chain-verify.spec.ts`, `audit-export.spec.ts` |

## 12. UX notes

- The hash-chain panel visualises contiguous ranges as green bars and gaps as red — at-a-glance integrity.
- Event-type multi-select supports typeahead (the catalogue has 60+ types).
- Row detail JSON is syntax-highlighted and collapsible for readability.

## 13. Open questions

- Whether to support live-tail (newest events streaming in via SSE). *Defer — Phase-2 nice-to-have.*
- Whether to allow downloading the full audit for a customer (out-of-band GDPR request fulfilment). *Partially covered by UC-G41 (compliance officer GDPR export). Admin-initiated full export is not a separate MVP flow.*
