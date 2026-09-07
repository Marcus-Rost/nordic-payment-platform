# UC-G01 — Customer Login and Session Lifecycle

*Owner: Frontend / Identity*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

A bank customer authenticates to one of the three client variants and obtains a session that allows access to their accounts, payments, and self-service features. Authentication includes Strong Customer Authentication (SCA) per PSD2. The session has a defined idle timeout and a defined hard cap. The customer can log out explicitly, in which case the session is fully revoked across the device (and refresh tokens cannot be reused).

## 2. Actors

- **Primary:** Bank customer (account holder)
- **Secondary:** `jrpay-identity-service` (A7), `ScaProvider` (`SimulatedBankIdScaProvider` by default per D6)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | Admin login is UC-G30 |
| Client Web | yes | OAuth2 redirect to identity service login page |
| Client iOS | yes | AppAuth-iOS in `ASWebAuthenticationSession`; PKCE |
| Client Android | yes | AppAuth-Android in Custom Tab; PKCE |

## 4. Preconditions

- The customer has an existing identity in the identity service (provisioned by upstream CDD per `system_requirements.md` §11). Self-service registration is out of scope.
- The customer has at least one SCA factor enrolled (simulated BankID account in portfolio default).
- The application is installed and online (web: page loaded; mobile: networked).

## 5. Postconditions

- The customer has a valid access token (15-min TTL) bound to their `sub`.
- The customer has a valid refresh token (30-day rolling, single-use rotated).
  - Web: in a first-party `HttpOnly`, `Secure`, `SameSite=Strict` cookie scoped to `/oauth2/`.
  - Mobile: in platform-secure storage (Keychain / EncryptedSharedPreferences) released only after biometric unlock.
- An `auth.login.success` audit event is published to `audit.events` with the actor, IP, user-agent, ACR (`loa3`).

## 6. Main flow (happy path)

1. The customer opens the app / web page. The GUI checks for a refresh token.
2. **No refresh token:** GUI displays the **Login** screen, primary action labelled "Logg inn med BankID" (NB) / "Sign in with BankID" (EN). On tap/click, GUI:
   1. Generates a random `code_verifier` (43–128 chars, RFC 7636) and computes `code_challenge = base64url(SHA-256(code_verifier))`.
   2. Generates a `state` (CSRF) and `nonce` (replay).
   3. Redirects (web) / opens system browser (mobile) to identity service `/oauth2/authorize?response_type=code&client_id=<client>&redirect_uri=<...>&scope=<requested>&code_challenge=<...>&code_challenge_method=S256&state=<...>&nonce=<...>&acr_values=loa3`.
3. Identity service shows its login form: customer enters their identifier (national identifier or username for the simulated provider).
4. Identity service triggers the `ScaProvider`. The simulated provider:
   1. Returns an `scaSessionId` to the browser.
   2. Web: shows a "Confirm in your BankID app" panel with a synthetic mobile screen the user clicks to approve in the same window (simulating cross-device flow).
   3. Records the `acr=loa3` claim if approved.
5. Identity service issues an authorization code, redirects to `<redirect_uri>?code=<code>&state=<state>`.
6. GUI verifies `state`, posts `POST /oauth2/token` with `code`, `code_verifier`, `client_id`, `redirect_uri`.
7. Identity service returns access token (JWT RS256, 15-min TTL) and refresh token. Web: refresh token sent as a cookie via `Set-Cookie` (so it never enters JS); mobile: refresh token in the JSON body, GUI writes to secure storage.
8. GUI verifies `nonce` claim in the ID token (if `openid` scope was requested), stores access token in memory.
9. GUI navigates to the home screen (account list, UC-G02).
10. Identity service publishes `auth.login.success` to `audit.events`.

## 7. Alternative flows

### AF-01 — Refresh token present, valid

1. GUI finds a refresh token in storage.
2. **Mobile:** GUI prompts for biometric unlock (Face ID / Touch ID / BiometricPrompt). Successful unlock releases the refresh token from secure storage.
3. GUI calls `POST /oauth2/token` with `grant_type=refresh_token`.
4. Identity service returns a new access token + new refresh token (rotation).
5. GUI navigates to home.

### AF-02 — Refresh token expired or revoked

1. The token endpoint returns `400 invalid_grant`.
2. GUI clears any local state, returns to step 2 of the main flow.

### AF-03 — SCA challenge fails (wrong code, BankID rejection)

1. Identity service records the failure, increments the per-user counter.
2. After 3 failures within the same session, identity service returns 423 Locked with retry-after = 300 s.
3. GUI displays the canonical i18n message `auth.sca.locked-for-5-min` with a countdown.
4. After cooldown, customer may retry.

### AF-04 — SCA challenge times out (5-min limit)

1. Identity service marks the session `EXPIRED` and returns an error to the redirect.
2. GUI displays `auth.sca.timeout` and offers a "Try again" button.

### AF-05 — User cancels the flow

1. User dismisses the system browser sheet (mobile) or clicks "Cancel" on the login form.
2. GUI returns to its initial state; no audit event.

### AF-06 — IdP unreachable

1. The browser fails to load the IdP page (timeout, DNS, or 5xx).
2. GUI displays `auth.idp.unreachable` with a "Retry" button. The status banner (UC-G54) is checked and any active maintenance notice surfaced.

### AF-07 — Account is suspended / locked at the identity service (5 lockouts in 24 h)

1. Identity service returns `403 Forbidden` with `error=user_locked`.
2. GUI displays `auth.account.locked` with instructions to contact support and a reference id.

### AF-08 — Account is FROZEN at the ledger (A1)

This is not detected at login. Login succeeds; CLI-F14 surfaces the FROZEN status on the account view.

## 8. Idle and hard-cap behaviour

- **Idle timer:** GUI tracks last user interaction. At T-60 s before idle timeout (default 15 min), GUI raises a non-blocking dialog "You are about to be logged out. Stay signed in?" with two options: "Stay signed in" (re-arms timer; calls `GET /oauth2/userinfo` to refresh access token if needed) or "Log out".
- **Hard cap:** Access token issuance has a 24-h hard cap (customer audience). At cap, refresh fails with `invalid_grant`; AF-02 applies.
- **Background:** Mobile app blanks the screen on background and requires biometric on resume (`GUI-SEC-11`).
- **Web tab inactive 5 min:** screen locks (overlay; does not log out). Bringing the tab back requires the local biometric on supported browsers (WebAuthn, optional in MVP) or no challenge but auto-extends the idle timer if recent.

## 9. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Network failure during `/oauth2/token` | `fetch` throws / timeout 10 s | Show retryable error toast; preserve `code_verifier` for retry within 30 s |
| `state` mismatch on callback | After redirect | Show `auth.state-mismatch` (severe; possible CSRF); do not retry; log to observability |
| `nonce` mismatch in ID token | After token exchange | Show `auth.nonce-mismatch`; clear any stored tokens; force restart |
| Clock skew > 5 min causing JWT `iat`/`exp` validation failure | On token decode | Show `auth.clock-skew` with instruction to check device time |
| Cookie blocked by browser settings (web) | `Set-Cookie` does not stick | Detect via test request; show `auth.cookies-required` with browser-settings instructions |

## 10. Logout (UC-G53 detail belongs there; summary here)

1. User taps Log out.
2. GUI calls `POST /oauth2/revoke` with the refresh token.
3. On success, GUI clears in-memory access token, deletes the refresh-token cookie / secure-storage entry, clears any cached read views (account list, transactions).
4. GUI navigates to login screen.
5. Identity service publishes `auth.logout` to `audit.events`.

## 11. Related requirements

- `gui/requirements/gui_requirements.md` — `GUI-SEC-01..02`, `GUI-SEC-04`, `GUI-SEC-06..07`, `GUI-SEC-10..11`, `GUI-SEC-13`, `GUI-SEC-17`, `CLI-F01..F05`, `IDS-F01..F11`
- `gui/PROJECT_DECISIONS.md` — D2, D6
- `gui/architecture/ARCHITECTURE_DESIGN.md` — §4 (Authentication and Authorisation Architecture)

## 12. API operations involved

- `GET  /.well-known/openid-configuration` (`identity.yaml`)
- `GET  /.well-known/jwks.json` (`identity.yaml`)
- `GET  /oauth2/authorize` (`identity.yaml`)
- `POST /oauth2/token` (`identity.yaml`)
- `POST /oauth2/revoke` (`identity.yaml`)
- `POST /sca/start` and `POST /sca/{sessionId}/verify` (`identity.yaml`)

## 13. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `client-web/src/shared/auth/__tests__/oauth-flow.test.ts` | `client-ios/.../Tests/AuthFlowTests.swift` | `client-android/.../auth/AuthFlowTest.kt` |
| Component | `client-web/.../LoginScreen.test.tsx` | `client-ios/.../Tests/LoginViewTests.swift` (ViewInspector) | `client-android/.../LoginScreenTest.kt` (Compose UI test) |
| Contract (Pact) | `client-web/.../auth.pact.test.ts` | `client-ios/.../AuthPactTests.swift` | `client-android/.../AuthPactTest.kt` |
| E2E | `client-web/e2e/login.spec.ts` (Playwright vs. live IdP) | `client-ios/.../UITests/LoginUITests.swift` (XCUITest vs. live IdP) | `client-android/.../androidTest/.../LoginEspressoTest.kt` (vs. live IdP) |
| Cross-platform consistency | `cross-platform-tests/scenarios/login.json` | (same) | (same) |

## 14. UX notes

- The "Logg inn med BankID" CTA must visually dominate the login screen; alternative TOTP login is a secondary action.
- The IdP origin is shown clearly in the URL bar (web) and in the system-browser chrome (mobile) — do not embed a WebView for OAuth (anti-pattern; explicitly forbidden by AppAuth guidance).
- The session-warning dialog uses Radix Dialog (web), `confirmationDialog` (iOS), `AlertDialog` (Android), all built on the design tokens.
- Countdown timers must be announced to screen readers using `aria-live="polite"` (web) and platform equivalents (mobile).

## 15. Open questions

- Whether to support WebAuthn (passkeys) as an additional SCA factor in MVP or defer to a follow-up. *(Defer: not on the MVP scope; flag for Phase-1 review.)*
- Whether to support biometric unlock without re-authentication for low-value scopes only on web (WebAuthn). *(Defer.)*
