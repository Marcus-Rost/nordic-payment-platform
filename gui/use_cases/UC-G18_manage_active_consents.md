# UC-G18 — Manage / Revoke Active Consents

*Owner: Frontend / PSD2 / Identity*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

The customer sees all active consents they've granted — PSD2 (AIS, PIS, CBPII) and VRP consents — with scopes, accounts, expiry countdown, and usage. The customer can revoke any consent immediately. Revocation is instantaneous and cascades: A4 purges token validity, A3 rejects pending VRP drawdowns, and the TPP is notified via the PSD2 consent-status Kafka topic.

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A4 (consent store), identity service (token revocation propagation)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | |
| Client Web | yes | |
| Client iOS | yes | |
| Client Android | yes | |

## 4. Preconditions

- Customer is authenticated with `consents:manage`.

## 5. Postconditions

- On revoke: consent `status=REVOKED` with `revokedAt`, access tokens tied to that consent are invalidated (identity service removes them from the active-tokens table and any future introspection returns `active: false`).
- Audit event `consent.revoked` with scope, TPP identity, reason (optional).

## 6. Main flow (happy path)

### 6.1 List

1. Customer navigates to **Tillatelser / Consents**.
2. GUI fetches `GET /api/v1/customer/consents?status=ACTIVE` (default).
3. Grouped list:
   - **Under tredjeparter / By third parties** — per TPP: logo, name, PSD2 role (AIS/PIS/CBPII), scopes in plain language, accounts covered, granted date, expiry date + countdown, usage count last 30 days.
   - **VRP-tillatelser / VRP consents** — per consent: payee, bounds, cumulative usage, expiry.
4. Filter chips: ACTIVE, EXPIRED, REVOKED.

### 6.2 Detail

1. Tap a consent row → detail page shows full metadata + activity log.
2. Activity log rows: "2 Mar 14:22 — Account balance read" (for AIS), "2 Mar 14:25 — Payment initiated NOK 250" (for PIS/VRP). Aggregated from audit events filtered by this consent's ID.
3. Actions: **Revoker / Revoke** (primary, but warning-toned).

### 6.3 Revoke

1. Customer taps **Revoker**.
2. Confirmation dialog: "Ved å revokere mister leverandøren umiddelbart tilgang. Eventuelle pågående operasjoner avbrytes." / "By revoking, the provider loses access immediately. Any in-progress operations are aborted." Optional reason dropdown (`NO_LONGER_NEEDED`, `SECURITY_CONCERN`, `WRONG_PROVIDER`, `OTHER`).
3. Customer confirms.
4. GUI calls `DELETE /api/v1/customer/consents/{consentId}` with `{reason}` in the body (for audit).
5. No SCA required — revocation is customer-positive and regulation explicitly allows the customer to revoke at any time without friction (PSD2 Art. 67 §4 / Art. 57).
6. Consent moves to REVOKED; list refreshes; notification sent to customer's inbox.

## 7. Alternative flows

### AF-01 — Consent already expired

1. Listed under EXPIRED filter; Revoke button greyed out (already inactive); "Utløpt / Expired" badge.

### AF-02 — Attempt to revoke while an in-flight operation (PIS payment mid-SCA)

1. A4 checks; if a payment is in `PENDING_SCA` for this consent, returns 409 with a warning. Dialog re-emerges: "En betaling er under SCA. Revokering vil avbryte den. Fortsett?" — customer confirms and revoke proceeds; A4 aborts the payment.

### AF-03 — Revoke all consents for a TPP (bulk)

1. At the TPP group-header level, a **Revoker alle / Revoke all** button issues the revoke on each child consent in sequence. Confirmation dialog shows the count of consents affected.

### AF-04 — Consent renewal prompt (7 days before expiry)

1. Dashboard badge and inbox notification (CLI-F73) alert the customer; tap → detail page shows a **Forny / Renew** button that opens a pre-populated consent flow with the TPP (via OAuth redirect initiated by the bank for AIS only; PIS consents are single-use and do not renew).

### AF-05 — Customer views REVOKED consents (history)

1. Filter to REVOKED; rows are read-only and show `revokedAt`, reason, granted-at; no action buttons.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 5xx on DELETE | Response | Retryable with idempotency key |
| 409 in-flight operation | A4 | Re-prompt dialog (AF-02) |
| Race: TPP is currently making a request mid-revoke | Internal | Backend handles idempotently; customer sees clean state |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F70`, `CLI-F71`, `CLI-F73`, `CLI-F44` (VRP overlap)
- `system_requirements.md` — A4-F10..F17 (PSD2), A4-F40..F47 (VRP)

## 10. API operations involved

- `GET    /api/v1/customer/consents` (`customer-consents.yaml`)
- `GET    /api/v1/customer/consents/{consentId}`
- `DELETE /api/v1/customer/consents/{consentId}`

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `.../consents/__tests__/list.test.tsx`, `revoke-dialog.test.tsx` | `.../Tests/ConsentListTests.swift` | `.../consents/ConsentListTest.kt` |
| Component | `.../consents/__tests__/consent-detail.test.tsx` | `.../Tests/ConsentDetailViewTests.swift` | `.../ConsentDetailScreenTest.kt` |
| Contract | `.../consents.pact.test.ts` | `.../ConsentsPactTests.swift` | `.../ConsentsPactTest.kt` |
| E2E | `client-web/e2e/consents-revoke.spec.ts` | `.../UITests/ConsentsRevokeUITests.swift` | `.../androidTest/.../ConsentsRevokeE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/consent_revoke_ais.json`, `consent_revoke_vrp.json`, `consent_renew_ais.json` | (same) | (same) |

## 12. UX notes

- TPP logo in the list uses a fallback (TPP initials) if eIDAS metadata doesn't provide one.
- Countdown to expiry uses a subtle traffic-light: green > 30 d, amber < 30 d, red < 7 d.
- Activity log has a reveal ("Se alle hendelser / See all events") — first render shows last 10 events only.

## 13. Open questions

- Whether to support pausing a consent (temporarily disable without revoke). *Defer — revoke + re-grant is the spec-clean approach.*
