# UC-G54 — Status Banner Display (Maintenance / Incident)

*Owner: Frontend / Ops*
*Status: DRAFT*
*Implementation status: Admin Web ☐ | Client Web ☐ | Client iOS ☐ | Client Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

All four GUIs display a status banner — scheduled maintenance, in-progress incident, or resolved-incident notice — sourced from the status-banner service (UC-G42 §6.5). Banners are visible on the login screen (no auth required) and on all authenticated screens. Severity drives visual treatment. DORA Art. 12 requires timely incident communication to users; this is the engineering surface that delivers it.

## 2. Actors

- **Primary:** Any user
- **Secondary:** Status-banner service, SUPERVISORs posting banners via UC-G42 §6.5

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes | Visible with an "admin-facing only" flag if the banner is targeted at operators |
| Client Web | yes | |
| Client iOS | yes | |
| Client Android | yes | |

## 4. Preconditions

- Status-banner service has published at least one active banner OR the service is reachable (absence-of-banner is also valid).

## 5. Postconditions

- User has seen the banner (or not, if none active).
- No audit events for banner rendering — this is low-signal.

## 6. Main flow (happy path)

1. On app bootstrap (before and after login), GUI fetches `GET /api/v1/public/status` (unauthenticated — same banner on login screen as on authenticated screens).
2. Response:
   ```json
   {
     "banners": [
       {
         "id": "...",
         "severity": "INFO | WARNING | CRITICAL",
         "titleKey": "banner.maintenance.titleKey",
         "bodyKey": "banner.maintenance.bodyKey",
         "params": { "startAt": "...", "endAt": "..." },
         "audiences": ["CUSTOMER" | "ADMIN" | "BOTH"],
         "dismissible": true|false,
         "validFrom": "...",
         "validUntil": "..."
       }, ...
     ]
   }
   ```
3. GUI filters by audience: customer GUIs show `CUSTOMER|BOTH`; admin GUI shows `ADMIN|BOTH`.
4. GUI renders a banner per severity:
   - INFO: blue, icon = info, collapsible.
   - WARNING: amber, icon = warning, non-dismissible for the duration.
   - CRITICAL: red, icon = exclamation, sticky at top of every screen, may interrupt modal UX briefly.
5. Banner copy uses i18n keys (`GUI-I18N-02`) with `params` interpolated (e.g., start/end times formatted to locale).
6. Banners subscribe to SSE `GET /api/v1/public/status/stream` for real-time updates; a banner can be published, updated, or resolved mid-session.

## 7. Alternative flows

### AF-01 — No active banners

1. GUI renders nothing; the chrome is unchanged.

### AF-02 — Multiple active banners

1. Stack in order of severity (CRITICAL first); max 2 visible, a "+N flere / +N more" chip expands the rest in a dropdown.

### AF-03 — User dismisses a dismissible banner

1. Dismissal persists in localStorage / UserDefaults / SharedPreferences under `banner.dismissed.<id>`.
2. The banner does not reappear for that user on that device until a new version (banner ID change) is published.

### AF-04 — Banner has a "more info" link

1. Link opens an in-app help view (authenticated) or external support page (not authenticated).

### AF-05 — Banner targets a specific feature (e.g., "Instant payments degraded")

1. GUI may *additionally* show an inline warning on the feature's screen (payment form) — backend provides a `featureScopes` array; GUI surfaces per scope.

### AF-06 — Status service unreachable

1. No banner shown; cached banner (if SSE was connected earlier and service went offline) is kept until its `validUntil`.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 5xx on fetch | Response | No banner; no error shown (absence-of-banner is a valid state) |
| SSE drop | EventSource error | Exponential backoff reconnect; poll `GET /api/v1/public/status` once on reconnect |
| Banner body contains malformed i18n key | i18n | Fall back to English; log |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F80` alignment, `ADM-F84`, `GUI-OPS-05`, `GUI-I18N-02`
- `system_requirements.md` — DORA Art. 12 incident communication

## 10. API operations involved

- `GET /api/v1/public/status` (no auth)
- `GET /api/v1/public/status/stream` (SSE)

## 11. Tests realising this use case

| Layer | Admin Web | Client Web | iOS | Android |
|-------|-----------|-----------|-----|---------|
| Unit | `.../status/__tests__/banner.test.tsx` | (same) | `.../Tests/StatusBannerTests.swift` | `.../status/StatusBannerTest.kt` |
| Component | `.../status/__tests__/banner-stack.test.tsx` | (same) | `.../Tests/BannerStackViewTests.swift` | `.../BannerStackTest.kt` |
| Contract | `.../public-status.pact.test.ts` | (same) | (same) | (same) |
| E2E | `admin-web/e2e/banner-visible.spec.ts` | `client-web/e2e/banner-visible.spec.ts`, `banner-sse-update.spec.ts`, `banner-dismiss-persistence.spec.ts` | `.../UITests/BannerUITests.swift` | `.../androidTest/.../BannerE2ETest.kt` |
| Cross-platform consistency | n/a | `cross-platform-tests/scenarios/banner_info.json`, `banner_critical.json`, `banner_multi.json` | (same) | (same) |

## 12. UX notes

- Banner appears under the top safe-area on mobile, above the status bar on web. Content below is not pushed; banner overlays with a subtle drop shadow.
- CRITICAL banners include a brief haptic on mobile on first appearance only.
- The banner's "X" (for dismissible) is a 44-pt/48-dp target and has an `aria-label` of the title for screen readers.

## 13. Open questions

- Whether banners should be language-aware at the source (a single banner with multiple locale bodies) or reuse the i18n catalogue. *Decision: i18n keys — catalogue is already the single source of truth; banners supply keys + params.*
