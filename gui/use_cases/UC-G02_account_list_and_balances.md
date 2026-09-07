# UC-G02 — Account List and Balances

*Owner: Frontend / Accounts*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

The customer lands on the home screen after login (UC-G01) and sees all of their accounts with current booked balance, available balance (booked minus reserved), account type, currency, and status badge. This is the read-entry point to every transactional feature.

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A4 (`GET /api/v1/customer/accounts`), A1 (upstream ledger)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | Admin equivalent is UC-G35 |
| Client Web | yes | |
| Client iOS | yes | Dashboard is the root of the tab bar |
| Client Android | yes | Dashboard is the start destination of nav graph |

## 4. Preconditions

- Customer holds the `accounts:read` scope (granted implicitly at login for account holders).
- Customer has an authenticated session with a valid access token.

## 5. Postconditions

- The list of accounts and balances rendered on screen matches the authoritative ledger state at the time the fetch returned; the render includes a "Last updated HH:MM" timestamp.
- A `read.account-list` audit event is emitted with the actor and the number of accounts returned (no per-account IDs in audit; cardinality only).

## 6. Main flow (happy path)

1. After login, GUI navigates to the **Accounts** screen.
2. GUI fetches `GET /api/v1/customer/accounts`.
3. A4 authorises the token, expands it into the customer's account set (relation stored in A4/identity), and proxies to A1 for each account's header data (A1 returns an aggregated response in a single call; see ARCHITECTURE_DESIGN §8).
4. Response body:
   ```json
   [
     {
       "accountId": "…",
       "iban": "NO9386011117947",
       "type": "CHECKING | SAVINGS | CREDIT",
       "currency": "NOK",
       "bookedBalance": "12345.67",
       "reservedAmount": "250.00",
       "availableBalance": "12095.67",
       "status": "ACTIVE | FROZEN | CLOSED",
       "nickname": "Hovedkonto",
       "primary": true
     }, …
   ]
   ```
5. GUI renders one card per account, ordered: primary first, then by booked balance descending. Per card:
   - Nickname + account type badge
   - Masked IBAN (`NO93 •••• •••• 7947`) with tap-to-reveal full IBAN (no SCA; IBAN is not PII under GDPR Art. 9 but is masked for shoulder-surfing protection)
   - Booked balance (large, locale-formatted)
   - Available balance (smaller, labelled "Disponibelt / Available")
   - Status badge: ACTIVE (none), FROZEN (amber with `CLI-F14` copy and reference), CLOSED (grey, read-only)
6. A header region shows: customer greeting ("Hei, Ola"), total wealth across accounts in the customer's preferred currency (via FX static table, see scope note in gui_requirements §1.2), and a "Last updated" timestamp.
7. Tapping an account navigates to UC-G03 (transaction history).
8. A pull-to-refresh (mobile) / refresh button (web) re-fetches.

## 7. Alternative flows

### AF-01 — No accounts (new customer, provisioning in progress)

1. Response returns an empty array.
2. GUI shows an empty-state with copy "Velkommen. Kontoen din er under opprettelse. Vi sender et varsel så snart den er klar." / "Welcome. Your account is being created. We'll notify you when it's ready." A support-contact link is offered.

### AF-02 — Exactly one account

1. Single-card layout with additional quick actions (New payment, Request money, Show statement) inline on the card.

### AF-03 — One or more accounts FROZEN

1. FROZEN accounts still appear in the list with the amber status badge.
2. Tap on a FROZEN account still navigates to UC-G03 (read-only view); write actions are hidden.

### AF-04 — Refresh after an expected balance change (e.g., after a payment)

1. After UC-G05 reports SETTLED, GUI invalidates the TanStack Query cache key for accounts; next navigation to the dashboard re-fetches.

### AF-05 — SSE push of balance change

1. If the customer is already on the Accounts screen and a `payment.status-updates` SSE event arrives for one of their accounts, GUI debounces for 500 ms and refetches the affected account row, then animates the delta (briefly highlights the changed balance for 2 s).

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 401 expired token | Response interceptor | Silent refresh (UC-G01 AF-01); on failure, force re-login |
| 403 `accounts:read` missing | Response interceptor | Show `accounts.error.forbidden`; log the scope set; do not retry |
| 5xx / network | Response interceptor | Show retryable error card with **Try again**; on repeated failure, show last-known cached list with "Offline — last updated HH:MM" banner (mobile only per CLI-NF07) |
| Partial response (A1 timeout for one account) | Response body has an item with `"status":"UNAVAILABLE"` | Render the card in a reduced state ("Saldo utilgjengelig — prøv igjen") with a per-card retry button |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F10`, `CLI-F14`, `CLI-NF07`, `GUI-SEC-11`, `GUI-A11Y-07`, `GUI-RES-01`, `GUI-RES-03`
- `system_requirements.md` — A1-F01..F05 (account model), A4-F* (customer surface)

## 10. API operations involved

- `GET /api/v1/customer/accounts` (`customer-accounts.yaml`)
- `GET /api/v1/customer/accounts/{accountId}` (`customer-accounts.yaml`) — used by UC-G03

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `client-web/.../accounts/__tests__/account-card.test.tsx` | `.../Tests/AccountCardTests.swift` | `.../accounts/AccountCardTest.kt` |
| Component | `client-web/.../accounts/__tests__/dashboard.test.tsx` | `.../Tests/DashboardViewTests.swift` | `.../DashboardScreenTest.kt` |
| Contract (Pact) | `client-web/.../accounts.pact.test.ts` | `.../AccountsPactTests.swift` | `.../AccountsPactTest.kt` |
| E2E | `client-web/e2e/dashboard.spec.ts` | `.../UITests/DashboardUITests.swift` | `.../androidTest/.../DashboardE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/dashboard_two_accounts.json` | (same) | (same) |

## 12. UX notes

- Balances use the locale's thousands/decimal separators (`GUI-I18N-03`); screen readers announce the currency in words (`GUI-A11Y-07`).
- The delta-animation on balance change respects `prefers-reduced-motion`.
- IBAN masking is a purely visual guard; the full IBAN is in the DOM `aria-label` so screen readers can still identify the account.
- FROZEN badge icon is a 🔒-equivalent SVG (not the emoji; WCAG-compliant icon from the tokens).
- **Screen-capture protection (Phase 1 FM.1 / GUI-SEC-05a):** this screen displays account numbers and balances; mobile platforms set `FLAG_SECURE` (Android) for the activity's lifetime and install the pre-snapshot obscuring overlay on iOS. OS-level screen-record is blocked; app-switcher thumbnail shows the obscuring splash rather than the content.

## 13. Open questions

- Whether to group credit/savings accounts under a collapsible "Other accounts" section when there are many. *Defer to Phase-1 review.*
