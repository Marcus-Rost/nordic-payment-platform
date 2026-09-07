# Certification Readiness Review: Nordic Payment Processing Infrastructure

**Reviewer:** Claude Opus 4.6 (1M context)
**Date:** 2026-04-11
**Scope:** Assessment of four certification/compliance pathways: (1) ISO 20022 CBPR+, (2) EPC SCT Inst Rulebook, (3) PCI DSS, (4) EPC VoP Scheme. Cross-referenced against full requirements corpus (system_requirements.md, 7 sub-project requirements, 10 use cases, 6 extensions, 4 prior reviews).
**Methodology:** Each certification domain assessed against its standard's specific conformance criteria. Findings graded as BLOCKER (prevents certification), SIGNIFICANT (must be resolved before certification attempt), MODERATE (auditor flag, resolvable during certification), or MINOR (best-practice recommendation). Prior reviews incorporated by reference where they cover the same ground.

---

## Executive Summary

The system's architecture is sound and the domain knowledge is exceptional. All four certification pathways are architecturally viable -- no fundamental redesign is needed. However, the specification depth across all four domains is insufficient for certification. The pattern is consistent: the system correctly identifies the right standards, messages, and patterns, but stops short of the operational detail that certification bodies test against.

### Certification Readiness Scorecard

| Certification Pathway | Architecture | Specification Depth | Certification Ready? | Hard Blockers |
|----------------------|-------------|--------------------|--------------------|---------------|
| ISO 20022 CBPR+ | Sound | Shallow | **No** | 7 |
| EPC SCT Inst Rulebook | Sound | Moderate | **No** | 3 |
| PCI DSS v4.0 | Partial | Shallow | **No** | 4 |
| EPC VoP Scheme | Sound | Moderate | **No** | 2 |

**Key insight:** The gap between "compatible" and "compliant" is a specification gap, not an architecture gap. The required additions are requirements-level work (adding field specs, version numbers, code list references, policy documents) rather than system redesign.

### Cross-Cutting Obstacles

Three obstacles affect multiple certification pathways simultaneously:

1. **No key management architecture** -- blocks PCI DSS Req 3, affects CBPR+ (digital signatures on BAH), and undermines audit log integrity across all pathways.
2. **ISO 20022 code list and version ambiguity** -- blocks CBPR+ and SCT Inst simultaneously; affects VoP (outcome vocabulary mapping).
3. **Absent incident response and security policy framework** -- blocks PCI DSS Req 12, affects DORA compliance (which underpins all EU financial certifications).

---

## Table of Contents

1. [ISO 20022 CBPR+ Message Compliance](#1-iso-20022-cbpr-message-compliance)
2. [EPC SCT Inst Rulebook Compliance](#2-epc-sct-inst-rulebook-compliance)
3. [PCI DSS v4.0 Alignment](#3-pci-dss-v40-alignment)
4. [EPC VoP Scheme Compliance](#4-epc-vop-scheme-compliance)
5. [Cross-Certification Dependencies](#5-cross-certification-dependencies)
6. [Consolidated Findings Table](#6-consolidated-findings-table)
7. [Recommended Certification Sequence](#7-recommended-certification-sequence)
8. [Appendix A: Existing Review Cross-References](#appendix-a-existing-review-cross-references)
9. [Appendix B: Standards Version Matrix](#appendix-b-standards-version-matrix)

---

## 1. ISO 20022 CBPR+ Message Compliance

**Claim under review:** "Message formats tested against SWIFT CBPR+ test cases" (pacs.008, pacs.002, camt.053).

**Verdict:** The claim is **not substantiated**. The system uses ISO 20022 message type names correctly and the architecture is CBPR+-compatible, but the specification depth is far below what CBPR+ conformance testing requires. Seven criteria are hard blockers.

The existing review at `reviews/requirements_review_iso20022_certification.md` already identified 31 findings (9 critical) with the verdict: "ISO 20022 compatible but not ISO 20022 compliant." This review confirms those findings and adds one critical gap the prior review missed.

### 1.1 Finding CBPR-01: No Message Version Pinning [BLOCKER]

**Severity:** BLOCKER
**Prior review:** Finding ISO-01 (CRITICAL)
**Affected:** system_requirements.md DATA-04, all CLR-* requirements, A2-F02, A2-F05

Every ISO 20022 reference in the corpus uses short-form names (e.g., "pacs.008") without the four-part identifier CBPR+ requires (e.g., `pacs.008.001.10`). Different versions have different mandatory fields, namespace URIs, and XSD schemas. The JAXB-generated `payment-iso20022` shared library (system_requirements.md Section 9.2) cannot be built without pinned versions.

**What must be specified:**

| Message | Recommended Version | Rationale |
|---------|-------------------|-----------|
| pacs.008 | pacs.008.001.10 | Current CBPR+ and SEPA 2025+ version |
| pacs.002 | pacs.002.001.12 | Matches pacs.008.001.10 |
| pacs.004 | pacs.004.001.11 | Return message aligned to pacs.008 v10 |
| pacs.028 | pacs.028.001.05 | Status request aligned to CBPR+ |
| pain.001 | pain.001.001.11 | Customer credit transfer initiation |
| pain.013 | pain.013.001.09 | Request-to-pay (R2P) |
| pain.014 | pain.014.001.09 | R2P status |
| camt.053 | camt.053.001.10 | Account statement |
| camt.056 | camt.056.001.10 | Payment cancellation request (currently absent) |
| head.001 | head.001.001.03 | Business Application Header (currently absent) |

### 1.2 Finding CBPR-02: No Business Application Header (head.001) [BLOCKER]

**Severity:** BLOCKER
**Prior review:** NOT IDENTIFIED (new finding)
**Affected:** All ISO 20022 message generation and consumption

This is the most fundamental CBPR+ gap and was missed by the prior ISO 20022 review. CBPR+ requires every message to be wrapped in a Business Application Header (head.001.001.03) containing:

- `Fr` (from) -- sending institution (BIC)
- `To` (to) -- receiving institution (BIC)
- `BizMsgIdr` -- business message identifier (unique per message)
- `MsgDefIdr` -- message definition identifier (e.g., "pacs.008.001.10")
- `CreDt` -- creation timestamp
- Optional: digital signature (for non-repudiation)

The XML examples in UC-01 and UC-08 show raw message bodies without any BAH envelope. The `payment-iso20022` JAXB library does not list head.001 among its generated classes. No requirement mentions the BAH.

**Impact:** Without the BAH, no outbound message is CBPR+ conformant, and inbound CBPR+ messages cannot be parsed correctly (the BAH is the routing/identification layer). This is analogous to sending HTTP bodies without HTTP headers.

**Recommendation:** Add head.001.001.03 to the `payment-iso20022` library and add a requirement specifying BAH generation for all outbound messages and BAH parsing/validation for all inbound messages.

### 1.3 Finding CBPR-03: No UETR (Unique End-to-End Transaction Reference) [BLOCKER]

**Severity:** BLOCKER
**Prior review:** Finding ISO-03 (CRITICAL)
**Affected:** pacs.008, pacs.002, pacs.004, pacs.028

UETR (UUID v4 format) has been mandatory in CBPR+ since November 2022. It is not mentioned anywhere in the requirements corpus. The system uses `EndToEndId` and internal `transaction_id` (UUID), but neither is a UETR. Without UETR:

- pacs.028 status requests cannot function (UETR is the lookup key)
- Payment tracking across institutions is impossible
- Messages would be rejected by SWIFT-connected counterparties

### 1.4 Finding CBPR-04: No Structured Address Specification [BLOCKER]

**Severity:** BLOCKER
**Prior review:** Finding ISO-05 (CRITICAL)
**Affected:** Party identification in all pacs.* messages

CBPR+ mandated structured addresses from November 2025 (already in effect). Required fields: `StreetName`, `BuildingNumber`, `PostCode`, `TownName`, `Country` (ISO 3166-1 alpha-2). The pacs.008 example in UC-01 shows only `<Dbtr><Nm>Kari Hansen</Nm></Dbtr>` with no address at all. The `payment-domain-model` shared library does not include address fields in its party model.

### 1.5 Finding CBPR-05: No Character Set Validation [BLOCKER]

**Severity:** BLOCKER
**Prior review:** Finding ISO-08 (HIGH)
**Affected:** All outbound ISO 20022 messages

CBPR+ restricts messages to a subset of Latin characters (aligned with the SWIFT FIN character set). No requirement specifies character set validation for outbound messages. This is operationally critical for a Nordic system where names commonly contain o-stroke, a-ring, and other characters that must be transliterated for CBPR+ messages. The VoP sub-system (A6) handles diacritics for name matching (VOP-N02), but no equivalent handling exists for message generation.

### 1.6 Finding CBPR-06: XSD-Only Validation (No Business Rule Layer) [BLOCKER]

**Severity:** BLOCKER
**Prior review:** Finding ISO-02 (CRITICAL)
**Affected:** CLR-B02, A2-F02

The clearing engine specifies "validate against ISO 20022 pacs.008 schema (JAXB XSD validation)" but not business rule validation. CBPR+ requires validation of:

- Mandatory fields beyond XSD (UETR, ChargeBearer=SLEV, ServiceLevel=SEPA/INST)
- IBAN structure validation (ISO 13616 mod-97 check digit)
- Code list membership (ExternalPaymentTransactionStatus1Code, etc.)
- Cross-field consistency rules
- Amount currency alignment

XSD-only validation is the difference between syntactically valid XML and semantically valid payment messages.

### 1.7 Finding CBPR-07: No External Code List References [BLOCKER]

**Severity:** BLOCKER
**Prior review:** Finding ISO-04 (CRITICAL)
**Affected:** All ISO 20022 message generation and interpretation

ISO 20022 external code lists are used informally (e.g., AM04, ACSC in UC-08 examples) but never formally referenced. Seven code lists are used without formal tracing:

| Code List | Used Values | Where Used |
|-----------|-------------|------------|
| ExternalPaymentTransactionStatus1Code | ACSC, RJCT | UC-08 pacs.002 |
| ExternalStatusReason1Code | AM04, AM06 | UC-08, UC-01 |
| ExternalServiceLevel1Code | (not specified) | Should be SEPA/INST |
| ExternalCategoryPurpose1Code | (not specified) | pacs.008 |
| ExternalPurpose1Code | (not specified) | pacs.008 |
| ExternalChargeType1Code | (not specified) | Should be SLEV |
| ExternalLocalInstrument1Code | (not specified) | Should be INST for instant |

Internal status values (SETTLED, REJECTED, FRAUD_SUSPECTED) have no formal mapping to ISO 20022 codes.

### 1.8 Finding CBPR-08: No SWIFT Test Case Evidence [SIGNIFICANT]

**Severity:** SIGNIFICANT
**Prior review:** Finding ISO-15 (LOW -- we escalate)

No document references SWIFT MyStandards, SWIFT readiness testing, CBPR+ conformance test suites, or XMLdation (the standard ISO 20022 validation tool). The testing stack lists JUnit, Testcontainers, Spring Batch Test -- no ISO 20022 conformance tools. Acceptance criteria test internal behavior only (e.g., "valid camt.053 XML generated" tests generation, not conformance).

**Recommendation:** Add a requirement for integration tests that validate generated messages against XMLdation or an equivalent ISO 20022 validation tool. Reference SWIFT's CBPR+ usage guidelines as a normative source.

### 1.9 Finding CBPR-09: No LEI Handling [MODERATE]

**Severity:** MODERATE
**Prior review:** Noted in passing (Finding ISO-01)

Legal Entity Identifier (LEI) is not mentioned in any document. LEI is currently encouraged rather than mandatory in most CBPR+ flows, but for pacs.008 messages involving corporate entities, its absence signals incomplete institutional identification. Becoming mandatory for certain flows under MiFIR/EMIR reporting.

### 1.10 Finding CBPR-10: No ISO 20022 Schema Version Migration Strategy [MODERATE]

**Severity:** MODERATE
**Prior review:** Finding ISO-14 (MEDIUM)

Kafka schema evolution is specified (Avro with Schema Registry, DATA-06), but ISO 20022 XML schema evolution is not. SWIFT periodically updates CBPR+ message versions (typically with 12+ months notice). Without a versioning strategy, each update is a breaking change across all sub-systems.

### CBPR+ Summary

| # | Finding | Severity | Hard Blocker? |
|---|---------|----------|--------------|
| CBPR-01 | No message version pinning | BLOCKER | Yes |
| CBPR-02 | No Business Application Header (head.001) | BLOCKER | Yes |
| CBPR-03 | No UETR | BLOCKER | Yes |
| CBPR-04 | No structured addresses | BLOCKER | Yes |
| CBPR-05 | No character set validation | BLOCKER | Yes |
| CBPR-06 | No business rule validation layer | BLOCKER | Yes |
| CBPR-07 | No formal code list references | BLOCKER | Yes |
| CBPR-08 | No SWIFT test case evidence | SIGNIFICANT | -- |
| CBPR-09 | No LEI handling | MODERATE | -- |
| CBPR-10 | No schema version migration strategy | MODERATE | -- |

**Hard blockers: 7.** The claim "message formats tested against SWIFT CBPR+ test cases" cannot be made until all seven are resolved.

---

## 2. EPC SCT Inst Rulebook Compliance

**Claim under review:** "Our instant payment path implements the SCT Inst scheme. We should update to the 2025 timing (5-sec execution target)."

**Verdict:** The instant payment path is a **strong implementation of the SCT Inst happy path** (payment submission through positive confirmation). The core timing, amount limits, 24/7 availability, and positive confirmation are well-specified. However, three areas present hard blockers: the complete absence of the recall mechanism (camt.056), the use of non-standard reject reason codes, and the undefined settlement finality moment.

### 2.1 Finding SCT-01: No 5-Second Execution Target [SIGNIFICANT]

**Severity:** SIGNIFICANT
**Affected:** A2-F20, A2-NF02, IP-F01, IP-NF01, CLR-I02

The system consistently specifies a 10-second end-to-end SLA, which matches the current EPC SCT Inst rulebook maximum. However:

- No requirement references a 5-second target or any planned SLA reduction.
- No configuration mechanism exists for adjusting the SLA downward.
- The 10-second hard timeout in the `CompletableFuture.orTimeout()` implementation pattern (CLR engine requirements line 127) is not specified as configurable.
- The monitoring targets in UC-08 (p95 < 5s, p50 < 2s) demonstrate awareness of tighter targets but these are monitoring thresholds, not enforcement thresholds.

**The EU Instant Payment Regulation (2024/886) does not itself mandate 5 seconds** -- it mandates availability and defers execution time to the EPC scheme rules. The EPC has been working toward reducing the maximum execution time but has not yet published a binding 5-second target. The system should nonetheless prepare:

**Recommendation:** Make the SLA timeout configurable (default: 10s) with a documented migration path to 5s. Add a requirement noting that the time budget must be re-balanced when the SLA tightens (the current 8.75s network buffer in UC-08's budget becomes ~3.75s under a 5-second ceiling, which compresses the fraud/sanctions check budget significantly).

### 2.2 Finding SCT-02: Recall Mechanism Absent (camt.056) [BLOCKER]

**Severity:** BLOCKER
**Affected:** SCT Inst Rulebook Section 4.3 (Recall procedures)

camt.056 (FIToFIPaymentCancellationRequest) is not specified as a requirement anywhere. It is mentioned only once in the clearing engine gap analysis as something "others have" (gap_analysis.md line 87). The SCT Inst rulebook defines a specific recall procedure:

- Originator PSP sends camt.056 to request recall of a completed instant payment
- Beneficiary PSP has 10 business days to respond (for technical/operational reasons) or must seek beneficiary consent
- Response via camt.029 (ResolutionOfInvestigation) -- also absent from requirements
- Customer-initiated recall request is a distinct flow

Additionally:
- pacs.004 (PaymentReturn) is specified only as "Should" priority (CLR-I12, A2-F30)
- pacs.028 (PaymentStatusRequest) is specified only as "Should" priority (CLR-I11, A2-F29)

For full SCT Inst compliance, all R-transactions must be Must priority:

| Message | Purpose | Current Priority | Required Priority |
|---------|---------|-----------------|-------------------|
| pacs.004 | PaymentReturn (beneficiary-initiated) | Should | Must |
| pacs.028 | PaymentStatusRequest | Should | Must |
| camt.056 | PaymentCancellationRequest (recall) | ABSENT | Must |
| camt.029 | ResolutionOfInvestigation (recall response) | ABSENT | Must |

### 2.3 Finding SCT-03: Non-Standard Reject Reason Codes [BLOCKER]

**Severity:** BLOCKER
**Affected:** CLR-I09, CLR-I10, UC-08 alternative flows, A2-F25
**Prior review:** Finding ISO-04 (code lists) covers this partially

The system uses some correct ISO 20022 reason codes (AM04 for insufficient funds, AM06 for sanctions unavailability) but also uses non-standard codes. The SCT Inst rulebook mandates the ISO 20022 ExternalStatusReason1Code code set:

| System's Code | Should Be | ISO 20022 Code |
|--------------|-----------|----------------|
| AM04 | Correct | AM04 (InsufficientFunds) |
| AM06 | Correct | AM06 (TooLowAmount -- actually used for "amount below minimum"; sanctions unavailability should use a different code) |
| TIMEOUT | Non-standard | AB05 (TimeoutCreditorAgent) or AB06 (TimeoutInstructedAgent) |
| FRAUD_SUSPECTED | Non-standard | FRAD (FraudulentOrigin) or use pacs.002 with RJCT + AM09 |
| INSUFFICIENT_FUNDS | Non-standard (duplicate of AM04) | AM04 |
| AMOUNT_EXCEEDED | Non-standard | AM02 (NotAllowedAmount) |
| BENEFICIARY_ACCOUNT_UNAVAILABLE | Non-standard | AC06 (AccountBlockedMissingInformation) or AC04 (ClosedAccountNumber) |

**Impact:** An SCT Inst counterparty receiving non-standard reason codes would be unable to process the rejection correctly. Automated payment routing systems depend on standardized reason codes.

**Recommendation:** Add a formal internal-to-ISO code mapping requirement (suggest CLR-B16 or equivalent). All externally-visible reason codes must be from ExternalStatusReason1Code. Internal codes can remain for operational use but must map to standard codes at the message boundary.

### 2.4 Finding SCT-04: Settlement Finality Moment Undefined [BLOCKER]

**Severity:** BLOCKER
**Affected:** CLR-I13, IP-F04, A2-F23
**Prior review:** EU legislation review GAP S-1 (SERIOUS)

The concept of immediate, irrevocable settlement is stated (IP-F04: "Settlement is immediate, irrevocable, and final"; A2-F23: same). However:

- CLR-I13 (Settlement finality event) is only "Should" priority -- must be "Must"
- The legal moment of finality is undefined: is it at debit, at credit, at confirmation, at the SETTLEMENT_FINAL event?
- The Settlement Finality Directive (98/26/EC) requires that the transfer order is legally enforceable from the "moment of entry into the system," even in insolvency
- No requirement defines what constitutes "entry into the system" for the purpose of finality

**Recommendation:** Elevate CLR-I13 to Must. Define the legal finality moment (recommended: the instant the debit is confirmed in A1, before the pacs.002 positive confirmation is sent). Add a requirement referencing the Settlement Finality Directive.

### 2.5 Finding SCT-05: Time Budget Not Traceable to Rulebook [MINOR]

**Severity:** MINOR
**Affected:** UC-08 time budget, instant_payments.md time budget

Two time budgets exist: UC-08 provides an internal-system view (1,250ms processing + 8,750ms buffer); the extension document provides a three-party view (originator 1.0s + CSM 2.0s + beneficiary 1.0s + confirmation 1.0s + buffer 5.0s). Both are reasonable engineering estimates but are not mapped to SCT Inst rulebook section numbers. The two documents also have slightly inconsistent VoP allocations (UC-08: 300ms; extension: 300ms within a 1.0s originator budget -- consistent in value but different in framing).

### 2.6 Finding SCT-06: No CSM Interface Specification [MODERATE]

**Severity:** MODERATE
**Affected:** Clearing engine architecture

The system models itself as the CSM (clearing and settlement mechanism) rather than as a PSP connecting to an external CSM. TIPS and RT1 are mentioned in commentary (UC-08 header, gap analysis) but not in requirements. For a portfolio project this is acceptable scope-bounding. For production SCT Inst compliance, the system would need TIPS DCA integration or RT1 connectivity. The gap analysis acknowledges this ("Multi-CSM routing... Low priority").

### 2.7 Positive Findings (SCT Inst)

Several SCT Inst requirements are well-covered:

| Requirement | Status | Evidence |
|-------------|--------|----------|
| 10-second maximum execution time | COVERED | A2-F20, A2-NF02, IP-F01, IP-NF01, CLR-I02 |
| EUR 100,000 maximum amount (configurable) | COVERED | A2-F22, CLR-I04, IP-F03 |
| 24/7/365 availability | COVERED | A2-F21, CLR-I03, IP-F02 (99.99% target exceeds scheme 99.5%) |
| Positive confirmation (pacs.002 ACSC) | COVERED | A2-F24, CLR-I08, IP-F06, with XML example |
| Dedicated instant path (no batch interference) | COVERED | A2-F26, separate Kafka topics, separate consumer groups |
| Balance reservation before settlement | COVERED | A2-F27, A2-F28, A1-F11 |
| Reject on timeout (no limbo state) | COVERED | A2-F25, CLR-I10 |
| Sanctions screening mandatory (no skip) | COVERED | A5-F28, A2-S05 |

### SCT Inst Summary

| # | Finding | Severity | Hard Blocker? |
|---|---------|----------|--------------|
| SCT-01 | No 5-second execution target | SIGNIFICANT | -- |
| SCT-02 | Recall mechanism absent (camt.056, camt.029) | BLOCKER | Yes |
| SCT-03 | Non-standard reject reason codes | BLOCKER | Yes |
| SCT-04 | Settlement finality moment undefined | BLOCKER | Yes |
| SCT-05 | Time budget not traceable to rulebook | MINOR | -- |
| SCT-06 | No CSM interface specification | MODERATE | -- |

**Hard blockers: 3.** Core happy path is solid; the gaps are in exception handling (recall, reason codes) and legal precision (finality).

---

## 3. PCI DSS v4.0 Alignment

**Claim under review:** "Our audit logging, encryption at rest, access controls are PCI DSS-aligned (already documented in Starter #4 gap analysis)."

**Verdict:** The claim is **overstated**. The Starter #4 gap analysis covers only PCI DSS Requirement 10 (logging) and does so honestly and well. But it does not assess Requirements 1-9, 11, or 12. More fundamentally, **PCI DSS may not be the applicable standard** -- the system processes IBAN-based credit transfers, not card payments. It does not store, process, or transmit cardholder data (PAN, CVV, track data). GDPR and AML directives are more directly relevant. However, PCI DSS practices remain valuable as a security baseline, so the assessment below evaluates alignment rather than formal applicability.

### 3.1 Scope Determination: PCI DSS Applicability [CRITICAL CONTEXT]

Before assessing alignment, the requirements must address scope:

- **Does the system handle cardholder data?** A search for "cardholder," "PAN," "CVV," "credit card," and "debit card" across the entire corpus returns zero results. The system handles IBANs and bank account numbers via SEPA/ISO 20022 credit transfers.
- **Is PCI DSS formally in scope?** Almost certainly not. The system processes account-to-account transfers, not card transactions.
- **Are PCI DSS practices still relevant?** Yes, as a security maturity framework. Many PCI DSS controls overlap with DORA (Digital Operational Resilience Act, mandatory for EU financial entities since January 2025) and with general information security best practices.

**Recommendation:** Add an explicit scope statement to the security requirements: "This system processes IBAN-based credit transfers and does not handle cardholder data. PCI DSS is not formally in scope. Security controls are aligned with [DORA / ISO 27001 / specific standard] as the primary framework."

### 3.2 Requirement-by-Requirement Assessment

#### Requirement 1 -- Network Security Controls: NOT ADDRESSED [BLOCKER]

No network security architecture is specified. No firewall rules, network segmentation, service mesh, or network policies. The prior requirements review (payment_processing_requirements_review.md) flags this: "Container and Deployment Security Not Specified" (HIGH-01) and rates "Service mesh / zero-trust networking" at 4/10.

For a payment system, minimum expectations: network policies restricting inter-service communication to defined paths, Kafka broker access restricted to specific service identities, database access restricted to owning service only.

#### Requirement 2 -- Secure Configurations: PARTIAL

**Present:** SEC-05 (no secrets in source code), Starter Gradle plugin (STARTER-F51-F53, minimum Java version, dependency banning), SEC-06 (dependency scanning, SBOM).
**Absent:** No hardening standards for PostgreSQL, Kafka, or application servers. No default credential prohibition. No unnecessary service/port closure specification.

#### Requirement 3 -- Protect Stored Data: PARTIAL [BLOCKER -- Key Management]

**Present:** SEC-03 mandates encryption at rest for sensitive data, referencing PCI DSS. A5-S04 and A3-S06 reiterate for their specific data.

**Absent and critical:** No key management architecture. The prior review flags this as CRITICAL-01 (CVSS 9.8):
- No key rotation strategy
- No key hierarchy (root keys vs. signing keys vs. encryption keys)
- No HSM/KMS integration (PCI DSS 3.5 requires secure cryptographic device)
- No key compromise response procedure
- No specification of encryption algorithms, key lengths, or cipher modes
- "Sensitive data" is undefined -- no classification of which fields require encryption

This is the single most impactful security gap across the entire system. Without key management, SEC-03 is an aspiration, not a control.

#### Requirement 4 -- Protect Data in Transit: PARTIAL

**Present:** SEC-01 (Kafka SASL/SSL), A4-S09 (mTLS for TPP), A4-S05 (JWE for sensitive API fields), STARTER-F01 (HSTS).
**Absent:** No minimum TLS version (PCI DSS 4.0 requires TLS 1.2+), no cipher suite restrictions, no message-level integrity for Kafka (transport encryption does not protect against compromised broker), no Schema Registry authentication.

#### Requirement 5 -- Malware Protection: NOT ADDRESSED

No anti-malware, runtime vulnerability scanning, or file integrity monitoring. SEC-06 covers build-time dependency scanning only.

#### Requirement 6 -- Secure Development: PARTIAL

**Present:** Dependency scanning (SEC-06), Starter Gradle plugin enforcement, strong testing stack (JUnit 5, Testcontainers, Pact contract testing), input validation (SEC-09).
**Absent:** No formal secure SDLC process, no security code review requirements, no vulnerability management triage process.

#### Requirement 7 -- Access Control: PARTIAL [BLOCKER -- Kafka ACLs]

**Present:** OAuth2 scopes for API access (SEC-02), service-to-service auth via mTLS/JWT (A2-S03), fraud management scopes (fraud:read, fraud:manage).
**Absent and critical:** No Kafka ACLs -- any authenticated service can produce to any topic, including `payment.settlement-events` (which credits accounts). No comprehensive RBAC model. No least privilege specification. No four-eyes principle for high-impact actions (sanctions false-positive dismissal, account unfreeze). No separation of duties for fraud threshold configuration.

#### Requirement 8 -- User Identification: PARTIAL

**Present:** Strong end-user SCA (PIN + TOTP with dynamic linking), refresh token rotation with family revocation, PKCE, mTLS for TPPs.
**Absent:** No password policy for admin/operator accounts, no MFA for admin access, no session management, no privileged access management, no break-glass procedures.

#### Requirement 9 -- Physical Security: N/A (Cloud Deployment)

Linked to Requirement 3 gap: if HSMs were specified, key ceremony physical security would apply.

#### Requirement 10 -- Logging and Monitoring: WELL-COVERED (with gaps)

This is the one area the Starter #4 gap analysis (`04_Secure_Spring_Boot_Platform_Starter/extensions/gap_analysis.md`) explicitly assesses against PCI DSS. It is genuinely well-designed:

**Covered (PCI DSS 10.x):**
| Sub-req | Control | Source |
|---------|---------|--------|
| 10.2 | Automated audit trails | STARTER-F10-F15, @Audited annotation |
| 10.3.1 | User identification in logs | JWT `sub` claim extraction |
| 10.3.2 | Event type | HTTP method + path |
| 10.3.3 | Date and time (UTC ISO 8601) | Structured JSON logging |
| 10.3.4 | Success/failure indication | Response status code |
| 10.3.5 | Origination (source IP, correlation ID) | Correlation ID propagation across services |

**Gaps:**
| Sub-req | Gap | Impact |
|---------|-----|--------|
| 10.3.6 | Affected resource identity is "Should" not "Must" | Cannot trace which specific account/payment was affected |
| 10.5 | Log immutability incomplete -- Kafka audit topic is append-only but PostgreSQL audit projections are mutable | Insider with DB access could modify audit records |
| 10.7 | No log retention policy | Cannot demonstrate required retention periods |
| -- | No SIEM integration | No centralized security event monitoring |
| -- | No alerting on security events (failed auth, privilege escalation) | Attack detection gap |

#### Requirement 11 -- Security Testing: MINIMALLY ADDRESSED

**Present:** Dependency scanning (SEC-06, OWASP Dependency-Check/Trivy).
**Absent:** No penetration testing requirements, no vulnerability scanning schedule, no IDS/IPS, no file integrity monitoring.

#### Requirement 12 -- Security Policies: ALMOST ENTIRELY ABSENT [BLOCKER]

The weakest area:
- No information security policy framework
- No incident response plan (prior review rates this 3/10)
- No risk assessment process
- No security awareness training requirements
- No vendor/third-party security requirements
- No breach notification procedure (GDPR requires 72 hours, PSD2 requires "without undue delay")

### PCI DSS Summary

| PCI DSS Requirement | Coverage | Rating |
|----|---------|--------|
| Req 1: Network security controls | Not addressed | 0/10 |
| Req 2: Secure configurations | Partial | 4/10 |
| Req 3: Protect stored data | Encryption mandated, key management absent | 3/10 |
| Req 4: Protect data in transit | SASL/SSL + mTLS, no TLS version/cipher specs | 5/10 |
| Req 5: Malware protection | Not addressed | 0/10 |
| Req 6: Secure development | Dependency scanning + testing, no formal SDLC | 5/10 |
| Req 7: Access control | OAuth2 scopes, no Kafka ACLs, no RBAC model | 4/10 |
| Req 8: User identification | Strong end-user SCA, weak admin controls | 5/10 |
| Req 9: Physical security | N/A | N/A |
| Req 10: Logging and monitoring | Best area -- well-designed audit logging | 7/10 |
| Req 11: Security testing | Minimal | 2/10 |
| Req 12: Security policies | Almost entirely absent | 1/10 |

**Hard blockers: 4** (network security, key management, Kafka ACLs, security policies). Overall alignment approximately 3/10. The claim "PCI DSS-aligned" can only be substantiated for Requirement 10 (audit logging).

---

## 4. EPC VoP Scheme Compliance

**Claim under review:** "The EPC is building a VoP API test toolbox. Our A6 implements the same patterns."

**Verdict:** The claim is **partially supportable**. A6 implements the correct architectural patterns (requester + responder, fuzzy name matching, privacy-preserving responses, anti-enumeration). Several areas are genuinely well-specified. However, two areas present hard blockers: the non-standard outcome vocabulary and the absence of the "Could Not Verify" EPC-mandated outcome. When the EPC test toolbox becomes available, A6 would fail on outcome code validation and API structure conformance without remediation.

### 4.1 Finding VOP-01: Non-Standard Result Vocabulary [BLOCKER]

**Severity:** BLOCKER
**Affected:** VOP-R02 (requirements.md line 40), A6-F01 (system_requirements.md line 420)

EPC VoP defines exactly 4 externally-visible outcomes: **Match**, **Close Match**, **No Match**, **Could Not Verify** (also called "Verification Not Possible").

A6 implements 6 result codes: `MATCH`, `CLOSE_MATCH`, `NO_MATCH`, `NOT_FOUND`, `TIMEOUT`, `ERROR`.

The EPC's "Could Not Verify" is a mandatory externally-facing outcome returned when the responder PSP cannot perform the check. A6 uses internal status codes (`NOT_FOUND`, `TIMEOUT`, `ERROR`) that have no mapping to the EPC vocabulary. The term "Could Not Verify" does not appear anywhere in the documentation.

**Recommendation:** In the external responder API, map `NOT_FOUND`, `TIMEOUT`, and `ERROR` to a single `COULD_NOT_VERIFY` response. Internally they can remain distinct for operational monitoring. This is a simple mapping layer but it is a certification requirement.

### 4.2 Finding VOP-02: API Not Aligned to EPC 257-23 [BLOCKER]

**Severity:** BLOCKER
**Affected:** VOP-P01 (responder API), requester API design

The EPC VoP Rulebook (EPC 257-23) defines a standardized API specification. A6 uses a custom REST API:
- Responder: `POST /api/v1/vop/check` with body `{iban, name}`
- Requester: `POST /internal/vop/verify` with body `{beneficiary_iban, stated_name, request_id}`

The EPC API specification uses different field names (e.g., `Payee`, `VerificationResult`, `AccountIdentification`), specific HTTP status code semantics, and version header requirements. The gap analysis acknowledges this ("ISO 20022 messaging (under development)" at gap_analysis.md line 48) but does not implement alignment.

**Recommendation:** Add a requirement for an EPC-compliant external API facade that maps between the internal REST API and the EPC-specified API contract. The internal APIs can remain as-is for intra-system use.

### 4.3 Finding VOP-03: No GDPR Compliance Section [SIGNIFICANT]

**Severity:** SIGNIFICANT
**Affected:** VoP query data handling, data model (requirements.md lines 124-137)

The EPC VoP scheme has strict data protection requirements. A6's privacy controls are present (no name disclosure on NO_MATCH) but incomplete:

- **No data retention policy**: The data model stores `stated_name` and `actual_name` in PostgreSQL permanently with no defined retention period.
- **No GDPR legal basis**: Processing personal data for VoP requires documented legal basis (likely Art. 6(1)(f) legitimate interest or Art. 6(1)(c) legal obligation under IPR).
- **No data subject rights**: Can an account holder request deletion of VoP query logs about their account? Not addressed.
- **No data minimization assessment**: Does the system store more VoP query data than necessary?

### 4.4 Finding VOP-04: Timeout Handling at "Should" Priority [SIGNIFICANT]

**Severity:** SIGNIFICANT
**Affected:** A6-F10 (system_requirements.md line 429), VOP-R06 (requirements.md line 44)

VoP timeout behavior (proceed with payment but flag for review) is specified as "Should" not "Must." Under EPC rules, timeout handling is mandatory. Additionally:
- No explicit timeout value is defined for the external PSP call
- The 500ms latency budget (VOP-R05/A6-NF02) implies a timeout but no explicit timeout value is stated

**Recommendation:** Elevate A6-F10 to "Must." Define an explicit timeout (e.g., 400ms) for the external responder call, reserving 100ms of the 500ms budget for internal processing.

### 4.5 Finding VOP-05: Legal Entity Handling at "Should" Priority [MODERATE]

**Severity:** MODERATE
**Affected:** VOP-N08 (requirements.md line 70)

Legal entity suffix handling ("compare base name independently from suffix") is "Should" priority. For EPC VoP, matching a corporate name like "Gronn Energi AS" against "Gronn Energi Aksjeselskap" is essential, not optional. The system does not formally distinguish between natural person accounts and legal entity accounts, which may require different matching logic.

**Recommendation:** Elevate VOP-N08 to "Must." Add an account type flag (NATURAL_PERSON / LEGAL_ENTITY) to drive matching path selection.

### 4.6 Finding VOP-06: No Scheme Participation / Inter-PSP Routing [MODERATE]

**Severity:** MODERATE
**Affected:** System architecture

The system operates as a single-PSP implementation. For EPC VoP scheme compliance, it would need:
- Registration as a VoP scheme participant
- Routing logic to direct queries to the correct beneficiary PSP (via EPC VoP directory service)
- Discoverability by other PSPs as a responder

The gap analysis acknowledges this as a known scope limitation. For a portfolio project this is acceptable, but the architecture should show where the directory lookup and inter-PSP routing would plug in.

### 4.7 Positive Findings (VoP)

Several VoP scheme requirements are well-covered:

| Criterion | Status | Evidence |
|-----------|--------|----------|
| Requester + Responder roles | COMPLIANT | Both explicitly implemented (VOP-R01-R07, VOP-P01-P07) |
| Fuzzy name matching | STRONG | Jaro-Winkler with configurable thresholds, diacritics, abbreviations |
| Anti-enumeration protection | COMPLIANT | Rate limiting (100/min), pattern detection, audit logging, name non-disclosure (VOP-E01-E04) |
| Close Match UX | COMPLIANT | Actual name displayed, confirm/cancel flow, feeds into fraud scoring |
| Payment flow integration | COMPLIANT | SEC-12 makes VoP mandatory; clear integration with A3, A4, A5 |
| IBAN validation | SPECIFIED | VOP-S06, though validation depth (mod-97) not detailed |
| VoP free of charge | COVERED | A6-F09 (regulatory requirement) |
| 24/7 availability | COVERED | A6-F08, A6-NF03 (99.99%) |

### VoP Summary

| # | Finding | Severity | Hard Blocker? |
|---|---------|----------|--------------|
| VOP-01 | Non-standard result vocabulary (missing "Could Not Verify") | BLOCKER | Yes |
| VOP-02 | API not aligned to EPC 257-23 | BLOCKER | Yes |
| VOP-03 | No GDPR compliance section | SIGNIFICANT | -- |
| VOP-04 | Timeout handling at "Should" priority | SIGNIFICANT | -- |
| VOP-05 | Legal entity handling at "Should" priority | MODERATE | -- |
| VOP-06 | No scheme participation / inter-PSP routing | MODERATE | -- |

**Hard blockers: 2.** The underlying logic is sound; the interface contract needs alignment to EPC vocabulary and API structure.

---

## 5. Cross-Certification Dependencies

Several findings affect multiple certification pathways and should be prioritized as force-multipliers:

### 5.1 Key Management Architecture (Affects: PCI DSS, CBPR+, SCT Inst)

A key management framework (HSM/KMS, key rotation, key hierarchy) is needed for:
- PCI DSS Req 3.5 (secure key storage)
- CBPR+ BAH digital signatures (CBPR-02)
- Message-level integrity for Kafka (affects all ISO 20022 message flows)
- Webhook HMAC key management (A3-S03)

**Resolution impact:** Resolving key management unblocks controls across all four pathways.

### 5.2 ISO 20022 Code List Formalization (Affects: CBPR+, SCT Inst, VoP)

Formalizing the code list mapping resolves:
- CBPR-07 (external code list references)
- SCT-03 (non-standard reject reason codes)
- VOP-01 (non-standard VoP outcomes mapped to ISO 20022 vocabulary)

**Resolution impact:** A single "ISO 20022 Code List Profile" document resolves findings across three pathways.

### 5.3 Incident Response and Security Policy (Affects: PCI DSS, DORA, all EU certifications)

The absent security policy framework blocks:
- PCI DSS Req 12
- DORA Article 6 (ICT risk management framework) -- which is mandatory for all EU financial entities since January 2025
- GDPR Article 33 (breach notification)
- PSD2 Article 96 (major incident reporting)

### 5.4 GDPR Data Protection Framework (Affects: VoP, PCI DSS, all sub-systems)

The EU legislation review identified GDPR as the most systemic gap across the entire system. This affects:
- VOP-03 (VoP data protection)
- PCI DSS alternative: if PCI DSS is not in scope, GDPR is the primary data protection standard
- All sub-systems that process personal financial data

---

## 6. Consolidated Findings Table

| ID | Finding | Pathway | Severity | Hard Blocker? |
|----|---------|---------|----------|--------------|
| CBPR-01 | No message version pinning | CBPR+ | BLOCKER | Yes |
| CBPR-02 | No Business Application Header (head.001) | CBPR+ | BLOCKER | Yes |
| CBPR-03 | No UETR | CBPR+ | BLOCKER | Yes |
| CBPR-04 | No structured addresses | CBPR+ | BLOCKER | Yes |
| CBPR-05 | No character set validation | CBPR+ | BLOCKER | Yes |
| CBPR-06 | No business rule validation layer | CBPR+ | BLOCKER | Yes |
| CBPR-07 | No formal code list references | CBPR+ / SCT Inst | BLOCKER | Yes |
| CBPR-08 | No SWIFT test case evidence | CBPR+ | SIGNIFICANT | -- |
| CBPR-09 | No LEI handling | CBPR+ | MODERATE | -- |
| CBPR-10 | No schema version migration strategy | CBPR+ | MODERATE | -- |
| SCT-01 | No 5-second execution target | SCT Inst | SIGNIFICANT | -- |
| SCT-02 | Recall mechanism absent (camt.056, camt.029) | SCT Inst | BLOCKER | Yes |
| SCT-03 | Non-standard reject reason codes | SCT Inst | BLOCKER | Yes |
| SCT-04 | Settlement finality moment undefined | SCT Inst | BLOCKER | Yes |
| SCT-05 | Time budget not traceable to rulebook | SCT Inst | MINOR | -- |
| SCT-06 | No CSM interface specification | SCT Inst | MODERATE | -- |
| PCI-01 | No network security controls | PCI DSS | BLOCKER | Yes |
| PCI-02 | No key management architecture | PCI DSS / All | BLOCKER | Yes |
| PCI-03 | No Kafka ACLs / RBAC model | PCI DSS | BLOCKER | Yes |
| PCI-04 | No security policies / incident response | PCI DSS / DORA | BLOCKER | Yes |
| PCI-05 | PCI DSS scope not determined | PCI DSS | SIGNIFICANT | -- |
| PCI-06 | No TLS version / cipher suite specs | PCI DSS | SIGNIFICANT | -- |
| PCI-07 | No security testing requirements | PCI DSS | SIGNIFICANT | -- |
| PCI-08 | Audit log immutability incomplete | PCI DSS | MODERATE | -- |
| PCI-09 | No admin/operator access controls | PCI DSS | MODERATE | -- |
| VOP-01 | Non-standard result vocabulary | VoP | BLOCKER | Yes |
| VOP-02 | API not aligned to EPC 257-23 | VoP | BLOCKER | Yes |
| VOP-03 | No GDPR compliance section | VoP | SIGNIFICANT | -- |
| VOP-04 | Timeout handling at "Should" priority | VoP | SIGNIFICANT | -- |
| VOP-05 | Legal entity handling at "Should" priority | VoP | MODERATE | -- |
| VOP-06 | No scheme participation / inter-PSP routing | VoP | MODERATE | -- |

**Total: 31 findings. 16 hard blockers across 4 pathways.**

---

## 7. Recommended Certification Sequence

Given the findings above, the recommended order of certification work (prioritized by effort-to-value ratio and dependency chains):

### Phase 1: Cross-Cutting Foundations (Unblocks Multiple Pathways)

| Action | Effort | Unblocks |
|--------|--------|----------|
| 1a. ISO 20022 Message Profile document (version matrix, code list references, field specs) | Medium | CBPR-01, CBPR-05, CBPR-06, CBPR-07, SCT-03 |
| 1b. Key management architecture requirements (KM-01 through KM-10) | Medium | PCI-02, CBPR-02 (BAH signing), all encryption controls |
| 1c. Security policy framework + incident response plan | Medium | PCI-04, DORA compliance |
| 1d. GDPR data protection framework (legal basis, retention, data subject rights) | Medium | VOP-03, PCI-05 (scope clarification) |

### Phase 2: VoP Scheme Compliance (Quickest Win)

| Action | Effort | Unblocks |
|--------|--------|----------|
| 2a. Map internal result codes to EPC 4-outcome vocabulary | Low | VOP-01 |
| 2b. Add EPC-compliant API facade for external responder interface | Medium | VOP-02 |
| 2c. Elevate VOP-N08 and A6-F10 priorities, add timeout value | Low | VOP-04, VOP-05 |

### Phase 3: SCT Inst Rulebook Compliance

| Action | Effort | Unblocks |
|--------|--------|----------|
| 3a. Add camt.056 recall + camt.029 response requirements | Medium | SCT-02 |
| 3b. Complete internal-to-ISO reason code mapping | Low | SCT-03 (after Phase 1a) |
| 3c. Define settlement finality moment, elevate CLR-I13 to Must | Low | SCT-04 |
| 3d. Make SLA timeout configurable, document 5s migration path | Low | SCT-01 |

### Phase 4: CBPR+ Full Compliance

| Action | Effort | Unblocks |
|--------|--------|----------|
| 4a. Add head.001 BAH to payment-iso20022 library and requirements | Medium | CBPR-02 |
| 4b. Add UETR generation + propagation across all pacs.* messages | Medium | CBPR-03 |
| 4c. Add structured address model to payment-domain-model | Medium | CBPR-04 |
| 4d. Add character set transliteration for outbound messages | Low | CBPR-05 |
| 4e. Add XMLdation or equivalent conformance testing | Medium | CBPR-08 |

### Phase 5: PCI DSS / Security Hardening (If Formally In Scope)

| Action | Effort | Unblocks |
|--------|--------|----------|
| 5a. Network security architecture (network policies, segmentation) | Medium | PCI-01 |
| 5b. Kafka ACLs and RBAC model | Medium | PCI-03 |
| 5c. TLS version/cipher specs, message-level integrity | Low | PCI-06 |
| 5d. Admin access controls, MFA, session management | Medium | PCI-09 |
| 5e. Security testing requirements (pen test, vuln scanning schedule) | Low | PCI-07 |

---

## Appendix A: Existing Review Cross-References

This review builds on and cross-references four prior reviews:

| Review | Key Findings Relevant Here | Status |
|--------|---------------------------|--------|
| `requirements_review_iso20022_certification.md` | 31 findings (9 critical). Verdict: "compatible not compliant." Covers ISO-01 through ISO-15. **Does not identify BAH/head.001 gap** (our CBPR-02). | Confirmed and extended |
| `payment_processing_requirements_review.md` | 14 security gaps (3 critical). Key: CRITICAL-01 (key management), CRITICAL-02 (Kafka security), CRITICAL-03 (incident response). Overall rating 8.5/10. | Confirmed. Security findings directly feed PCI DSS assessment |
| `requirements_review_eu_legislation.md` | 23 legislation gaps (7 critical). Key: GDPR near-total absence, Settlement Finality Directive gap, DORA gap. | Confirmed. GDPR and SFD gaps directly affect VoP and SCT Inst |
| `requirements_review_computational_stability.md` | Numerical precision, concurrency, event ordering. | Not directly relevant to certification but supports operational quality |
| `requirements_review_allevo_comparison.md` | Reconciliation and duplicate detection from FinTP. | Supports CBPR+ reconciliation readiness |

---

## Appendix B: Standards Version Matrix

For certification work, these are the normative documents:

| Standard | Version | Publication | Notes |
|----------|---------|-------------|-------|
| SWIFT CBPR+ Usage Guidelines | November 2025 release | SWIFT | Version-specific; updated annually |
| EPC SCT Inst Rulebook | EPC 004-16, 2023 v1.2 | EPC | Check for 2025/2026 updates |
| EPC VoP Rulebook | EPC 257-23 | EPC | First edition; API spec under development |
| PCI DSS | v4.0.1 (March 2024) | PCI SSC | Full compliance deadline: March 2025 |
| ISO 20022 Catalogue | 2023 full edition | ISO | Message versions per CBPR-01 table |
| EU Instant Payment Regulation | 2024/886 | Official Journal | Phased deadlines through 2027 |
| Settlement Finality Directive | 98/26/EC (consolidated) | Official Journal | As amended |
| DORA | 2022/2554 | Official Journal | In force January 2025 |
| GDPR | 2016/679 | Official Journal | In force |
