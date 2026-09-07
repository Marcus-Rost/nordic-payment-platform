# UC-G28 — Offline Read of Cached Account and Transaction Data

*Owner: Frontend / Mobile / Resilience*
*Status: DRAFT*
*Implementation status: Web n/a | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

When the mobile app has no network connection, the customer can still read their last-known account list and the last 30 days of transactions. A "Last updated" timestamp is prominent and all write affordances are disabled. Writes are never attempted offline — the customer must reconnect to initiate payments, change settings, or revoke consents.

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** Local cache (SQLite via CoreData/Room)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | |
| Client Web | no | Web has no offline story in MVP |
| Client iOS | yes | CoreData with encrypted SQLite (SQLCipher or NSFileProtectionComplete) |
| Client Android | yes | Room with SQLCipher |

## 4. Preconditions

- App was online at some point within the last 30 days.
- Biometric unlock has completed (UC-G27) — offline mode requires the customer to have proven local control.

## 5. Postconditions

- Customer has read cached data; no backend writes occurred.
- When the app comes back online, the cache is refreshed in the background on next foreground.

## 6. Main flow (happy path)

1. After UC-G27 unlock, token refresh fails with a network error OR subsequent API calls fail with timeout.
2. App detects offline (reachability API: iOS `NWPathMonitor`, Android `ConnectivityManager`).
3. App serves the cached accounts + transactions to the Accounts + Transaction screens.
4. A persistent "Offline-modus" / "Offline mode" banner at the top of every screen states the last-updated timestamp: "Oppdatert 12:34 — du kan ikke betale eller endre innstillinger nå."
5. Screens hide or disable: New payment, Edit profile, Revoke consent, Add beneficiary, etc. Tooltip on the greyed-out CTAs: "Trenger nett — koble til og prøv igjen."
6. When the network returns (detected passively), app runs a background refresh of accounts + last 30 days of transactions; banner auto-dismisses; any stale data is replaced.

## 7. Alternative flows

### AF-01 — Cache is older than 30 days

1. App refuses offline mode. Customer sees a splash: "Vi trenger nett for å vise oppdaterte data. Prøv igjen når du er online."

### AF-02 — Customer tries to tap a disabled action anyway

1. Toast/ bottom sheet repeats the offline restriction copy.

### AF-03 — Cache integrity check fails (e.g., DB migration error after an update)

1. App wipes the cache, forces full login.

### AF-04 — Cache contains PII; device is stolen

1. Local encryption + OS file-protection prevents access without biometric unlock. App-level re-encryption rotates keys on biometric change (per UC-G27 AF-01).

### AF-05 — Customer was mid-payment form when connection dropped

1. Form state preserved in memory until explicit cancel. On reconnect, VoP re-runs. If customer restarts app before reconnect, draft is discarded (we don't persist in-progress payments offline for security).

### AF-06 — Push notification arrives while offline

1. System-level delivery still works (APNs/FCM have their own store-and-forward). In-app inbox update happens only on next sync.

### AF-07 — Partial connectivity (DNS resolves but TLS handshake fails)

1. Treated as offline; same banner.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| DB corruption | SQLite error | Wipe cache; force login |
| Cache schema version mismatch after app update | Migration failure | Wipe cache; force online refresh |
| Clock skew — cached data shows future timestamp | Local clock check | Banner "Klokken på telefonen kan være feil — oppdater enhetsinnstillingene." |

## 9. Related requirements

- `gui_requirements.md` — `CLI-NF07`, `GUI-SEC-06`, `GUI-SEC-11`, `GUI-RES-01..03`
- `system_requirements.md` — resilience principle

## 10. API operations involved

- No offline call actually hits the network.
- On reconnect: `GET /api/v1/customer/accounts`, `GET /api/v1/customer/accounts/{id}/transactions?since=<last-sync>`

## 11. Tests realising this use case

| Layer | iOS | Android |
|-------|-----|---------|
| Unit | `.../Tests/CacheRepositoryTests.swift` (fake network) | `.../cache/CacheRepositoryTest.kt` |
| Integration | `.../Tests/OfflineReachabilityTests.swift` | `.../ReachabilityIntegrationTest.kt` |
| Component | `.../Tests/OfflineBannerViewTests.swift` | `.../OfflineBannerTest.kt` |
| E2E | `.../UITests/OfflineReadUITests.swift` (airplane-mode simulation) | `.../androidTest/.../OfflineReadE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/mobile_offline_read.json` | (same) |

## 12. UX notes

- The offline banner is coloured distinctly (amber) and appears under the top safe-area — unmissable without blocking interaction with the cached content.
- Disabled CTAs have visible, high-contrast tint change per `GUI-A11Y-03`; screen readers announce "deaktivert — trenger nett".
- The timestamp "Last updated" uses relative time when recent ("4 minutter siden"), absolute otherwise ("i går kl. 18:22").
- **Screen-capture protection (Phase 1 FM.1 / GUI-SEC-05a):** offline views display the same balances and transactions as online; `FLAG_SECURE` + iOS obscuring overlay applies identically, regardless of connectivity state.

## 13. Open questions

- Whether to cache receipts/PDFs locally for offline viewing. *Decision: no — PDFs are generated server-side and the customer can re-download on reconnect; reduces cache bloat.*
