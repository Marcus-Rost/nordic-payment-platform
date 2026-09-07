# UC-G30 — Operator Login with SCA

*Owner: Frontend / Identity / Admin*
*Status: DRAFT*
*Implementation status: Admin Web ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

A back-office operator authenticates to the Admin Web Console. Authentication is OAuth2 Authorization Code + PKCE against the identity service, with SCA (TOTP in portfolio default; pluggable to BankID merchant ID per D6). Session is 15-min idle / 8-h hard cap; concurrent-session limit is 1 per operator (logging in elsewhere terminates the existing session). A step-up SCA is required for every `*:manage` action regardless of session age.

## 2. Actors

- **Primary:** Back-office operator (FRAUD_ANALYST, COMPLIANCE_OFFICER, OPERATIONS, SUPERVISOR, or ADMIN)
- **Secondary:** Identity service, SCA provider (TOTP)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes | |
| Client Web/iOS/Android | no | Customer login is UC-G01 |

## 4. Preconditions

- Operator exists in identity service with a role assignment (UC-G43 covers creation).
- Operator has enrolled a TOTP factor (or equivalent).
- Admin Web app loaded in a supported browser.

## 5. Postconditions

- Operator has an access token with role-to-scope-bundle claims.
- Refresh token stored in an `HttpOnly, Secure, SameSite=Strict` cookie scoped to `/oauth2/`.
- Any existing active session for the same operator is revoked (single-concurrent-session).
- Audit event `auth.operator-login.success` with role, IP, UA, SCA factor used.

## 6. Main flow (happy path)

1. Operator opens Admin Web. App checks for a valid access token; none present (or expired) → redirect to identity service `/oauth2/authorize?...&audience=admin&acr_values=loa3-admin`.
2. Identity service renders the operator login form: username + password. (This form is visually distinct from the customer login — different branding; helps operators realise which surface they're on.)
3. Operator submits credentials.
4. On success, identity service triggers SCA (TOTP) — screen shows "Skriv inn 6-sifret kode / Enter 6-digit code" with 5-min countdown.
5. Operator enters TOTP code.
6. On success:
   - Identity service checks for existing active session for this operator; if present, revokes it (the other tab/window receives a force-logout SSE event next render — see UC-G53).
   - Issues authorization code + redirects to Admin Web callback.
7. Admin Web exchanges code for tokens via `POST /oauth2/token`.
8. Admin Web decodes JWT, extracts role + scopes, and renders the dashboard (UC-G42) or the landing page appropriate to the role (FRAUD_ANALYST → UC-G31 queue; COMPLIANCE_OFFICER → UC-G34 queue; etc.).

## 7. Alternative flows

### AF-01 — Wrong password

1. Identity service rejects; increments per-account counter; operator sees generic "Feil brukernavn eller passord" (no enumeration).
2. 5 failed attempts in 24h → account locked; requires SUPERVISOR unlock in UC-G43.

### AF-02 — TOTP wrong code

1. 3 failed TOTPs in the same session → 5-min lockout with countdown.
2. 5 lockouts in 24h → admin-required unlock (IDS-F07).

### AF-03 — Operator already has an active session (another tab)

1. New login succeeds; old session receives a force-logout event within 10s via SSE; shows modal "Du er logget inn et annet sted — økten her er avsluttet.".

### AF-04 — Account suspended

1. Identity service returns 403 with `user_suspended`. Admin Web shows "Kontoen er midlertidig suspendert. Kontakt din leder."

### AF-05 — First-login forced password change + SCA enrolment

1. A4-created operator accounts start with a temporary password + no SCA factor.
2. First login redirects to a password-change form, then SCA enrolment (TOTP QR scan via a secondary device + code verification).
3. Audit event `operator.first-login-setup.complete`.

### AF-06 — Step-up SCA during session (before a `*:manage` action)

1. Operator clicks a manage action (e.g., Decide fraud case).
2. Admin Web POSTs to the action endpoint without a step-up token; backend responds 401 `sca-required`.
3. Admin Web shows an inline SCA modal; operator enters TOTP.
4. New short-lived step-up token is issued; action retried with it.
5. This is UC-G51 dynamic-linking SCA but bound to the specific action (e.g., `{caseId, decision}`).

### AF-07 — 8-h hard cap reached

1. Any API call returns 401 `session-expired`; Admin Web routes to login and preserves the in-progress screen state (best-effort — the operator can navigate back after re-login).

### AF-08 — IdP unreachable

1. Admin Web shows the maintenance banner and a "Prøv igjen / Retry".

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Cookie blocked | `Set-Cookie` no-op | Detect and show "Cookies påkrevd for å logge inn — sjekk nettleserinnstillinger." |
| State mismatch on OAuth callback | State param | Show severe-error page; do not retry; log |
| Clock skew on TOTP | SCA validation | Copy hints about clock sync; suggest refreshing |

## 9. Related requirements

- `gui_requirements.md` — `ADM-F01`, `ADM-F02`, `ADM-F03`, `GUI-SEC-01`, `GUI-SEC-04`, `GUI-SEC-10`, `GUI-SEC-14`
- `system_requirements.md` — IDS-F01..F12
- `PROJECT_DECISIONS.md` — D2

## 10. API operations involved

- `GET  /oauth2/authorize` (admin audience)
- `POST /oauth2/token`
- `POST /oauth2/revoke` (logout)
- `POST /sca/verify`
- `POST /sca/step-up` — step-up for manage actions
- `POST /sca/enroll` — first-login

## 11. Tests realising this use case

| Layer | Admin Web |
|-------|-----------|
| Unit | `admin-web/.../auth/__tests__/oauth-flow.test.ts`, `step-up.test.ts` |
| Component | `admin-web/.../auth/__tests__/login-screen.test.tsx`, `sca-modal.test.tsx` |
| Contract | `admin-web/.../admin-auth.pact.test.ts` |
| E2E | `admin-web/e2e/operator-login.spec.ts`, `admin-web/e2e/step-up-sca.spec.ts`, `admin-web/e2e/concurrent-session.spec.ts` |

## 12. UX notes

- Admin Web uses a darker, more utilitarian colour palette than the customer GUIs — visual distinctiveness prevents "am I on the wrong surface?" errors.
- The SCA step-up modal never obscures the row being acted on — operators need to see what they're authorising.
- Concurrent-session take-over modal in the old tab is unmissable but not alarming — it's a deliberate feature, not a security incident.

## 13. Open questions

- Whether to support corporate-SSO (SAML or OIDC from the bank's existing IdP) for operators. *Defer per PROJECT_DECISIONS "open follow-ups".*
- Whether SUPERVISOR and ADMIN roles should require hardware security keys (WebAuthn) in addition to TOTP. *Defer to Phase-1 security review.*
