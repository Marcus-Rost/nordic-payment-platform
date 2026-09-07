# Financial Integrity Review Checklist

*Purpose: Verify that the payment system's financial calculations are always correct — no money created, destroyed, lost, or miscounted*
*Date: 2026-04-12*
*Based on: Double-entry bookkeeping invariants, IEEE 754 guidelines, Java BigDecimal best practices, JSR 354, BCBS 239, reconciliation standards*

---

## Category FI1: Monetary Data Types

### FI1.1 — No Float/Double for Money
Verify that no service uses float, double, Float, or Double for monetary amounts. All money must be BigDecimal, JSR 354 MonetaryAmount, or integer minor units.

### FI1.2 — BigDecimal Construction
Verify that BigDecimal values are never constructed from float/double literals (`new BigDecimal(0.1)` is wrong; `new BigDecimal("0.1")` is correct).

### FI1.3 — Rounding Mode Always Specified
Verify that every BigDecimal division operation specifies a RoundingMode. Division without rounding mode throws ArithmeticException for non-terminating decimals.

### FI1.4 — HALF_EVEN (Banker's Rounding) as Default
Verify that the default rounding mode is HALF_EVEN for financial calculations. Other modes only where specifically required by business rules, with documented justification.

### FI1.5 — Currency-Aware Decimal Scale
Verify that monetary amounts use the correct decimal scale per ISO 4217 (EUR=2, NOK=2, JPY=0, BHD=3). Never hardcode 2 decimal places.

### FI1.6 — PostgreSQL NUMERIC, Not FLOAT
Verify that all monetary columns in PostgreSQL use NUMERIC (arbitrary precision), never FLOAT, DOUBLE PRECISION, or REAL.

### FI1.7 — Avro Schema Monetary Encoding
Verify that Avro schemas encode monetary amounts as string (BigDecimal serialized) or as a logical type, not as double or float.

## Category FI2: Accounting Invariants

### FI2.1 — Sum(Debits) == Sum(Credits)
Verify that the architecture structurally guarantees debit-credit equality. The compound event model in A1 (debit+credit in one message) should make unbalanced entries impossible.

### FI2.2 — Balance = Sum(Entries)
Verify that materialized balances (PostgreSQL projections in A1) can be independently verified by replaying all events from the event store.

### FI2.3 — Conservation of Money
Verify that money cannot be created or destroyed within the system. Every internal transfer is zero-sum. Only external events (deposits/withdrawals) change total system balance.

### FI2.4 — Available = Total - Reserved
Verify that the available balance formula is correctly maintained under all operations: reserve, confirm, release, settle.

### FI2.5 — Balance Never Negative
Verify that no code path allows an account balance to go below zero, enforced by domain type + projection validation + database CHECK constraint.

### FI2.6 — No Orphaned Entries
Verify that every debit has a corresponding credit. The compound event model should make orphaned entries structurally impossible.

## Category FI3: Netting Correctness

### FI3.1 — Net Positions Sum to Zero
Verify that A2's multilateral netting algorithm produces net positions that sum to exactly zero. This must be checked before settlement execution.

### FI3.2 — Netting Determinism
Verify that the netting algorithm is deterministic — same inputs always produce same outputs. No dependency on processing order, random values, or timing.

### FI3.3 — Rounding in Netting
Verify that rounding remainders in netting are handled deterministically. One party must absorb the remainder with a documented rule. The remainder must not be silently lost.

### FI3.4 — Gross-to-Net Reconciliation
Verify that Sum(|gross obligations|) >= Sum(|net positions|). Netting must reduce (or maintain) total payment volume.

## Category FI4: Currency Handling

### FI4.1 — Currency Always Paired with Amount
Verify that every monetary amount in the system carries its currency code. An amount without currency is meaningless.

### FI4.2 — No Implicit Currency Conversion
Verify that two amounts in different currencies cannot be added without an explicit conversion step. The domain type should prevent this.

### FI4.3 — ISO 4217 Currency Codes
Verify that currency codes are validated against ISO 4217. Invalid codes must be rejected.

### FI4.4 — Multi-Currency Account Structure
Verify that the architecture supports multiple currencies correctly — separate balances per currency, no implicit conversions.

## Category FI5: Reconciliation

### FI5.1 — Automated Reconciliation Exists
Verify that A1's reconciliation compares ledger events against A2's settlement reports automatically, not manually.

### FI5.2 — Zero Tolerance for Amount Discrepancies
Verify that reconciliation has zero tolerance for monetary differences. One cent off is a failure that must be investigated.

### FI5.3 — Completeness Check
Verify that reconciliation checks count of transactions (not just amounts) — every transaction in one system has a match in the other.

### FI5.4 — Discrepancy Alerting
Verify that reconciliation discrepancies trigger automated alerts with severity classification and escalation rules.

## Category FI6: Idempotency and Deduplication

### FI6.1 — Payment Applied Exactly Once
Verify that idempotency mechanisms prevent the same payment from being applied twice (double-charge) or zero times (lost payment).

### FI6.2 — Semantic Duplicate Detection
Verify that A2's semantic duplicate detection (same debtor+creditor+amount within window) works correctly and the window is appropriate.

### FI6.3 — Idempotency Key Determinism
Verify that derived idempotency keys (SHA-256(transactionId + suffix)) are deterministic — retries produce the same key.
