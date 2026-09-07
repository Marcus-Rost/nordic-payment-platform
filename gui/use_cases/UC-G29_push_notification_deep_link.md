# UC-G29 — Push Notification Deep Link to Target

*Owner: Frontend / Mobile / Notifications*
*Status: DRAFT*
*Implementation status: Web n/a | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

A server-triggered push notification (APNs for iOS, FCM for Android) arrives on the customer's device. Tapping it launches the app and deep-links to the relevant destination — a specific R2P, a payment under review, a consent expiry warning, etc. Deep links are structurally validated (allowlist of target path patterns) and always land on an authenticated screen.

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** Notification service (composes the push payload), APNs / FCM (delivery), the deep-link router in the mobile app

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | |
| Client Web | no | Web has no equivalent; inbox + email cover the web case |
| Client iOS | yes | APNs + Universal Links |
| Client Android | yes | FCM + App Links |

## 4. Preconditions

- Customer has registered a push token (from UC-G21 device registration).
- Customer has enabled push for the category of the notification (UC-G21 preferences).
- The app is installed.

## 5. Postconditions

- The app is foregrounded and navigated to the deep-link target.
- If biometric unlock is required (cold start or locked-on-background), UC-G27 runs first; after unlock, navigation continues to the target.
- Notification service records delivery + open events (for operational metrics; no per-customer behavioural profiling).

## 6. Main flow (happy path)

1. Notification service composes a push payload:
   ```json
   {
     "aps": { "alert": {"title":"...","body":"..."}, "sound":"default", "badge":1 },
     "deepLink": "jrpay://payment/{paymentId}",
     "category": "PAYMENT_STATUS",
     "reference": "<notificationId>"
   }
   ```
   (Android / FCM uses equivalent fields in the `data` payload.)
2. APNs / FCM delivers. Customer taps the notification.
3. OS launches the app with the push payload. App's launch handler:
   - If app was not running, completes bootstrap (UC-G27 unlock if token present).
   - Parses the `deepLink` URI against the allowlist: valid schemes (`jrpay://`), allowed paths (`/payment/:id`, `/r2p/:id`, `/consent/:id`, `/gdpr/:requestId`, `/inbox`, `/settings/notifications`).
   - Rejects any unknown URI and shows the inbox instead.
4. Router pushes the target screen onto the nav stack with the parsed params.
5. Screen fetches fresh state (never relies on the push-payload fields for authoritative data — only as navigation hint).
6. Inbox item corresponding to `reference` is marked as read.

## 7. Alternative flows

### AF-01 — App is foregrounded when notification arrives

1. iOS: foreground presentation configured to show in-app banner + sound (optional).
2. Tap → same router behaviour.

### AF-02 — Customer is on the target screen when notification arrives

1. Router detects duplicate and refreshes data silently; no navigation stack change.

### AF-03 — Notification arrives after related record is deleted (race)

1. Target screen fetch returns 404; GUI shows "Denne hendelsen er ikke lenger tilgjengelig" and routes back to inbox.

### AF-04 — Deep link points to an admin-only path (e.g., a cross-environment mistake)

1. Router rejects; customer lands on inbox; service-ops alert fired.

### AF-05 — User has multiple devices; only one opens the notification

1. APNs / FCM deliver to all registered devices. Opening on one does not dismiss on others — each is separate; inbox read-state synchronises via SSE (the opened device marks read; others receive the SSE event and dismiss).

### AF-06 — Notification payload tampered (signature mismatch, MASVS defence)

1. On iOS we don't sign the APNs payload (Apple signs delivery, not content) — instead the app validates that the `reference` ID matches an inbox record fetched server-side before trusting the deep-link.
2. If no such inbox record exists, app refuses the deep link and shows inbox.

### AF-07 — OS notification permission revoked

1. Pushes stop arriving. Inbox still functions. UC-G21 reflects the OS state in the devices panel.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Push token invalid (device reinstalled) | APNs/FCM feedback | Notification service marks device expired; no delivery to this device |
| Network during post-tap data fetch | Target screen fetch | UC-G28 offline path or retry |
| Biometric unlock fails at cold start | UC-G27 AF-02 | Customer routed to UC-G01; after login, app honours the pending deep link |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F82`, `GUI-SEC-05` (cert pinning), `GUI-SEC-16` (build-time tamper)
- `system_requirements.md` — notification service
- `PROJECT_DECISIONS.md` — D7

## 10. API operations involved

- `POST /api/v1/customer/notifications/devices` — device registration (UC-G21)
- Target-screen fetches per destination (no unique endpoint for this UC)

## 11. Tests realising this use case

| Layer | iOS | Android |
|-------|-----|---------|
| Unit | `.../Tests/DeepLinkRouterTests.swift` (allowlist + param parsing) | `.../deeplink/DeepLinkRouterTest.kt` |
| Component | `.../Tests/PushHandlerTests.swift` | `.../PushHandlerTest.kt` |
| Integration | `.../Tests/NotificationPayloadIntegrationTests.swift` (fake APNs sandbox) | `.../NotificationPayloadIntegrationTest.kt` (FCM test lab) |
| E2E | `.../UITests/PushDeepLinkUITests.swift` (install fixture + fire push) | `.../androidTest/.../PushDeepLinkE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/push_payment_detail_deeplink.json`, `push_r2p_deeplink.json` | (same) |

## 12. UX notes

- Push copy is limited to 180 chars (APNs) / 250 chars (FCM); notification service truncates and adds "..." with the full copy available in-app.
- No PII in push copy for sensitive cases (sanctions-block — UC-G20 already generic); amounts and names used for routine categories.
- Badge counter matches inbox unread count; zeroed on inbox open.

## 13. Open questions

- Whether to support rich notifications (APNs Notification Service Extension for attaching receipts PDF). *Defer — requires an extension target and adds complexity; not MVP.*
