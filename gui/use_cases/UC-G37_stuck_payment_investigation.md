# UC-G37 — Stuck Payment Investigation (pacs.028)

*Owner: Frontend / Admin / Operations*
*Status: DRAFT*
*Implementation status: Admin Web ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

An OPERATIONS operator investigates payments that have been stuck in a non-terminal state for longer than 2 hours. They can view the payment history, issue a pacs.028 status-query to the correspondent, record external replies, and ultimately escalate to return (UC-G38) or mark the payment as manually resolved.

## 2. Actors

- **Primary:** OPERATIONS
- **Secondary:** A2 (clearing engine — pacs.028 dispatcher), correspondent bank (as external pacs.028 responder)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes | |

## 4. Preconditions

- Operator has `payments:investigate` scope.

## 5. Postconditions

- pacs.028 query sent; response persisted as part of the payment's investigation log.
- Audit events `payment.investigation.queried`, `.resolved` / `.returned`.

## 6. Main flow (happy path)

### 6.1 Queue view

1. Operator navigates to **Operations** → **Fastlåste betalinger / Stuck payments**.
2. GUI fetches `GET /api/v1/admin/payments/investigations?status=OPEN&sort=age,desc`.
3. Row per payment: ID, status, age, last state change, debtor IBAN, creditor IBAN, amount.
4. Filters: age bucket (2-6h / 6-24h / >24h), debtor bank, creditor bank, amount range.

### 6.2 Detail

1. Operator taps a row → detail page.
2. Timeline of state transitions (with Kafka offsets for drill-through) — identical renderer as customer UC-G03 detail but with operator-level metadata (correlation IDs, pacs.002/pacs.028 history).
3. Actions: **pacs.028-forespørsel / pacs.028 query**, **pacs.004-retur / pacs.004 return** (UC-G38), **Marker løst / Mark resolved**.

### 6.3 Issue pacs.028

1. Operator taps **pacs.028-forespørsel**.
2. Modal:
   - Target correspondent (auto-filled from the payment's creditor bank).
   - Reason dropdown (`STATUS_QUERY`, `REQUEST_CANCEL`, `OTHER`).
   - Notes ≥ 20 chars.
3. Step-up SCA.
4. Backend `POST /api/v1/admin/payments/{paymentId}/pacs028` — A2 constructs and dispatches the pacs.028 message; returns `queryId`.
5. UI shows "Sendt — venter på svar" with a pending indicator.
6. Correspondent eventually responds (pacs.002 / pacs.029). A2 receives via Kafka; UI updates via SSE with the response inline.

### 6.4 Mark resolved

1. When external action concluded the matter (e.g., correspondent confirms manual settlement), operator taps **Marker løst**.
2. Modal: notes (required, ≥ 50 chars) explaining how it was resolved.
3. Step-up SCA.
4. Backend `POST /api/v1/admin/payments/{paymentId}/resolve`. Sets payment state to `MANUALLY_RESOLVED`; no money moves; audited.

## 7. Alternative flows

### AF-01 — No response to pacs.028 within 4h

1. UI flags the query as timed-out; operator can re-issue or escalate.

### AF-02 — Multiple stuck payments with the same correspondent

1. Queue filter reveals batch; bulk pacs.028 is supported only up to 20 payments per dispatch (rate limit). Each payment still gets a per-query audit row.

### AF-03 — Payment transitions to terminal state during investigation

1. A2 publishes SETTLED/REJECTED via SSE; UI auto-updates; investigation closes if terminal; operator sees a note "Betalingen har blitt avgjort automatisk".

### AF-04 — Correspondent responds with pacs.028 that directs us to return

1. UI suggests UC-G38 (return) as the next action; operator decides.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| pacs.028 dispatch 5xx | A2 | Retryable |
| Response never arrives | SLA timer | Soft-warn + re-issue option |
| SCA fails | UC-G51 | No state change |

## 9. Related requirements

- `gui_requirements.md` — `ADM-F40`, `ADM-F41`, `ADM-F43`
- `system_requirements.md` — A2-F29 (pacs.028), UC-08

## 10. API operations involved

- `GET  /api/v1/admin/payments/investigations`
- `GET  /api/v1/admin/payments/{paymentId}` (admin view)
- `POST /api/v1/admin/payments/{paymentId}/pacs028`
- `POST /api/v1/admin/payments/{paymentId}/resolve`

## 11. Tests realising this use case

| Layer | Admin Web |
|-------|-----------|
| Unit | `admin-web/.../payments/__tests__/investigation-row.test.tsx`, `pacs028-modal.test.tsx` |
| Component | `admin-web/.../payments/__tests__/payment-detail-admin.test.tsx` |
| Contract | `admin-web/.../admin-payments.pact.test.ts` |
| E2E | `admin-web/e2e/stuck-payment-pacs028.spec.ts`, `stuck-payment-resolve.spec.ts` |

## 12. UX notes

- Each state-timeline entry has a "copy correlation ID" affordance — operators routinely paste these into Jira / support tickets.
- The 4h SLA timer for pacs.028 is a soft indicator; the actual SLA is defined by the scheme's rule book.

## 13. Open questions

- Whether to integrate live correspondent-status dashboards (some banks publish). *Defer — out of MVP.*
