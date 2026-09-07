# UC-G03 — Transaction History and Detail

*Owner: Frontend / Accounts*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

From an account, the customer browses paginated transaction history with filters (date range, amount range, counterparty text, reference text) and sort (date or amount). Tapping a transaction opens a detail sheet with amount, counterparty, reference (incl. KID render), payment method, status, VoP outcome badge, clearing refs, and a downloadable PDF receipt.

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A1 (ledger reads), A4 (customer proxy + receipt PDF), A6 (historical VoP outcome embedded in transaction record)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | Admin uses UC-G35 / UC-G40 for similar queries |
| Client Web | yes | Table layout |
| Client iOS | yes | List + sheet |
| Client Android | yes | LazyColumn + bottom sheet |

## 4. Preconditions

- Customer is authenticated with `accounts:read` + `transactions:read` scopes.
- The account is visible to the customer (own account, not CLOSED).

## 5. Postconditions

- The rendered history matches `GET /api/v1/customer/accounts/{id}/transactions?…` response for the filter/sort in effect.
- `read.transaction-history` audit event recorded with actor, account ID, filter summary.
- `read.transaction-detail` audit event recorded on detail open.

## 6. Main flow (happy path)

1. Customer taps an account card → GUI navigates to the account detail screen.
2. GUI fetches `GET /api/v1/customer/accounts/{accountId}` (metadata) and `GET /api/v1/customer/accounts/{accountId}/transactions?page=0&size=25&sort=date,desc` in parallel.
3. Header renders: nickname, full IBAN (tap to copy), balances, status, quick actions (Ny betaling / New payment, Eksporter / Export — UC-G04).
4. List renders one row per transaction:
   - Date + time (locale)
   - Counterparty name (own-side inverted: "To Ola" for outgoing, "From Kari" for incoming) + masked counterparty IBAN on a second line
   - Amount (locale-formatted, minus sign + red for debit, plus sign + green for credit; colour never the only indicator per `GUI-A11Y-03` — also uses `−` / `+` prefix and an arrow icon)
   - Status badge if non-SETTLED (PENDING, RETURNED, CANCELLED)
5. Filters toolbar:
   - Date range presets (Today, Last 7, Last 30, This month, Custom)
   - Amount range (two numeric inputs)
   - Counterparty substring
   - Reference substring
   - Apply / Clear
6. Each filter change is a new query with `page=0`; results replace the list.
7. Pagination: 25 per page; "Load more" button (web) / infinite scroll (mobile) triggers `page=n+1`.
8. Customer taps a row → GUI fetches `GET /api/v1/customer/transactions/{txId}` (full record) and opens detail sheet/page (`CLI-F12`):
   - Amount + currency, large
   - Debtor (name, IBAN), Creditor (name, IBAN)
   - Reference: free text and/or KID (if KID, render with the checksum digit visually separated: `1234 5678 901 | 8`)
   - Payment method: INSTANT (SCT Inst badge) / STANDARD / R2P-accepted / AvtaleGiro / VRP
   - Status + last status transition timestamp
   - VoP outcome at initiation (badge, read-only; `actualName` only visible if the original response included it, structurally enforced)
   - A2 payment ID, clearing correlation ID, settlement instant timestamp
   - "Last nde kvittering" / "Download receipt" button → `GET /api/v1/customer/transactions/{txId}/receipt.pdf`
   - "Se på faktura" / "Related R2P" link if the transaction originated from an accepted R2P → UC-G11
9. Customer closes the sheet; list is preserved.

## 7. Alternative flows

### AF-01 — Empty history for the account

1. Empty state: "Ingen transaksjoner ennå." / "No transactions yet."

### AF-02 — Very large account (> 10k txs total)

1. Filter defaults to "Last 30 days" on initial load; a notice appears if a broader date range is requested ("Large result — may take a moment"); server-side paginates identically.

### AF-03 — Filter by counterparty using an IBAN

1. If the substring looks like an IBAN (mod-97 valid), GUI sends it as an exact `counterpartyIban` filter rather than substring `counterparty`. Backend supports both.

### AF-04 — Transaction in non-terminal state (PENDING)

1. Row shows a pulsing status dot; tap opens detail; detail subscribes to SSE `GET /api/v1/customer/payments/stream?paymentId=…` for live updates while open, and unsubscribes on close.

### AF-05 — Sort by amount

1. Query becomes `sort=amount,desc` (or asc); the list re-orders; a visual cue (arrow next to column header / segmented control on mobile) indicates the sort.

### AF-06 — Deep link to a specific transaction (from notification or email)

1. GUI opens detail directly without the list; a back button returns to the account's list.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 403 `transactions:read` missing | Response interceptor | Show forbidden copy; log scope set |
| 404 on `{txId}` | Detail fetch | Show not-found sheet with a support reference |
| 5xx | Response interceptor | Retryable error card; cached list preserved |
| PDF receipt 500 | Receipt download | Toast "Kvittering midlertidig utilgjengelig. Prøv igjen om litt." |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F11`, `CLI-F12`, `CLI-F14`, `CLI-NF07`, `GUI-SEC-11`, `GUI-SEC-09` (VoP), `GUI-A11Y-03`, `GUI-A11Y-07`
- `system_requirements.md` — A1-F03..F08 (transaction model)

## 10. API operations involved

- `GET /api/v1/customer/accounts/{accountId}` (`customer-accounts.yaml`)
- `GET /api/v1/customer/accounts/{accountId}/transactions` (`customer-accounts.yaml`)
- `GET /api/v1/customer/transactions/{transactionId}` (`customer-accounts.yaml`)
- `GET /api/v1/customer/transactions/{transactionId}/receipt.pdf` (`customer-accounts.yaml`)

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `.../transactions/__tests__/row.test.tsx`, `detail.test.tsx`, `filters.test.ts` | `.../Tests/TransactionRowTests.swift` | `.../transactions/TransactionRowTest.kt` |
| Component | `.../transactions/__tests__/history.test.tsx` | `.../Tests/TransactionHistoryViewTests.swift` | `.../HistoryScreenTest.kt` |
| Contract | `.../transactions.pact.test.ts` | `.../TransactionsPactTests.swift` | `.../TransactionsPactTest.kt` |
| E2E | `client-web/e2e/transaction-history.spec.ts` | `.../UITests/HistoryUITests.swift` | `.../androidTest/.../HistoryE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/history_filter_last_30_days.json`, `history_open_detail.json` | (same) | (same) |

## 12. UX notes

- Negative amounts use `−` (U+2212) rather than `-` for typographic clarity; screen readers receive "minus".
- KID is rendered in groups of 4-4-3-1 with the checksum digit after a thin space and separator.
- The VoP badge in the detail has an info tooltip explaining the outcome at the time of payment (never shows actualName if absent — `GUI-SEC-09`).
- Receipt download on mobile uses the native share sheet (iOS `UIActivityViewController`, Android `Intent.ACTION_SEND`) rather than a direct save, to match platform conventions.
- **Screen-capture protection (Phase 1 FM.1 / GUI-SEC-05a):** list and detail screens render counterparty names, IBANs, and amounts; mobile sets `FLAG_SECURE` and installs the iOS obscuring overlay for the entire screen lifetime. Applies to the detail sheet too.

## 13. Open questions

- Whether the GUI should allow CSV export at this view or require the customer to use UC-G04. *Decision: UC-G04 only, to keep a single export surface.*
