# Requirements Comparison Review: Our System vs. Mastercard Payment Services Norway AS

**Reviewer:** Claude Opus 4.6
**Date:** 2026-04-11
**Scope:** Comparative analysis of our Nordic Payment Processing Infrastructure requirements against the production capabilities of Mastercard Payment Services Norway AS (MPSNOR / MPSI)
**Documents reviewed:** All system requirements, 10 use cases, 6 extension analyses, NAVIGATION.md, SUBSYSTEM_MAP.md, sub-project requirements for A1-A6 and the Platform Starter
**External research:** MPSNOR public documentation, Norges Bank retail payment reports (2024), Bits AS publications, Finanstilsynet filings, eFaktura developer portal, AvtaleGiro API specifications, industry press

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Company Profile: Mastercard Payment Services Norway AS](#2-company-profile)
3. [Feature-by-Feature Comparison](#3-feature-by-feature-comparison)
4. [Where Mastercard is Stronger](#4-where-mastercard-is-stronger)
5. [Where Our System is Stronger](#5-where-our-system-is-stronger)
6. [Importance Assessment of Mastercard's Advantages](#6-importance-assessment)
7. [Recommendations for Improving Our Requirements](#7-recommendations)
8. [Risk Assessment: What Happens If We Ignore These Gaps](#8-risk-assessment)
9. [Conclusion](#9-conclusion)

---

## 1. Executive Summary

Mastercard Payment Services Norway AS (formerly Nets Norway) is the **sole technical operator** of Norway's national payment clearing infrastructure. Through its subsidiary MPSI (Mastercard Payment Services Infrastructure Norway AS), it operates NICS (Norwegian Interbank Clearing System), eFaktura, AvtaleGiro, Autogiro, and KAR (the Account & Addressing Register). It processes over **2.7 billion transactions annually** with a total value of approximately NOK 104,832 billion, serving all banks operating in Norway.

**Overall finding:** Our system and MPSNOR occupy different positions in the payment value chain but share significant functional overlap. MPSNOR excels in **production-grade national infrastructure operations** -- multi-window batch clearing, centralized account registry (KAR), mature Norwegian-specific instruments, and massive scale. Our system excels in **EU-forward regulatory compliance** -- full VoP with fuzzy matching, ML-based fraud detection, comprehensive sanctions screening architecture, SCA exemption engine, VRP, and SRTP-aligned Request-to-Pay.

**Bottom line:** MPSNOR is a production monopoly operator of Norway's domestic payment rails. Our system is a regulation-forward, EU-aligned design that goes beyond what MPSNOR currently offers in several strategically important areas. However, MPSNOR has **five capabilities** that expose real gaps or weaknesses in our requirements, three of which are important enough to warrant requirements changes.

---

## 2. Company Profile

### Mastercard Payment Services Norway AS

| Attribute | Detail |
|-----------|--------|
| **Legal name** | Mastercard Payment Services Norway AS |
| **Org. nr** | 822 988 822 (Bronnøysundregistrene) |
| **Infrastructure subsidiary** | Mastercard Payment Services Infrastructure (Norway) AS, org. nr 922 988 862 |
| **Address** | Haavard Martinsens vei 54, 0978 Oslo |
| **Revenue (2024)** | NOK ~815 million |
| **Employees** | ~300+ (Oslo and Copenhagen; Romania operations via Mastercard global) |
| **SWIFT/BIC** | BBSNNOKA, BBSNNOKK |
| **Ownership** | Mastercard Inc. (acquired Nets' account-to-account business for $3.2B, completed March 2021) |
| **Contract** | Five-year NICS operating contract with Bits AS, renewed May 2025 |
| **Market position** | Monopoly operator of Norwegian interbank clearing, eFaktura, AvtaleGiro, and KAR |

### Systems Operated

| System | Description | Scale (2024) |
|--------|-------------|-------------|
| **NICS Real (Straks 2.0)** | Instant payments, 24/7/365, max NOK 40,000 | 205 million transactions |
| **NICS Net** | Same-day batch clearing, 5 daily windows | Part of 2.7B total NICS transactions |
| **NICS Brutto (Gross)** | Large-value payments > NOK 25M, RTGS via Norges Bank NBO | Included in NICS total |
| **eFaktura** | Electronic invoice delivery to consumer online banking | 228.4M B2C + 123.9M EHF B2B invoices |
| **AvtaleGiro** | Recurring direct debit mandates | 112 million payments |
| **Autogiro** | B2B direct debits, 4 daily deadlines | 4 million payments |
| **KAR** | Centralized account & addressing register (all Norwegian bank accounts) | All banks participate |
| **OCR Giro** | KID-based payment reconciliation | Integrated with AvtaleGiro/eFaktura |

### Current Strategic Context

As of March 2026, Mastercard is reportedly exploring **divestiture** of this unit. The unit generates ~$370M annual revenue but faces competitive and regulatory pressures in European real-time payments. Any acquirer would inherit the NICS operating contract, eFaktura/AvtaleGiro platforms, and KAR. This signals that the Norwegian domestic payment infrastructure space may be in transition -- making forward-looking EU compliance and architectural flexibility more important, not less.

---

## 3. Feature-by-Feature Comparison

### 3.1 Clearing & Settlement

| Capability | Our System (A2) | MPSNOR (NICS) | Assessment |
|-----------|-----------------|---------------|------------|
| **Batch clearing** | Single configurable window (end-of-day or every 15 min) | **5 daily clearing windows** (05:30, 09:30, 11:30, 13:30, 15:30) + cut-off at 14:30 | **MPSNOR stronger** |
| **Instant payments** | SCT Inst aligned, 10-second SLA, EUR 100,000 max, EU IPR compliant | Straks 2.0 (NICS Real), 24/7/365, max **NOK 40,000** (~EUR 3,500) | **Our system stronger** (higher limit, EU-aligned) |
| **Large-value payments** | Not modeled -- all payments through same batch or instant path | **NICS Brutto**: payments > NOK 25M settled individually via Norges Bank RTGS (NBO) | **MPSNOR stronger** |
| **Multilateral netting** | Modeled in A2 with detailed netting algorithm and example | Production netting across 21 direct + ~109 indirect settlement participants | Comparable (our model is accurate) |
| **Settlement model** | Pre-funded settlement accounts (A2-F40 through F43) | Pre-funded Straks LOM accounts at Norges Bank for instant; NBO for batch/gross | Comparable |
| **ISO 20022** | Full: pacs.008, pacs.002, pacs.004, pain.001, pain.013/014, camt.053 | **Legacy** -- NICS has NOT fully migrated to ISO 20022 yet (Straks 2.0 uses ISO 20022; rest is legacy) | **Our system stronger** |
| **Multi-currency clearing** | Ledger supports NOK, EUR, USD, SEK, DKK; FX at clearing time noted as partial | NOK-only domestic clearing. EUR via SEPA (separate). No integrated multi-currency clearing | **Our system stronger** (P27-aligned) |

### 3.2 Bill Payment & Direct Debit

| Capability | Our System (A3) | MPSNOR | Assessment |
|-----------|-----------------|--------|------------|
| **eFaktura** | Modeled as R2P with accept/decline/defer/amend (SRTP evolution) | **Production eFaktura platform** -- 228.4M invoices/year, direct integration with all bank online portals | **MPSNOR stronger** (maturity) |
| **AvtaleGiro** | UC-06: mandate with max amount, auto-accept, consumer revocation | **Production AvtaleGiro** -- 112M payments/year, 4-day advance submission, KID-based reconciliation | **MPSNOR stronger** (maturity + KID) |
| **Autogiro (B2B)** | Not modeled | 4 daily submission windows, ~4M payments/year | **MPSNOR stronger** |
| **R2P lifecycle** | Accept, decline (with reason), defer (with date), request amendment | eFaktura is accept-or-ignore; AvtaleGiro is auto-accept within mandate | **Our system stronger** (SRTP aligned) |
| **QR code initiation** | EPC069-12 standard QR, dynamic per invoice | Not documented as a feature | **Our system stronger** |
| **Webhook notifications** | HMAC-SHA256 signed, exponential backoff, max 5 retries | File-based delivery (OCR giro format, ISO-8859-1), not webhook-based | **Our system stronger** (API-first) |

### 3.3 Open Banking / PSD2

| Capability | Our System (A4) | MPSNOR | Assessment |
|-----------|-----------------|--------|------------|
| **AISP / PISP** | Full: OAuth2 consent, accounts, balances, transactions, payment initiation | Via Mastercard's **Aiia** acquisition (formerly Nordic API Gateway) -- provides open banking connectivity but as a separate product, not integrated into NICS clearing | **Our system stronger** (integrated) |
| **SCA** | PIN + TOTP with dynamic linking, 4 exemption types | PSD2 SCA compliance is handled by individual banks, not by MPSNOR | **Our system stronger** |
| **SCA exemption engine** | Low-value, TRA, trusted beneficiary, recurring, MIT | Not applicable (banks implement their own SCA) | **Our system stronger** |
| **VRP** | Full: consent with parameter bounds, no-SCA execution, sweeping | Not offered | **Our system stronger** |
| **BerlinGroup NextGenPSD2** | API surface alignment documented | Not directly applicable | N/A |

### 3.4 Fraud Detection & Sanctions Screening

| Capability | Our System (A5) | MPSNOR | Assessment |
|-----------|-----------------|--------|------------|
| **Fraud detection model** | 3-layer: velocity rules + statistical anomaly + ML (DJL) | Mastercard global fraud tools (Risk Based Decisioning, AI-powered); applied at card network level, not directly at NICS clearing level | **Our system stronger** (for account-to-account payments) |
| **Sanctions screening** | 3-tier: Bloom filter + Aho-Corasick + fuzzy (Jaro-Winkler), <50ms SLA | Part of AML/CFT obligations; detailed architecture not publicly documented for NICS operations | **Our system stronger** (documented architecture) |
| **Case management** | Full workflow: OPEN -> INVESTIGATING -> CONFIRMED/DISMISSED, analyst dashboard | Not publicly documented at clearing level | **Our system stronger** |
| **VoP-fraud integration** | NO_MATCH override adds +0.3 to fraud score; NOT_FOUND adds +0.4 | Not documented; KAR is separate from fraud systems | **Our system stronger** |

### 3.5 Verification of Payee / Account Registry

| Capability | Our System (A6) | MPSNOR (KAR) | Assessment |
|-----------|-----------------|--------------|------------|
| **Account registry** | Not modeled -- VoP is a per-query service | **KAR**: centralized registry of ALL Norwegian bank accounts with holder names, IBANs, org/person numbers, phone mappings | **MPSNOR significantly stronger** |
| **VoP requester** | Full: query + display MATCH/CLOSE_MATCH/NO_MATCH, payer override with audit | Voluntary VoP via KAR lookup; not legally mandated yet in Norway | **Our system stronger** (mandatory, integrated) |
| **VoP responder** | Full: fuzzy matching (Jaro-Winkler >=0.95/0.85), privacy rules, anti-enumeration | KAR provides data but VoP responder function is at individual banks | Comparable |
| **Fuzzy name matching** | Jaro-Winkler with diacritics, common abbreviations (AS/Aksjeselskap), name order | Not documented for KAR | **Our system stronger** |
| **Phone/alias mapping** | Not modeled (noted as DIFF 3, low priority) | **KAR maps phone numbers to bank accounts** -- enables Vipps | **MPSNOR stronger** |
| **Anti-enumeration** | Rate limiting (100/min/PSP), same-IBAN-different-name detection (5x/hour block) | Not publicly documented | **Our system stronger** (explicit) |

### 3.6 Account Ledger & Reconciliation

| Capability | Our System (A1) | MPSNOR | Assessment |
|-----------|-----------------|--------|------------|
| **Ledger architecture** | Event-sourced, CQRS, Kafka as event store, PostgreSQL projections | Not applicable -- MPSNOR is clearing infrastructure, not a bank ledger | N/A (different scope) |
| **KID-based reconciliation** | Not modeled -- we use structured references and camt.053 | **OCR giro reconciliation with KID** -- Norwegian standard customer identification numbers for payment matching | **MPSNOR stronger** |
| **Reconciliation** | External reference IDs, UNRECONCILED/RECONCILED/DISCREPANCY status, reconciliation API | OCR giro delivers reconciliation data for KID payments; deeply integrated with AvtaleGiro | Comparable (different approaches) |
| **Multi-currency** | NOK, EUR, USD, SEK, DKK with JSR 354 | NOK only for domestic clearing | **Our system stronger** |

---

## 4. Where Mastercard is Stronger

### 4.1 CRITICAL: KAR -- Centralized Account & Addressing Register

**What they have that we lack:** KAR is a centralized, daily-updated registry containing every active Norwegian bank account, linked to holder identification (fodselsnummer / organisasjonsnummer), names, IBANs, account status, and crucially -- **mobile phone number mappings**. Nearly all Norwegian banks participate. KAR is governed by the Norwegian Financial Contracts Act (section 4-26) and is also used by DSOP (Digital Collaboration Public-Private) for government anti-financial-crime investigations.

**Why this matters:** KAR serves as the backbone for:
- Vipps mobile payments (phone-to-account resolution)
- A voluntary VoP mechanism (name-to-IBAN verification)
- Government financial crime investigations
- Account portability and routing

**Impact on our system:** Our A6 (VoP) performs per-query verification, which is correct for the EU VoP model. However, we have **no concept of a centralized account directory or registry**. This means:
- No proxy/alias resolution (pay by phone number)
- No single source of truth for account-to-holder mappings across PSPs
- No mechanism for government/regulatory inquiries against a consolidated registry

**Importance: HIGH** -- While our VoP design is technically sound for EU IPR compliance, the absence of a registry concept limits our ability to model realistic cross-PSP verification and mobile payment enabling. KAR is what makes Vipps work.

### 4.2 HIGH: Multiple Batch Clearing Windows Per Day

**What they have:** NICS Net operates with **5 clearing windows per business day** (05:30, 09:30, 11:30, 13:30, 15:30), with a cut-off at 14:30. This means a payment submitted at 10:00 can settle by 11:30 -- same-day, within hours, without needing instant payment.

**What we have:** Our A2 clearing engine specifies a single configurable clearing window (described as "end-of-day" or "every 15 minutes for near-real-time"). UC-04 describes a single scheduled clearing cycle trigger.

**Why this matters:** Multiple clearing windows are the Norwegian standard. A system claiming to mirror NICS but offering only one batch window per day misses a key operational characteristic. The 5-window model also provides a middle ground between batch (once/day) and instant (per-transaction) that is economically important -- batch netting at multiple intervals reduces settlement risk without the cost of instant gross settlement.

**Importance: MEDIUM-HIGH** -- Our configurable window approach technically supports multiple windows (just schedule more triggers), but the requirements and use cases describe only a single cycle pattern. This should be made explicit.

### 4.3 HIGH: Large-Value Payment Handling (NICS Brutto / RTGS)

**What they have:** NICS Brutto handles payments exceeding NOK 25 million. These are settled individually through Norges Bank's NBO (real-time gross settlement system) during business hours (05:30-16:35). This is a separate processing path from batch netting.

**What we have:** No concept of a large-value payment tier. All payments go through either batch netting or instant payment (which has a EUR 100,000 cap). A payment of NOK 30 million has no processing path in our system.

**Why this matters:** Large-value payments are critical for interbank settlement, corporate treasury, and securities settlement. Omitting this tier means our system cannot process high-value transfers that are routine in the Norwegian banking system. While this may be acceptable for a portfolio project focused on retail/SME payments, any domain expert from Mastercard Oslo or DNB would immediately notice the gap.

**Importance: MEDIUM** -- Large-value RTGS is a different market segment (wholesale vs. retail). Our system is explicitly retail-focused. However, acknowledging the gap shows awareness.

### 4.4 MEDIUM: KID-Based Payment Reconciliation (OCR Giro)

**What they have:** The OCR giro system uses KID (Kundeidentifikasjonsnummer / Customer Identification Number) -- a structured reference number with a MOD10/MOD11 check digit -- to automatically match payments to invoices. KID is the Norwegian standard for payment reconciliation and is deeply integrated with eFaktura and AvtaleGiro.

**What we have:** Our reconciliation model (A1-F15 through F17) uses external reference IDs and settlement references linked to clearing cycle IDs. We support ISO 20022 structured references. But we do not model KID specifically.

**Why this matters:** KID is Norway-specific but universal within Norway. Every Norwegian business and consumer encounters KID numbers on payment slips, invoices, and bank statements. A system targeting Norwegian employers that does not mention KID misses a fundamental domain concept.

**Importance: MEDIUM** -- KID is essentially a specific format of structured reference. Our generic "reference" field in the invoice entity can accommodate KID values. The gap is conceptual (not mentioning KID by name) rather than architectural.

### 4.5 MEDIUM: B2B Direct Debit (Autogiro)

**What they have:** Autogiro is a dedicated B2B direct debit service with 4 daily submission deadlines (09:45, 10:45, 13:45, 16:45) and specific settlement timings. It handles ~4 million transactions annually.

**What we have:** Our AvtaleGiro model (UC-06) covers consumer direct debit mandates. Our gap analysis identifies SEPA Direct Debit (SDD) as GAP 5 but does not specifically address a B2B direct debit variant beyond mentioning SDD B2B (no refund rights).

**Why this matters:** B2B payments have different characteristics (higher values, no consumer refund rights, specific submission deadlines). The Norwegian Autogiro is a distinct instrument from AvtaleGiro. While small in volume (4M vs. 112M for AvtaleGiro), it serves an important corporate segment.

**Importance: LOW-MEDIUM** -- This is a niche segment. Our system's focus on retail payments is reasonable. However, noting the B2B variant would demonstrate completeness.

---

## 5. Where Our System is Stronger

### 5.1 EU Instant Payment Regulation Compliance (CRITICAL Advantage)

Our instant payment path (UC-08, A2 instant path) is designed from the ground up for EU IPR 2024/886 compliance:
- **EUR 100,000 transaction limit** vs. MPSNOR's NOK 40,000 (~EUR 3,500) -- a **28x higher limit**
- **10-second SLA with documented time budget** (300ms VoP + 100ms validation + 100ms reservation + 50ms sanctions + 100ms fraud + 300ms settlement + 200ms confirmation = 1,250ms used, 8,750ms buffer)
- **24/7/365 availability** explicitly required (OPS-08)
- **Positive confirmation** (pacs.002) within the SLA window
- **Timeout handling**: reject with reason code (no limbo state)

MPSNOR's Straks 2.0 is a mature instant payment system, but its NOK 40,000 cap will need to increase substantially when the EU IPR is incorporated into the EEA Agreement (expected 2026-2027). Our system is already designed for the higher EU limits.

### 5.2 Verification of Payee -- Full Implementation (CRITICAL Advantage)

Our A6 is a purpose-built VoP service with:
- **Requester and responder roles** (both mandatory under EU IPR)
- **Fuzzy name matching** with configurable Jaro-Winkler thresholds (>=0.95 MATCH, >=0.85 CLOSE_MATCH)
- **Nordic diacritics handling** (ø, å, ä, ü) and common abbreviations (AS/Aksjeselskap)
- **Privacy rules**: actual name returned on CLOSE_MATCH but NOT on NO_MATCH
- **Anti-enumeration**: rate limiting + same-IBAN-multiple-names detection
- **Fraud integration**: VoP result feeds directly into A5 fraud scoring with calibrated risk weights

MPSNOR has KAR, which can function as a voluntary VoP lookup, but it is **not a VoP service** in the EU IPR sense. KAR predates VoP regulation and does not implement the traffic-light response model, fuzzy matching, anti-enumeration, or fraud integration that the EU IPR mandates. When the IPR is incorporated into Norwegian law (expected 2026-2027), MPSNOR will need to build or integrate a VoP layer on top of KAR.

### 5.3 Fraud Detection Architecture (HIGH Advantage)

Our A5 implements a sophisticated 3-layer fraud detection pipeline:
- **Layer 1**: Velocity rules (7 defined rules: VEL-01 through VEL-07) with Kafka Streams state stores
- **Layer 2**: Statistical anomaly detection (z-score based, per-account historical profiles)
- **Layer 3**: ML model scoring via Deep Java Library (DJL)
- **Score aggregation**: Weighted combination with configurable thresholds (auto-pass <0.5, manual review 0.5-0.7, auto-block >0.7)
- **VoP integration**: NO_MATCH override adds +0.3 to fraud score
- **Semantic duplicate integration**: Duplicate override from A2 adds +0.2

MPSNOR operates clearing infrastructure, not fraud detection. Fraud detection at the Norwegian national level is handled by individual banks and by Mastercard's card network tools (which apply to card transactions, not account-to-account clearing). Our system integrates fraud detection directly into the clearing pipeline -- a significant architectural advantage.

### 5.4 Sanctions Screening Architecture (HIGH Advantage)

Our sanctions screening (A5-F20 through F29) has a well-documented 3-tier architecture:
- **Tier 1**: Bloom filter for O(1) negative elimination (~99% of transactions clear in <1ms)
- **Tier 2**: Aho-Corasick multi-pattern matching for exact name matches
- **Tier 3**: Jaro-Winkler + Soundex/Metaphone fuzzy matching for transliterations and variations
- **SLA**: <50ms per transaction
- **Critical design decision**: Sanctions screening cannot be skipped -- if unavailable, payment is rejected (unlike fraud scoring which can auto-pass on timeout)

MPSNOR's sanctions screening capabilities at the clearing level are not publicly documented in detail. Individual banks are responsible for their own AML/CFT compliance. Our integrated approach -- sanctions screening as a mandatory step in the clearing pipeline -- is a stronger architectural position.

### 5.5 SCA Exemption Engine (HIGH Advantage)

Our A4 models 4 SCA exemption types with detailed decision logic:
- **Low-value**: <EUR 30 with cumulative caps (5 transactions or EUR 100)
- **TRA**: Risk-based with EBA fraud rate thresholds (EUR 100/250/500 brackets)
- **Trusted beneficiary**: Payer whitelist
- **Recurring**: Same amount + same payee

This is not applicable to MPSNOR (they don't handle SCA -- banks do), but it is a significant differentiator for our system as an integrated payment platform.

### 5.6 Request-to-Pay / SRTP Evolution (MEDIUM-HIGH Advantage)

Our A3 has evolved beyond basic eFaktura to implement SEPA Request to Pay (SRTP) capabilities:
- **Decline with reason code** (not just ignore/expire)
- **Defer** (accept but pay later, specify date)
- **Request amendment** (propose different amount/date)
- **ISO 20022 pain.013/014** message modeling
- **Instant payment on accept** (links to SCT Inst path)
- **HMAC-SHA256 signed webhooks** with retry logic

MPSNOR's eFaktura is a production system used by 90%+ of Norwegian households, but it is fundamentally an accept-or-ignore model. The SRTP evolution in our system is forward-looking and aligns with the pan-European direction.

### 5.7 Variable Recurring Payments (MEDIUM Advantage)

Our VRP (UC-10) is a differentiating feature with no EU equivalent:
- Consent with parameter bounds (max per payment, max per period, max frequency, specific payee, date range)
- Bank-side enforcement (A4 validates every payment against consent parameters)
- No per-payment SCA (SCA only at consent creation)
- Smart use cases: sweeping, variable billing, metered services

MPSNOR does not offer VRP or anything equivalent. AvtaleGiro mandates have max amount limits but lack the granular parameter control and instant settlement of VRP.

### 5.8 ISO 20022 Adoption (MEDIUM Advantage)

Our system specifies full ISO 20022 from the start: pacs.008, pacs.002, pacs.004, pain.001, pain.013/014, camt.053. NICS has "legacy" ISO 20022 status -- only Straks 2.0 uses ISO 20022 messaging, while the rest of the clearing infrastructure has not fully migrated. The Norwegian industry is considering ISO 20022 for replacing existing domestic payment platforms, but the timeline is unclear.

---

## 6. Importance Assessment of Mastercard's Advantages

| # | MPSNOR Advantage | Importance | Justification |
|---|-----------------|-----------|---------------|
| 1 | **KAR (centralized account registry)** | **HIGH** | Enables mobile payments (Vipps), government AML inquiries, and cross-PSP account resolution. Fundamental infrastructure concept missing from our system. Without it, we cannot model how Vipps works or how regulatory bodies access account data. |
| 2 | **Multiple batch clearing windows** | **MEDIUM-HIGH** | Norwegian operational standard. Important for realism but architecturally simple to add (schedule multiple triggers). The gap is in requirements documentation, not in architectural capability. |
| 3 | **Large-value RTGS payments** | **MEDIUM** | Different market segment (wholesale). Important for completeness but our system explicitly targets retail/SME. The gap is deliberate and defensible, but should be acknowledged. |
| 4 | **KID-based reconciliation** | **MEDIUM** | Norwegian domain standard. Easy to accommodate (KID is a specific structured reference format). The gap is terminology, not architecture. |
| 5 | **B2B direct debit (Autogiro)** | **LOW-MEDIUM** | Small volume (4M vs. 112M AvtaleGiro). Corporate niche. Low priority but worth mentioning for completeness. |

---

## 7. Recommendations for Improving Our Requirements

### Recommendation 1: Add Account Registry / Directory Service Concept (Priority: HIGH)

**Problem:** Our system has no centralized account directory. VoP (A6) works on a per-query basis, which is correct for EU IPR compliance, but we lack the foundational concept of a shared account registry that enables mobile payment resolution, regulatory data access, and cross-PSP account lookup.

**Recommendation:** Add a new component or extend A6 with an **Account Directory Service** concept, inspired by KAR but adapted for our architecture:

- **New requirement A6-F11**: Maintain a local account directory mapping IBANs to account holder names, updated in near-real-time from A1 events. This directory is the data source for VoP responder queries.
- **New requirement A6-F12**: Support proxy/alias registration: account holders can register a phone number or email as an alias for their IBAN. Alias-to-IBAN resolution available via API.
- **New requirement A6-F13**: Regulatory inquiry API: authorized government entities (FIU, Finanstilsynet equivalent) can query the directory for AML/CFT investigations, with full audit logging.
- **Add to UC-09**: New scenario 9D for alias/proxy resolution before payment initiation (payer enters phone number, system resolves to IBAN, then proceeds with VoP check on the resolved IBAN).

**Rationale:** KAR is what makes Vipps work. Mobile payment is Norway's dominant payment channel. A payment infrastructure that cannot resolve phone numbers to accounts is incomplete in the Norwegian context. This also elevates our existing DIFF 3 (Proxy/Alias Resolution) from "nice-to-have" to a more integrated part of the architecture.

**Effort:** Medium. The VoP responder already queries A1 for account holder names -- extending this to a maintained directory with alias support is incremental.

### Recommendation 2: Specify Multiple Batch Clearing Windows (Priority: MEDIUM-HIGH)

**Problem:** Our UC-04 and A2 requirements describe a single batch clearing cycle pattern. NICS operates with 5 daily windows. Our requirements need to explicitly support multiple configurable windows.

**Recommendation:**

- **Modify A2-F04**: Change from "Support batch clearing: collect instructions over configurable window, process as batch" to: "Support batch clearing with **multiple configurable daily windows**. Default schedule: 5 settlement windows per business day (configurable). Each window collects instructions submitted since the previous window. Window times, frequency, and cut-off rules are configuration-driven."
- **Add A2-F14**: "Support configurable cut-off time per clearing window. Instructions received after cut-off are queued for the next window."
- **Modify UC-04**: Add a note in the preconditions that the scheduler can trigger at multiple configured times per day, and show an example with 5 daily windows mirroring NICS Net.
- **Add UC-04 AF-07**: "Late instruction: payment instruction arrives after the window's cut-off time. A2 queues the instruction for the next clearing window. Status: QUEUED_FOR_NEXT_CYCLE."

**Rationale:** This is the Norwegian operational standard. Any reviewer from Mastercard Oslo would expect multiple clearing windows. The change is requirements-level (our architecture already supports it -- Spring Batch can be triggered on any schedule).

**Effort:** Low. Configuration and documentation change, no architectural impact.

### Recommendation 3: Acknowledge Large-Value Payment Tier (Priority: MEDIUM)

**Problem:** Our system has no path for payments exceeding EUR 100,000 (instant path cap). Batch path has no stated limit but performs netting, which is inappropriate for large-value individual payments that require RTGS-style gross settlement.

**Recommendation:**

- **Add A2-F15**: "Payments exceeding a configurable threshold (default: NOK 25,000,000 or EUR equivalent) are routed to a **large-value processing path** with individual gross settlement (no netting). These payments are subject to enhanced compliance checks (extended sanctions screening, manual authorization)."
- **Add A2-F16**: "Large-value payments are settled during business hours only (not 24/7). Settlement is final upon confirmation from the settlement system."
- **Add to UC-04 AF-08**: "Payment amount exceeds large-value threshold. A2 routes to large-value path: individual gross settlement, enhanced screening, settlement during business hours. Status: PENDING_LARGE_VALUE_SETTLEMENT."
- **Add to the gap analysis**: A note that NICS Brutto handles this via Norges Bank NBO, and our model abstracts this as a configurable routing decision within A2.

**Rationale:** While our system targets retail/SME payments, not modeling any large-value path at all is a gap that domain experts will notice. Even a simple acknowledgment with configurable routing shows awareness. The implementation can be minimal (route to a separate queue with manual approval).

**Effort:** Low. Requirements and routing logic only. No need to model full RTGS.

### Recommendation 4: Add KID Reference Support to Invoice/Payment Model (Priority: MEDIUM)

**Problem:** Our invoice entity has a generic "reference" field but does not mention KID by name. KID is the universal Norwegian payment reference standard with MOD10/MOD11 check digit validation.

**Recommendation:**

- **Modify the invoice entity** in UC-01 data requirements: Change `reference: String (KID number or structured reference)` to include validation: "Reference field supports Norwegian KID format (MOD10 or MOD11 check digit validated) or ISO 11649 Creditor Reference (RF prefix). KID validation is applied when the payment is NOK-denominated and a KID-format reference is provided."
- **Add A3-F14**: "Validate KID reference numbers using MOD10 or MOD11 check digit algorithm when provided. Reject payment requests with invalid KID references."
- **Add to the reconciliation model** (A1-F17): "Reconciliation matching supports KID-based payment matching: when a settlement event carries a KID reference, match against the original invoice/payment request KID."

**Rationale:** KID is domain language that every Norwegian banker and developer knows. Naming it explicitly and adding check digit validation demonstrates Norwegian domain fluency. The implementation is trivial (MOD10/MOD11 are simple algorithms).

**Effort:** Very low. Validation logic + terminology.

### Recommendation 5: Document Scope Boundary for Wholesale/RTGS (Priority: LOW-MEDIUM)

**Problem:** Our system does not explain why it excludes large-value / RTGS payments. This could look like an oversight rather than a deliberate scoping decision.

**Recommendation:**

- **Add to system_requirements.md Section 1** (System Overview): A scope statement: "This system focuses on **retail and SME payment processing** -- credit transfers up to EUR 100,000 (instant) and multilateral-nettable batch payments. **Large-value / wholesale payments** (NICS Brutto / RTGS equivalent, typically >NOK 25M) are out of scope for Phase 1 but the architecture supports extension via configurable payment routing (see A2-F15). Securities settlement, TARGET2/T2S, and correspondent banking flows are out of scope."
- **Add to the gap analysis**: A new entry acknowledging NICS Brutto / RTGS as a known exclusion with justification.

**Rationale:** Explicit scoping decisions are stronger than silent omissions. Stating "we chose not to include X because Y" shows more domain knowledge than simply not mentioning X.

**Effort:** Very low. Documentation only.

### Recommendation 6: Add eFaktura Address Concept (Priority: LOW)

**Problem:** MPSNOR's eFaktura routes invoices to consumers using a unique "eFakturaadresse" per consumer. Our system routes payment requests by debtor account number. The eFakturaadresse concept is absent.

**Recommendation:**

- **Add A3-F15**: "Support consumer eFaktura address registration: each consumer can register a receiving address (linked to their account) for electronic invoice delivery. Merchants submit invoices to the eFaktura address; the system resolves this to the consumer's account."
- This is a minor extension to A3 that adds Norwegian domain realism.

**Rationale:** Low priority but adds authenticity. The eFakturaadresse is a routing concept that Norwegian consumers interact with when setting up electronic invoicing.

**Effort:** Very low.

---

## 8. Risk Assessment: What Happens If We Ignore These Gaps

| Gap | Risk if Ignored | Likelihood of Detection by Reviewer |
|-----|----------------|--------------------------------------|
| **No account registry / directory** | Reviewer from Mastercard or DNB will ask "how does phone-to-account resolution work?" and "where is the equivalent of KAR?" | **HIGH** -- This is fundamental infrastructure that every Norwegian payment professional knows |
| **Single batch clearing window** | Reviewer familiar with NICS will note that real Norwegian clearing has multiple daily windows | **MEDIUM-HIGH** -- Easy to spot for anyone who has worked with NICS |
| **No large-value tier** | Reviewer may ask "what happens with a NOK 50M interbank transfer?" | **MEDIUM** -- Less likely if the reviewer focuses on retail, but corporate banking reviewers will notice |
| **No KID mention** | Norwegian banking professional will notice the absence of KID terminology | **MEDIUM** -- KID is ubiquitous in Norwegian payments |
| **No B2B direct debit** | Minor gap; unlikely to be noticed unless reviewer specifically works in B2B payments | **LOW** |
| **No eFaktura address** | Minor domain detail; unlikely to be a blocking observation | **LOW** |

---

## 9. Conclusion

### Summary Scorecard

| Dimension | Our System | MPSNOR | Winner |
|-----------|-----------|--------|--------|
| **EU regulatory readiness** | Designed for EU IPR, PSD2/PSD3, AML Package from day one | Will need to adapt when EU IPR is incorporated into EEA | **Our system** |
| **Norwegian market authenticity** | Models Norwegian instruments but misses some domain concepts (KAR, KID, clearing windows) | IS the Norwegian market infrastructure | **MPSNOR** |
| **Instant payment maturity** | EUR 100K limit, 10-sec SLA with time budget, EU-aligned | NOK 40K limit, production-proven but lower ceiling | **Our system** (design); **MPSNOR** (operational proof) |
| **Fraud & sanctions** | Sophisticated 3-layer pipeline with ML, integrated with VoP | Not documented at clearing level; banks handle individually | **Our system** |
| **Open Banking / API design** | Full PSD2 gateway with SCA exemptions, VRP, PKCE, token rotation | Separate product (Aiia); not integrated into clearing | **Our system** |
| **Bill payment / R2P** | SRTP-aligned with rich lifecycle (decline, defer, amend) | Production eFaktura/AvtaleGiro serving 90%+ of Norwegian households | **MPSNOR** (scale); **Our system** (features) |
| **Scale / production readiness** | Portfolio project; not deployed | 2.7 billion transactions/year, all Norwegian banks | **MPSNOR** (obviously) |
| **VoP implementation** | Purpose-built service with fuzzy matching, anti-enumeration, fraud integration | Voluntary via KAR; not EU IPR-compliant yet | **Our system** |
| **Reconciliation** | Event-sourced with external references, camt.053, reconciliation API | OCR giro with KID, deeply integrated with AvtaleGiro | **MPSNOR** (Norwegian maturity); **Our system** (ISO 20022 alignment) |
| **Multi-currency** | 5 currencies supported from the start (P27 model) | NOK-only for domestic clearing | **Our system** |

### Final Assessment

Our requirements are **architecturally stronger and more forward-looking** than what MPSNOR currently operates. We are designed for the EU regulatory landscape that MPSNOR will need to evolve toward. However, MPSNOR has **decades of production operations** and deep Norwegian domain integration that gives it authenticity our system lacks in places.

The **three most valuable improvements** we can make based on this comparison are:

1. **Add an account directory / registry concept** (KAR equivalent) -- this is the biggest conceptual gap and the most likely to be noticed by Norwegian payment professionals
2. **Specify multiple batch clearing windows** -- simple requirements change that adds significant Norwegian operational authenticity
3. **Add KID reference validation** -- trivial implementation that adds domain fluency

These three changes would significantly strengthen our system's positioning against Mastercard Oslo reviewers while preserving our EU-forward advantages in VoP, fraud detection, instant payments, and open banking.

The fact that Mastercard is reportedly exploring divestiture of this unit (March 2026) reinforces the strategic value of our architectural approach: the Norwegian domestic payment infrastructure may be transitioning, and a system designed for EU-wide compliance from the start is better positioned for whatever comes next.

---

*This review was based on publicly available information about MPSNOR's systems and capabilities. Production details that are not publicly documented (internal architectures, proprietary algorithms, specific SLA metrics) could not be compared. Where MPSNOR capabilities were unclear from public sources, this was noted.*
