# UC-G36 — PII Reveal with Audit

*Owner: Frontend / Admin / Compliance*
*Status: DRAFT*
*Implementation status: Admin Web ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

A back-office operator explicitly reveals customer PII (full holder name, full address, fødselsnummer) that is normally masked. Each reveal is an audited, SCA-gated action. The revealed values display for 60 seconds then auto-re-mask to minimise exposure. The customer sees the reveal in their audit log (UC-G24 export) and in UC-G26 consent/activity log.

## 2. Actors

- **Primary:** COMPLIANCE_OFFICER, SUPERVISOR, ADMIN (and FRAUD_ANALYST in read-only capacity with `accounts:admin` read sub-scope — UC-G35 tabulates scopes)
- **Secondary:** A1 (account detail), identity service (SCA), audit service

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes | |

## 4. Preconditions

- Operator is authenticated.
- Operator has `accounts:admin` scope (read suffices for PII reveal).

## 5. Postconditions

- PII displayed briefly.
- Audit `audit.pii-revealed` recorded with `{operatorId, customerId, accountId, field, revealedAt, justificationNotes, revealDurationSec}`.
- Customer's activity log (UC-G26) shows the reveal event.

## 6. Main flow (happy path)

1. From the account detail page (UC-G35 §6.2), masked PII fields show a small **Avslør / Reveal** button per field (individual fields are individually auditable — revealing the name is a different event from revealing the fødselsnummer).
2. Operator taps **Avslør** on the fødselsnummer.
3. Modal:
   - Title: "Avslør fødselsnummer"
   - Justification notes (required, ≥ 20 chars) — "Hvorfor trenger du å se dette nå?"
   - Retention notice: "Handlingen logges. Kunden vil se dette i sin aktivitetslogg."
   - Checkbox: "Jeg bekrefter at jeg har forretningsmessig behov for å se dette" — required.
   - Buttons: Cancel, Avslør.
4. Step-up SCA with dynamic-linking `{accountId, field:"fodselsnummer", action:"reveal"}`.
5. On SCA success, backend `POST /api/v1/admin/accounts/{accountId}/reveal-pii` returns the unmasked value with a 60-second TTL claim. For COMPLIANCE_OFFICER or SUPERVISOR the full PII is returned; for FRAUD_ANALYST, fødselsnummer is always redacted server-side (Phase 1 FS.10 / UC-G36 §13 fix — fødselsnr requires `accounts:admin:write` which FRAUD_ANALYST does not hold).
6. Admin Web displays the value inline; the canonical countdown component (`gui/architecture/COMPONENTS_COUNTDOWN.md`) shows remaining time with thresholds at 30 s, 15 s, 5 s, 0 s. An **Extend (+60s)** button appears; clicking extends the TTL once (server-side `POST /api/v1/admin/accounts/{accountId}/reveal-pii/extend` with the current step-up token; max 3 extensions — 4×60 s = 4 min total). Each extension is a separate audit event `audit.pii-revealed.extended` so the audit trail retains forensic precision (WCAG 2.2.1 clause (c) satisfied per Phase 1 FA.1).
7. At TTL expiry (or on page unload, or on re-mask click), the field re-masks. Re-revealing is a fresh UC-G36 flow (fresh modal + SCA + audit). **Sliding-window rate limit** (Phase 1 FS.10): 5 reveals per hour per operator per account; above that threshold, the server responds `202 Accepted` with `requiresSecondApproval: true` and the reveal is pending a SUPERVISOR concurrence via `/api/v1/admin/accounts/{accountId}/reveal-pii/approve`. A daily digest to SUPERVISORs lists per-operator reveal counts.

## 7. Alternative flows

### AF-01 — Operator reveals multiple fields in quick succession

1. Each reveal is a separate modal + SCA + audit entry. This is by design — we do not batch PII reveals into a single "reveal all" button.

### AF-02 — Operator leaves the justification empty

1. Inline validation; submit disabled.

### AF-03 — SCA fails

1. UC-G51 path; no reveal.

### AF-04 — Screen recorded (via OS-level screen recording)

1. Admin Web applies a DOM-level "redact-on-capture" class where possible (limited efficacy on web); compensating control is the audit — the operator is accountable for the reveal event regardless.

### AF-05 — Customer whose PII was revealed asks "who saw my data?"

1. UC-G24 export includes the full list of reveal events with operator identity and justification. Customer-side UC-G26 also surfaces these.

### AF-06 — Bulk PII need (e.g., printing statements for 20 customers)

1. Use the backend statement-generation (UC-G04) which is audited at the customer level; no operator-side bulk PII reveal exists.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 5xx on reveal POST | Response | Retryable; audit entry for the attempt |
| 403 (scope missing) | Response | Shouldn't happen given button gating; if it does, surfaces security alert |
| Timeout on SCA | UC-G51 | No reveal; audit entry `reveal.sca-timeout` |

## 9. Related requirements

- `gui_requirements.md` — `ADM-F32`, `GUI-SEC-15`, `GUI-SEC-14`, `GUI-PRV-*`
- `system_requirements.md` — GDPR Art. 32, SEC-07 audit

## 10. API operations involved

- `POST /api/v1/admin/accounts/{accountId}/reveal-pii` (`admin-accounts.yaml`)
  - Request: `{field: "holderName" | "address" | "fodselsnummer", justificationNotes, step-up-token}`
  - Response: `{value: "<unmasked>", ttlSeconds: 60, extensionsRemaining: 3}` or `{requiresSecondApproval: true, approvalRef: "<id>"}` when rate limit hit
- `POST /api/v1/admin/accounts/{accountId}/reveal-pii/extend` (`admin-accounts.yaml`)
  - Request: `{field, step-up-token}`
  - Response: `{ttlSeconds: 60, extensionsRemaining: <0-2>}`
- `POST /api/v1/admin/accounts/{accountId}/reveal-pii/approve` (`admin-accounts.yaml`) — SUPERVISOR-only
  - Request: `{approvalRef, step-up-token}`
  - Response: `{value, ttlSeconds: 60}`

## 11. Tests realising this use case

| Layer | Admin Web |
|-------|-----------|
| Unit | `admin-web/.../pii/__tests__/reveal-modal.test.tsx`, `countdown.test.ts`, `auto-remask.test.tsx` |
| Component | `admin-web/.../accounts/__tests__/account-detail.test.tsx` (PII reveal flow) |
| Contract | `admin-web/.../admin-reveal-pii.pact.test.ts` |
| E2E | `admin-web/e2e/admin-pii-reveal.spec.ts`, `admin-web/e2e/admin-pii-reveal-customer-visibility.spec.ts` (verifies customer sees the event in UC-G26) |

## 12. UX notes

- Revealed values render in a subtly-different visual treatment (monospace, light-yellow background) to remind the operator this is sensitive data temporarily visible.
- Countdown shows seconds remaining; at 10s the value pulses gently (colour only, respects prefers-reduced-motion).
- The "Avslør" button has an info tooltip explaining the audit consequence on first hover per session.

## 13. Open questions

- Whether the fødselsnummer reveal should require COMPLIANCE_OFFICER or higher (not FRAUD_ANALYST). *Decision: yes — FRAUD_ANALYST can see masked + name; full fødselsnr reveal is compliance-officer plus.*
- Whether to shorten TTL to 30s. *Defer to Phase-1 security review.*
