# Gap Analysis: Our System vs. EU Payment Infrastructure

*Based on research of SEPA, TIPS, RT1, P27, UK FPS, EPI/Wero, and EU regulatory mandates*
*This document identifies what we have, what we're missing, and what's "exotic"*

---

## Status Legend

- **(a) MANDATORY** — Required by EU regulation. Non-negotiable for a realistic system.
- **(b) EMERGING** — Industry standard practice or adopted by leading platforms. High value.
- **(c) DIFFERENTIATING** — Innovative, not widely implemented. Would make the portfolio stand out.
- **HAVE** — Already in our current draft requirements/use cases.
- **GAP** — Missing entirely.
- **PARTIAL** — Partially addressed, needs elevation or expansion.

---

## 1. Feature Comparison Matrix

| # | Feature | Our Status | EU Status | Priority | Action Required |
|---|---------|-----------|-----------|----------|----------------|
| 1 | Event-sourced account ledger | **HAVE** (A1) | Good foundation | — | Ensure ISO 20022 data model alignment |
| 2 | Batch clearing & netting | **HAVE** (A2) | Standard SCT batch | — | Add ISO 20022 pacs.008/pacs.002 properly |
| 3 | eFaktura-style bill payment | **HAVE** (A3) | Similar to R2P | PARTIAL | Evolve toward SRTP model (see #12) |
| 4 | PSD2 AISP/PISP | **HAVE** (A4) | Mandatory baseline | — | Extend with VRP, enrichment |
| 5 | Fraud detection (real-time scoring) | **HAVE** (A5) | Standard practice | — | Integrate with VoP pre-check |
| 6 | **Instant payment processing** | **GAP** | **(a) MANDATORY** | **CRITICAL** | New use case UC-08. 10-second SLA, 24/7/365 |
| 7 | **Verification of Payee (VoP)** | **GAP** | **(a) MANDATORY** | **CRITICAL** | Pre-payment name-IBAN check. Mandatory Oct 2025 |
| 8 | **Sanctions screening (real-time)** | **GAP** | **(a) MANDATORY** | **CRITICAL** | Must not delay instant payment SLA. AML requirement |
| 9 | **SCA exemption engine** | **PARTIAL** | **(a) MANDATORY** | **HIGH** | We simulate SCA but don't model exemptions (low-value, TRA, trusted beneficiary, recurring) |
| 10 | **SEPA Direct Debit (pull payments)** | **GAP** | **(a) STANDARD** | **HIGH** | Mandate management, refund rights (8 weeks Core, 13 months no mandate), R-transactions |
| 11 | **Liquidity management** | **GAP** | **(a) STANDARD** for instant | **HIGH** | Pre-funded settlement accounts, threshold monitoring, auto-top-up |
| 12 | **Request-to-Pay (SRTP)** | **PARTIAL** | **(b) EMERGING** | **HIGH** | Our eFaktura is close but not SRTP. Add: accept/decline/defer/amend, pain.013/014, link to SCT Inst |
| 13 | **Variable Recurring Payments (VRP)** | **GAP** | **(c) DIFFERENTIATING** | **HIGH** | Consent-based recurring with parameter guardrails. No EU equivalent. UK-only so far |
| 14 | **Payment status tracking** | **PARTIAL** | **(b) EMERGING** | **MEDIUM** | We have status on Kafka but no unified tracking API. Add: real-time tracking, webhooks, estimated completion |
| 15 | **Multi-currency clearing** | **PARTIAL** | **(b) STANDARD** for Nordics | **MEDIUM** | Ledger supports multi-currency. Clearing engine needs FX conversion at clearing time (P27 model) |
| 16 | **QR code payment initiation** | **GAP** | **(b) EMERGING** | **MEDIUM** | EPC QR standard, dynamic QR with payment reference, triggers PIS flow |
| 17 | **Proxy/alias resolution** | **GAP** | **(c) DIFFERENTIATING** | **MEDIUM** | Pay by phone number/email. No EU mandate. Swish, Vipps, Bizum have national schemes |
| 18 | **Embedded finance / BaaS API design** | **PARTIAL** | **(b) EMERGING** | **MEDIUM** | Our APIs exist but not designed for white-label embedding. Add: multi-tenancy, webhook streams, onboarding API |
| 19 | **Confirmation of Payee response service** | **GAP** | **(a) MANDATORY** | **HIGH** | We need to RESPOND to VoP queries about our accounts (not just make queries) |
| 20 | **Digital Euro (CBDC) readiness** | **GAP** | **(c) DIFFERENTIATING** | **LOW** | Abstract settlement asset type. Not before 2028-2029. Strategic architecture signal |

---

## 2. Critical Gaps — Must Fix

These are features that are **legally mandated** in the EU or so universally standard that omitting them would make the system look unrealistic to any payment domain expert reviewing the portfolio.

### GAP 1: Instant Payment Processing (SCT Inst)

**Why it's critical:**
The EU Instant Payment Regulation (2024/886) makes SEPA Instant Credit Transfer mandatory for all PSPs in the eurozone. Euro-area banks must receive instant payments by Jan 2025 and send by Oct 2025. Non-euro EU PSPs follow in 2027. Price parity with regular SCT is mandated.

**What we need:**
- A real-time processing path alongside our batch clearing (dual-mode A2)
- 10-second end-to-end SLA (submit → fraud check → settle → confirm)
- 24/7/365 availability (no maintenance windows, no batch cycles)
- EUR 100,000 transaction limit
- Immediate, irrevocable settlement (no netting — gross settlement per transaction)
- pacs.008 for the instruction, pacs.002 for confirmation/rejection
- Kafka-based but with strict latency guarantees (Kafka Streams with low-latency config)

**Architecture impact:**
- Cannot use Spring Batch for instant path (batch is inherently not real-time)
- Fraud detection (A5) must respond in <100ms or be non-blocking (score asynchronously, allow configurable timeout with auto-pass)
- Account Ledger (A1) must process balance updates in near-real-time (not just batch projections)
- Need a concept of CSM (Clearing and Settlement Mechanism) — abstraction over TIPS/RT1

**Recommendation:** Elevate from UC-04 alternative flow to a full, first-class use case UC-08.

---

### GAP 2: Verification of Payee (VoP) / Confirmation of Payee (CoP)

**Why it's critical:**
Mandatory under the EU IPR from October 2025 for all euro credit transfers (instant and non-instant). Must be offered free of charge. This is the single most impactful fraud prevention measure mandated by the regulation.

**What we need (two roles):**

**As VoP Requester (before sending a payment):**
1. Payer enters beneficiary name + IBAN
2. Our system sends verification request to beneficiary PSP (via central directory or bilateral API)
3. Receive response: MATCH / CLOSE_MATCH / NO_MATCH
4. Display result to payer before payment confirmation
5. If NO_MATCH: warn payer, allow override with explicit acknowledgment (logged)

**As VoP Responder (when others query our accounts):**
1. Receive query: "Does IBAN NO12 3456 7890 123 belong to 'Ola Nordmann'?"
2. Look up account holder name in A1 Account Ledger
3. Apply fuzzy matching (Jaro-Winkler, handle diacritics, common abbreviations)
4. Respond: MATCH / CLOSE_MATCH (provide actual name) / NO_MATCH
5. Must respond within SLA (sub-second) without delaying the payment

**Integration with fraud detection:**
- VoP result should be an input to the fraud score (A5). A NO_MATCH with payer override is a strong risk signal.
- Combine VoP result with other fraud indicators for enriched scoring.

**Recommendation:** New sub-system or module — could be a component of A5 (Fraud Detection) or a separate lightweight service.

---

### GAP 3: Real-time Sanctions Screening

**Why it's critical:**
Legally required for all payment processing under EU AML directives. The IPR specifically mandates that sanctions screening must not delay the 10-second instant payment SLA. PSPs must screen against EU Consolidated Sanctions List, UN list, and national lists.

**What we need:**
- List ingestion and normalization (EU Consolidated List updates ~50 times/year)
- Real-time screening of originator and beneficiary names, accounts, countries against sanctions lists
- Fuzzy matching (transliteration, aliases, partial names, name order variations)
- Sub-100ms screening latency (to fit within the 10-second instant payment SLA)
- Hit/alert management: auto-clear low-confidence matches, queue high-confidence for analyst review
- Integration point: between payment instruction receipt and clearing/settlement
- Must NOT be a blocking synchronous call that could fail the SLA — design for async with fast-path

**Architecture approach:**
- Pre-load sanctions lists into an in-memory data structure (trie, bloom filter for initial check, then fuzzy match)
- Update lists asynchronously (event-driven: Kafka topic for list updates)
- Screening result is part of the fraud enrichment pipeline (A5)

**Recommendation:** Integrate as a screening layer within A5 (Fraud Detection) or as a dedicated pre-processing step in A2 (Clearing Engine).

---

## 3. High-Priority Gaps — Should Fix

### GAP 4: SCA Exemption Engine

**Current state:** We simulate SCA (PIN + TOTP) in A4, but we don't model exemptions.

**What real systems do:**
- Automatically determine the optimal SCA exemption for each transaction
- Low-value: <EUR 30 (cumulative max 5 or EUR 100)
- Transaction Risk Analysis (TRA): if PSP fraud rate below thresholds, exempt up to EUR 500
- Trusted beneficiary: payer has whitelisted the payee
- Recurring: same amount, same payee — SCA only on first
- Merchant-initiated transactions (MIT): outside SCA scope

**Why it matters for portfolio:**
An SCA exemption engine directly impacts checkout conversion rates. Showing you understand when to apply SCA and when to exempt it — balancing security vs. friction — is a senior-level security design decision.

**Recommendation:** Add exemption logic to A4 (PSD2 API Gateway). Model as a decision engine with rules based on transaction attributes.

---

### GAP 5: SEPA Direct Debit (Pull Payments)

**Current state:** We have eFaktura (push with merchant-submitted invoices) and AvtaleGiro mandates (UC-06). But we don't have the SEPA Direct Debit (SDD) model.

**Key differences from what we have:**
- SDD is a PULL payment: the creditor initiates the debit, not the debtor
- SDD requires a signed mandate (paper or electronic eMandates)
- SDD Core has an 8-week unconditional refund right (debtor can get money back, no questions asked)
- SDD Core has a 13-month refund right if no valid mandate exists
- SDD B2B has NO refund right (business-to-business)
- R-transactions: Reject (before settlement), Return (after settlement), Refund (debtor-initiated), Reversal (creditor-initiated)
- Collection files use pain.008 (CustomerDirectDebitInitiation), interbank uses pacs.003

**Why it matters:**
Direct debit is one of the two fundamental payment types in Europe (along with credit transfer). Subscriptions, utility bills, insurance premiums, loan repayments — all use direct debit. Omitting it makes the system incomplete.

**Recommendation:** New use case UC-09. Model SDD Core with mandate lifecycle, refund handling, and R-transactions.

---

### GAP 6: Liquidity Management

**Current state:** Not modeled. Our clearing engine processes payments but doesn't track available liquidity for settlement.

**What real systems need:**
- Each participating bank/PSP has a pre-funded settlement account
- Before clearing can execute, sufficient liquidity must exist
- Threshold monitoring: warn when liquidity drops below configurable level
- Auto-top-up: trigger funding requests when balance is low
- Liquidity reporting: current balance, reserved amounts, available amounts
- For instant payments: liquidity must be available 24/7 (no end-of-day refunding)

**Why it matters:**
In TIPS, settlement happens in central bank money from pre-funded DCAs. In RT1, settlement accounts are pre-funded via TARGET. Without liquidity management, our clearing engine is incomplete — it settles payments from thin air.

**Recommendation:** New module within A2 (Clearing Engine) or standalone liquidity management service.

---

## 4. Valuable Additions — Differentiating Features

### DIFF 1: Variable Recurring Payments (VRP)

**What it is:** A consent-based recurring payment mechanism where the payer authorizes a TPP to make payments within agreed parameters (max amount per payment, max cumulative per period, max frequency, specific payee). SCA required only at consent setup, not for each payment.

**Why it's differentiating:** Currently UK-only (CMA mandated "sweeping" VRP in 2022). No EU equivalent exists. Building VRP-like capability positions the system as forward-looking and innovative.

**How it differs from our AvtaleGiro (UC-06):** AvtaleGiro is merchant-initiated (pull). VRP is TPP-initiated on behalf of the payer (push). VRP offers granular parameter control (amount limits, frequency caps, date ranges). VRP settles instantly (not batch). VRP can be used for "smart" use cases like automated savings sweeping.

**Recommendation:** New use case UC-10. Model as an extension of A4 (PSD2 API) with consent management and parameter enforcement.

---

### DIFF 2: Request-to-Pay (SRTP) — Evolve eFaktura

**What it is:** The pan-European SEPA Request to Pay scheme (EPC014-20). Payee sends structured request → Payer can accept (triggers SCT Inst), decline, defer, or request amendment.

**How to evolve our eFaktura (UC-01/A3):**
- Add: payer can **decline** with reason code
- Add: payer can **defer** (accept but pay on a later date)
- Add: payer can **request amendment** (different amount, different date)
- Add: pain.013 (CreditorPaymentActivationRequest) and pain.014 (status) message modeling
- Add: link acceptance directly to SCT Inst (instant payment on accept)
- Add: recurring R2P for subscription billing
- Add: QR code initiation of R2P (scan QR → see payment request → approve)

**Recommendation:** Evolve UC-01 and A3 to support full SRTP lifecycle.

---

### DIFF 3: Proxy/Alias Resolution (Pay by Phone/Email)

**What it is:** A directory service mapping human-friendly identifiers (phone number, email) to IBANs. Enables "send money to +47 123 456 789" instead of entering an IBAN.

**Why it's differentiating:** No pan-EU mandate. National schemes exist (Swish, Vipps, Bizum) but are not interoperable. Building a multi-alias resolution layer would be innovative.

**Recommendation:** New lightweight service. Low priority but high demo value ("send money to a phone number").

---

### DIFF 4: QR Code Payment Initiation

**What it is:** Generate EPC-standard QR codes encoding payment details (IBAN, amount, reference). Consumer scans with banking app to initiate payment.

**Why it's valuable:** Bridges online and offline payments. Wero and EPI are driving QR adoption in Europe. EPC QR standard (EPC069-12) exists but is underused.

**Recommendation:** Add to A3 (Bill Payment) and A4 (PSD2 API). Dynamic QR generation per invoice/payment request.

---

### DIFF 5: Digital Euro / CBDC Readiness

**What it is:** The ECB's Digital Euro — a retail CBDC. Not expected before 2028-2029, but the architecture decision to support it is a strategic signal.

**How to prepare:** Abstract the "settlement asset" in A1 (Account Ledger) and A2 (Clearing Engine) so it's configurable: commercial bank money, central bank money (TIPS DCA), or CBDC. Don't build CBDC settlement — just make the architecture extensible.

**Recommendation:** Architecture decision record (ADR) documenting the settlement asset abstraction. Minimal code change, maximum strategic signaling.

---

## 5. "Exotic" Features — Do We Have Things Others Don't?

Features in our current draft that are unusual or rare in typical payment systems:

### EXOTIC 1: ML-based Fraud Scoring via Deep Java Library (DJL)

**Status:** We have this in A5. Most payment systems use rule-based fraud detection. ML-based scoring exists at large institutions (DNB, Sportradar UFDS) but is unusual in a portfolio project.

**Is this justified?** YES — strongly justified.
- Sportradar explicitly uses DJL in production on AWS EKS
- DNB invests heavily in ML for AML/fraud
- DJL is a rare, high-signal skill that sets the portfolio apart
- The layered approach (rules + statistics + ML) mirrors Sportradar's UFDS architecture
- **Keep it. This is a differentiator, not an exotic unnecessary feature.**

### EXOTIC 2: Event Sourcing for the Account Ledger (A1)

**Status:** We use event sourcing with CQRS for the account ledger. This is less common in typical payment tutorials/demos but IS standard practice at Tietoevry Banking, DNB, and modern banking platforms.

**Is this justified?** YES — strongly justified.
- Tietoevry explicitly uses event sourcing and CQRS in transaction-heavy domains
- DNB uses event-driven architecture with Kafka as backbone
- Immutability is a regulatory requirement for financial audit trails
- Event sourcing naturally supports the "rebuild from events" pattern needed for audit and compliance
- **Keep it. This is not exotic — it's best practice for financial ledgers.**

### EXOTIC 3: JWE (JSON Web Encryption) for Sensitive API Responses

**Status:** We encrypt sensitive fields (account numbers, balances) in PSD2 API responses using JWE. This goes beyond what most PSD2 implementations do (they rely on TLS only).

**Is this justified?** PARTIALLY — justified for Mastercard alignment but not standard practice.
- Mastercard's `client-encryption-java` library does exactly this (JWE payload encryption)
- Most PSD2 implementations rely on TLS (transport encryption) and don't encrypt payloads
- The EU PSD2 RTS doesn't mandate field-level encryption
- **Keep it as an optional feature.** Document WHY: defense-in-depth (protects against TLS termination proxy exposure, log leakage). Note that Mastercard uses this pattern. But don't make it mandatory for all API responses — make it opt-in per TPP.

### EXOTIC 4: Pact Contract Testing

**Status:** We specified Pact for contract testing between sub-systems. This is unusual in payment system portfolios but is real-world practice at Tietoevry.

**Is this justified?** YES — justified.
- Tietoevry explicitly lists Pact in their tech requirements
- Contract testing is the professional approach to microservice testing
- It prevents the "works in isolation, breaks in integration" problem
- **Keep it. It demonstrates senior-level testing maturity.**

### EXOTIC 5: NAV's mock-oauth2-server for Testing

**Status:** We use NAV's actual open-source library for OAuth2 testing in A4.

**Is this justified?** YES — justified and strategically valuable.
- Using a Norwegian open-source library signals ecosystem awareness
- mock-oauth2-server is a well-maintained, production-quality test tool
- It's better than rolling our own mock OAuth2 server
- **Keep it. It's a deliberate strategic choice, not an exotic one.**

---

## 6. Summary: Recommended Actions

### Must-do (legally mandated features):
1. **Add UC-08: Instant Payment Processing** — 10-second SLA, 24/7, EUR 100K limit
2. **Add Verification of Payee (VoP)** — Pre-payment name check, both requester and responder roles
3. **Add Sanctions Screening** — Real-time, sub-100ms, integrated with fraud pipeline
4. **Enhance SCA with Exemption Engine** — Low-value, TRA, trusted beneficiary, recurring exemptions

### Should-do (high value, standard practice):
5. **Add UC-09: SEPA Direct Debit** — Pull payments with mandate lifecycle and refund handling
6. **Add Liquidity Management** — Pre-funded accounts, threshold monitoring
7. **Evolve eFaktura to Request-to-Pay (SRTP)** — Accept/decline/defer/amend, pain.013/014
8. **Add VRP (Variable Recurring Payments)** — Consent-based recurring with parameter guardrails

### Nice-to-have (differentiating):
9. **Add QR Code Payment Initiation** — EPC QR standard, dynamic QR per invoice
10. **Add Proxy/Alias Resolution** — Pay by phone number/email
11. **Add CBDC Settlement Abstraction** — Architecture readiness for Digital Euro
12. **Enhance Payment Status Tracking** — Unified tracking API with webhooks and estimated completion

### Keep as-is (justified "exotic" features):
- DJL-based ML fraud scoring (**keep** — Sportradar alignment)
- Event sourcing for ledger (**keep** — Tietoevry/DNB best practice)
- JWE payload encryption (**keep as optional** — Mastercard alignment, document rationale)
- Pact contract testing (**keep** — Tietoevry requirement)
- NAV mock-oauth2-server (**keep** — ecosystem signal)
