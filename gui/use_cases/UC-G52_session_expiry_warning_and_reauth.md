# UC-G52 — Session Expiry Warning and Re-Authentication

*Owner: Frontend / Identity*
*Status: DRAFT*
*Implementation status: Admin Web ☐ | Client Web ☐ | Client iOS ☐ | Client Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

At T-60 s before idle-session expiry, the GUI shows a non-blocking warning and offers Stay-signed-in or Log-out. On actual expiry, the GUI forces re-authentication preserving the current screen context where possible. Idle timeouts: customers 15 min, operators 15 min. Hard caps: customer 24 h, operator 8 h — at the cap, refresh-token refresh will fail and a full login is required regardless of user activity.

## 2. Actors

- **Primary:** User
- **Secondary:** Identity service

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes | |
| Client Web | yes | |
| Client iOS | yes | Different screen-lock semantics (UC-G27 biometric re-unlock on background) |
| Client Android | yes | Same as iOS |

## 4. Preconditions

- User has an active session.

## 5. Postconditions

- Either the session is extended (on Stay-signed-in), or the user is logged out / forced to re-authenticate.

## 6. Main flow (happy path)

1. GUI tracks last user interaction (mouse/keyboard on web; tap/scroll on mobile).
2. At (idleTimeout − 60 s), GUI raises a non-blocking dialog:
   - Title: "Du blir logget ut om 60 sekunder" / "You'll be logged out in 60 seconds"
   - Body: "Trykk 'Fortsett' for å bli værende." / "Tap 'Stay signed in' to remain."
   - Buttons: **Fortsett / Stay signed in** (primary), **Logg ut / Log out** (secondary).
   - Countdown updates each second; announced to screen readers at 30s / 15s remaining.
3. If Stay signed in: GUI calls `GET /oauth2/userinfo` (refreshes access token if near TTL, via `POST /oauth2/token grant_type=refresh_token`) — this proves the refresh token still works and resets the idle timer.
4. If Log out: UC-G53 (logout flow).
5. If the dialog is ignored and idle expires:
   - GUI discards the access token from memory.
   - Routes to login with a "Din økt utløp" banner and, on mobile, preserves the in-progress form state where possible (the customer can tap login to continue).
   - After successful re-auth, GUI restores the route that was active at expiry.

## 7. Alternative flows

### AF-01 — User becomes active during the warning (moves mouse, types)

1. Warning dismisses automatically; idle timer resets.

### AF-02 — Web tab is backgrounded for 5 min

1. Web screen locks with an overlay (does not log out). Returning to the tab requires the local biometric on supported browsers (WebAuthn, optional in MVP) OR simply auto-extends if recent activity. The session itself is unchanged.

### AF-03 — Mobile app is backgrounded

1. Screen is blanked (`FLAG_SECURE` / iOS snapshot blur) per GUI-SEC-10.
2. On resume, biometric is required (UC-G27) before rendering content; if biometric fails, full login.

### AF-04 — 24h / 8h hard cap reached

1. Refresh-token exchange returns `invalid_grant`. GUI routes to login with "Dagen din er utløpt" (customer) / "Arbeidsøkten er utløpt" (operator).

### AF-05 — User clicks Stay but the refresh fails

1. Refresh-token call returns 400 `invalid_grant` (revoked or expired). Same as AF-04.

### AF-06 — Multiple tabs open (web)

1. All tabs share the cookie-based refresh token; idle timer is per-tab (independent).
2. If one tab refreshes, the refreshed access token is broadcast via a `BroadcastChannel` so other tabs don't needlessly re-refresh.

### AF-07 — Concurrent-session limit triggers force-logout from another device

1. GUI receives an SSE `session.revoked` event (see UC-G53).
2. All tabs/devices affected show "Du er logget inn et annet sted" and route to login.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Refresh-token endpoint 5xx | Response | Retry 3× with backoff; if still failing, route to login with maintenance copy |
| Clock skew | Local time vs. server time | Soft-warn at 5-min skew, hard-fail at 10-min skew |
| Browser sleep / system suspend (laptop closed) | Time jump on wake | Re-evaluate idle timer using wall-clock, not `setInterval` counts |

## 9. Related requirements

- `gui_requirements.md` — `GUI-SEC-10`, `CLI-F02`, `ADM-F02`, `GUI-A11Y-04..06`
- `system_requirements.md` — IDS-F03 (token TTLs)

## 10. API operations involved

- `GET  /oauth2/userinfo` (used to refresh access token)
- `POST /oauth2/token grant_type=refresh_token`
- SSE `GET /api/v1/customer/session/stream` (for revocation events, UC-G53)

## 11. Tests realising this use case

| Layer | Admin Web | Client Web | iOS | Android |
|-------|-----------|-----------|-----|---------|
| Unit | `.../session/__tests__/idle-timer.test.ts`, `warning-dialog.test.tsx` | (same pattern) | `.../Tests/IdleTimerTests.swift` | `.../session/IdleTimerTest.kt` |
| Component | `.../session/__tests__/session-screen.test.tsx` | (same) | `.../Tests/SessionViewTests.swift` | `.../SessionScreenTest.kt` |
| Contract | shares auth pact | (same) | (same) | (same) |
| E2E | `admin-web/e2e/session-warning.spec.ts`, `session-hard-cap.spec.ts` | `client-web/e2e/session-warning.spec.ts` | `.../UITests/SessionUITests.swift` | `.../androidTest/.../SessionE2ETest.kt` |
| Cross-platform consistency | n/a | `cross-platform-tests/scenarios/session_warning_stay.json`, `session_warning_logout.json`, `session_hard_cap.json` | (same) | (same) |

## 12. UX notes

- The dialog is non-blocking — user can continue reading; only the two buttons are modal.
- Sound / vibration on 30 s and 15 s warnings, opt-outable in accessibility preferences.
- The "restore route on re-auth" feature is silent for the user; they shouldn't notice the interstitial beyond a brief login.

## 13. Open questions

- Whether the warning threshold should be configurable (some operators want 2 min). *Defer — 60 s is the industry convention.*
