# UC-G21 — Notification Preferences

*Owner: Frontend / Notifications*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

The customer configures which categories of notification they receive on which channels. Inbox is always on (as the primary record); email, SMS, and push are independently toggleable per category. Defaults are set conservatively (security-critical on, marketing off).

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** Notification service (preferences store)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | |
| Client Web | yes | Full matrix |
| Client iOS | yes | Full matrix, uses iOS `UNUserNotificationCenter` for the push-authorisation prompt |
| Client Android | yes | Full matrix, uses Android POST_NOTIFICATIONS permission prompt |

## 4. Preconditions

- Customer is authenticated with `notifications:manage` (implicit for account holders).

## 5. Postconditions

- Preferences record in the notifications service updated.
- Subsequent notifications follow the updated preferences.
- Audit event `preferences.notifications.updated` with the diff.

## 6. Main flow (happy path)

1. Customer navigates to **Innstillinger / Settings** → **Varsler / Notifications**.
2. GUI fetches `GET /api/v1/customer/notifications/preferences`. Response:
   ```json
   {
     "channels": {
       "inbox": { "enabled": true, "locked": true },
       "email": { "enabled": true, "address": "o*****@example.com" },
       "sms":   { "enabled": false, "phone": "+47 ••• •• •34" },
       "push":  { "enabled": true, "devices": [{"name":"iPhone 15","id":"..."}] }
     },
     "categories": {
       "PAYMENT_STATUS":   { "inbox": true, "email": true, "sms": false, "push": true },
       "FRAUD_HOLD":       { "inbox": true, "email": true, "sms": true,  "push": true },
       "SANCTIONS_BLOCK":  { "inbox": true, "email": true, "sms": false, "push": false },
       "R2P_INCOMING":     { "inbox": true, "email": false, "sms": false, "push": true },
       "CONSENT_EVENTS":   { "inbox": true, "email": false, "sms": false, "push": false },
       "MANDATE_PRE_DEBIT":{ "inbox": true, "email": true, "sms": false, "push": true },
       "SECURITY_EVENTS":  { "inbox": true, "email": true, "sms": true,  "push": true, "locked": true },
       "MARKETING":        { "inbox": false, "email": false, "sms": false, "push": false }
     }
   }
   ```
3. GUI renders:
   - **Channels** section: toggle per channel + the masked endpoint (email, phone); "Verifiser / Verify" action if unverified; "Enheter / Devices" expansion for push.
   - **Categories** matrix: rows = categories, columns = channels; each cell a checkbox. Locked cells (inbox always, security events per regulation) are greyed out with a tooltip explaining the lock.
4. Customer toggles a cell → optimistic UI update + debounced `PUT /api/v1/customer/notifications/preferences` with the full matrix (simpler than PATCH per cell).
5. Backend persists; sends a quiet inbox confirmation "Innstillingene dine er lagret".
6. Customer taps **Ferdig / Done** → navigates back.

## 7. Alternative flows

### AF-01 — Enable email but address unverified

1. Toast: "Vi har sendt en bekreftelseslenke til o****@example.com. Klikk lenken for å bekrefte." Email column stays greyed until verification completes; UC-G22 covers address change + verify.

### AF-02 — Enable SMS but phone unverified

1. Similar flow using an OTP SMS.

### AF-03 — Enable push but OS permission denied

1. GUI attempts to re-prompt (iOS / Android allow re-prompting once after a cooldown; else GUI shows a "Open Settings" button).
2. If permission remains denied, the push column is visually disabled with an explainer.

### AF-04 — Customer wants to disable SECURITY_EVENTS email

1. Blocked by the `locked` flag. Tooltip: "Sikkerhetsvarsler kan ikke skrus av — dette er et lovkrav for å kunne varsle deg om misbruk av kontoen din."

### AF-05 — Customer removes a push device

1. Per-device delete button; DELETE to `/api/v1/customer/notifications/devices/{id}`. Device is unregistered from APNs/FCM; inbox receives a confirmation.

### AF-06 — Customer is visually impaired and uses screen reader

1. The matrix is announced as a table with row/column headers; each cell toggle announces both "turning on X notifications for Y" and the resulting state.

### AF-07 — Quiet hours setting (future)

1. *Out of MVP.*

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| PUT fails 5xx | Response | Revert optimistic UI; toast "Kunne ikke lagre — prøv igjen" |
| Concurrent edit from another device | 409 version conflict | Refetch current state; merge locally; re-submit once |
| Push token registration fails | Device POST | Show inline error on devices panel; customer can retry |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F81`, `GUI-PRV-01` (no third-party analytics in adapters)
- `system_requirements.md` — notification-service endpoints (gui_requirements §6.6)
- `PROJECT_DECISIONS.md` — D7

## 10. API operations involved

- `GET    /api/v1/customer/notifications/preferences`
- `PUT    /api/v1/customer/notifications/preferences`
- `POST   /api/v1/customer/notifications/devices` — register push
- `DELETE /api/v1/customer/notifications/devices/{id}`
- `POST   /api/v1/customer/notifications/email/verify` — trigger verification email

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `.../prefs/__tests__/matrix.test.tsx`, `persist.test.ts` | `.../Tests/PrefsMatrixTests.swift` | `.../prefs/PrefsMatrixTest.kt` |
| Component | `.../prefs/__tests__/preferences-screen.test.tsx` | `.../Tests/PreferencesViewTests.swift` | `.../PreferencesScreenTest.kt` |
| Contract | `.../prefs.pact.test.ts` | `.../PrefsPactTests.swift` | `.../PrefsPactTest.kt` |
| E2E | `client-web/e2e/notification-preferences.spec.ts` | `.../UITests/NotificationPrefsUITests.swift` | `.../androidTest/.../NotificationPrefsE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/prefs_toggle_email.json`, `prefs_remove_device.json`, `prefs_security_locked.json` | (same) | (same) |

## 12. UX notes

- The matrix layout is compact on mobile (category rows expand to show channel checkboxes; web shows the full grid).
- Locked cells use a padlock icon plus the tooltip; they are not visually "disabled" in a way that suggests the setting is a bug.
- The consent-history entry (UC-G26) records each preference flip for GDPR accountability.

## 13. Open questions

- Whether marketing emails should be allowed at all in the MVP. *Decision: the category exists but defaults to all-off; enabling it requires explicit opt-in in UC-G26's marketing-consent flow (future).*
