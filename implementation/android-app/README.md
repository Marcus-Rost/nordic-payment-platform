# JRPay Android — customer mobile client

Native Android (Kotlin + Jetpack Compose + Material 3) customer client for
JRPay. Phase 4B of the GUI extension; feature-parity with the Web client
(UC-G01…UC-G29).

## Stack

| Concern              | Choice                                     |
| -------------------- | ------------------------------------------ |
| Language             | Kotlin 2.0.21                              |
| UI                   | Jetpack Compose + Material 3               |
| Build                | Gradle 8.x + AGP 8.7.2                     |
| Min SDK / Target SDK | 28 / 34 (Java 17 toolchain)                |
| Networking           | OkHttp 4 + kotlinx-serialization-json      |
| Auth                 | OAuth2 PKCE + Custom Tabs (BankID upstream)|
| Token storage        | EncryptedSharedPreferences (AES-256 GCM)   |
| SCA                  | Dynamic-linking (RFC 8785 + SHA-256)       |
| Biometric unlock     | androidx.biometric (UC-G27)                |
| Offline cache        | Room (UC-G28)                              |
| Deep links           | androidx.navigation deep-link DSL (UC-G29) |
| Tests                | JUnit + Kotlin coroutines test             |

## Layout

```
android-app/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/jrpay/client/
│       │   ├── JrpayApplication.kt        # Manual DI graph
│       │   ├── MainActivity.kt            # Single-activity Compose host
│       │   ├── api/                       # ApiClient + Problem
│       │   ├── auth/                      # PKCE, OAuth, SecureTokenStore, SessionStore, Biometric
│       │   ├── sca/                       # DlHash + ScaClient (4 input classes)
│       │   ├── data/                      # OfflineCache (Room)
│       │   ├── ui/
│       │   │   ├── JrpayApp.kt            # Bottom-nav + nav graph
│       │   │   ├── theme/                 # JrpayTheme
│       │   │   └── screens/               # Per-feature Compose screens
│       │   └── util/DeepLinkRouter.kt
│       └── res/
│           ├── values/strings.xml         # English
│           ├── values-nb/strings.xml      # Norwegian (default locale)
│           └── values/themes.xml
├── build.gradle.kts                       # Top-level: AGP + Kotlin + Compose + serialization
├── settings.gradle.kts
└── gradle.properties
```

## Use-case coverage

| Use case          | File                                                             |
| ----------------- | ---------------------------------------------------------------- |
| UC-G01 sign-in    | `LoginScreen` + `OAuthClient` + Custom Tabs                      |
| UC-G02 accounts   | `dashboard/DashboardScreen`                                      |
| UC-G03 transactions | (next iteration — accounts/AccountDetail)                      |
| UC-G05 instant pay  | `payments/PaymentsScreen` (VoP + dynamic-linking SCA)          |
| UC-G07 scheduled  | (mode toggle in PaymentsScreen)                                  |
| UC-G08/G09 mandates| `mandates/MandatesScreen`                                       |
| UC-G11–G14 R2P    | `r2p/R2pInboxScreen`                                             |
| UC-G16 beneficiaries| `beneficiaries/BeneficiariesScreen`                            |
| UC-G17/G18 consents| `consents/ConsentsScreen`                                       |
| UC-G21 notifications| `notifications/NotificationsScreen`                            |
| UC-G22 profile    | `profile/ProfileScreen`                                          |
| UC-G24/G25 GDPR   | `privacy/PrivacyScreen`                                          |
| UC-G27 biometric  | `auth/BiometricUnlock`                                           |
| UC-G28 offline    | `data/OfflineCache` (Room snapshot for accounts list)            |
| UC-G29 deep links | `util/DeepLinkRouter` + AndroidManifest intent-filters           |
| UC-G50 logout     | `MoreScreen` + `SessionStore.clear()`                            |
| UC-G51 SCA        | `sca/DlHash` + `sca/ScaClient`                                   |

## Build

The Android project is **standalone** — it does NOT participate in the JVM
backend's Gradle build tree (the AGP introduces requirements that would
pollute the Spring services). Open `implementation/android-app/` in Android
Studio, or run from the CLI with the Android SDK installed:

```bash
cd implementation/android-app
./gradlew :app:assembleDebug         # debug APK → app/build/outputs/apk/debug/
./gradlew :app:test                  # JVM unit tests (DlHashTest etc.)
./gradlew :app:lint                  # AGP lint
```

The Gradle wrapper has not been committed (intentional — Android Studio
generates it on first sync). Run `gradle wrapper --gradle-version 8.10`
inside the project to bootstrap, or open in Android Studio Hedgehog or
later.

### Configuration

Build-time configuration is exposed as `BuildConfig` fields:

| Field                  | Default (debug)             | Use                      |
| ---------------------- | --------------------------- | ------------------------ |
| `IDENTITY_BASE_URL`    | `http://10.0.2.2:8097`      | OAuth2 + SCA             |
| `API_BASE_URL`         | `http://10.0.2.2`           | Customer REST surface    |

`10.0.2.2` is the Android emulator's host loopback. For physical devices
on the same LAN, set these to the LAN IP of the machine running compose.
For staging / production, override per-flavor.

## Security notes

- **Tokens**: encrypted at rest by `SecureTokenStore`
  (`EncryptedSharedPreferences` with AES-256 SIV / AES-256 GCM, master key in
  AndroidKeyStore + StrongBox where available).
- **PII**: the offline cache only stores already-redacted server responses
  (masked IBANs, masked holder names). Tokens are NEVER cached in Room.
- **SCA**: every payment / mandate / R2P-accept / GDPR-erasure runs the
  dynamic-linking flow. The Kotlin `DlHash` algorithm is byte-identical to
  the Java services and the web clients; verified by `DlHashTest`.
- **OAuth**: BankID redirect through Custom Tabs (system browser sandbox);
  the deep-link callback uses an Android App Link with a custom scheme
  registered in the manifest.
- **Backups**: `data_extraction_rules.xml` excludes the encrypted-prefs file
  and the Room cache from cloud-backup and device-transfer.

## Outstanding follow-ups

- Account-detail screen with transaction list + filters (currently the
  dashboard surfaces summary only).
- FCM (Firebase Cloud Messaging) wiring for UC-G29 push notifications;
  current scaffolding is the deep-link routing, but the device-token
  registration call to the notifications service is TODO.
- Camera-based QR scan for UC-G15 (CameraX + ML Kit Barcode Scanning).
- Compose-test instrumentation suite — only the JVM `DlHashTest` is wired
  in; UI smoke tests should follow.
