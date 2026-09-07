# Phase G — Mobile (Android + iOS) — roadmap

Per `space/E2E_TESTING_GUIDE.md` §G: native mobile clients are deferred
to a roadmap doc rather than built as part of the test program. Building
the apps from source is a separate project, not a test deliverable.

This document captures the host blockers, the contract surface that's
already in place, and the order of operations for whoever picks Phase G
up cold.

## What exists today (2026-04-29)

- `implementation/android-app/` — standalone Gradle project. Kotlin 2.0 +
  Jetpack Compose + Material 3 + AGP 8.7.2. Networking via OkHttp +
  kotlinx.serialization. EncryptedSharedPreferences for tokens. BankID
  via Custom Tabs (ASWebAuthenticationSession-equivalent on Android).
  Room offline cache (UC-G28). Biometric unlock (UC-G27). Deep-link
  router (UC-G29). DlHashTest covers the canonical-JSON algorithm
  byte-identically with the JVM service implementation.
- `implementation/ios-app/` — Swift 5.9 + SwiftUI + Combine, iOS 17 target.
  Package.swift describes layout. URLSession-based ApiClient. Keychain-
  backed SessionStore. ASWebAuthenticationSession for BankID. CryptoKit-
  backed DlHash + ScaClient. LocalAuthentication for biometric unlock.
  JrpayTests/DlHashTests covers canonical-JSON parity.

These are real, complete codebases — not stubs. They cannot be built or
tested on the current Linux dev host (no Android SDK, Xcode is macOS-only).

## Host blockers

### Android

- **Android SDK + AGP 8.7.2 + Java 21**. Standard install via
  `sdkmanager`. The Gradle wrapper in `android-app/` will pull AGP and
  the Kotlin tooling on first run.
- **Emulator** — at least one AVD running an API ≥ 31 image. KVM
  acceleration recommended; this host has KVM available
  (`/dev/kvm` present) but the runtime memory budget is tight (7.4 GB
  total, ~2 GB free with the JRPay docker stack up). Practical options:
  shut the docker stack down before running the emulator, or run on a
  separate machine.
- Gradle build target: `./gradlew :app:assembleDebug` then
  `./gradlew :app:connectedDebugAndroidTest` for the espresso/compose
  UI tests. Unit tests via `./gradlew :app:testDebugUnitTest`.

### iOS

- Xcode is macOS-only. **Cannot build on this host.** Pick up on a Mac
  with Xcode 15+.
- Once on a Mac: `xed implementation/ios-app/Package.swift`, then
  Cmd+U for the test scheme. UI tests need an iOS 17 simulator.

## What the mobile clients consume from the backend

All three client surfaces (web, Android, iOS) consume the **same OpenAPI
specs** (`api-specs/customer-*.yaml`). Server-side errors surface as the
same RFC 9457 problem URNs across all surfaces:

- `urn:problem:vop-mismatch`
- `urn:problem:fraud-hold`
- `urn:problem:sanctions-block`
- `urn:problem:mandate-cap-exceeded`
- `urn:problem:account-frozen`
- `urn:problem:domain-error`
- `urn:problem:dynamic-linking-mismatch`
- `urn:problem:validation` (added by F7 in this session)
- `urn:problem:missing-parameter` (added by F7 in this session)

A Phase G test program should re-use these contracts; do not invent
mobile-specific error shapes.

The dynamic-linking hash (`DlHash`) is implemented identically on web
(TypeScript), Android (Kotlin), iOS (Swift), and the JVM services. Any
drift fails server-side SCA validation with `urn:problem:dynamic-linking-mismatch`.
There's a JVM-side test asserting parity for representative payloads
across all four customer classes (`payment-initiation`, `mandate-action`,
`consent-grant`, `gdpr-action`).

## Order of operations for Phase G

1. **Pick a host** with the SDK installed (Android dev box for Android,
   Mac for iOS). Shut down the JRPay docker stack on this host first
   if running both side-by-side.
2. **Stand up the backend stack** on a remote (or the same) host;
   point the mobile build at it via the existing `BASE_URL` config
   (Android: `local.properties` → `JRPAY_API_BASE`; iOS: `Config.swift`).
3. **Build + install + smoke-launch** — `connectedDebugAndroidTest`
   for Android, simulator XCUITest for iOS. Confirm the login screen
   renders.
4. **Drive the simplest UC end-to-end** — same approach as Phase B in
   this suite: open the simplest known-good flow (login → dashboard →
   accounts list) and assert it lands on real data, not a fixture.
5. **Add cross-client invariants** — for the parity-critical paths
   (DlHash, payment-initiation, consent-grant, mandate-action), assert
   that the mobile client produces the same canonical-JSON bytes the
   web client produces for the same inputs. Drift here is the highest-
   yield bug.
6. **Robustness sweep** — empty state, pull-to-refresh, network
   flakiness, biometric-unlock edge cases, deep-link entry, locale
   switch (`nb-NO` ↔ `en-US`).
7. **Hand-off** — same pattern as this E2E package: a `PHASE_G_RUNBOOK.md`
   that gets a fresh engineer from `git clone` to green.

## Already-in-place mobile deliverables

The web suite's mobile-viewport spec (`robustness-refresh-and-mobile.spec.ts`)
covers "the page is broken on phone widths" for ~80% of what "test it on
mobile" actually means in practice. Native UI testing would be additive,
not a substitute.

## Estimated effort

A focused engineer on a properly-equipped host: ~3 days for Android
(SDK setup + first connected smoke + 5–6 cross-client invariants +
hand-off doc). ~3 days for iOS (Xcode + first XCUITest + parity specs +
hand-off doc). The DlHash parity test alone is the highest-yield first
target because it's deterministic and asserts the contract that matters
most for SCA correctness.
