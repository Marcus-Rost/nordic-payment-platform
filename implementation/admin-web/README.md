# JRPay Admin Web — operator console

React 19 + TypeScript 5.6 + Vite 6 + Tailwind 3 single-page application.
Phase 3 of the GUI-extension initiative; covers UC-G30…UC-G54 use-cases:
fraud queue + decide, sanctions queue + decide, account search +
freeze/unfreeze/close/create, PII reveal with TTL countdown, audit search
+ hash-chain integrity, system health + DLQ, and operator administration.

## Stack

| Concern              | Choice                                                |
| -------------------- | ----------------------------------------------------- |
| Build / dev server   | Vite 6 (port 5174 — matches identity OAuth callback)  |
| Framework            | React 19 + react-router-dom 7                         |
| Type system          | TypeScript 5.6 strict                                 |
| Styling              | Tailwind 3 (JRPay theme + score-band colours)         |
| i18n                 | i18next + react-i18next, nb-NO + en-US                |
| Auth                 | OAuth2 PKCE against the identity service              |
| Step-up SCA          | UC-G51 dynamic-linking (RFC 8785 + SHA-256)           |
| Tests                | Vitest + @testing-library/react (unit), Playwright (E2E) |
| Production runtime   | nginx 1.27-alpine via multi-stage Dockerfile          |

## Layout

```
admin-web/
├── src/
│   ├── api/         # typed fetch wrapper, RFC 9457 problem decoding, OpenAPI codegen
│   ├── auth/        # PKCE, JWT decoding, AuthContext, login + callback pages
│   ├── sca/         # dynamic-linking hash, SCA modal + client
│   ├── i18n/        # i18next bundle (nb-NO + en-US)
│   ├── components/  # Modal, Toast, StatusBanner, Countdown
│   ├── app/         # App, AppShell (header + nav), AuthGuard / RoleGuard
│   ├── pages/       # Dashboard + per-domain pages (fraud, sanctions, accounts, audit, system, users)
│   ├── styles/      # Tailwind entrypoint + component layer (.btn, .input, .badge)
│   └── test/        # Vitest setup
├── e2e/             # Playwright specs
├── scripts/         # OpenAPI codegen wrapper
├── Dockerfile       # Multi-stage build (Node → nginx)
└── playwright.config.ts
```

## Use-case coverage

| Use case          | Page / component                                                   |
| ----------------- | ------------------------------------------------------------------ |
| UC-G30 sign-in    | `auth/LoginPage`, `auth/CallbackPage`                              |
| UC-G31 fraud queue| `pages/fraud/FraudQueuePage` (filters + counters + SSE live updates) |
| UC-G32 / G33      | `pages/fraud/FraudCaseDetailPage` (decide modal + SCA replay)      |
| UC-G34 sanctions  | `pages/sanctions/SanctionsQueuePage`, `SanctionsHitDetailPage`     |
| UC-G35 accounts   | `pages/accounts/AccountSearchPage`, `AccountDetailPage`            |
| UC-G36 PII reveal | `pages/accounts/AccountDetailPage` (per-field reveal + TTL + extend) |
| UC-G40 audit      | `pages/audit/AuditSearchPage` (search + chain integrity + verify)  |
| UC-G42 system     | `pages/system/SystemHealthPage` (health + queues + SLA tiles + DLQ)|
| UC-G43 users      | `pages/users/UsersAdminPage`                                       |
| UC-G50 logout     | `app/AppShell` header                                              |
| UC-G51 SCA        | `sca/ScaModal` + `sca/dlHash`                                      |
| UC-G52 expiry     | `auth/SessionExpiryWarning`                                        |
| UC-G53 refresh    | `auth/oauth.refreshAccessToken` (driven by AuthContext tick)       |
| UC-G54 banners    | `components/StatusBanner` (SSE wiring on the AppShell)             |

## Local development

```bash
cd implementation/admin-web
npm install
npm run dev            # http://localhost:5174
```

Backend is expected on the canonical compose ports (identity 8097,
account-ledger 8091, fraud-detection 8095, audit-query 8099). The Vite dev
server proxies every `/oauth2`, `/sca`, `/.well-known` and `/api/v1/admin/*`
prefix to the right service so the SPA stays same-origin.

### Tests

```bash
npm run typecheck      # tsc --noEmit
npm test               # Vitest unit suite (39 tests across api/auth/sca/components/pages)
npm run test:e2e       # Playwright (assumes the stack + `npm run preview` are up)
```

### Production build

```bash
npm run build          # tsc -b && vite build → ./dist
docker build -f admin-web/Dockerfile -t jrpay-admin-web .
```

The image multi-stages on `node:20-alpine` (build) → `nginx:1.27-alpine`
(serve). The nginx config sits at `docker/admin-web/nginx.conf` and:

- emits the GUI-SEC-03 security headers (CSP, X-Frame, Referrer, HSTS,
  Permissions-Policy, X-Content-Type-Options);
- reverse-proxies every backend admin surface using compose hostnames
  (`identity`, `account-ledger`, `fraud-detection`, `audit-query`) with a
  `127.0.0.11` (Docker DNS) resolver so the container starts even when an
  upstream is down or absent;
- preserves SSE chunked encoding (`proxy_buffering off`,
  `proxy_read_timeout 1h`) for `/api/v1/admin/{fraud,sanctions}/*/stream`;
- fingerprinted assets cached `immutable` for one year, with SPA fallback to
  `index.html` for deep-link routes.

## Compose

`docker-compose.yml` builds the image alongside the backend stack:

```yaml
admin-web:
  build:
    context: ..
    dockerfile: admin-web/Dockerfile
  ports: ["5174:80"]
  depends_on: [identity, account-ledger, fraud-detection, audit-query]
```

`make compose-up` (or `docker compose up -d` from `implementation/docker`)
brings everything up; the SPA is reachable on http://localhost:5174.

## Security notes

- All mutating actions (decide / freeze / unfreeze / close / create / role
  change / verify-chain / reveal-pii) run UC-G51 SCA: the operator types
  TOTP into the `<ScaModal>`, the resulting `Step-Up-Token` is forwarded as
  the `Step-Up-Token` header alongside the auto-generated `Idempotency-Key`.
- `dl_hash` is computed in TypeScript via the byte-identical canonical-JSON
  algorithm used by the Java services (RFC 8785 subset, SHA-256, lowercase
  hex). Verified by `src/sca/__tests__/dlHash.test.ts`.
- Bearer tokens live in `sessionStorage`; expiry is enforced on every
  `useAuth()` tick, with a UC-G52 5-minute warning toast.
- `AuthGuard` redirects unauthenticated traffic to `/login`; `RoleGuard`
  filters per-feature surfaces by role + scope (FS.10).

## Outstanding follow-ups

- Status banner SSE wiring (UC-G54) — current `StatusBanner` accepts a
  static prop; a hook around `/api/v1/public/status/stream` is the natural
  follow-on once the status-aggregator backend ships.
- Bulk-decide flow (UC-G31 AF-01 / UC-G33 AF-05) — the API spec is in
  `admin-fraud.yaml`; the SPA currently exposes only per-case decide.
- DLQ retry / purge buttons in `SystemHealthPage` (the panel renders the
  state but the SCA-protected actions are still TODO).
- Two-supervisor approval queue (`UsersAdminPage` ADMIN-grants) — the spec
  exposes `/users/approvals/{ref}`; UI is currently simple and trusts
  the server's redirect.
