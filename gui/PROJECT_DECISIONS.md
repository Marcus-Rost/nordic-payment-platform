# JRPay GUI Extension — Locked Project Decisions

*Decisions taken at session kick-off, 2026-04-23, locking the seven open questions raised in the discovery overview.*
*Status: AGREED — supersedes any alternatives discussed in chat.*

---

These decisions are inputs to all downstream artefacts (`gui/requirements/gui_requirements.md`, `gui/architecture/*`). If any decision is reopened later, every dependent artefact must be re-reviewed for consistency.

---

## D1. Tech stack per GUI

| GUI | Stack | Rationale |
|-----|-------|-----------|
| **Admin Web** | React 18 + TypeScript + Vite + TanStack Query + TanStack Router + Tailwind CSS + Headless UI / Radix Primitives | Mature, ubiquitous, plays well with OpenAPI codegen. Tailwind chosen for the data-dense admin tooling. Headless UI / Radix gives WCAG-compliant primitives without locking us into a heavy design system. |
| **Client Web** | React 18 + TypeScript + Vite + TanStack Query + TanStack Router + Tailwind CSS + Radix Primitives + react-hook-form + zod | Same toolchain as admin → shared infrastructure (build, lint, codegen). `react-hook-form` + `zod` chosen because payment forms have strict validation invariants (IBAN mod-97, KID mod-10/11, BigDecimal) and we want a typed schema layer that mirrors backend Bean Validation. |
| **Client iOS** | SwiftUI + Swift 5.9 + Swift Concurrency (`async/await`) + AppAuth-iOS + LocalAuthentication + Keychain Services + URLSession with certificate pinning | "Native" was a hard requirement. SwiftUI is the modern path; iOS 16+ baseline (covers >95% of iOS install base in 2026). |
| **Client Android** | Jetpack Compose + Kotlin 1.9 + Coroutines + AppAuth-Android + BiometricPrompt + EncryptedSharedPreferences + Retrofit + OkHttp with certificate pinning via Network Security Configuration | "Native" was a hard requirement. Jetpack Compose is the modern path; Android 10+ (API 29) baseline. |

**Shared across all four GUIs:**
- **OpenAPI 3.1** as the single contract source. We generate typed clients per platform: `openapi-typescript` for the web GUIs, `openapi-generator` (`swift5` and `kotlin`) for the mobile apps. The OpenAPI specs live in the backend repo (`implementation/api-specs/`) so contract and implementation cannot drift.
- **One ICU MessageFormat catalogue** (Norwegian Bokmål primary, English secondary) consumed by all three client variants. Source of truth: `gui/i18n/`. Web uses `formatjs`; iOS uses `String.localizedStringWithFormat` with a Swift adapter; Android uses `MessageFormat`.
- **Design tokens** (colours, spacing, typography, radii) defined once in `gui/design-tokens/tokens.json` and exported per-platform (CSS variables / Tailwind config for web; Swift `Color`/`Font` extensions for iOS; Compose `MaterialTheme` overrides for Android).

**Rejected alternatives (and why):**
- **React Native or Flutter for mobile** — Rejected. The user explicitly said "native". A cross-platform framework would also undermine the portfolio framing of platform mastery.
- **Vue/Angular/Svelte for web** — Rejected. React's tooling for OpenAPI codegen and TypeScript inference is the most mature; aligning both web GUIs on the same toolchain reduces marginal cost of the second one substantially.
- **Material Web Components / Bootstrap** — Rejected. Tailwind + Radix gives finer control over the bespoke payment UX (VoP traffic-light feedback, SCA dynamic-linking confirmation) without fighting an opinionated design system.

---

## D2. Auth architecture

**Decision:** A new dedicated `jrpay-identity-service` module hosts the OAuth2 Authorization Server (Spring Authorization Server 1.x). It is *not* merged into A4.

**Rationale:**
- A4's mandate is **external** TPP-facing PSD2 — its OAuth2 flows are part of the regulated PSD2 surface (consent grants under PSD2 RTS Art. 10 with 90-day re-auth).
- Customer login (own banking app) and back-office login (admin/case-worker) are **different audiences** with different SCA rules, different session lifetimes, different scope sets, different rate-limit budgets.
- Mixing those concerns inside A4 would muddle two regulated flows and inflate A4's blast radius. P4 ("Justified Service Boundaries") in the existing architecture explicitly forbids this.
- A dedicated identity service is also closer to how target companies (DNB, SpareBank 1, Tietoevry) deploy in production — an identity tier in front of the business services.

**Scope sets (locked):**

*Customer scopes:*
- `accounts:read`, `accounts:write`
- `transactions:read`
- `payments:initiate`, `payments:read`, `payments:cancel`
- `mandates:manage`
- `consents:manage` (for managing own PSD2 grants)
- `beneficiaries:manage`
- `notifications:manage`
- `profile:read`, `profile:write`
- `gdpr:export`, `gdpr:erase`

*Admin/back-office scopes:*
- `fraud:read`, `fraud:manage` (assign + decide cases)
- `sanctions:read`, `sanctions:manage`
- `accounts:admin:read` (search, view detail, no mutations) — Phase 1 FI.9 split
- `accounts:admin:write` (freeze, unfreeze, close, create, reveal PII) — Phase 1 FI.9 split
- `audit:read`, `audit:verify` (hash-chain verification)
- `reconciliation:read`, `reconciliation:run`
- `payments:investigate` (pacs.028 stuck-payment, pacs.004 return)
- `gdpr:execute` (process erasure requests)
- `users:read`, `users:manage` (back-office user/role admin)
- `system:read` (health, queue depth)

*TPP scopes (existing PSD2 surface, unchanged):*
- `accounts:read`, `transactions:read`, `payments:initiate` (per BerlinGroup)

**Roles (back-office, mapped to scope bundles):**
- `FRAUD_ANALYST` → `fraud:read`, `fraud:manage`, `audit:read`, `accounts:admin:read`
- `COMPLIANCE_OFFICER` → `sanctions:read`, `sanctions:manage`, `accounts:admin:read`, `accounts:admin:write`, `audit:read`, `gdpr:execute`
- `OPERATIONS` → `payments:investigate`, `reconciliation:read`, `reconciliation:run`, `system:read`, `audit:read`, `accounts:admin:read`
- `SUPERVISOR` → all `*:manage` + `accounts:admin:write` + `users:manage`
- `ADMIN` → all scopes (break-glass; usage triggers high-priority audit alert)

Note: `reveal-pii` is gated by `accounts:admin:write` but additionally requires step-up SCA plus (per FS.10) a second SUPERVISOR approval on repeat reveals of the same account within 24 hours. See UC-G36 and GUI-SEC-15.

**Tokens:** JWT (RS256, rotating signing key, JWKS endpoint) with 15 min access-token TTL, refresh tokens bound to the device fingerprint. Customer SCA proof is recorded as a JWT claim (`acr=loa3`) with the dynamic-linking hash per PSD2 RTS Art. 5.

---

## D3. Mobile depth

**Decision:** Fully native. SwiftUI for iOS, Jetpack Compose / Kotlin for Android. **No** React Native, Flutter, or Kotlin Multiplatform UI.

Shared cross-platform layer is limited to:
- OpenAPI-generated networking client (per-platform language)
- i18n catalogue (per-platform format adapter)
- Design tokens (per-platform export)
- Documentation of user narratives (one source, three implementations)

This is consistent with the user's instruction that "a user can switch between variants without re-training" — narratives are shared, code is not.

---

## D4. Production deployment target

**Decision:** Kubernetes-ready with the existing `docker-compose` retained for local development.

- **Backend services:** Helm chart + Kustomize overlays. Manifests added under `implementation/deploy/k8s/`. Targeted at any conformant Kubernetes 1.28+ cluster. We do not bind to a specific cloud provider in code; cloud-specific deploy notes (EKS, GKE, AKS) go in `implementation/deploy/PROVIDERS.md`.
- **Web GUIs:** Built as static SPAs (Vite production build) and shipped as a single nginx container per GUI. Same Helm pattern.
- **Mobile apps:** CI builds (.ipa for iOS via Fastlane match + gym; .aab for Android via Gradle + Play Publisher). Signing infrastructure documented; we do not actually publish to App Store / Play Store.
- **Local dev:** `docker-compose up` continues to bring up the full stack (Kafka + Postgres + 6 backend services + identity service + admin web + client web). Mobile apps point to `http://localhost:<port>` via build-config flag.

This honours Guideline 9 (production-ready architecture with documented upgrade paths) — local infra is simple, production path is explicit.

---

## D5. Scope of GDPR self-service

**Decision:** Full implementation, customer-facing.

- **Right to access (Art. 15):** customer can trigger `POST /api/v1/customer/gdpr/export`. Returns ZIP within 30 days containing accounts, transactions (full history), consents, fraud-alert involvements (anonymised counterparts), audit-log entries about the customer.
- **Right to erasure (Art. 17):** customer can trigger `POST /api/v1/customer/gdpr/erase`. The backend already has the crypto-shredding mechanism (per-account AES-256-GCM keys — see `HIGHLIGHTS.md` "GDPR Crypto-Shredding"). The customer-facing trigger executes the existing erasure cascade across all six services. Tension with AML 5-year retention is resolved by crypto-shredding (events remain, PII unrecoverable).
- **Right to rectification (Art. 16):** profile-edit screens.
- **Right to withdraw consent (Art. 7(3)):** consent-revocation screens (PSD2 consents, marketing preferences).
- **Admin-side execution & log:** compliance officer GUI shows GDPR queue, can trigger erasure on customer's behalf if request was offline, audits every key deletion.

This satisfies the project's existing GDPR architecture (which had the mechanism but not the customer-facing trigger).

---

## D6. BankID realism

**Decision:** Pluggable abstraction, with **simulated BankID as the default** and a documented production upgrade path to real BankID merchant integration.

**Implementation:**
- An `ScaProvider` SPI in `jrpay-identity-service` with two implementations:
  - `SimulatedBankIdScaProvider` (default; portfolio-friendly; deterministic test users; no merchant agreement needed)
  - `RealBankIdScaProvider` (skeleton + adapter contract; requires real BankID merchant credentials at runtime)
- Switched via `jrpay.identity.sca.provider=simulated|bankid`.
- `TotpScaProvider` retained as fallback for non-Norwegian users.

**Rationale:**
- This mirrors the project's existing pattern for `KeyProvider` (env-vars → KMS) and other production-upgrade paths (Guideline 9).
- Honest for the portfolio: we do not claim a real BankID integration we cannot back up. The architecture is unambiguously *able* to plug it in.
- The simulation is realistic enough for full E2E testing of all SCA flows (dynamic linking, lockout, exemption decisions).

---

## D7. Notification gateways

**Decision:** Real send-paths via pluggable adapters; **stub adapters as the default** with the production credential plug-in points clearly defined. Same Guideline-9 pattern.

**Channels:**
- **In-app inbox** — first-class channel. Always on. Implemented by a new `notifications` table (own service or hosted by identity service) and an SSE feed to web GUIs / push notifications to mobile.
- **Email** — `EmailNotificationAdapter` SPI. `LoggingEmailAdapter` (default; prints to logs and writes to `target/notifications/email/`). `SmtpEmailAdapter` (configurable host/port/credentials; production).
- **SMS** — `SmsNotificationAdapter` SPI. `LoggingSmsAdapter` (default). `TwilioSmsAdapter`/`LinkMobilitySmsAdapter` (skeleton; production).
- **Push (mobile)** — `PushAdapter` SPI. `LoggingPushAdapter` (default). `ApnsPushAdapter` (iOS production). `FcmPushAdapter` (Android production).

**Why stubs by default:** to remain Guideline 9 compliant and to make the smoke tests run anywhere without external credentials. The full integration tests verify the SPI contract. The end-to-end production-path tests run only when credentials are supplied via environment.

---

## Open follow-ups (deliberately deferred)

The following are flagged for revisit at the end of Phase 1 (architecture review) — they are not blockers for Phase 0 work:

| Topic | Defer reason |
|-------|--------------|
| Mobile app store distribution channel (TestFlight / internal track only? full publication?) | Not needed until Phase 8 |
| FX live-rate provider | Multi-currency in MVP scope, but a static rate table is acceptable; live rates are a small follow-up |
| Real BankID merchant agreement | Out-of-band; would only flip the `SimulatedBankIdScaProvider` switch |
| Admin web SSO with corporate IdP (SAML/OIDC) | Identity service abstraction allows this later; not in MVP scope |
