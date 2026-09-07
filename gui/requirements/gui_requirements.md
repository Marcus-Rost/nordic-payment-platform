# JRPay GUI Extension — System Requirements

*Master requirements document for the four GUIs that complete the JRPay payment-processing platform.*
*Version: 1.0*
*Date: 2026-04-23*
*Companion to: `requirements/system_requirements.md` (backend), `gui/PROJECT_DECISIONS.md` (locked decisions).*

---

## 1. System Overview

This document defines the requirements for the user-facing surfaces of the JRPay platform. The backend (services A1–A6) is documented in `requirements/system_requirements.md`. This document covers four new artefacts and one supporting service:

| ID | Artefact | Audience | Platform |
|----|----------|----------|----------|
| **G1** | Admin Web Console | Bank back-office: case-workers (fraud), compliance officers (sanctions, GDPR), operations, supervisors | Web browser only |
| **G2** | Client Web Portal | Bank end-customers (retail account holders) | Web browser |
| **G3** | Client iOS App | Same end-customers | Native iOS (SwiftUI) |
| **G4** | Client Android App | Same end-customers | Native Android (Jetpack Compose) |
| **A7** | Identity Service | All four GUIs and TPP integrations (PSD2) | Backend (Spring Authorization Server) |

G2, G3 and G4 implement **identical user narratives**. Visual layouts may differ; the set of features, the flow shape per feature, and the copy strings (modulo platform conventions) must not. A user proficient in one variant must be able to switch to another with no re-training.

### 1.1 Architecture Overview

```
                     ┌────────────────────────────────────────────────────┐
                     │              jrpay-identity-service (A7)           │
                     │   OAuth2 Authorization Server + SCA orchestration  │
                     │   ScaProvider SPI: simulated BankID | real BankID  │
                     └─────────────────┬─────────────────────┬───────────┘
                                       │ JWT (RS256)         │
            ┌──────────────────────────┼─────────────────────┴───────┐
            │                          │                             │
            ▼                          ▼                             ▼
   ┌────────────────┐         ┌────────────────┐         ┌────────────────┐
   │  Admin Web (G1)│         │ Client Web (G2)│         │  iOS / Android │
   │  React+TS      │         │  React+TS      │         │  (G3 / G4)     │
   └───────┬────────┘         └────────┬───────┘         └────────┬───────┘
           │                           │                          │
           │ REST (HTTPS)              │                          │
           │ + SSE (status streaming)  │                          │ + Push (APNs/FCM)
           │                           │                          │
           ▼                           ▼                          ▼
   ┌──────────────────────────────────────────────────────────────────────┐
   │                        Backend services A1–A6                        │
   │   (REST surfaces extended; OpenAPI specs in implementation/api-specs)│
   └──────────────────────────────────────────────────────────────────────┘
```

### 1.2 Out of scope

The following are explicitly out of scope for this initiative (in addition to the items already out of scope per `requirements/system_requirements.md` Section 11):

- Customer Due Diligence (KYC) onboarding workflow — assumed to be performed by an upstream system; we display CDD risk rating only.
- Marketing / campaign UI.
- Investment / brokerage features (the platform is a payment platform, not a wealth platform).
- Cross-currency FX with live market rates — multi-currency payments are supported; FX uses a static rate table that can be swapped for a live feed later.
- Real BankID merchant integration (skeleton present, see PROJECT_DECISIONS D6).
- App store distribution (build artefacts produced in CI; publication is operational, not engineering).

---

## 2. Cross-Cutting Requirements (apply to G1–G4)

### 2.1 Technology Stack

Locked in `gui/PROJECT_DECISIONS.md` D1. Summary:

| Layer | Technology |
|-------|-----------|
| Web (G1, G2) | React 18 + TypeScript 5 + Vite + TanStack Query + TanStack Router + Tailwind CSS + Radix Primitives |
| iOS (G3) | SwiftUI + Swift 5.9 + Swift Concurrency, iOS 16+ |
| Android (G4) | Jetpack Compose + Kotlin 1.9 + Coroutines, API 29+ |
| Identity Service (A7) | Spring Authorization Server 1.x on Spring Boot 3.x |
| API contract | OpenAPI 3.1 (single source of truth in `implementation/api-specs/`) |
| Auth | OAuth2 Authorization Code + PKCE (clients), Resource Owner Password Credentials prohibited |
| Token format | JWT RS256, 15-min access TTL, refresh tokens device-bound |
| i18n | ICU MessageFormat catalogue, Norwegian Bokmål primary, English secondary |
| Test (web) | Vitest (unit), Testing Library (component), Playwright (E2E), MSW only for component tests, never for E2E |
| Test (iOS) | XCTest + ViewInspector |
| Test (Android) | JUnit 5 + Compose UI Test + Espresso for E2E |
| Mobile build | Fastlane (signing, beta distribution) |

### 2.2 Security Requirements

| ID | Requirement | Rationale |
|----|------------|-----------|
| GUI-SEC-01 | All GUIs must authenticate via the identity service (A7) using OAuth2 Authorization Code + PKCE. Implicit flow and Resource Owner Password flow are forbidden. | OWASP ASVS 4.0.3 V2.1.6, OAuth 2.1 best practice |
| GUI-SEC-02 | All HTTP traffic between any GUI and any backend must be HTTPS with TLS 1.3 (TLS 1.2 fallback acceptable). HTTP is permitted only on the loopback interface in local dev. | OWASP ASVS V9.1, PCI DSS v4.0 |
| GUI-SEC-03 | Web GUIs must enforce the security headers configured by the platform starter, plus a strict Content-Security-Policy pinned to the directive set in `ARCHITECTURE_DESIGN.md` §11.5 ("Full CSP"). Key properties: `default-src 'none'`, script-src whitelisted by nonce, `frame-ancestors 'none'`, `form-action 'self' https://identity.{env}`, `base-uri 'none'`, CSP-violation reporting via `report-to`. | OWASP ASVS V14.4, CSP best practice, Phase 1 FS.2 / FS.11 |
| GUI-SEC-04 | Web GUIs use bearer tokens (`Authorization: Bearer <access-token>`) as the authoritative credential for all API requests; cookies authenticate exactly one endpoint (`/oauth2/token`) and are `HttpOnly`, `Secure`, `SameSite=Strict`, path-scoped to `/oauth2/`. The cookie path-scope, combined with SameSite=Strict, plus server-side Origin-header validation on `/oauth2/token`, are the complete CSRF defence. No other endpoint accepts cookie credentials. See `ARCHITECTURE_DESIGN.md` §11.5. | OWASP ASVS V13.2, Phase 1 FS.1 |
| GUI-SEC-05 | Mobile apps must implement certificate pinning against the production identity service and backend API host (pin the public-key SPKI hash, not the leaf cert). Pin rotation procedure documented in `ARCHITECTURE_DESIGN.md` §6.3 (up to three simultaneous pins per host: current, next, emergency; annual rotation aligned with cert renewal; pin-audit script in CI). | OWASP MASVS L1 V5.6, Phase 1 FM.3 |
| GUI-SEC-05a | All screens that render account numbers, balances, transaction details, holder names, or SCA challenges set `FLAG_SECURE` (Android) throughout their lifecycle; on iOS, the app installs a pre-snapshot obscuring overlay in `applicationDidEnterBackground` and dismisses it in `applicationWillEnterForeground`. Applies to UC-G02, UC-G03, UC-G05, UC-G22, UC-G24, UC-G27, UC-G28, and every admin PII-display screen. | OWASP MASVS L1 STORAGE-1.7, Phase 1 FM.1 |
| GUI-SEC-06 | Mobile apps must store refresh tokens in the platform-secure store (iOS Keychain with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`; Android `EncryptedSharedPreferences` backed by Android Keystore with `setUserAuthenticationRequired(true)` for high-value scopes). Access tokens are kept in memory only. | OWASP MASVS L1 V2.1 |
| GUI-SEC-07 | Biometric unlock (Face ID / Touch ID / BiometricPrompt) is required to release the refresh token from secure storage on every cold start. PIN/password fallback supported. | OWASP MASVS V4.7 |
| GUI-SEC-08 | All GUIs must display the SCA dynamic-linking proof exactly as the backend computed it (amount + creditor name) on the SCA confirmation screen, in a UI region that cannot be obscured by other elements. The dynamic-linking hash is verified by the GUI before SCA challenge submission. | PSD2 RTS Art. 5 |
| GUI-SEC-09 | The actual creditor name must NEVER be displayed to the user when VoP returns `NO_MATCH` or `NOT_FOUND` (anti-enumeration). The web/mobile rendering layer must enforce this structurally — the API contract guarantees the field is absent, and the GUI must not pull the name from any cached source if absent. | EU IPR Art. 5c, structural enforcement (ARCHITECTURE_DECISIONS, A6) |
| GUI-SEC-10 | All GUIs must display a session-timeout warning at T-60s before idle expiry (default 15 min) and force re-authentication on expiry. Customer GUIs must blank the screen on background (mobile) and on lock (web after 5 min). | PSD2 RTS Art. 4(5), banking convention |
| GUI-SEC-11 | All GUIs must clear all PII (account numbers, balances, names) from the in-memory store on logout. Mobile apps must additionally clear the WebView cache, response cache, and any temporary files. | GDPR Art. 32, OWASP MASVS V2 |
| GUI-SEC-12 | Mobile apps detect jailbroken/rooted devices and include a `X-Device-Integrity: rooted` header on every API request. The backend enforces the policy (deny high-value operations, allow low-value with warning) so the threshold calculation stays on the server with A4's SCA exemption logic. The client always shows a warning banner on detection. | OWASP MASVS V8.3, banking convention, Phase 1 FM.6 |
| GUI-SEC-13 | All GUIs must rate-limit retry of SCA codes locally (server-side rate limit is the authoritative defence; local limit prevents accidental lockout). 3 attempts per session before forcing a 5-minute backoff (matches A4 server-side behaviour, UC-02 AF-02). | UC-02 AF-02, UX |
| GUI-SEC-14 | Admin GUI must enforce per-screen role check before fetching data, and must not render a page for a role that lacks the scope (defence-in-depth on top of server-side authorisation). 403 from the API must be treated as a deliberate access denial, not a network error. | OWASP API Security Top 10 API1:2023 (BOLA) |
| GUI-SEC-15 | Admin GUI views that show PII (full holder name, address, fødselsnummer) require an explicit "Reveal PII" interaction that is logged with `audit.pii-revealed` event including the operator's identity, the resource (account ID), and the field revealed. | GDPR Art. 32 access logging, Phase 1 FI.3 |
| GUI-SEC-16 | All GUIs must include a build-time tamper-detection token (web: subresource-integrity hashes; mobile: code signing with non-bypassable verification on launch). | OWASP MASVS V8.2 |
| GUI-SEC-17 | Logout must revoke the refresh token at the identity service (`POST /oauth2/revoke`). The GUI does not consider logout complete until the revoke call returns 2xx. | OAuth 2.1 §4 |
| GUI-SEC-18 | All four GUIs must pass `npm audit --audit-level=high` / equivalent SCA scan as a CI gate. Lockfiles committed. CI install uses `--frozen-lockfile` with integrity verification. | DORA Art. 8, OWASP ASVS V14.2 |
| GUI-SEC-19 | All runtime dependencies pinned to exact versions (no `^` / `~` for runtime deps). Updates by explicit PR; automated bots may propose but not merge. Pin a single package manager per platform: `pnpm@9.x` web, `CocoaPods` + `SwiftPM` iOS, Gradle version catalog Android. | Phase 1 FS.4 |
| GUI-SEC-20 | Build-time tools (Vite plugins, codegen tools, Gradle plugins, Fastlane plugins) listed in `gui/architecture/BUILD_DEPENDENCIES.md` with a risk tier. High-risk tools (outgoing network or transitive installation) reviewed on every update. CI runs under a least-privilege GitHub Actions OIDC identity, not a long-lived token. | Phase 1 FS.4 |
| GUI-SEC-21 | SBOM (CycloneDX) generated at every build. Released artefacts signed with Sigstore (cosign). A new release's SBOM is diffed vs. the previous; any new dependency requires explicit approval via PR. | Phase 1 FS.4 |

### 2.3 Accessibility Requirements

| ID | Requirement | Rationale |
|----|------------|-----------|
| GUI-A11Y-01 | Web GUIs must conform to WCAG 2.1 Level AA. Verified by automated audit (axe-core) plus manual screen-reader pass (NVDA + VoiceOver) on every screen prior to release. | EAA (EU Directive 2019/882), Norwegian likestillings- og diskrimineringsloven §17 |
| GUI-A11Y-02 | All form inputs must have programmatically associated labels. All error messages must be associated with their input via `aria-describedby` and announced on validation failure. | WCAG 1.3.1, 3.3.1 |
| GUI-A11Y-03 | Colour contrast: minimum 4.5:1 for normal text, 3:1 for large text and UI components. Status indicators (VoP traffic-lights, fraud-score badges) must not rely on colour alone. | WCAG 1.4.3, 1.4.11 |
| GUI-A11Y-04 | Full keyboard operability. Visible focus ring meeting 3:1 contrast. No keyboard traps. Tab order matches logical reading order. | WCAG 2.1.1, 2.1.2, 2.4.7 |
| GUI-A11Y-05 | Mobile apps must respect platform accessibility settings: dynamic type / font scaling, VoiceOver / TalkBack labels for all interactive elements, sufficient touch target size (44pt iOS, 48dp Android). | WCAG 2.5.5, Apple HIG, Material Design |
| GUI-A11Y-06 | Time-limited operations (SCA, VoP) must inform the user of the remaining time and offer an explicit extension request. | WCAG 2.2.1 |
| GUI-A11Y-07 | All currency amounts must be announced by screen readers in a meaningful form (e.g., "1.234,50 norske kroner", not "one two three four comma five oh"). | WCAG 1.3.1 |
| GUI-A11Y-08 | Every time-bound operation (SCA challenge, session warning, PII-reveal auto-remask, signed-URL TTL, SLA countdowns) documents its WCAG 2.2.1 compliance clause — either (c) warning + user extension, or (d) essential time limit per regulation. PII reveal (UC-G36) provides an extension button meeting clause (c). See `gui/architecture/COMPONENTS_COUNTDOWN.md`. | WCAG 2.2.1, Phase 1 FA.1 |
| GUI-A11Y-09 | Focus order, focus-visible indicators, focus-restoration on modal close, and keyboard-trap prevention are CI-enforced. Layer 2 component tests assert Tab cycle within modal + Escape closes + focus returns to trigger. Layer 4 E2E suite includes a keyboard-only scenario alongside each pointer-based scenario. | WCAG 2.1.2, 2.4.3, 2.4.7, Phase 1 FA.2 |
| GUI-A11Y-10 | All screens layout-test at 200% browser zoom (web) and Dynamic Type XXXL (iOS) / Accessibility L text size (Android), with Norwegian Bokmål strings (the longest localisation). Overflow of the SCA dynamic-linking region, VoP traffic-light card, or any required information is a release-blocker. CI snapshot-tests at max text size. | WCAG 1.4.4, 1.4.12, Phase 1 FA.6 |
| GUI-A11Y-11 | An accessibility statement is published at `/accessibility` on both web domains; content covers conformance level (WCAG 2.1 AA), any known non-conforming content with remediation plan, the feedback mechanism, and the enforcement procedure per EU EAA Directive 2019/882. | EU EAA, Phase 1 FA.12 |

### 2.4 Internationalisation Requirements

| ID | Requirement | Rationale |
|----|------------|-----------|
| GUI-I18N-01 | Norwegian Bokmål is the primary language and the default. English is a secondary supported language. Language preference is per-user, persisted server-side. | Finansavtaleloven §3-1, EU consumer protection |
| GUI-I18N-02 | All user-visible strings must come from the ICU MessageFormat catalogue. No hard-coded user-visible strings in code (lint rule: `no-untranslated-strings`). | i18n discipline |
| GUI-I18N-03 | Number, date, time and currency formatting must be locale-aware. NOK 1.234,50 in Norwegian; NOK 1,234.50 in English. Dates ISO-8601 in technical contexts; locale-formatted in user-facing contexts. | Locale conventions |
| GUI-I18N-04 | The catalogue is the single source of truth across all three client variants. Web, iOS and Android must surface identical strings for identical user actions. CI verifies catalogue parity. | D1 narrative-parity guarantee |
| GUI-I18N-05 | Right-to-left languages are not supported in MVP but layouts must use logical CSS properties (`margin-inline-start` etc.) so RTL is a future config flag, not a re-architecture. | Future-proofing |

### 2.5 Operational Requirements

| ID | Requirement | Rationale |
|----|------------|-----------|
| GUI-OPS-01 | Web GUIs must be served as pre-built static SPAs from an HTTPS-terminated reverse proxy (nginx) with HTTP/2, gzip + brotli, immutable asset caching, no-cache on `index.html`. | Performance |
| GUI-OPS-02 | Web GUIs must report Web Vitals (LCP, INP, CLS) to the existing observability stack via OpenTelemetry browser SDK. Budget: LCP ≤ 2.5s on 4G, INP ≤ 200ms, CLS ≤ 0.1, all at the 75th percentile. | UX, Google Web Vitals |
| GUI-OPS-03 | Web GUIs must propagate the W3C Trace Context (`traceparent`) on every backend call so a customer action appears as a single distributed trace from browser through to ledger. | DORA Art. 11, observability discipline |
| GUI-OPS-04 | Mobile apps must report crash reports and key UX metrics via OpenTelemetry mobile SDK (or Sentry + OTel exporter). Crash-free user rate ≥ 99.5% before release. | Standard mobile quality bar |
| GUI-OPS-05 | All GUIs must show a maintenance / incident banner sourced from the existing service-status feed (`status.events` Kafka topic projected to a small static feed). | DORA Art. 12 |
| GUI-OPS-06 | All GUIs must have a `/version` (web) or About screen (mobile) showing build SHA, build time, and backend API version. CI gates a release that has uncommitted changes or differs from the tagged version. | Release engineering |
| GUI-OPS-07 | All GUIs must run an SBOM scan in CI and fail the build on any HIGH or CRITICAL CVE in a runtime dependency without an approved suppression. | DORA Art. 8, GUI-SEC-18 |

### 2.6 Privacy Requirements

| ID | Requirement | Rationale |
|----|------------|-----------|
| GUI-PRV-01 | No third-party analytics, advertising, or tracking SDKs in any GUI. Telemetry goes only to first-party observability. | GDPR, banking convention |
| GUI-PRV-02 | All cookies must be first-party, marked `Secure`, `HttpOnly` (where applicable), `SameSite=Strict`. No tracking cookies. Consent banner only for the strictly necessary cookies (which we still disclose in a privacy policy). | GDPR Art. 7, ePrivacy |
| GUI-PRV-03 | All GUIs must support the customer's right to access (Art. 15) and right to erasure (Art. 17) self-service flows specified in `gui/use_cases/UC-G24_gdpr_data_export.md` and `gui/use_cases/UC-G25_gdpr_account_erasure.md`. | GDPR Art. 12, D5, Phase 1 FC.13 |
| GUI-PRV-04 | All GUIs must show a consent log to the customer per GDPR Art. 7(1) (proof of consent) and 13/14 (information about processing). | GDPR Art. 7 |
| GUI-PRV-05 | Customer-side data export must include all categories of personal data the bank processes about the customer (accounts, transactions, consents, fraud-alert involvements, audit log entries about the customer). Sensitive third-party data (other customers' names) is anonymised per the specification in `UC-G24_gdpr_data_export.md` §6 step 10 — per-export-random pseudonyms, full IBANs dropped, last-four digits retained. | GDPR Art. 15(1), Art. 11 |
| GUI-PRV-06 | Customer-facing notifications for fraud-hold (UC-G19), sanctions-block (UC-G20), and other automated-decision outcomes explicitly inform the customer of their GDPR Art. 22 rights: the decision was made in part by an automated system, a human operator reviews, and the customer may request further human review or contest. Copy for sanctions-block preserves anti-tipping-off (no mention of sanctions as the reason). | GDPR Art. 22, Recital 71, Phase 1 FG.1 |
| GUI-PRV-07 | On first app open / first login and on every material change, the privacy notice is displayed and explicitly accepted per UC-G45. Accepted version is persisted and appears in the consent log (UC-G26). The privacy notice discloses controller, DPO contact, purposes, legal bases, retention, rights, right to lodge complaint with Datatilsynet, and cross-border transfers (APNs/FCM/SMTP/SMS). | GDPR Art. 13, Art. 14, Phase 1 FG.2 |
| GUI-PRV-08 | Before the erasure cascade executes in UC-G25, the customer sees an explanation screen listing what is erased (PII: name, address, fødselsnummer, contact info), what is retained (transaction records for 5-year AML obligation, crypto-shredded but recoverable-as-events, never linkable to the customer) and what lies outside the bank's reach (counterparty banks and merchants who hold their own records). In-flight payments' disposition is named explicitly. The admin certificate (UC-G41) mirrors this language. | GDPR Art. 17, Art. 12, Phase 1 FG.3 |
| GUI-PRV-09 | The bank maintains a UC-G47 "Breach notification to customer" flow (Art. 34) — pre-designed operator-initiated action with inbox + email delivery. Customer-facing template is approved by compliance, stored in i18n catalogue, versioned per publication. | GDPR Art. 34, Phase 1 FG.10 |

### 2.7 Resilience and Error Handling

| ID | Requirement | Rationale |
|----|------------|-----------|
| GUI-RES-01 | All network calls must use timeout (default 10s, instant payment status 12s aligning to EPC 9s + buffer), retry-with-backoff for idempotent reads, no automatic retry for non-idempotent writes (the user is asked to confirm). | Crash resilience principle, idempotency discipline |
| GUI-RES-02 | All write requests must include an idempotency key (UUIDv4 generated client-side, surfaced in the request as `Idempotency-Key` header and persisted by the backend per existing pattern). | Backend idempotency contract (see system_requirements.md), prevent double-submit |
| GUI-RES-03 | All GUIs must distinguish three failure classes in user-facing messages: (a) transient (network, retryable — show retry button), (b) declined (business rule — show explanation), (c) error (unexpected — show support reference). Backend RFC 9457 Problem responses drive the classification. | UX, RFC 9457 already used by backend |
| GUI-RES-04 | When the backend returns a fraud HOLD or sanctions BLOCK, the GUI must use the canonical reason code from the API and the canonical user-facing copy from the i18n catalogue, never construct ad-hoc messages. | GUI-SEC-09-style structural enforcement |
| GUI-RES-05 | If the SSE stream for payment-status updates drops, the GUI must reconnect with exponential backoff and reconcile state by polling the payment-status endpoint once on reconnect. | Resilience, no missed status |

### 2.8 Test Requirements

The four-layer test pyramid documented in `implementation/TEST_FRAMEWORK.md` is extended to cover the GUIs:

| Layer | Web (G1, G2) | Mobile (G3, G4) |
|-------|--------------|-----------------|
| 1 — Unit | Vitest, jsdom | XCTest / JUnit |
| 2 — Component | Testing Library + MSW (mocked OpenAPI client) | ViewInspector / Compose UI Test |
| 3 — Contract | Pact consumer tests against the OpenAPI spec | Pact mobile equivalents |
| 4 — End-to-end | Playwright against live `docker-compose` stack | XCUITest / Espresso against live `docker-compose` stack via emulator |
| 5 — Cross-platform consistency | n/a | n/a — *new layer*: same scripted user task replayed on web, iOS and Android against the same backend, asserting the same outcome (matching narratives) |

Layer 5 is unique to this initiative: it is the structural verification that the user's "no re-training between variants" requirement holds.

### 2.8.1 Accessibility test discipline (Phase 1 FA.2 / FA.6)

- **Layer 2 component tests** for every modal / dialog / dropdown / popover include a focus-trap assertion: on open focus lands on first interactive element; Tab cycles within; Shift-Tab reverses; Escape (or platform-equivalent) closes and restores focus to the trigger; `aria-modal='true'` is set on web.
- **Layer 4 E2E** suites include a keyboard-only scenario alongside each pointer-based scenario for critical flows (UC-G05, UC-G17, UC-G24, UC-G25, UC-G32, UC-G33, UC-G43) — same assertions but driving with Tab / Enter / Space.
- **Visual regression at max text size.** Per GUI-A11Y-10, snapshot tests run at 200 % browser zoom (web) and Dynamic Type XXXL / Accessibility L (mobile), with Norwegian Bokmål strings. Layouts that overflow fail the build.
- **axe-core** on every component test renders a clean report; component violations block merge.
- **Manual screen-reader pass** per release on the critical flows — attested by the release PR description.

### 2.8.2 Contract test discipline (Phase 1 FI.10)

- Every mutating endpoint (`POST`, `PATCH`, `DELETE`) requires an `Idempotency-Key` header (UUIDv4). Spectral lint rule `jrpay-idempotency-key-required` enforces.
- Every request/response schema references only enums listed in `gui/architecture/SHARED_ENUMS.md`. Lint rule `jrpay-enums-match-shared`.
- Every error response in every spec file references `type` URIs listed in `gui/architecture/PROBLEM_TYPES.md`. Lint rule `jrpay-problem-types-registered`.

---

## 3. Functional Requirements — G1 Admin Web Console

Numbered as `ADM-F<n>` for functional, `ADM-NF<n>` for non-functional.

### 3.1 Authentication and Session Management

| ID | Requirement |
|----|-------------|
| ADM-F01 | Operators authenticate via OAuth2 Authorization Code + PKCE against the identity service. SCA required (TOTP for portfolio default; pluggable to BankID per D6). |
| ADM-F02 | Operator session timeout: 15 min idle, hard cap 8h. Re-authentication required at hard cap. SCA required for any `*:manage` action regardless of session age. |
| ADM-F03 | Concurrent-session limit: 1 per operator. Logging in elsewhere terminates other sessions. |

### 3.2 Fraud Case Management

| ID | Requirement | Source |
|----|-------------|--------|
| ADM-F10 | Display a paginated, filterable queue of open fraud cases. Filters: score range, triggered rule, account, date range, status, assignee. Default sort: highest score first. Auto-refresh every 30 s. | UC-05 |
| ADM-F11 | Drill-down view shows: transaction (debtor/creditor IBAN, amount, timestamp, reference), fraud-score breakdown (per layer: sanctions, VoP-risk, velocity, anomaly), VoP result (with `actualName` if present per disclosure rules), account 30-day snapshot, full audit timeline. | UC-05 |
| ADM-F12 | Operator can self-assign a case or be assigned by a supervisor. Assignment is logged. Reassignment requires reason. | UC-05, operational |
| ADM-F13 | Operator can decide a case as `CONFIRMED_FRAUD` or `DISMISSED`. Both decisions require: notes ≥ 50 characters, SCA confirmation, audit trail entry. `CONFIRMED_FRAUD` triggers `account.freeze-commands` Kafka publish (existing contract). `DISMISSED` releases held payments to clearing. | UC-05 |
| ADM-F14 | If a payment is held pending the case decision, the case view must display "Payment held" badge and a real-time SLA countdown (default 1 h SLA). | UC-05 |
| ADM-F15 | Bulk action on selected cases: bulk-assign or bulk-dismiss with shared notes. SCA per bulk action. Limit 50 cases per bulk action (rate limit). | Operational efficiency |

### 3.3 Sanctions Hit Review (separate queue, higher priority)

| ID | Requirement | Source |
|----|-------------|--------|
| ADM-F20 | Display a separate queue of sanctions hits (red banner, 15-min SLA per IPR Art. 5d operational standard). Filters: list (EU consolidated / UN / Norwegian / OFAC), matched entity, date range. | UC-05, IPR Art. 5d |
| ADM-F21 | Drill-down view shows: transaction, beneficiary name from transaction, matched sanctions entry (full record from list), Jaro-Winkler score, prior dealings with this beneficiary, list version & last update. | UC-05 |
| ADM-F22 | Operator decision: `CONFIRMED_SANCTION` (block payment + freeze account + queue FIU report stub) / `FALSE_POSITIVE` (release, add to local false-positive cache, audit) / `RELEASE_FOR_FRAUD_REVIEW` (reassign to fraud queue). All decisions require SCA + notes ≥ 50 chars. | UC-05 |
| ADM-F23 | Sanctions list update view: current list version, last update, next scheduled update. Manual "Force Update" with SCA confirmation. | A5-F24 |

### 3.4 Account Administration

| ID | Requirement | Source |
|----|-------------|--------|
| ADM-F30 | Search accounts by holder name (fuzzy), IBAN (exact), or fødselsnummer (exact, requires `accounts:admin` scope, logged). | UC-07 |
| ADM-F31 | Account detail page: status (ACTIVE/FROZEN/CLOSED), balances (booked, reserved, available), currency, freeze reason+date+actor (if frozen), reservations list, recent transactions, audit subset for this account. | UC-07 |
| ADM-F32 | PII reveal: holder full name and address are masked by default (`Ola N******** / **********`). Reveal button requires SCA, logs `audit:pii-revealed` per GUI-SEC-15. | GUI-SEC-15, GDPR Art. 32 |
| ADM-F33 | Manual freeze: reason dropdown (`FRAUD_INVESTIGATION`, `SANCTIONS_HOLD`, `REGULATORY_HOLD`, `OTHER`), notes ≥ 50 chars, SCA. Publishes `account.freeze-commands` (existing contract). | UC-07 |
| ADM-F34 | Manual unfreeze: reason dropdown (`INVESTIGATION_CONCLUDED`, `FALSE_POSITIVE_CONFIRMED`, `CUSTOMER_DISPUTE_RESOLVED`, `OTHER`), notes, SCA. Requires SUPERVISOR role or higher. | UC-07 |
| ADM-F35 | Account closure: only when balance is zero (server-checked, GUI also checks). Reason, notes, SCA. Closure cannot be undone. | UC-07 |
| ADM-F36 | Account creation (admin-initiated, e.g., post-CDD): minimal form, SCA. | UC-07 |

### 3.5 Stuck-Payment Investigation

| ID | Requirement | Source |
|----|-------------|--------|
| ADM-F40 | Display the `payment.investigation` queue: payments stuck in non-terminal state for > 2 h. Per row: payment ID, status, age, last state change, debtor, creditor, amount. | UC-08 AF-06 |
| ADM-F41 | Operator can issue a pacs.028 status query and see the response inline. | A2-F29 |
| ADM-F42 | Operator can initiate a pacs.004 return: original payment ID, return reason (`ACCOUNT_CLOSED`, `CUSTOMER_REQUEST`, `FRAUD_REFUND`, `OTHER`), amount (defaults to original), notes, SCA. | A2-F30 |
| ADM-F43 | Operator can mark a payment as "manually resolved" with required notes (for cases where external action concluded the matter). Audited. | Operational |

### 3.6 Reconciliation

| ID | Requirement | Source |
|----|-------------|--------|
| ADM-F50 | Reconciliation status dashboard: per-day totals of RECONCILED / UNRECONCILED / DISCREPANCY counts and amounts. | A1-F16 |
| ADM-F51 | Drill-down to discrepancy list. Per discrepancy: type (missing-internal, missing-external, amount-mismatch), external reference, internal events. | A1-F16 |
| ADM-F52 | Trigger on-demand reconciliation: upload external settlement report (camt.053 XML or CSV) or select scheduled-report from dropdown. Result available within budget defined by A1-F17 (target ≤ 60s for ≤ 50k records). | A1-F17 |
| ADM-F53 | Mark a discrepancy as "investigated and resolved" with required notes; audited. | Operational |

### 3.7 Audit Log Search and Hash-Chain Verification

| ID | Requirement | Source |
|----|-------------|--------|
| ADM-F60 | Search audit events by date range, event type, actor, affected resource (account ID, transaction ID), outcome. Paginated. Sortable by timestamp. | SEC-07 |
| ADM-F61 | View hash-chain verification report: latest verified checkpoint, gaps detected (if any), instance fingerprints. Trigger ad-hoc verification with SCA. | SEC-07, AML hash chain |
| ADM-F62 | Export selected audit events as CSV (audited) or as a signed JSON bundle (signing key in HSM/KMS, out of scope for portfolio — log "signing skipped: no KMS"). | Compliance reporting |

### 3.8 GDPR Operations

| ID | Requirement | Source |
|----|-------------|--------|
| ADM-F70 | View the GDPR queue: customer-initiated erasure and access requests, status (`PENDING`, `IN_PROGRESS`, `COMPLETED`, `REJECTED`), age, due date (30 days from request per Art. 12(3)). | GDPR Art. 12, D5 |
| ADM-F71 | Process an erasure request: confirm crypto-shredding key deletion across all six services, generate completion certificate, audit. | GDPR Art. 17 |
| ADM-F72 | Process an access request: trigger generation of the export bundle, monitor progress, deliver via secure download link. | GDPR Art. 15 |
| ADM-F73 | Reject a GDPR request with required justification (e.g., AML retention conflict for non-personal data); customer is notified with reason. | GDPR Art. 12(4) |

### 3.9 System Health and SLA

| ID | Requirement | Source |
|----|-------------|--------|
| ADM-F80 | Service health summary embedded from Prometheus: per-service status, error rate, p50/p95/p99 latency, uptime. | OPS-01, OPS-02 |
| ADM-F81 | Queue-depth widget: instant-payment instructions, fraud requests, settlement events, audit events. Alert badge if any over threshold. | DORA Art. 12 |
| ADM-F82 | SLA dashboard: instant-payment p95 latency vs 5 s target, fraud-alert resolution age vs 1 h target, sanctions-alert resolution age vs 15 min target. Drill-through to violating items. | EU IPR Art. 5b, operational |
| ADM-F83 | DLQ viewer: per-topic dead-letter depth, sample of poisoned messages (with PII redacted), retry button (per message, requires SUPERVISOR + SCA). | RES-09 |
| ADM-F84 | Status banner manageable by SUPERVISOR: schedule/post a maintenance or incident notice; appears on customer GUIs (G2, G3, G4). | DORA Art. 12 |

### 3.10 User and Role Administration

| ID | Requirement | Source |
|----|-------------|--------|
| ADM-F90 | List back-office users with role, last login, status. Filters: role, department, status. | Standard admin |
| ADM-F91 | Create user (SUPERVISOR scope): assigns a role from the locked set (D2). Initial credentials sent via email; first-login forces password change and SCA enrolment. | D2 |
| ADM-F92 | Suspend, reactivate, delete (archive) user. All audited; requires SUPERVISOR + SCA. | Standard admin |
| ADM-F93 | View per-user activity log (decisions made, accounts touched, PII reveals). | GUI-SEC-15 |

### 3.11 Non-functional

| ID | Requirement |
|----|-------------|
| ADM-NF01 | Page load p95 ≤ 1.5 s on broadband, ≤ 3 s on 4G. |
| ADM-NF02 | Fraud queue must remain interactive at 1000 cases with virtualised list rendering. |
| ADM-NF03 | All `*:manage` actions audited within the same transaction as the action (no eventual-consistency window where the action exists without an audit row). |
| ADM-NF04 | Browser support: latest 2 versions of Chrome, Firefox, Safari, Edge. No IE11. |

---

## 4. Functional Requirements — G2/G3/G4 Client GUIs (parity)

The following requirements apply identically to the Client Web (G2), Client iOS (G3), and Client Android (G4). Where a requirement is platform-specific (e.g., biometric API), the body notes the platform-specific binding.

Numbered as `CLI-F<n>` and `CLI-NF<n>`.

### 4.1 Authentication and Session

| ID | Requirement | Source |
|----|-------------|--------|
| CLI-F01 | Login via OAuth2 Authorization Code + PKCE. SCA via simulated BankID by default; biometric fallback (Face ID, Touch ID, BiometricPrompt) on mobile. | UC-02, UC-03 |
| CLI-F02 | Customer session timeout: 15 min idle. After timeout, GUI requires re-authentication. PSD2 RTS Art. 10 90-day re-auth cadence enforced at the consent layer. | UC-02 AF-04 |
| CLI-F03 | Logout: clears in-memory state, revokes refresh token at identity service, returns to login screen. | GUI-SEC-17 |
| CLI-F04 | Mobile cold-start unlock: biometric or PIN required to release the refresh token from secure storage. Failed unlock attempts are rate-limited identically to backend SCA. | GUI-SEC-07 |
| CLI-F05 | Password reset: self-service via email link + SCA challenge. Account lockout after 3 failed SCA attempts; unlock after 5 min. | UC-02 AF-02 |

### 4.2 Account Overview and Transaction History

| ID | Requirement | Source |
|----|-------------|--------|
| CLI-F10 | Account list: per account show IBAN, currency, account type, booked balance, available balance (booked − reserved), status badge (ACTIVE/FROZEN/CLOSED). Tap/click → account detail. | UC-02, UC-07 |
| CLI-F11 | Account detail: transaction history paginated (25/page default). Filters: date range, amount range, counterparty name, reference. Sort by date or amount. | UC-02, UC-07 |
| CLI-F12 | Transaction detail: amount, currency, debtor/creditor IBAN+name, reference (incl. KID rendering), payment method (INSTANT/STANDARD), status, timestamp, VoP outcome (badge), confirmation/clearing references. Downloadable receipt (PDF). | UC-01 |
| CLI-F13 | Account statement export: PDF (visual), JSON, camt.053 XML for selected period. | UC-07 |
| CLI-F14 | Status badge for FROZEN account: customer sees "Account under review — credits accepted, debits suspended. Contact support." with reference number for the case. | UC-07, UX |

### 4.3 One-off Payment Initiation

| ID | Requirement | Source |
|----|-------------|--------|
| CLI-F20 | Payment form fields: debtor account (own, dropdown), creditor IBAN, creditor name, amount, currency (defaults from debtor), reference (free text or KID with mod-10/11 hint), description (optional), execution mode (instant/standard, default instant if available for the currency/amount). | UC-08, UC-03 |
| CLI-F21 | IBAN format validation (mod-97) on input; KID validation (mod-10/11) on input. Error messages reference the i18n catalogue. | Banking convention |
| CLI-F22 | Verification of Payee runs *before* SCA, after creditor IBAN+name are entered. Result displayed as a traffic-light card: GREEN (`MATCH` — proceed); AMBER (`CLOSE_MATCH` — display the actual name from the API as a suggestion, "Did you mean X?", buttons Confirm / Cancel); RED (`NO_MATCH` — actual name **never displayed**, warning copy, Cancel/Override buttons; override requires explicit re-confirmation and is logged); RED-error (`NOT_FOUND` — instruct to check IBAN, no override path). | UC-09, GUI-SEC-09 |
| CLI-F23 | If A2 returns a semantic-duplicate warning (existing CLR-B14 contract), display a non-blocking warning card: "Looks similar to a payment of NOK 1.234 to the same recipient 3 minutes ago." with Cancel/Confirm buttons. Confirm sets the override flag. | UC-01 AF-11 |
| CLI-F24 | SCA challenge screen: displays amount and creditor name in a dynamic-linking-protected region (GUI-SEC-08); customer enters TOTP / completes BankID; 5-min timeout with countdown. | UC-03, PSD2 RTS Art. 5 |
| CLI-F25 | If SCA exemption applies (low-value / trusted beneficiary / TRA / recurring), display a brief notification stating the exemption type before submission. Customer may always demand SCA via "Require SCA anyway" option. | UC-03 AF-01..04 |
| CLI-F26 | Confirmation screen: payment ID, amount, recipient, reference, status (`SETTLED` for instant — usually within 5 s; `PENDING` for batch). Receipt downloadable as PDF. | UC-08 |
| CLI-F27 | Real-time status updates via SSE (web) / push notifications (mobile) for INSTANT payments: GUI shows progress through `RECEIVED → AWAITING_RESERVATION → AWAITING_FRAUD → SETTLING → SETTLED` with the canonical PaymentStatus enum. | UC-08, GUI-RES-05 |
| CLI-F28 | Payment cancellation pre-SCA only: explicit cancel button removes the in-progress instruction. Once SCA submitted, cancellation not possible. | UC-01 AF-05 |
| CLI-F29 | Status-poll fallback for batch payments: customer can request status check (pacs.028 path on backend) for a pending payment. | UC-08 AF-06 |

### 4.4 Scheduled and Recurring Payments

| ID | Requirement | Source |
|----|-------------|--------|
| CLI-F40 | Scheduled payment (single, future date): same payment form plus execution date picker (max 90 days ahead). Server queues; customer can cancel before execution. | Standard banking |
| CLI-F41 | AvtaleGiro mandate setup: form (merchant search, max single amount, frequency, start/end), VoP check on merchant IBAN, SCA. | UC-06 |
| CLI-F42 | Mandate list: per mandate show merchant, max amount, frequency, last and next execution. Actions: View, Modify max amount (SCA), Revoke (SCA). | UC-06 |
| CLI-F43 | VRP consent setup: TPP name (auto from OAuth flow), per-payment max, cumulative period max, max frequency, payee, validity dates, duration. SCA. Parameters immutable post-creation. | UC-10 |
| CLI-F44 | VRP consent dashboard: each consent's bounds, cumulative usage, expiry. Revoke (SCA). | UC-10 |

### 4.5 Request-to-Pay and Bill Payments

| ID | Requirement | Source |
|----|-------------|--------|
| CLI-F50 | Incoming R2P inbox: per-request show merchant name (+ logo where supplied), amount, currency, due date, description, reference, status. Filters: status, date range, merchant. Sort default: due date ascending. Badge counts urgent (< 3 days). | UC-01 |
| CLI-F51 | R2P detail: full record + invoice attachment link. Actions: Accept & Pay Now, Accept & Pay Later (defer with date picker), Decline (reason dropdown), Request Amendment (counter-offer). | UC-01 |
| CLI-F52 | Accept & Pay Now: chooses execution mode, runs VoP, runs SCA, submits. | UC-01 |
| CLI-F53 | Decline reason codes: `ALREADY_PAID`, `WRONG_AMOUNT`, `INVOICE_INCORRECT`, `OTHER` (with mandatory free-text). | UC-01 AF-05 |
| CLI-F54 | QR-code initiation: scan EPC069-12 QR code to pre-fill payment form (mobile); upload QR image or paste payload (web). | A3-F09 |

### 4.6 Trusted Beneficiaries

| ID | Requirement | Source |
|----|-------------|--------|
| CLI-F60 | Trusted beneficiary list: name, IBAN, bank, last paid, total YTD. | UC-03 AF-02 |
| CLI-F61 | Add trusted beneficiary: name, IBAN, optional notes; VoP check; SCA on add. | UC-03 AF-02 |
| CLI-F62 | Edit, remove (no SCA on remove; remove does not revoke any mandates to that beneficiary). | UC-06 separation |

### 4.7 PSD2 Consent Management

| ID | Requirement | Source |
|----|-------------|--------|
| CLI-F70 | Consent dashboard: per TPP show name, scopes granted, accounts covered, granted date, expiry date with countdown. | UC-02 |
| CLI-F71 | Revoke consent: confirmation dialog, immediate effect, success state shown. | UC-02 AF-05 |
| CLI-F72 | OAuth2 consent grant flow (when redirected by a TPP): show TPP name, requested scopes in plain language, accounts to be shared, validity duration. Approve / Deny. SCA on Approve. | UC-02 |
| CLI-F73 | Consent renewal prompt 7 days before expiry (in-app inbox notification + push if enabled). | UC-02 AF-04 |

### 4.8 Notifications and Status

| ID | Requirement | Source |
|----|-------------|--------|
| CLI-F80 | Notification inbox: chronological list of personal notifications (payment confirmations, payment rejections, fraud holds affecting customer, R2P arrivals, consent expiries, GDPR-request status). Mark read / unread / delete. | UC-05, GDPR transparency |
| CLI-F81 | Notification preferences: per category, per channel (in-app inbox always on; email, SMS, push toggleable). | Standard |
| CLI-F82 | Push notifications (mobile only) wake the app to the relevant deep link (e.g., R2P detail). Channel registration uses APNs/FCM token; token rotation handled. | Mobile UX |
| CLI-F83 | Fraud-hold notification copy: "Your payment of NOK X to Y is under review. We will confirm within 1 h." Customer can view status; cannot lift the hold. | UC-05 Scenario 5B |
| CLI-F84 | Sanctions-block notification copy: "Your payment to Y could not be processed due to regulatory compliance. Please contact support: <reference>." Customer cannot override. | UC-05 Scenario 5E |

### 4.9 Self-Service and Regulatory Rights

| ID | Requirement | Source |
|----|-------------|--------|
| CLI-F90 | Profile: view name, DOB, fødselsnummer (masked), address, contact methods. Edit address, email, phone (SCA + email confirmation loop). | GDPR Art. 16 |
| CLI-F91 | Customer-facing language preference (NB / EN); persisted server-side. | GUI-I18N-01 |
| CLI-F92 | GDPR export request: customer triggers export; status visible; download link via email when ready (within 30 days, target same-day). | GDPR Art. 15, D5 |
| CLI-F93 | GDPR erasure request: customer triggers; warning flow ("balance must be 0; mandates will be revoked; access will be revoked permanently"); SCA confirmation; backend executes crypto-shredding cascade. Status visible to customer until completion. | GDPR Art. 17, D5 |
| CLI-F94 | Consent log: chronological list of all consent grants/revocations relating to the customer (PSD2 consents, marketing preferences). | GDPR Art. 7 |

### 4.10 Cross-platform parity rules (binding on G2, G3, G4)

| ID | Requirement |
|----|-------------|
| CLI-F100 | Every CLI-F requirement is implemented on all three variants. CI gates a release where any platform is missing a requirement marked "core". |
| CLI-F101 | The wording of user-visible strings is identical across platforms (subject to platform-conventional terminology, e.g., "tap" vs "click"). i18n catalogue is authoritative. |
| CLI-F102 | Flow shape is identical: same number of steps, same decision points, same back/forward semantics. Layouts and component choices may differ. |
| CLI-F103 | Error and warning copy is identical (canonical i18n catalogue). |
| CLI-F104 | The cross-platform consistency test layer (Section 2.8 layer 5) executes the same scripted scenarios and asserts the same backend state changes regardless of which client variant performed them. |

### 4.11 Non-functional (Customer GUIs)

| ID | Requirement |
|----|-------------|
| CLI-NF01 | Cold start to login screen: web ≤ 2.5 s on 4G; iOS ≤ 1.5 s; Android ≤ 1.8 s (p95). |
| CLI-NF02 | Time from "Confirm payment" tap to status `SETTLED` displayed (instant payment, happy path): ≤ 6 s p95 (backend SLA 5 s + GUI overhead 1 s). |
| CLI-NF03 | App size: iOS ≤ 60 MB; Android ≤ 40 MB (split APKs). |
| CLI-NF04 | Crash-free user rate ≥ 99.5%. |
| CLI-NF05 | Browser support (web): latest 2 versions of Chrome, Firefox, Safari, Edge; iOS Safari 16+; Chrome for Android current. |
| CLI-NF06 | Mobile OS support: iOS 16+; Android 10 (API 29)+. |
| CLI-NF07 | Offline behaviour: cached read-only views (account list, last 30 days transactions) must be available offline with a "Last updated <time>" indicator. Writes are not attempted offline. |

---

## 5. Functional Requirements — A7 Identity Service

Numbered as `IDS-F<n>`.

| ID | Requirement |
|----|-------------|
| IDS-F01 | OAuth2 Authorization Code + PKCE (RFC 7636) for all four GUIs and for TPPs. Implicit and Resource Owner Password flows are not exposed. |
| IDS-F02 | OAuth2 Client Credentials for service-to-service (e.g., admin GUI's backend-for-frontend if introduced; not used in MVP). |
| IDS-F03 | JWT access tokens RS256 signed with rotating keys; JWKS endpoint (`/.well-known/jwks.json`); 15-min TTL. Refresh tokens opaque, device-bound (DPoP per RFC 9449 — phase-2 enhancement, not MVP), 30-day rolling TTL. |
| IDS-F04 | SCA orchestration: pluggable `ScaProvider` SPI per D6. Default `SimulatedBankIdScaProvider`. Enrollment flow for first-time SCA factor setup. |
| IDS-F05 | Dynamic-linking SCA per PSD2 RTS Art. 5: when SCA is requested for a payment, the request includes amount + creditor; the SCA proof is bound to those values; A4 verifies the binding before allowing the payment. |
| IDS-F06 | RBAC scope mapping per D2; roles defined in DB; role assignments audited. |
| IDS-F07 | Lockout policy: 3 failed SCA attempts → 5-min lockout; 5 lockouts in 24 h → admin-required unlock. |
| IDS-F08 | Concurrent-session control per audience (admin: 1; customer: configurable, default 3). |
| IDS-F09 | Token revocation (RFC 7009) and introspection (RFC 7662) endpoints. |
| IDS-F10 | OpenID Connect discovery (`/.well-known/openid-configuration`) for all four GUIs and TPP partners. |
| IDS-F11 | All authentication events emit structured audit events to `audit.events` Kafka topic (existing contract). |
| IDS-F12 | DB schema (Postgres `identity`): `users`, `roles`, `user_roles`, `sca_factors`, `sca_sessions`, `oauth2_clients`, `consents` (separate from PSD2 consents in A4), `refresh_tokens`, `audit` (mirror to Kafka). Flyway migrations. |
| IDS-F13 | Performance: token issue p95 ≤ 100 ms; introspect p95 ≤ 30 ms. |
| IDS-F14 | Password hashing: argon2id with parameters memoryKb=65536 (64 MiB), iterations=3, parallelism=4, saltLength=16 bytes. BCrypt tolerated only on read-path during a documented migration window; subsequent logins migrate-on-login to argon2id. | Phase 1 FS.6, NIST 800-63B |
| IDS-F15 | Password policy: minimum length 10; no complexity-class checks (per NIST 800-63B); strength-check via zxcvbn with score ≥ 3 required; breached-password check against a local HIBP bloom filter (updated monthly) — reject on match. | Phase 1 FS.6, NIST 800-63B |
| IDS-F16 | Password reset: customer- and operator-initiated via UC-G44. One-time signed token, 30-min TTL, single-use, HMAC-signed. Post-reset: all refresh tokens for the user invalidated; user re-authenticates via UC-G01 with SCA. | Phase 1 FS.6, UC-G44 |
| IDS-F17 | Password history: cannot reuse any of the last 4 password hashes. | Phase 1 FG.x (follow-on); NIST 800-63B recommendation |
| IDS-F18 | Identity service emits to a short-lived revocation cache (Redis) on session revocation; resource servers check `jti` against this cache for the first 30 seconds after `exp - 30s` to propagate revocation faster than token-TTL alone. | Phase 1 FS.5 |

---

## 6. Backend Endpoint Additions (summary; OpenAPI specs are authoritative)

Each endpoint listed here will have a full OpenAPI 3.1 specification under `implementation/api-specs/`.

### 6.1 A4 customer-facing PSD2 endpoints (extension)

```
GET    /api/v1/customer/accounts
GET    /api/v1/customer/accounts/{accountId}
GET    /api/v1/customer/accounts/{accountId}/balances
GET    /api/v1/customer/accounts/{accountId}/transactions
POST   /api/v1/customer/payments
GET    /api/v1/customer/payments/{paymentId}
DELETE /api/v1/customer/payments/{paymentId}      (cancel pre-SCA)
GET    /api/v1/customer/payments/stream           (SSE for status updates)
```

### 6.2 A3 R2P and mandate endpoints (extension)

```
GET    /api/v1/customer/r2p
GET    /api/v1/customer/r2p/{requestId}
POST   /api/v1/customer/r2p/{requestId}/accept
POST   /api/v1/customer/r2p/{requestId}/decline
POST   /api/v1/customer/r2p/{requestId}/defer
POST   /api/v1/customer/r2p/{requestId}/amend
GET    /api/v1/customer/mandates
POST   /api/v1/customer/mandates
PATCH  /api/v1/customer/mandates/{mandateId}
DELETE /api/v1/customer/mandates/{mandateId}
GET    /api/v1/customer/beneficiaries
POST   /api/v1/customer/beneficiaries
DELETE /api/v1/customer/beneficiaries/{id}
```

### 6.3 A5 case-management endpoints (new — A5 was previously stateless)

```
GET    /api/v1/admin/fraud/cases
GET    /api/v1/admin/fraud/cases/{caseId}
POST   /api/v1/admin/fraud/cases/{caseId}/assign
POST   /api/v1/admin/fraud/cases/{caseId}/decide
GET    /api/v1/admin/sanctions/hits
GET    /api/v1/admin/sanctions/hits/{hitId}
POST   /api/v1/admin/sanctions/hits/{hitId}/decide
GET    /api/v1/admin/sanctions/list
POST   /api/v1/admin/sanctions/list/refresh
```

A5 gains a Postgres `fraud` schema with `fraud_cases`, `sanctions_hits`, `false_positive_cache` tables. Stateless scoring pipeline is preserved; case state is persisted by a new projection consumer.

### 6.4 A1 admin endpoints (extension)

```
GET    /api/v1/admin/accounts/search
GET    /api/v1/admin/accounts/{accountId}
POST   /api/v1/admin/accounts/{accountId}/freeze
POST   /api/v1/admin/accounts/{accountId}/unfreeze
POST   /api/v1/admin/accounts/{accountId}/close
POST   /api/v1/admin/accounts                          (create — already exists, scope-tightened)
POST   /api/v1/admin/accounts/{accountId}/reveal-pii   (audited per GUI-SEC-15)
```

### 6.5 Audit query service (new)

A dedicated `jrpay-audit-query` service consumes `audit.events` (existing topic) into a Postgres `audit` schema for query.

```
GET    /api/v1/admin/audit/events
GET    /api/v1/admin/audit/checkpoints
POST   /api/v1/admin/audit/verify
GET    /api/v1/admin/audit/export
```

### 6.6 Notification service (new)

`jrpay-notifications` hosts the inbox + adapter SPIs per D7.

```
GET    /api/v1/customer/notifications
PATCH  /api/v1/customer/notifications/{id}             (read/unread/delete)
GET    /api/v1/customer/notifications/preferences
PUT    /api/v1/customer/notifications/preferences
GET    /api/v1/customer/notifications/stream           (SSE)
POST   /api/v1/customer/notifications/devices          (register APNs/FCM token)
```

### 6.7 GDPR endpoints (split between A4 and a new compliance helper)

```
POST   /api/v1/customer/gdpr/export
GET    /api/v1/customer/gdpr/export/{requestId}
POST   /api/v1/customer/gdpr/erase
GET    /api/v1/customer/gdpr/erase/{requestId}

GET    /api/v1/admin/gdpr/queue
POST   /api/v1/admin/gdpr/{requestId}/process
POST   /api/v1/admin/gdpr/{requestId}/reject
```

### 6.8 Status banner service (re-uses notifications backend)

```
GET    /api/v1/public/status                            (no auth — same banner shown on login screen)
POST   /api/v1/admin/status                             (SUPERVISOR + SCA)
```

---

## 7. Use Case Coverage

The functional requirements above are realised as use cases under `gui/use_cases/UC-G<nn>_<slug>.md`. The naming `UC-G<nn>` distinguishes GUI use cases from the existing backend `UC<nn>`. See `gui/use_cases/INDEX.md` for the full list. A representative sample is delivered with this requirements document; the remainder is delivered in Phase 0 follow-up.

---

## 8. Traceability

Every requirement above is linked to:
- A use case under `gui/use_cases/`
- An OpenAPI operation under `implementation/api-specs/`
- A test at the appropriate layer of the test pyramid (Section 2.8)

A traceability matrix is maintained at `gui/requirements/TRACEABILITY.md` and updated when any of the four artefacts change.

---

## 9. Glossary additions

| Term | Definition |
|------|-----------|
| **GUI** | One of the four user-facing artefacts: G1 admin web, G2 client web, G3 client iOS, G4 client Android |
| **Identity service (A7)** | New backend module hosting OAuth2 Authorization Server and SCA orchestration |
| **Scope** | OAuth2 scope string; a unit of authorisation granularity |
| **Role** | Named bundle of scopes assigned to a back-office user |
| **Variant** | Refers to one of the three client GUI variants (web, iOS, Android) |
| **Cross-platform consistency test** | Layer-5 test that replays the same user task on all three variants and asserts identical backend outcomes |
| **Dynamic-linking** | PSD2 RTS Art. 5 binding of an SCA proof to the specific amount + payee of a payment |
| **Reveal PII** | An audited operator interaction in the admin GUI that unmasks a customer's full name, address, or fødselsnummer |
