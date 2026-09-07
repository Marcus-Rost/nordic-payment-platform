# UC-G39 — Reconciliation Status and Run

*Owner: Frontend / Admin / Operations*
*Status: DRAFT*
*Implementation status: Admin Web ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

OPERATIONS operators see the daily reconciliation dashboard, drill into discrepancies, upload an external settlement report (camt.053 XML or CSV) to trigger on-demand reconciliation, and mark discrepancies as resolved.

## 2. Actors

- **Primary:** OPERATIONS, SUPERVISOR
- **Secondary:** A1 (reconciliation service), an external clearing system (source of camt.053)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes | |

## 4. Preconditions

- Operator has `reconciliation:read` (view) and `reconciliation:run` (trigger) scopes.

## 5. Postconditions

- A reconciliation run completes; discrepancy rows persisted.
- Audit `reconciliation.run.triggered`, `reconciliation.discrepancy.resolved`.

## 6. Main flow (happy path)

### 6.1 Dashboard

1. Operator navigates to **Operations** → **Avstemming / Reconciliation**.
2. GUI fetches `GET /api/v1/admin/reconciliation/summary?date=today`.
3. Dashboard:
   - Per day (today, yesterday, previous 7 days): count + total amount RECONCILED / UNRECONCILED / DISCREPANCY.
   - Bar chart visualising volume trend (last 30 days).
   - Date picker to pick any day.
4. Tile "Kjør avstemming / Run reconciliation" opens the run flow.

### 6.2 Drill into discrepancies

1. Click a day's discrepancy count → list view.
2. Row per discrepancy: type (MISSING_INTERNAL / MISSING_EXTERNAL / AMOUNT_MISMATCH), external reference, our internal payment ID (if any), expected vs actual amount.
3. Row detail shows the full external record + our matching attempt.
4. Action: **Marker undersøkt / Mark investigated** (with notes ≥ 50 chars, SCA).

### 6.3 Run reconciliation on demand

1. Click "Kjør avstemming". Modal:
   - Source: Upload camt.053 XML / Upload CSV / Select scheduled report (dropdown of recent external files).
   - Clearing date (must match records).
   - Notes (optional).
2. Step-up SCA.
3. Upload + submit → `POST /api/v1/admin/reconciliation/run` (multipart if upload).
4. Backend processes within A1-F17 SLA target (≤ 60s for ≤ 50k records); UI shows progress via SSE (percent-complete).
5. On completion, run summary appears at top of dashboard with new discrepancies highlighted.

## 7. Alternative flows

### AF-01 — Upload rejected (malformed camt.053)

1. UI shows parse errors with line numbers; operator fixes the file and re-uploads.

### AF-02 — Reconciliation finds new amount-mismatch

1. Highlighted in red on the discrepancy list; notification to a Slack-style ops channel (via notification adapter stub).

### AF-03 — Very large file (> 100k records)

1. Processing takes longer than the SLA; UI shows an extended progress bar and lets the operator navigate away (SSE continues in the background; inbox notification when done).

### AF-04 — Operator runs the same file twice

1. Backend idempotently de-duplicates by file hash; returns the existing run's summary. UI explains.

### AF-05 — Run triggered automatically (cron)

1. Not a UC-G39 flow; the dashboard just shows the results.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Backend run 5xx | Response | Retryable |
| SSE drops | EventSource error | Poll the run status endpoint (UC-G04-style) |

## 9. Related requirements

- `gui_requirements.md` — `ADM-F50..F53`
- `system_requirements.md` — A1-F16, A1-F17, A1-F13 (camt.053 ingest)

## 10. API operations involved

- `GET  /api/v1/admin/reconciliation/summary`
- `GET  /api/v1/admin/reconciliation/discrepancies`
- `GET  /api/v1/admin/reconciliation/discrepancies/{id}`
- `POST /api/v1/admin/reconciliation/discrepancies/{id}/resolve`
- `POST /api/v1/admin/reconciliation/run` (multipart)
- `GET  /api/v1/admin/reconciliation/runs/{runId}/stream` (SSE)

## 11. Tests realising this use case

| Layer | Admin Web |
|-------|-----------|
| Unit | `admin-web/.../reconciliation/__tests__/summary.test.tsx`, `upload.test.ts` |
| Component | `admin-web/.../reconciliation/__tests__/dashboard.test.tsx` |
| Contract | `admin-web/.../admin-reconciliation.pact.test.ts` |
| E2E | `admin-web/e2e/reconciliation-view.spec.ts`, `reconciliation-run-upload.spec.ts`, `reconciliation-resolve-discrepancy.spec.ts` |

## 12. UX notes

- Discrepancy amounts use a distinctive monospace font for ease of visual scanning.
- Drill-down keeps the date filter pinned so operators don't lose context.
- Uploads show file size + estimated processing time before submit.

## 13. Open questions

- Whether to allow "preview" mode (run without persisting). *Defer.*
