# JRPay E2E — resume notes (2026-04-29)

State at end of session 2026-04-29:

- **38** cross-client web specs green (`implementation/e2e/`) — Phases A, B, D, D2, E, F.1
- **15** native Android Compose UI tests green (`android-app/app/src/androidTest/`)
- **53 cross-client specs total green**
- Backend smoke 51/51 green
- **11 production fixes committed in source**: F7 (validation handlers), F8 (Room→KSP), F9 (API_BASE_URL), F10 (cleartext config), F11 (TxnId dedup), F12 (credits-to-frozen allowed), F13 (DevToken 500→401), F14 (NoResourceFoundException→404), F15+F16 (nginx routing for 4 customer endpoints), F17 (ProfilePage error state)
- Open findings remaining: OPEN-1 (clearing-engine healthcheck mis-wire — harmless), OPEN-6 (audit-query REST surface absent), OPEN-13 (profile + GDPR customer controllers missing)
- Android emulator: Pixel 7 / Android 14 / x86_64 / KVM-accelerated; AVD name `jrpay`
- UCs covered (rendering-level on both clients): UC-G01, G02, G07, G08/09, G14, G16, G17/18, G19, G24, G26, G33

## Stack state on shutdown

The JRPay docker stack is running. Recommended power-down sequence:

```bash
cd implementation/docker
docker compose down            # keep the postgres volume so V008 doesn't
                                # need to re-run on next bring-up
```

To resume cold:

```bash
# Web stack + Playwright
cd implementation/docker
docker compose up -d
# wait ~3 minutes for Spring services to bind
cd ../e2e
npx playwright test            # expected: 27/27 passed in ~12s

# Android UI tests (requires running emulator with KVM)
cd ../android-app
emulator -avd jrpay -no-snapshot -no-boot-anim -gpu swiftshader_indirect -accel on &
adb wait-for-device
./gradlew :app:connectedDebugAndroidTest   # expected: 7/7 passed in ~6 min
```

The host has 7.4 GB RAM and the stack runs hot — don't co-host the
Android emulator or other heavy processes during E2E runs.

## What's NOT yet committed to git

This project is **not a git repo** (`git status` reports "fatal: not a git
repository"). All source-level changes since 2026-04-27 are uncommitted in
the working tree. Carry-over from the prior session plus this session:

### Production code changes this session
- `jrpay-security-starter/src/main/java/com/jrpay/starter/error/PaymentExceptionHandler.java`
  — F7 fix: 3 new `@ExceptionHandler`s (MethodArgumentNotValidException,
  ConstraintViolationException, MissingServletRequestParameterException).
- `jrpay-security-starter/build.gradle.kts` — added
  `spring-boot-starter-validation` dependency.

### From prior session (still uncommitted, see prior `E2E_RESUME.md`)
- `jrpay-account-ledger/src/main/java/com/jrpay/ledger/projection/AccountTransactionRepository.java`
- `jrpay-account-ledger/src/main/java/com/jrpay/ledger/command/AccountCommandHandler.java`
  (and the two test classes that had to be updated)
- `jrpay-security-starter-kafka/build.gradle.kts`
- `jrpay-account-ledger/src/main/java/com/jrpay/ledger/config/DemoSecurityConfig.java` (F1)
- `jrpay-domain-model/src/main/java/com/jrpay/domain/exception/SelfTransferException.java` (F2)
- `jrpay-account-ledger/src/main/java/com/jrpay/ledger/command/AccountCommandHandler.java` (F2)
- `jrpay-identity-service/src/main/resources/db/migration/V008__fix_demo_user_passwords.sql` (F3)
- `admin-web/src/pages/accounts/AccountDetailPage.tsx` (F4)
- `jrpay-psd2-api/src/main/java/com/jrpay/psd2/customer/consents/CustomerConsentController.java` (F5)

### New files this session (E2E suite)
- `implementation/e2e/` — entire package
  - `package.json`, `tsconfig.json`, `playwright.config.ts`, `README.md`
  - `OPEN_ITEMS.md`, `PHASE_COVERAGE.md`, `PHASE_G_MOBILE_ROADMAP.md`,
    `RESUME_NOTES.md` (this file)
  - `helpers/` (5 files)
  - `specs/` (6 files, 27 tests)

## Easy next-session pick-ups (highest yield first)

1. **OPEN-7 — implement customer-payments controller in jrpay-bill-payment.**
   Unblocks the production Kafka chain spec (the canonical demo path
   A3→A2→A5→A1) and the Phase F.3 chaos spec (pause Kafka mid-chain).
   Estimated 1–2 days.
2. **OPEN-5 — fix DevTokenController error mapping.** 5-line change in
   one file. Flips three Phase-E regression-traps from `expect([...,500])`
   to `expect([401])`. Single rebuild of identity image.
3. **OPEN-4 — drop `creditAccount.FROZEN` check in `AccountCommandHandler`.**
   3-line removal. Greens the pre-existing `scenario1F` unit test.
4. **OPEN-3 — TxnId dedup at /transfer.** Service-layer dedup or
   `INSERT ... ON CONFLICT DO NOTHING` returning a sentinel that gates
   the balance update. Flips the regression-trap in `phaseB:110` from
   980 → 990.
5. **OPEN-6 — investigate audit-query 500.** Probably a missing required
   query parameter; codify the contract in OpenAPI; flip the Phase-D
   regression-trap.

## Robustness categories left to cover (Phase E)

- §3 concurrent tabs (admin + customer same user, action in one is
  visible in the other after refresh)
- §4 stale data (admin updates while user has page open)
- §5 network flakiness (inject 500 on a key endpoint; user sees graceful
  degradation, not silent fixture)
- §7 long lists / many records (one user with N records, all surface
  correctly)

Each is one spec; expect 0–1 finding per category.

## Higher-bar items that need design work first

- **Driving the OAuth flow from Playwright.** Today specs cannot
  authenticate via the SPA UI because the BankID-simulated round-trip
  isn't scripted. A `seedAuthenticatedSession()` helper that calls
  `/dev/oauth2/dev-token` and then injects the cookies into the
  browser context would unlock authenticated-page tests. Probably
  4–6 hours to design and implement.
- **Step-up SCA for admin actions** (UC-G35 freeze, UC-G36 PII reveal).
  Prior session got it working with TOTP + dynamic-linking inputs;
  scripting the dance is straightforward but tedious. A
  `acquireStepUpToken(action, resource)` helper would unlock all admin
  write-flow tests.
- **Real perf-smoke** — needs a host with more RAM, or a remote stack,
  to run N concurrent flows without thrashing. Not blocking; document
  the runbook and defer.

## Where everything lives

- E2E suite root: `implementation/e2e/`
- Backend: `implementation/jrpay-*` (9 services + 2 starters)
- Compose: `implementation/docker/docker-compose.yml`
- Bug ledger: `implementation/e2e/OPEN_ITEMS.md`
- Phase coverage: `implementation/e2e/PHASE_COVERAGE.md`
- This file: `implementation/e2e/RESUME_NOTES.md`
- Runbook: `implementation/e2e/README.md`
