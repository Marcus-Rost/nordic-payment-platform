# UC-G31 — Fraud Case Queue and Triage

*Owner: Frontend / Admin*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS n/a | Android n/a*
*Last updated: 2026-04-23*

---

## 1. Purpose

A fraud analyst signs in to the Admin Web Console and works through the queue of open fraud cases produced by A5's scoring pipeline. The analyst filters, sorts, drills into a case, gathers context (transaction, account history, VoP outcome, score breakdown), and decides the case as `CONFIRMED_FRAUD` or `DISMISSED` (UC-G32, UC-G33). This use case covers the queue and triage; the decision itself is detailed in the linked use cases.

## 2. Actors

- **Primary:** Fraud analyst (role `FRAUD_ANALYST`)
- **Secondary:** A5 case-management endpoints, A1 (account context), audit-query service, identity service (assignment)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes | |
| Client Web | no | |
| Client iOS | no | |
| Client Android | no | |

## 4. Preconditions

- Analyst has an `acr=loa3` session (logged in via UC-G30 with SCA in last 8 h).
- Analyst holds the `fraud:read` and `fraud:manage` scopes (FRAUD_ANALYST role per `D2`).
- A5 has materialised at least one open fraud case (the projector consumes `payment.fraud-check-response` events into `fraud_cases` table — see `gui/architecture/ARCHITECTURE_DESIGN.md` §7.4).

## 5. Postconditions

- The analyst has a current view of the open queue.
- Any case the analyst opened produced an `audit.fraud.case.viewed` event with the analyst id and case id.
- Any case the analyst self-assigned produced an `audit.fraud.case.assigned` event.
- No PII was revealed without an explicit `audit.pii-revealed` event (the case detail shows masked PII by default; reveal is UC-G36).

## 6. Main flow

1. Analyst lands on the home dashboard (post-login from UC-G30).
2. Dashboard shows the **My queue** widget (cases assigned to me, sorted by SLA expiry) and the **All open** widget (count by status). Clicking either navigates to the full queue view.
3. Queue view (`ADM-F10`):
   - Filter chips: *Score ≥ 0.7*, *Score 0.5–0.7*, *Triggered rule = …*, *Account = …*, *Date range*, *Status*, *Assignee*. Filters compose with AND.
   - Sort options: highest score first (default), oldest first, SLA-soonest first.
   - Auto-refresh every 30 s (visible "Refreshed Xs ago"; pause toggle).
   - Virtualised list rendering (handles 1000+ rows per `ADM-NF02`).
   - Per row: case id (truncated), account holder *masked*, amount, currency, fraud score (gradient badge), top 3 triggered rules, age, status, assignee.
   - Quick actions per row: **Assign to me**, **Open**.
   - Bulk select (checkbox column) with bulk actions (`ADM-F15`): **Bulk assign**, **Bulk dismiss** (max 50 per batch; SCA per bulk action).
4. Analyst clicks **Assign to me** on a row.
   - GUI calls `POST /api/v1/admin/fraud/cases/{caseId}/assign` with `{assignee: <self>}`.
   - A5 updates `fraud_cases.assignee` and emits `audit:fraud.case.assigned`.
   - GUI updates the row optimistically (`ARCHITECTURE_DECISIONS` G.10b: assignment is low-stakes → optimistic).
   - Row turns "yours" (subtle background tint, "Assigned to you" badge).
5. Analyst clicks **Open** on the row to drill in.
6. Case detail view (`ADM-F11`):
   - **Header strip:** Case id, status, age, SLA countdown (with red badge if breached), assignee.
   - **Transaction panel:** debtor IBAN (masked), creditor IBAN, amount, currency, timestamp, reference (full), payment method (INSTANT/STANDARD).
   - **Score breakdown panel:**
     - Sanctions: result (`CLEAR | HIT | UNAVAILABLE`); if HIT, matched entry.
     - VoP risk: VoP result; if `CLOSE_MATCH` or `NO_MATCH`, the actual name (subject to disclosure rules; analyst has authorised access — but PII reveal still triggers the audit per `GUI-SEC-15`).
     - Velocity: list of triggered rules with rule ids and contributing values.
     - Anomaly: ML-model score (if enabled), model version.
     - Composite score and the threshold that triggered the case.
   - **Account context panel:** 30-day snapshot for the debtor account: total volume, unique counterparties, count of new beneficiaries, average transaction size, deviation indicators. Built from a composite endpoint `GET /api/v1/admin/fraud/cases/{caseId}/context`.
   - **Audit timeline panel:** chronological entries for the case (created → assigned → viewed → decisions). Sourced from `jrpay-audit-query`.
   - **Decision panel:** two large buttons **Confirm fraud** (red) and **Dismiss** (green). Both disabled until the analyst has typed ≥ 50 chars in the **Decision notes** field. The notes field has a character counter and a soft warning at 250+ chars (encouraging concise but specific notes).
   - **Reveal PII** button at the top of the **Account context panel** (UC-G36) — masked by default per `ADM-F32` and `GUI-SEC-15`.
7. Analyst gathers context, types notes, then chooses **Confirm fraud** (UC-G32) or **Dismiss** (UC-G33). Each path ends with SCA confirmation and an audit entry.
8. After decision, GUI returns to the queue with a success toast and the row removed (or moved to "Resolved" tab).

## 7. Alternative flows

### AF-01 — Bulk dismiss

1. Analyst selects rows (max 50). The **Bulk dismiss** button enables.
2. Analyst clicks **Bulk dismiss**. GUI shows a dialog with a **Shared dismissal note** field (≥ 50 chars), a list of selected case ids, and an SCA challenge.
3. On confirm, GUI calls `POST /api/v1/admin/fraud/cases/bulk-decide` with the case ids, `decision=DISMISSED`, and the shared note.
4. A5 processes server-side; rows removed from the queue. Audit emits one event per case.
5. Bulk action rate limit: at most 1 bulk action per 60 s per analyst (server-enforced; GUI shows cooldown).

### AF-02 — Bulk assign

Same as AF-01 but the action is `assign` and SCA is not required (assignment is low-risk).

### AF-03 — A case becomes assigned to someone else while you're viewing it

1. The case detail view subscribes to `GET /api/v1/admin/fraud/cases/stream` (SSE per ARCHITECTURE_DECISIONS G.5a).
2. When another analyst assigns the case, the GUI receives an event and shows a non-blocking warning banner: "This case was assigned to <name> while you were viewing it."
3. The decision buttons disable; analyst may **Take over** (which calls assign-to-me; reason field required).

### AF-04 — A case becomes RESOLVED while you're viewing it

1. Same SSE; GUI receives a status change event.
2. Analyst is notified: "This case has just been resolved as <decision> by <name>."
3. Decision buttons disable; analyst may go back to queue.

### AF-05 — SLA breach

1. The SLA countdown reaches 0 (1 h default).
2. The case row gains a red badge and a "SLA breached" tag.
3. The system also publishes a metric `fraud.alert.sla_breach_count` and triggers an alert (handled outside the GUI).
4. Analyst behaviour does not change; the badge is visual only.

### AF-06 — A held payment is associated with the case

1. The score breakdown shows "Payment HELD pending decision".
2. On `Dismiss`, the held payment is released (server publishes a follow-on event to A2).
3. On `Confirm fraud`, the held payment stays REJECTED and the account is frozen (UC-G32).

### AF-07 — Filter combination yields zero results

1. Empty-state UI: illustration + "No cases match these filters" + **Clear filters** button.

### AF-08 — A5 unavailable

1. The list endpoint returns 503; queue shows a retryable error with **Try again**; SLA badges still rendered for any cached rows.
2. The system status banner (UC-G54) is displayed if A5 is in declared incident.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Stale data after auto-refresh fails | `lastRefreshedAt` > 60 s | Banner "Data is stale. Refreshing…"; manual retry button |
| Decision notes field rejected as too short by server | 422 `notes-too-short` | Inline error under field; preserve text |
| Decision endpoint returns 409 (case already resolved) | After concurrent action | AF-04 |
| SSE disconnect | EventSource error | Reconnect with backoff; reconcile via list refresh on reconnect |

## 9. Related requirements

- `gui_requirements.md` — `ADM-F10..F15`, `ADM-NF02..NF04`, `GUI-SEC-14..15`
- `system_requirements.md` — A5-F* (case lifecycle), CASE-* (existing requirements)
- `gui/architecture/ARCHITECTURE_DESIGN.md` — §7.4 (A5 case-management addition)

## 10. API operations involved

- `GET  /api/v1/admin/fraud/cases` (`admin-fraud.yaml`) — list with filter/sort
- `GET  /api/v1/admin/fraud/cases/{caseId}` (`admin-fraud.yaml`) — detail
- `GET  /api/v1/admin/fraud/cases/{caseId}/context` (`admin-fraud.yaml`) — composite account context
- `POST /api/v1/admin/fraud/cases/{caseId}/assign` (`admin-fraud.yaml`)
- `POST /api/v1/admin/fraud/cases/{caseId}/decide` (`admin-fraud.yaml`) — UC-G32/UC-G33
- `POST /api/v1/admin/fraud/cases/bulk-decide` (`admin-fraud.yaml`) — bulk
- `GET  /api/v1/admin/fraud/cases/stream` (SSE) (`admin-fraud.yaml`) — live updates

## 11. Tests realising this use case

| Layer | Web |
|-------|-----|
| Unit | `admin-web/src/features/fraud/__tests__/queue-filtering.test.ts`, `score-breakdown.test.tsx` |
| Component | `admin-web/src/features/fraud/__tests__/queue-view.test.tsx`, `case-detail.test.tsx` |
| Contract (Pact) | `admin-web/.../fraud.pact.test.ts` |
| E2E | `admin-web/e2e/fraud-triage.spec.ts` |

## 12. UX notes

- The score-gradient badge uses a perceptual gradient (HCL-uniform) so the visual difference between 0.5 and 0.7 matches the analyst's mental model.
- The decision notes field shows examples of "good" and "weak" notes in a tooltip (e.g., "Good: 'Velocity rule VEL-04 triggered due to 7 transactions in 1 hour from a known business merchant; pattern is consistent with payroll, no suspicious indicators.' Weak: 'Looks fine.'").
- The decision buttons are visually different (Confirm fraud is red; Dismiss is green) but never colour-only — both have icons (alert / check) and explicit labels.
- The SLA countdown uses `aria-live="polite"` so screen readers announce when crossing major thresholds (15 min, 5 min, breached).

## 13. Open questions

- Whether to allow analyst to put a case "On hold" pending external confirmation (e.g., callback to customer). *Defer to Phase-1.*
- Whether the score breakdown should expand a sub-panel showing the actual rule contribution math, not just the IDs. Useful for senior analysts; noisy for juniors. *Defer; consider a per-user expand toggle.*
