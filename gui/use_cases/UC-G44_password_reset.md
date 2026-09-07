# UC-G44 — Password Reset

*Owner: Frontend / Identity*
*Status: DRAFT*
*Implementation status: Admin Web ☐ | Client Web ☐ | Client iOS ☐ | Client Android ☐*
*Last updated: 2026-04-24*

---

## 1. Purpose

A customer or operator has forgotten their password. They initiate a self-service reset: GUI takes an identifier, identity service sends a one-time signed reset link to the verified contact (email for customers and operators), the user follows the link to set a new password, and on success re-authenticates with SCA. Rate-limited and account-lockout-aware to prevent abuse.

Phase 1 finding reference: FC.2 (CLI-F05 had no realising UC).

## 2. Actors

- **Primary:** Bank customer (CLI-F05) or back-office operator (same flow, different audience).
- **Secondary:** Identity service (A7), email adapter (notifications service), `ScaProvider` for post-reset SCA.

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes | Operator reset |
| Client Web | yes | Customer reset |
| Client iOS | yes | Link opens in Universal Link; ends in app |
| Client Android | yes | App Link; ends in app |

## 4. Preconditions

- Account exists in identity service.
- Account has a verified email address (UC-G22 for customers; UC-G43 for operators).

## 5. Postconditions

- Password is updated in identity service (argon2id hash per IDS-F14).
- A post-reset SCA proof is recorded.
- Any active sessions for the user are revoked (defensive; the user's session may be the attacker's).
- All refresh tokens for the user are invalidated.
- Audit events: `auth.password-reset.requested`, `auth.password-reset.completed`, plus `session.revoked` cascade.
- Customer is signed in with a fresh session after the flow.

## 6. Main flow (happy path — customer)

1. From the login screen, user taps **Glemt passord? / Forgot password?**.
2. Form: identifier (email address, or `fødselsnummer` for customer, or username for operator).
3. User submits.
4. GUI calls `POST /oauth2/password-reset/request` with the identifier. Response is always `202 Accepted` regardless of whether the identifier exists (prevents enumeration).
5. Identity service, if the identifier resolves:
   - Generates a one-time reset token: 256-bit random, HMAC-signed, 30-min TTL.
   - Stores `hash(token) → {userId, expiresAt, used=false}` in `identity.password_reset_tokens`.
   - Sends email via notifications service with a link `https://{client-web-origin}/reset?t=<token>` (or `https://admin.{env}.jrpay.example/reset?t=<token>` for operators).
6. GUI shows a confirmation screen: "Hvis kontoen finnes, sender vi en e-post med en lenke. Sjekk innboksen (og søppelpost)." / "If the account exists, we'll email a link. Check your inbox (and spam)."
7. User opens the email, clicks the link. The link lands on `/reset` with the token in the query.
8. GUI calls `GET /oauth2/password-reset/verify?t=<token>` to validate the token (not consume). On 200: render the new-password form. On 4xx: render the appropriate error page (expired, already used, invalid).
9. New-password form:
   - New password (≥ 10 chars, strength check against zxcvbn score ≥ 3 per IDS-F15).
   - Confirm password (must match).
   - Breached-password check (HIPS offline library or API call gated by server-side; `urn:problem:breached-password` on hit).
10. User submits. GUI calls `POST /oauth2/password-reset/complete` with `{token, newPassword}`.
11. Identity service:
    - Re-validates token, marks `used=true`, sets `hash(newPassword)` using argon2id.
    - Revokes all existing refresh tokens for the user (device-family rotation).
    - Revokes all active SSE sessions (UC-G53 cascade via `session.revoked`).
    - Emits `auth.password-reset.completed` audit.
12. GUI shows a confirmation and redirects to the login screen pre-filled with the identifier.
13. User logs in per UC-G01, which triggers SCA; on success the session is active again.

## 7. Alternative flows

### AF-01 — Account does not exist

1. Same `202 Accepted` as the happy path. GUI cannot distinguish. (Enumeration defence.)
2. No email sent.

### AF-02 — Token expired (30+ min since issue)

1. `GET /oauth2/password-reset/verify` returns `410 urn:problem:signed-url-expired` (sharing the conventional Problem URI; the pattern is the same).
2. GUI shows "Lenken er utløpt. Be om ny." / "Link expired. Request a new one." with button.

### AF-03 — Token already used

1. `410 urn:problem:signed-url-expired` with detail `already-used`.
2. GUI shows specific copy "Denne lenken har allerede blitt brukt. Be om en ny." / "This link has already been used. Request a new one."

### AF-04 — Token validates but user attempts to reuse an old password

1. Identity service compares new hash against the last 4 stored password hashes (IDS-F17 — added here as new); on match returns `422 urn:problem:password-requirements-unmet` detail `reused-recent`.
2. GUI surfaces "Du kan ikke bruke et passord du har brukt nylig." / "You cannot reuse a recent password."

### AF-05 — Password fails policy (length, complexity, breached-password)

1. Identity service returns `422 urn:problem:password-requirements-unmet` or `urn:problem:breached-password`.
2. GUI shows the specific hint and the user tries a stronger password.

### AF-06 — User triggers reset from a new device (account-takeover signal)

1. Identity service records the request's IP country and User-Agent fingerprint. If it differs significantly from the user's typical profile, emit a high-priority alert to the fraud queue (`fraud.suspicious-reset`) and send the reset email with an additional "Hvis dette ikke var deg, kontakt oss umiddelbart" warning.
2. Reset proceeds; fraud team reviews in parallel.

### AF-07 — User cancels on the new-password form

1. GUI navigates away; the token remains valid for its TTL. If the user never completes, the token expires naturally.

### AF-08 — Rate-limit exceeded

1. Per FS.3 rate-limit: `10 / hour / identifier + 3 / hour / IP`. Response on exceed: `429 urn:problem:rate-limit` with `Retry-After` header.
2. GUI shows retry countdown; no indication of whether the limit is per-account or per-IP (privacy).

### AF-09 — Mobile deep link opens the app

1. Email link uses the bank's verified domain (Universal Link / App Link). The OS routes to the installed app if present.
2. The app routes per UC-G29 deep-link conventions: `/reset?t=<token>` is in the allowlist with regex `[A-Za-z0-9_-]{43,}` for the token format. Invalid routes land on inbox.
3. In-app, step 8 onwards applies.

### AF-10 — User has SCA factor enrolled; no SCA during reset (flows only)

1. The password reset itself does not include SCA — the one-time token + new password is the proof. After reset, the user logs in via UC-G01, which runs SCA. This is standard industry practice.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Email adapter down | notifications service 5xx | Reset-request call still returns 202 (no enumeration); notifications service retries with backoff; customer may not receive for minutes |
| Identity service 5xx | response | retryable; GUI shows maintenance banner |
| Token DB corruption | 500 on verify | `urn:problem:unexpected-error`; support reference |
| Concurrent use of same token from two tabs | second call gets `used=true` marker | second tab lands on AF-03 |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F05`, `IDS-F14..F17` (password hashing, policy, reset), `GUI-SEC-*` auth baseline
- FS.6, FC.2 (Phase 1 fixes)

## 10. API operations involved

- `POST /oauth2/password-reset/request` — initiate; returns 202
- `GET  /oauth2/password-reset/verify?t=<token>` — validate without consuming
- `POST /oauth2/password-reset/complete` — consume token + set new password
- (post-reset) `POST /oauth2/token` — login per UC-G01

All in `identity.yaml`.

## 11. Tests realising this use case

| Layer | Admin Web | Client Web | iOS | Android |
|-------|-----------|-----------|-----|---------|
| Unit | `.../auth/__tests__/reset-form.test.tsx`, `password-policy.test.ts` | (same) | `.../Tests/PasswordResetTests.swift` | `.../auth/PasswordResetTest.kt` |
| Component | `.../auth/__tests__/reset-screen.test.tsx` | (same) | `.../Tests/PasswordResetViewTests.swift` | `.../PasswordResetScreenTest.kt` |
| Contract | `.../reset.pact.test.ts` | (same) | `.../ResetPactTests.swift` | `.../ResetPactTest.kt` |
| E2E | `admin-web/e2e/password-reset.spec.ts` | `client-web/e2e/password-reset.spec.ts` | `.../UITests/PasswordResetUITests.swift` (fixture inbox) | `.../androidTest/.../PasswordResetE2ETest.kt` |
| Cross-platform consistency | n/a | `cross-platform-tests/scenarios/password_reset_happy.json`, `password_reset_expired_link.json`, `password_reset_breached_password.json`, `password_reset_enumeration_defence.json` | (same) | (same) |

## 12. UX notes

- Password-strength meter (zxcvbn) is colour + label; colour-independent per `GUI-A11Y-03`.
- "Vis/skjul passord" toggle uses `aria-pressed` for screen readers.
- The confirmation screen (step 6) copy is deliberately ambiguous about whether the account exists — this is correct for defence, but users may worry they typed the identifier wrong. A support-contact link is available.
- Email copy: Norwegian-formal, short, with the bank's visible branding; link expires in 30 min is stated.
- Post-reset "All sessions ended" toast on login reassures the user (and alerts them if a reset was not initiated by them).

## 13. Open questions

- Should the post-reset SCA require BankID always (skip TOTP fallback)? Decision: no — fallback remains per D6. But document clearly.
- Is 30-min TTL the right number? Aligns with industry norm (most banks 15-60 min); 30 is a reasonable default.
