# JRPay E2E test session — resume notes

Session name: **EU261-E2E** (Phase 4 + GUI-to-GUI end-to-end testing)
Saved: 2026-04-27 around 06:00 UTC

## What was achieved

### Bug fixes (committed in source, NOT yet committed to git)
1. **`jrpay-account-ledger/src/main/java/com/jrpay/ledger/projection/AccountTransactionRepository.java`**
   Split the single null-tolerant JPQL query into 4 explicit overloads + a default
   dispatcher to avoid Postgres "could not determine data type of parameter" on
   `(:from IS NULL OR posted_at >= :from)`. Same pattern as `searchInbox` /
   `searchInboxWithQuery` elsewhere.

2. **`jrpay-account-ledger/src/main/java/com/jrpay/ledger/command/AccountCommandHandler.java`**
   `applyDoubleEntry` now also writes both legs into `account_transactions` so
   the admin transaction-list view actually shows the rows. Added
   `AccountTransactionRepository` constructor arg; updated the two unit tests
   (`UseCaseEndToEndTest`, `AccountCommandHandlerTest`) to inject the new repo.

3. **`jrpay-security-starter-kafka/build.gradle.kts`**
   Empty placeholder so the docker-compose Dockerfile.service `COPY` step succeeds
   (the module had src/ but no build.gradle.kts).

### Runtime patch (LOST on Postgres volume reset, REQUIRED for tests)
- The seeded bcrypt hash for `admin@jrpay.example` and
  `ola.nordmann@example.com` in `V003__seed_demo_users.sql` does NOT match the
  password `demo-password-1!` that the smoke tests use. Patched at runtime via:
  ```
  python3 -c "import bcrypt; print(bcrypt.hashpw(b'demo-password-1!', bcrypt.gensalt(rounds=10)).decode())"
  # then: UPDATE identity_users SET password_hash = '<hash>' WHERE username IN (...)
  ```
  The DB volume `docker_postgres-data` IS persistent, so the patch should survive
  a docker compose stop / start. It does NOT survive a `docker volume rm`.
  PROPER FIX: regenerate the hash and commit a corrected V003 / V004 migration.

### Tests already proven on the live stack
- IdentitySmokeTest: 7/7 pass.
- MasterUC01SmokeTest: 7/9 pass (2 VoP failures are seed-data issues, not
  product bugs — A6 doesn't have the test creditors in its payee directory).
- Customer payment via `/api/v1/accounts/transfer` settles atomically
  (debtor 50000→48750, creditor 10000→11250, conservation holds).
- After the bug-fix #2, `account_transactions` now has both DEBIT + CREDIT rows.
- Admin endpoint `/api/v1/admin/accounts/{id}/transactions` works (was 500
  before bug-fix #1) — returns rows with masked PII (`Kari H*****`,
  `NO-C •••• •••• •• 6916`).
- Admin search by holder name returns masked accounts (operator can find Kari).

### Docker images built and ready
```
docker-account-ledger:latest    (rebuilt with the two bug fixes)
docker-bill-payment:latest
docker-clearing-engine:latest
docker-fraud-detection:latest
docker-identity:latest
docker-notifications:latest
docker-vop:latest
docker-audit-query:latest       (built but never started)
docker-psd2:latest              (built but never started; service is "psd2-api"
                                 in compose but module is "jrpay-psd2-api"; tag
                                 may differ — verify with `docker images`)
jrpay-admin-web:phase3
jrpay-client-web:phase4
```

## Test plan (T1–T13) — current status

| ID  | Subject                                                           | Status |
| --- | ----------------------------------------------------------------- | ------ |
| T1  | Run existing Master UC01/07/09 smoke suite                        | DONE (partial — 2 VoP failures are test-seed issues) |
| T2  | Production payment path A3→A2→A5→A1 via Kafka                     | NOT STARTED — bill-payment endpoint identified |
| T3  | UC-G35 admin freeze/unfreeze/close                                | NOT STARTED |
| T4  | UC-G36 PII reveal with audit + auto-remask                        | NOT STARTED |
| T5  | UC-G31 fraud queue + UC-G32/33 decide                             | NOT STARTED |
| T6  | UC-G40 audit search + hash-chain integrity verify                 | BLOCKED on audit-query container |
| T7  | UC-G16 beneficiaries CRUD with VoP binding                        | NOT STARTED |
| T8  | UC-G17/G18 PSD2 consent grant + revoke                            | BLOCKED on psd2-api container |
| T9  | UC-G08/G09 AvtaleGiro mandate setup + amend + terminate           | NOT STARTED |
| T10 | UC-G24 GDPR data export request                                   | NOT STARTED |
| T11 | Web GUI containers reverse-proxy verification                     | NOT STARTED |
| T12 | UC-G19 fraud-hold notification + held-payment release             | NOT STARTED |
| T13 | Conservation-of-money invariant check                             | NOT STARTED |

## How to resume

1. **Power up** and bring the stack back:
   ```bash
   cd <workspace>/secure_apps/apps/payment_processing/implementation/docker
   docker compose up -d postgres kafka identity vop account-ledger \
       bill-payment clearing-engine fraud-detection notifications \
       audit-query psd2-api
   ```

2. **Verify the bcrypt patch persisted** (Postgres volume should retain it):
   ```bash
   curl -s -H "Content-Type: application/json" -X POST \
       http://localhost:8097/dev/oauth2/dev-token \
       -d '{"username":"admin@jrpay.example","password":"demo-password-1!","audience":"admin"}' \
       | python3 -m json.tool
   ```
   Expect: `{"access_token":"eyJ...","token_type":"Bearer",...}`. If you see
   "Bad credentials", re-run the bcrypt patch from the Runtime patch section
   above.

3. **Continue with T2 onwards**. The bill-payment endpoint to drive the
   production Kafka path is `POST /api/v1/customer/payments` on port 8093 —
   inspect:
   ```bash
   grep -rn "PostMapping" implementation/jrpay-bill-payment/src/main/java/com/jrpay/billpay/customer/ | grep -i payment
   ```

4. **Test the GUIs through containers**:
   ```bash
   docker compose up -d admin-web client-web
   curl -s http://localhost:5174/healthz   # admin
   curl -s http://localhost:5173/healthz   # client
   ```

5. **Optional Playwright on the actual SPAs**:
   ```bash
   cd implementation/admin-web && npm run test:e2e
   cd implementation/client-web && npm run test:e2e
   ```

## Known issues to fix

1. **`clearing-engine` healthcheck mis-configured**: app binds 8092/9082, compose
   env declares MGMT_PORT=9112 and maps host ports 8112/9112. Service is
   functionally fine (Kafka consumers running) but `docker compose ps` shows
   "unhealthy". Decide which port set is correct, fix one side.

2. **VoP seed mismatch**: `MasterUC01SmokeTest` creates accounts in A1 but A6's
   payee directory is independently seeded. The smoke test expects MATCH but
   gets NOT_FOUND. Either pre-seed A6 with the test creditors or relax the
   assertion to accept NOT_FOUND for unknown IBANs.

3. **bcrypt hash in V003**: the committed hash doesn't match the documented
   password. Either regenerate or drop the seed and let identity create the
   admin user via a startup hook.

4. **clearing-engine + notifications restart races**: when notifications hit
   the port-bind error first time, its docker DNS state went stale and
   `UnknownHostException: postgres`. `docker compose rm -fs notifications` +
   re-up resolved it. Worth investigating whether compose `depends_on:
   condition: service_healthy` should be tighter.

## Files of interest

- `<workspace>/secure_apps/apps/payment_processing/E2E_RESUME.md` — this file
- `/tmp/jrpay-e2e.env` — DEBTOR/CREDITOR/TXN ids from the run (will be lost on
  reboot; reseed accounts after resume)
- The two source-level bug fixes listed above (NOT yet `git commit`-ed)
