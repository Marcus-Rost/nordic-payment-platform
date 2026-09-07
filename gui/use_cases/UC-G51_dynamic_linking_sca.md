# UC-G51 — Dynamic-Linking SCA Flow

*Owner: Frontend / Identity / Payments*
*Status: DRAFT*
*Implementation status: Admin Web ☐ | Client Web ☐ | Client iOS ☐ | Client Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

Canonical specification of the Strong Customer Authentication flow with PSD2 RTS Art. 5 dynamic linking. The GUI displays the exact amount + payee (or the exact action parameters for non-payment SCA) that the customer is authorising. The SCA proof is cryptographically bound to those values; A4 or the identity service verifies the binding before allowing the operation. This is called by UC-G05, UC-G07, UC-G08, UC-G10, UC-G17, UC-G30 (step-up), UC-G32, UC-G33, UC-G34, UC-G35, UC-G36, UC-G37, UC-G38, UC-G41, UC-G43.

## 2. Actors

- **Primary:** User (customer or operator)
- **Secondary:** Identity service (orchestrator), `ScaProvider` (simulated BankID by default; TOTP fallback; real BankID via RealBankIdScaProvider adapter)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes | |
| Client Web | yes | |
| Client iOS | yes | |
| Client Android | yes | |

## 4. Preconditions

- User is authenticated at `acr=loa1` (valid session).
- User has enrolled SCA factors (set up during UC-G01 or UC-G30 first-login).

## 5. Postconditions

- On success: a `dl_hash` claim is bound to the user's access token for the transaction-scoped action; the action may proceed.
- Audit `auth.sca.dynamic-linking.bound` with `dl_hash`, the action id (paymentId, consentId, caseId, etc.), ACR achieved.

## 6. Main flow (happy path)

1. The calling UC determines the dynamic-linking inputs. Examples:
   - Payment: `{amount, currency, creditorIban, creditorName, paymentId}`
   - Consent grant: `{consentId, tppId, scopes, accountIds}`
   - Admin case decision: `{caseId, decision, notes-hash}`
   - PII reveal: `{accountId, field, action:"reveal"}`
2. Client posts `POST /sca/start` with those inputs. Identity service:
   1. Computes `dl_hash = SHA256(canonical-JSON-of-inputs)`. (Canonical form is locked in the API contract.)
   2. Creates an SCA session (10-min TTL) bound to `{user, dl_hash, factor-hint}`.
   3. Invokes the configured ScaProvider; returns `{scaSessionId, dl_hash, challenge: {type: "TOTP" | "BANKID_SIMULATED" | "BANKID_REAL", metadata}}`.
3. Client verifies its own locally-computed `SHA256(inputs)` matches the returned `dl_hash`. Mismatch is severe (AF-01).
4. Client renders the **SCA screen**:
   - A visually distinct, unobscurable region displaying the dynamic-linking inputs in plain language:
     - Payments: "Beløp: NOK 1.234,56" and "Mottaker: Ola Nordmann"
     - Consent: "Gi <TPP> tilgang til saldo og transaksjoner for 3 kontoer i 180 dager"
     - Admin reveal: "Avsløre fødselsnummer for konto NO93..."
   - The SCA challenge interaction per factor type.
   - A countdown (5 min default).
5. User completes the challenge.
6. Client posts `POST /sca/{scaSessionId}/verify` with the proof (TOTP code / BankID OTP / biometric assertion).
7. Identity service verifies with the ScaProvider:
   - TOTP: code valid within drift window.
   - Simulated BankID: stored-approval matches.
   - Real BankID: JWT assertion from BankID signature service.
8. On success:
   - Identity service updates the access token's claim set: adds `dl_hash=<>` and `acr=loa3` bound to `{sub, dl_hash}` — a short-lived (5-min) projection that resource servers (A4, A5, A1) verify against the incoming operation's hash.
   - Returns `{status: "VERIFIED", stepUpToken}` (a short-lived token the client carries into the action API call).
9. Client calls the action endpoint with `Step-Up-Token: <stepUpToken>` header.
10. Resource server verifies `stepUpToken` and its bound `dl_hash` matches the operation's inputs. If mismatch → 403 `dynamic-linking-mismatch`.

## 7. Alternative flows

### AF-01 — Local hash mismatch with server `dl_hash` (step 3)

1. Severe security event — either the server is wrong or the client inputs were altered between form and start. Client aborts; clears draft state; logs to observability.

### AF-02 — SCA wrong code

1. 3 failures in the same session → 5-min lockout per IDS-F07.
2. 5 lockouts in 24h → admin-required unlock (for operators) or customer-self-unlock after 24h (for customers; simple retry allowed after cooldown).

### AF-03 — SCA timeout (5 min)

1. Identity service marks session EXPIRED; client shows `auth.sca.timeout` with Retry.

### AF-04 — User cancels (dismisses BankID app, closes TOTP modal)

1. Client aborts the flow; no action occurs.

### AF-05 — SCA exemption (low-value, trusted beneficiary, TRA, recurring)

1. Before requesting SCA, the caller consults the server for exemption eligibility (PSD2 RTS Art. 10–18).
2. If exempt, `POST /sca/start` returns `{exempt: true, reasonCode, stepUpToken}` — no UI SCA; token still includes `dl_hash` (non-interactive binding).
3. UI shows a subtle "SCA-unntak: lav verdi" banner for transparency; customer may demand SCA via "Require SCA anyway" (CLI-F25).

### AF-06 — BankID provider unreachable

1. Retry in `ScaProvider` (3 attempts); then fall back to TotpScaProvider if enabled for the user.
2. UI shows the appropriate factor's UI.

### AF-07 — User's primary factor not enrolled (e.g., phone lost)

1. Enrolment flow redirects to a secondary factor; high-value scopes may require admin-side re-enrolment (out of MVP UX).

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Start 5xx | Response | Retryable with idempotency key (same inputs produce same dl_hash so safe) |
| Verify 5xx | Response | Retryable within the 5-min session window |
| Mismatched `dl_hash` at resource server | 403 | UI shows severe-error copy; does NOT retry silently |

## 9. Related requirements

- `gui_requirements.md` — `GUI-SEC-08`, `GUI-SEC-13`, `GUI-A11Y-06`
- `system_requirements.md` — IDS-F04, IDS-F05, PSD2 RTS Art. 5, Art. 10–18 (exemptions)
- `PROJECT_DECISIONS.md` — D6

## 10. API operations involved

- `POST /sca/start` (`identity.yaml`)
- `POST /sca/{scaSessionId}/verify`
- `POST /sca/enroll` (UC-G01 / UC-G30 first-login)

## 11. Tests realising this use case

| Layer | Admin Web | Client Web | iOS | Android |
|-------|-----------|-----------|-----|---------|
| Unit | `admin-web/.../sca/__tests__/dl-hash.test.ts` | `client-web/.../sca/__tests__/dl-hash.test.ts` | `.../Tests/DLHashTests.swift` | `.../sca/DLHashTest.kt` |
| Component | `admin-web/.../sca/__tests__/sca-modal.test.tsx` | `client-web/.../sca/__tests__/sca-screen.test.tsx` | `.../Tests/ScaScreenViewTests.swift` | `.../ScaScreenTest.kt` |
| Contract | `admin-web/.../sca.pact.test.ts` | `client-web/.../sca.pact.test.ts` | `.../ScaPactTests.swift` | `.../ScaPactTest.kt` |
| E2E | `admin-web/e2e/sca-step-up.spec.ts` | `client-web/e2e/sca-payment.spec.ts`, `sca-timeout.spec.ts`, `sca-lockout.spec.ts`, `sca-exemption.spec.ts` | `.../UITests/ScaUITests.swift` | `.../androidTest/.../ScaE2ETest.kt` |
| Cross-platform consistency | n/a | `cross-platform-tests/scenarios/sca_payment_happy.json`, `sca_exemption_trusted_beneficiary.json`, `sca_timeout.json` | (same) | (same) |

## 12. UX notes

- The dynamic-linking region uses the largest text weight on the screen — "amount + recipient" is what the regulator wants unmissable.
- The countdown announces at 1-minute intervals and at 30 s / 15 s remaining (`GUI-A11Y-06`).
- For simulated BankID, the synthetic "phone screen" sits side-by-side with the inputs so the user simulates cross-device confirmation.
- Step-up SCA modal (admin) never obscures the action it authorises.

## 13. Open questions

- Whether to support biometric-as-SCA (WebAuthn / passkeys) in MVP. *Defer per CLI/ADM requirements open-questions — flagged for Phase-1 review.*
- Whether to show a "Why am I being asked?" link on the SCA screen. *Decision: yes — short tooltip referencing PSD2; customers benefit from knowing it's regulatory.*
