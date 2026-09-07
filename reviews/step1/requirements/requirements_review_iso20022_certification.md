# Requirements Review: ISO 20022 Compliance, Certification Readiness, and Requirements Quality

**Reviewer:** Claude Opus 4.6 (1M context)
**Date:** 2026-04-11
**Scope:** Full requirements corpus -- system_requirements.md, 7 sub-project requirements, 10 use cases, 6 extensions, NAVIGATION.md cross-references. With particular attention to ISO 20022 compliance and obstacles to future certification.
**Methodology:** All documents read in full. Cross-referenced against ISO 20022 standard structure, EPC rulebook requirements, EU Instant Payment Regulation 2024/886, and SWIFT CBPR+ conformance criteria. Requirements traceability, consistency, and testability assessed systematically.

---

## Executive Summary

The requirements documentation demonstrates strong domain knowledge and a thoughtful architecture. The ISO 20022 message types are correctly identified (pain.001, pacs.008, pacs.002, pacs.004, pain.013/014, camt.053, pacs.028), the Kafka topic separation for batch vs. instant is sound, and the dual-mode clearing architecture (Spring Batch + Kafka Streams) is well-motivated.

However, the ISO 20022 treatment is **shallow and would not survive certification scrutiny**. The documents name the right messages but do not specify which *versions* of those messages to use, which *fields* are mandatory, what *validation rules* to apply, which *external code lists* to reference, or how *schema versioning* will be managed. These are not cosmetic omissions -- they are the difference between a system that generates XML files with ISO 20022 element names and a system that a clearing house or scheme operator would accept as conformant.

This review identifies **31 findings** organized into four areas:

| Area | Critical | High | Medium | Low | Total |
|------|----------|------|--------|-----|-------|
| ISO 20022 Compliance Gaps | 5 | 6 | 3 | 1 | 15 |
| Certification Obstacles | 3 | 3 | 2 | 0 | 8 |
| Requirements Quality (Traceability, Testability) | 1 | 2 | 2 | 0 | 5 |
| Cross-reference Consistency | 0 | 1 | 1 | 1 | 3 |
| **Total** | **9** | **12** | **8** | **2** | **31** |

**Verdict:** The system is currently **ISO 20022 compatible** (it uses ISO 20022 concepts and message names) but is **not ISO 20022 compliant** (it does not specify sufficient detail for conformance testing). Moving from compatible to compliant requires substantial requirements additions but no architectural changes -- the foundation is sound.

---

## Table of Contents

1. [ISO 20022 Compliance Findings](#1-iso-20022-compliance-findings)
2. [Certification Obstacles](#2-certification-obstacles)
3. [Requirements Quality for Certification](#3-requirements-quality-for-certification)
4. [Cross-reference Consistency Analysis](#4-cross-reference-consistency-analysis)
5. [Use Case Review -- ISO 20022 and Certification Lens](#5-use-case-review)
6. [Positive Findings](#6-positive-findings)
7. [Recommendations and Roadmap](#7-recommendations-and-roadmap)
8. [Appendix: ISO 20022 Message Version Matrix](#appendix-iso-20022-message-version-matrix)

---

## 1. ISO 20022 Compliance Findings

### Finding ISO-01: No Message Version Specification [CRITICAL]

**Location:** system_requirements.md DATA-04, CLR-B02, CLR-B08, A2-F02, A2-F05, A3-F12, IP-F05

**Issue:** The requirements reference ISO 20022 message types by name (pain.001, pacs.008, pacs.002, pacs.004, pain.013, pain.014, camt.053, pacs.028) but never specify which *version* of each message to implement. ISO 20022 messages are identified by a four-part code: `area.message.variant.version` (e.g., `pacs.008.001.10`). Different versions have different mandatory fields, different cardinalities, and different namespace URIs.

This is not a theoretical concern. The difference between versions is material:

| Message | Version 03 (legacy SEPA) | Version 08-10 (current SEPA 2023+) | Key Difference |
|---------|--------------------------|-------------------------------------|----------------|
| pacs.008 | No UETR field | UETR mandatory | Cannot track payment end-to-end without UETR |
| pacs.008 | Unstructured address OK | Structured address required (2025+) | Messages with unstructured addresses will be rejected |
| pain.001 | No LEI field | LEI optional/encouraged | Corporate payments may require LEI |
| camt.053 | Basic entry detail | Enhanced entry detail with references | Reconciliation data may be missing |

**Impact:** Without version pinning, the JAXB-generated classes in `payment-iso20022` (shared library, section 9.2 of system_requirements.md) cannot be generated. JAXB requires XSD schemas, and each version has a different XSD. Two developers picking different versions would produce incompatible code.

**Recommendation:** Add a version matrix to DATA-04 or to a new "ISO 20022 Message Profile" document. Recommended versions for SEPA/EPC alignment (2025+):

| Message | Recommended Version | XSD Namespace |
|---------|---------------------|---------------|
| pain.001 | pain.001.001.11 or .12 | urn:iso:std:iso:20022:tech:xsd:pain.001.001.11 |
| pacs.008 | pacs.008.001.10 | urn:iso:std:iso:20022:tech:xsd:pacs.008.001.10 |
| pacs.002 | pacs.002.001.12 | urn:iso:std:iso:20022:tech:xsd:pacs.002.001.12 |
| pacs.004 | pacs.004.001.11 | urn:iso:std:iso:20022:tech:xsd:pacs.004.001.11 |
| pacs.028 | pacs.028.001.05 | urn:iso:std:iso:20022:tech:xsd:pacs.028.001.05 |
| pain.013 | pain.013.001.09 | urn:iso:std:iso:20022:tech:xsd:pain.013.001.09 |
| pain.014 | pain.014.001.09 | urn:iso:std:iso:20022:tech:xsd:pain.014.001.09 |
| camt.053 | camt.053.001.10 | urn:iso:std:iso:20022:tech:xsd:camt.053.001.10 |

---

### Finding ISO-02: No Mandatory Field Specification for pacs.008 [CRITICAL]

**Location:** A2-F02 ("Validate payment instructions against ISO 20022 schema"), CLR-B02

**Issue:** The requirements say "validate against ISO 20022 pacs.008 schema" but do not specify which fields are mandatory in the system's usage profile. ISO 20022 XSD schemas define many fields as optional that SEPA scheme rules make mandatory. XSD validation alone is insufficient.

For example, in a pacs.008 credit transfer under SEPA rules, the following fields are mandatory beyond what the XSD enforces:

**Mandatory per SEPA SCT Rulebook (EPC-specific, not XSD-enforced):**
- `EndToEndIdentification` (max 35 chars, must be unique)
- `UETR` (Universal End-to-End Transaction Reference, UUID v4 format -- mandatory since SWIFT CBPR+ 2022)
- `InstructedAmount` with `Ccy` attribute (must be valid ISO 4217)
- `ChargeBearer` must be `SLEV` (service level agreement)
- `Creditor/Name` (max 70 chars)
- `CreditorAccount/IBAN` (validated per ISO 13616 mod-97 check)
- `DebtorAccount/IBAN`
- `SettlementMethod` must be `CLRG` for CSM-routed
- `ServiceLevel/Code` must be `SEPA` (for SCT) or `SINT` (for SCT Inst)
- `PaymentTypeInformation/LocalInstrument/Code` = `INST` for instant

**Impact:** Without business-rule validation beyond XSD, the system would accept malformed messages that any real clearing house would reject. More critically, the system would *produce* messages that other participants cannot process.

**Recommendation:** Add a requirement (suggest A2-F14 or CLR-B16) specifying:
- Two-level validation: (1) XSD schema validation, (2) business rule validation per SEPA rulebook
- A SEPA-specific validation profile listing mandatory fields and value constraints
- Specific IBAN validation (mod-97 algorithm per ISO 13616)
- UETR generation (UUID v4) for every payment instruction

---

### Finding ISO-03: No UETR (Unique End-to-End Transaction Reference) [CRITICAL]

**Location:** All use cases, all sub-system requirements

**Issue:** The Universal End-to-End Transaction Reference (UETR) is completely absent from the requirements. Since November 2022 (SWIFT CBPR+ migration), UETR is mandatory for all interbank credit transfers. It is a UUID v4 that travels with the payment from originator to beneficiary, enabling end-to-end tracking.

The system uses `transaction_id` (UUID, internal) and `EndToEndIdentification` (in ISO 20022 messages) but not UETR. These serve different purposes:
- `transaction_id`: internal system identifier
- `EndToEndIdentification`: originator-assigned reference (may be the same as UETR or different)
- `UETR`: globally unique tracking ID for the payment, mandatory in pacs.008, pacs.002, pacs.004, pacs.028

**Impact:** Without UETR:
- The system cannot participate in payment tracking (pacs.028 status requests reference UETR)
- Generated pacs.008 messages would be rejected by SWIFT-connected counterparties
- The status inquiry feature (A2-F29/CLR-I11, pacs.028 support) cannot work without UETR
- Reconciliation loses a key cross-system reference

**Recommendation:** Add UETR as a first-class field:
- Generated at payment initiation time (A3 or A4)
- Carried in all Kafka events (`PaymentInstruction`, `FraudCheckRequest`, `SettlementEvent`, `StatusUpdate`)
- Mapped to pacs.008 `UETR` field
- Used as reference in pacs.002, pacs.004, pacs.028
- Stored in A1 ledger events alongside `externalReference`

---

### Finding ISO-04: No External Code List References [CRITICAL]

**Location:** UC-08 (pacs.002 example), UC-04, A2 requirements

**Issue:** ISO 20022 uses externalized code lists maintained separately from message schemas. The requirements do not reference these code lists, though they use values from them informally.

Key missing code list references:

| Code List | Used Informally | Formal Reference Needed |
|-----------|-----------------|-------------------------|
| Payment status codes | "ACSC" (AcceptedSettlementCompleted), "RJCT" (Rejected) in UC-08 | ExternalPaymentTransactionStatus1Code |
| Rejection reason codes | "AM04" (InsufficientFunds) in UC-08 | ExternalStatusReason1Code |
| Service level codes | "INSTANT", "STANDARD" in A4/A3 | ExternalServiceLevel1Code (`SEPA`, `INST`) |
| Purpose codes | Not referenced | ExternalPurpose1Code |
| Category purpose | Not referenced | ExternalCategoryPurpose1Code |
| Charge bearer | Not referenced | Must be `SLEV` for SEPA |
| Local instrument | Not referenced | ExternalLocalInstrument1Code (`INST` for SCT Inst) |

**Impact:** The requirements use informal status values (`SETTLED`, `REJECTED`, `FRAUD_SUSPECTED`) that do not map directly to ISO 20022 codes. This creates a translation gap: internal statuses must map to standard codes, and incoming standard codes must map to internal statuses. Without explicit mapping, each developer will invent their own, causing inconsistency.

Specific issue: UC-08 shows `<TxSts>ACSC</TxSts>` and `<Rsn><Cd>AM04</Cd></Rsn>` -- these are correct ISO 20022 values, but the mapping from internal states (SETTLED -> ACSC, INSUFFICIENT_FUNDS -> AM04) is nowhere documented as a requirement.

**Recommendation:** Add a status mapping table as a requirement (suggest DATA-07 or a dedicated "ISO 20022 Code Mapping" document):
- Internal status -> ISO 20022 status code (for outbound messages)
- ISO 20022 status code -> internal status (for inbound messages)
- Reference the specific ExternalCodeSet version
- Document reason code mappings for all rejection scenarios

---

### Finding ISO-05: No Structured Address Requirement [CRITICAL]

**Location:** All sub-system requirements, payment domain model

**Issue:** ISO 20022 messages contain party identification elements (Debtor, Creditor, DebtorAgent, CreditorAgent) that include postal addresses. The industry is moving from unstructured addresses (free-text lines) to mandatory structured addresses (street name, building number, postal code, town, country).

Timeline for structured address mandates:
- **SWIFT CBPR+**: Structured addresses recommended from March 2023, **mandatory from November 2025** (already in effect at time of review)
- **TARGET**: Structured addresses mandatory from November 2025
- **EPC SEPA schemes**: Increasingly requiring structured addresses

The requirements mention `creditor_name` and `debtor_account` but never define the party identification structure. The data model for `PaymentInstruction` (in Kafka events and ISO 20022 messages) needs:
- Name (max 140 chars)
- Postal address: StreetName, BuildingNumber, PostCode, TownName, Country (ISO 3166-1 alpha-2)
- Identification: BIC (for agents), LEI (optional, for corporates), or proprietary ID

**Impact:** Without structured address fields:
- Generated pacs.008 messages will be non-compliant with CBPR+ rules (already in effect)
- Messages will be rejected or truncated by counterparties
- Sanctions screening accuracy is degraded (addresses are a key matching criterion)
- The shared `payment-domain-model` library will need redesign after implementation begins

**Recommendation:** Add structured party identification to the domain model. At minimum:
- Debtor: name, IBAN, BIC (optional), structured address (optional for domestic, mandatory for cross-border)
- Creditor: name, IBAN, BIC (optional), structured address
- Agent: BIC
- Add a requirement that cross-border payments must include structured addresses

---

### Finding ISO-06: Incomplete pacs.002 Specification [HIGH]

**Location:** UC-08 (pacs.002 example), A2-F24, CLR-I08

**Issue:** UC-08 includes a pacs.002 XML example, which is commendable. However, the example is incomplete relative to what a conformant pacs.002 requires. Missing elements:

- `OriginalGroupInformation` (reference to the original pacs.008 group header)
- `UETR` (see ISO-03)
- `OrgnlTxRef` (original transaction reference block -- required for negative confirmations so the originator can identify which payment was rejected and why)
- `AcceptanceDateTime` format should include timezone designator
- `InstdAgt` / `InstgAgt` (instructed/instructing agent) -- only `InstgAgt` is shown

For **negative confirmation** (RJCT), the example shows only `<Rsn><Cd>AM04</Cd></Rsn>` but should also include:
- `OriginalTransactionReference` with enough detail to identify the payment (debtor, creditor, amount)
- `StsReasonInformation/AddtlInf` for human-readable explanation

**Impact:** Implementers following the example will produce non-conformant confirmation messages.

**Recommendation:** Expand the pacs.002 examples (both positive and negative) to include all mandatory fields per the selected message version. Add both as acceptance criteria data.

---

### Finding ISO-07: No pain.013/014 Detail for Request-to-Pay [HIGH]

**Location:** A3-F12 ("ISO 20022 message support: pain.013, pain.014"), RTP-F11

**Issue:** Both the master and sub-project requirements list pain.013 (CreditorPaymentActivationRequest) and pain.014 (CreditorPaymentActivationRequestStatusReport) as "Should" priority with no further specification. These are the ISO 20022 message types for Request-to-Pay, yet:

- No version specified
- No field mapping between internal R2P model and pain.013 elements
- No validation rules
- No example messages
- The R2P lifecycle states (PENDING, ACCEPTED, DECLINED, DEFERRED, AMENDED) are not mapped to pain.014 status codes

The EPC's SEPA Request to Pay (SRTP) scheme (EPC014-20) defines specific usage of pain.013/014 with additional business rules. Since the system explicitly aims to evolve toward SRTP (gap_analysis.md DIFF 2), the pain.013/014 requirements need substance.

**Impact:** The "Should" priority means these might be deferred, but the R2P service (A3) is a core component. Without pain.013/014 grounding, the R2P implementation will be a proprietary REST API that happens to use some ISO 20022 concepts, rather than a genuine SRTP participant.

**Recommendation:** Either:
(a) Elevate pain.013/014 to "Must" and add field mappings, or
(b) Explicitly document that R2P is REST-API-first with pain.013/014 as a future export format, and define the mapping at that time.
Option (b) is honest and pragmatic for a portfolio project.

---

### Finding ISO-08: No Character Set Specification [HIGH]

**Location:** All ISO 20022 message generation requirements

**Issue:** ISO 20022 XML uses UTF-8 encoding, but SEPA schemes restrict the allowed character set. The EPC specifies the "Basic Latin" character set for SEPA messages:
- a-z, A-Z, 0-9
- Space, slash, hyphen, period, comma, plus, parentheses, colon, question mark, apostrophe
- National characters (ä, ö, ü, ø, å, etc.) are allowed in some schemes but NOT in others

For a system operating in the Nordics with names containing ø, å, ä, the character set handling is critical:
- `Ola Nordmann` -- OK everywhere
- `Bjørn Åsheim` -- OK in P27/Nordic schemes, problematic in strict SEPA character set
- `Müller GmbH` -- OK in most SEPA, but needs UTF-8 awareness

The requirements mention diacritics handling in VoP (A6, VOP-N02) but not in ISO 20022 message generation.

**Impact:** Messages containing Nordic characters could be rejected by strict SEPA CSMs. The system must either:
- Transliterate names for ISO 20022 messages (ø -> o, å -> a) while preserving originals for VoP, OR
- Use the extended character set allowed in P27 and some SEPA implementations

**Recommendation:** Add a requirement (suggest DATA-08) specifying:
- Character set for ISO 20022 message generation (UTF-8 with SEPA Basic Latin restriction or extended)
- Transliteration rules for Nordic characters when generating cross-border SEPA messages
- Preservation of original names (with full Unicode) in internal storage and VoP

---

### Finding ISO-09: No IBAN Validation Requirement [HIGH]

**Location:** All sub-systems handling IBANs (A1, A2, A3, A4, A6)

**Issue:** IBANs appear throughout the system (debtor accounts, creditor accounts, VoP queries, ledger accounts) but no requirement specifies IBAN format validation. ISO 13616 defines IBAN validation:

1. Country code (2 letters, ISO 3166-1)
2. Check digits (2 digits, mod-97 verification)
3. BBAN (country-specific, for Norway: 11 digits)

The requirements mention "IBAN format (or simplified 11-digit Norwegian account number)" in DATA-05 but do not specify:
- When to accept 11-digit Norwegian format vs. full IBAN
- Whether IBAN check digit validation is required
- Whether BIC can be derived from IBAN (IBAN-only rule in SEPA)
- How to validate foreign IBANs (different BBAN lengths per country)

**Impact:** Without IBAN validation:
- Typos in IBANs propagate through the entire payment chain before failing at settlement
- VoP checks (A6) will query invalid IBANs, wasting resources
- The system cannot distinguish between "IBAN not found at this PSP" and "malformed IBAN"

**Recommendation:** Add explicit IBAN validation requirements:
- SEC-13 or DATA-07: "All IBANs must be validated per ISO 13616 (mod-97 check digit verification) at the point of entry (A3 request creation, A4 payment initiation, A6 VoP query)"
- Define behavior for 11-digit Norwegian account numbers (convert to IBAN internally?)

---

### Finding ISO-10: camt.053 Generation Lacks Reconciliation Detail [HIGH]

**Location:** A2-F05/CLR-B08 (camt.053 generation), A1-F13/LED-F34 (camt.053 statement), A2-F13/CLR-B14 (reconciliation-ready output)

**Issue:** Two sub-systems generate camt.053: A2 (settlement reports) and A1 (account statements). The requirements state that camt.053 should contain references for reconciliation (CLR-B14, LED-F36) but do not specify *where* in the camt.053 structure these references go.

In a camt.053 statement, the key reconciliation fields are:
- `Entry/AccountServicerReference` -- the bank's unique reference for the entry
- `Entry/EntryDetails/TransactionDetails/References/EndToEndIdentification`
- `Entry/EntryDetails/TransactionDetails/References/UETR`
- `Entry/EntryDetails/TransactionDetails/References/ClearingSystemReference`
- `Entry/EntryDetails/TransactionDetails/References/InstructionIdentification`

The requirement says "include settlementReference and clearingCycleId" but doesn't say which camt.053 XML element each maps to. Multiple valid choices exist, and inconsistency between A1 and A2 would break reconciliation.

**Impact:** If A2 puts the `clearingCycleId` in `ClearingSystemReference` and A1 puts it in `AccountServicerReference`, reconciliation matching fails despite both generating valid camt.053 XML.

**Recommendation:** Add a field mapping table specifying:
- `settlementReference` -> `Entry/EntryDetails/TransactionDetails/References/AccountServicerReference`
- `clearingCycleId` -> `Entry/EntryDetails/TransactionDetails/References/ClearingSystemReference`
- `EndToEndIdentification` -> same-named element in both
- `UETR` -> same-named element in both
- This mapping must be consistent between A1 and A2

---

### Finding ISO-11: No BIC Handling [HIGH]

**Location:** payment-domain-model, all sub-systems

**Issue:** BIC (Bank Identifier Code, ISO 9362) appears only in the pacs.002 example (`<BIC>DNBANOKK</BIC>`) and nowhere in the requirements or data models. In ISO 20022 messages:
- `DebtorAgent` and `CreditorAgent` are identified by BIC
- Since the SEPA IBAN-only rule (2016), BIC is derivable from IBAN for domestic SEPA, but still used in clearing messages
- Cross-border payments require BIC

The `payment-domain-model` shared library (section 9.2) lists shared types but does not include BIC.

**Impact:** Without BIC handling:
- pacs.008 messages will have empty `DebtorAgent`/`CreditorAgent` elements
- Cross-border payment support is incomplete
- Agent identification in clearing is ambiguous

**Recommendation:** Add BIC to the domain model and add a requirement for BIC derivation from IBAN (using a BIC directory or IBAN structure) for domestic payments.

---

### Finding ISO-12: No pacs.004 (Payment Return) Detail [MEDIUM]

**Location:** A2-F30/CLR-I12 ("Support pacs.004 for post-settlement return requests"), both marked "Should"

**Issue:** Payment returns are a critical operational process. After an instant payment is settled (irrevocable), the originator PSP may need to request a return (e.g., fraud detected post-settlement, recall request). The requirements acknowledge pacs.004 but provide no detail:
- What return reason codes to support?
- Who can initiate a return request?
- What is the process flow? (Request -> beneficiary PSP evaluates -> accept/reject return)
- Timeframes for return request processing?
- How does a return interact with the ledger (A1)? (Reverse credit, re-credit debtor)
- Is beneficiary consent required for the return?

**Impact:** Payment returns are operationally inevitable. Without a defined process, post-settlement corrections will be ad hoc.

**Recommendation:** Expand pacs.004 into a use case scenario (add to UC-08 or create UC-11) covering:
- Recall request flow (debtor PSP -> creditor PSP)
- Return reason codes (FRAD for fraud, DUPL for duplicate, TECH for technical)
- SLA for processing return requests
- Ledger reversal entries in A1

---

### Finding ISO-13: Settlement Method Not Specified [MEDIUM]

**Location:** A2 clearing engine requirements

**Issue:** pacs.008 requires a `SettlementMethod` in the GroupHeader. The three options are:
- `INDA` (InstructedAgent -- direct settlement between two agents)
- `INGA` (InstructingAgent -- settlement by the instructing agent)
- `CLRG` (ClearingSystem -- settlement via CSM like TIPS or RT1)

The requirements do not specify which settlement method the system uses. Since A2 *is* the clearing engine (it performs the settlement), the correct value depends on the perspective:
- If A2 is acting as a CSM: `CLRG`
- If A2 is acting as a bilateral settlement mechanism: `INDA`

**Impact:** Ambiguity in a mandatory pacs.008 field.

**Recommendation:** Specify that A2 uses `CLRG` as SettlementMethod (consistent with its CSM role) and document this in the architecture decisions.

---

### Finding ISO-14: No ISO 20022 Schema Evolution Strategy [MEDIUM]

**Location:** DATA-06 (Avro schema evolution), DATA-04 (ISO 20022)

**Issue:** DATA-06 specifies schema evolution rules for Kafka events (Avro with Schema Registry), which is good. But there is no equivalent strategy for ISO 20022 XML schemas. ISO 20022 messages evolve:
- New versions are released periodically
- SEPA schemes adopt new versions on published timelines (often years in advance)
- Multiple versions may need to coexist during migration periods

The `payment-iso20022` shared library (JAXB-generated classes) will be tightly coupled to specific XSD versions. Upgrading to a new message version requires:
1. New XSD
2. Regenerated JAXB classes
3. Migration of any code using old elements
4. Potentially supporting both old and new versions simultaneously

**Impact:** Without a strategy, the first schema upgrade will require a breaking change across all consuming sub-systems.

**Recommendation:** Add a requirement for ISO 20022 version management:
- The `payment-iso20022` library should use a versioned package structure (e.g., `com.payment.iso20022.pacs008.v10`)
- Support transformation between versions where needed
- Document the upgrade process in the architecture decisions

---

### Finding ISO-15: No Interoperability Testing Requirement [LOW]

**Location:** Demonstration requirements (section 10), acceptance criteria across sub-systems

**Issue:** The system is designed to interoperate with external CSMs (TIPS, RT1, NICS) and external PSPs (for VoP). The acceptance criteria test internal behavior but do not test interoperability:
- Can the system produce a pacs.008 that XMLdation validates?
- Can the system parse a pacs.002 from a real CSM (sample messages available from SWIFT, EPC)?
- Does the camt.053 output pass EPC/SWIFT validation tools?

**Impact:** The system may produce internally consistent but externally non-conformant messages.

**Recommendation:** Add interoperability test requirements:
- Validate generated messages using XMLdation or equivalent ISO 20022 validation tool
- Parse and correctly handle sample messages from EPC/SWIFT test suites
- Include sample ISO 20022 messages in the test fixtures

---

## 2. Certification Obstacles

### Finding CERT-01: No Formal Certification Target Identified [CRITICAL]

**Issue:** The requirements do not identify which certification or conformance program the system should target. ISO 20022 itself has no certification -- compliance is assessed per scheme:

| Possible Certification Target | What It Requires | Relevance |
|-------------------------------|-----------------|-----------|
| **EPC SEPA SCT Adherence** | Conform to SCT Rulebook, pass CSM onboarding tests | Required if the system processes real SEPA credit transfers |
| **EPC SEPA SCT Inst Adherence** | Conform to SCT Inst Rulebook, pass RT1/TIPS testing | Required for instant payments |
| **EPC SEPA R2P Scheme** | Conform to SRTP Rulebook (EPC014-20) | Required if A3 claims SRTP compliance |
| **EPC VoP Scheme** | Conform to VoP scheme rules | Required under EU IPR |
| **SWIFT CBPR+ Readiness** | Self-attestation + testing via SWIFT MyStandards | Required for cross-border ISO 20022 |
| **PCI DSS** | Cardholder data protection standard | Not directly applicable (no card data) but often expected |
| **ISO 27001** | Information security management | Often required by financial regulators |

Without a target, the requirements cannot be evaluated for sufficiency.

**Impact:** It is impossible to determine if the requirements are "enough" without knowing "enough for what." A portfolio demonstration has different requirements than an EPC scheme participant.

**Recommendation:** Add a section to system_requirements.md explicitly stating:
- Primary target: **portfolio demonstration** of ISO 20022 competence (not formal scheme participation)
- Secondary target: **structure requirements such that formal EPC SCT/SCT Inst conformance testing could be pursued** with incremental additions
- This framing resolves ambiguity and sets appropriate expectations while preserving the path to formal compliance

---

### Finding CERT-02: Sanctions Screening Lacks Regulatory-Grade Audit Trail Specification [CRITICAL]

**Location:** A5-S06, SEC-07, SAN-F07 through SAN-F11

**Issue:** For sanctions screening, the audit trail is not just operationally useful -- it is *legally mandated evidence*. If a compliance analyst clears a payment that later turns out to involve a sanctioned entity, the audit trail is the primary defense. Regulators (and potentially prosecutors) will examine:

1. **Which version** of the sanctions list was active at the time of screening?
2. **Which fields** were screened (name? IBAN? country? all three?)
3. **What was the screening result** (exact similarity score, which algorithm, which list entry matched)?
4. **What was the analyst's justification** for a false-positive determination?
5. **How long** after list publication was the screening performed (within the 1-hour update SLA)?

The requirements address some of these (SEC-05 in A5: "traceable to specific list version"; SAN-F11: "analyst decision logged") but do not specify the complete audit record structure.

**Impact:** In a certification or regulatory audit, the first question is "show me the screening audit trail for transaction X." If the audit record is incomplete, the entire sanctions screening implementation may be deemed non-compliant regardless of its technical sophistication.

**Recommendation:** Add a detailed sanctions screening audit record specification:
```
sanctions_audit_record:
  transaction_id:           UUID
  uetr:                     UUID (payment UETR)
  screening_timestamp:      Instant (UTC)
  list_version:             String (EU list publication date + version)
  list_ingestion_timestamp: Instant (when the active list was loaded)
  originator_screened:      Boolean
  beneficiary_screened:     Boolean
  fields_screened:          Set<String> (NAME, IBAN, COUNTRY)
  screening_algorithm:      String (BLOOM_FILTER, AHO_CORASICK, JARO_WINKLER)
  result:                   Enum (CLEAR, POTENTIAL_HIT, HIT)
  matched_entity:           String (nullable -- list entry ID if hit)
  similarity_score:         Double (nullable)
  screening_latency_ms:     Long
  decision:                 Enum (AUTO_CLEAR, ANALYST_REVIEW, etc.)
  analyst_id:               String (nullable)
  analyst_decision:         String (nullable)
  analyst_justification:    String (nullable)
  resolution_timestamp:     Instant (nullable)
```

---

### Finding CERT-03: No Data Retention Requirements [CRITICAL]

**Location:** All sub-systems

**Issue:** Financial systems have mandatory data retention periods that vary by data type and jurisdiction:

| Data Type | EU Requirement | Norwegian Requirement |
|-----------|---------------|----------------------|
| Transaction records | 5 years (AML Directive) | 5 years (hvitvaskingsloven) |
| Sanctions screening records | 5 years after end of business relationship | 5 years |
| Consent records (PSD2) | Duration of consent + regulatory retention | Same |
| Audit logs | Varies; typically 5-7 years | Typically 5 years |
| Kafka events | Not specified in regulation | Must be derivable |

The requirements specify Kafka event retention ("7 days" in instant_payments.md for Kafka topic config) but never specify data retention for:
- PostgreSQL records (transactions, alerts, consents, mandates)
- Kafka events (the event store for A1 -- must be retained indefinitely if it is the source of truth)
- Audit trail entries
- Sanctions screening records

**Impact:** In a certification audit, the auditor will ask: "Can you produce the sanctions screening record for a transaction from 3 years ago?" Without retention requirements, the answer may be "the Kafka topic was compacted" or "the database was pruned."

The conflict is particularly acute for A1 (event-sourced ledger): the requirements say events are the source of truth (LED-F01, LED-S05: "Events in Kafka are immutable -- once published, cannot be modified or deleted") but Kafka topics have default retention. If `ledger.events` has a 7-day retention, the source of truth is destroyed after 7 days.

**Recommendation:** Add data retention requirements:
- DATA-09: "Kafka topic `ledger.events` must have infinite retention (retention.ms=-1) or be archived to durable storage. This topic is the source of truth for account balances."
- DATA-10: "All PostgreSQL records related to transactions, sanctions screening, and audit must be retained for a minimum of 5 years."
- DATA-11: "Kafka topics other than `ledger.events` must retain events for at least 30 days. After 30 days, events must be archived or be derivable from PostgreSQL records."

---

### Finding CERT-04: VoP Scheme Conformance Gap [HIGH]

**Location:** A6/VoP requirements, UC-09

**Issue:** The EPC published the SEPA Verification of Payee (VoP) Scheme Rulebook (EPC221-23) defining the operational rules for VoP. The system's VoP implementation (A6) is well-designed but deviates from the scheme in several ways:

1. **Matching algorithm**: The scheme does not mandate a specific algorithm but defines the match result categories and response format. Our system uses Jaro-Winkler with fixed thresholds (0.95/0.85). The scheme allows PSPs to choose their algorithm but requires that results be reported in scheme-defined categories (Match, Close Match, No Match, etc.). This is acceptable.

2. **Response format**: The scheme defines specific API message formats (currently being finalized). Our system uses a custom REST API. For a portfolio project, this is acceptable. For certification, the scheme-defined API format would be required.

3. **Inter-PSP communication**: The scheme defines how PSPs communicate with each other for VoP queries. Our system simulates this as an internal lookup (A6 queries A1 directly). This is explicitly acknowledged ("simulated as internal lookup" in UC-09) and is appropriate for a portfolio demonstration. However, the requirements should note that a production deployment would need:
   - Integration with a VoP routing service (central directory)
   - Support for the scheme's messaging format
   - Participation agreement with the VoP scheme operator

**Impact:** The VoP implementation is functionally correct but would not pass EPC VoP scheme conformance testing without API format changes and inter-PSP communication integration.

**Recommendation:** Add a note to the VoP requirements explicitly documenting the gap between internal simulation and scheme conformance, and listing what would need to change for production deployment.

---

### Finding CERT-05: No Disaster Recovery / Business Continuity Requirements [HIGH]

**Location:** All sub-systems

**Issue:** The EU IPR requires 24/7/365 availability for instant payments (OPS-08, A2-F21). The requirements specify availability targets (99.99% for instant path, 99.99% for VoP) but do not specify disaster recovery or business continuity requirements:

- **RTO** (Recovery Time Objective): How fast must the system recover after a failure?
- **RPO** (Recovery Point Objective): How much data can be lost?
- **Failover**: Is there a standby deployment? Multi-region?
- **Data backup**: What is backed up, how often, where?

For a certification audit (ISO 27001, ECB oversight expectations for CSMs), DR/BCP documentation is mandatory.

**Impact:** A 99.99% availability target without DR/BCP requirements is an aspiration, not a verifiable requirement.

**Recommendation:** Add DR/BCP requirements to the cross-cutting section:
- OPS-09: "RTO for instant payment path: < 30 minutes"
- OPS-10: "RPO for all financial data: 0 (zero data loss, ensured by Kafka replication factor 3 and synchronous database writes)"
- OPS-11: "All PostgreSQL instances must have automated daily backups with 30-day retention"
- OPS-12: "Kafka cluster must use replication factor >= 3 with min.insync.replicas >= 2"

---

### Finding CERT-06: PCI DSS Scope Not Assessed [HIGH]

**Location:** Cross-cutting security requirements (SEC-01 through SEC-12)

**Issue:** The security requirements are solid but do not address PCI DSS. While PCI DSS primarily concerns cardholder data (PANs, CVVs), the system handles IBANs and account numbers which are considered sensitive financial data. Some certification bodies and banking regulators apply PCI DSS principles to account data even in non-card environments.

More importantly, the EUR 100,000 transaction limit (A2-F22) and the handling of large-value payments may bring the system under scrutiny for financial crime controls that overlap with PCI DSS network segmentation and access control requirements.

**Impact:** A certification auditor may ask "is PCI DSS in scope?" and the requirements provide no answer.

**Recommendation:** Add a brief statement to the security section: "PCI DSS is not in scope as the system does not process payment card data (PANs, CVVs, track data). The system handles IBANs and bank account numbers, which are protected under GDPR and AML requirements rather than PCI DSS."

---

### Finding CERT-07: Key Management Not Specified [MEDIUM]

**Location:** A4-S01 (RS256 JWT signing), A2-S01/S02 (HMAC/digital signatures), CLR-S01/S02

**Issue:** The system uses cryptographic keys in several places:
- JWT signing keys (RS256) in A4
- HMAC keys for webhook signatures in A3
- Digital signatures on settlement files in A2
- mTLS certificates for TPP authentication in A4 and VoP in A6
- Potential JWE encryption keys for sensitive response fields

No requirement specifies:
- Key generation procedures
- Key storage (HSM? Kubernetes Secrets? Vault?)
- Key rotation schedule
- Key revocation and re-issuance
- Key escrow or backup
- Minimum key lengths (RSA 2048? 4096?)

**Impact:** In a certification audit, key management is always examined. SEC-05 says "no secrets in source code" but doesn't say where they should be.

**Recommendation:** Add key management requirements:
- SEC-13: "RSA keys for JWT signing must be minimum 2048 bits. Key rotation: publish new key in JWKS, retire old key after max token lifetime (15 min)"
- SEC-14: "HMAC secrets for webhook signing must be minimum 256 bits, generated using a CSPRNG"
- SEC-15: "All cryptographic keys must be stored in Kubernetes Secrets or a dedicated secrets manager (e.g., HashiCorp Vault). HSM is recommended for production signing keys"

---

### Finding CERT-08: No Penetration Testing or Security Assessment Requirement [MEDIUM]

**Location:** Cross-cutting requirements

**Issue:** The security requirements cover defenses (OWASP headers, input validation, rate limiting, encryption) but do not require offensive security testing:
- No penetration testing requirement
- No OWASP ASVS (Application Security Verification Standard) self-assessment
- No threat modeling requirement (though docs/security/threat model is in the project structure)

For PSD2 compliance, the EBA requires ASPSPs to maintain "appropriate and up-to-date security measures" including "regular testing" (EBA Guidelines on ICT and security risk management, EBA/GL/2019/04).

**Recommendation:** Add to the cross-cutting requirements:
- SEC-16: "OWASP ASVS Level 2 self-assessment for all external-facing APIs"
- SEC-17: "Annual penetration test of OAuth2 flows and payment initiation APIs"
- Document the threat model in docs/security/ as referenced in the project structure

---

## 3. Requirements Quality for Certification

### Finding RQ-01: Inconsistent Requirement ID Numbering [CRITICAL for traceability]

**Location:** Cross-cutting between master system_requirements.md and sub-project requirements

**Issue:** The requirements use two parallel numbering systems:
- **Master system**: A1-F01 through A6-F10, SEC-01 through SEC-12, OPS-01 through OPS-08, DATA-01 through DATA-06
- **Sub-projects**: LED-F01 through LED-F42, CLR-B01 through CLR-L06, RTP-F01 through RTP-W08, VOP-R01 through VOP-I03, SAN-F01 through SAN-F14, FRD-F01 through FRD-F10, etc.

NAVIGATION.md provides a mapping (e.g., "A1-F* -> LED-F*") but the mapping is not one-to-one:
- Master A1-F01 through A1-F17 = 17 requirements
- Sub-project LED-F01 through LED-F42 = 42 requirements (more detailed)

This means sub-project requirements contain functionality not captured at the master level. For example:
- LED-F35 (point-in-time balance query) has no master equivalent
- LED-F40 through LED-F42 (snapshot mechanism) have no master equivalent
- CLR-I13 (settlement finality event) has no master equivalent
- VOP-E01 through VOP-E04 (anti-enumeration) have no explicit master counterpart

**Impact:** In a certification context, traceability is paramount. An auditor must be able to trace every requirement to a test case and every test case to a requirement. The dual-numbering with imperfect mapping creates ambiguity about which document is authoritative.

**Recommendation:** Declare one level as authoritative (recommend sub-project requirements as authoritative for detail, master as the summary view). Add a formal traceability matrix mapping each sub-project requirement to its master system source (or noting it as "sub-project only, elaboration of [master ID]").

---

### Finding RQ-02: Inconsistent Threshold Values Between Documents [HIGH]

**Location:** VoP name matching thresholds

**Issue:** The Jaro-Winkler thresholds for VoP matching differ between documents:

| Source | MATCH threshold | CLOSE_MATCH threshold |
|--------|----------------|----------------------|
| system_requirements.md section 8.4 | >= 0.95 | >= 0.85 and < 0.95 |
| extensions/verification_of_payee.md | >= 0.95 | >= 0.85 and < 0.95 |
| VOP-N07 (sub-project requirements) | >= 0.95 | >= 0.85 |
| UC-09 Scenario 9A (close match example) | Score 0.92 -> CLOSE_MATCH | Consistent |
| extensions/verification_of_payee.md algorithm example | Score 0.97 -> CLOSE_MATCH ("threshold >= 0.92") | **INCONSISTENT** |

In the VoP extension document, the algorithm example says "threshold >= 0.92 -> CLOSE_MATCH" while the threshold table on the same page says ">= 0.85 and < 0.95 -> CLOSE_MATCH" and ">= 0.95 -> MATCH". By the table, 0.92 would be CLOSE_MATCH, and 0.97 would be MATCH. But the example text says 0.97 is CLOSE_MATCH with "threshold >= 0.92".

This is a minor confusion in the example text (the table is consistent across documents), but it illustrates how threshold values can diverge across documents.

**Impact:** A developer reading only the extension document's example would implement different thresholds than one reading the requirements table.

**Recommendation:** Declare VOP-N07 as the authoritative threshold specification. Review and correct the example in extensions/verification_of_payee.md to use the correct thresholds from the table.

---

### Finding RQ-03: Fraud Score Adjustment Inconsistency [HIGH]

**Location:** VoP-fraud integration, multiple documents

**Issue:** The fraud score adjustment for VoP NO_MATCH override varies:

| Source | NO_MATCH override adjustment |
|--------|------------------------------|
| system_requirements.md A5-F10 | "NO_MATCH override feeds into fraud score as high-risk signal" (no specific value) |
| UC-01 AF-01 step 4b | "VoP result forwarded to A5 as HIGH_RISK signal" (no specific value) |
| UC-03 AF-05 step 2b | "+0.3 risk" |
| UC-05 Scenario 5F table | "+0.3 (significant risk)" for NO_MATCH override |
| UC-09 Scenario 9C table | "+0.3" for NO_MATCH override |
| VOP-I02 (sub-project) | "+0.3" for NO_MATCH, "+0.4" for NOT_FOUND |
| FRD-F06 (sub-project) | "+0.3" for NO_MATCH override |
| VEL-09 (velocity rule) | Severity weight 0.3 for VoP override |

The NO_MATCH value (+0.3) is consistent across documents that specify it. However, the NOT_FOUND override value appears only in VOP-I02 (+0.4) and UC-09 Scenario 9C (+0.4), and UC-05 Scenario 5F (+0.4). It is not mentioned in FRD-F06 or VEL-09.

Additionally, the CLOSE_MATCH ignore/override adjustment differs:
- UC-05 Scenario 5F: "+0.1 (mild risk)"
- UC-09 Scenario 9C: "+0.1"
- VOP-I03 (sub-project): "+0.1" (marked "Should")
- Not mentioned in FRD-F06 at all

**Impact:** The core value (+0.3 for NO_MATCH) is consistent, which is good. The edge cases (NOT_FOUND, CLOSE_MATCH ignore) are inconsistently documented across layers. This is a low-risk inconsistency since the sub-project requirements (VOP-I02, VOP-I03) are the most specific.

**Recommendation:** FRD-F06 should be updated to include all three adjustments: NO_MATCH override (+0.3), NOT_FOUND override (+0.4), CLOSE_MATCH ignore (+0.1). This makes the fraud engine requirements self-contained.

---

### Finding RQ-04: Acceptance Criteria Missing for Several Requirements [MEDIUM]

**Location:** Master system_requirements.md

**Issue:** The master system_requirements.md defines requirements but has no acceptance criteria. Acceptance criteria exist in the sub-project requirements (AC-01 through AC-10 per sub-project) but not at the system level. For cross-cutting requirements (SEC-*, OPS-*, DATA-*), there are no acceptance criteria anywhere.

Examples of untestable cross-cutting requirements:
- SEC-03: "All sensitive data at rest must be encrypted" -- how is this verified? Which data is "sensitive"?
- SEC-07: "Structured audit logging for all state-changing operations" -- what constitutes a "state-changing operation"?
- OPS-03: "All services must start independently" -- does this mean they start without errors, or they start and function correctly?

**Impact:** Without acceptance criteria, requirements cannot be verified during testing or certification. An auditor asking "how did you verify SEC-03?" would receive no structured answer.

**Recommendation:** Add acceptance criteria for all cross-cutting requirements, or reference the sub-project ACs that collectively verify each cross-cutting requirement.

---

### Finding RQ-05: "Should" Requirements Not Assessed for Certification Impact [MEDIUM]

**Location:** All requirements documents

**Issue:** The requirements use MoSCoW prioritization (Must/Should). Several "Should" requirements are actually mandatory for certification:

| Requirement | Priority | Certification Impact |
|-------------|----------|---------------------|
| A2-F29 / CLR-I11 (pacs.028 status request) | Should | Required for SEPA SCT Inst scheme compliance -- CSMs expect status inquiry capability |
| A2-F30 / CLR-I12 (pacs.004 payment return) | Should | Payment returns are operationally mandatory -- cannot run without them |
| A1-F13 / LED-F34 (camt.053 statement) | Should | Required for automated reconciliation and regulatory reporting |
| A5-F29 / SAN-F13 (retrospective screening) | Should | Regulators expect retrospective screening when lists update |
| VOP-R06 (proceed on VoP timeout) | Should | Required by EU IPR -- VoP failure must not block payments |
| A3-F12 / RTP-F11 (pain.013/014 support) | Should | Required for SRTP scheme participation |

**Impact:** If "Should" requirements are deferred to save implementation time, the system may miss certification-critical functionality.

**Recommendation:** Review all "Should" requirements and re-classify those that are mandatory for any certification target as "Must". Alternatively, add a "Certification Priority" column alongside the implementation priority.

---

## 4. Cross-reference Consistency Analysis

### Finding XR-01: Kafka Topic `payment.instant-confirmation` Not in Master [HIGH]

**Location:** extensions/instant_payments.md vs. system_requirements.md section 9.1

**Issue:** The instant payments extension document (instant_payments.md) lists a Kafka topic `payment.instant-confirmation` for pacs.002 confirmations. This topic does not appear in the master Kafka topology (section 9.1 of system_requirements.md) or in NAVIGATION.md's Kafka cross-reference table.

The master topology has `payment.status-updates` (consumed by A3, A4) which serves a similar purpose. It's unclear whether instant payment confirmations use the existing `payment.status-updates` topic or a dedicated `payment.instant-confirmation` topic.

**Impact:** Ambiguity in the Kafka topology for the most latency-sensitive flow. If the clearing engine publishes to a topic that no consumer is listening on, instant payment confirmations are lost.

**Recommendation:** Resolve the ambiguity: either add `payment.instant-confirmation` to the master topology and NAVIGATION.md, or explicitly state that instant confirmations use `payment.status-updates`. The latter is simpler and consistent with the current architecture.

---

### Finding XR-02: AUTH-F16 Out of Sequence [MEDIUM]

**Location:** 02_Open_Banking_PSD2_API/requirements/requirements.md

**Issue:** Requirement AUTH-F16 ("Payment status webhooks to TPPs") appears between AUTH-F12 and AUTH-F13 in the PSD2 requirements. The numbering is: AUTH-F01 through AUTH-F12, AUTH-F16, AUTH-F13, AUTH-F14, AUTH-F15. This suggests AUTH-F16 was added later without renumbering.

**Impact:** Minor. Does not affect functionality but suggests ad-hoc additions that could indicate incomplete review of the requirements after updates.

**Recommendation:** Renumber to restore sequential order: move AUTH-F16 to AUTH-F17 (since AUTH-F13-F15 are PKCE, JWKS, and OIDC discovery which are foundational, while webhook subscriptions are an enhancement).

---

### Finding XR-03: VoP Timing Inconsistency in Flow Diagrams [LOW]

**Location:** UC-08 (instant payment time budget) vs. UC-01 (invoice payment flow)

**Issue:** In UC-08, the VoP check happens at step 2 (0-300ms) before the payment instruction is published to Kafka. In UC-01, the VoP check happens at step 4, also before Kafka publication. However, in the data flow diagram in SUBSYSTEM_MAP.md, VoP (A6) is shown as a parallel step alongside the clearing engine path, which could be interpreted as occurring during clearing rather than before it.

The system_requirements.md section 9.3 (instant payment flow) shows VoP at step 2 (before Kafka), which is consistent with UC-08.

**Impact:** Minor. The use cases are consistent with each other. The SUBSYSTEM_MAP diagram is a simplification. No functional ambiguity.

**Recommendation:** No change needed. The use cases are authoritative for the flow sequence; the diagram is a logical view, not a sequence diagram.

---

## 5. Use Case Review -- ISO 20022 and Certification Lens

### UC-01 (Invoice Payment): ADEQUATE with gaps
- Good: Complete happy path with VoP, sanctions, fraud, settlement, webhook
- Good: 11 alternative flows covering major error cases
- Gap: No ISO 20022 message examples (unlike UC-08 which has pacs.002 examples)
- Gap: No mention of UETR generation
- Gap: Data requirements use internal field names only; no mapping to ISO 20022 elements
- **Certification concern:** The data model lists `reference: String (KID number or structured reference)` -- in ISO 20022, this maps to `RemittanceInformation/Structured/CreditorReferenceInformation/Reference`. The mapping is undocumented.

### UC-02 (PSD2 Account Access): GOOD
- Complete OAuth2 flow with all alternative paths
- PSD3 forward-looking note (consent reauth configurability) is excellent
- No ISO 20022 concerns (AISP is pure REST, no clearing messages)
- **Certification concern:** Consent expiry is hardcoded at 90 days (PSD2 Art. 10). The configurable parameter suggestion is good but not captured as a formal requirement.

### UC-03 (PSD2 Payment Initiation): GOOD with certification-relevant gap
- Excellent SCA exemption scenarios with specific thresholds and EBA references
- TRA thresholds correctly quoted (EBA RTS Article 18)
- **Certification concern:** The low-value cumulative tracking (EXE-F01, AF-01) resets after SCA. The requirement says "5 count limit" and "EUR 100 amount limit" but does not specify that these are *contactless* payment limits under PSD2 RTS. For remote electronic payments, the thresholds are different. This conflation could cause a certification issue.

### UC-04 (Batch Clearing): ADEQUATE
- Netting example is excellent (concrete numbers, correct arithmetic)
- **Certification concern:** The fraud timeout behavior (AF-01: "A2 scores as UNKNOWN, transaction proceeds") contradicts A5-F28 / SAN-F12 which says sanctions cannot be skipped. UC-04 AF-01 describes fraud timeout but does not explicitly separate fraud timeout from sanctions timeout. It says "Fraud Detection does not respond within 10 seconds" -- does this mean only fraud is timed out, or fraud+sanctions together? UC-08 AF-05 correctly separates them.

### UC-05 (Fraud Detection): EXCELLENT
- Most detailed use case in the corpus
- Six scenarios covering automated scoring, case management, velocity rules, anomaly detection, sanctions screening, and VoP integration
- "Sanctions screening cannot be skipped" table is the clearest statement of this critical requirement
- **Minor gap:** The score aggregation formula (Scenario 5A step 5: weighted_average) uses different weights than the sub-project requirements (FRD section 3.4). UC-05 shows `weighted_average(velocity=0.3 * 0.3, anomaly=0.4 * 0.4, ml=0.25 * 0.3) = 0.325` while the sub-project shows `w_velocity=0.3, w_anomaly=0.3, w_ml=0.3` (equal weights). The UC-05 example uses unequal weights without explanation.

### UC-06 (Recurring Direct Debit): GOOD
- Excellent comparison table between AvtaleGiro and SEPA Direct Debit
- Clear rationale for choosing AvtaleGiro model
- **Certification concern:** If SEPA SDD is ever added (documented as future extension), the mandate model would need significant changes. The current mandate entity lacks fields required by SEPA SDD: signed mandate reference, sequence type (FRST/RCUR/FNAL/OOFF), pre-notification date, refund eligibility.

### UC-07 (Account Management): ADEQUATE
- Covers essential lifecycle (create, freeze, unfreeze, close)
- **Gap:** No mention of account closure restrictions (what if the account has pending instant payment reservations? Active mandates? Active PSD2 consents?)
- **Gap:** No KYC/onboarding requirements (regulatory requirement for account opening)

### UC-08 (Instant Payment): EXCELLENT
- Best use case in the corpus for certification purposes
- Detailed time budget with millisecond allocations
- All failure scenarios covered with explicit timeout behavior
- pacs.002 examples (both positive and negative) -- the only use case with ISO 20022 examples
- Batch vs. instant comparison table is valuable
- Monitoring metrics with alert thresholds
- **Certification concern:** The EUR 100,000 limit (precondition) is stated as configurable. Under the EU IPR, the limit was removed effective January 2025. The system should clarify whether this is a configurable risk limit (acceptable) or a scheme limit (incorrect after IPR). The extension document (instant_payments.md IP-F03) says "EUR 100,000 (configurable)" which is appropriate.

### UC-09 (Verification of Payee): GOOD
- Both requester and responder roles covered
- Anti-enumeration measures documented
- Privacy rules (no name on NO_MATCH) correctly implemented
- **Certification concern:** The VoP scheme (EPC221-23) defines specific technical message formats for inter-PSP communication. The use case uses a custom REST API, which is appropriate for internal simulation but would need to be replaced for scheme conformance.

### UC-10 (Variable Recurring Payments): GOOD
- Clear comparison table (SDD vs. AvtaleGiro vs. VRP)
- Parameter enforcement well-specified
- Sweeping use case demonstrates understanding of the UK CMA mandate
- **Certification concern:** VRP has no EU regulatory mandate. This is correctly noted ("No regulatory mandate yet -- DIFFERENTIATING"). No certification concern.

---

## 6. Positive Findings

The review must acknowledge the substantial strengths of the documentation:

### POS-01: Dual-mode Architecture is Sound
The decision to separate batch (Spring Batch) and instant (Kafka Streams) processing paths with dedicated Kafka topics is architecturally correct and mirrors how real CSMs (TIPS + TARGET, RT1 + STEP2) operate. This is not trivial to get right and demonstrates deep domain understanding.

### POS-02: Sanctions Screening Cannot Be Skipped -- Consistently Enforced
This critical rule is stated in: system_requirements.md (SEC-11), A2-S05, A5-F28, SAN-F12, CLR-S05, CLR-I07, UC-05 Scenario 5E (explicit comparison table), UC-08 AF-05/AF-06. The consistency across documents is excellent. This is the single most important compliance rule in the system and it is well-handled.

### POS-03: Balance Reservation Pattern for Instant Payments
The three-state pattern (AVAILABLE -> RESERVED -> SETTLED/RELEASED) with timeout auto-release (LED-F14) is correct and mirrors TIPS DCA reservation. The concurrency handling (LED-F15: SELECT FOR UPDATE SKIP LOCKED) is the right approach.

### POS-04: Cross-reference System is Exemplary
NAVIGATION.md with its four cross-reference tables (use cases -> sub-projects, requirement IDs -> sub-project locations, Kafka topics -> producers/consumers, extensions -> impacted requirements) is a sophisticated documentation practice. It prevents the "which document is authoritative?" problem that plagues most multi-document systems. It is the single best feature of the documentation.

### POS-05: Gap Analysis Methodology
The gap analysis (gap_analysis.md) with its systematic comparison matrix, priority classification (MANDATORY/EMERGING/DIFFERENTIATING), "exotic features justified" analysis, and phased implementation roadmap demonstrates a mature requirements engineering process. The intellectual honesty of examining whether features are genuinely needed or just impressive is rare.

### POS-06: Event Sourcing with Reconciliation
The combination of event sourcing (A1), external reference IDs on events (LED-F36), reconciliation status tracking (LED-F37), and the reconciliation API (LED-F38) -- all inspired by the Allevo FinTP analysis -- creates a system that can *prove* its financial consistency. This is invaluable for certification.

### POS-07: Semantic Duplicate Detection
The warn-but-don't-block approach to semantic duplicates (CLR-B13), with the override fed into fraud scoring (VEL-10, A5-F11), is a nuanced design decision. Most systems either ignore duplicates or hard-block them. The warn-and-score approach balances user experience with fraud prevention. This will resonate with payment domain experts reviewing the portfolio.

---

## 7. Recommendations and Roadmap

### Priority 1: ISO 20022 Message Profile (addresses ISO-01 through ISO-05, ISO-08, ISO-09)

Create a new document: `payment_processing/requirements/iso20022_message_profile.md` containing:
1. **Version matrix**: Which version of each message type, with XSD namespace URIs
2. **Mandatory field specification**: For each message type, which fields are mandatory in this system's usage (beyond XSD)
3. **UETR handling**: Generation, propagation, and storage
4. **External code lists**: Which version, which codes are used, mapping to/from internal codes
5. **Character set rules**: UTF-8 with SEPA restrictions, transliteration rules for Nordic characters
6. **IBAN validation**: Algorithm reference, Norwegian account number handling
7. **Structured address requirements**: When required, field mapping
8. **BIC handling**: Derivation, storage, usage in messages

This single document resolves 8 of the 15 ISO 20022 findings.

### Priority 2: Certification Positioning (addresses CERT-01, CERT-03)

Add a "Certification and Compliance" section to system_requirements.md:
1. **Explicit certification target**: Portfolio demonstration with path to EPC conformance
2. **Data retention requirements**: 5-year minimum for financial records, infinite for event store
3. **Sanctions audit trail specification**: Complete record structure

### Priority 3: Requirements Traceability (addresses RQ-01, RQ-04, RQ-05)

1. Declare sub-project requirements as authoritative for detail
2. Add traceability matrix (sub-project ID -> master ID)
3. Review all "Should" requirements for certification impact
4. Add acceptance criteria for cross-cutting requirements

### Priority 4: Consistency Fixes (addresses RQ-02, RQ-03, XR-01, XR-02)

1. Fix VoP threshold example in extensions/verification_of_payee.md
2. Update FRD-F06 to include all three VoP-fraud adjustments
3. Resolve `payment.instant-confirmation` topic ambiguity
4. Fix AUTH-F16 numbering

---

## Appendix: ISO 20022 Message Version Matrix (Recommended)

This table should be added to the requirements. It aligns with SEPA 2025+ scheme rules.

| Message Type | Full ID | Purpose in System | Producer | Consumer | Priority |
|---|---|---|---|---|---|
| pain.001 | pain.001.001.11 | Customer credit transfer initiation | A3, A4 | A2 | Must |
| pacs.008 | pacs.008.001.10 | FI-to-FI credit transfer (clearing) | A2 | (external CSM) | Must |
| pacs.002 | pacs.002.001.12 | Payment status report (confirmation/rejection) | A2 | A3, A4 | Must |
| pacs.004 | pacs.004.001.11 | Payment return | A2 | A1, A3, A4 | Should |
| pacs.028 | pacs.028.001.05 | Payment status request (inquiry) | A3, A4 | A2 | Should |
| pain.013 | pain.013.001.09 | Creditor payment activation request (R2P) | A3 | (external) | Should |
| pain.014 | pain.014.001.09 | Creditor payment activation request status | (external) | A3 | Should |
| camt.053 | camt.053.001.10 | Bank-to-customer statement | A1, A2 | (external, reconciliation) | Must |

### Key Field Requirements Per Message (Summary)

**pacs.008.001.10 (credit transfer) -- minimum viable for SEPA SCT Inst:**
- GroupHeader: MsgId (unique), CreDtTm, NbOfTxs, SttlmInf/SttlmMtd=CLRG
- PaymentTypeInfo: SvcLvl/Cd=INST, LclInstrm/Cd=INST (for SCT Inst)
- CdtTrfTxInf: PmtId/InstrId, PmtId/EndToEndId, PmtId/UETR, IntrBkSttlmAmt + Ccy, ChrgBr=SLEV
- Debtor: Nm, PstlAdr (structured for cross-border), Id (optional)
- DebtorAccount: Id/IBAN
- DebtorAgent: FinInstnId/BICFI
- Creditor: Nm, PstlAdr (structured for cross-border)
- CreditorAccount: Id/IBAN
- CreditorAgent: FinInstnId/BICFI
- RmtInf: Strd or Ustrd (remittance information)

**pacs.002.001.12 (status report) -- minimum viable:**
- GroupHeader: MsgId, CreDtTm
- OrgnlGrpInfAndSts: OrgnlMsgId, OrgnlMsgNmId
- TxInfAndSts: OrgnlEndToEndId, UETR, TxSts (ACSC/RJCT/PDNG), StsRsnInf (if RJCT), AccptncDtTm, InstgAgt, InstdAgt

---

*End of review. 31 findings: 9 critical, 12 high, 8 medium, 2 low. The system architecture is sound. The ISO 20022 treatment needs substantial deepening for any certification path. The priority recommendations are additive (new documents and requirements) rather than corrective (changing existing architecture), which is a strong position to be in.*
