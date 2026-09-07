# UC-G35 — Account Search, Freeze, Unfreeze, Close

*Owner: Frontend / Admin / Accounts*
*Status: DRAFT*
*Implementation status: Admin Web ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

A back-office operator searches the customer-account dataset by name (fuzzy), IBAN (exact), or fødselsnummer (exact, scope-gated). The operator can view a full account snapshot and perform administrative actions: manual freeze (any role with `accounts:admin`), manual unfreeze (SUPERVISOR+), account closure (zero-balance requirement), or account creation (post-CDD). Every action requires SCA and emits an audit event.

## 2. Actors

- **Primary:** FRAUD_ANALYST (read-only), COMPLIANCE_OFFICER, OPERATIONS, SUPERVISOR, ADMIN
- **Secondary:** A1 (ledger, new admin endpoints per gui_requirements §6.4), identity service (SCA)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes | |

## 4. Preconditions

- Operator has `accounts:admin` scope (read for FRAUD_ANALYST; full for others).

## 5. Postconditions

- Depending on the action:
  - Search: audit `account.search` with query hash + result cardinality (no PII in audit).
  - Freeze: `account.freeze-commands` published; audit `account.freeze.requested`.
  - Unfreeze: `account.unfreeze-commands`; audit `account.unfreeze.requested`.
  - Closure: account state CLOSED; audit `account.closed`.
  - Creation: new account in A1; audit `account.created`.

## 6. Main flow (happy path)

### 6.1 Search

1. Operator navigates to **Kontoer / Accounts**.
2. Search bar with mode selector (Navn / Name — fuzzy; IBAN — exact; Fødselsnr. — exact + scope-gated).
3. Name search: backend does prefix + trigram fuzzy match; returns up to 50 accounts with holder name (masked if operator lacks PII reveal right), account type, IBAN (masked), status.
4. IBAN search: exact; returns the single account if found.
5. Fødselsnr. search: requires SCA on first use per session (extra friction for PII lookup); logs the search.
6. Operator taps a result → detail page.

### 6.2 Detail page (ADM-F31)

- Holder info (masked; reveal is UC-G36).
- Status badge: ACTIVE / FROZEN (with reason + date + actor) / CLOSED.
- Balances: booked, reserved, available, currency.
- Reservations list (with payment IDs).
- Recent transactions (with UC-G03-style rendering).
- Audit subset for this account.
- Actions bar: **Frys / Freeze** (if ACTIVE), **Slipp frys / Unfreeze** (if FROZEN, SUPERVISOR+), **Lukk / Close** (zero balance), **Avslør PII / Reveal PII** (UC-G36), **Ny konto / Create account** (top-level, not from detail).

### 6.3 Freeze

1. Operator taps **Frys**.
2. Modal:
   - Reason dropdown: FRAUD_INVESTIGATION / SANCTIONS_HOLD / REGULATORY_HOLD / OTHER.
   - Notes ≥ 50 chars (required).
   - Confirm checkbox.
3. Step-up SCA with dynamic-linking `{accountId, action:"FREEZE", reason}`.
4. Backend `POST /api/v1/admin/accounts/{accountId}/freeze` persists, publishes freeze command, returns updated account state.
5. UI shows FROZEN badge; customer notified via a system-initiated message (UC-G19 variant).

### 6.4 Unfreeze

1. Operator with SUPERVISOR role taps **Slipp frys**.
2. Modal:
   - Reason dropdown: INVESTIGATION_CONCLUDED / FALSE_POSITIVE_CONFIRMED / CUSTOMER_DISPUTE_RESOLVED / OTHER.
   - Notes ≥ 50 chars.
   - Confirm checkbox.
3. Step-up SCA.
4. Backend `POST /api/v1/admin/accounts/{accountId}/unfreeze`.
5. UI reflects; customer notified ("Kontoen din er nå aktiv igjen").

### 6.5 Close

1. Guard: Close button disabled if balance ≠ 0 (server also enforces).
2. Modal with reason, notes, confirmation.
3. Step-up SCA.
4. `POST /api/v1/admin/accounts/{accountId}/close`.
5. UI: account CLOSED; read-only.

### 6.6 Create

1. Operator taps **Ny konto** (top level).
2. Form: customer (searched by fødselsnr — requires SCA from 6.1 or step-up), account type, currency, initial deposit source (optional — internal transfer from operating account).
3. Step-up SCA.
4. `POST /api/v1/admin/accounts` — reuses existing create endpoint with scope-tightening.

## 7. Alternative flows

### AF-01 — Fuzzy name search with many results

1. Returns 50, with a "refine search" banner. Operator types more of the name or switches to IBAN mode.

### AF-02 — Freeze when already FROZEN

1. Action disabled. Tooltip explains existing state.

### AF-03 — Unfreeze attempt by non-SUPERVISOR

1. Button hidden; if URL-typed, API returns 403. `GUI-SEC-14` defence-in-depth.

### AF-04 — Close attempt with non-zero balance

1. Client-side validation + server 409. UI shows guidance to transfer the balance out first.

### AF-05 — Customer has active mandates or consents at close

1. Server 409 `consents-active`. UI offers a "See active consents" link → admin view of the customer's consents for revocation (a parallel flow; not per-customer screen in MVP).

### AF-06 — Race with customer concurrent action

1. A1 is authoritative; last-writer-wins at the ledger level; UI shows current state after action.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 5xx on freeze/unfreeze/close | Response | Retryable with idempotency key |
| Fødselsnr. lookup for an operator without the scope | 403 | Copy explaining scope requirement |
| Search timeout (large dataset) | Timeout | Partial results + "narrow the query" hint |

## 9. Related requirements

- `gui_requirements.md` — `ADM-F30..F36`, `GUI-SEC-14`, `GUI-SEC-15`
- `system_requirements.md` — A1-F10..F12 (freeze), A1-F18..F22 (admin search)

## 10. API operations involved

- `GET  /api/v1/admin/accounts/search`
- `GET  /api/v1/admin/accounts/{accountId}`
- `POST /api/v1/admin/accounts/{accountId}/freeze`
- `POST /api/v1/admin/accounts/{accountId}/unfreeze`
- `POST /api/v1/admin/accounts/{accountId}/close`
- `POST /api/v1/admin/accounts`
- `POST /api/v1/admin/accounts/{accountId}/reveal-pii` (UC-G36)

## 11. Tests realising this use case

| Layer | Admin Web |
|-------|-----------|
| Unit | `admin-web/.../accounts/__tests__/search.test.tsx`, `action-modals.test.tsx` |
| Component | `admin-web/.../accounts/__tests__/account-detail.test.tsx` |
| Contract | `admin-web/.../admin-accounts.pact.test.ts` |
| E2E | `admin-web/e2e/admin-account-search.spec.ts`, `admin-account-freeze.spec.ts`, `admin-account-unfreeze.spec.ts`, `admin-account-close.spec.ts`, `admin-account-create.spec.ts` |

## 12. UX notes

- Name search supports Norwegian characters and accents; backend normalises (NFC).
- Holder mask shows enough characters to distinguish similar names (`Ola N*****` for short names, mask length proportional to original).
- All actions on the detail page sit in a sticky action bar; critical ones (Close, Freeze) use confirm-style styling.

## 13. Open questions

- Whether to support bulk freeze (select multiple accounts → freeze all). *Defer — bulk freeze is a high-risk operation; single-flow preferred.*
