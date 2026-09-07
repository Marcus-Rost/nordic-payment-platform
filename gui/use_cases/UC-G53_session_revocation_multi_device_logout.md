# UC-G53 — Session Revocation Across Devices on Logout

*Owner: Frontend / Identity*
*Status: DRAFT*
*Implementation status: Admin Web ☐ | Client Web ☐ | Client iOS ☐ | Client Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

When the user taps Log out, the GUI explicitly revokes the refresh token at the identity service, clears in-memory and at-rest token state on the current device, and broadcasts the revocation so any other devices logged in as the same user are forced to re-authenticate. This protects customers (e.g., lost phone) and satisfies OAuth 2.1 §4 and GUI-SEC-17. For customer-side users, the GUI also exposes a "Log out everywhere" action that revokes all refresh tokens across all devices without affecting the current one (useful if a device has been lost).

## 2. Actors

- **Primary:** User
- **Secondary:** Identity service

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes | For admin concurrent-session limit = 1, the system logs out elsewhere automatically on login; this UC covers manual logout |
| Client Web | yes | |
| Client iOS | yes | |
| Client Android | yes | |

## 4. Preconditions

- User has an active session on the current device.

## 5. Postconditions

- Refresh token on current device revoked; in-memory access token cleared; secure-storage entries cleared; cached read views (account list, transactions) purged.
- Identity service emits `session.revoked` SSE to other devices of the same user; they cascade to logout.
- Audit `auth.logout` with device identity.

## 6. Main flow (happy path)

### 6.1 Single-device logout

1. User taps **Logg ut**.
2. GUI calls `POST /oauth2/revoke` with `{token: <refresh-token>, token_type_hint: "refresh_token"}`. Mobile passes the refresh token from secure storage; web relies on the browser attaching the `HttpOnly` cookie automatically.
3. Identity service marks the refresh token revoked and propagates to the access-token validity table (resource servers reject further calls with the matching `jti`).
4. GUI does NOT consider logout complete until the response is 2xx (`GUI-SEC-17`).
5. On success:
   - Web: clear `sessionStorage` / `localStorage`, clear route state, navigate to login.
   - Mobile: delete refresh-token entry in Keychain / EncryptedSharedPreferences, clear in-memory state, clear cached reads, navigate to login.
6. Identity service emits `auth.logout` audit event.

### 6.2 Logout-everywhere

1. From **Innstillinger** → **Sikkerhet / Security** → **Logg ut alle enheter / Log out everywhere**.
2. Dialog explains: "Dette logger deg ut på alle andre enheter du er logget inn på. Du forblir innlogget her."
3. SCA required (high-risk action — UC-G51).
4. GUI calls `POST /oauth2/revoke-all?preserve-current=true`.
5. Identity service revokes all refresh tokens for this user except the one in the request header (or cookie).
6. Other devices receive SSE `session.revoked`; on next heartbeat, they force-logout the user.

### 6.3 Concurrent-session enforcement (admin)

1. Operator logs in from a new browser (UC-G30 step 6).
2. Identity service silently revokes any other active session for this operator.
3. The older session gets an SSE `session.revoked`; UI displays "Du er logget inn et annet sted. Denne økten er avsluttet." and routes to login.

## 7. Alternative flows

### AF-01 — `POST /oauth2/revoke` fails (5xx)

1. GUI does not consider logout complete. Shows "Kunne ikke fullføre utlogging — prøv igjen." with Retry.
2. After 3 failures, GUI offers "Force-logga ut lokalt" (removes local state only) with a warning that remote tokens may still be valid — customer should contact support.

### AF-02 — User has no network connectivity

1. Offline-logout: local state cleared immediately; a pending revoke is queued to fire on reconnect (stored locally with the token). On reconnect, revoke fires.

### AF-03 — Multiple tabs (web)

1. The tab performing logout broadcasts a `BroadcastChannel` "logout" event.
2. Other tabs clear their state and navigate to login without needing a separate revoke.

### AF-04 — Logout-everywhere while offline

1. Blocked — requires online connectivity because SCA and revoke are server-side.

### AF-05 — User logs out immediately after initiating a payment (race)

1. Payment may already have been submitted; A4 stamps it with the `jti` it was submitted under. Logout revokes future calls but in-flight is honoured.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Revoke 5xx | Response | AF-01 |
| Local state clear fails (extremely rare) | Exception | Prompt user to manually clear browser/cookies |
| SSE drop causes other devices not to log out promptly | Monitoring | Heartbeat (30s) + introspection pattern catches it within 30s; operations alert on longer gaps |

## 9. Related requirements

- `gui_requirements.md` — `GUI-SEC-11`, `GUI-SEC-17`, `CLI-F03`
- `system_requirements.md` — IDS-F08 (concurrent sessions), IDS-F09 (revocation)

## 10. API operations involved

- `POST /oauth2/revoke` (`identity.yaml`, RFC 7009)
- `POST /oauth2/revoke-all`
- SSE `GET /api/v1/session/stream` (same stream as UC-G52 AF-07)

## 11. Tests realising this use case

| Layer | Admin Web | Client Web | iOS | Android |
|-------|-----------|-----------|-----|---------|
| Unit | `.../session/__tests__/logout.test.ts` | (same) | `.../Tests/LogoutTests.swift` | `.../session/LogoutTest.kt` |
| Component | `.../session/__tests__/settings-security.test.tsx` | (same) | `.../Tests/SecuritySettingsViewTests.swift` | `.../SecuritySettingsScreenTest.kt` |
| Contract | shares auth pact | (same) | (same) | (same) |
| E2E | `admin-web/e2e/concurrent-session-take-over.spec.ts` | `client-web/e2e/logout.spec.ts`, `logout-everywhere.spec.ts` | `.../UITests/LogoutUITests.swift` | `.../androidTest/.../LogoutE2ETest.kt` |
| Cross-platform consistency | n/a | `cross-platform-tests/scenarios/logout_single_device.json`, `logout_everywhere.json` | (same) | (same) |

## 12. UX notes

- On mobile, the "Log out everywhere" option is not in the main menu; it's in Security settings to reduce accidental taps.
- After logout, the login screen briefly shows "Du er logget ut — vi ses igjen!" then transitions to the normal login UI.
- Concurrent-session-take-over message is calm, not alarming; it's a deliberate feature.

## 13. Open questions

- Whether to show the user a list of active sessions before logout-everywhere. *Decision: yes — display device names (iPhone, MacBook Pro) and last-seen; customer can selectively revoke.*
- Implementation: separate session-list endpoint + per-session DELETE. *Scoped for MVP but marked phase-2-lean.*
