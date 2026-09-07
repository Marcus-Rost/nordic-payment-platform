# Proposed Requirement Text: EU Legislation Gaps

**Date:** 2026-04-11
**Purpose:** Concrete, copy-paste-ready requirement text to resolve the 7 critical and 9 serious gaps identified in `requirements_review_eu_legislation.md`
**Integration target:** `payment_processing/requirements/system_requirements.md` (new sections to insert after current Section 2)

---

## New Section: 2.5 Regulatory Framework

*Insert after Section 2.4 (Data Standards) in system_requirements.md*

This section maps all applicable EU/EEA legislation to the system requirements that implement them. It serves as the compliance index for regulatory assessments.

### 2.5.1 Applicable Legislation

| Legislation | Full Citation | Applicability | Primary Sub-systems |
|------------|--------------|---------------|-------------------|
| PSD2 | Directive (EU) 2015/2366 on payment services | Core regulatory basis for payment services, TPP access, SCA | A4, A2, A3 |
| PSD2 RTS | Commission Delegated Regulation (EU) 2018/389 | SCA technical standards, dynamic linking, exemptions | A4 |
| EU IPR | Regulation (EU) 2024/886 on instant credit transfers in euro | Instant payments, VoP, sanctions screening timing | A2, A5, A6 |
| SEPA Regulation | Regulation (EU) 260/2012 | SEPA scheme rules, IBAN-only | A2, A3 |
| SFD | Directive 98/26/EC on settlement finality | Legal finality of settlement | A2 |
| GDPR | Regulation (EU) 2016/679 | Personal data protection | ALL |
| AMLR | Regulation (EU) 2024/1624 (AML Regulation) | AML/CFT obligations | A5, A1 |
| AMLD6 | Directive (EU) 2024/1640 (6th AML Directive) | AML institutional framework | A5 |
| FTR | Regulation (EU) 2023/1113 (recast Funds Transfer Regulation) | Payer/payee information requirements ("Travel Rule") | A2, A3, A4 |
| DORA | Regulation (EU) 2022/2554 | Digital operational resilience | ALL |
| NIS2 | Directive (EU) 2022/2555 | Cybersecurity risk management, incident reporting | ALL |
| eIDAS | Regulation (EU) 910/2014 | Electronic identification, QWAC certificates for TPPs | A4 |
| PAD | Directive 2014/92/EU | Basic payment account rights | A1 |
| EAA | Directive (EU) 2019/882 | Accessibility of payment services | A3, A4, A6 |
| AI Act | Regulation (EU) 2024/1689 | AI systems in financial decisions | A5 |
| CRA | Regulation (EU) 2024/2847 | Software supply chain, SBOM | Cross-cutting |
| Hvitvaskingsloven | Norwegian AML Act (LOV-2018-06-01-23) | Norwegian transposition of AMLD + national additions | A5 |
| Finansforetaksloven | Norwegian Financial Institutions Act | Norwegian PSP licensing framework | ALL |

---

## New Section: 2.6 Data Protection Requirements (GDPR)

*Resolves: GAP C-1 from EU legislation review*

### 2.6.1 Lawful Basis for Processing

| ID | Requirement | Art. 6(1) Ground | Priority |
|----|------------|------------------|----------|
| GDPR-01 | Payment execution processing (A2, A1): personal data processed as necessary for the performance of the payment service contract with the payer | (b) Contractual necessity | Must |
| GDPR-02 | Fraud detection and sanctions screening (A5): personal data processed as necessary to comply with AML/CFT legal obligations | (c) Legal obligation | Must |
| GDPR-03 | VoP responder queries (A6): personal data (account holder name) processed as required by EU IPR Art. 5c | (c) Legal obligation | Must |
| GDPR-04 | PSD2 AISP/PISP access (A4): personal data processed based on explicit consent granted via OAuth2 consent flow | (a) Consent (PSD2 consent is GDPR-valid consent per PSD2 Art. 94) | Must |
| GDPR-05 | Audit logging (cross-cutting): personal data in audit logs (principal, IP, request parameters) processed to comply with AML record-keeping and PCI DSS requirements | (c) Legal obligation | Must |
| GDPR-06 | Each sub-system must document its lawful basis for processing in its README or ADR | Documentation | Must |

### 2.6.2 Data Minimisation and Purpose Limitation

| ID | Requirement | Priority |
|----|------------|----------|
| GDPR-07 | A5 velocity rule state stores (90-day rolling account profiles) must retain only the minimum data needed for scoring: aggregate statistics (mean, stddev, count), not individual historical transactions | Must |
| GDPR-08 | VoP query logs (A6) must not retain the stated name from NO_MATCH queries longer than 30 days. Purpose: audit trail for anti-enumeration detection. After 30 days, retain only: query timestamp, querying PSP ID, IBAN queried, result, but delete stated_name | Must |
| GDPR-09 | PSD2 consent records (A4) must be retained for the duration of the consent plus 5 years (aligned with AMLR Art. 56 retention). After that period, pseudonymise the account holder identity | Must |

### 2.6.3 Data Subject Rights

| ID | Requirement | Priority |
|----|------------|----------|
| GDPR-10 | **Right of access (Art. 15):** Expose API endpoint `GET /api/v1/data-subject/access-request` that returns all personal data held about a data subject across A1 (account, balances, transaction history), A4 (consents, tokens), A5 (fraud alerts, scores associated with their transactions), and A6 (VoP queries involving their IBAN). Response must include purpose of processing, retention period, and recipients | Must |
| GDPR-11 | **Right to rectification (Art. 16):** When an account holder name is corrected in A1, the change must propagate to: A6 VoP name cache (invalidation within 1 minute), A4 consent records, A5 case records. The event store (A1) is append-only: corrections are modeled as new events (NameCorrected), not mutations of historical events | Must |
| GDPR-12 | **Right to erasure (Art. 17):** Limited by Art. 17(3)(b) (legal obligation) and Art. 17(3)(e) (legal claims). Financial transaction records subject to 5-year AMLR retention cannot be erased. After the retention period expires, erasure is achieved via crypto-shredding: delete the per-account encryption key, rendering all events for that account unreadable while preserving the event chain structure | Must |
| GDPR-13 | **Right to object to profiling (Art. 21):** Account holders may object to automated fraud profiling. If objected, A5 must apply a rules-only scoring path (VEL rules + sanctions screening) without ML model inference. Flag `profiling_objection=true` on the account in A1 | Should |
| GDPR-14 | **Right to data portability (Art. 20):** Satisfied by PSD2 AISP (A4-F01) which provides machine-readable account data access. No additional endpoint needed beyond the existing PSD2 API | N/A (covered) |

### 2.6.4 Automated Decision-Making (Art. 22)

| ID | Requirement | Priority |
|----|------------|----------|
| GDPR-15 | When A5 issues an AUTO_BLOCK decision (fraud score >= 0.7), the payer must be informed that the decision was made by automated means | Must |
| GDPR-16 | The payer must be provided a mechanism to contest an AUTO_BLOCK decision and request human review. This is distinct from the A5 case management flow (which is for internal analysts). The payer-facing mechanism: POST `/api/v1/payments/{id}/contest` triggers creation of a CASE_OFFICER review alert with `origin=PAYER_CONTEST` | Must |
| GDPR-17 | On request, the system must provide meaningful information about the logic involved in fraud scoring (Art. 15(1)(h)). This must explain which rule categories contributed to the decision (velocity, anomaly, ML) without revealing specific thresholds or model weights that would enable gaming. Implementation: the `triggered_rules` field in fraud responses provides category-level explanations (e.g., "Unusual transaction frequency detected") | Must |

### 2.6.5 Data Protection Impact Assessment

| ID | Requirement | Priority |
|----|------------|----------|
| GDPR-18 | A DPIA must be conducted for: (a) A5 fraud detection (systematic monitoring, automated decision-making with legal effects, large-scale processing), (b) A6 VoP service (processing of account holder names for identity verification), (c) A1 account ledger (large-scale processing of financial data). The DPIA document is maintained in `docs/security/dpia.md` | Must |

### 2.6.6 Data Breach Notification

| ID | Requirement | Priority |
|----|------------|----------|
| GDPR-19 | The system must support detection and classification of personal data breaches. A breach affecting personal data must be reportable to the supervisory authority within 72 hours (GDPR Art. 33) and to affected data subjects without undue delay if high risk (GDPR Art. 34) | Must |
| GDPR-20 | Breach detection signals: (a) unauthorised access to A1 account data, (b) bulk data extraction via A4 AISP beyond normal TPP patterns, (c) unauthorised access to A5 fraud case management, (d) A6 anti-enumeration alert threshold exceeded. These feed into the DORA incident reporting process (see Section 2.7) | Must |

### 2.6.7 Retention Schedule

| ID | Data Category | Retention Period | Legal Basis | Priority |
|----|--------------|-----------------|-------------|----------|
| GDPR-RET-01 | Transaction records (A1 events) | 5 years from transaction date | AMLR Art. 56 | Must |
| GDPR-RET-02 | Fraud scores and case records (A5) | 5 years from case closure | AMLR Art. 56 | Must |
| GDPR-RET-03 | Sanctions screening decisions (A5) | 5 years from screening date | AMLR Art. 56 | Must |
| GDPR-RET-04 | PSD2 consent records (A4) | Consent duration + 5 years | AMLR Art. 56, PSD2 Art. 94 | Must |
| GDPR-RET-05 | Audit logs (cross-cutting) | 12 months online, 5 years archive | PCI DSS 10.7, AMLR Art. 56 | Must |
| GDPR-RET-06 | VoP query logs (A6) | 30 days (stated_name), 5 years (metadata) | GDPR Art. 5(1)(e), AMLR Art. 56 | Must |
| GDPR-RET-07 | Kafka event store (A1) | Infinite retention with crypto-shredding after 5-year period | GDPR Art. 5(1)(e) reconciled via crypto-shredding | Must |
| GDPR-RET-08 | Account holder personal data (A1) | Until account closure + 5 years | AMLR Art. 56 | Must |

### 2.6.8 Event Sourcing and GDPR Reconciliation

| ID | Requirement | Priority |
|----|------------|----------|
| GDPR-21 | All personal data in Kafka events (A1 ledger.events) must be encrypted with per-account encryption keys stored in a separate key management service | Must |
| GDPR-22 | Erasure after retention period: delete the per-account encryption key. This renders all events for that account unreadable (crypto-shredding) while preserving the event sequence and non-personal aggregate data for system integrity | Must |
| GDPR-23 | PostgreSQL read projections (A1 CQRS) must support physical deletion of personal data after the retention period, since projections are mutable (unlike the event store) | Must |

---

## New Section: 2.7 Digital Operational Resilience (DORA)

*Resolves: GAP C-2 from EU legislation review*

| ID | Requirement | DORA Article | Priority |
|----|------------|-------------|----------|
| DORA-01 | Define and maintain an ICT risk management framework covering: risk identification, protection, detection, response, recovery, and learning. Document in `docs/security/ict-risk-framework.md` | Art. 5-16 | Must |
| DORA-02 | Classify ICT incidents by severity. Major ICT incidents: (a) instant payment path unavailable for > 2 minutes, (b) sanctions screening service unavailable (any duration — causes payment rejections), (c) personal data breach, (d) A1 ledger data integrity failure. Classification criteria: number of affected clients, duration, geographic spread, data loss, criticality of services | Art. 18 | Must |
| DORA-03 | Major ICT incident reporting: initial notification to competent authority within 4 hours of classification, intermediate report within 72 hours, final report within 1 month | Art. 19 | Must |
| DORA-04 | Significant cyber threat reporting: if a significant threat is detected (e.g., targeted attack on sanctions screening), report to competent authority on a voluntary basis | Art. 19 | Should |
| DORA-05 | Digital operational resilience testing programme: regular vulnerability assessments, penetration testing, scenario-based testing (e.g., "what if Kafka is unavailable for 5 minutes during peak"). For significant entities: TLPT at least every 3 years | Art. 24-27 | Must |
| DORA-06 | Third-party ICT risk: for managed Kafka (e.g., Aiven), managed PostgreSQL, and Schema Registry: maintain register of third-party ICT providers with contractual provisions covering: access rights, audit rights, exit strategy, data location, subcontracting chain | Art. 28-30 | Must |
| DORA-07 | Business continuity: document recovery time objectives (RTO) and recovery point objectives (RPO) for each sub-system. Instant payment path: RTO < 15 minutes, RPO = 0 (no data loss). Batch clearing: RTO < 4 hours, RPO < 1 clearing cycle | Art. 11 | Must |
| DORA-08 | The 99.99% availability SLA for instant payments (A2-NF05, OPS-08) is reframed as a DORA operational resilience target, not merely a technical SLA | Art. 11 | Must |

---

## New Section: 2.8 AML/CFT Compliance Framework

*Resolves: GAP C-4 from EU legislation review. Extends existing A5 sanctions and fraud requirements.*

### 2.8.1 Customer Due Diligence Boundary

| ID | Requirement | Priority |
|----|------------|----------|
| AML-01 | Account creation (A1-F10, UC-07) assumes that Customer Due Diligence (CDD) has been completed in an external KYC system. The `account_holder_id` in A1 maps to a verified identity in that system. **No account may be created without a valid reference to a completed CDD record.** This is an architectural boundary, not an implementation gap — CDD is out of scope for this system but must be referenced | Must |
| AML-02 | The account entity in A1 must include: `kyc_status` (VERIFIED, PENDING_REVIEW, ENHANCED_DUE_DILIGENCE, FAILED), `kyc_verified_at` (timestamp), `pep_status` (NOT_PEP, PEP_DOMESTIC, PEP_FOREIGN, PEP_INTERNATIONAL_ORG, RCA — relative/close associate) | Must |
| AML-03 | A5 must receive `pep_status` as an input field in fraud-check-request messages. PEP status is a risk factor: PEP_DOMESTIC or PEP_FOREIGN with unusual transaction patterns adds +0.15 to the fraud risk score | Should |

### 2.8.2 Suspicious Activity Reporting

| ID | Requirement | Priority |
|----|------------|----------|
| AML-04 | When a fraud case officer confirms fraud (CONFIRMED_FRAUD), the system must generate a Suspicious Activity Report (SAR) containing: reporting entity details, subject details (anonymised externally), suspicious activity narrative, transaction details, and date ranges. Format: national FIU submission format (for Norway: EFE/Enheten for finansiell etterretning goAML format) | Must |
| AML-05 | SAR filing must be separate from, and independent of, sanctions reporting. A sanctions hit generates an immediate FIU report (A5-F26). A SAR is generated for non-sanctioned suspicious activity (fraud, money laundering indicators, unusual patterns) | Must |
| AML-06 | **Tipping-off prohibition (AMLR Art. 54):** When a SAR has been filed or a sanctions alert is under investigation, the account holder must NOT be informed of the investigation. The payment rejection reason shown to the payer must be generic (e.g., "Payment could not be processed") and must NOT reference fraud, sanctions, or AML. A5 case management alerts (A5-F07) must not be visible via any customer-facing API | Must |

### 2.8.3 Funds Transfer Regulation (Travel Rule)

| ID | Requirement | Priority |
|----|------------|----------|
| FTR-01 | Every PaymentInstruction Kafka message must include the following payer information (FTR Art. 4): payer_full_name, payer_account_number (IBAN), payer_address OR payer_national_id OR payer_customer_id, payer_psp_bic | Must |
| FTR-02 | Every PaymentInstruction must include payee information (FTR Art. 4): payee_full_name, payee_account_number (IBAN) | Must |
| FTR-03 | A2 must validate FTR completeness on every received payment instruction before clearing. Missing payer/payee information → reject with reason FTR_INCOMPLETE | Must |
| FTR-04 | FTR payer/payee information must be retained for 5 years from the date of the transfer (FTR Art. 16) | Must |
| FTR-05 | For transfers > EUR 1,000 (or linked transactions exceeding EUR 1,000): payer PSP must verify payer identity against CDD records before execution | Must |

### 2.8.4 Record Retention

| ID | Requirement | Priority |
|----|------------|----------|
| AML-06 | All transaction records, CDD references, screening decisions, SARs, and FTR data must be retained for a minimum of 5 years. This aligns with GDPR-RET-01 through GDPR-RET-08 in Section 2.6.7. After 5 years, data subject to GDPR erasure requests must be deleted or crypto-shredded | Must |

### 2.8.5 Risk-Based Approach Documentation

| ID | Requirement | Priority |
|----|------------|----------|
| AML-07 | The velocity rules (VEL-01 through VEL-10), anomaly detection parameters, and ML model features in A5 must be grounded in a documented ML/TF risk assessment. The risk assessment document (`docs/security/aml-risk-assessment.md`) must: identify the ML/TF risks relevant to the system's payment types, justify each velocity rule threshold as proportionate to an identified risk, and be reviewed annually | Must |

---

## New Section: 2.9 Norwegian Regulatory Context

*Resolves: GAP C-5 from EU legislation review*

| ID | Requirement | Priority |
|----|------------|----------|
| NOR-01 | The system is designed to operate under Norwegian financial regulation. Norwegian IBAN format: NO + 2 check digits + 11-digit account number (BBAN). Validation: MOD-11 check digit algorithm per Norwegian Banking Association standard | Must |
| NOR-02 | AML obligations follow Hvitvaskingsloven (LOV-2018-06-01-23), Norway's transposition of AMLD. SAR reports must be filed with Enheten for finansiell etterretning (EFE), Norway's Financial Intelligence Unit, via the goAML platform | Must |
| NOR-03 | PSD2 obligations in Norway are implemented via Finansforetaksloven and Finanstilsynet circulars. TPP registration and supervision follow Finanstilsynet's PSD2 regulatory framework | Must |
| NOR-04 | NICS (Norwegian Interbank Clearing System) is operated by BITS AS and supervised by Norges Bank. The system's batch clearing model mirrors NICS operations. In a production deployment, the system would need to comply with Norges Bank's oversight requirements for payment systems | Documentation |

---

## New Section: 2.10 Consumer Protection

*Resolves: GAPs S-3, S-7, S-8 from EU legislation review*

| ID | Requirement | Legislation | Priority |
|----|------------|-------------|----------|
| CONS-01 | **Advance notification for auto-approved mandates (PSD2 Art. 78):** When an AvtaleGiro mandate auto-approves a payment request (UC-06), the consumer must be notified at least 4 weeks before the debit date (configurable, shorter period if agreed with consumer). Notification channel: webhook or push notification to the consumer's banking app | Must |
| CONS-02 | **Account freeze review (PAD Art. 16-18):** When an account is frozen (A1-F14, UC-07), the freeze must include a review deadline (default: 30 days). If the freeze is not lifted or escalated to legal proceedings within the review period, the system must generate a review alert for the compliance officer | Must |
| CONS-03 | **Account closure notice (PAD Art. 19):** Account closure must provide at least 2 months' notice to the account holder. Immediate closure is permitted only for: (a) confirmed fraud, (b) sanctions match, (c) zero balance with account holder's explicit request | Must |
| CONS-04 | **Accessibility (EAA 2019/882):** All consumer-facing interfaces — consent approval, SCA challenge, VoP name correction, R2P accept/decline — must comply with WCAG 2.1 Level AA | Must |
| CONS-05 | **Refund right analysis for mandates:** Document (in an ADR) the legal position on PSD2 Art. 76 refund rights for auto-approved AvtaleGiro mandates. The push-payment model means the consumer's bank initiates the transfer, which may fall under the Art. 76 direct debit refund right. If so, implement a refund API: `POST /api/v1/payments/{id}/refund` with an 8-week window | Should |

---

## New Section: 2.11 Settlement Finality

*Resolves: GAP S-1 from EU legislation review*

| ID | Requirement | Priority |
|----|------------|----------|
| SFD-01 | Each settled payment must carry a distinct `settlement_finality_timestamp` representing the moment when the payment obligation becomes irrevocable and enforceable against third parties under the Settlement Finality Directive (98/26/EC). This timestamp is distinct from the processing completion timestamp | Must |
| SFD-02 | A2 must publish a `SettlementFinalityEvent` to `payment.settlement-events` after the settlement is final. This event is the legally significant record. A1 must store this timestamp on the corresponding ledger event | Must |
| SFD-03 | For instant payments: the moment of finality is defined as the moment when A1 confirms the balance reservation (debit becomes irrevocable). For batch payments: the moment of finality is defined as the completion of the netting cycle and publication of the settlement report | Must |

---

## New Section: 2.12 Incident Reporting (NIS2 + DORA)

*Resolves: GAP S-2 from EU legislation review*

| ID | Requirement | Priority |
|----|------------|----------|
| INCIDENT-01 | The system must classify cybersecurity and ICT incidents per both DORA (Art. 18) and NIS2 (Art. 23) classification criteria. Where both apply (financial entity under DORA that is also an essential entity under NIS2), the stricter timeline governs | Must |
| INCIDENT-02 | Incident reporting timelines: (a) DORA major ICT incident: initial to competent authority within 4 hours, intermediate within 72 hours, final within 1 month. (b) NIS2 significant incident: early warning within 24 hours to CSIRT, incident notification within 72 hours, final report within 1 month | Must |
| INCIDENT-03 | Automated incident detection signals: instant payment path down (Prometheus `instant.availability` < 100%), sanctions screening unavailable (any duration), A1 ledger integrity check failure, unusual data access patterns (potential breach), SLA breach rate > 1% over 5-minute window | Must |

---

## Updates to Existing Requirements

### Update: SEC-12 (VoP Mandatory)

**Current text:**
> "Every credit transfer must pass VoP check before execution (mandatory under EU IPR)"

**Proposed replacement:**
> "Every credit transfer must initiate a VoP check before execution (mandatory under EU IPR Art. 5c). If the VoP check times out or the beneficiary PSP is unavailable, the payment may proceed with a VOP_TIMEOUT flag and must be flagged for post-settlement review (A6-F10). **Note:** failure to perform VoP shifts liability to the payer's PSP under EU IPR Art. 5c(4) for any resulting misdirected payment."

### Update: A1-F11 (Balance Reservation)

**Current text:**
> "Three-state: AVAILABLE -> RESERVED -> SETTLED (or RELEASED)"

**Proposed replacement:**
> "Three-state: AVAILABLE -> RESERVED -> CONFIRMED (or RELEASED). Aligns with Avro schema enum: BALANCE_RESERVED, BALANCE_CONFIRMED, BALANCE_RELEASED."

### Update: A1-F11 (elevate reservation timeout)

Add new requirement:
> "A1-F11a | Balance reservation timeout: reservations not confirmed or released within a configurable period (default: 30 seconds) are automatically released. This is a safety mechanism to prevent permanent fund locking on clearing engine failure | **Must**"

*(Previously LED-F14 at Should priority — elevated to Must as it is a correctness requirement)*

---

## New: AI Act Assessment (ADR)

*Resolves: GAP S-5 from EU legislation review*

| ID | Requirement | Priority |
|----|------------|----------|
| AIACT-01 | Document an EU AI Act assessment for the A5 fraud scoring ML model in `docs/security/ai-act-assessment.md`. The assessment must evaluate: (a) whether the fraud detection exception in Annex III(5)(b) applies to the AUTO_BLOCK mechanism, (b) regardless of classification, adopt AI Act-aligned practices: training data documentation, bias monitoring across protected characteristics (nationality, name origin), human oversight of automated blocking decisions | Must |
| AIACT-02 | A5 must monitor ML model predictions for demographic bias: compare false positive rates across name-origin categories (Nordic, non-Nordic) and transaction corridors (domestic vs. cross-border). Alert if disparity exceeds configurable threshold (default: 20% relative difference) | Should |

---

## New: eIDAS Clarification (ADR)

*Resolves: GAP S-4 from EU legislation review*

| ID | Requirement | Priority |
|----|------------|----------|
| EIDAS-01 | Document the eIDAS QWAC simulation boundary in `docs/architecture-decisions/adr-eidas.md`: "In the portfolio project, eIDAS QWAC is simulated via self-signed X.509 client certificates with PSD2-structured organization identifier fields (ETSI TS 119 495 format: PSDXX-YYYYYY-ZZZZZZZZ where XX=national authority, YYYYYY=authorization number). In production, certificates would be obtained from a QTSP listed on the EU Trusted List." | Must |
| EIDAS-02 | Note eIDAS2/EUDIW awareness: "The EU Digital Identity Wallet (eIDAS2, adopted 2024) will provide an alternative strong authentication mechanism. The SCA implementation in A4 should be designed to support wallet-based authentication as an additional possession factor alongside TOTP." | Documentation |

---

## Summary of Proposed Changes

| Section | New Requirements | Effort |
|---------|-----------------|--------|
| 2.5 Regulatory Framework | 1 table (no req IDs) | Low |
| 2.6 GDPR (8 subsections) | GDPR-01 to GDPR-23, RET-01 to RET-08 | Medium |
| 2.7 DORA | DORA-01 to DORA-08 | Low |
| 2.8 AML/CFT (5 subsections) | AML-01 to AML-07, FTR-01 to FTR-05 | Medium |
| 2.9 Norwegian Context | NOR-01 to NOR-04 | Low |
| 2.10 Consumer Protection | CONS-01 to CONS-05 | Low |
| 2.11 Settlement Finality | SFD-01 to SFD-03 | Low |
| 2.12 Incident Reporting | INCIDENT-01 to INCIDENT-03 | Low |
| Updates to SEC-12, A1-F11 | 2 updates | Trivial |
| AI Act ADR | AIACT-01 to AIACT-02 | Low |
| eIDAS ADR | EIDAS-01 to EIDAS-02 | Trivial |

**Total new requirements:** ~58
**Total effort estimate:** Medium — primarily documentation, no changes to existing functional requirements
