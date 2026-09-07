# JRPay E2E — open items ledger

Per `space/E2E_TESTING_GUIDE.md` §10. Every bug found, fix made, or
explicit non-goal lives here.

## Open

| ID | Phase | Subject | Status | Where it bites |
|----|-------|---------|--------|----------------|
| OPEN-1 | A | `clearing-engine` healthcheck mis-wired (compose says `MGMT_PORT=9112`, app binds 8092/9082) | Documented | Service runs fine; `docker compose ps` shows `unhealthy`. Don't gate E2E on it. |
| ~~OPEN-2~~ | A | ~~VoP seed mismatch~~ | **Resolved on fresh stack** | All 9/9 in `MasterUC01SmokeTest` pass against a clean docker volume. |
| ~~OPEN-3~~ | B | ~~`/api/v1/accounts/transfer` did not dedup by `transactionId`, silent double-debit~~ | **Fixed (F11)** | applyDoubleEntry now short-circuits when the projection PK already exists. Phase-B idempotency spec flipped to assert exactly-one-debit. |
| ~~OPEN-4~~ | B | ~~Credits to FROZEN account rejected~~ | **Fixed (F12)** | Removed `creditAccount.getStatus() == FROZEN` check in `AccountCommandHandler.java`. Banking-correct: refunds allowed, only outgoing debits blocked. `UseCaseEndToEndTest.scenario1F_accountFreeze` now green. |
| ~~OPEN-5~~ | E | ~~DevTokenController auth failures returned 500~~ | **Fixed (F13)** | Throws `BadCredentialsException` (→ 401) for wrong password + unknown user (anti-enumeration: same response); throws `ResponseStatusException(FORBIDDEN)` for audience mismatch. Three Phase-E traps flipped to assert 401 / 403. |
| **OPEN-6** | D | `audit-query` `/api/v1/admin/audit?page=0&size=10` — path not mapped to a controller. | Open | Now surfaces as clean 404 (post-F14 OPEN-10 fix). The endpoint genuinely doesn't exist on audit-query; admin audit reads happen at the DB level. Either implement the REST surface or document its absence in admin-audit.yaml OpenAPI spec. |
| ~~OPEN-7~~ | D | ~~Customer-facing `/api/v1/customer/payments` POST endpoint missing~~ | **Fixed (F15)** | Endpoint already existed in `jrpay-psd2-api` (`CustomerPaymentController`); the gap was nginx routing the path to account-ledger instead. Updated `nginx.conf` to point `/customer/payments` at psd2-api. Phase D2 spec asserts the surface returns proper RFC-9457 validation errors (Idempotency-Key required). Full happy-path Kafka chain spec is a follow-up. |
| ~~OPEN-10~~ | G | ~~`NoResourceFoundException` mapped to 500~~ | **Fixed (F14)** | Added `@ExceptionHandler(NoResourceFoundException.class)` to `PaymentExceptionHandler` returning 404 with `urn:problem:not-found`. Also added `HttpRequestMethodNotSupportedException` → 405. Affects all 9 services; rebuilt during Round-1 batch. |
| ~~OPEN-11~~ (partial) | G | ~~5 customer endpoints returned 500 — accounts, payments, beneficiaries, profile, privacy~~ | **3 Fixed (F16) + 2 Documented** | nginx misrouted accounts/payments/beneficiaries to account-ledger instead of psd2-api/bill-payment. Updated routing → all three return 200 with real data. profile + gdpr controllers genuinely don't exist (clean 404 post-F14); see new OPEN-13 below. |
| ~~OPEN-12~~ | D2 | ~~ProfilePage stuck on Loading when /profile fails~~ | **Fixed (F17)** | Refactored to render `<h1>` unconditionally, with separate Loading / data slots below. The page is no longer hijacked by an indefinite spinner when /api/v1/customer/profile is unavailable. |
| **OPEN-13** | D2 | Two customer endpoints have no controller anywhere: `/api/v1/customer/profile` (PATCH-able), `/api/v1/customer/gdpr/exports` + `/customer/privacy/{settings,consent-log}`. | Open | Web SPA + Android app expect these. Profile controller would live in identity (UC-G26); GDPR + privacy-settings controller would live in identity or a new module (UC-G24/G25). Estimate: 1–2 days each. |

## Closed (fixed in code)

Carry-over from prior session "EU261-E2E" (see `E2E_REPORT.md`). Listed
here for the ledger; commits already in source.

| ID | Subject | Resolution |
|----|---------|------------|
| F1 | A1 admin endpoints rejected Bearer JWT (Basic-only) | Added JWT chain bound to `/api/v1/admin/**` + `/api/v1/customer/**` in `DemoSecurityConfig.java` |
| F2 | Self-transfer projection PK collision | New `SelfTransferException` → 422 `urn:problem:domain-error` |
| F3 | V003 bcrypt hash didn't match documented password | New `V008__fix_demo_user_passwords.sql` migration |
| F4 | Admin SPA unfreeze enum drift from server | Aligned `UnfreezeReason` type + dropdown options to server enum |
| F5 | PSD2 customer consent endpoints missing | New `CustomerConsentController` |
| **F7** | `MethodArgumentNotValidException` / `ConstraintViolationException` / `MissingServletRequestParameterException` mapped to 500 instead of 400 | Added three dedicated `@ExceptionHandler`s to `PaymentExceptionHandler.java` returning 400 with `urn:problem:validation` (or `urn:problem:missing-parameter`) and a `fields` map. Added `spring-boot-starter-validation` to `jrpay-security-starter`'s build. Affects every service that uses the shared starter (all 9). |
| **F8** | Android app crashes on launch — `Cannot find implementation for OfflineCache.AppDb. OfflineCache_AppDb_Impl does not exist` | Wrong annotation-processor configuration in `android-app/app/build.gradle.kts`: used `annotationProcessor("androidx.room:room-compiler")` which only sees Java sources. Codebase is Kotlin → Room never generated `_Impl` classes → app crashed in `Application.onCreate`. **Fix:** added KSP plugin (`com.google.devtools.ksp` `2.0.21-1.0.27` in root, applied in `app/`); switched dep to `ksp("androidx.room:room-compiler:2.6.1")`. Verified working in the Android emulator on 2026-04-29. |
| **F9** | `API_BASE_URL` in Android app pointed at bare host port 80 (nothing serves there) | Fixed in `android-app/app/build.gradle.kts`: changed to `http://10.0.2.2:5173` so the app talks to the same `client-web` nginx the web SPA uses, getting all customer endpoint reverse-proxying for free. |
| **F10** | Android app blocks cleartext HTTP by default (API 28+) — instrumentation tests against `http://10.0.2.2` fail | Fixed by adding `res/xml/network_security_config.xml` that permits cleartext only for `10.0.2.2`, `localhost`, `127.0.0.1`. Production hosts remain HTTPS-only via `<base-config cleartextTrafficPermitted="false">`. |
| **F11** | `/api/v1/accounts/transfer` silently double-debited on duplicate TxnId (closes OPEN-3) | Added existsById short-circuit at the top of `AccountCommandHandler.applyDoubleEntry` checking the (transactionId, accountId) PK on either leg. Second call with same TxnId returns immediately without re-applying balances or republishing the event. Phase-B idempotency assertion flipped from `980` to `990`. |
| **F12** | Credits to a FROZEN account were rejected (closes OPEN-4) | Removed the `creditAccount.getStatus() == AccountStatus.FROZEN` guard in `AccountCommandHandler.java`. Banking convention: only outgoing debits block; refunds and other inbound credits to a frozen account are allowed. Greens the pre-existing `scenario1F_accountFreeze` unit test. |
| **F13** | `DevTokenController` returned 500 for auth failures (closes OPEN-5) | Replaced `IllegalArgumentException` with `BadCredentialsException` for wrong password + unknown user (both → 401, anti-enumeration: identical response prevents user-existence leak). Audience mismatch + suspended account → `ResponseStatusException(FORBIDDEN)` → 403. Added matching `@ExceptionHandler`s in `PaymentExceptionHandler`. |
| **F14** | `NoResourceFoundException` (404 case) mapped to 500 (closes OPEN-10) | Added two new handlers to `PaymentExceptionHandler`: `NoResourceFoundException` → 404 `urn:problem:not-found` (with the failing path); `HttpRequestMethodNotSupportedException` → 405 `urn:problem:method-not-allowed` (with the supported methods). Cleans up the 500-haze that masked routing bugs. |
| **F15** | `/api/v1/customer/payments` POST unreachable (closes OPEN-7) | The controller already existed in `jrpay-psd2-api/.../CustomerPaymentController.java` — fully wired with VoP, SCA, idempotency, Kafka publishing. nginx misrouted the path to account-ledger. Fixed by updating `docker/client-web/nginx.conf` `/customer/payments` location to point at `psd2-api:8094`. |
| **F16** | 3 of 5 customer endpoints in OPEN-11 fixed by nginx routing | Updated `docker/client-web/nginx.conf`: `/customer/accounts` and `/customer/payments` now route to `psd2-api:8094` (controllers there); `/customer/beneficiaries` routes to `bill-payment:8093` (controller there). All three return 200 with real data after the fix. The remaining two (profile, privacy/gdpr) genuinely lack controllers — see OPEN-13. |
| **F17** | `ProfilePage.tsx` stuck on Loading when /profile fails (closes OPEN-12) | Refactored the early-return-on-null pattern into a single render that always shows the `<h1>` plus a separate slot for Loading vs. data. The page no longer disappears into an indefinite spinner when the backend is unreachable; the user sees the page title and the loading indicator below it. |

## Out of scope (deliberate non-goals)

| Topic | Reason |
|-------|--------|
| Real BankID merchant integration | Out of v1 scope — substituted with `BANKID_SIMULATED` in dev profile |
| Real PSP / RTGS | Out of v1 scope — clearing-engine settles to internal projection only |
| Real cloud KMS / HSM | Out of v1 scope — dev keys live in-memory |
| Real email / SMS | Out of v1 scope — notifications sinks to log + DB |
| Native Android / iOS build | Cannot build on this Linux host (no Android SDK / Xcode). See `PHASE_G_MOBILE_ROADMAP.md`. |

## Changelog

| Date | Action | Item |
|------|--------|------|
| 2026-04-29 | Opened | Phase-A scaffold under `implementation/e2e/`; ledger seeded with carry-over OPEN-1, OPEN-2 from prior session |
| 2026-04-29 | Closed (carry-over) | OPEN-2 — fresh-volume run shows MasterUC01SmokeTest 9/9 |
| 2026-04-29 | Fixed in code | F7 — validation handlers in PaymentExceptionHandler. Added 3 ExceptionHandlers + validation starter dep |
| 2026-04-29 | Opened | OPEN-3 — `/transfer` silent double-debit on duplicate TxnId. Pinned as regression-trap (`phaseB-payment-uc.spec.ts:110`) |
| 2026-04-29 | Opened | OPEN-4 — `UseCaseEndToEndTest.scenario1F` fails: applyDoubleEntry rejects credits-to-frozen. Pre-existing, out of scope this session |
| 2026-04-29 | Phase A | Cross-client harness scaffold green (3/3) |
| 2026-04-29 | Phase B | Payment UC saturation (8/8) including F7 fix verification |
| 2026-04-29 | Phase D | Cross-client invariants (3/3) — issuer parity, PII masking, audit-query reachability |
| 2026-04-29 | Phase E | Robustness sweep §1, §2, §6, §9, §10 (11/11) |
| 2026-04-29 | Phase F.1 | a11y scan on both SPA login pages (2/2) — no SERIOUS/CRITICAL violations |
| 2026-04-29 | Phase G | Mobile roadmap doc written: `PHASE_G_MOBILE_ROADMAP.md` |
| 2026-04-29 | Opened | OPEN-5 — `DevTokenController` 500 → 401 mapping. Pinned in 3 Phase-E specs as trap. |
| 2026-04-29 | Opened | OPEN-6 — `audit-query` 500 on `/api/v1/admin/audit?page=0&size=10`. Pinned in Phase-D spec as trap. |
| 2026-04-29 | Opened | OPEN-7 — Customer-payments REST controller missing in jrpay-bill-payment. Phase-D Kafka-chain spec deferred. |
| 2026-04-29 | Fixed in code | F8 — Android Room annotation processing switched from Java `annotationProcessor` to KSP. Verified live in emulator (login screen renders). |
| 2026-04-29 | Phase G | Android app booted in emulator with KVM acceleration; UC-G01 login screen confirmed cross-client-consistent with web SPA. Mobile-build path is no longer hypothetical. |
| 2026-04-29 | Fixed in code | F9 — API_BASE_URL points at client-web nginx (5173) so customer endpoints reverse-proxy correctly |
| 2026-04-29 | Fixed in code | F10 — network_security_config.xml permits cleartext for 10.0.2.2 / localhost / 127.0.0.1 only |
| 2026-04-29 | Opened | OPEN-10 — `NoResourceFoundException` (404) maps to 500 via generic handler. Same shape as F7. |
| 2026-04-29 | Phase G UI tests | 7 Compose UI tests added to `android-app/app/src/androidTest/.../uitest/`; all green on emulator. UC-G01, G02, G07, G14 covered. |
