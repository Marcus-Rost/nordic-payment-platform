# Further Extensions — Overview

*Lower priority extensions identified in gap_analysis.md*
*These are documented at overview level — to be detailed if/when we decide to implement them*

---

## SCA Exemption Engine (GAP 4)

**What:** Logic within A4 (PSD2 API) that automatically determines whether a transaction can skip SCA.

**Exemption types to model:**
- Low-value: < EUR 30 (cumulative max: 5 transactions or EUR 100, then force SCA)
- Transaction Risk Analysis (TRA): if our fraud rate < thresholds, exempt up to EUR 500
- Trusted beneficiary: payer has whitelisted this payee
- Recurring: same amount + same payee → SCA only on first payment
- Merchant-initiated (MIT): pre-agreed charges (hotel no-show, subscription renewal)

**Key design decision:** The exemption engine must track cumulative counters per card/account (how many low-value exemptions since last SCA) and maintain fraud rate statistics (for TRA).

**Target companies:** All PSD2 implementers — DNB, SpareBank 1, Tietoevry, Mastercard, Vipps.

---

## SEPA Direct Debit / Pull Payments (GAP 5)

**What:** Creditor-initiated debits from debtor's account, based on a signed mandate.

**Key features to model:**
- Mandate lifecycle: CREATE → ACTIVE → AMENDED → CANCELLED
- SDD Core: 8-week unconditional refund right (debtor can reverse any debit within 8 weeks)
- SDD Core: 13-month refund if no valid mandate
- SDD B2B: no refund right (business-to-business)
- R-transactions: Reject, Return, Refund, Reversal, Revocation
- Collection files: pain.008 (CustomerDirectDebitInitiation)
- Pre-notification: creditor must notify debtor 14 days before collection (configurable)
- Sequence types: FRST (first), RCUR (recurring), FNAL (final), OOFF (one-off)

**How it differs from our AvtaleGiro (UC-06):** AvtaleGiro is Norwegian-specific. SDD is pan-European. SDD has mandatory refund rights. SDD uses ISO 20022 pain.008 messages. SDD has formal R-transaction types.

**Target companies:** Mastercard Oslo (payment infrastructure), Tietoevry (core banking), all companies processing European payments.

---

## Liquidity Management (GAP 6)

**What:** Tracking and managing the pre-funded settlement accounts needed for instant payment processing.

**Features to model:**
- Settlement account per participating PSP with current balance
- Threshold alerts: warn when balance drops below configurable minimum
- Auto-top-up: trigger funding request when balance is low
- Liquidity reservation: tentatively reserve funds for in-flight instant payments
- Reporting: current balance, reserved, available, daily min/max, utilization rate
- Intraday liquidity monitoring (regulatory requirement for large PSPs)

**Why it matters:** In TIPS, banks must pre-fund Dedicated Cash Accounts with central bank money. Without modeling liquidity, our clearing engine settles from "infinite money" which is unrealistic.

---

## Variable Recurring Payments / VRP (DIFF 1)

**What:** Consent-based recurring payments where the payer authorizes a TPP to make payments within agreed parameter bounds, without per-payment SCA.

**Consent parameters:**
- Maximum amount per payment
- Maximum cumulative amount per period (day/week/month)
- Maximum payment frequency
- Valid date range
- Specific payee only
- Currency

**Use cases:**
- "Sweeping": automatically move excess balance from current account to savings
- Subscription billing with variable amounts (utility bills, metered services)
- "Smart direct debit replacement": instant, transparent, revocable

**Why differentiating:** Currently UK-only. No EU equivalent. Building this for the EU market is genuinely innovative.

---

## Request-to-Pay / SRTP Evolution (DIFF 2)

**What:** Evolve our eFaktura (UC-01/A3) toward the pan-European SEPA Request to Pay standard.

**Additional features beyond current eFaktura:**
- Payer can **decline** with reason code
- Payer can **defer** (accept but pay on a later date)
- Payer can **request amendment** (different amount, different date)
- ISO 20022 messages: pain.013 (CreditorPaymentActivationRequest), pain.014 (status)
- Accept triggers SCT Inst (instant payment) not just batch
- Recurring R2P for subscription management
- QR code initiation (scan QR → see R2P request → approve → instant payment)

---

## QR Code Payment Initiation (DIFF 4)

**What:** Generate and parse EPC-standard QR codes (EPC069-12) for payment initiation.

**EPC QR format (text-based):**
```
BCD                          # Service tag
002                          # Version
1                            # Charset (UTF-8)
SCT                          # Identification (SEPA Credit Transfer)
RABONL2U                     # BIC (optional since 2016)
Ola Nordmann AS              # Beneficiary name
NO93860111117947             # IBAN
NOK1250.00                   # Amount (currency + value)
                             # Purpose (optional)
RF18539007547034             # Structured reference
Invoice 2026-042             # Unstructured text
```

**Features to implement:**
- Dynamic QR generation per invoice/payment request (A3)
- Static QR for merchant registration (fixed IBAN, no amount)
- QR parsing and payment initiation via PSD2 PIS (A4)
- Embed QR in R2P notifications

---

## Proxy/Alias Resolution (DIFF 3)

**What:** Pay by phone number or email instead of IBAN.

**Architecture:**
- Central directory mapping: alias → IBAN + BIC
- Registration: account holder registers alias with their PSP
- Lookup: sender's PSP queries directory, resolves to IBAN
- Payment proceeds as normal credit transfer using resolved IBAN

**Implementation:** Lightweight lookup service with in-memory cache. Low priority but high demo appeal.

---

## CBDC / Digital Euro Readiness (DIFF 5)

**What:** Architecture-level preparation for Digital Euro integration (ECB, not before 2028).

**What to do now (minimal effort, maximum signal):**
- Abstract the "settlement asset" in A1 and A2: `enum SettlementAsset { COMMERCIAL_BANK_MONEY, CENTRAL_BANK_MONEY, CBDC }`
- Document the abstraction in an Architecture Decision Record (ADR)
- Note in the ADR: "When the Digital Euro launches, this system can support CBDC settlement by implementing a new `CbdcSettlementProvider` without changing the clearing logic"

**No actual CBDC code needed.** The signal is in the architecture readiness.

---

## Priority Summary

| Extension | Gap/Diff | Effort | Impact | Recommended Phase |
|-----------|---------|--------|--------|-------------------|
| Instant Payments | GAP 1 | High | **Critical** — legally mandatory | Phase 1 (with core system) |
| Verification of Payee | GAP 2 | Medium | **Critical** — mandatory Oct 2025 | Phase 1 |
| Sanctions Screening | GAP 3 | Medium | **Critical** — legal obligation | Phase 1 |
| SCA Exemptions | GAP 4 | Low-Medium | High — checkout conversion | Phase 2 |
| SEPA Direct Debit | GAP 5 | High | High — core payment type | Phase 2 |
| Liquidity Management | GAP 6 | Medium | High — realism | Phase 2 |
| Request-to-Pay (SRTP) | DIFF 2 | Medium | High — evolves existing A3 | Phase 2 |
| VRP | DIFF 1 | Medium | High — innovative, no EU competitor | Phase 3 |
| QR Code Payments | DIFF 4 | Low | Medium — good demo value | Phase 3 |
| Proxy Resolution | DIFF 3 | Low | Medium — good demo value | Phase 3 |
| CBDC Readiness | DIFF 5 | Very Low | Low urgency, high strategic | Phase 3 (ADR only) |
