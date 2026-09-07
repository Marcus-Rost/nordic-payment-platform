# Requirements Review: Nordic Payment Processing Infrastructure

**Reviewer:** Claude Opus 4.6
**Date:** 2026-04-11
**Scope:** Full requirements corpus (system_requirements.md, 7 sub-project requirements, 10 use cases, 6 master extensions, 7 sub-project gap analyses)
**Focus:** European legislation coverage: payment regulation, banking law, consumer protection, data protection, personal data/identity, and other mandatory EU/EEA law

---

## Executive Summary

The requirements corpus is impressively comprehensive for a portfolio project. It demonstrates deep knowledge of the EU payment ecosystem and correctly identifies the three critical mandatory compliance areas (instant payments, VoP, sanctions screening). The use cases are detailed, well-structured, and cover both happy paths and exception flows with appropriate regulatory grounding.

However, the review identifies **23 material gaps** in European legislation coverage. Of these, **7 are critical** (failure to address them would render parts of the system non-compliant with mandatory EU law), **9 are serious** (significant regulatory risk), and **7 are notable** (best-practice gaps that regulators or auditors would flag). The most systemic weakness is the near-total absence of GDPR, which is striking given that the system processes personal financial data at every layer.

### Verdict

The requirements are strong on payment mechanics and operational design. They are weak on the legal framework that governs those mechanics. The system would function correctly as software but would not pass a regulatory compliance assessment in its current form. The gaps identified below are actionable and can be resolved by adding explicit regulatory requirement sections to the existing documents.

---

## Table of Contents

1. [Assessment Methodology](#1-assessment-methodology)
2. [European Legislation Mapping](#2-european-legislation-mapping)
3. [Critical Gaps (Mandatory EU Law Violated)](#3-critical-gaps)
4. [Serious Gaps (Significant Regulatory Risk)](#4-serious-gaps)
5. [Notable Gaps (Auditor/Regulator Flags)](#5-notable-gaps)
6. [Positive Findings (Well-Covered Areas)](#6-positive-findings)
7. [Use Case Review — Regulatory Completeness](#7-use-case-review)
8. [Cross-Cutting Observations](#8-cross-cutting-observations)
9. [Consolidated Recommendations](#9-consolidated-recommendations)
10. [Appendix: Regulation-to-Requirement Traceability Matrix](#10-appendix-traceability-matrix)

---

## 1. Assessment Methodology

Each requirement and use case was evaluated against the following European legislative instruments (in force or with confirmed compliance deadlines):

| Category | Legislation | Abbreviation | Status |
|----------|------------|--------------|--------|
| Payment regulation | Payment Services Directive 2 (2015/2366) | PSD2 | In force |
| Payment regulation | PSD2 RTS on SCA (2018/389) | PSD2 RTS | In force |
| Payment regulation | EU Instant Payment Regulation (2024/886) | EU IPR | In force (phased) |
| Payment regulation | SEPA Regulation (260/2012) | SEPA Reg | In force |
| Payment regulation | Settlement Finality Directive (98/26/EC) | SFD | In force |
| AML/CFT | 6th Anti-Money Laundering Directive (2024/1640) | AMLD6 | In force |
| AML/CFT | AML Regulation (2024/1624) | AMLR | In force |
| AML/CFT | Funds Transfer Regulation (2015/847, amended 2023/1113) | FTR/TFR | In force |
| Data protection | General Data Protection Regulation (2016/679) | GDPR | In force |
| Data protection | ePrivacy Directive (2002/58/EC) | ePD | In force |
| Digital identity | eIDAS Regulation (910/2014) | eIDAS | In force |
| Digital identity | eIDAS2 / EUDIW proposal | eIDAS2 | Adopted, phased |
| Digital resilience | Digital Operational Resilience Act (2022/2554) | DORA | In force (Jan 2025) |
| Cybersecurity | NIS2 Directive (2022/2555) | NIS2 | In force (transposition deadline Oct 2024) |
| Consumer protection | Payment Accounts Directive (2014/92/EU) | PAD | In force |
| Consumer protection | Consumer Rights Directive (2011/83/EU) | CRD | In force |
| AI regulation | EU AI Act (2024/1689) | AI Act | In force (phased) |
| Software supply chain | Cyber Resilience Act (2024/2847) | CRA | Adopted, phased |
| Proposed | PSD3 / Payment Services Regulation | PSD3/PSR | Proposed (~2026-2027) |
| Proposed | Financial Data Access Regulation | FIDA | Proposed |
| Norwegian law | Finansforetaksloven | FFL | In force |
| Norwegian law | Hvitvaskingsloven (AML Act) | HvL | In force |
| Norwegian EEA | Finanstilsynet PSD2 implementation | FT circulars | In force |

Assessment levels:
- **COVERED**: Requirement explicitly addresses the legislative obligation with traceable requirement IDs
- **PARTIAL**: Some aspects addressed but material elements missing
- **GAP**: Legislative obligation not addressed in requirements
- **N/A**: Legislation not applicable to the system scope

---

## 2. European Legislation Mapping

### 2.1 Summary Matrix

| Legislation | Coverage | Severity of Gap |
|------------|----------|----------------|
| PSD2 (payment services, SCA) | PARTIAL | Serious |
| PSD2 RTS (SCA, dynamic linking, exemptions) | COVERED | -- |
| EU IPR (instant payments, VoP, sanctions) | COVERED | -- |
| SEPA Regulation | PARTIAL | Notable |
| Settlement Finality Directive | GAP | Serious |
| AMLD6 / AMLR | PARTIAL | Critical |
| Funds Transfer Regulation | GAP | Critical |
| GDPR | GAP | Critical |
| ePrivacy Directive | GAP | Notable |
| eIDAS / eIDAS2 | PARTIAL | Serious |
| DORA | GAP | Critical |
| NIS2 | GAP | Serious |
| Payment Accounts Directive | GAP | Notable |
| Consumer Rights Directive | GAP | Serious |
| EU AI Act | GAP | Serious |
| Cyber Resilience Act | PARTIAL | Notable |
| Norwegian AML Act | GAP | Critical |
| Finanstilsynet PSD2 rules | GAP | Critical |
| PSD3/PSR (proposed) | PARTIAL | Notable |
| FIDA (proposed) | GAP | Notable |

---

## 3. Critical Gaps (Mandatory EU Law Violated)

These gaps represent obligations under EU law that is currently in force. Failure to address them would make the system non-compliant.

---

### GAP C-1: GDPR is Absent from All Requirements

**Severity:** CRITICAL
**Legislation:** General Data Protection Regulation (2016/679), Articles 5, 6, 9, 12-22, 25, 30, 32, 35, 44-49
**Affected sub-systems:** ALL (A1 through A6, cross-cutting)

**Finding:** The acronym "GDPR" appears only in passing in SEC-03 ("PCI DSS and GDPR requirement") and in one use case note about VoP privacy. There are zero GDPR-specific requirements across the entire corpus. This is the most significant systemic gap.

**What is missing:**

| GDPR Obligation | Current Status | What Must Be Added |
|----------------|---------------|-------------------|
| **Lawful basis for processing (Art. 6)** | Not addressed | Each sub-system must specify which Art. 6(1) ground applies. For payment execution: Art. 6(1)(b) (contractual necessity) and Art. 6(1)(c) (legal obligation). For fraud/AML: Art. 6(1)(c) (legal obligation) and Art. 6(1)(f) (legitimate interest). For VoP queries about third-party accounts: Art. 6(1)(c) (EU IPR mandate). This is not merely documentation; it determines what data can be collected and retained. |
| **Data minimisation (Art. 5(1)(c))** | Not addressed | A5 (fraud engine) stores full transaction history, account profiles, and case management records. What is the minimum data needed? How long is each category retained? Velocity rule state stores (90-day rolling profiles) must be justified as proportionate. |
| **Storage limitation (Art. 5(1)(e))** | Not addressed | No retention period is defined anywhere. Financial record-keeping law requires 5-10 years for transaction records and AML data. But GDPR requires deletion when the purpose is fulfilled. These must be reconciled with explicit retention schedules per data category. |
| **Data subject rights (Arts. 12-22)** | Not addressed | Right of access (Art. 15): account holders must be able to request all personal data held about them, including fraud scores, VoP query logs, and consent records. Right to rectification (Art. 16): name corrections in A1 must propagate to A6 VoP cache. Right to erasure (Art. 17): limited by legal retention obligations but must be documented. Right to data portability (Art. 20): PSD2 already provides this via AISP, but GDPR adds broader portability rights. Right to object (Art. 21): relevant for profiling in A5 fraud scoring. |
| **Data Protection Impact Assessment (Art. 35)** | Not addressed | Processing that involves systematic monitoring of financial transactions (A5), automated decision-making with legal effects (fraud blocking), and large-scale processing of financial data (all sub-systems) triggers mandatory DPIA. |
| **Data protection by design and default (Art. 25)** | Partially addressed | Encryption at rest is mentioned (SEC-03, A3-S06, A5-S04). But Art. 25 requires more: pseudonymisation where feasible, default minimisation, purpose limitation enforcement in the architecture. |
| **Records of processing activities (Art. 30)** | Not addressed | Each sub-system (as a processor or controller) must maintain ROPA documentation. Not a code requirement per se, but requirements should reference it. |
| **International data transfers (Arts. 44-49)** | Not addressed | If VoP queries reach PSPs outside the EEA, or if Kafka infrastructure is hosted outside the EEA, transfer mechanisms (Standard Contractual Clauses, adequacy decisions) must be specified. |
| **Data breach notification (Arts. 33-34)** | Not addressed | 72-hour notification obligation to supervisory authority. The system must have breach detection and notification procedures. |

**Recommendation:** Add a new cross-cutting section "Data Protection Requirements (GDPR)" to system_requirements.md with requirements GDPR-01 through GDPR-15 covering: lawful basis per sub-system, retention schedule, data subject rights API endpoints, DPIA reference, data protection by design controls, breach notification procedure, international transfer controls, ROPA template.

---

### GAP C-2: DORA (Digital Operational Resilience Act) Not Addressed

**Severity:** CRITICAL
**Legislation:** Regulation (EU) 2022/2554, in force since 17 January 2025
**Affected sub-systems:** ALL

**Finding:** DORA is not mentioned anywhere in the requirements. As a regulation (not a directive), it is directly applicable in all EU/EEA member states without transposition. It applies to payment service providers, account information service providers, and payment initiation service providers — i.e., exactly the entities this system serves.

**What is missing:**

| DORA Requirement | Current Status | Gap |
|-----------------|---------------|-----|
| **ICT risk management framework (Arts. 5-16)** | Not addressed | Must define ICT risk policies, risk tolerance thresholds, business continuity and disaster recovery plans. The 99.99% availability SLA (A2-NF05, OPS-08) is a good operational requirement but is not framed as DORA compliance. |
| **ICT incident reporting (Arts. 17-23)** | Not addressed | Major ICT incidents must be reported to the competent authority within 4 hours (initial notification), 72 hours (intermediate), and 1 month (final). The system must define what constitutes a "major ICT incident" (e.g., instant payment path down, sanctions screening unavailable, data breach) and have automated incident classification. |
| **Digital operational resilience testing (Arts. 24-27)** | Not addressed | Regular testing of ICT tools and systems. For significant financial entities: Threat-Led Penetration Testing (TLPT) at least every 3 years. |
| **Third-party ICT risk management (Arts. 28-44)** | Not addressed | Kafka (Aiven), PostgreSQL hosting, Schema Registry — if these are provided by third-party ICT providers, DORA requires contractual provisions, exit strategies, and ongoing monitoring. |
| **Information sharing (Art. 45)** | Not addressed | Voluntary sharing of cyber threat intelligence with other financial entities. |

**Recommendation:** Add cross-cutting section "Digital Operational Resilience (DORA)" with requirements DORA-01 through DORA-10. Even for a portfolio project, demonstrating DORA awareness in requirements is a strong signal to Norwegian financial sector employers.

---

### GAP C-3: Funds Transfer Regulation (Travel Rule) Not Addressed

**Severity:** CRITICAL
**Legislation:** Regulation (EU) 2015/847 as amended by Regulation (EU) 2023/1113 (recast)
**Affected sub-systems:** A2 (Clearing), A3 (R2P), A4 (PSD2 API), A5 (Fraud/Sanctions)

**Finding:** The Funds Transfer Regulation (commonly called the "Travel Rule" in the EU context) requires that payment service providers include and transmit payer and payee information with every fund transfer for AML/CFT purposes. This is legally mandatory and enforced by national competent authorities.

**What is missing:**

| FTR Requirement | Status |
|----------------|--------|
| **Payer information (Art. 4):** full name, payment account number, address or national identity number or customer ID, BIC or LEI of payer's PSP | PARTIAL — name and account number present in payment instructions, but address/identity number not modeled |
| **Payee information (Art. 4):** full name, payment account number | Present (creditor_name, creditor_account) |
| **Verification of payer information (Art. 5):** PSP must verify payer identity before executing transfer (for amounts >EUR 1,000 or linked transactions) | GAP — no verification requirement in A3 or A4 beyond OAuth2 authentication |
| **Record-keeping (Art. 16):** payer and payee information retained for 5 years | GAP — no retention period specified |
| **Detection of missing information (Art. 11-12):** intermediary and payee PSP must detect transfers missing required payer/payee information and decide whether to reject, suspend, or request | GAP — A2 validates ISO 20022 schema but does not check for FTR completeness |

**Recommendation:** Add FTR-01 through FTR-05 requirements. Extend the PaymentInstruction Kafka schema to include all FTR-mandated payer information fields. Add a validation step in A2 that checks FTR completeness before clearing.

---

### GAP C-4: AML Requirements Incomplete (KYC, PEP, SAR Missing)

**Severity:** CRITICAL
**Legislation:** AMLD6 (2024/1640), AML Regulation (2024/1624)
**Affected sub-systems:** A5 (Fraud & Sanctions), cross-cutting

**Finding:** The system correctly implements transaction monitoring and sanctions screening. But AML compliance is a three-legged stool: (1) Customer Due Diligence (CDD/KYC), (2) Transaction Monitoring + Sanctions Screening, and (3) Reporting. The system addresses leg 2 well, leg 3 partially, and leg 1 not at all.

**What is missing:**

| AML Component | Status | Impact |
|--------------|--------|--------|
| **Customer Due Diligence / KYC (AMLR Arts. 15-29)** | GAP | Account creation (A1-F10, UC-07) creates accounts without any CDD reference. In reality, no account can be opened without verifying the customer's identity, address, and beneficial ownership. Even if CDD is "out of scope," the requirements must specify that account creation assumes completed KYC and must reference/link to an external KYC subsystem. |
| **Politically Exposed Persons (AMLR Art. 35-40)** | GAP | PEP screening is a mandatory AML requirement. The A5 gap analysis correctly notes this is a KYC/onboarding concern rather than transaction-level, but the system creates accounts (UC-07) and processes payments without any PEP awareness. At minimum, A5 should receive PEP status as an input to fraud scoring (a PEP with unusual transaction patterns is a higher-risk signal). |
| **Suspicious Activity Reports / STR (AMLR Art. 50-53)** | PARTIAL | A5 generates a "FIU report" on confirmed sanctions matches but: (a) no structured SAR format is defined, (b) no workflow exists for filing SARs for suspicious (non-sanctioned) transactions, (c) no tipping-off prohibition is enforced (Art. 54 — the customer must not be informed that a SAR has been filed). |
| **Beneficial ownership (AMLR Art. 42-49)** | GAP | For business accounts (A3 merchants), the system does not reference beneficial ownership registers. Relevant because EU AML now requires access to beneficial ownership information. |
| **Risk-based approach documentation (AMLR Art. 8-10)** | GAP | The velocity rules and thresholds in A5 are not grounded in a documented risk assessment. Regulators require PSPs to document their ML/TF risk assessment and demonstrate that monitoring rules derive from identified risks. |
| **Record retention (AMLR Art. 56)** | GAP | Five-year retention required for CDD records and transaction records. No retention period is specified anywhere. |

**Recommendation:** Add section "AML/CFT Compliance Requirements" to system_requirements.md. For a portfolio project, it is acceptable to declare CDD as out of scope, but this must be an explicit architectural decision with documented boundary: "Account creation assumes that KYC/CDD has been completed in an external system. The `account_holder_id` in A1 maps to a verified identity in that system." Add PEP flag as an input field to A5 scoring. Define SAR workflow for fraud-confirmed cases (not only sanctions). Add a tipping-off prohibition to case management (A5-F07 alerts must not be visible to the account holder).

---

### GAP C-5: Norwegian National AML/Financial Supervision Law Not Addressed

**Severity:** CRITICAL
**Legislation:** Hvitvaskingsloven (Norwegian AML Act), Finansforetaksloven, Finanstilsynet circulars
**Affected sub-systems:** ALL

**Finding:** The system explicitly targets Norwegian financial companies (Mastercard Oslo, DNB, SpareBank 1, Tietoevry). The requirements reference EU-level legislation but never cite Norwegian transpositions or national regulations. Norway, as an EEA member, transposes EU directives into national law, but also has additional national requirements.

**What is missing:**

| Norwegian Requirement | Status |
|----------------------|--------|
| **Hvitvaskingsloven (Anti-Money Laundering Act)** — Norway's transposition of AMLD, with national additions including specific reporting obligations to Enheten for finansiell etterretning (EFE/Norwegian FIU) | GAP |
| **Finanstilsynet PSD2 implementation** — Norwegian implementation of PSD2 via Finansforetaksloven. Norwegian PSPs must follow Finanstilsynet's circulars on SCA, API access, and TPP registration | GAP |
| **Norges Bank settlement requirements** — Norwegian Interbank Clearing System (NICS) is explicitly referenced as a model but the regulatory requirements for participation (Norges Bank oversight) are not cited | GAP |
| **Norwegian IBAN format (NO + 2 check digits + 11-digit account number)** — DATA-05 mentions "IBAN format or simplified 11-digit Norwegian account number" but does not cite the regulatory standard | PARTIAL |

**Recommendation:** Add a "Norwegian Regulatory Context" section to system_requirements.md. Even if the system is not a licensed PSP, demonstrating awareness of the national regulatory framework is essential for the target employers.

---

### GAP C-6: Absence of Explicit Retention Periods

**Severity:** CRITICAL (intersects GDPR, AMLR, FTR, PCI DSS, and national law)
**Affected sub-systems:** ALL

**Finding:** No retention period is specified for any data category across the entire requirements corpus. This is not a minor omission — it sits at the intersection of multiple mandatory legal requirements that impose conflicting obligations:

| Data Category | Applicable Retention Requirement | Source |
|--------------|----------------------------------|--------|
| Transaction records | 5 years | AMLR Art. 56, FTR Art. 16 |
| CDD/KYC records | 5 years after relationship ends | AMLR Art. 56 |
| Audit logs | 12 months online, 5 years total | PCI DSS 10.7, AMLR |
| Consent records (PSD2) | Duration of consent + reasonable period for disputes | PSD2 Art. 94 |
| VoP query logs | Not specified by regulation — GDPR Art. 5(1)(e) requires purpose-based limit | GDPR |
| Fraud scores / case records | 5 years | AMLR Art. 56 |
| Kafka event store (A1) | Infinite retention configured but not justified | Conflicts with GDPR Art. 5(1)(e) |
| Sanctions screening decisions | 5 years (legally significant) | AMLR |
| Account holder personal data | Until account closure + retention period | GDPR Art. 17(3)(b) |

**The core tension:** Kafka infinite retention (`retention.ms=-1`) for event sourcing directly conflicts with GDPR storage limitation. This must be resolved architecturally — e.g., crypto-shredding (encrypt PII in events with per-customer keys, delete keys after retention period to render events unreadable while preserving the event chain for audit).

**Recommendation:** Add requirement DATA-07: "Define retention schedules per data category aligned with AMLR (5 years), PCI DSS (12 months online), and GDPR (purpose-based limitation). Resolve the event sourcing vs. GDPR tension via crypto-shredding or data lifecycle management."

---

### GAP C-7: GDPR Automated Decision-Making (Art. 22) Not Addressed for Fraud Scoring

**Severity:** CRITICAL
**Legislation:** GDPR Art. 22
**Affected sub-systems:** A5 (Fraud & Sanctions Engine)

**Finding:** A5 makes automated decisions (AUTO_BLOCK at score >= 0.7) that produce legal effects on individuals — their payment is rejected and in some cases their account is frozen. GDPR Art. 22 restricts automated individual decision-making that produces legal effects, unless:
- (a) it is necessary for a contract, or
- (b) authorized by EU/member state law, or
- (c) based on explicit consent.

For fraud blocking, ground (b) applies (AML legal obligations). However, Art. 22(3) requires that even under these grounds, the data controller must implement suitable measures to safeguard the data subject's rights, including **at minimum the right to obtain human intervention, to express their point of view, and to contest the decision.**

The current requirements have case management (A5-F07/F08) but only for alerts in the MANUAL_REVIEW or AUTO_BLOCK range, and only for fraud analysts — not for the affected account holder. There is no requirement for the account holder to be informed of an automated decision, to contest it, or to obtain human review.

**Recommendation:** Add requirements:
- A5-GDPR-01: When a payment is AUTO_BLOCKed, the payer must be informed that the decision was made by automated means and must be given a mechanism to contest the decision and request human review.
- A5-GDPR-02: Account holders must be able to request meaningful information about the logic involved in fraud scoring (GDPR Art. 22(3) + Art. 15(1)(h)), without revealing scoring parameters that would enable gaming.

---

## 4. Serious Gaps (Significant Regulatory Risk)

These gaps represent obligations where the legislation is in force and non-compliance carries regulatory risk, even if the immediate operational impact may be less than the critical gaps.

---

### GAP S-1: Settlement Finality Directive Not Addressed

**Severity:** SERIOUS
**Legislation:** Settlement Finality Directive 98/26/EC
**Affected sub-systems:** A2 (Clearing Engine), A1 (Account Ledger)

**Finding:** The system uses "SETTLED" as a terminal status, and UC-08 correctly states that instant payments are "immediate, irrevocable, and final." However, settlement finality is a precise legal concept under the SFD that determines the exact moment when a payment obligation becomes irrevocable and enforceable against third parties (including in insolvency). The system lacks:
- A distinct `SETTLEMENT_FINALITY` event (as noted in the A2 gap analysis, this is only a "Should" priority — it should be "Must")
- A clear definition of the legal moment of finality (entry into settlement system? debit from payer? confirmation sent?)
- Protection against insolvency-related challenges (SFD Art. 3 — transfer orders entered into a system are enforceable and binding even in insolvency proceedings, from the moment of entry)

**Recommendation:** Elevate the settlement finality event to Must priority. Define the legal moment of finality in an ADR. Add requirement A2-F-FINALITY: "Each settled payment must carry a timestamp representing the moment of settlement finality as defined by the SFD, distinguishing it from the processing completion timestamp."

---

### GAP S-2: NIS2 Directive Not Addressed

**Severity:** SERIOUS
**Legislation:** NIS2 Directive 2022/2555 (transposition deadline October 2024)
**Affected sub-systems:** ALL

**Finding:** Financial market infrastructure and digital infrastructure operators are "essential entities" under NIS2. Obligations include: cybersecurity risk management measures (Art. 21), incident reporting to CSIRTs within 24 hours of awareness (Art. 23), supply chain security (Art. 21(2)(d)), and management body oversight of cybersecurity.

The system has good operational security (SEC-01 through SEC-12) but does not frame these as NIS2 compliance. More importantly, NIS2's incident reporting timeline (24 hours initial, 72 hours detailed) differs from DORA's (4 hours initial for major ICT incidents) and both must be met.

**Recommendation:** Add requirement SEC-13: "Incident detection and reporting capability compliant with NIS2 Art. 23 and DORA Arts. 17-23 reporting timelines."

---

### GAP S-3: Consumer Protection for Recurring Payments / Direct Debits

**Severity:** SERIOUS
**Legislation:** PSD2 Art. 76 (refund right for direct debits), Consumer Rights Directive 2011/83/EU, Payment Accounts Directive 2014/92/EU Art. 10
**Affected sub-systems:** A3 (Bill Payment / R2P), use cases UC-06 and UC-10

**Finding:** The AvtaleGiro mandate model (UC-06) deliberately omits SEPA Direct Debit refund rights ("8-week unconditional refund for SDD Core"). The rationale given is that the push-payment model is safer. While the architectural choice is sound, the requirements fail to address consumer protection obligations that exist regardless of the payment model:

| Consumer Right | Status | Source |
|---------------|--------|--------|
| **Right to cancel a recurring mandate at any time** | COVERED (UC-06 AF-02: DELETE revokes mandate) | PSD2 Art. 75 |
| **Advance notification before debit** | GAP | PSD2 Art. 78: for direct debits, the payee or PSP must provide information about the upcoming debit at least 4 weeks before the debit date (or agreed shorter period). UC-06 auto-approves without any notification to the consumer before the debit executes. |
| **Right to receive a refund for authorised payments** | GAP | PSD2 Art. 76: 8-week unconditional refund right for authorised direct debits. Even if the mandate model is "push," when auto-approval occurs without consumer interaction the economic reality resembles a direct debit and the refund right may apply. This legal ambiguity must be addressed. |
| **Right to set spending limits** | COVERED (mandate max_amount) | PSD2 Art. 75 |
| **Switching facilitator** | GAP | PAD Art. 10: payment account providers must facilitate switching including mandate migration. Not addressed. |

**Recommendation:** Add requirements:
- A3-CONSUMER-01: Before auto-executing an AvtaleGiro mandate, send a notification to the consumer at least N days before execution (configurable, default 4 days per PSD2 Art. 78).
- A3-CONSUMER-02: Document the legal position on refund rights when mandates auto-approve without consumer interaction. Consider implementing a voluntary refund mechanism to mitigate regulatory risk.

---

### GAP S-4: eIDAS / eIDAS2 Not Properly Addressed

**Severity:** SERIOUS
**Legislation:** eIDAS Regulation 910/2014, eIDAS2 Regulation (adopted 2024)
**Affected sub-systems:** A4 (PSD2 API Gateway)

**Finding:** A4-S09 says "TPP client certificate validation (mTLS) for PSD2 compliance simulation." PSD2 requires TPPs to identify themselves using eIDAS Qualified Website Authentication Certificates (QWACs) and Qualified Electronic Seals (QSealC). The requirements mention "simulation" but do not specify:
- What exactly is simulated vs. what would be real in production
- The role of Qualified Trust Service Providers (QTSPs) in issuing certificates
- That under eIDAS2 and the EU Digital Identity Wallet (EUDIW), strong customer authentication will evolve to include wallet-based identification
- The structure of PSD2 certificates (X.509 with specific attributes in the organization identifier field per ETSI TS 119 495)

**Recommendation:** Add an ADR documenting: "In the portfolio project, eIDAS QWAC is simulated via self-signed client certificates with PSD2-structured organization identifiers. In production, certificates would be obtained from a QTSP listed on the EU Trusted List." Add a note about eIDAS2/EUDIW implications for future SCA evolution.

---

### GAP S-5: EU AI Act Implications for ML Fraud Scoring

**Severity:** SERIOUS
**Legislation:** EU AI Act (2024/1689), Annex III(5)(b)
**Affected sub-systems:** A5 (Fraud & Sanctions Engine)

**Finding:** The EU AI Act classifies AI systems used for "creditworthiness assessment" and "risk assessment and pricing in life and health insurance" as high-risk (Annex III(5)). While fraud detection is not explicitly listed, an AI system that makes or supports decisions to block financial transactions (AUTO_BLOCK), freeze accounts, or flag individuals for investigation materially affects their access to financial services. This is likely to be classified as high-risk under Annex III(5)(b): "AI systems intended to be used to evaluate the creditworthiness of natural persons or establish their credit score, with the exception of AI systems used for the purpose of detecting financial fraud."

The exception for fraud detection is notable but narrow — it applies to detecting fraud, not to **blocking transactions** based on fraud scores. The AUTO_BLOCK mechanism produces a legal effect (denial of financial service), which may bring it back into high-risk scope despite the fraud detection exception. This legal uncertainty must be addressed.

**Relevant AI Act obligations (if high-risk applies):**
- Risk management system (Art. 9)
- Data governance (Art. 10): training data quality, representativeness, bias checking
- Technical documentation (Art. 11)
- Record-keeping / logging (Art. 12) — the system's audit trail partially satisfies this
- Transparency to users (Art. 13): the "explanation" field in fraud scores partially satisfies this
- Human oversight (Art. 14): case management partially satisfies this
- Accuracy, robustness, cybersecurity (Art. 15)

**Recommendation:** Add an ADR: "EU AI Act assessment for fraud scoring ML model." Document the position on whether the fraud detection exception in Annex III(5)(b) applies. Regardless of classification, adopt AI Act-aligned practices: maintain documentation of training data, monitor for bias across protected characteristics (nationality, name origin given sanctions/VoP name matching), and ensure human oversight of automated blocking decisions.

---

### GAP S-6: PSD2 Liability Framework Not Addressed

**Severity:** SERIOUS
**Legislation:** PSD2 Arts. 73-74, 80, 89
**Affected sub-systems:** A2 (Clearing), A4 (PSD2 API), A6 (VoP)

**Finding:** PSD2 establishes a detailed liability framework for unauthorized and incorrectly executed payment transactions. The requirements do not address:

| PSD2 Liability Rule | Status |
|---------------------|--------|
| **Art. 73 — Payer's PSP liability for unauthorized transactions:** immediate refund (by end of next business day) unless suspected fraud | GAP |
| **Art. 74 — Payer's liability capped at EUR 50 for unauthorized transactions** (unless gross negligence/fraud) | GAP |
| **Art. 80 — Incorrect execution by PSP:** If PISP initiates payment and PSP executes to wrong beneficiary, PSP bears liability. **EU IPR Art. 5c(4) adds**: if PSP fails to perform VoP check, PSP bears liability for misdirected payments to the extent the VoP check would have prevented the error | GAP |
| **Art. 89 — PISP liability:** If the payment initiation was the cause of incorrect execution, the PISP must compensate the payer's PSP | GAP |
| **Refund procedures and timelines** | GAP |

**The VoP liability point is particularly important:** EU IPR explicitly shifts liability to the PSP if it fails to perform VoP. This means VoP is not just a fraud reduction tool — it is a liability shield. The requirements should make this explicit.

**Recommendation:** Add requirement A2-LIABILITY-01: "Document the PSD2 liability framework for payment execution failures. Implement refund/dispute resolution API endpoints. Link VoP requirement (SEC-12) to liability protection: if VoP is not performed, the PSP bears liability for misdirected funds under EU IPR Art. 5c(4)."

---

### GAP S-7: Right to a Basic Payment Account (PAD)

**Severity:** SERIOUS
**Legislation:** Payment Accounts Directive 2014/92/EU, Art. 16-18
**Affected sub-systems:** A1 (Account Ledger), UC-07

**Finding:** UC-07 (Account Management) covers account creation, freeze, and closure, but does not reference the PAD, which gives every EU/EEA resident the right to a payment account with basic features. Relevant implications:
- Account refusal must be justified (Art. 16(3))
- Account termination requires at least 2 months' notice and must be for specific reasons (Art. 19)
- Account freeze for fraud/AML must still comply with procedural safeguards

The current account freeze flow (UC-07 Scenario 7B) allows A5 or an admin to freeze immediately with no notice, no time limit, and no appeal mechanism. While fraud/AML-based freezing is legally justified, it must be proportionate and time-limited.

**Recommendation:** Add requirements:
- A1-PAD-01: Account freeze must include a review deadline (e.g., 30 days) after which the freeze must be reviewed and either lifted or escalated to legal proceedings.
- A1-PAD-02: Account closure must comply with PAD Art. 19 (2-month notice, specific grounds).

---

### GAP S-8: Accessibility Requirements

**Severity:** SERIOUS
**Legislation:** European Accessibility Act (2019/882), effective June 2025
**Affected sub-systems:** A3 (consumer-facing R2P), A4 (consent UI, SCA, VRP consent display), A6 (VoP "Did you mean?" UI)

**Finding:** The European Accessibility Act applies to payment services (Art. 2(2)(b)) and requires that consumer-facing payment service interfaces be accessible to persons with disabilities. No accessibility requirements exist in the corpus. While the system is primarily API-based, the use cases describe consumer-facing UI elements:
- Consent approval screen (UC-02, UC-10)
- SCA challenge screen with dynamic linking display (UC-03)
- VoP name mismatch warning (UC-09)
- R2P accept/decline/defer interface (UC-01)

**Recommendation:** Add requirement UX-01: "All consumer-facing interfaces must comply with WCAG 2.1 AA and the European Accessibility Act (2019/882) accessibility requirements for payment services."

---

## 5. Notable Gaps (Auditor/Regulator Flags)

These are best-practice gaps that would be flagged in a compliance assessment but are less likely to result in regulatory action.

---

### GAP N-1: SEPA Regulation — IBAN-Only Rule

**Legislation:** SEPA Regulation 260/2012, Art. 5(7)
**Finding:** The system supports BIC in QR codes (EPC069-12 format includes BIC). Under the SEPA Regulation "IBAN-only" rule, payers should not be required to provide a BIC for cross-border SEPA payments. Minor point, but auditors check this.

### GAP N-2: ePrivacy — Cookie/Tracking for Consent UI

**Legislation:** ePrivacy Directive 2002/58/EC
**Finding:** If the consent UI (A4) or VoP correction UI (A6) uses cookies or tracking, ePrivacy consent requirements apply. Not addressed, but may not be relevant if UIs are purely functional.

### GAP N-3: Cyber Resilience Act — SBOM Obligations

**Legislation:** Cyber Resilience Act 2024/2847 (phased, full effect ~2027)
**Finding:** The Secure Spring Boot Starter (Project #4) generates SBOMs in CycloneDX format (STARTER-F30), which is forward-looking. However, the CRA will impose specific SBOM obligations including vulnerability handling, coordinated disclosure, and security updates. The current requirements should reference the CRA as the regulatory basis for SBOM generation.

### GAP N-4: PSD3/PSR Preparation

**Legislation:** Proposed PSD3 (Directive) and PSR (Regulation)
**Finding:** The requirements mention PSD3 awareness (configurable 90-day re-auth parameter). However, PSD3/PSR will introduce: enhanced fraud data sharing between PSPs, stricter API performance requirements, IBAN/name verification (already covered via VoP), and the transition from a directive to a directly applicable regulation. The system is generally well-positioned, but a formal PSD3 readiness assessment section would strengthen the documentation.

### GAP N-5: FIDA (Financial Data Access Regulation)

**Legislation:** Proposed FIDA
**Finding:** FIDA will extend open banking beyond payment accounts to savings, investments, insurance, and pensions. Not immediately relevant, but worth noting in an ADR as the next evolution of the AISP concept.

### GAP N-6: PCI DSS v4.0 Broader Scope

**Legislation/Standard:** PCI DSS v4.0 (effective March 2025)
**Finding:** The Secure Starter references PCI DSS 10.3.6 for audit logging. However, PCI DSS scope assessment is incomplete — if the system stores, processes, or transmits cardholder data (it processes IBAN-based transfers, not card payments), PCI DSS may not be in scope at all. This should be clarified to avoid implying a PCI DSS obligation that doesn't exist, or to identify one that does.

### GAP N-7: Log Integrity / Immutability

**Cross-cutting finding:** Audit logs are described as "immutable" (A2-S04, A5-S06, A4-S08), and Kafka's append-only nature supports this. However, PostgreSQL-stored audit projections are not inherently immutable. For regulatory compliance (AMLR Art. 56, PCI DSS 10.5), consider write-once storage, cryptographic chaining (hash chain / Merkle tree), or database triggers preventing UPDATE/DELETE on audit tables.

---

## 6. Positive Findings (Well-Covered Areas)

The following areas demonstrate strong regulatory awareness and correct implementation:

### 6.1 EU Instant Payment Regulation (2024/886) — STRONG

The IPR is comprehensively addressed across UC-08, the instant payments extension, A2 requirements, and A6 requirements:
- 10-second SLA with detailed time budget breakdown
- 24/7/365 availability as a hard requirement
- EUR 100,000 transaction limit (configurable)
- Mandatory VoP with both requester and responder roles
- VoP free of charge (A6-F09, correctly citing regulatory obligation)
- Sanctions screening mandatory, reject-on-unavailability (A5-F28)
- No limbo state: mandatory reject on timeout (A2-F25)
- Gross settlement per transaction for instant path
- Dedicated Kafka topics to prevent head-of-line blocking
- Balance reservation pattern (reserve-confirm-release)

### 6.2 PSD2 RTS on SCA — STRONG

SCA implementation in A4 is thorough:
- Dynamic linking correctly implemented (SCA challenge bound to amount + creditor)
- All four exemption types modeled with proper cumulative counters
- TRA exemption references EBA thresholds (EUR 100/0.13%, EUR 250/0.06%, EUR 500/0.01%)
- SCA exemption decisions logged with type and rationale (audit requirement)
- PKCE mandatory (mitigates authorization code interception)
- Refresh token rotation with family revocation (detects token theft)
- SCA timeout handling (5-minute limit)

### 6.3 Sanctions Screening Architecture — STRONG

The three-layer sanctions screening (Bloom filter + Aho-Corasick + Jaro-Winkler fuzzy matching) is production-grade:
- Sub-50ms latency target with realistic architecture
- Correct handling of the asymmetry: fraud timeout = auto-pass, sanctions timeout = reject
- EU and UN sanctions lists addressed
- Retrospective screening on list updates (A5-F29)
- Compliance analyst workflow for hit resolution
- FIU reporting on confirmed matches
- Sanctions decisions are immutable audit records

### 6.4 Verification of Payee Privacy — STRONG

VoP correctly implements the privacy requirements:
- NO_MATCH: actual name NOT returned (prevents name harvesting)
- CLOSE_MATCH: actual name returned (assists correction)
- Anti-enumeration measures (rate limiting + pattern detection)
- VoP result fed into fraud scoring (security best practice)
- Free of charge (regulatory requirement)

### 6.5 ISO 20022 Message Standards — STRONG

Comprehensive ISO 20022 coverage:
- pacs.008 (FIToFICustomerCreditTransfer) for clearing
- pacs.002 (FIToFIPaymentStatusReport) for confirmation
- pacs.004 (PaymentReturn) for returns
- pacs.028 (PaymentStatusRequest) for status inquiry
- pain.001 (CustomerCreditTransferInitiation) for payment initiation
- pain.013/014 (CreditorPaymentActivationRequest/Status) for R2P
- camt.053 (BankToCustomerStatement) for statements and settlement reports

### 6.6 Audit Trail Design — STRONG (with noted gaps)

The audit trail architecture is comprehensive:
- Structured JSON logging with correlation IDs
- Every state-changing operation logged (who, what, when, from where, outcome)
- Kafka-based audit event publishing
- @Audited annotation for consistent implementation
- Sensitive field redaction
- Fraud/sanctions decisions are immutable audit records

---

## 7. Use Case Review — Regulatory Completeness

| Use Case | Regulatory Coverage | Key Gaps |
|----------|-------------------|----------|
| **UC-01 Invoice Payment** | Good: covers VoP, sanctions, fraud, instant settlement. 11 alternative flows cover most regulatory scenarios | Missing: FTR travel rule data, GDPR data subject notification, PSD2 refund rights reference |
| **UC-02 PSD2 Account Access** | Strong: correctly cites PSD2 RTS Art. 10, handles consent expiry, PKCE, token rotation | Missing: GDPR consent for data access (separate from PSD2 consent), eIDAS certificate validation details |
| **UC-03 PSD2 Payment Initiation** | Strong: SCA with dynamic linking, 4 exemption types, TRA thresholds | Missing: PSD2 Art. 73-74 liability framework for failed payments, refund procedure |
| **UC-04 Batch Clearing** | Good: netting, sanctions, fraud, ISO 20022 output | Missing: settlement finality event, FTR compliance check on instructions, regulatory reference for netting obligations |
| **UC-05 Fraud Detection** | Good: three-layer pipeline, case management, sanctions | Missing: GDPR Art. 22 (automated decision-making rights), AI Act assessment, SAR workflow, tipping-off prohibition |
| **UC-06 Recurring Direct Debit** | Adequate: mandate model is sound | Missing: PSD2 Art. 78 advance notification, SDD refund right analysis, PAD implications for mandate revocation |
| **UC-07 Account Management** | Basic: lifecycle states covered | Missing: PAD rights (account refusal justification, closure notice), GDPR account holder rights, KYC/CDD reference at account creation |
| **UC-08 Instant Payment** | Strong: detailed time budget, regulatory SLA, all failure scenarios | Missing: settlement finality timestamp, DORA incident classification for SLA breaches |
| **UC-09 Verification of Payee** | Strong: both roles, privacy rules, anti-enumeration | Missing: liability framework (EU IPR Art. 5c(4)), cross-border interoperability requirement, GDPR basis for VoP query data |
| **UC-10 Variable Recurring Payments** | Good: consent parameters, bank-side enforcement, sweeping | Missing: no EU regulatory mandate exists — must be clearly stated as optional/differentiating; future FIDA implications |

---

## 8. Cross-Cutting Observations

### 8.1 Requirements Quality

The requirements are well-structured with clear IDs, priorities (Must/Should), and traceable cross-references (NAVIGATION.md, SUBSYSTEM_MAP.md). The use cases follow a consistent pattern with actors, preconditions, main flow, alternative flows, postconditions, and data entities. The gap analyses are self-aware and honest about what is missing. This is significantly above average for a portfolio project.

### 8.2 Regulatory Framing

The requirements are written primarily as technical specifications with regulatory rationale given as supporting context. For European financial systems, the framing should be inverted: **regulatory obligations come first, and technical requirements derive from them.** This is not just a stylistic preference — it is how regulatory compliance is demonstrated to supervisors, auditors, and compliance teams at the target companies (DNB, SpareBank 1, etc.).

**Recommendation:** Add a top-level section "2.0 Regulatory Framework" before the cross-cutting requirements, listing all applicable legislation with article-level references, and mapping each to the system requirements that implement it. This section becomes the compliance index that a regulator or auditor would use to assess the system.

### 8.3 Regulation vs. "Portfolio Project" Tension

Several gaps are dismissed in the sub-project gap analyses as "infrastructure concern" or "out of scope for portfolio." This is pragmatically correct — a portfolio project need not implement GDPR data subject access request endpoints. However, the **requirements documents** should still specify these obligations even if the implementation is deferred. Requirements document what the system must do; implementation decides what gets built now. Omitting a requirement because it won't be implemented in phase 1 means it will be forgotten in phase 2.

---

## 9. Consolidated Recommendations

### Priority 1 — Must Address Before Claiming Regulatory Awareness (Critical)

| # | Action | Effort |
|---|--------|--------|
| R-01 | Add GDPR requirements section (lawful basis, retention, data subject rights, DPIA reference, Art. 22 automated decision-making) | Medium |
| R-02 | Add data retention schedule per data category (reconciling AMLR 5-year, PCI DSS 12-month, and GDPR storage limitation) | Low |
| R-03 | Resolve event sourcing vs. GDPR tension (crypto-shredding architecture) | Medium |
| R-04 | Add Funds Transfer Regulation (Travel Rule) data requirements to PaymentInstruction schema | Low |
| R-05 | Add AML boundary definition: document CDD/KYC as external dependency, add PEP flag input to A5, define SAR workflow | Medium |
| R-06 | Add Norwegian regulatory context (Hvitvaskingsloven, Finanstilsynet, Norges Bank) | Low |
| R-07 | Add DORA requirements section (ICT incident classification, reporting timelines, resilience testing) | Low |

### Priority 2 — Should Address for Completeness (Serious)

| # | Action | Effort |
|---|--------|--------|
| R-08 | Add PSD2 liability framework (Arts. 73-74, 80, 89) — especially VoP liability under EU IPR Art. 5c(4) | Low |
| R-09 | Elevate settlement finality event to Must priority; define legal moment of finality | Low |
| R-10 | Add NIS2 incident reporting requirement | Low |
| R-11 | Add consumer protection requirements for recurring mandates (advance notification, refund right analysis) | Medium |
| R-12 | Document eIDAS QWAC simulation boundary and eIDAS2/EUDIW awareness | Low |
| R-13 | Add EU AI Act assessment for fraud scoring ML model | Low |
| R-14 | Add European Accessibility Act requirement for consumer-facing UIs | Low |
| R-15 | Add PAD requirements for account lifecycle (refusal justification, closure notice, freeze review deadline) | Low |

### Priority 3 — Nice to Have (Notable)

| # | Action | Effort |
|---|--------|--------|
| R-16 | Add top-level "Regulatory Framework" section with legislation-to-requirement mapping | Medium |
| R-17 | Add SEPA IBAN-only rule clarification | Trivial |
| R-18 | Add CRA reference for SBOM generation | Trivial |
| R-19 | Add PSD3/PSR readiness assessment | Low |
| R-20 | Add log integrity mechanism (hash chain or write-once storage) | Medium |
| R-21 | Clarify PCI DSS scope (IBAN-based transfers — is PCI DSS applicable?) | Trivial |

---

## 10. Appendix: Regulation-to-Requirement Traceability Matrix

This matrix shows which system requirements trace to which EU legislation. Green cells indicate coverage; red cells indicate gaps.

| Legislation | Article/Section | Obligation | Requirement ID(s) | Status |
|------------|----------------|------------|-------------------|--------|
| **GDPR** | Art. 5(1)(c) | Data minimisation | — | GAP |
| GDPR | Art. 5(1)(e) | Storage limitation | — | GAP |
| GDPR | Art. 6(1) | Lawful basis | — | GAP |
| GDPR | Art. 12-22 | Data subject rights | — | GAP |
| GDPR | Art. 22 | Automated decision-making | — | GAP |
| GDPR | Art. 25 | Data protection by design | SEC-03 (partial) | PARTIAL |
| GDPR | Art. 30 | Records of processing | — | GAP |
| GDPR | Art. 33-34 | Breach notification | — | GAP |
| GDPR | Art. 35 | DPIA | — | GAP |
| GDPR | Art. 44-49 | International transfers | — | GAP |
| **PSD2** | Art. 64-66 | AISP/PISP rights | A4-F01, A4-F02 | COVERED |
| PSD2 | Art. 73-74 | Payer liability | — | GAP |
| PSD2 | Art. 75 | Consent to payment | A3-S05, A4-F03 | COVERED |
| PSD2 | Art. 76 | Refund for direct debits | — | GAP |
| PSD2 | Art. 78 | Advance notification | — | GAP |
| PSD2 | Art. 80 | Execution liability | — | GAP |
| PSD2 | Art. 89 | PISP liability | — | GAP |
| PSD2 | Art. 94 | Data protection | — | GAP |
| PSD2 | Art. 97 | SCA requirement | A4-F05, A4-F06 | COVERED |
| PSD2 RTS | Art. 5 | Dynamic linking | A4-S06 | COVERED |
| PSD2 RTS | Art. 10 | 90-day re-auth | A4-F04 | COVERED |
| PSD2 RTS | Art. 11 | Low-value exemption | A4-F07 | COVERED |
| PSD2 RTS | Art. 18 | TRA exemption | A4-F07 | COVERED |
| **EU IPR** | Art. 5a | 10-second SLA | A2-F20, A2-NF02 | COVERED |
| EU IPR | Art. 5a | 24/7/365 availability | A2-F21, OPS-08 | COVERED |
| EU IPR | Art. 5a | EUR 100,000 limit | A2-F22 | COVERED |
| EU IPR | Art. 5b | Sanctions screening | A5-F20-F28, SEC-11 | COVERED |
| EU IPR | Art. 5c | VoP mandatory | A6-F01-F10, SEC-12 | COVERED |
| EU IPR | Art. 5c(4) | VoP liability shift | — | GAP |
| EU IPR | Art. 5d | Price parity | — | GAP (noted in extension but no req ID) |
| **AMLR** | Art. 8-10 | Risk-based approach | — | GAP |
| AMLR | Art. 15-29 | Customer due diligence | — | GAP |
| AMLR | Art. 35-40 | PEP screening | — | GAP |
| AMLR | Art. 42-49 | Beneficial ownership | — | GAP |
| AMLR | Art. 50-53 | SAR filing | PARTIAL (A5 FIU report) | PARTIAL |
| AMLR | Art. 54 | Tipping-off prohibition | — | GAP |
| AMLR | Art. 56 | Record retention (5 years) | — | GAP |
| **FTR** | Art. 4 | Payer/payee information | PARTIAL | PARTIAL |
| FTR | Art. 5 | Verification of payer identity | — | GAP |
| FTR | Art. 11-12 | Detection of missing info | — | GAP |
| FTR | Art. 16 | Record-keeping (5 years) | — | GAP |
| **DORA** | Art. 5-16 | ICT risk management | — | GAP |
| DORA | Art. 17-23 | Incident reporting | — | GAP |
| DORA | Art. 24-27 | Resilience testing | — | GAP |
| DORA | Art. 28-44 | Third-party ICT risk | — | GAP |
| **NIS2** | Art. 21 | Cybersecurity measures | SEC-01 to SEC-12 (partial) | PARTIAL |
| NIS2 | Art. 23 | Incident reporting | — | GAP |
| **SFD** | Art. 3 | Settlement finality | A2-F23 (partial) | PARTIAL |
| **PAD** | Art. 16-18 | Basic account right | — | GAP |
| PAD | Art. 19 | Account termination | — | GAP |
| **CRD** | Art. 11-16 | Recurring payment cancellation | A3-S05 (partial) | PARTIAL |
| **EAA** | Art. 2(2)(b) | Payment service accessibility | — | GAP |
| **AI Act** | Annex III(5) | High-risk AI in finance | — | GAP |
| **eIDAS** | Arts. 3, 28 | QWAC/QSealC for TPP identification | A4-S09 (simulated) | PARTIAL |
| **CRA** | Art. 13 | SBOM obligations | STARTER-F30 (partial) | PARTIAL |

---

## End of Review

**Total gaps identified:** 23 (7 critical, 9 serious, 7 notable)
**Total positive findings:** 6 areas of strong coverage
**Overall assessment:** Requirements are technically excellent and operationally sound. European legislative coverage needs significant strengthening, particularly GDPR (systemically absent), DORA (not mentioned), FTR/Travel Rule (not addressed), and AML completeness (KYC, PEP, SAR workflow missing). The recommended actions are additive (no existing requirements need to be changed) and can be addressed by expanding the cross-cutting requirements section and adding a regulatory framework index.
