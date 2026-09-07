# JRPay Client Web — customer SPA

React 19 + TypeScript 5.6 + Vite 6 + Tailwind 3 single-page application for
JRPay's end-customer surface. Phase 4A of the GUI-extension initiative;
covers UC-G01…UC-G26 customer use-cases (login, dashboard, accounts,
payments, mandates, R2P, beneficiaries, consents, notifications, profile,
GDPR/privacy).

## Stack

| Concern              | Choice                                                |
| -------------------- | ----------------------------------------------------- |
| Build / dev server   | Vite 6 (port 5173 — matches identity OAuth callback)  |
| Framework            | React 19 + react-router-dom 7                         |
| Type system          | TypeScript 5.6 strict                                 |
| Styling              | Tailwind 3 (JRPay theme)                              |
| i18n                 | i18next + react-i18next, nb-NO + en-US                |
| Auth                 | OAuth2 PKCE against the identity service (BankID)     |
| Step-up SCA          | UC-G51 dynamic-linking (RFC 8785 + SHA-256)           |
| Tests                | Vitest + @testing-library/react (unit), Playwright (E2E) |
| Production runtime   | nginx 1.27-alpine via multi-stage Dockerfile          |

## Layout

```
client-web/
├── src/
│   ├── api/         # typed fetch wrapper, RFC 9457 problem decoding
│   ├── auth/        # PKCE, JWT decoding, AuthContext, login/callback
│   ├── sca/         # dynamic-linking hash (4 classes), SCA modal + client
│   ├── i18n/        # i18next bundle (nb-NO + en-US)
│   ├── components/  # Modal, Toast, StatusBanner
│   ├── app/         # App, AppShell (header + nav), AuthGuard
│   ├── pages/       # Dashboard + per-domain pages
│   ├── lib/         # locale-aware formatters
│   ├── styles/      # Tailwind entrypoint + component layer
│   └── test/        # Vitest setup
├── e2e/             # Playwright specs
├── Dockerfile       # Multi-stage build (Node → nginx)
└── playwright.config.ts
```

## Use-case coverage

| Use case          | Page / component                                                   |
| ----------------- | ------------------------------------------------------------------ |
| UC-G01 sign-in    | `auth/LoginPage`, `auth/CallbackPage`                              |
| UC-G02 accounts   | `pages/DashboardPage` (account list + balances)                    |
| UC-G03 transactions| `pages/accounts/AccountDetailPage` (paginated + filtered)         |
| UC-G04 statements | `AccountDetailPage.exportStatement` (CSV async)                    |
| UC-G05 instant payment | `pages/payments/PaymentsPage` (VoP + dynamic-linking SCA)     |
| UC-G07 scheduled  | same page, `mode=SCHEDULED`                                        |
| UC-G08/G09 mandates| `pages/mandates/MandatesPage` (CREATE/AMEND/TERMINATE flows)      |
| UC-G11–G14 R2P    | `pages/r2p/R2pInboxPage` (accept/decline/defer/amend + SSE)        |
| UC-G16 beneficiaries| `pages/beneficiaries/BeneficiariesPage` (VoP-bound add)          |
| UC-G17/G18 consents| `pages/consents/ConsentsPage` (revoke)                            |
| UC-G19/G20 notifications| `pages/notifications/NotificationsPage` (inbox + prefs)      |
| UC-G21 prefs      | same page (preferences section)                                    |
| UC-G22/G23 profile| `pages/profile/ProfilePage` (edit + language + SCA factors)        |
| UC-G24/G25/G26 GDPR| `pages/privacy/PrivacyPage` (export + erasure + consent log)      |
| UC-G50 logout     | `app/AppShell` header                                              |
| UC-G51 SCA        | `sca/ScaModal` + `sca/dlHash` (4 classes: payment-initiation,      |
|                   | mandate-action, consent-grant, gdpr-action)                        |
| UC-G52 expiry     | `auth/SessionExpiryWarning`                                        |
| UC-G53 refresh    | `auth/oauth.refreshAccessToken`                                    |
| UC-G54 banners    | `components/StatusBanner` (auto SSE wired)                         |

## Local development

```bash
cd implementation/client-web
npm install
npm run dev            # http://localhost:5173
```

Backend is expected on the canonical compose ports (identity 8097,
account-ledger 8091, bill-payment 8093, psd2-api 8094, notifications 8098).
The Vite dev server proxies every `/oauth2`, `/sca`, `/.well-known` and
`/api/v1/customer/*` prefix to the right service so the SPA stays same-origin.

### Tests

```bash
npm run typecheck      # tsc --noEmit
npm test               # Vitest unit suite (35 tests across api/auth/sca/lib/components/pages)
npm run test:e2e       # Playwright (assumes the stack + `npm run preview`)
```

### Production build

```bash
npm run build          # tsc -b && vite build → ./dist
docker build -f client-web/Dockerfile -t jrpay-client-web .
```

The image multi-stages on `node:20-alpine` (build) → `nginx:1.27-alpine`
(serve). The nginx config sits at `docker/client-web/nginx.conf` and:

- emits the GUI-SEC-03 security headers (CSP, X-Frame, Referrer, HSTS,
  Permissions-Policy, X-Content-Type-Options);
- reverse-proxies every backend customer surface using compose hostnames
  with a `127.0.0.11` (Docker DNS) resolver so the container starts even when
  an upstream is down;
- preserves SSE chunked encoding (`proxy_buffering off`,
  `proxy_read_timeout 1h`) for `/api/v1/customer/{r2p,mandates}/*/stream`;
- fingerprinted assets cached `immutable` for one year, with SPA fallback to
  `index.html` for deep-link routes.

## Compose

`docker-compose.yml` builds the image alongside the backend stack:

```yaml
client-web:
  build:
    context: ..
    dockerfile: client-web/Dockerfile
  ports: ["5173:80"]
  depends_on: [identity, account-ledger, bill-payment, psd2-api, notifications]
```

`make compose-up` (or `docker compose up -d` from `implementation/docker`)
brings everything up; the SPA is reachable on http://localhost:5173.

## Security notes

- All payment / mandate / consent / GDPR mutations run UC-G51 SCA: the
  customer types BankID-simulated TOTP into the `<ScaModal>`, the resulting
  `Step-Up-Token` is forwarded as the `Step-Up-Token` header alongside the
  auto-generated `Idempotency-Key`.
- `dl_hash` is computed in TypeScript via the byte-identical canonical-JSON
  algorithm used by the Java services. Verified by
  `src/sca/__tests__/dlHash.test.ts` across all four customer classes.
- Bearer tokens live in `sessionStorage`; expiry is enforced on every
  `useAuth()` tick, with a UC-G52 5-minute warning toast.
- `AuthGuard` redirects unauthenticated traffic to `/login`.
- VoP is mandatory before any new beneficiary or payment submission; the UI
  blocks submit on `MISMATCH` and requires explicit acknowledgement on
  `UNAVAILABLE`.

## Outstanding follow-ups

- UC-G15 QR-code payment initiation (camera-based scan; web-only fallback to
  paste-IBAN).
- UC-G06 batch payment upload (CSV / pain.001 import).
- Live websocket for the dashboard "recent activity" footer (currently
  fetched once per page load).
- Bind the marketing-consent toggle to a `gdpr-action`-class SCA flow if the
  compliance team treats it as a consent-grant rather than a preference.
