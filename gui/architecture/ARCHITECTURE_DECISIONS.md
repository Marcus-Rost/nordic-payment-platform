# JRPay GUI Extension — Architectural Decisions

*Options, tradeoffs, and recommendations for the GUI extension.*
*Date: 2026-04-23*
*Companion to: `gui/architecture/ARCHITECTURE_DESIGN.md`. The pre-locked decisions are in `gui/PROJECT_DECISIONS.md` (D1–D7) and not repeated here.*

---

## How to Read This Document

For each open architectural challenge in the GUI tier, this document presents:
- **Options** — the realistic alternatives
- **Pros / Cons** — for each option, in our context
- **Recommendation** — a specific choice with rationale

Decisions are numbered `G.<n>`. Where a decision depends on a backend-side decision documented in `architecture/ARCHITECTURE_DECISIONS.md`, the dependency is cited.

---

## G.1 — Identity Service Hosting

### Decision G.1a: Identity Service Module Boundary

Decided in `PROJECT_DECISIONS.md` D2 — separate `jrpay-identity-service` module.

### Decision G.1b: SCA Provider Abstraction

**Option 1: Hardcode TOTP for portfolio, add BankID later**

Pros: simplest path; no SPI to design.
Cons: production upgrade requires code changes, not configuration; violates Guideline 9.

**Option 2: SPI with `SimulatedBankIdScaProvider` + `TotpScaProvider` + skeleton `RealBankIdScaProvider`**

Pros: matches Guideline 9 (production upgrade path documented and structurally enabled); honest for portfolio; supports BankID-style flows in tests; easy to swap real provider in production.
Cons: more code than Option 1.

**Recommendation: Option 2.** Per `D6`. The SPI is a small interface (`start`, `verify`, `supportsDynamicLinking`); the marginal cost is low and the architectural benefit is high.

### Decision G.1c: Audience separation in OAuth2 clients

**Option 1: One OAuth2 client per audience (admin-web, client-web, mobile, TPP)**

Pros: per-audience scope restriction at the IdP layer; per-audience redirect URIs; per-audience rate limits.
Cons: more clients to register; more configuration.

**Option 2: One OAuth2 client total, scope decided at request time**

Pros: simpler IdP config.
Cons: a customer-facing client could request admin scopes if compromised; weaker defence in depth.

**Recommendation: Option 1.** Per `D2`. The admin and customer audiences have different threat models; per-client scope ceilings enforce that boundary structurally.

---

## G.2 — Token Format and Lifetime

### Decision G.2a: JWT vs Opaque Access Tokens

**Option 1: JWT access tokens (RS256)**

Pros: backend services validate locally; no per-request introspection call; trace context can ride along; standard SAS default.
Cons: revocation harder (must wait for TTL); larger headers.

**Option 2: Opaque tokens with introspection per request**

Pros: instant revocation; smaller headers.
Cons: per-request introspect call adds latency to every API call; identity service becomes a hot path.

**Recommendation: Option 1 with short TTL (15 min) and refresh-token revocation.** Revocation latency is bounded by TTL, which is short enough for our threat model. Introspection is available for high-sensitivity services that opt in (audit-query, identity).

### Decision G.2b: Refresh Token Storage on Web

**Option 1: localStorage**

Pros: easy access from JS.
Cons: vulnerable to XSS exfiltration. Banned by OWASP for sensitive tokens.

**Option 2: sessionStorage**

Pros: clears on tab close.
Cons: same XSS exposure as localStorage.

**Option 3: HttpOnly + Secure + SameSite=Strict cookie**

Pros: not accessible to JS, CSRF protection via SameSite.
Cons: requires the IdP to be on the same site as the GUI (or careful subdomain setup); some refresh flows require server-side cooperation.

**Recommendation: Option 3.** The identity service exposes the refresh-token endpoint behind a path under the GUI's origin (via reverse proxy) so the cookie is first-party. Access tokens stay in JS memory; refresh is handled by a dedicated endpoint that reads the cookie.

### Decision G.2c: Refresh Token Storage on Mobile

**Option 1: Plaintext file**

Banned. Not viable.

**Option 2: iOS Keychain / Android EncryptedSharedPreferences**

Pros: standard secure storage; backed by hardware (Secure Enclave / Keystore).
Cons: requires careful access-class configuration.

**Option 3: Same as Option 2 but with biometric required to unlock**

Pros: defence against device theft; aligns with banking convention.
Cons: friction on cold start.

**Recommendation: Option 3 with biometric-required, per GUI-SEC-06 and GUI-SEC-07.** PIN/password fallback for users without biometrics or with sensor failures.

---

## G.3 — Web GUI Framework

Decided in `PROJECT_DECISIONS.md` D1 — React + TypeScript + Vite. The remaining decisions are within React.

### Decision G.3a: Routing

**Option 1: React Router**

Pros: most common; large ecosystem.
Cons: weaker type-safety than TanStack Router; manual data-loading orchestration.

**Option 2: TanStack Router**

Pros: end-to-end type-safe routing; built-in loaders; integrates with TanStack Query.
Cons: smaller ecosystem; newer.

**Option 3: Next.js (full framework)**

Pros: SSR/SSG support; opinionated structure.
Cons: SSR adds operational surface area we don't need (banking SPA, no SEO requirement); App Router learning curve.

**Recommendation: Option 2.** Type-safe routing pairs well with the OpenAPI-generated types and helps catch breaking changes during refactors.

### Decision G.3b: Data Fetching

**Option 1: TanStack Query (React Query)**

Pros: cache, invalidation, retry, suspense — all built in; integrates with OpenAPI client.
Cons: cache management still requires care.

**Option 2: SWR**

Pros: simpler API.
Cons: weaker mutation story.

**Option 3: Apollo / urql (GraphQL)**

Pros: declarative.
Cons: no GraphQL backend; would require BFF.

**Recommendation: Option 1.** Standard choice; pairs well with OpenAPI codegen.

### Decision G.3c: Form Library

**Option 1: react-hook-form + zod**

Pros: minimal re-renders; zod schemas mirror OpenAPI; strong typing.
Cons: zod schemas must be authored or generated.

**Option 2: Formik**

Pros: long-standing.
Cons: more re-renders; weaker TypeScript story.

**Recommendation: Option 1**, with `openapi-zod-client` or a custom small generator producing zod schemas from OpenAPI. Payment forms have strict invariants (IBAN mod-97, KID mod-10/11, BigDecimal precision); a typed schema layer pays off.

### Decision G.3d: Component Primitives

**Option 1: Material UI / Ant Design**

Pros: complete UI kit out of the box.
Cons: heavy bundle; opinionated design fights our bespoke flows (VoP traffic-light, SCA dynamic-linking proof); customisation wars.

**Option 2: Tailwind + Radix Primitives**

Pros: accessible primitives; full control over presentation; small bundle.
Cons: more component work upfront.

**Option 3: Tailwind + shadcn/ui**

Pros: copy-paste components on top of Radix; quick start.
Cons: components live in our repo (we own updates).

**Recommendation: Option 2 with selective copy-paste from shadcn/ui as a starting point.** Our flows are bespoke enough that off-the-shelf kits won't fit; Radix gives accessibility for free.

---

## G.4 — Mobile UI Framework

Decided in `PROJECT_DECISIONS.md` D1, D3 — SwiftUI for iOS, Jetpack Compose for Android. No further decision needed at the framework level. The remaining decisions are within those frameworks.

### Decision G.4a: iOS State Management

**Option 1: `@Observable` + `@State` + `@Environment` (modern Swift Macros)**

Pros: native; minimal boilerplate; built into SwiftUI 5+.
Cons: requires iOS 17 baseline (we target 16+).

**Option 2: ObservableObject + @Published**

Pros: works back to iOS 13.
Cons: slightly more boilerplate; older idiom.

**Option 3: TCA (The Composable Architecture)**

Pros: testable; explicit state machine.
Cons: opinionated; learning curve; significant dependency.

**Recommendation: Option 2 with iOS 16 baseline.** When we drop iOS 16 in a future cycle, migration to `@Observable` is mechanical. TCA is overkill for our screen complexity.

### Decision G.4b: Android State Management

**Option 1: ViewModel + StateFlow + collectAsStateWithLifecycle**

Pros: idiomatic; lifecycle-safe; well documented.
Cons: requires care with cold/hot flows.

**Option 2: MVI library (Orbit, Mavericks)**

Pros: explicit reducer model.
Cons: extra dependency; learning curve.

**Recommendation: Option 1.** Standard Android Architecture Components pattern.

---

## G.5 — Real-time Status Delivery

### Decision G.5a: Browser Mechanism for Payment Status

**Option 1: Polling**

Pros: trivial.
Cons: either too slow (misses 5 s SLA) or too chatty (every 500 ms × N customers).

**Option 2: WebSockets**

Pros: bidirectional; widely supported.
Cons: more infrastructure; harder to terminate at our nginx; requires sticky sessions.

**Option 3: Server-Sent Events (SSE)**

Pros: HTTP/2-friendly; no special infrastructure; auto-reconnect built into EventSource; one-way (which is what we need).
Cons: connection-per-tab; per-customer fanout state in A4.

**Recommendation: Option 3 (SSE) for browsers; push notifications (APNs/FCM) as primary for mobile with SSE as a convenience.** SSE's single-direction model fits the use case (server pushes status; client doesn't need to push back over the same channel). Mobile push survives the app being backgrounded; SSE does not.

### Decision G.5b: SSE Authentication

EventSource cannot send custom headers. Options:

**Option 1: Cookie-based auth**

Pros: works transparently with EventSource.
Cons: requires the SSE endpoint on the same site; cookie-based auth is prohibited for customer-facing flows in our threat model (XSS exposure).

**Option 2: Token in query string**

Pros: works with EventSource.
Cons: token may leak in logs / referrers.

**Option 3: One-time stream token**

Customer GUI calls `POST /api/v1/customer/payments/{id}/stream-token` to obtain a short-TTL, single-use token; EventSource opens with `?token=<streamToken>`.

Pros: no long-lived token in query strings; no log leak risk for the access token; backend can invalidate the stream token on disconnect.
Cons: extra request before opening the stream.

**Recommendation: Option 3.** The extra request is a few hundred ms once per stream open; trivial cost for the security benefit.

---

## G.6 — Notification Delivery

### Decision G.6a: Push Provider Abstraction

**Option 1: Direct APNs / FCM SDKs in the notifications service**

Pros: simplest.
Cons: tight coupling; harder to swap; no easy stub.

**Option 2: Adapter SPI with `LoggingPushAdapter` default + `ApnsPushAdapter` + `FcmPushAdapter`**

Pros: stub-friendly for portfolio per D7; production swap by configuration.
Cons: small SPI to design.

**Recommendation: Option 2.** Same Guideline-9 pattern used elsewhere in the project.

### Decision G.6b: Email and SMS Adapters

Same shape as G.6a. Recommendation: SPI with `Logging<X>Adapter` default and a real adapter as configurable production option.

### Decision G.6c: Notifications Service Hosting

**Option 1: Embedded in identity service**

Pros: fewer modules.
Cons: notifications and identity are different concerns; coupling makes both harder to evolve.

**Option 2: Separate `jrpay-notifications` module**

Pros: clean separation; can scale independently; easier to reason about.
Cons: another service.

**Recommendation: Option 2.** Notifications consume from many topics and fan out to many channels — distinct concern from identity.

---

## G.7 — Audit Query Architecture

### Decision G.7a: Read Path for Audit Events

The backend writes audit events to `audit.events` (Kafka, infinite retention) and to a per-instance local log (compliance-primary). It does *not* maintain a queryable store today.

**Option 1: Query Kafka directly via ksqlDB**

Pros: no separate database.
Cons: ksqlDB is heavier than the requirement; complex queries (search by actor + date range + resource) are slow on Kafka.

**Option 2: Query the per-instance local logs via grep**

Pros: zero infrastructure.
Cons: not realistic for a UI; not searchable across instances.

**Option 3: New `jrpay-audit-query` service consuming `audit.events` into Postgres**

Pros: standard read-model pattern (CQRS); query performance per index choices; per-row indexed search.
Cons: another service; another database.

**Recommendation: Option 3.** Mirrors the project's existing event-sourcing-with-projections pattern (A1) at the audit layer. The Kafka topic remains the source of truth.

### Decision G.7b: Hash-Chain Verification

Backend already maintains a per-instance SHA-256 hash chain with daily signed checkpoints (audit.checkpoints topic). Verification is therefore a matter of reading the checkpoints and recomputing the chain over the windowed events.

**Recommendation:** verification implemented as an admin endpoint that streams events for the requested window and recomputes; results cached for 24 h. Heavy operation — rate-limited to 10 verifications/day per operator.

---

## G.8 — Cross-Platform Parity Mechanism

### Decision G.8a: Where to Store Use Case Documents

**Option 1: One file per user narrative under `gui/use_cases/UC-G<n>_<slug>.md`**

Pros: discoverable; mirrors existing backend `use_cases/` pattern.
Cons: many files.

**Option 2: One mega-document per platform**

Pros: fewer files.
Cons: divergence risk; hard to map across platforms.

**Recommendation: Option 1.** Each UC document explicitly states "this user story is implemented identically on web/iOS/Android" and links to the per-platform implementation tests.

### Decision G.8b: Layer-5 Cross-Platform Test Implementation

**Option 1: Three independent E2E suites, no cross-checking**

Pros: simplest.
Cons: violates the parity guarantee structurally.

**Option 2: Scripted scenarios with backend snapshot comparison after each platform run**

Pros: structurally enforces parity (same scenario → same backend state).
Cons: needs a snapshot mechanism; needs all three platforms in CI.

**Recommendation: Option 2.** Scripted scenarios live in `implementation/cross-platform-tests/scenarios/`. The runner orchestrates platform-specific drivers (Playwright, XCUITest, Espresso) and compares JSON snapshots of:
- A1 transaction history
- A1 audit log entries for the customer
- A3 R2P state
- Notification inbox

after each platform's run. Differences are flagged.

CI is heavy (boots iOS simulator + Android emulator + docker-compose); runs on PRs that touch shared narratives, on every nightly main build.

---

## G.9 — Backend Endpoint Layout

### Decision G.9a: BFF (Backend-for-Frontend) or Direct Service Calls

**Option 1: Each GUI calls A1, A3, A4, A5, jrpay-audit-query, jrpay-notifications directly**

Pros: simpler; fewer services; matches existing backend topology.
Cons: GUI must know about service decomposition; CORS configuration per service; consistent auth on each.

**Option 2: BFF per GUI (admin-bff, client-bff)**

Pros: GUI talks to one service; aggregation done server-side; per-audience tailoring.
Cons: another tier; another service to operate; risk of becoming a god-object.

**Recommendation: Option 1 with a few exceptions.** Admin GUI calls services directly; the cross-cutting CORS, scope check, and trace propagation are uniform via the platform starter. The few aggregations the customer GUIs need (e.g., "account list with last-transaction-at") are exposed as composite endpoints on A4 (since A4 already aggregates A1 reads on the customer's behalf for AISP), not in a separate BFF.

The Admin GUI's "case detail with transaction context" aggregation is a composite endpoint on A5, since A5 owns the case projection.

This avoids a BFF tier without forcing the GUIs to be aware of service decomposition.

### Decision G.9b: Path Structure

**Option 1: `/api/v1/<service>/...`**

Pros: clear ownership.
Cons: GUIs must hard-code service prefixes.

**Option 2: `/api/v1/customer/...` and `/api/v1/admin/...` with the audience as the prefix**

Pros: clear audience separation; easier rate-limiting and routing per audience.
Cons: services own paths under multiple prefixes (A4 owns `/api/v1/customer/payments`; A1 owns `/api/v1/admin/accounts`).

**Recommendation: Option 2.** Audience separation is more useful to GUI developers and to operators than service separation. The OpenAPI specs document the implementing service per endpoint.

---

## G.10 — Error and Status Code Handling

### Decision G.10a: Error Format

Backend already uses RFC 9457 (Problem Details). GUIs must consume this consistently.

**Recommendation:** A shared `ApiError` model in each platform, generated from the OpenAPI spec, with a canonical `mapError(error) -> UserFacingError` function that consults `gui/i18n/errors.json` to resolve the user-facing copy. Never display the raw `detail` field; always look up by `type` URI.

### Decision G.10b: Optimistic vs Pessimistic UI Updates

**Option 1: Optimistic — apply the change in the UI immediately, roll back on error**

Pros: snappy.
Cons: dangerous for payment operations where an apparent success that later rolls back is worse than a slight delay.

**Option 2: Pessimistic — wait for server confirmation**

Pros: truth-on-screen at all times.
Cons: feels slower.

**Option 3: Hybrid — optimistic for low-stakes UX (mark notification read, toggle preference); pessimistic for everything financial**

**Recommendation: Option 3.** Define an explicit list of operations that are optimistic: notification read/unread toggle, trusted-beneficiary nickname edit, language preference toggle. Everything else is pessimistic.

---

## G.11 — i18n Catalogue Format

### Decision G.11a: Format

**Option 1: ICU MessageFormat in JSON**

Pros: standard format; supports plurals, gender, selects; consumed by FormatJS (web), MessageFormat (Android), `String.localizedStringWithFormat` + adapter (iOS).
Cons: iOS `String.localizedStringWithFormat` is positional — needs an ICU adapter on iOS.

**Option 2: Plain key-value JSON**

Pros: simplest.
Cons: no plural/gender support; payment language requires plurals ("1 transaction", "5 transactions").

**Option 3: Per-platform native formats (.strings, strings.xml, JSON)**

Pros: native to each platform.
Cons: triple-source-of-truth; drift inevitable.

**Recommendation: Option 1 (ICU JSON) as the source of truth, generated into per-platform formats by a build step.** A small Swift adapter (`MessageFormat.swift`) bridges ICU semantics on iOS.

### Decision G.11b: Translation Workflow

**Option 1: In-repo JSON files, manual edits**

Pros: simple; works for portfolio.
Cons: not scalable to many languages.

**Option 2: TMS (Crowdin, Lokalise) integration**

Pros: scales.
Cons: external dependency; out of scope for portfolio.

**Recommendation: Option 1 for MVP**, with a documented migration path to a TMS if/when the project supports more than two languages.

---

## G.12 — Build, Sign and Distribute Mobile Apps

### Decision G.12a: iOS Signing

**Option 1: Manual signing with a developer profile in CI**

Pros: simple.
Cons: certificate management is tedious; no team scalability.

**Option 2: Fastlane match (encrypted git-stored certs)**

Pros: standard Fastlane workflow; reproducible.
Cons: requires Apple Developer account.

**Recommendation: Option 2 with documented bootstrap.** For portfolio scope, we produce signed `.ipa` artifacts in CI but do not actually publish; bootstrap instructions are in `implementation/client-ios/SIGNING.md`.

### Decision G.12b: Android Signing

**Option 1: Local keystore committed encrypted in the repo**

Banned. Not viable.

**Option 2: CI secrets + Fastlane supply**

Pros: standard.
Cons: requires Play Store account for actual distribution.

**Recommendation: Option 2 with documented bootstrap.** Same scope as iOS — produce signed `.aab` in CI; do not publish.

---

## G.13 — Mobile Threat Detection

### Decision G.13a: Jailbreak / Root Detection

**Option 1: No detection**

Cons: violates banking-app convention; allows trivially compromised devices.

**Option 2: Best-effort detection using common heuristics (file presence, library presence)**

Pros: catches naive attacks.
Cons: bypassable; false positives possible.

**Option 3: Commercial RASP (Runtime Application Self-Protection) library**

Pros: stronger.
Cons: cost; adds dependency; out of scope for portfolio.

**Recommendation: Option 2 with the policy: warn for low-value scopes, refuse for high-value scopes (above SCA low-value threshold).** Documented as upgradeable to a RASP library in production.

### Decision G.13b: Screen Capture and Recording Defence

**Option 1: No defence**

Cons: a screen recorder can capture the SCA challenge with full payment context.

**Option 2: Block screen capture on sensitive screens**

iOS: detect screen recording via `UIScreen.main.isCaptured` and obscure sensitive fields. Android: `WindowManager.FLAG_SECURE` on activities containing sensitive UI.

**Recommendation: Option 2** on these screens: SCA challenge, account detail with PII visible, GDPR export download. Customers may take screenshots elsewhere.

---

## G.14 — Backend CORS Policy

### Decision G.14a: CORS Source

**Option 1: Per-service CORS config**

Pros: explicit per-service.
Cons: drift across services; easy to misconfigure.

**Option 2: Centralised in the platform starter**

Pros: one source of truth; deny-by-default + explicit allowlist of GUI origins per environment.
Cons: requires the starter to know about GUI origins.

**Recommendation: Option 2.** The starter exposes a `jrpay.cors.allowed-origins` property; the deployment manifest sets it per environment. Local dev: `http://localhost:5173` (Vite) and `http://localhost:5174`. Production: actual GUI origins.

---

## G.15 — Documentation and Traceability

### Decision G.15a: Use Case Document Template

**Recommendation:** Adopt the structure used by the existing backend use cases (`use_cases/UC01_invoice_payment.md` etc.):
- Purpose, actors, preconditions, postconditions
- Main flow (numbered steps)
- Alternative flows (AF-01..)
- Failure modes
- Related requirements (linkbacks to `gui_requirements.md` IDs)
- Per-platform implementation status (Web / iOS / Android)
- Tests realising this UC (links to test files)

A template at `gui/use_cases/_TEMPLATE.md` enforces consistency.

### Decision G.15b: Traceability Matrix

**Recommendation:** A single CSV-formatted traceability matrix at `gui/requirements/TRACEABILITY.md` mapping every `*-F<n>` and `GUI-*-<n>` requirement to:
- One or more use case documents
- One or more OpenAPI operations
- Tests at each test pyramid layer

CI gate: every requirement must have at least one test mapping; every UC must have at least one mapped requirement.

---

## Decisions Pending Phase 1 Review

The following decisions are deliberately deferred to the Phase-1 architecture review, where we have the full picture:

- **G.16** Web GUI bundle splitting strategy (route-based vs feature-based vs hybrid)
- **G.17** Mobile background-fetch strategy for status reconciliation when push misses
- **G.18** Audit retention for the projection store (mirror Kafka infinite or a sliding window?)
- **G.19** Test fixture seeding policy in production (clearly disabled in prod; but how is "prod" identified?)
- **G.20** Outage UI: degraded modes when individual backend services are down
