# UC-G41 — GDPR Queue Processing (Admin Side)

*Owner: Frontend / Admin / Compliance*
*Status: DRAFT*
*Implementation status: Admin Web ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

COMPLIANCE_OFFICER operators process customer-initiated GDPR requests (access / erasure / rectification objection) that require operator intervention — typically when automation fails, when the customer requested out-of-band, or when the request conflicts with AML retention. The operator processes the queue, triggers the backend operations, and records the outcome with a completion certificate.

## 2. Actors

- **Primary:** COMPLIANCE_OFFICER
- **Secondary:** GDPR helper service, identity service (SCA), notification service

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes | |

## 4. Preconditions

- Operator has `gdpr:execute` scope.

## 5. Postconditions

- Request status transitions through PENDING → IN_PROGRESS → COMPLETED or REJECTED.
- Audit events `gdpr.<type>.processed`, `gdpr.<type>.rejected`.
- Customer notified (inbox + email with completion certificate or rejection reason).

## 6. Main flow (happy path)

### 6.1 Queue

1. Operator navigates to **Compliance** → **GDPR-kø / GDPR queue**.
2. GUI fetches `GET /api/v1/admin/gdpr/queue?status=PENDING|IN_PROGRESS&sort=dueDate,asc`.
3. Row per request: customer (masked), type (ACCESS / ERASURE / OBJECTION), submitted-at, due-date (30-day countdown), status, automation-status flag.
4. Filters: type, age bucket, assignee.

### 6.2 Detail and triage

1. Operator taps a row → detail page.
2. Shows request body, customer metadata (masked), automation log (what the automated path tried and why it stopped — e.g., "balance non-zero" for ERASURE, "export worker 5xx" for ACCESS).
3. Actions: **Behandle / Process**, **Avvis / Reject**, **Reassign**.

### 6.3 Process an ACCESS request

1. Operator taps **Behandle** on an ACCESS request.
2. Modal: confirms triggering the generation; optionally pre-supplies parameters (date range).
3. Step-up SCA.
4. Backend triggers the same export pipeline as UC-G24; operator monitors progress; on completion, backend sends the customer the secure-download link.
5. Request transitions to COMPLETED.

### 6.4 Process an ERASURE request

1. Operator taps **Behandle** on an ERASURE request.
2. Confirmation flow:
   - Required checks displayed (balance=0 across all accounts, all mandates revoked, all consents revoked, no open fraud/sanctions cases, account CLOSED). If any check fails, a guided remediation panel suggests next steps.
   - Notes ≥ 100 chars (reason for processing; legal basis).
   - Checkbox: "Jeg bekrefter at alle forutsetninger er oppfylt".
3. Step-up SCA with dynamic-linking `{customerId, action:"ERASE"}`.
4. Backend runs the erasure cascade: crypto-shredding per account, profile clearing across all six services, identity-service user removal.
5. Completion certificate generated (signed PDF — or unsigned per UC-G40 KMS-stub rule) and delivered to the customer's provided email-at-erasure.
6. Audit event `gdpr.erasure.completed` with the certificate hash; customer's record remains (with PII crypto-shredded) for AML 5-year retention.

### 6.5 Reject a request

1. Operator taps **Avvis** on any request.
2. Modal: required justification (dropdown of common reasons: `AML_RETENTION_CONFLICT`, `ACTIVE_INVESTIGATION`, `INSUFFICIENT_VERIFICATION`, `OTHER`) + notes.
3. Step-up SCA.
4. Customer notified with the justification; GDPR Art. 12(4) satisfied.

## 7. Alternative flows

### AF-01 — Partial erasure (some data must be retained per AML)

1. The cascade crypto-shreds PII but retains event history; completion certificate explicitly states what was erased vs. retained (per `GDPR Art. 17 §3` legal obligation exemption).

### AF-02 — Request older than 30-day deadline

1. Banner warns; still processable but audited with lateness flag.

### AF-03 — Customer withdraws request before processing

1. Queue row moves to CANCELLED; no action needed by operator.

### AF-04 — Erasure requested while a fraud investigation is active

1. Guardrail: can't proceed; operator must either resolve the investigation or use the REJECT path with `ACTIVE_INVESTIGATION` justification.

### AF-05 — Access request with very large data set

1. Same async export behaviour as UC-G24.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Erasure cascade partial failure | Backend saga reports partial | Red alert; operator coordinates with ops; request stays IN_PROGRESS until resolved |
| Certificate generation 5xx | Adapter | Retry; if persistent, emit request without certificate and flag for follow-up |
| SCA fails | UC-G51 | No change |

## 9. Related requirements

- `gui_requirements.md` — `ADM-F70..F73`
- `system_requirements.md` — GDPR Art. 12, 15, 17, crypto-shredding mechanism
- `PROJECT_DECISIONS.md` — D5

## 10. API operations involved

- `GET  /api/v1/admin/gdpr/queue` (`admin-gdpr.yaml`)
- `GET  /api/v1/admin/gdpr/{requestId}`
- `POST /api/v1/admin/gdpr/{requestId}/process`
- `POST /api/v1/admin/gdpr/{requestId}/reject`

## 11. Tests realising this use case

| Layer | Admin Web |
|-------|-----------|
| Unit | `admin-web/.../gdpr/__tests__/queue-row.test.tsx`, `erasure-checklist.test.tsx`, `rejection-modal.test.tsx` |
| Component | `admin-web/.../gdpr/__tests__/gdpr-detail.test.tsx` |
| Contract | `admin-web/.../admin-gdpr.pact.test.ts` |
| E2E | `admin-web/e2e/gdpr-process-access.spec.ts`, `gdpr-process-erasure.spec.ts`, `gdpr-reject.spec.ts`, `gdpr-partial-erasure.spec.ts` |

## 12. UX notes

- The erasure checklist is visually stepped so operators see exactly which preconditions are met vs outstanding.
- Completion certificate is a clean one-page PDF identifying the customer (hashed), the data categories erased, and the retained categories with legal basis.
- The 30-day countdown colour-shifts green → amber → red as the deadline approaches.

## 13. Open questions

- Whether to offer an "erasure simulator" (dry-run). *Defer — operators have enough signal from the checklist + audit trail to avoid surprises.*
