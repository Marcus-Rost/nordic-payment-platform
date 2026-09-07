# JRPay cross-client E2E suite

End-to-end tests that drive **admin-web (5174)** + **client-web (5173)** + the
JRPay backend (A1–A6, identity, audit-query, notifications) in a single
Playwright run. These tests exist to assert **cross-client invariants** like
"customer submits a payment, admin sees it" — concerns that don't belong to
either client's own e2e folder.

Per `space/E2E_TESTING_GUIDE.md`, this package is the Phase-A scaffold. The
phase pyramid the suite climbs:

| Phase | What it adds | Status |
|-------|--------------|--------|
| **A** | Scaffold + 1 demo + this runbook | ✅ in progress |
| **B** | Saturate simplest UC (4–8 specs) | ⏳ pending |
| **C** | Synthetic-with-loud-marker fixtures | ⏳ pending |
| **D** | Cross-client per testable UC | ⏳ pending |
| **E** | Robustness sweep (10 categories) | ⏳ pending |
| **F** | a11y + perf-smoke + chaos | ⏳ pending |
| **G** | Mobile (deferred per guide; roadmap doc only) | 📋 documented |

## Cold-clone runbook

Assumes a clean machine with Docker, Node.js ≥ 20, and `bash`. Targets a
green run from `git clone` in three commands.

### 1 — bring up the JRPay backend stack

```bash
cd implementation/docker
docker compose down -v        # belt-and-braces; ensures the postgres volume
                               # is fresh so V008 (bcrypt-fix) actually runs
docker compose up -d
```

Wait until every service reports healthy or the app port is bound. On modest
hardware (8 GB RAM) all services come up in ~3 minutes; the first cold-image
build may take longer. To check:

```bash
docker compose ps --format "table {{.Service}}\t{{.Status}}"
```

`clearing-engine` will show `unhealthy` even when functionally OK — its
docker-compose `MGMT_PORT=9112` doesn't match the in-app management port the
service binds. The service runs fine; the healthcheck is mis-wired. (Tracked
in `OPEN_ITEMS.md`; harmless for E2E.)

### 2 — install Playwright + browsers (one-time)

```bash
cd implementation/e2e
npm install
npx playwright install chromium
```

The Chromium binary is cached at `~/.cache/ms-playwright/`; subsequent runs
re-use it.

### 3 — run the suite

```bash
npm run test
```

Expected first-run output for Phase A: **3 passed** (scoped to the demo
spec) plus `[globalSetup] OK — N/N services responding`.

If the stack isn't up, you'll see `N skipped` (with a clear message naming
which services are down) — never red without cause.

To inspect failures:

```bash
npm run report        # opens the HTML report locally
ls test-results/      # traces / videos / screenshots for failed specs
```

## Layout

```
e2e/
├── playwright.config.ts        # one-place config; globalSetup wired here
├── package.json                # @jrpay/e2e — own deps, doesn't graft onto admin-web/client-web
├── tsconfig.json
├── README.md                   # this file
├── OPEN_ITEMS.md               # bug ledger (every finding gets an OPEN-N id)
├── helpers/
│   ├── endpoints.ts            # service URLs, demo creds, REQUIRED_FOR_E2E list
│   ├── globalSetup.ts          # probes all services, writes preflight file
│   ├── preflight.ts            # specs use requireServices() to skip cleanly
│   ├── auth.ts                 # /dev/oauth2/dev-token client
│   └── freshId.ts              # per-run unique IDs (false-green defense)
├── fixtures/                   # synthetic-with-loud-marker payloads
└── specs/
    ├── phaseA-cross-client-smoke.spec.ts   # demo
    ├── phaseB-*.spec.ts                    # simplest UC saturation (later)
    ├── phaseD-*.spec.ts                    # cross-client per UC (later)
    ├── robustness-*.spec.ts                # Phase E (later)
    └── perf-*.spec.ts / a11y-*.spec.ts     # Phase F (later)
```

## Conventions

- **Two `BrowserContext` instances per cross-client test**, never two pages
  on one context. Each context has its own cookie jar; auth on one doesn't
  leak to the other (E2E_TESTING_GUIDE.md §3.6).
- **Per-run unique identity** — `freshTag()`, `freshUuid()`, `freshTestIban()`
  to defend against fixture-fallback false-greens (§4.8).
- **`requireServices(...)`** at the top of every spec. Stack-down → `skipped`
  with a clear message, never red without cause (§3.1).
- **Spec naming**: `phase<X>-<scenario>.spec.ts`, `robustness-<category>.spec.ts`,
  `<phase>-<thing>.spec.ts` (§5).
- **Comment block at top of every spec** explains: what it proves, what it
  deliberately does NOT prove, false-green hazards, status (real / regression-trap).

## Demo credentials (dev profile only)

| Audience | Username | Password | Notes |
|---------|----------|----------|-------|
| admin    | `admin@jrpay.example`         | `demo-password-1!` | Seeded by V003 + bcrypt-fixed by V008 |
| customer | `ola.nordmann@example.com`    | `demo-password-1!` | Seeded by V003 + bcrypt-fixed by V008 |

If `dev-token` fails with `Bad credentials`, V008 didn't run — drop the
postgres volume and re-up the stack (`docker compose down -v && docker compose up -d`).

## Where bugs go

Every finding goes into `OPEN_ITEMS.md` with an `OPEN-N` id. See the file
for the running ledger; per the guide the success metric is **bugs found and
fixed**, not specs written.

## What this suite does NOT cover (deliberate non-goals)

- Real BankID merchant integration (substituted with `BANKID_SIMULATED`).
- Real PSP / Real-Time Gross Settlement integration.
- Real cloud KMS / HSM backed signing keys.
- Real email / SMS delivery (notifications uses an in-process sink).
- Native Android / iOS clients (cannot build on Linux dev host; see
  `PHASE_G_MOBILE_ROADMAP.md`).

These are documented as deliberate non-goals per E2E_TESTING_GUIDE.md §0.
