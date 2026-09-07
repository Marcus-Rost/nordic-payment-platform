# Build Guide — Payment Processing App

How to build, test, run, and demo this app from a clean checkout.
Written to be reproducible by any developer on any machine, and to serve
as a template for future secure apps.

---

## Prerequisites

| Tool | Version | Install |
|---|---|---|
| JDK | 21 | `sdk install java 21.0.6-tem` |
| Gradle | 8.12+ | `sdk install gradle 8.12` |
| Docker + Compose | any recent | system package manager |
| RAM | ~4 GB free | services use `-Xmx256m` each |

After installing via SDKMAN, ensure the tools are on your PATH:

```bash
source ~/.sdkman/bin/sdkman-init.sh
java -version    # should print 21.x
gradle --version # should print 8.12+
```

---

## 1. Build and Run Unit Tests (Layers 1-3)

All paths in this guide are relative to `implementation/`.

```bash
cd implementation
gradle build
```

This compiles all 10 subprojects and runs ~250 tests across layers 1-3
(unit, subproject use case, master use case). Expected: `BUILD SUCCESSFUL`.

These tests need no infrastructure — they run entirely in-process with H2.

---

## 2. Start Infrastructure

```bash
cd implementation/docker
docker compose up -d
```

This starts:
- **PostgreSQL 16** on port 5432 (with ledger, clearing, billpay, psd2 databases)
- **Kafka** (KRaft mode, single broker) on port 9092

Wait for both to become healthy:

```bash
docker compose ps
```

Both should show `healthy` in the STATUS column. PostgreSQL takes ~10 seconds,
Kafka takes ~30-60 seconds. Kafka's healthcheck can be slow — if it shows
`unhealthy` but `kafka-topics --bootstrap-server localhost:9092 --list` works,
it is fine.

---

## 3. Start Services

### Critical: the `docker` profile hostname problem

The `application-docker.yml` files use Docker container hostnames (`postgres`,
`kafka`) which only resolve inside Docker networks. When running services
directly on the host, you **must override** these to `localhost`.

**This is the single biggest source of startup failures.** If a service fails
with `UnknownHostException: postgres` or `UnknownHostException: kafka`, this
is the cause.

### A1 — Account Ledger (port 8091, management port 9091)

```bash
cd implementation

java -Xmx256m \
  -Dspring.profiles.active=docker \
  -Dspring.datasource.url=jdbc:postgresql://localhost:5432/ledger \
  -Dspring.datasource.username=jrpay \
  -Dspring.datasource.password=jrpay \
  -Dspring.kafka.bootstrap-servers=localhost:9092 \
  -jar jrpay-account-ledger/build/libs/jrpay-account-ledger-0.1.0-SNAPSHOT.jar
```

### A6 — VoP Service (port 8096, management port 9096)

A6 has no database. It only needs the docker profile for security config:

```bash
java -Xmx256m \
  -Dspring.profiles.active=docker \
  -jar jrpay-vop-service/build/libs/jrpay-vop-service-0.1.0-SNAPSHOT.jar
```

### Verify services are up

Health endpoints are on **management ports**, not app ports:

```bash
curl http://localhost:9091/actuator/health   # A1 → {"status":"UP"}
curl http://localhost:9096/actuator/health   # A6 → {"status":"UP"}
```

Do **not** check health on the app ports (8091/8096) — the security error
handler intercepts `/actuator/health` there and returns a 500 with an
RFC 9457 error body. This is by design: the app port enforces authentication
on all paths; the management port is separate and public.

### Startup time

Each service takes 30-90 seconds on machines with limited RAM. On first run
Flyway applies database migrations, which adds ~10 seconds.

### If a port is already in use

```bash
fuser -k 8091/tcp   # kill process on A1 port
fuser -k 8096/tcp   # kill process on A6 port
```

---

## 4. Run the Demo

```bash
cd implementation
bash scripts/run-demo.sh
```

The demo creates two accounts, runs VoP verification (match + privacy test),
and shows balances. Expected: all steps show green checkmarks.

### Stale database state

The demo uses hardcoded account IDs (`NO9386011117947`, `NO1234567890123`).
If the database already has these accounts from a previous run, the demo still
works — account creation is effectively idempotent (returns 200), but balances
will reflect accumulated state. To start fresh:

```bash
cd implementation/docker
docker compose down -v   # -v removes the PostgreSQL volume
docker compose up -d     # recreates with clean databases
```

Then restart the services.

### Manual demo (step by step)

See `implementation/demo/DEMO_INSTRUCTIONS.md` for individual curl commands
if you prefer to run the demo manually or need to debug a specific step.

---

## 5. Run Smoke Tests (Layer 4)

Smoke tests require A1 and A6 to be running (see step 3).

```bash
cd implementation
gradle :jrpay-smoke-tests:test
```

Expected: 28 tests pass. If services are not running, the tests skip
gracefully (JUnit `Assumptions`), reporting 0 tests run — not failures.

### If smoke tests fail after a folder move or clean checkout

This happened to us: the build and all unit tests passed, but smoke tests
failed after reorganizing the folder structure. The root causes were:

1. **Health check on wrong port.** Smoke tests checked `/actuator/health`
   on the app port (8091/8096), which returns 500 because the security
   error handler intercepts it. The management ports (9091/9096) are correct.

2. **Stale database.** Accounts from previous runs accumulate. Tests that
   assert exact initial balances can fail. Use `docker compose down -v` to
   reset.

3. **Services started with wrong profile overrides.** The `docker` profile
   uses container hostnames. Must override with `-Dspring.datasource.url`
   and `-Dspring.kafka.bootstrap-servers` pointing to `localhost`.

4. **Cached test results.** Gradle caches test results. After fixing test
   code, run `gradle :jrpay-smoke-tests:cleanTest` before re-running.

---

## 6. Shut Down

```bash
# Stop services
pkill -f "AccountLedgerApplication"
pkill -f "VoPServiceApplication"

# Stop infrastructure
cd implementation/docker
docker compose down       # keeps data
docker compose down -v    # removes data (clean slate for next run)
```

---

## Architecture of the Test Framework

The project uses four test layers, each catching defects the others cannot:

| Layer | What | Infrastructure | Count |
|---|---|---|---|
| 1. Unit tests | Individual classes, algorithms | None | ~130 |
| 2. Subproject use case tests | End-to-end within one service | Spring context, H2 | ~73 |
| 3. Master use case tests | System-level cross-service logic | None | ~51 |
| 4. Smoke tests | Real HTTP, real auth, real DB | Running services + Docker | ~28 |

See `implementation/TEST_FRAMEWORK.md` for the full rationale.

---

## Authentication

All API endpoints require HTTP Basic auth. The `docker`/`demo` profile
activates `DemoSecurityConfig` with in-memory test users:

| Username | Password | Role |
|---|---|---|
| admin | admin | ADMIN |
| a3-service | a3-service | SERVICE |
| a6-service | a6-service | SERVICE |

Security is **never disabled** — not even for testing. The smoke tests
and demo script authenticate with `admin:admin`.

---

## Adapting This Template for Other Secure Apps

### What to keep

- **Four-layer test framework.** Unit, subproject use case, master use case,
  smoke. Each layer has a specific purpose. Do not skip the smoke layer —
  it catches an entire class of defects (security misconfiguration,
  serialization, cross-service integration) that in-process tests cannot.

- **Security-first starter.** The `jrpay-security-starter` provides deny-all
  default, OWASP headers, and RFC 9457 error responses. New apps should
  depend on this (or a renamed equivalent) rather than configuring security
  per service.

- **DemoSecurityConfig pattern.** Never disable security. Create a profile-
  activated config with in-memory test users instead. This ensures the demo
  and smoke tests exercise real authentication.

- **Separate management port.** Spring Boot's `management.server.port`
  isolates health/readiness endpoints from the app port. This avoids the
  problem of security interceptors blocking health checks.

- **Review-driven development.** Requirements, architecture, then
  implementation — with systematic reviews at each stage. The 11 review
  categories (architecture, security, financial integrity, regulatory
  compliance, crash resilience, etc.) catch design-level issues before
  they become code-level bugs.

- **Docker Compose for local infrastructure.** Single PostgreSQL with
  multiple databases (saves RAM vs. separate instances). Kafka in KRaft
  mode (no Zookeeper). Both with healthchecks.

### What to change

- **Service names and ports.** Replace `jrpay-*` with app-specific names.
  Assign non-overlapping port ranges per app (e.g., 8101-8106 for app 2).

- **Database names.** Update `init-databases.sql` with app-specific databases.

- **Kafka topics.** Update `create-topics.sh` with app-specific topic names.

- **Domain model.** The `jrpay-domain-model` contains payment-specific types
  (MonetaryAmount, Currency, PaymentStatus). New apps should create their own
  domain model, but can reuse shared types if the apps overlap.

- **Use cases.** Derive subproject and master use case tests from the new
  app's use case documents, not from payment processing use cases.

### Lessons learned (things that broke and why)

**1. The profile hostname trap.**
The `application-docker.yml` uses container hostnames (`postgres`, `kafka`)
that only work inside Docker networks. When running services on the host
machine, you must override with `localhost`. This is not obvious, breaks
silently (service starts but fails to connect), and is the number one cause
of "it worked yesterday" failures. Future apps should define a `local`
profile with `localhost` hostnames, separate from the `docker` profile.

**2. Health check port confusion.**
When `management.server.port` is set, health endpoints are NOT on the app
port. Code that checks health on the app port gets a 500 from the security
error handler — which looks like a broken service. This caused smoke test
`Assumptions` to skip all tests silently (0 tests run, BUILD SUCCESSFUL),
which looks like success but is actually a silent skip. Future apps should
log a clear message when smoke tests skip due to service unavailability.

**3. Stale database state across runs.**
Demo scripts and smoke tests that use hardcoded account IDs will see
accumulated state across runs. Tests should either use random IDs (smoke
tests do this — `UUID.randomUUID()`) or document that `docker compose down -v`
is needed for a clean run. The demo script uses fixed IDs for readability
and accepts accumulated state.

**4. Gradle caches test results.**
After changing test source code, `gradle test` may report UP-TO-DATE and
not re-execute tests. Use `gradle cleanTest test` to force re-execution.
This is especially confusing when fixing a failing test — the fix looks
like it didn't work because Gradle served the cached failure.

**5. The grep-for-null-value trap.**
The demo script checked VoP privacy by grepping for the JSON key
`"actualName"` — but the key is present with value `null`, which is correct
(name not revealed). The grep matched the key and reported a false security
failure. When checking JSON responses in shell scripts, match the value
pattern (`"actualName"\s*:\s*"[^"]`), not just the key name.

**6. Folder moves break nothing in code, but everything in scripts.**
Moving the project folder does not affect Gradle builds or Java tests —
they use relative paths. But shell scripts, documentation, and muscle
memory all break. After a folder reorganization: re-run the full build,
re-run smoke tests, re-run the demo, and update all absolute paths in
documentation. Do not assume "nothing changed" because the code didn't
change.

---

## Quick Reference

```bash
# From implementation/ directory:

# Build + unit tests
gradle build

# Start infrastructure
cd docker && docker compose up -d && cd ..

# Start services (in background)
java -Xmx256m -Dspring.profiles.active=docker \
  -Dspring.datasource.url=jdbc:postgresql://localhost:5432/ledger \
  -Dspring.datasource.username=jrpay -Dspring.datasource.password=jrpay \
  -Dspring.kafka.bootstrap-servers=localhost:9092 \
  -jar jrpay-account-ledger/build/libs/jrpay-account-ledger-0.1.0-SNAPSHOT.jar &

java -Xmx256m -Dspring.profiles.active=docker \
  -jar jrpay-vop-service/build/libs/jrpay-vop-service-0.1.0-SNAPSHOT.jar &

# Wait for startup, then verify
curl http://localhost:9091/actuator/health
curl http://localhost:9096/actuator/health

# Run demo
bash scripts/run-demo.sh

# Run smoke tests
gradle :jrpay-smoke-tests:test

# Clean shutdown
pkill -f "AccountLedgerApplication"; pkill -f "VoPServiceApplication"
cd docker && docker compose down
```
