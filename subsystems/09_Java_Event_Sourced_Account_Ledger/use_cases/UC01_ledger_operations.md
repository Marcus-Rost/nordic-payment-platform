# UC-01: Ledger Operations — Credits, Debits, Reservations, Queries

---

## Scenario 1A: Standard Credit Transfer (Debit + Credit Pair)

| Step | Actor | Action | Events Generated |
|------|-------|--------|-----------------|
| 1 | A2 Clearing Engine | Publishes settlement event: debit 5,000 NOK from account A, credit 5,000 NOK to account B | |
| 2 | A1 | Validates: account A exists, status ACTIVE, available balance >= 5,000 | |
| 3 | A1 | Publishes to `ledger.events`: `{eventType: DEBIT, accountId: A, amount: 5000, currency: NOK, counterparty: B, idempotencyKey: "settle-001-debit"}` | Event #1 |
| 4 | A1 | Publishes to `ledger.events`: `{eventType: CREDIT, accountId: B, amount: 5000, currency: NOK, counterparty: A, idempotencyKey: "settle-001-credit"}` | Event #2 |
| 5 | A1 projection consumer | Consumes Event #1: updates PostgreSQL → account A balance -= 5,000 | |
| 6 | A1 projection consumer | Consumes Event #2: updates PostgreSQL → account B balance += 5,000 | |

**Invariant check:** Total debits (5,000) = Total credits (5,000) ✓

---

## Scenario 1B: Instant Payment with Balance Reservation

| Step | Time | Actor | Action |
|------|------|-------|--------|
| 1 | T+0ms | A2 (instant path) | Requests reservation: "Reserve 5,000 NOK on account A for payment P-001" |
| 2 | T+50ms | A1 | Checks: available balance (10,000) >= 5,000 → OK. `SELECT FOR UPDATE SKIP LOCKED` on account row |
| 3 | T+60ms | A1 | Publishes: `{eventType: BALANCE_RESERVED, accountId: A, amount: 5000, reservationId: "res-001"}` |
| 4 | T+60ms | A1 | Updates PostgreSQL: available_balance = 10,000 - 5,000 = 5,000. booked_balance unchanged at 10,000 |
| 5 | T+500ms | A2 | Fraud + sanctions checks pass. Requests confirmation: "Confirm reservation res-001" |
| 6 | T+550ms | A1 | Publishes: `{eventType: BALANCE_CONFIRMED, accountId: A, amount: 5000, reservationId: "res-001"}` |
| 7 | T+560ms | A1 | Updates: booked_balance = 10,000 - 5,000 = 5,000. available_balance stays 5,000. Reservation removed |
| 8 | T+570ms | A1 | Publishes credit to beneficiary: `{eventType: CREDIT, accountId: B, amount: 5000}` |

**After settlement:** Account A: booked=5,000, available=5,000. Account B: booked += 5,000.

---

## Scenario 1C: Reservation Released (Payment Failed)

| Step | Time | Actor | Action |
|------|------|-------|--------|
| 1 | T+0ms | A2 | Reserves 5,000 NOK on account A → available = 5,000, booked = 10,000 |
| 2 | T+500ms | A2 | Fraud detection blocks payment → requests release: "Release reservation res-001" |
| 3 | T+550ms | A1 | Publishes: `{eventType: BALANCE_RELEASED, accountId: A, amount: 5000, reservationId: "res-001"}` |
| 4 | T+560ms | A1 | Updates: available_balance = 5,000 + 5,000 = 10,000. booked_balance unchanged. Reservation removed |

**After release:** Account A: booked=10,000, available=10,000. As if the reservation never happened.

---

## Scenario 1D: Concurrent Reservations (Two Instant Payments Competing)

| Step | Payment P-001 | Payment P-002 | Account A (balance: 8,000) |
|------|--------------|--------------|---------------------------|
| 1 | Reserve 5,000 | Reserve 5,000 | Both requests arrive simultaneously |
| 2 | `SELECT FOR UPDATE SKIP LOCKED` → acquires lock | `SKIP LOCKED` → row locked, skips → retries | |
| 3 | Reserved: available = 3,000 | — | P-001 holds lock |
| 4 | Lock released | Retry: `SELECT FOR UPDATE` → acquires lock | |
| 5 | — | Available = 3,000 >= 5,000? NO → INSUFFICIENT_FUNDS | |
| 6 | Proceeds to settlement | Rejected | |

**Result:** P-001 succeeds, P-002 rejected. No over-reservation. The `SKIP LOCKED` pattern prevents deadlocks and ensures one winner.

---

## Scenario 1E: Projection Rebuild

| Step | Actor | Action |
|------|-------|--------|
| 1 | Admin | Triggers rebuild: stops projection consumer, truncates PostgreSQL balance table |
| 2 | A1 | Resets Kafka consumer offset to 0 (beginning of `ledger.events` topic) |
| 3 | A1 | Replays all events from beginning. For each event: check idempotencyKey → apply to PostgreSQL |
| 4 | A1 | After 1M events (~3 minutes): all balances rebuilt |
| 5 | Verification | Compare rebuilt balances against a checksum of expected balances (derived from event replay) → identical ✓ |

**Why this matters:** Proves the event log is the source of truth. PostgreSQL projections are derived, disposable, and rebuildable. This is the core event sourcing guarantee.

---

## Scenario 1F: Account Freeze on Fraud Confirmation

| Step | Actor | Action |
|------|-------|--------|
| 1 | A5 Fraud Engine | Publishes `account.freeze-commands`: `{accountId: A, reason: CONFIRMED_FRAUD}` |
| 2 | A1 | Consumes command. Publishes: `{eventType: ACCOUNT_FROZEN, accountId: A}` |
| 3 | A1 | Updates: account status = FROZEN |
| 4 | A2 | Subsequent debit attempt on account A → A1 rejects: "Account frozen, debits not allowed" |
| 5 | A3 | Incoming credit (merchant refund) to account A → A1 accepts: credits allowed on frozen accounts |

---

## Scenario 1G: Daily Reconciliation

*Inspired by Allevo FinTP's reconciliation feature. Every production payment system must reconcile daily.*

| Step | Actor | Action | Result |
|------|-------|--------|--------|
| 1 | Scheduler (or Admin) | Triggers reconciliation: POST `/api/v1/reconciliation/run` with settlement report from A2 (camt.053 or JSON) | A1 starts reconciliation |
| 2 | A1 | For each settlement entry in the report: look up internal ledger event by `externalReference` (settlementReference + clearingCycleId) | Match or no match |
| 3 | A1 | **Matched**: amounts match → set reconciliation status = RECONCILED | 9,995 of 10,000 matched |
| 4 | A1 | **Amount discrepancy**: internal says NOK 5,000 but settlement report says NOK 5,500 → DISCREPANCY | 2 discrepancies found |
| 5 | A1 | **Unmatched internal**: we have a ledger event with no corresponding settlement entry → DISCREPANCY | 1 internal-only entry (possible settlement failure) |
| 6 | A1 | **Unmatched external**: settlement report has an entry with no corresponding ledger event → DISCREPANCY | 2 external-only entries (possible missed events) |
| 7 | A1 | Publishes reconciliation results to `payment.reconciliation-results` Kafka topic | |
| 8 | A1 | Returns: `{cycleId, matched: 9995, discrepancies: 5, unmatchedInternal: 1, unmatchedExternal: 2, reconciledAt: "..."}` | |
| 9 | Monitoring | Grafana alert fires: "5 discrepancies in clearing cycle CLR-2026-04-11-16" | Ops team investigates |

### Why Reconciliation Matters

- **Discrepancies mean money is unaccounted for.** If we think we debited NOK 5,000 but the clearing house says 5,500, someone is wrong. This must be investigated immediately.
- **Regulatory requirement**: banks must reconcile against clearing house records daily. Auditors check this.
- **Detects system bugs**: a reconciliation mismatch can reveal: missed events (Kafka consumer lag), double-processing (idempotency failure), or A2 settlement errors.
- **Our event sourcing architecture makes this easier**: we can replay events for any time range and compare against external reports. Traditional systems must query mutable database records (which may have been altered).

---

## Scenario 1H: Semantic Duplicate Warning

*A2 flags a payment as a probable duplicate, and the ledger records the duplicate context.*

| Step | Actor | Action | Result |
|------|-------|--------|--------|
| 1 | A2 | Detects: payment P-002 (NOK 5,000 from account A to account B) matches P-001 (same debtor, creditor, amount, submitted 3 minutes ago) | A2 sets `duplicateFlag: PROBABLE_DUPLICATE, duplicateOf: P-001` on P-002 |
| 2 | Payer | Warned: "This looks similar to payment P-001 submitted 3 min ago. Proceed?" → confirms override | |
| 3 | A2 | Proceeds with clearing. Duplicate override flag forwarded to A5 (+0.2 fraud risk) | |
| 4 | A1 | Records ledger event for P-002 with metadata: `{duplicateFlag: "PROBABLE_DUPLICATE", duplicateOf: "P-001", payerOverride: true}` | Audit trail preserves the duplicate context |

**Key design decision:** Semantic duplicate detection **warns but does not block**. Many legitimate payments look like duplicates (e.g., monthly rent of exactly the same amount to the same landlord). The warning gives the payer a chance to catch mistakes, but the system doesn't second-guess intentional payments.

---

## Scenario 1I: Account Creation

| Step | Actor | Action | Result |
|------|-------|--------|--------|
| 1 | Admin / System | POST `/api/v1/accounts` with `{holderId: "person-001", holderName: "Ola Nordmann", currency: NOK, accountType: CURRENT}` | |
| 2 | A1 | Validates: holderId not empty, currency is valid ISO 4217, accountType supported | OK |
| 3 | A1 | Generates IBAN: `NO` + 2 check digits + 11-digit account number (mod-11 checksum) | IBAN = "NO93 8601 1117 947" |
| 4 | A1 | Publishes to `ledger.events`: `{eventType: ACCOUNT_CREATED, accountId: "acct-uuid-001", iban: "NO9386011117947", holderName: "Ola Nordmann", currency: NOK}` | Event published |
| 5 | A1 projection consumer | Creates PostgreSQL row: account_id, iban, holder_name, currency=NOK, status=ACTIVE, booked_balance=0.00, available_balance=0.00 | |
| 6 | A1 | Returns 201: `{accountId, iban, status: ACTIVE, balance: 0.00, currency: NOK}` | Account ready for use |

**Postconditions**: Account exists with ACTIVE status, zero balance, available for VoP queries (A6 can look up holder name by IBAN).

---

## Scenario 1J: Account Statement Generation

| Step | Actor | Action | Result |
|------|-------|--------|--------|
| 1 | Consumer / TPP | GET `/api/v1/accounts/{id}/statement?from=2026-01-01&to=2026-03-31&format=json` | |
| 2 | A1 | Queries PostgreSQL transaction projection for account within date range | Returns paginated list |
| 3 | A1 | Response: `{account: {iban, currency}, period: {from, to}, openingBalance: 45000.00, closingBalance: 52300.00, entries: [{date, amount, currency, creditDebit, reference, counterparty, runningBalance}, ...]}` | JSON statement returned |

### ISO 20022 camt.053 variant:

| Step | Actor | Action | Result |
|------|-------|--------|--------|
| 1 | Consumer / Reconciliation | GET `/api/v1/accounts/{id}/statement?from=2026-01-01&to=2026-03-31&format=camt053` | |
| 2 | A1 | Queries same data, marshals to ISO 20022 camt.053 XML via JAXB | |
| 3 | A1 | Returns XML: `<BkToCstmrStmt>` containing `<Bal>` (opening/closing), `<Ntry>` per transaction (amount, CdtDbtInd, booking date, EndToEndId, counterparty) | Valid camt.053 XML returned |

**Used by**: A2 reconciliation (compares internal ledger against clearing house reports), consumers (bank statements), regulatory reporting (quarter-end/year-end balances via LED-F35 point-in-time query).

---

## Security Considerations
- **Events are immutable** — the Kafka topic is append-only. No event can be modified or deleted. This is the regulatory audit trail
- **Projections are derived** — if PostgreSQL is compromised, we can detect it by comparing against the event log
- **Balance reservations have timeouts** — prevents permanent fund locking if a service crashes mid-payment
- **Account holder names** — stored in PostgreSQL for VoP (A6) lookups. Access restricted to A6 service account
- **Double-entry invariant** — can be verified at any time by summing all debits and credits across all accounts
- **Reconciliation detects integrity issues** — daily comparison against external settlement reports catches: missed events, double-processing, and settlement errors
- **External references enable auditability** — every ledger event links to its clearing cycle and settlement reference, creating a complete chain from payment instruction to balance change
