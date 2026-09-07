# UC-G27 — Mobile Cold-Start Biometric Unlock

*Owner: Frontend / Mobile / Security*
*Status: DRAFT*
*Implementation status: Web n/a | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

When the customer opens the iOS / Android app from a cold start (app was killed or the phone was rebooted), and a refresh token exists in secure storage, the app requires biometric unlock (Face ID / Touch ID / BiometricPrompt) before the refresh token is released and the session resumed. If biometric is unavailable or the user opts out, a 6-digit app PIN is the fallback. Failed attempts are rate-limited locally and globally.

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** iOS Keychain / Android Keystore, `LocalAuthentication` (iOS) / `BiometricPrompt` (Android), identity service (token refresh)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | |
| Client Web | no | Web re-auth is handled in UC-G52 |
| Client iOS | yes | Face ID or Touch ID via `LAContext` with `biometryAny`; Keychain ACL `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` |
| Client Android | yes | `BiometricPrompt`; `EncryptedSharedPreferences` + `setUserAuthenticationRequired(true)` |

## 4. Preconditions

- App was previously logged in with `biometric-unlock` enabled in settings (default on).
- Refresh token exists in secure storage.
- Device has at least one biometric factor enrolled OR an app-PIN set.

## 5. Postconditions

- On success: access token + refresh token (rotated) in memory/storage; customer lands on the Accounts screen (UC-G02).
- On failure after retry-budget exceeded: refresh token is invalidated server-side, customer returns to the full login flow (UC-G01).
- Audit event `auth.biometric-unlock.success` or `.failure` (local-device metadata hashed; never raw biometric data).

## 6. Main flow (happy path)

1. App launches. Pre-UI bootstrap checks secure storage for a refresh token and the `biometric-unlock-enabled` flag.
2. If present, the splash immediately transitions to the **unlock screen**:
   - Bank logo
   - "Lås opp med Face ID" / "Unlock with Face ID" prompt (or Touch ID / BiometricPrompt equivalent)
   - Fallback CTA "Bruk PIN / Use PIN"
   - Secondary CTA "Logg inn med BankID / Sign in with BankID" (triggers UC-G01)
3. iOS: `LAContext.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics)`; Android: `BiometricPrompt.authenticate(promptInfo)`.
4. On success, Keychain / EncryptedSharedPreferences release the refresh token.
5. App calls `POST /oauth2/token?grant_type=refresh_token` (UC-G01 AF-01).
6. Token exchange succeeds; new refresh token written; app navigates to UC-G02.

## 7. Alternative flows

### AF-01 — Biometric enrolment changed since last session (new fingerprint added, Face ID re-enrolled)

1. iOS: Keychain releases only if the ACL `kSecAccessControlBiometryCurrentSet` matches. If changed, access denied → AF-02.
2. Android: EncryptedSharedPreferences is invalidated when the biometric set changes.
3. App treats this as "biometric-no-longer-trusted": clears the refresh token, forces full login (UC-G01).
4. This is a security feature — new fingerprint cannot inherit access.

### AF-02 — Biometric scan fails (N times)

1. iOS `LAContext` surfaces failure at N=3; Android `BiometricPrompt` fires `onAuthenticationError` after platform retry budget.
2. App disables biometric and shows PIN prompt. Copy: "Biometrien virker ikke — bruk PIN eller logg inn med BankID."

### AF-03 — PIN fails (3 attempts)

1. App deletes the refresh token + PIN; forces UC-G01 full login.
2. A local cooldown (5 min) prevents brute force retries via re-launch.

### AF-04 — Device has no enrolled biometric

1. On first launch after install, app doesn't offer biometric toggle; PIN is the only option.

### AF-05 — User skips unlock ("Logg inn på nytt / Re-login")

1. Same as AF-02 outcome: treated as a voluntary full login — refresh token removed.

### AF-06 — Device has been rooted / jailbroken (detected post-unlock) — Phase 1 FM.6

1. Jailbreak/root detection runs on every app foreground; result is sent to the backend as header `X-Device-Integrity: rooted` (or `clean`) on every API request.
2. The app always shows a warning banner on detection ("Telefonen din ser ut til å være modifisert — vær oppmerksom.").
3. Policy enforcement (deny high-value operations; allow low-value with warning) happens server-side in A4's SCA-exemption logic. The client does not implement the threshold — the backend is the single point of truth.
4. When backend denies, customer sees the operation-specific rejection with support reference. Customer is not misled into thinking a borderline amount will work "next time".

### AF-07 — Cold start with no refresh token

1. Unlock screen is skipped; full login (UC-G01) shown.

### AF-08 — Cold start after 30 days inactivity

1. Refresh token has 30-day rolling TTL. If elapsed, token refresh fails; force UC-G01.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Keychain/Keystore corrupted | OS error on retrieval | Clear state, force full login |
| Network offline during refresh | Token exchange fails | Show "Offline — du kan bla gjennom lagrede kontoer uten tilgang til overføringer" and proceed with cached data (UC-G28) |
| Identity service 5xx on refresh | Token exchange fails | Retry 3× with backoff; if still failing, offline mode (UC-G28) |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F04`, `GUI-SEC-06`, `GUI-SEC-07`, `GUI-SEC-11`, `GUI-SEC-12`
- OWASP MASVS L1 V2.1, V4.7, V8.3

## 10. API operations involved

- `POST /oauth2/token?grant_type=refresh_token` (`identity.yaml`)

## 11. Tests realising this use case

| Layer | iOS | Android |
|-------|-----|---------|
| Unit | `.../Tests/BiometricUnlockTests.swift` (LAContext stubbed) | `.../biometric/BiometricUnlockTest.kt` (BiometricPrompt stubbed) |
| Component | `.../Tests/UnlockViewTests.swift` | `.../UnlockScreenTest.kt` |
| Integration | `.../Tests/KeychainIntegrationTests.swift` (real keychain, simulator) | `.../EncryptedPrefsIntegrationTest.kt` (real keystore, emulator) |
| E2E | `.../UITests/UnlockUITests.swift` — cold launch with fake biometric | `.../androidTest/.../UnlockE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/mobile_cold_start_unlock.json` (iOS + Android only) | (same) |

## 12. UX notes

- First-time setup of biometric unlock happens in UC-G01 step 8 (post-login, opt-in dialog); copy covers privacy ("Biometrien lagres aldri i banken — kun på din telefon").
- The PIN fallback button is deliberately small compared to the biometric CTA — we want the happy path to stay front-and-centre but the fallback always reachable.
- No visual shake / error animation on biometric failure beyond the system default — gratuitous feedback is a UX anti-pattern.
- **Screen-capture protection (Phase 1 FM.1 / GUI-SEC-05a):** the unlock screen itself, the PIN fallback screen, and the post-unlock transition screens set `FLAG_SECURE` (Android) throughout their lifetime; iOS installs the pre-snapshot obscuring overlay on background so the app-switcher thumbnail shows the bank wordmark, not the unlock state.

## 13. Open questions

- Whether to offer passkeys (WebAuthn) on mobile as a third factor. *Defer.*
- Whether high-value payment initiation should always re-prompt biometric (belt-and-suspenders over the 15-min session). *Current design: yes for amounts > 10 000 NOK. Confirm in Phase-1.*
