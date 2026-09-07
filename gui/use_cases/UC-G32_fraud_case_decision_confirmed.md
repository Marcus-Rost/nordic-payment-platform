# UC-G32 — Fraud Case Decision: CONFIRMED_FRAUD (with Freeze)

*Owner: Frontend / Admin / Fraud*
*Status: DRAFT*
*Implementation status: Admin Web ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

The fraud analyst decides a case as `CONFIRMED_FRAUD`. This rejects the held payment, freezes the affected account, publishes an audited decision, and notifies the customer. All steps happen atomically from the operator's perspective; backend ensures the decision and the freeze command are either both emitted or neither.

## 2. Actors

- **Primary:** FRAUD_ANALYST
- **Secondary:** A5 (case store), A1 (freeze consumer), A2 (payment rejection), notification service

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes | |

## 4. Preconditions

- Operator is authenticated with `fraud:manage` scope.
- Case is in `ASSIGNED_TO_ME` (or ADMIN/SUPERVISOR override) state.
- Operator has reviewed the case detail (UC-G31).

## 5. Postconditions

- Case `status=CONFIRMED_FRAUD`, `decidedBy`, `decidedAt`, `notes` persisted.
- `fraud.decision-response` published with `decision=CONFIRMED_FRAUD` → A2 rejects the held payment.
- `account.freeze-commands` published with `reason=FRAUD_CONFIRMED` → A1 freezes the debtor account.
- Audit events: `fraud.case.decided`, `account.freeze.requested`, `audit.decision-notes` (with notes hash for integrity).
- Customer receives sanction/fraud notification (UC-G20-style copy variant for fraud).
- If multiple payments were held pending this case, all are rejected.

## 6. Main flow (happy path)

1. From UC-G31 case detail, operator taps **Bekreft svindel / Confirm fraud** (red, tertiary position to discourage casual click).
2. Confirmation modal:
   - Title: "Bekreft svindel og frys konto"
   - Notes field (required, ≥ 50 chars) — "Beskriv hvorfor du bekrefter — dette blir en del av revisjonsloggen og kan deles med politiet."
   - Freeze-reason preset (locked to `FRAUD_INVESTIGATION`)
   - Checkbox: "Jeg har vurdert VoP-resultat, svindel-poengbrudd, kontekst, og konklusjonen er svindel" — required.
   - Buttons: Avbryt / Cancel (default), Bekreft / Confirm (destructive style).
3. Operator fills notes + checks box → **Bekreft**.
4. Admin Web POSTs `POST /api/v1/admin/fraud/cases/{caseId}/decide` with `{decision: "CONFIRMED_FRAUD", notes, freezeReason}`.
5. Backend returns 401 `sca-required` (step-up per UC-G30 AF-06).
6. Step-up SCA modal: TOTP; dynamic-linking bound to `{caseId, decision: "CONFIRMED_FRAUD"}`.
7. On SCA success, Admin Web re-POSTs the decide call with the step-up token.
8. Backend (A5):
   - Persists the decision transactionally.
   - Publishes `fraud.decision-response` + `account.freeze-commands` atomically via outbox.
   - Returns 200 with the case final state + related payment IDs.
9. Admin Web updates the case view: status badge → CONFIRMED_FRAUD; actions disabled; audit timeline shows the decision.
10. A toast confirms: "Saken er lukket og kontoen er frosset. Kunden varsles automatisk."
11. Within 1–2s, SSE events arrive for the related payment(s): status → REJECTED. UI auto-refreshes.

## 7. Alternative flows

### AF-01 — Operator misses the checkbox

1. Inline validation; Confirm stays disabled until checked.

### AF-02 — Operator writes < 50 char notes

1. Inline counter turns red; submit disabled.

### AF-03 — Case was already decided by another operator (race)

1. 409 Conflict: "Saken er allerede avgjort av <other> for X minutter siden."
2. Admin Web shows the current state; no action needed.

### AF-04 — Freeze command fails (A1 outage)

1. Backend rolls back the decision (outbox transactional); Admin Web sees 503 and a retry banner.
2. Operator can retry; step-up SCA is still valid for the session (fresh dynamic-linking hash).

### AF-05 — Held payment count was large (bulk effect)

1. Backend handles up to 50 payments per decision; above that, a separate batch job is kicked off and the case has a "Additional payments being rejected..." chip for up to 60s.

### AF-06 — Account is already FROZEN for another reason

1. Backend accepts the decision but the freeze command is a no-op (idempotent); audit shows `freeze-skipped-already-frozen`. UI reflects the existing freeze reason + the new one appended.

### AF-07 — Operator wants to add an attachment (screenshot)

1. *Out of MVP.* Notes field only; attachments are a Phase-2 feature.

### AF-08 — Decision outside the 1-h SLA

1. Permitted; late decisions are flagged in ADM-F82 SLA dashboard.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 5xx on decide POST | Response | Retryable with idempotency key; step-up token preserved within 60s |
| Step-up SCA fails | SCA modal | Return to modal; case unchanged |
| SSE delay on related-payment update | UI cache | Shows "refreshing..." for up to 5s; then polls `GET /api/v1/admin/fraud/cases/{caseId}` to reconcile |

## 9. Related requirements

- `gui_requirements.md` — `ADM-F13`, `ADM-F14`, `ADM-NF03`, `GUI-SEC-14`, `GUI-SEC-08` (dynamic linking for operator actions)
- `system_requirements.md` — A5-F10..F19, A1-F10..F11 (freeze)
- UC-05 (master fraud use case)

## 10. API operations involved

- `POST /api/v1/admin/fraud/cases/{caseId}/decide` (`admin-fraud.yaml`)
- Step-up SCA endpoints via identity service.

## 11. Tests realising this use case

| Layer | Admin Web |
|-------|-----------|
| Unit | `admin-web/.../fraud/__tests__/decide-modal.test.tsx`, `notes-counter.test.ts` |
| Component | `admin-web/.../fraud/__tests__/case-detail.test.tsx` (confirmed-fraud flow) |
| Contract | `admin-web/.../admin-fraud.pact.test.ts` |
| E2E | `admin-web/e2e/fraud-decide-confirmed.spec.ts` — end-to-end: decision → SSE on payment → freeze command → A1 state change |

## 12. UX notes

- Destructive-action colouring (red/orange) on the Confirm button; the Cancel is the default focus.
- After decision, the case stays visible for 10s with a "undo? support only" tooltip — but there is no actual undo path in the UI; undo requires a reverse operation escalated to SUPERVISOR.
- The audit timeline surfaces the step-up SCA event too: operators see that their action is forensically traceable.

## 13. Open questions

- Whether to give the operator a canned copy helper for common fraud patterns ("Known 419-scam language pattern — recommend confirm"). *Defer — LLM assistance in decisions needs explicit governance.*
