# UC-G34 — Sanctions Hit Queue and Review

*Owner: Frontend / Admin / Compliance*
*Status: DRAFT*
*Implementation status: Admin Web ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

A compliance officer reviews sanctions hits in a dedicated high-priority queue separate from the fraud queue. Each hit shows the matched entity, Jaro-Winkler score, and prior dealings. The officer decides: `CONFIRMED_SANCTION` (block + freeze + queue FIU report stub), `FALSE_POSITIVE` (release + add to local false-positive cache), or `RELEASE_FOR_FRAUD_REVIEW` (reassign to the fraud queue). All decisions require SCA and ≥ 50-char notes. SLA per IPR Art. 5d operational standard: 15 minutes.

## 2. Actors

- **Primary:** COMPLIANCE_OFFICER
- **Secondary:** A5 (sanctions screening + hit store), A1 (freeze if confirmed), FIU report stub service

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes | |

## 4. Preconditions

- Operator is authenticated with `sanctions:manage`.

## 5. Postconditions

- Hit `status` is one of CONFIRMED_SANCTION / FALSE_POSITIVE / RELEASE_FOR_FRAUD_REVIEW with decidedBy, decidedAt, notes.
- Cascaded effects per decision:
  - CONFIRMED_SANCTION → `account.freeze-commands reason=SANCTIONS_HOLD`, payment REJECTED, FIU report stub queued.
  - FALSE_POSITIVE → false-positive cache row (`{beneficiaryHash, matchedEntityId, expiresAt: now+90d}`), payment resumed if still held, customer notified of success.
  - RELEASE_FOR_FRAUD_REVIEW → new fraud case in UC-G31 queue; hit closed.
- Audit events `sanctions.hit.decided`, `sanctions.fp-cache.added` (if applicable), `fiu.report.queued` (stub).

## 6. Main flow (happy path)

### 6.1 Queue view

1. Operator navigates to **Compliance** → **Sanksjonstreff / Sanctions hits**.
2. GUI fetches `GET /api/v1/admin/sanctions/hits?status=OPEN&sort=age,desc`.
3. List row per hit: red-banner card with: payment ID, debtor, beneficiary name, matched entity (name + list), Jaro-Winkler score (with a subtle progress bar), hit age, SLA countdown (15-min budget).
4. Filter bar: list (EU consolidated / UN / Norwegian / OFAC), matched entity substring, date range, assignee.

### 6.2 Detail

1. Operator taps a row → detail page.
2. Left column:
   - Transaction summary (debtor IBAN, creditor IBAN + name, amount, reference, timestamp).
   - Beneficiary name from transaction.
   - Matched sanctions entry (full record: names, aliases, DOB if individual, country, listing date, regulator, UNSC/EU resolution reference).
3. Right column:
   - Jaro-Winkler score breakdown (which aliases triggered the match).
   - Prior dealings: list of past transactions between this debtor and this beneficiary (or aliases) with their outcomes.
   - List version & last update time (ADM-F23 link).
4. Actions panel: three buttons + notes field.

### 6.3 Decide: CONFIRMED_SANCTION

1. Operator taps **Bekreft sanksjon / Confirm sanction**.
2. Modal:
   - Mandatory notes ≥ 50 chars.
   - Checkbox: "Jeg har verifisert match-kvaliteten og konkluderer med sanksjonstreff" — required.
   - Warning: "Dette fryser kundens konto og genererer en FIU-rapportstump."
3. Operator submits → step-up SCA (UC-G30 AF-06) with dynamic-linking `{hitId, decision:"CONFIRMED_SANCTION"}`.
4. Backend persists; publishes freeze + payment-reject + FIU events.
5. Customer notified via sanctions-block path (UC-G20).

### 6.4 Decide: FALSE_POSITIVE

1. Operator taps **Falskt treff / False positive**.
2. Modal: mandatory notes + checkbox "Jeg har verifisert at dette ikke er en sanksjonert part".
3. Optional: "Add to local false-positive cache so repeated innocent matches in 90 days skip the queue" (checkbox, default on).
4. SCA.
5. Backend adds FP cache entry (if checked), releases the held payment (if still held), closes hit.
6. Customer notified (positive — "Betalingen din er nå gjennomført").

### 6.5 Decide: RELEASE_FOR_FRAUD_REVIEW

1. Operator decides the match is not a true sanctions hit but the case is suspicious in other ways.
2. Modal: mandatory notes describing the suspicion.
3. SCA.
4. Backend creates a new fraud case referencing this hit, closes the sanctions hit, routes the case to UC-G31.

## 7. Alternative flows

### AF-01 — SLA breached

1. 15-min timer is a visual indicator; no hard block. Overdue hits appear on the SLA dashboard (ADM-F82).

### AF-02 — List version stale (A5 couldn't update in time)

1. Banner at top of detail: "Listen er 4 timer gammel — sjekk om beslutningen fortsatt er gyldig."

### AF-03 — Simultaneous decision by two operators

1. 409 conflict; UI refreshes.

### AF-04 — FP cache entry duplicates an existing one

1. Backend makes it idempotent; TTL is reset to 90 days.

### AF-05 — Force update of the list (ADM-F23)

1. Separate action "Oppdater liste / Refresh list" — confirms via SCA, triggers A5's list-refresh job.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Freeze command fails | A1 outage | Decision rolls back via outbox; retry banner |
| FIU stub 5xx | FIU service | Decision persists; stub queued for retry by ops; audit records partial outcome |
| SCA fails | UC-G51 | No state change |

## 9. Related requirements

- `gui_requirements.md` — `ADM-F20..F23`, `GUI-SEC-14`, `GUI-SEC-08`
- `system_requirements.md` — A5-F20..F28 (sanctions screening), UC-05

## 10. API operations involved

- `GET  /api/v1/admin/sanctions/hits` (`admin-sanctions.yaml`)
- `GET  /api/v1/admin/sanctions/hits/{hitId}`
- `POST /api/v1/admin/sanctions/hits/{hitId}/decide`
- `GET  /api/v1/admin/sanctions/list` (list metadata)
- `POST /api/v1/admin/sanctions/list/refresh`

## 11. Tests realising this use case

| Layer | Admin Web |
|-------|-----------|
| Unit | `admin-web/.../sanctions/__tests__/hit-row.test.tsx`, `decision-modals.test.tsx` |
| Component | `admin-web/.../sanctions/__tests__/hit-detail.test.tsx` |
| Contract | `admin-web/.../admin-sanctions.pact.test.ts` |
| E2E | `admin-web/e2e/sanctions-confirm.spec.ts`, `sanctions-false-positive.spec.ts`, `sanctions-release-to-fraud.spec.ts`, `sanctions-list-refresh.spec.ts` |

## 12. UX notes

- Red banner framing for the whole queue area is visually distinctive from the fraud queue (amber) so operators instantly know the surface.
- Jaro-Winkler score bar uses a scale marker with the decision thresholds (e.g., 0.85 is the alert threshold) so operators can calibrate.
- Matched-entity card shows the full UNSC/EU resolution text excerpt when expanded, so the officer can see the legal basis for the listing.

## 13. Open questions

- Whether `RELEASE_FOR_FRAUD_REVIEW` should auto-assign to a specific fraud analyst. *Decision: leave unassigned; the fraud queue's self-assign handles it.*
