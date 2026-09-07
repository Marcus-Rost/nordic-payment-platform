# JRPay API Specifications

*OpenAPI 3.1 specs for the GUI tier. Source of truth per GP1 in `gui/architecture/ARCHITECTURE_DESIGN.md` §8.*

---

## Inventory

### Shared (cross-spec)

| File | Purpose |
|------|---------|
| [`shared-schemas.yaml`](shared-schemas.yaml) | All enums (mirrors `gui/architecture/SHARED_ENUMS.md`), RFC 9457 Problem envelope, dynamic-linking input schemas, reusable parameters, security schemes |

### Spec files

| File | Realises | Status |
|------|----------|--------|
| [`identity.yaml`](identity.yaml) | UC-G01, G30, G44, G45, G50, G51, G52, G53 | ✅ Phase 2A |
| [`customer-accounts.yaml`](customer-accounts.yaml) | UC-G02, G03, G04 | ✅ Phase 2A |
| [`customer-payments.yaml`](customer-payments.yaml) | UC-G05, G06, G07, G15 | ✅ Phase 2A |
| [`customer-r2p.yaml`](customer-r2p.yaml) | UC-G11, G12, G13, G14 | ✅ Phase 2B |
| [`customer-mandates.yaml`](customer-mandates.yaml) | UC-G08, G09 | ✅ Phase 2B |
| [`customer-consents.yaml`](customer-consents.yaml) | UC-G10, G17, G18 | ✅ Phase 2B |
| [`customer-beneficiaries.yaml`](customer-beneficiaries.yaml) | UC-G16 | ✅ Phase 2B |
| [`customer-notifications.yaml`](customer-notifications.yaml) | UC-G19, G20, G21, G29, G80 | ✅ Phase 2B |
| [`customer-gdpr.yaml`](customer-gdpr.yaml) | UC-G24, G25 | ✅ Phase 2B |
| [`customer-profile.yaml`](customer-profile.yaml) | UC-G22, G23 | ✅ Phase 2B |
| [`customer-privacy.yaml`](customer-privacy.yaml) | UC-G26 (and inherits UC-G45 from identity.yaml) | ✅ Phase 2B |
| [`admin-accounts.yaml`](admin-accounts.yaml) | UC-G35, G36 | ✅ Phase 2C |
| [`admin-fraud.yaml`](admin-fraud.yaml) | UC-G31, G32, G33 | ✅ Phase 2C |
| [`admin-sanctions.yaml`](admin-sanctions.yaml) | UC-G34 | ✅ Phase 2C |
| [`admin-payments.yaml`](admin-payments.yaml) | UC-G37, G38 | ✅ Phase 2C |
| [`admin-reconciliation.yaml`](admin-reconciliation.yaml) | UC-G39 | ✅ Phase 2C |
| [`admin-audit.yaml`](admin-audit.yaml) | UC-G40 | ✅ Phase 2C |
| [`admin-gdpr.yaml`](admin-gdpr.yaml) | UC-G41 | ✅ Phase 2C |
| [`admin-status.yaml`](admin-status.yaml) | UC-G42, G54 | ✅ Phase 2C |
| [`admin-users.yaml`](admin-users.yaml) | UC-G43 | ✅ Phase 2C |

Total planned: 20 spec files + 1 shared. Phase 2A delivered 3 of them (identity + accounts + payments) plus shared-schemas — the highest-leverage foundation. Phase 2B completed the customer surface (8 more specs). Phase 2C completes the admin surface (9 specs). All 20 + 1 specs are now in place and pass full cross-file OpenAPI 3.1 validation.

---

## Authoritative companion docs

The following live under `gui/architecture/`; specs cross-reference but do not re-define:

- `DYNAMIC_LINKING.md` — SCA hash canonical form (10 classes, RFC 8785 JCS, NFC, amount-as-string)
- `PROBLEM_TYPES.md` — RFC 9457 `type` URI registry (70+ entries)
- `SSE_CONVENTIONS.md` — stream-token auth, heartbeat 15 s, reconnect backoff, event names
- `SIGNED_URL_CONVENTIONS.md` — HMAC-SHA256 signing, per-use TTL, single-use
- `SHARED_ENUMS.md` — enum registry (mirrors `shared-schemas.yaml`)
- `COMPONENTS_COUNTDOWN.md` — UI side, but impacts the time-bound operations documented in specs

---

## Linting discipline (Spectral)

`.spectral.yaml` extends `spectral:oas` with the JRPay house rules in
`.spectral/functions/`. Every rule below is enforced at **error**
severity in CI — merge is blocked on any violation.

| Rule | Severity | Function |
|------|----------|----------|
| `jrpay-idempotency-key-required` | error | Every POST / PATCH / DELETE declares `Idempotency-Key` (exempt: `/oauth2/`, `/.well-known/`, `/api/v1/public/`). |
| `jrpay-problem-types-registered` | error | Every `urn:problem:*` in prose / examples is in `gui/architecture/PROBLEM_TYPES.md`. |
| `jrpay-security-required` | error | Every operation declares `security:` unless public or SSE-stream-token authed. |
| `jrpay-rfc9457-error-format` | error | Every 4xx / 5xx response uses `application/problem+json`. |
| `jrpay-enums-match-shared` | warn | Inline enums must not exactly duplicate a canonical `SHARED_ENUMS.md` enum — `$ref` into `shared-schemas.yaml` instead. |

Built-in `spectral:oas` tightened rules:
- `operation-operationId` → error (generators need stable method names).
- `operation-tag-defined` → error (docs grouping).
- `info-contact`, `info-license`, `oas3-operation-security-defined`,
  `oas3-unused-component` → disabled (cross-file refs break these).

CI workflow `.github/workflows/openapi.yml` runs three jobs:
1. **Spectral lint** — `npm install && spectral lint --fail-severity=error *.yaml`.
2. **Cross-file validator** — `python3 scripts/validate_cross_refs.py` (prance + openapi-spec-validator) resolves every `shared-schemas.yaml#/...` reference and validates the merged document against the OpenAPI 3.1 schema.
3. **Problem-registry parity** — `diff` the Problem-URN list in `PROBLEM_TYPES.md` against the registry embedded in `.spectral.yaml`; CI fails if either drifts ahead of the other.

Phase 2E will add typed-client codegen (openapi-typescript for web,
swift-openapi-generator for iOS, openapi-generator Kotlin for Android)
plus Pact consumer-contract tests.

### Run linting locally

```sh
cd implementation/api-specs
npm install                               # one-time, installs Spectral in node_modules/
pip install -r scripts/requirements.txt   # one-time, installs prance + validator
npm run lint                              # spectral lint, fail on errors
npm run lint:all                          # spectral lint, show warnings too
npm run validate                          # cross-file $ref + OpenAPI 3.1 schema
```

All three checks pass today with 0 errors across all 21 specs.
72 `operation-description` warnings remain as style-level follow-ups (GETs with `summary:` but no `description:`); they do not block CI.

---

## Codegen smoke (Phase 2E)

Each spec must produce client code that compiles on every platform we
ship to. The `codegen/` folder holds three smoke jobs — one per
platform — that run in CI and locally.

| Platform | Generator | Compile gate | Local? |
|----------|-----------|--------------|--------|
| Web | [`openapi-typescript`](https://openapi-ts.dev/) | `tsc --noEmit --strict` | ✅ Node 20 |
| Android | `@openapitools/openapi-generator-cli` (`kotlin` + `jvm-retrofit2` + Moshi) | `gradle compileKotlin` (multi-project) | ✅ JDK 21 + Gradle 8.12 + Kotlin 2.0.21 (SDKMAN) |
| iOS | [`swift-openapi-generator`](https://github.com/apple/swift-openapi-generator) (SwiftPM plugin) | `swift build` | requires Swift 6 (CI: `swift:6.0.2-noble` container) |

```sh
# Web
cd implementation/api-specs/codegen/web   && npm install && npm run smoke
# Android
cd implementation/api-specs/codegen/android && npm install && npm run smoke
# iOS (requires `swift` on PATH)
cd implementation/api-specs/codegen/ios   && npm run smoke
```

CI runs all three as separate jobs in `.github/workflows/codegen.yml`.

### Spec-level fixes prompted by codegen

- `identity.yaml` — removed `info.license` (had `name` without
  `identifier`; openapi-generator's spec validator rejected it).
- `customer-consents.yaml` — `ConsentSummary` reshaped from
  `allOf + discriminator` to `oneOf + discriminator` (matches
  `ConsentDetail`); resolved a generator bug where inherited `$ref`
  properties were emitted as nested-class tokens.
- `customer-notifications.yaml` — dropped `const: true` on
  `NotificationPreferences.channels.inbox.{enabled,locked}`; openapi-
  generator emitted Boolean enums with String values, which Kotlin
  rejected. Constraint preserved in prose + server enforcement.
- `shared-schemas.yaml` — `Problem.instance.format` switched to
  `uri-reference` so the relative-path example aligns with RFC 9457
  semantics (caught by Spectral's example validator).

---

## Versioning policy

Per ARCHITECTURE_DESIGN §8.4:
- Customer-facing: `/api/v1/customer/…`
- Admin-facing: `/api/v1/admin/…`
- Public (unauth): `/api/v1/public/…`
- OAuth2: `/oauth2/…` (fixed path, RFC-compliant)
- OIDC Discovery: `/.well-known/…`

Breaking changes require `/v2/` path and announce → deprecate → remove cadence.

---

## Phase 2 roadmap (this folder)

| Sub-phase | Scope | Target |
|-----------|-------|--------|
| **2A — Foundation** | shared-schemas, identity, customer-accounts, customer-payments | Done |
| **2B — Customer specs** | remaining 8 customer-* specs | Done |
| **2C — Admin specs** | 9 admin-* specs | Done |
| **2D — Spectral rules + CI** | .spectral.yaml + custom functions + openapi.yml | Done |
| **2E — Codegen smoke** | typed clients compile per platform (web ✅ local, android ✅ local, ios ✅ CI) | Done |
| **2F — Identity service module** | `jrpay-identity-service/` with SAS + SCA + password-reset + privacy notice + sessions | Done |
| **2G — Notifications service module** | `jrpay-notifications/` with inbox + SSE + preferences + Kafka projection + adapter SPI | Done |
| **2H — Audit-query service module** | `jrpay-audit-query/` Kafka projection + search + chain verify + signed exports | Done |
| **2I — A1 admin controller** | freeze/unfreeze/close/create/reveal-pii on `jrpay-account-ledger`, plus identity-service step-up validation endpoint | Done |
| **2J — A3 customer controller** | R2P (list/detail/accept/decline/defer/amend) + mandates + beneficiaries on `jrpay-bill-payment`, with server-initiated SCA + step-up verification + audit emission | Done |
| **2K — A4 customer controller + SSE** | accounts read-projection + payments full lifecycle + schedules + statements + SSE fanout on `jrpay-psd2-api` | Done |
| **2L — A5 case projection + admin** | FraudCaseProjector + SanctionsHitProjector + full admin-fraud / admin-sanctions endpoints + SSE on `jrpay-fraud-detection` | Done |
| **2M — Docker-compose updates** | full stack: 9 Spring Boot services + kafka-init + admin-web + client-web nginx placeholders, all with healthchecks + dependency ordering | Done |
| **2N — Smoke tests** | extend `jrpay-smoke-tests` to cover new GUI-tier surfaces end-to-end against the running compose stack | Done — Phase 2 complete |

Phase 2A completed the foundation. Phase 2B closed the customer OpenAPI surface. Phase 2C closed the admin OpenAPI surface; together they constitute the full GUI-tier contract. Phase 2D locked the conventions in CI with a 5-rule Spectral ruleset + custom JS functions, a cross-file OpenAPI 3.1 validator, and a Problem-type registry-parity check. Phase 2E added per-platform codegen smoke gates — web (`openapi-typescript` + `tsc`) and Android (`openapi-generator` Kotlin + `gradle compileKotlin`) compile locally, iOS (`swift-openapi-generator` + SwiftPM) compiles in CI. Phase 2F shipped the `jrpay-identity-service` Spring Boot module backing `identity.yaml` — Spring Authorization Server + dynamic-linking SCA (RFC 8785 / SHA-256) + password-reset + privacy notice + active-session management, with 23 passing tests (4-layer pyramid). Phase 2G shipped the `jrpay-notifications` Spring Boot module backing `customer-notifications.yaml` — inbox CRUD with locked-category enforcement, SSE bus for cross-device read-state sync, JSONB preferences matrix with optimistic concurrency, push-device registration, channel-adapter SPI (logging defaults / production swap-in), and a Kafka projector for `payment.status-updates` (verified end-to-end: HELD → FRAUD_HOLD inbox row, SETTLED → PAYMENT_STATUS), with 13 passing tests. Phase 2H shipped the `jrpay-audit-query` Spring Boot module backing `admin-audit.yaml` + the `customer-privacy.yaml` consent-log slice — a SHA-256 hash-chained Kafka projection of `audit.events` with RFC 8785 canonical-JSON, JPA Specifications-driven search (free-text + filter + range), on-demand chain verification with checkpoint roll-up, async CSV / signed-JSON export jobs with HMAC-signed single-use download URLs, and a customer consent-log surface filtered by `customerUserId`, with 21 passing tests (Layer 1 unit: canonical JSON, hasher, signed URLs, CSV writer; Layer 2 integration: ingest → chain → verify → export round-trip) and a verified end-to-end Kafka boot smoke (publish event → audit_events row at `chain_offset=1` with `chain_hash`). Phase 2I shipped the A1 admin controller on `jrpay-account-ledger` backing `admin-accounts.yaml` (UC-G35 + UC-G36) — search by name (pg_trgm fuzzy) / IBAN (mod-97) / fødselsnummer (loa3-admin gated), masked detail view with reservations + freeze metadata, freeze/unfreeze/close/create with admin-action dynamic-linking SCA proof verification (delegated to identity-service via the new `/sca/step-up-tokens/validate` endpoint that A1 calls with a service-credential JWT), full RFC 9457 problem responses, the PII-reveal flow with 60-s TTL + up-to-3 extensions + sliding-window rate-limit (5/h/operator/account) triggering SUPERVISOR-second-approval, and `audit.events` + `audit.pii-revealed` Kafka publishing, with 53 passing tests (the prior ledger tests plus 23 new tests covering masking, dynamic-linking hash determinism + NFC, search modes, freeze/unfreeze/close/create flows, PII reveal happy-path + extensions cap + rate-limit + SUPERVISOR approval). Phase 2J shipped the A3 customer controller on `jrpay-bill-payment` backing `customer-r2p.yaml` (UC-G11..G14) + `customer-mandates.yaml` (UC-G08, UC-G09) + `customer-beneficiaries.yaml` (UC-G16) — full R2P inbox lifecycle (list/detail/accept/decline/defer/defer-cancel/amend/amend-cancel) with the state machine extended to `AMENDMENT_REQUESTED ↔ REPLACED`, server-initiated SCA via the new A3 forward-the-customer-bearer `ScaInitiator` calling identity-service `/sca/start` (returning 202 + `scaSessionId` + `dl_hash`), step-up token validation reusing the 2I `/sca/step-up-tokens/validate` endpoint with a new `jrpay-a3-service` client-credentials principal, AvtaleGiro mandate lifecycle (`PENDING_SCA → ACTIVE` on SCA verify, pause/resume without SCA, max-amount change requires SCA, revoke blocked while debit in-flight) with cached merchant registry and seeded demo merchants, trusted-beneficiary CRUD with `PENDING_SCA → ACTIVE` flow + IBAN mod-97 + duplicate detection + IBAN masking on list (`NO93 •••• •••• 7947`), `audit.events` Kafka publishing for every customer action, and 71 passing tests (prior 6 + 65 new across the customer-surface integration suite). Phase 2K shipped the A4 customer controller on `jrpay-psd2-api` backing `customer-accounts.yaml` (UC-G02..G04) + `customer-payments.yaml` (UC-G05/G06/G07) — accounts read-projection driven by a `ledger.events` Kafka consumer (idempotent via `processed_kafka_events`), customer-scoped accounts/balances/transactions/transaction-detail/receipt-PDF/statements (sync demo + async job table), the customer payment full lifecycle (initiate → 202 PendingSca → submit → 200 PaymentAck) with idempotency-key dedup + probable-duplicate detection + pre-SCA cancel + status refresh rate limit, VoP proxy to A6 with per-customer 30/min sliding window, future-dated payment schedules (UC-G07) with PENDING_SCA → SCHEDULED transition + cancel-pre-execution, single-use SSE stream tokens (5-min TTL, SHA-256 stored) + `payment.status-updates` Kafka listener fan-out via in-memory `PaymentStreamHub` with 15-second heartbeat scheduler, plus the new `jrpay-a4-service` identity client-credentials principal, with 59 passing tests (DL hash, IBAN, VoP rate limiter unit; accounts/payments/schedules/stream-token integration). Phase 2L shipped the A5 case-management projection + admin surface on `jrpay-fraud-detection` backing `admin-fraud.yaml` (UC-G31..G33) + `admin-sanctions.yaml` (UC-G34) — V001 schema for the new `casework` database (fraud_cases, fraud_timeline, fraud_held_payments, sanctions_hits, sanctions_lists, sanctions_fp_cache, sanctions_list_refresh_runs, stream_tokens, processed_kafka_events, admin_idempotency, bulk_decide_runs), a `payment.fraud-check-response` Kafka projector that creates cases on HOLD decisions and fans the event to live SSE subscribers, a `sanctions.hits` Kafka projector that creates hit rows, the full fraud queue + detail + context + assign + decide + bulk-decide endpoints (CONFIRMED_FRAUD cascades to `account.freeze-commands` + payment REJECTED; DISMISSED releases held payments; bulk decide capped at 50 / cooldown 60s with sorted-joined-ids hash as the dynamic-linking resourceId), the full sanctions queue + detail + decide endpoints (CONFIRMED_SANCTION freezes the account; FALSE_POSITIVE optionally adds a 90-day FP-cache entry; RELEASE_FOR_FRAUD_REVIEW spawns a new fraud case), sanctions list metadata + refresh job + FP-cache list/remove, single-use SSE stream tokens + dual-scope hub (FRAUD/SANCTIONS) with 15-second heartbeat scheduler, dynamic-linking hash + step-up validation reusing the V007-seeded `jrpay-a5-service` client-credentials principal, full RFC 9457 problem responses, with 65 passing tests (DL hash + masking unit; case + sanctions + stream-token integration covering decide flows, bulk-decide cap, FP-cache TTL, list refresh). Phase 2M wired the full local development stack — a single multi-stage `Dockerfile.service` (gradle:8.12-jdk21-alpine builder → eclipse-temurin:21-jre-alpine runtime) that the compose file invokes once per Spring Boot module via a `MODULE` build-arg, application-docker.yml profiles for all nine services pointing DB at `postgres:5432` and Kafka at `kafka:29092` and JWKS at `http://identity:8097/oauth2/jwks`, an extended `create-topics.sh` covering all Phase-2 GUI-tier additions (notifications.outbound, r2p.arrived, consent.expiring, gdpr.status-changed, status.events, csp.violation, account.customer-registered, audit.pii-revealed, session.revoked, sanctions.hits, audit.checkpoints), a one-shot `kafka-init` compose service that runs the topic script when Kafka is healthy, healthchecks + `service_healthy`/`service_started` dependency ordering across all nine services (clearing-engine moved to 8112/9112 to dodge the Kafka external-listener port), and two nginx placeholders serving Norwegian/English landing pages on the OAuth-callback ports (`client-web:5173`, `admin-web:5174`) ready to be swapped for the React SPAs in Phase 3. `docker compose config --quiet` passes; full stack build + smoke verification arrive in 2N. Phase 2N closed the cycle with end-to-end smoke coverage across the compose stack — a profile-gated `/dev/oauth2/dev-token` endpoint in identity-service (active under `docker`/`demo`/`smoke` profiles, signs JWTs with the same JWKS the resource servers consume so no separate verification key is needed) plus 22 new smoke tests in `jrpay-smoke-tests` covering identity (OIDC discovery, JWKS, public privacy notice, password-reset always-202, dev-token claim shape for both audiences, bad-credentials rejection), the customer surface (PSD2 list-accounts/list-payments, bill-payment list-R2P/list-mandates/AvtaleGiro merchant search returning the seeded Hafslund entry/list-beneficiaries, notifications inbox), the admin surface (A1 admin search, A5 fraud queue with counters, A5 sanctions queue with counters, A5 sanctions lists returning the seeded EU-CONS+OFAC-SDN registry, A5 FP-cache list, audit-query health), and the SSE infrastructure (PSD2 payment-stream-token mint, A5 fraud-stream-token mint, A5 sanctions-stream-token mint — all returning a non-blank token with 300-second TTL). Each test class probes service-management ports first and `Assumptions.assumeTrue`-skips when targets are down, so `gradle :jrpay-smoke-tests:test` is green-on-empty when the stack isn't running and meaningful when it is. 51 smoke tests total (22 new Phase-2 plus 29 pre-existing); identity-service still 23/23 with the new dev-token controller; whole-project compileJava + compileTestJava succeed. Phase 2 of the GUI extension is complete: 6 backend service modules wired to 21 OpenAPI specs, full Docker stack, end-to-end smoke discipline. Phase 3 (Admin Web SPA) and Phase 4–6 (Client Web/iOS/Android) consume this contract surface.

---

## Cross-spec conventions (summary)

- **Every mutating op** — `Idempotency-Key` header required.
- **Every error body** — `application/problem+json` with `type` from the registry.
- **Every enum** — `$ref` into `shared-schemas.yaml`.
- **Every OAuth2-scoped op** — declares the required `scope` in its `security:` block.
- **Every SSE endpoint** — authenticated by one-time stream token acquired via a `-token` sibling endpoint.
- **Every download endpoint** — returns either 302 to a signed URL or a signed-URL JSON object per SIGNED_URL_CONVENTIONS.md.
- **Every time-bound field** — uses `Iso8601Instant` with UTC `Z` suffix.
- **Every monetary amount** — `MonetaryAmount` string (never JSON number).
- **Every UUID** — `Uuid` format with lowercase-hyphenated pattern.
