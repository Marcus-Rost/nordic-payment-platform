# JRPay GUI Extension — Architectural Design

*Version 1.0*
*Date: 2026-04-23*

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Architectural Principles](#2-architectural-principles)
3. [System Decomposition](#3-system-decomposition)
4. [Authentication and Authorisation Architecture](#4-authentication-and-authorisation-architecture)
5. [Frontend Architecture (Web GUIs)](#5-frontend-architecture-web-guis)
6. [Frontend Architecture (Mobile GUIs)](#6-frontend-architecture-mobile-guis)
7. [Backend Service Additions](#7-backend-service-additions)
8. [Contract Architecture (OpenAPI as the Source of Truth)](#8-contract-architecture-openapi-as-the-source-of-truth)
9. [Cross-Platform Parity Architecture](#9-cross-platform-parity-architecture)
10. [Real-Time Status and Notifications](#10-real-time-status-and-notifications)
11. [Security Architecture](#11-security-architecture)
12. [Privacy Architecture (GDPR Self-Service)](#12-privacy-architecture-gdpr-self-service)
13. [Observability Architecture](#13-observability-architecture)
14. [Testing Architecture](#14-testing-architecture)
15. [Deployment Architecture](#15-deployment-architecture)
16. [Architectural Decision Log](#16-architectural-decision-log)

---

## 1. Introduction

### 1.1 Purpose

This document describes the architectural design of the JRPay GUI extension — four user-facing artefacts (admin web, client web, client iOS, client Android) plus one supporting backend service (`jrpay-identity-service`). It builds on the existing backend architecture documented in `architecture/ARCHITECTURE_DESIGN.md` and translates `gui/requirements/gui_requirements.md` into structural decisions.

### 1.2 Scope

In scope:
- Authentication, session, and authorisation architecture across all four GUIs
- Frontend architecture per platform (web stack, iOS stack, Android stack)
- Backend additions required to power the GUIs (REST, SSE, OAuth2 server)
- Contract architecture (OpenAPI 3.1 as the single source of truth)
- Cross-platform consistency mechanisms
- Security, privacy, observability, and testing architecture for the GUI tier
- Deployment architecture (Kubernetes manifests, web hosting, mobile build pipeline)

Out of scope:
- Detailed visual design (Figma/Sketch artefacts produced separately under `gui/design/`)
- Mobile app store distribution mechanics (engineered in Phase 8, but not architected here)
- Real BankID merchant integration (documented as plug-in point per D6)
- Cloud-provider-specific deployment details

### 1.3 Key Constraints

| Constraint | Source | Impact |
|-----------|--------|--------|
| Three client variants must implement identical user narratives | User requirement, `D1`, `CLI-F100..104` | Drives contract-first architecture, shared i18n and design tokens, layer-5 cross-platform tests |
| Backend has no existing UI, no Swagger, no PSD2 customer endpoints, no fraud-case projection, no audit-query API | Backend survey, 2026-04-23 | Phase 2 backend gap closure precedes Phase 3+ GUI work |
| Production-ready end state (no mocks against the database/Kafka) | User requirement, project guideline | All E2E tests run against the real `docker-compose` stack; no GUI is "done" until validated against real backend |
| Quality over speed | User feedback memory | Methodology is requirements → architecture → contracts → review → implement → 4-layer tests, mirroring backend discipline |
| EU IPR Art. 5c (VoP) and Art. 5d (sanctions) place hard requirements on user-facing flows | EU IPR | Drives `CLI-F22` VoP traffic-light flow and `ADM-F20` separate sanctions queue |
| PSD2 RTS Art. 5 requires dynamic linking of SCA to amount+payee | PSD2 RTS | Drives `GUI-SEC-08` and the SCA challenge component design |
| EU EAA accessibility (WCAG 2.1 AA) | EU 2019/882 | Drives `GUI-A11Y-01..07` and component-library choices (Radix Primitives) |

### 1.4 Related Documents

| Document | Location | Relationship |
|----------|----------|--------------|
| Backend system requirements | `requirements/system_requirements.md` | Existing constraints the GUIs sit on top of |
| Backend architecture design | `architecture/ARCHITECTURE_DESIGN.md` | Architecture principles inherited by the GUI tier |
| Backend interface contracts | `architecture/INTERFACE_CONTRACTS.md` | Existing canonical contracts; extended with GUI-related additions in Phase 2 |
| GUI requirements | `gui/requirements/gui_requirements.md` | What the GUIs must do |
| GUI architectural decisions | `gui/architecture/ARCHITECTURE_DECISIONS.md` | Options analysis for each decision |
| Locked decisions | `gui/PROJECT_DECISIONS.md` | The seven up-front decisions (D1..D7) referenced throughout |

---

## 2. Architectural Principles

These principles inherit from the backend's principles (P1..P12 in `architecture/ARCHITECTURE_DESIGN.md`) and add five GUI-specific principles.

### GP1: Contract-First, Not UI-First

The OpenAPI 3.1 specification is the single source of truth across all four GUIs. Typed clients are generated; no GUI hand-codes a request body or response shape. This prevents the GUI tier from drifting from the backend and makes the four GUIs structurally consistent with each other.

*Rationale: With three feature-parity client variants, ad-hoc clients would diverge under modification. Generated clients fail at compile time when contracts change.*

### GP2: Narrative Parity, Not Code Sharing

The three client variants share **narratives** (catalogued in use case documents), **strings** (one ICU catalogue), and **tokens** (one design token set). They do not share UI code. Each variant uses its platform's native idioms.

*Rationale: Cross-platform UI frameworks (React Native, Flutter) sacrifice platform fit; the user explicitly asked for native. Sharing at the narrative layer rather than the code layer gives consistency without compromising native quality.*

### GP3: Structural Enforcement of Security UX

Security properties that depend on UI behaviour (anti-enumeration on VoP `NO_MATCH`, dynamic linking of SCA, PII reveal logging) must be enforced by the architecture, not by developer discipline. The API contract must make insecure behaviour impossible (e.g., `actualName` field absent in `NO_MATCH` response means there is no field to render).

*Rationale: Mirrors backend principle P1 (security by construction). UI is the natural place to bypass these protections by accident; the architecture must prevent it.*

### GP4: Async Status, Sync Queries

Customer-facing real-time status (instant payment progress, fraud holds, R2P arrival) is delivered via Server-Sent Events on web and push notifications on mobile, fed by Kafka topic projections. Queries (account list, transaction history, case detail) are synchronous REST.

*Rationale: Mirrors backend principle P5. Polling for instant-payment status would either miss the SLA (slow polling) or hammer the backend (fast polling).*

### GP5: Defence-in-Depth UX

User-visible defences (session warnings, rate-limit countdowns, audit confirmations on sensitive actions) reinforce server-side controls without replacing them. The GUI is never the only line of defence, but it is *a* line.

*Rationale: Defence in depth (backend principle P9) extends to the user surface. A user who sees that an action will be audited is less likely to misuse it.*

---

## 3. System Decomposition

### 3.1 New Components

| Component | Type | Module / Repo | Owner |
|-----------|------|---------------|-------|
| Identity Service (A7) | Spring Boot service | `implementation/jrpay-identity-service/` | Backend |
| Audit Query Service | Spring Boot service | `implementation/jrpay-audit-query/` | Backend |
| Notifications Service | Spring Boot service | `implementation/jrpay-notifications/` | Backend |
| Admin Web (G1) | React SPA | `implementation/admin-web/` | Frontend |
| Client Web (G2) | React SPA | `implementation/client-web/` | Frontend |
| Client iOS (G3) | SwiftUI app | `implementation/client-ios/` | Mobile |
| Client Android (G4) | Compose app | `implementation/client-android/` | Mobile |
| API specs | OpenAPI 3.1 docs | `implementation/api-specs/` | Backend (specs) + Frontend (codegen consumers) |
| i18n catalogue | ICU MessageFormat | `gui/i18n/` | Shared |
| Design tokens | JSON | `gui/design-tokens/` | Shared |

### 3.2 Existing Components Extended

| Component | What changes |
|-----------|--------------|
| A1 (Account Ledger) | New admin endpoints: search, freeze, unfreeze, close, reveal-pii. Tightened scope checks on existing endpoints. |
| A3 (Bill Payment) | New REST surface: customer R2P endpoints, mandate CRUD, beneficiary CRUD. |
| A4 (PSD2 API) | New customer-facing PSD2 endpoints (account list, transactions, payment initiation). Existing TPP-facing endpoints unchanged. SSE endpoint for instant payment status. |
| A5 (Fraud Detection) | Gains a Postgres `fraud` schema and a projection consumer that materialises `payment.fraud-check-response` events into queryable case records. New REST surface for case management. Stateless scoring pipeline preserved. |
| Platform Starter | Add CORS policy module (deny by default; explicit allow per service for the GUI origins). Add SSE filter that propagates W3C Trace Context across the SSE boundary. |

### 3.3 Inter-Component Diagram

```
                         ┌─────────────────────────────────┐
                         │      jrpay-identity-service     │
                         │  (Spring Authorization Server)  │
                         │   ScaProvider SPI: Sim BankID   │
                         │     │ │ │  (TOTP fallback)      │
                         └─────┴─┴─┴───────────────────────┘
                                ▲                   ▲
                                │ JWT (RS256)       │ JWT
                                │ JWKS              │
                                │                   │
       ┌────────────────────────┼───────────────────┼────────────────────────┐
       │                        │                   │                        │
       ▼                        ▼                   ▼                        ▼
┌─────────────┐         ┌─────────────┐     ┌─────────────┐         ┌──────────────┐
│ Admin Web   │         │ Client Web  │     │  iOS App    │         │ Android App  │
│ (G1, React) │         │ (G2, React) │     │  (G3)       │         │  (G4)        │
└──────┬──────┘         └──────┬──────┘     └──────┬──────┘         └──────┬───────┘
       │                       │                   │                       │
       │ REST + SSE            │ REST + SSE        │ REST + APNs push      │ REST + FCM push
       │                       │                   │                       │
       └───────────┬───────────┴─────────┬─────────┴───────────┬───────────┘
                   │                     │                     │
        ┌──────────▼─────────────────────▼─────────────────────▼─────────────┐
        │                    Backend services (REST/SSE)                     │
        │                                                                    │
        │  A1 (admin) | A3 (R2P/mandate) | A4 (customer) | A5 (cases)        │
        │  jrpay-audit-query   | jrpay-notifications                         │
        └──────┬─────────────────────┬─────────────────────┬─────────────────┘
               │                     │                     │
               └────────── Kafka ────┴───── PostgreSQL ────┘
                          (existing topics + 1 new:
                           notifications.outbound)
```

---

## 4. Authentication and Authorisation Architecture

### 4.1 Identity Service Topology

The identity service (A7) is a Spring Authorization Server (SAS 1.x) hosted as a separate Spring Boot module. It exposes:

| Endpoint | Purpose |
|----------|---------|
| `/oauth2/authorize` | Authorization endpoint (Auth Code + PKCE) |
| `/oauth2/token` | Token endpoint |
| `/oauth2/jwks` | JSON Web Key Set (signing keys) |
| `/oauth2/revoke` | Token revocation (RFC 7009) |
| `/oauth2/introspect` | Token introspection (RFC 7662) |
| `/.well-known/openid-configuration` | OIDC discovery |
| `/login` | Login UI (Spring-rendered, unstyled fallback; replaced per audience by the calling GUI's hosted login experience for customer/admin flows) |
| `/sca/start` / `/sca/{sessionId}/verify` | SCA orchestration |
| `/.well-known/jwks.json` | mirror for caches |

**Audience separation:** Three OAuth2 clients are registered:
- `client-web` (PKCE; redirect to client-web origin)
- `admin-web` (PKCE; redirect to admin-web origin)
- `mobile-clients` (PKCE; deep link to `nopayments://auth/callback`)
- TPP clients (existing, registered out-of-band per PSD2)

Each client has its allowed scope set restricted at the IdP layer so that a customer-facing client cannot request `fraud:manage` even if a malicious request were attempted.

**SCA provider:** `ScaProvider` interface with implementations:

```java
public interface ScaProvider {
    ScaChallenge start(ScaContext ctx);
    ScaResult verify(ScaSession session, ScaResponse response);
    boolean supportsDynamicLinking();
}
```

`SimulatedBankIdScaProvider` is the default; `RealBankIdScaProvider` is the production plug-in (skeleton only). `TotpScaProvider` is fallback. The provider is selected by `jrpay.identity.sca.provider`.

### 4.2 Token Architecture

- **Access token:** JWT, RS256, 15-min TTL, contains `sub` (user id), `aud` (client id), `scope`, `acr` (Authentication Context Class Reference: `loa1` = password only, `loa3` = SCA-passed), `dl_hash` (dynamic-linking hash if SCA bound to a payment).
- **Refresh token:** Opaque, 30-day rolling TTL, single-use (rotation on every refresh), stored hashed in `identity.refresh_tokens`. Optional DPoP binding for phase 2.
- **ID token (OIDC):** Issued only for clients that request `openid` scope; carries display name only, no PII.

### 4.3 Dynamic-Linking Flow (PSD2 RTS Art. 5)

The canonical form of the dynamic-linking hash — algorithm, input schema per operation class, encoding, and field rules — is specified in `gui/architecture/DYNAMIC_LINKING.md`. All components (GUI, identity service, A4, A5, A1) compute the hash per that document. This section gives the flow overview only; the hash formula is authoritative in `DYNAMIC_LINKING.md`, not here.

```
GUI                  A4                IdP                  GUI
 │  initiate payment  │                  │                   │
 │ ─────────────────▶ │                  │                   │
 │                    │  request SCA(canonical inputs)       │
 │                    │ ───────────────▶ │                   │
 │                    │                  │  dl_hash = hex(SHA256(JCS(inputs)))
 │                    │ ◀── scaSessionId+dl_hash ─────────── │
 │ ◀── scaSessionId+dl_hash + display(amount,creditor) ──── │
 │   (GUI recomputes and compares; mismatch = UC-G51 AF-01)  │
 │  user enters TOTP / completes BankID                     │
 │ ───────────────────────────────────▶ /sca/verify(sess,resp)
 │                                       │  verify; if ok, mint step-up token
 │                                       │  with claim dl_hash=<>
 │ ◀────── step-up token + access ────── │                   │
 │ submit payment with token              │                   │
 │ ───────────────▶ │                                         │
 │                  recomputes dl_hash over actual request    │
 │                  verifies token.dl_hash == recomputed      │
 │                  proceeds only if equal                    │
```

If the GUI is tampered to display different values than what the SCA proof was bound to, A4 rejects the payment with `403 urn:problem:dynamic-linking-mismatch`. See also `gui/architecture/DYNAMIC_LINKING.md` for the 10-class input schema, encoding rules (RFC 8785 JCS + NFC + amount-as-string), and test fixtures.

### 4.4 Scope and Role Mapping

Defined in `gui/PROJECT_DECISIONS.md` D2. Roles map to scope bundles in `identity.user_roles`. The mapping is loaded into the access token's `scope` claim at issue time. Scope checks are enforced at every backend endpoint via Spring Security `@PreAuthorize("hasAuthority('SCOPE_<name>')")`.

### 4.5 Session Architecture

Customer GUIs use a refresh-token rotation pattern:
1. Initial login → access (15 min) + refresh (30 days)
2. On 401 from API, GUI silently refreshes using `/oauth2/token` grant_type=refresh_token
3. On idle 15 min of no API activity, GUI shows session-expiry warning at T-60s
4. On hard cap (8 h admin / 24 h customer), GUI forces re-login

Admin GUI additionally requires SCA on every `*:manage` action regardless of session age (token must carry `acr=loa3` issued in the last 5 minutes for the action's resource).

---

## 5. Frontend Architecture (Web GUIs)

### 5.1 Module Layout

```
implementation/admin-web/
  src/
    app/                         # Routes (TanStack Router file-based)
    features/                    # Feature folders (fraud-cases/, sanctions/, accounts/, ...)
      <feature>/
        ui/                      # Components
        api/                     # Generated API hooks (TanStack Query wrappers)
        flows/                   # Multi-step orchestration (state machine)
        copy/                    # i18n keys for this feature
        __tests__/
    shared/
      auth/                      # OAuth2 + SCA components
      ui/                        # Buttons, dialogs, table, masking, traffic-light card
      i18n/                      # ICU runtime, locale loader
      observability/             # OTel browser SDK init, error boundary
    main.tsx
  api-client/                    # Generated by openapi-typescript (gitignored or committed; both options OK; we commit for reproducibility)
  e2e/                           # Playwright specs (live docker-compose)
```

`client-web/` mirrors this layout.

### 5.2 State Management

- **Server state** → TanStack Query. Cache key per resource. Invalidations triggered by mutations and by SSE notifications.
- **Auth state** → React context backed by a slim store; access token in memory, refresh token never stored in JS-accessible storage (cookie with `Secure`+`HttpOnly`+`SameSite=Strict` set by the IdP-callback handler).
- **Form state** → react-hook-form + zod. Schemas mirror the OpenAPI request schemas (a small adapter generates zod schemas from OpenAPI).
- **No global Redux/MobX/Zustand store.** Server state belongs in TanStack Query; ephemeral UI state belongs in components.

### 5.3 Component Library Choice

- **Tailwind CSS** for styling.
- **Radix Primitives** (or HeadlessUI) for unstyled-but-accessible primitives (Dialog, Combobox, Listbox, Switch, Tooltip).
- **Custom components** built on these primitives, using the design-token CSS variables.

Rationale for not using MUI / Ant / Mantine: those bring an opinionated visual identity that we'd fight; our flows (VoP traffic-light, SCA dynamic-linking proof) have specific UX requirements that benefit from finer control.

### 5.4 Routing

TanStack Router with file-based routes, type-safe. Route guards implemented as a high-level `<RequireScope scope="fraud:manage">` wrapper that:
1. Reads the current access token's scope claims
2. Renders the route if authorised
3. Renders a 403 page (no client-side redirect) if not — defence-in-depth GUI-SEC-14

### 5.5 SSE Integration

```ts
function usePaymentStatusStream(paymentId: string) {
  // EventSource with auto-reconnect + traceparent propagation
  // On reconnect, GET /payments/{paymentId} once to reconcile (GUI-RES-05)
  // Returns { status, transitions, lastUpdate }
}
```

`EventSource` cannot send custom headers, so authentication uses a one-time `streamToken` query parameter issued by `POST /api/v1/customer/.../stream-token`. Token properties and conventions are pinned in `gui/architecture/SSE_CONVENTIONS.md`: ≥ 128 bits entropy, 5-min TTL, single-use (consumed on connect), scope-bound to `{user, audience, resource-if-any}`, stored server-side only as `hash(streamToken)` in Redis, logged only as the hash. Reconnect follows the standard exponential-backoff + reconciliation pattern (GUI-RES-05).

### 5.6 Design Tokens

`gui/design-tokens/tokens.json` defines:
- Colour palette (Nordic-inspired; primary, surface, semantic-success/warning/error, fraud-score-gradient)
- Typography scale (Inter for sans; SF Mono for IBANs)
- Spacing scale (4px base)
- Radii, elevation
- Motion durations and easings

Build script generates:
- `tailwind.config.cjs` (web)
- `Tokens.swift` enum (iOS)
- `Tokens.kt` object (Android)

---

## 6. Frontend Architecture (Mobile GUIs)

### 6.1 iOS Module Layout

```
implementation/client-ios/
  JRPayClient.xcworkspace
  JRPayClient/
    App/                          # Entry, root navigation, theme bootstrap
    Features/                     # One folder per feature (Accounts, Payments, R2P, ...)
      <Feature>/
        Views/                    # SwiftUI views
        ViewModels/               # @Observable view models
        UseCases/                 # Domain logic, talk to API client
        Tests/
    Shared/
      Auth/                       # AppAuth-iOS integration, Keychain
      Networking/                 # URLSession config, cert pinning, OpenAPI generated client
      I18n/                       # ICU adapter, NSLocalizedString bridge
      Tokens/                     # Generated from gui/design-tokens
      DesignSystem/               # SwiftUI components built on tokens
    Resources/
      en.lproj/  nb.lproj/        # Localised strings
  JRPayClientUITests/
  fastlane/                       # Build, sign, distribute
```

### 6.2 Android Module Layout

```
implementation/client-android/
  app/
    src/main/
      java/com/jrpay/client/
        app/                      # MainActivity, Compose theme, navigation
        features/                 # One per feature
          <feature>/
            ui/                   # Compose screens
            viewmodel/
            domain/
        shared/
          auth/                   # AppAuth-Android, EncryptedSharedPreferences, BiometricPrompt
          network/                # OkHttp + cert pinning + OpenAPI generated client
          i18n/                   # ICU adapter
          tokens/                 # Generated from design-tokens
          designsystem/           # Composables on tokens
      res/values{,-nb}/strings.xml
    src/test/                     # Unit
    src/androidTest/              # Espresso E2E
  fastlane/
```

### 6.3 Networking

- iOS: `URLSession` with a custom delegate enforcing certificate pinning (SPKI hash). The OpenAPI-generated client is wrapped to add `Authorization: Bearer <token>` and `traceparent` headers automatically.
- Android: `OkHttp` with `CertificatePinner`. Network Security Configuration in XML for additional defence. Same wrapping pattern.

#### 6.3.1 Certificate-pinning operational procedure (Phase 1 FM.3)

Each app ships with up to **three** pinned SPKIs per host at all times: current, next, emergency. The pins are rotated on a documented annual schedule aligned with cert renewal (next-pin added to the app 60+ days before the server cert rotation; prior current-pin retired with the release that follows the rotation).

- **Compromise response:** if a pinned key is compromised, Ops switches the server to use the emergency-pin SPKI; mobile releases follow within 7 days adding a new current pin and retiring the compromised one.
- **Pin-audit CI script:** `implementation/scripts/pin-audit.sh` runs on every release build and verifies the app bundle's pins include the currently-valid server cert SPKI. Fails the release if not.
- **Emergency unbrick mode:** if *all* pinned SPKIs fail (cert and emergency both compromised simultaneously — effectively never), the server exposes a version-gated endpoint `/api/v1/public/force-update` without pinning. The mobile app falls back to this single endpoint — and only to display a "force update" UI — if all pins fail. This is the last-resort unbrick path; documented trade-off. A separate signing key holds these emergency cert releases.
- **Session with an iOS user when pin rotation occurs mid-session:** existing TLS sessions continue; subsequent fresh connections use the new pin. No customer impact if either current or next pin matches.
- **AppAuth / ASWebAuthenticationSession cookie sharing:** preferred default is `prefersEphemeralWebBrowserSession = false` so the identity-service session cookie is shared with Safari (better UX). Documented trade-off (FM.12).

### 6.6.1 Backup exclusion (Phase 1 FM.2)

The offline-read cache (UC-G28) must never appear in cross-device backups:

- **iOS:** the CoreData SQLite file sets `NSURLIsExcludedFromBackupKey = true` at creation; the backing file path is `~/Library/Application Support/<bundle-id>/cache.sqlite`. The encryption key derived for SQLCipher is stored in Keychain with `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` (which also excludes it from backup); restore-to-new-device yields an empty cache plus no usable key.
- **Android:** `android:allowBackup="false"` in the release manifest. Alternative: provide explicit `android:fullBackupContent` XML excluding the cache DB. Either way the Room/SQLCipher file is not backed up. The encryption key is held in the Android Keystore with `setUserAuthenticationRequired(true)`.

### 6.7 Privacy Manifests and Data Safety (Phase 1 FM.9)

iOS 17+ and Play Console's Data Safety both require explicit declarations of data handling. These artefacts ship with the app:

- **iOS `PrivacyInfo.xcprivacy`:** declares required-API reasons for `UserDefaults` (CA92.1), file timestamp (C617.1), boot time (35F9.1), disk space (E174.1). No IDFV, no third-party tracking SDKs. `NSPrivacyTracking=false`. A CI check verifies the file matches a pinned template in `implementation/client-ios/PrivacyInfo.template.xcprivacy` — deviations fail the release build.
- **Android Data Safety:** prepared answers for the Play Console Data Safety form, version-controlled in `implementation/client-android/datasafety.md`. Data collected: Financial info (accounts, transactions — service delivery). Data shared: none. No Ads ID, no Analytics SDKs. Encrypted in transit; encrypted at rest.
- **Both platforms:** privacy manifest is consistent with the privacy notice (UC-G45) and UC-G24 data categories.

### 6.4 Push Notifications

- iOS: APNs token registered at first launch (after permission grant). Token sent to `POST /notifications/devices`. Notifications include a `deepLink` payload that the app routes to.
- Android: FCM token registered on first launch and on token-rotation callback. Same backend endpoint. Notification channels per category (payment-status, fraud-alert, r2p-arrival, consent-expiry, gdpr-status) so users can configure per-category importance in OS settings.

### 6.5 Biometric Unlock

- iOS: `LocalAuthentication.LAContext().evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, ...)`. On success, retrieve the refresh token from Keychain (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`).
- Android: `BiometricPrompt` with `setUserAuthenticationRequired(true)` on the Keystore key that wraps the refresh token in `EncryptedSharedPreferences`.

### 6.6 Offline Behaviour (CLI-NF07)

- Both apps cache the account list and the last 30 days of transactions in a local read-only store (Core Data on iOS; Room on Android). Cache is per-user, encrypted with a key derived from the device-bound credential.
- Read views render from cache when offline with a "Last updated <time>" indicator and a banner "You are offline".
- Write operations are blocked offline with a clear message; no offline queue (a payment that depends on real-time VoP and SCA cannot be safely queued offline).

---

## 7. Backend Service Additions

### 7.1 jrpay-identity-service (A7)

| Aspect | Detail |
|--------|--------|
| Port | 8097 |
| DB | Postgres `identity` |
| Tables | `users`, `roles`, `user_roles`, `sca_factors`, `sca_sessions`, `oauth2_clients`, `oauth2_consents`, `refresh_tokens`, `audit` |
| Kafka | Produces to `audit.events` (existing). Consumes nothing. |
| Dependencies | Spring Boot 3.x, Spring Authorization Server 1.x, Spring Security, Postgres, Flyway, jrpay-security-starter, jrpay-domain-model |

### 7.2 jrpay-audit-query

| Aspect | Detail |
|--------|--------|
| Port | 8098 |
| DB | Postgres `audit` |
| Tables | `audit_events` (projection of `audit.events` topic), `audit_checkpoints` (mirror of `audit.checkpoints`), `pii_reveals` (separate table for PII-access logs per GUI-SEC-15) |
| Kafka | Consumes `audit.events`, `audit.checkpoints`. Produces nothing. |
| REST | Admin-only search and verification endpoints (Section 6.5 of requirements) |

### 7.3 jrpay-notifications

| Aspect | Detail |
|--------|--------|
| Port | 8099 |
| DB | Postgres `notifications` |
| Tables | `notifications` (per-user inbox), `preferences` (per-user channel toggles), `devices` (APNs/FCM tokens), `outbound_log` |
| Kafka | Consumes `payment.status-updates`, `payment.fraud-check-response` (subset relevant to customer), `r2p.arrived`, `consent.expiring`, `gdpr.status-changed`, `account.freeze-commands` (filtered for affected customer), all existing or new application events to be added in `INTERFACE_CONTRACTS.md` extension. Produces `notifications.outbound` (new topic) for adapter dispatch. |
| SSE | `/api/v1/customer/notifications/stream` per user |
| Adapters | Email (Logging/SMTP), SMS (Logging/Twilio/LinkMobility), Push (Logging/APNs/FCM) — D7 |

### 7.4 A5 case-management addition

A new Postgres `fraud` schema added to A5:

```sql
CREATE TABLE fraud_cases (
  case_id           UUID PRIMARY KEY,
  transaction_id    VARCHAR(36) NOT NULL,
  account_id        VARCHAR(34) NOT NULL,
  amount            NUMERIC(18,2) NOT NULL,
  currency          VARCHAR(3) NOT NULL,
  fraud_score       NUMERIC(4,3) NOT NULL,
  triggered_rules   TEXT[],
  vop_result        VARCHAR(20),
  status            VARCHAR(20) NOT NULL,           -- OPEN, ASSIGNED, INVESTIGATING, CONFIRMED_FRAUD, DISMISSED
  assignee          VARCHAR(50),
  decision_notes    TEXT,
  created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
  resolved_at       TIMESTAMP WITH TIME ZONE,
  sla_due_at        TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ON fraud_cases (status, sla_due_at);
CREATE INDEX ON fraud_cases (assignee, status);
```

A new `FraudCaseProjector` consumes `payment.fraud-check-response` and `payment.fraud-check-request` to materialise cases. The stateless scoring pipeline is preserved exactly as today; the projector is a separate concern with its own consumer group (`a5-case-projection`).

`sanctions_hits` and `false_positive_cache` tables follow the same pattern, fed by hits emitted from the existing scoring pipeline (additionally publishes `sanctions.hits` topic — new).

### 7.5 A4 customer endpoints

A4 gains a customer-facing controller layer that maps requests to:
- A1 REST (read accounts/balances/transactions on the customer's behalf, scoped to their accounts via the access token's `sub` and the consent table)
- Kafka producer (publishes `payment.instructions` to A2; identical contract as TPP-initiated payments)
- A6 REST (VoP pre-flight)

A4 owns the SSE endpoint for payment status, with a `PaymentStatusFanoutService` that consumes `payment.status-updates` and broadcasts to subscribed customer SSE connections (per-user filtering).

### 7.6 A3 customer endpoints

A3 gains a customer-facing controller layer covering R2P inbox, mandate CRUD, beneficiary CRUD. A new `customer_owns_iban` check (against A1 via REST) gates that the customer can only see/act on R2Ps and mandates targeting their own IBANs.

### 7.7 A1 admin endpoints

A1 gains an admin controller bound to admin scopes. The PII-reveal endpoint emits a `audit.pii-revealed` audit event with the operator id, target account, fields revealed, and reason claim.

### 7.8 New Kafka Topics Inventory (Phase 1 FI.2 / FC.4 / FC.7 / FC.15)

The initiative introduces the following new Kafka topics beyond `notifications.outbound`. Each will receive a full schema, ACL entry, partition key, retention, and consumer-group registration in `architecture/INTERFACE_CONTRACTS.md` during Phase 2.

| Topic | Producer | Consumer(s) | Partition key | Retention | Purpose |
|-------|----------|-------------|---------------|-----------|---------|
| `notifications.outbound` | notifications service | email/SMS/push adapter dispatchers | `userId` | 7 days | Adapter dispatch queue |
| `sanctions.hits` | A5 | notifications, A5 `FraudCaseProjector` | `paymentId` | 30 days | Materialises UC-G34 queue |
| `r2p.arrived` | A3 | notifications | `customerId` | 30 days | Triggers inbox + push for new R2P |
| `consent.expiring` | identity service scheduler | notifications | `customerId` | 7 days | 7-day-before-expiry reminder (CLI-F73) |
| `gdpr.status-changed` | GDPR helper | notifications | `requestId` | 90 days | Fires when UC-G24 / UC-G25 request status moves |
| `status.events` | status-banner service | web GUIs (via SSE), mobile (via push fanout) | `bannerId` | 14 days | Fires when SUPERVISOR publishes a banner (UC-G42 §6.5) |
| `csp.violation` | backend CSP-violation receiver | audit-query | `sessionId` | 30 days | Structured CSP violation reports from web GUIs (FS.2) |
| `account.customer-registered` | upstream CDD / A4 | identity service, notifications | `customerId` | 90 days | Customer ↔ account binding events |
| `audit.pii-revealed` | A1 admin controller | audit-query, ops alerting | `operatorId` | 5 years | Operator PII reveal audit (UC-G36 / GUI-SEC-15) |
| `session.revoked` | identity service | all resource servers (via Redis broadcast), SSE session-stream | `userId` | 30 minutes | FS.5 fast revocation propagation |

This list supersedes the "+1 new" claim in §3.3.

Existing topics reused (unchanged in schema, extended in consumer set):
- `audit.events`, `audit.checkpoints`
- `payment.instant-instructions`, `payment.instructions`
- `payment.status-updates`, `payment.settlement-events`
- `payment.fraud-check-request`, `payment.fraud-check-response`
- `account.freeze-commands`

The ACL model extends `reviews/step1/security/` FS.5.2: new topics inherit the deny-by-default policy; producer/consumer identities are listed above.

---

## 8. Contract Architecture (OpenAPI as the Source of Truth)

### 8.1 Spec Location

```
implementation/api-specs/
  identity.yaml
  customer-accounts.yaml          (A4 customer-facing)
  customer-payments.yaml          (A4 customer-facing + SSE)
  customer-r2p.yaml               (A3)
  customer-mandates.yaml          (A3)
  customer-beneficiaries.yaml     (A3)
  customer-consents.yaml          (A4: PSD2 consents + VRP consents — Phase 1 FI.1)
  customer-notifications.yaml     (jrpay-notifications)
  customer-gdpr.yaml
  customer-profile.yaml           (A4 / identity service: profile edit, email/phone verify, language — FI.1)
  customer-privacy.yaml           (privacy notice + consent-log — FI.1)
  admin-accounts.yaml             (A1 admin)
  admin-fraud.yaml                (A5)
  admin-sanctions.yaml            (A5)
  admin-payments.yaml             (A2 investigation, A2 returns)
  admin-reconciliation.yaml       (A1)
  admin-audit.yaml                (jrpay-audit-query)
  admin-gdpr.yaml
  admin-status.yaml
  admin-users.yaml                (identity service: operator admin — FI.1)
  shared-schemas.yaml             (all enums listed in gui/architecture/SHARED_ENUMS.md + dynamic-linking input schemas — FI.8, FC.7)
```

Companion specification documents (authoritative for specific conventions):
- `gui/architecture/DYNAMIC_LINKING.md` — dynamic-linking canonical hash spec (FC.1)
- `gui/architecture/PROBLEM_TYPES.md` — RFC 9457 Problem `type` registry (FI.5)
- `gui/architecture/SSE_CONVENTIONS.md` — SSE auth, heartbeat, reconnect, event naming (FI.6)
- `gui/architecture/SIGNED_URL_CONVENTIONS.md` — signed-URL download discipline (FI.7)
- `gui/architecture/SHARED_ENUMS.md` — every shared enum value (FI.8)
- `gui/architecture/COMPONENTS_COUNTDOWN.md` — canonical countdown component (FA.4)

### 8.2 Spec Discipline

| Rule | Enforcement |
|------|-------------|
| Specs are the source of truth; controllers are generated from / validated against the spec | springdoc-openapi configured to fail at startup on mismatch |
| Specs use `$ref` into `shared-schemas.yaml` for canonical enums | Lint check on PR: `jrpay-enums-match-shared` |
| Every operation has at least one example request and response | Spectral lint |
| Every error response uses RFC 9457 Problem detail with a `type` from `PROBLEM_TYPES.md` | Spectral lint: `jrpay-problem-types-registered` |
| Every mutating endpoint (`POST` / `PATCH` / `DELETE`) requires an `Idempotency-Key: <uuidv4>` header | Spectral lint: `jrpay-idempotency-key-required` (Phase 1 FI.10) |
| Every new Kafka topic is listed in §7.8 before any service produces to it | Manual review + CI registry check |
| Breaking changes require a major version bump of the path (`/v1/` → `/v2/`) | Manual review + openapi-diff CI gate |

### 8.3 Codegen

| Target | Tool | Output |
|--------|------|--------|
| TypeScript types + fetch client (web GUIs) | `openapi-typescript` + `openapi-fetch` | `implementation/admin-web/api-client/`, `implementation/client-web/api-client/` |
| TypeScript Zod schemas (form validation) | Custom (`openapi-zod-client`) | Same folder |
| Swift client (iOS) | `swift-openapi-generator` | `implementation/client-ios/JRPayClient/Shared/Networking/Generated/` |
| Kotlin client (Android) | `openapi-generator` (`kotlin` template) | `implementation/client-android/app/src/main/java/com/jrpay/client/network/generated/` |
| Pact contract tests (consumer side per platform) | Pact JS / Pact Swift / Pact JVM | Per-platform test folders |

Codegen runs in CI before build. Generated code is committed (we choose visibility over slimness) so reviewers can see exactly what each platform consumes.

### 8.4 Versioning

- Customer-facing APIs: `/api/v1/customer/...`
- Admin-facing APIs: `/api/v1/admin/...`
- TPP-facing APIs (existing PSD2): unchanged path conventions
- Internal: not exposed to GUIs

Breaking changes follow the existing project's deprecation discipline (announce at version N, remove at N+2 minor releases minimum).

---

## 9. Cross-Platform Parity Architecture

### 9.1 Sources of Truth

| Source | Consumed by |
|--------|-------------|
| OpenAPI specs | All four GUIs |
| ICU MessageFormat catalogue (`gui/i18n/{nb,en}.json`) | All three client variants |
| Design tokens (`gui/design-tokens/tokens.json`) | All four GUIs |
| Use case documents (`gui/use_cases/UC-G*.md`) | Documentation; drives layer-5 cross-platform tests |
| Error code → user copy map (`gui/i18n/errors.json`) | All four GUIs |

### 9.2 Layer-5 Consistency Test

A new test suite (`implementation/cross-platform-tests/`) runs scripted scenarios in turn against each variant:

1. Provision a clean docker-compose stack
2. Seed a known fixture (one customer, two accounts, one merchant)
3. For each scenario:
   - Run script against client web (Playwright)
   - Run script against iOS (XCUITest in simulator)
   - Run script against Android (Espresso in emulator)
4. After each platform run, snapshot backend state (transaction history, audit log, fraud-case state)
5. Assert all three snapshots are equivalent

Scenarios cover the critical narratives: login, instant payment with VoP MATCH, instant payment with VoP NO_MATCH override, R2P accept, mandate setup, GDPR export request.

This is the structural verification of the user's "no re-training between variants" requirement.

### 9.3 i18n Catalogue Discipline

- All user-visible strings come from the catalogue.
- A lint rule (`@formatjs/cli` + custom) fails the build for hard-coded strings in JSX, SwiftUI views, and Compose composables.
- Catalogue parity check: every key in `nb.json` must exist in `en.json` and vice versa. Web, iOS, and Android resource bundles must match the catalogue 1:1. CI gate.

### 9.4 Design Token Discipline

- All web styling uses Tailwind tokens (no hard-coded colours, spacing, font sizes).
- iOS and Android views consume the generated `Tokens.swift` / `Tokens.kt` only — no `Color(hex: "#1234ff")` literals.

---

## 10. Real-Time Status and Notifications

### 10.1 Status Streaming Architecture

For instant payments, the customer must see status transitions in real time (CLI-F27, CLI-NF02). The SLA budget is 5 s end-to-end backend; 6 s end-to-end including GUI rendering.

Architecture:
1. A4 hosts `GET /api/v1/customer/payments/stream?token=<streamToken>` (SSE).
2. `PaymentStatusFanoutService` in A4 consumes `payment.status-updates` (existing topic) into per-user channels (in-memory map keyed by customer id).
3. SSE filter applies (a) per-user authentication via the one-time stream token, (b) W3C Trace Context propagation, (c) heartbeat every 15 s.
4. Client GUIs connect at login (web) or on the payment confirmation screen (mobile, where push notifications are the primary channel and SSE is a convenience).
5. On disconnect, GUI reconnects with exponential backoff and reconciles by GET on the active payment IDs (GUI-RES-05).

Mobile primary channel is push:
- Notification consumer service publishes `notifications.outbound` for each customer-facing event.
- Notifications service iterates registered devices for the customer, sends APNs / FCM payloads with deep-link routes.
- Push payloads carry only the deep link and an event id; the app fetches details via REST so PII never lives in the OS notification store.

### 10.2 Notification Categories

| Category | Trigger | Delivery |
|----------|---------|----------|
| `payment-status` | Payment SETTLED / REJECTED / HELD / RETURNED | In-app inbox + push (mobile) |
| `fraud-hold` | A5 marks customer's payment HELD | In-app inbox + push + email (always, customer cannot disable for safety) |
| `sanctions-block` | A5 BLOCKs customer payment for sanctions | Same as fraud-hold |
| `r2p-arrival` | New R2P arrives in customer's inbox | In-app inbox + push (default on, customer can disable) |
| `consent-expiry` | PSD2 consent within 7 days of expiry | In-app inbox + email |
| `gdpr-status` | Erasure / export request status change | In-app inbox + email |
| `account-freeze` | Customer's account frozen by admin | In-app inbox + push + email (always on) |
| `system-maintenance` | Operator posts a status banner | In-app inbox + push (opt-in) |

---

## 11. Security Architecture

### 11.1 Threat Model Summary

| Adversary | Goal | Mitigation |
|-----------|------|------------|
| Phishing user | Steal credentials | OAuth2 + SCA + dynamic linking; the GUI shows the IdP origin throughout login |
| Compromised browser tab (XSS) | Steal access token, execute payment | CSP `default-src 'self'`; access token in memory not localStorage; refresh token in `HttpOnly` cookie; SCA per `*:manage` action; SCA dynamic linking means the attacker cannot reuse a stolen token to issue an arbitrary payment |
| Network attacker | Read or modify traffic | TLS 1.3 mandatory; certificate pinning on mobile; HMAC on Kafka (existing) |
| Compromised mobile OS / rooted device | Extract credentials | Biometric required to release refresh token; `kSecAttrAccessibleWhenUnlockedThisDeviceOnly`; jailbreak/root detection refuses high-value scopes (GUI-SEC-12) |
| Insider in operations | Mass extract PII | PII masked by default; reveal logged with operator id and reason; supervisor approval required for repeat reveals on the same account within 24 h |
| Adversary enumerating accounts via VoP | Learn account holder names | `actualName` absent on `NO_MATCH` (server contract + GUI structural); 10 ms timing floor (existing); rate-limit on VoP per IP |
| Replayed SCA proof | Submit different payment than the user authorised | Dynamic-linking hash bound to access token; A4 verifies hash matches actual request |
| Stolen device | Operate as user | Biometric unlock; refresh token expires in 30 days; remote logout via revoke endpoint |

### 11.2 Defence-in-Depth Layers (per GUI)

| Layer | Admin Web | Client Web | Mobile |
|-------|-----------|------------|--------|
| L1 Transport | TLS 1.3 + HSTS | TLS 1.3 + HSTS | TLS 1.3 + cert pinning |
| L2 Identity | OAuth2 + PKCE + SCA per *:manage | OAuth2 + PKCE + SCA on payment | OAuth2 + PKCE + biometric + SCA on payment |
| L3 Authorization | Scope check per route + per operation | Scope check per route + per operation | Same |
| L4 Input validation | zod schemas + server validation | zod schemas + server validation | Native form validation + server validation |
| L5 Output encoding | React (auto), no `dangerouslySetInnerHTML` | Same | SwiftUI / Compose (no string injection vectors) |
| L6 Session | 15-min idle, 8-h cap, single concurrent | 15-min idle, 24-h cap, 3 concurrent | Biometric on cold start, idle re-auth |
| L7 Audit | Every *:manage action audited | Sensitive customer actions audited | Same |
| L8 Tamper detection | SRI on assets, build SHA visible | SRI on assets, build SHA visible | Code signing, integrity check on launch |

### 11.3 OWASP ASVS L2 Coverage

The GUI tier will be reviewed against OWASP ASVS L2 (the same standard the backend is reviewed against). A checklist is maintained at `gui/reviews/security_review_checklist.md` per Phase 1 review.

### 11.4 OWASP MASVS L1 Coverage (mobile)

Mobile-specific verification standard. Checklist at `gui/reviews/mobile_threat_model/REVIEW_CHECKLIST.md`.

### 11.5 Web Auth, CSRF, and CSP (Phase 1 FS.1, FS.2, FS.11)

**Credentialling.** Bearer tokens are the authoritative credential for all API requests. The OAuth2 refresh-token cookie authenticates exactly one endpoint (`/oauth2/token`); no other endpoint accepts cookie credentials. Consequences:
- `/oauth2/token` is the only endpoint where CSRF must be considered; we defend with `SameSite=Strict` + `Path=/oauth2/` + server-side `Origin:` header validation against a pinned allowlist of client-web and admin-web origins.
- All state-changing calls to resource servers (A1..A7, notifications, audit-query) carry `Authorization: Bearer <access-token>` as the sole credential; no session cookie is attached. CSRF on these calls is impossible (no implicit credential).
- The `/oauth2/authorize` endpoint is cross-site-redirect entry, so SameSite=Strict does not block it — by the time the cookie round-trips to `/oauth2/token` with a code, we are on the client's own origin.

**Content-Security-Policy (full directive set):**

```
default-src 'none';
script-src 'self' 'nonce-{{build-nonce}}' 'strict-dynamic';
style-src 'self' 'nonce-{{build-nonce}}';
img-src 'self' data:;
font-src 'self';
connect-src 'self' https://identity.{env}.jrpay.example https://api.{env}.jrpay.example;
form-action 'self' https://identity.{env}.jrpay.example;
frame-ancestors 'none';
base-uri 'none';
object-src 'none';
upgrade-insecure-requests;
report-to csp-endpoint;
```

Supporting `Report-To` header directs violation reports to `/api/v1/internal/csp-report` which produces to the `csp.violation` Kafka topic (§7.8).

**Nonce generation.** nginx generates a per-response nonce via the `nginx-module-openssl-rand` / equivalent; the nonce is substituted into every `<script>` and `<style>` tag via an `sub_filter` pass on `index.html`. Vite's build produces HTML with `{{build-nonce}}` placeholders that nginx replaces.

**Tailwind caveat.** The `style-src 'self' 'nonce-…'` directive requires Tailwind's generated CSS to be served from `/assets/...` (same origin) and any dynamic styles (e.g., from Radix Popper positioning) to carry the nonce or be moved to CSS variables. We accept this engineering cost in exchange for dropping `'unsafe-inline'` entirely.

**Mobile webview posture.** Mobile clients never embed a WebView for OAuth (per UC-G50 §12). Any WebView that does exist (future) sets:
- iOS: `WKWebpagePreferences.allowsContentJavaScript` only where required; `WKWebView.configuration.preferences.javaScriptCanOpenWindowsAutomatically = false`.
- Android: `WebSettings.setAllowFileAccess(false)`, `setAllowContentAccess(false)`, `setJavaScriptEnabled(true)` only where required + `shouldOverrideUrlLoading` filters URLs through the deep-link allowlist.

### 11.6 Rate-Limit Table (Phase 1 FS.3)

Baseline rate limits applied per endpoint. Implementation via Bucket4j or Resilience4j in the backend starter; exceeded requests return `429 urn:problem:rate-limit` with `Retry-After`.

| Surface | Limit | Scope |
|---------|-------|-------|
| `POST /oauth2/token` | 20 / min | per refresh-token family |
| `POST /oauth2/authorize` (start) | 10 / min | per IP |
| `POST /oauth2/password-reset/request` | 10 / hour | per identifier |
| `POST /oauth2/password-reset/request` | 3 / hour | per IP |
| `POST /sca/start` | 30 / min | per customer |
| `POST /sca/{sid}/verify` | 10 / session | per SCA session (IDS-F07 enforces lockout above this) |
| Customer read endpoints (accounts, transactions, notifications, consents) | 120 / min | per customer |
| `POST /api/v1/customer/payments/vop` | 30 / min | per customer |
| `POST /api/v1/customer/payments/vop` | 2 / 5s | per IP (anti-enumeration) |
| `POST /api/v1/customer/payments` | 60 / hour | per customer |
| `POST /api/v1/customer/payments/schedules` | 30 / hour | per customer |
| `POST /api/v1/customer/r2p/{id}/accept\|defer\|decline\|amend` | 60 / hour | per customer |
| `POST /api/v1/customer/mandates` | 20 / hour | per customer |
| `POST /api/v1/customer/consents/vrp` | 20 / hour | per customer |
| `POST /api/v1/customer/gdpr/export` | 1 / 7 days | per customer (business rule) |
| `POST /api/v1/customer/gdpr/erase` | 1 / 30 days | per customer |
| `POST /api/v1/customer/privacy-notice/accept` | 10 / day | per customer |
| SSE stream-token issue | 10 / min | per customer |
| Admin read endpoints | 600 / min | per operator |
| Admin mutating endpoints (`POST`/`PATCH`/`DELETE` under `/admin/`) | 120 / min | per operator |
| `POST /api/v1/admin/accounts/{id}/reveal-pii` | 5 / hour | per operator per account (sliding window; above → SUPERVISOR approval per FS.10) |
| `POST /api/v1/admin/fraud/cases/{id}/decide` (bulk) | 1 bulk of 50 / min | per operator |
| `POST /api/v1/admin/reconciliation/run` | 3 / hour | per operator |
| `GET /api/v1/public/status/stream` (SSE) | 3 concurrent connections | per IP |
| `GET /api/v1/public/status` | 60 / min | per IP |

Limits are adjustable via service config; the defaults above are the baseline the design targets.

---

## 12. Privacy Architecture (GDPR Self-Service)

### 12.1 Right to Access (Art. 15)

Flow:
1. Customer clicks "Download my data" in profile.
2. GUI calls `POST /api/v1/customer/gdpr/export`.
3. Backend creates a `gdpr_export_request` row, status `PENDING`.
4. Async job collects:
   - Account list (A1)
   - Transaction history full (A1)
   - Consents (A4 + identity service)
   - Fraud-alert involvements (A5; counterparty names anonymised per Art. 11)
   - Audit log entries about the customer (audit-query service)
5. Bundles into a ZIP containing JSON files + a human-readable PDF index.
6. Stores in object storage with a one-time signed URL valid 7 days.
7. Notifies customer via in-app inbox + email.

### 12.2 Right to Erasure (Art. 17)

Flow:
1. Customer clicks "Close account and erase my data" in profile.
2. GUI runs the prerequisites check:
   - All accounts must have zero balance (server-checked at `POST /api/v1/customer/gdpr/erase`)
   - All mandates will be revoked (warning displayed)
   - All consents will be revoked (warning displayed)
   - Access will be permanently removed
3. SCA confirmation.
4. Backend creates `gdpr_erasure_request`. Status visible to customer until completion.
5. Backend executes the existing crypto-shredding cascade across A1, A2, A3, A4, A5, A6, identity service. Per-account encryption keys deleted; events remain (AML 5-year retention) but PII is unrecoverable.
6. Customer notified in-app + email when complete (within 30 days per Art. 12(3)).

### 12.3 Tension between Erasure and AML

Resolved by crypto-shredding (existing architecture). The customer-facing GUI must explain this clearly: "Your transaction history is retained as encrypted records for 5 years (AML obligation), but your name and personal details are permanently and verifiably erased. The records cannot be linked to you."

---

## 13. Observability Architecture

### 13.1 Trace Propagation

W3C Trace Context (`traceparent`) flows from the GUI through every backend call:

- Browser GUIs: OpenTelemetry browser SDK creates a span on each user action; the `traceparent` header is set on outbound `fetch` calls automatically by the OpenAPI client wrapper.
- Mobile GUIs: OpenTelemetry mobile SDKs (otel-swift, otel-android). Same pattern.
- SSE: the SSE establish call carries `traceparent`; subsequent events are part of the same trace via a `linked` span (since SSE is long-lived).

### 13.2 Metrics

GUI-side metrics complement backend metrics:

| Metric | GUI tier | Backend complement |
|--------|----------|--------------------|
| Web Vitals (LCP, INP, CLS) | Web | n/a |
| Crash-free user rate | Mobile | n/a |
| Payment latency from tap to SETTLED | All client GUIs | Backend instant-payment p95 |
| SCA challenge success rate | All GUIs | A4 SCA metrics |
| VoP NO_MATCH override rate | Client GUIs | A6 VoP metrics, A5 fraud metrics |
| Login success rate | All GUIs | Identity service metrics |

### 13.3 Logging

- Web GUIs: structured JSON to a log-collector endpoint (`/api/v1/admin/log-relay` for browser-originated logs that need server-side correlation; PII-stripped client-side first).
- Mobile GUIs: same pattern; logs queued offline, flushed when connectivity returns.
- All logs carry `traceparent`, user id (hashed for privacy), session id.

### 13.4 RUM (Real User Monitoring)

Web Vitals reported to the existing observability stack. No third-party RUM (per GUI-PRV-01).

---

## 14. Testing Architecture

### 14.1 Pyramid

| Layer | Web | Mobile |
|-------|-----|--------|
| 1 Unit | Vitest + jsdom | XCTest / JUnit |
| 2 Component | Testing Library + MSW | ViewInspector / Compose UI Test |
| 3 Contract | Pact JS | Pact Swift / Pact JVM |
| 4 E2E | Playwright against live `docker-compose` | XCUITest / Espresso against live `docker-compose` |
| 5 Cross-platform | n/a | n/a — *new layer* (see 9.2) |

### 14.2 Test Data

A `gui-test-fixtures` module produces a deterministic seeded backend state:
- 5 customers (alice, bob, carol, dave, eve)
- 12 accounts across them
- 50 historical transactions
- 3 active mandates
- 2 active TPP consents
- 5 R2P requests (mix of statuses)
- 3 fraud cases (mix of statuses)
- 1 sanctions hit

Fixtures applied via `POST /admin/test-fixtures` (only enabled with `jrpay.test-fixtures.enabled=true`, off in production).

### 14.3 Visual Regression

- Storybook (web) per component, with chromatic / loki for visual diffs on PR.
- iOS: snapshot testing per view (Point-Free swift-snapshot-testing).
- Android: Paparazzi for Compose previews.

### 14.4 Accessibility Testing

- axe-core in component tests for web.
- iOS: Accessibility Inspector via XCUITest.
- Android: Accessibility Scanner via Espresso.
- Manual screen-reader pass per release.

### 14.5 Load Testing

A k6 script set under `implementation/load-tests/` exercises customer login + payment + status, ramping from 1 to 100 concurrent customers. Baseline: backend latency p95 ≤ 5 s; GUI-side overhead ≤ 1 s.

---

## 15. Deployment Architecture

### 15.1 Local Dev

`docker-compose up` brings up:
- Kafka (single-node KRaft)
- PostgreSQL (single instance, all schemas)
- A1, A2, A3, A4, A5, A6 (existing)
- A7 identity service (new)
- jrpay-audit-query (new)
- jrpay-notifications (new)
- admin-web (nginx serving Vite production build)
- client-web (nginx serving Vite production build)

Mobile apps run on simulator/emulator pointing to `http://localhost:<port>`.

### 15.2 Production

- Backend: Helm chart per service, deployed to Kubernetes 1.28+. Ingress via nginx-ingress with cert-manager. Service mesh optional (Istio supported but not required).
- Web GUIs: Helm chart deploys an nginx container per GUI. Public-facing via the same ingress. Static assets fingerprinted; long-cache; `index.html` no-cache.
- Mobile GUIs: built in CI (Fastlane). Distribution via TestFlight and Google Play internal track.

Per `D4`: Kubernetes is the production target; we do not bind to a cloud provider in code. Provider-specific notes in `implementation/deploy/PROVIDERS.md`.

### 15.3 Secrets

- Local dev: `.env` files (gitignored); fixtures only.
- Production: Kubernetes Secrets bound from a secrets manager (Vault / AWS Secrets Manager / Azure Key Vault) via External Secrets Operator. KeyProvider abstraction continues to handle KMS for the crypto-shredding keys.

### 15.4 CI/CD

GitHub Actions workflows:
- `backend.yml` — gradle build, tests, container image, scan (existing, extended for new services)
- `web.yml` — pnpm install, lint, typecheck, vitest, playwright (against ephemeral docker-compose), build, image, scan
- `ios.yml` — Fastlane lane: build, test (XCTest + UI tests in simulator), archive, sign
- `android.yml` — Gradle build, test, instrumented test (Compose UI test in emulator), assemble, sign
- `cross-platform.yml` — boots docker-compose, runs the layer-5 consistency suite
- `openapi.yml` — lint specs (Spectral), diff against main (openapi-diff), generate clients, run consumer Pact tests

---

## 16. Architectural Decision Log

Decisions for this initiative are recorded in `gui/architecture/ARCHITECTURE_DECISIONS.md`. The locked seven are in `gui/PROJECT_DECISIONS.md`. Subsequent decisions will be appended in numbered order (G.1, G.2, …).
