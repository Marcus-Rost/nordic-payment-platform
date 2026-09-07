# Financial Integrity Review — Findings

*Date: 2026-04-12*
*Checklist: FINANCIAL_INTEGRITY_CHECKLIST.md*

---

## Summary

27 checks performed. 21 PASS, 3 PARTIAL, 3 FAIL.

**The core accounting invariants are strong.** Compound events, conservation of money, balance integrity, netting correctness, and idempotency are all structurally enforced. The failures are in rounding policy and decimal scale — important for future-proofing but not currently causing incorrect results.

---

## Findings

### HIGH (2)

| ID | Finding | Fix |
|----|---------|-----|
| **FI1.3+FI1.4** | No rounding mode policy. No documentation of HALF_EVEN as default. No ArchUnit rule prohibiting bare `BigDecimal.divide()`. Current code avoids division, but this becomes a bug the moment fee/interest/pro-rata calculations are added. | Add `MoneyMath` utility to `payment-domain-model` with mandatory `RoundingMode.HALF_EVEN`. Document as project guideline. |
| **FI1.5** | `MonetaryAmount` hardcodes 2 decimal places. `DECIMAL(18,2)` in A2. Correct for current currencies {NOK, EUR, SEK, DKK, USD} but structurally fragile. Adding JPY (0 decimals) or BHD (3 decimals) would silently truncate. | Derive scale from `Currency` enum using ISO 4217 metadata. |

### MEDIUM (2)

| ID | Finding | Fix |
|----|---------|-----|
| **FI2.5** | A1's `account_balances` table lacks `CHECK (available_balance >= 0)` database constraint. A2 has this defense-in-depth. | Add CHECK constraint to A1. |
| **FI1.2** | No ArchUnit/SpotBugs rule preventing `new BigDecimal(double)` construction. Architecture shows correct string construction but doesn't enforce it. | Add ArchUnit rule. |

### LOW (1)

| ID | Finding | Fix |
|----|---------|-----|
| **FI5.3** | Reconciliation detects missing entries via UNMATCHED but doesn't do explicit count comparison. | Minor — implicit coverage is adequate. |

---

## Positive Findings

- **Compound events:** debit+credit structurally atomic — impossible to separate
- **Conservation of money:** verified at unit, property-based, and integration test levels
- **Netting:** postcondition check throws if net positions don't sum to zero. ArchUnit enforces pure function.
- **BigDecimal everywhere:** no float/double for money. PostgreSQL NUMERIC. Avro string encoding.
- **Reconciliation:** automated, zero tolerance, P1/P2 alerting with escalation
- **Three-layer idempotency:** command dedup + producer dedup + consumer dedup
- **Deterministic idempotency keys:** SHA-256(transactionId + purpose)
