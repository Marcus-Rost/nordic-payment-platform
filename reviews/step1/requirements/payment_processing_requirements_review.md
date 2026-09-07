# Requirements & Security Review: Nordic Payment Processing Infrastructure

**Reviewer:** Claude Opus 4.6  
**Date:** 2026-04-11  
**Scope:** Complete review of System A requirements, use cases, extensions, and gap analyses  
**Documents reviewed:** 40+ files across payment_processing/ and 6 sub-project folders  
**Focus:** Requirements quality, completeness, internal consistency, and **security / cyber-crime / hacker resilience**

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Overall Assessment](#2-overall-assessment)
3. [Security Review — Critical Findings](#3-security-review--critical-findings)
4. [Security Review — Notable Strengths](#4-security-review--notable-strengths)
5. [Requirements Quality Review](#5-requirements-quality-review)
6. [Use Case Review](#6-use-case-review)
7. [Cross-Subsystem Consistency Review](#7-cross-subsystem-consistency-review)
8. [Gap Analysis Review](#8-gap-analysis-review)
9. [Detailed Security Threat Analysis](#9-detailed-security-threat-analysis)
10. [Recommendations Summary](#10-recommendations-summary)
11. [Appendix: Requirement Coverage Matrix](#11-appendix-requirement-coverage-matrix)

---

## 1. Executive Summary

The Nordic Payment Processing Infrastructure is a remarkably thorough and well-architected set of requirements for a 6-subsystem payment platform. The documentation demonstrates deep domain knowledge of EU payment regulation (PSD2, IPR 2024/886, AML Package), Nordic payment infrastructure (NICS, AvtaleGiro, eFaktura), and modern security practices.

**Overall Quality Rating: VERY HIGH (8.5/10)**

The requirements are implementable, internally consistent, and regulation-aware. The security posture is strong — significantly above what is typical for portfolio-level projects. However, a thorough review reveals **14 security gaps** (3 critical, 5 high, 6 medium) that would be exploitable in a production deployment and should be addressed in the requirements before implementation begins.

**Key finding:** The requirements are excellent at defending against *known payment fraud patterns* (APP fraud, sanctions evasion, account takeover) but have gaps in *infrastructure-level attack vectors* (Kafka poisoning, API abuse, key management, incident response).

---

## 2. Overall Assessment

### 2.1 Strengths

| Area | Rating | Notes |
|------|--------|-------|
| Functional completeness | 9/10 | All major payment flows covered with detailed alternative flows |
| Regulatory alignment | 9/10 | PSD2, EU IPR, AML Package correctly interpreted |
| Security requirements | 8/10 | Strong application-level security; gaps at infrastructure and operational levels |
| Non-functional requirements | 8/10 | Concrete SLA targets, reasonable performance budgets |
| Internal consistency | 8.5/10 | Cross-references are accurate; Kafka topology is coherent |
| Use case quality | 9/10 | Exceptional detail — alternative flows, data models, security notes per UC |
| Gap analysis quality | 9/10 | Honest self-assessment, justified exotic features |
| Implementability | 8/10 | Sufficient detail for a team to begin implementation |

### 2.2 Areas for Improvement

| Area | Rating | Notes |
|------|--------|-------|
| Key and secret management | 4/10 | SEC-05 mentions it but no concrete design |
| Incident response requirements | 3/10 | Almost entirely absent |
| Data retention and destruction | 5/10 | Mentioned for sanctions (5 years) but not specified system-wide |
| Threat modeling | 5/10 | Implicit in design decisions but no explicit threat model |
| Service mesh / zero-trust networking | 4/10 | mTLS mentioned for TPP but not for internal service mesh |
| Deployment security | 4/10 | Docker/K8s mentioned but container security not specified |

---

## 3. Security Review -- Critical Findings

### CRITICAL-01: No Key Management Architecture

**Affected requirements:** SEC-05, A4-S01, A4-S02, A2-S01, A2-S02  
**Risk:** Key compromise leads to complete system takeover  
**CVSS equivalent:** 9.8

SEC-05 states *"No secrets in source code or environment variables. Use Spring Cloud Config or Kubernetes Secrets"* but the requirements lack:

- **Key rotation strategy**: The RS256 keys for JWT signing (A4-S02), HMAC keys for webhook signatures (A3-S03, A2-S01), and encryption keys for JWE (A4-S05) have no rotation requirements.  
- **Key hierarchy**: No distinction between root keys, signing keys, and encryption keys.  
- **HSM/KMS integration**: Payment systems handling real money typically require HSM-backed key storage (PCI DSS requirement 3.5). The requirements don't mention HSM, AWS KMS, or HashiCorp Vault.  
- **Key compromise response**: If the JWT signing key leaks, what happens? No revocation procedure is defined.  
- **Certificate lifecycle**: mTLS certificates for TPP identification (A4-S09) need issuance, renewal, revocation (CRL/OCSP). Not specified.

**Recommendation:** Add a dedicated key management section (KM-01 through KM-10) specifying:
- Minimum key lengths (RSA 2048+, ECDSA P-256+)
- Rotation intervals (JWT signing keys: 90 days, HMAC keys: 180 days)
- Key storage mechanism (KMS or HSM)
- Key compromise incident response procedure
- Certificate revocation checking for mTLS

### CRITICAL-02: Kafka Security is Underspecified

**Affected requirements:** SEC-01, all Kafka topics (Section 9.1)  
**Risk:** Message injection, replay, or tampering across all subsystems  
**CVSS equivalent:** 9.1

SEC-01 states *"All inter-service communication via Kafka must use SASL/SSL"* but this is insufficient for a payment system:

- **No message-level integrity**: SASL/SSL protects the transport channel, but a compromised Kafka broker or an operator with broker access could inject or modify messages. Payment instructions on `payment.instant-instructions` and settlement events on `payment.settlement-events` have no message-level signing. An attacker who compromises a Kafka broker could:
  - Inject fraudulent payment instructions
  - Modify settlement amounts
  - Replay old settlement events to cause double credits
  - Drop sanctions check requests to bypass screening

- **No ACLs specified**: Which service principals can produce/consume which topics? Without topic-level ACLs, any service that authenticates to Kafka can produce to `payment.settlement-events` and credit arbitrary accounts.

- **Schema Registry authentication**: The Avro Schema Registry is not mentioned as requiring authentication. An attacker who can modify schemas could alter event formats to bypass validation.

- **Consumer group isolation**: While CLR-NF05 mentions separate consumer groups for batch/instant, there are no requirements preventing a rogue consumer from joining a consumer group and stealing messages.

**Recommendation:** Add Kafka security requirements:
- KFK-S01: Topic-level ACLs (e.g., only A2 can produce to `payment.settlement-events`)
- KFK-S02: Message-level HMAC or digital signature on all payment instruction and settlement event messages
- KFK-S03: Schema Registry requires authentication and authorization
- KFK-S04: Consumer group naming convention enforced via ACLs
- KFK-S05: Message replay protection via monotonic sequence numbers per partition
- KFK-S06: Kafka broker access restricted to operations team; broker audit logging enabled

### CRITICAL-03: No Incident Response or Breach Notification Requirements

**Affected requirements:** None (absent)  
**Risk:** Regulatory non-compliance (GDPR Art. 33/34, PSD2 Art. 96), uncontrolled breach impact  
**CVSS equivalent:** N/A (process gap)

For a payment system handling PII and financial data, the requirements must specify:

- **Breach detection capability**: How is unauthorized access detected? Beyond audit logging, there's no mention of intrusion detection, log analysis, or anomaly detection on infrastructure access.
- **Breach notification timeline**: GDPR requires 72-hour notification to supervisory authority. PSD2 requires notification to competent authority without undue delay.
- **Incident classification**: What constitutes a security incident vs. operational issue?
- **Forensic readiness**: Audit logs must be tamper-evident (not just "immutable" — are they hash-chained? Stored in write-once storage?).
- **Account holder notification**: When must consumers be notified of account compromise?
- **Sanctions breach reporting**: If a sanctions-hit payment was accidentally settled, what is the remediation procedure?

**Recommendation:** Add incident response requirements section (IR-01 through IR-08).

---

## 4. Security Review -- Notable Strengths

The requirements demonstrate sophisticated understanding of payment security. These strengths should be preserved and built upon:

### 4.1 Sanctions Screening Cannot Be Skipped (A5-F28, A2-S05, CLR-S05)

**Excellent.** The hard rule that sanctions screening unavailability results in payment rejection (not auto-pass) is correct and legally required. The explicit contrast with fraud scoring (which *can* auto-pass on timeout) shows precise understanding of the different legal obligations. This is documented consistently across A5, A2, and the master requirements.

### 4.2 VoP Override as Fraud Signal (VOP-I02, FRD-F06, A5-F10, A5-F11)

**Outstanding design.** The integration between VoP and fraud detection — where a payer overriding a NO_MATCH warning adds +0.3 to the fraud score — is a production-grade pattern used by SurePay and UK CoP. The fact that a VoP override can independently push a borderline transaction (0.45) above the auto-block threshold (0.7) demonstrates correct calibration. The additional +0.4 for NOT_FOUND override is appropriately more severe.

### 4.3 Anti-Enumeration Protection (VOP-E01 through VOP-E04)

**Strong.** Rate limiting (100 queries/minute/PSP) plus pattern detection (>5 different names for same IBAN in 1 hour) addresses the primary attack vector against VoP services. The privacy rule (never reveal actual name on NO_MATCH) prevents VoP from becoming an IBAN-to-name oracle.

### 4.4 Refresh Token Rotation with Family Revocation (AUTH-F07, AUTH-F08, A4-S03)

**Best-practice implementation.** Single-use rotating refresh tokens with family-based revocation on reuse is the state-of-the-art defense against token theft. The requirement correctly identifies that reuse of an old refresh token indicates theft and triggers revocation of the entire family.

### 4.5 Dynamic Linking for SCA (PISP-F05, SCA-F04, A4-S06)

**Correct PSD2 implementation.** The requirement that TOTP challenges display payment amount and creditor name (not just a generic code request) implements PSD2 RTS Article 5 dynamic linking. UC-03 shows this in practice: *"Confirm payment of NOK 5,000.00 to Ola Nordmann. Enter TOTP code."*

### 4.6 Semantic Duplicate Detection (CLR-B13, A2-F12, A5-F11)

**Innovative and practical.** Detecting probable duplicates beyond idempotency keys (matching debtor+creditor+amount within 10 minutes) addresses a real-world problem. The "warn but don't block" approach is correct — many legitimate payments look like duplicates. The integration with fraud scoring (+0.2 for override) adds defense-in-depth against account takeover attacks.

### 4.7 Balance Reservation Architecture (LED-F10 through LED-F15)

**Well-designed for instant payments.** The three-state reservation model (AVAILABLE -> RESERVED -> SETTLED/RELEASED) with SELECT FOR UPDATE SKIP LOCKED for concurrency and configurable auto-release timeout prevents both double-spending and permanent fund locking. UC-01 Scenario 1D explicitly handles concurrent reservations — a critical correctness scenario.

### 4.8 Immutable Audit Trail (SEC-07, A2-S04, A4-S08, A5-S02)

**Comprehensive coverage.** Every security-relevant action — consent grants, SCA decisions, fraud scores, sanctions outcomes, case officer decisions — is required to be logged immutably. The requirements consistently specify *what* to log (who, what, when, from where, outcome).

---

## 5. Requirements Quality Review

### 5.1 Requirements Traceability

**Rating: EXCELLENT**

The cross-reference system is well-designed:
- Master requirement IDs (A1-F01, SEC-01, etc.) map to sub-project detail IDs (LED-F01, CLR-B01, etc.) via NAVIGATION.md
- Kafka topics are fully traced to producers, consumers, and requirement IDs
- Use cases reference specific requirements they implement

**Minor issue:** Some sub-project requirements (e.g., LED-F36 through LED-F39 for reconciliation) were added after the initial gap analysis and appear in the sub-project requirements but are not individually listed in the master system_requirements.md Section 3 (which stops at A1-F17). The master requirements cover reconciliation conceptually via A1-F15/F16/F17, but the detailed IDs diverge. This is acceptable for a portfolio project but could confuse a reviewer.

### 5.2 Priority Classification

**Rating: GOOD**

Requirements use Must/Should priority consistently. However:

- **Missing "Won't" classification**: Some features are discussed as "future extension" (e.g., SEPA Direct Debit, CBDC gateway) but not formally classified as Won't/Deferred. A MoSCoW classification would be clearer.
- **Implicit dependencies**: Some "Should" requirements have implicit dependencies on "Must" requirements. For example, camt.053 generation (A1-F13, Should) is actually needed by reconciliation (A1-F17, Must). The priority should be Must.

### 5.3 Testability

**Rating: VERY GOOD**

Requirements are generally testable with concrete acceptance criteria. Examples of highly testable requirements:
- A2-F20: "Process instant credit transfers end-to-end in < 10 seconds" — clear, measurable
- A5-F23: "Screening latency: < 50ms per transaction" — precise SLA
- VOP-N07: "MATCH >= 0.95, CLOSE_MATCH >= 0.85, NO_MATCH < 0.85" — exact thresholds

**Areas needing improvement for testability:**
- SEC-03: "All sensitive data at rest must be encrypted" — which fields are "sensitive"? Need an explicit list
- SEC-07: "Structured audit logging for all state-changing operations" — what is "state-changing"? Does reading an account balance count? (A1-S03 says yes, but the cross-cutting requirement is ambiguous)
- OPS-03: "All services must start independently" — how is this tested? What if Kafka isn't up?

### 5.4 Ambiguity Analysis

| Requirement | Ambiguity | Suggested clarification |
|------------|-----------|------------------------|
| SEC-03 "sensitive data" | Which fields exactly? | Define: account numbers, holder names, transaction amounts, IP addresses, tokens. Exclude: request IDs, status codes, timestamps |
| SEC-08 "Rate limiting on all external-facing APIs" | What limits? | Define defaults per subsystem (e.g., A4: 100 req/min/TPP, A6: 100 queries/min/PSP, A3: 1000 req/min/merchant) |
| A2-F22 "EUR 100,000 (configurable)" | Configurable by whom? At what level? | Specify: per-PSP configuration, admin-only, runtime-changeable via API or config file |
| A5-F09 "Configurable thresholds" | How configured? | Already partly addressed in sub-project (YAML/JSON or Drools). Elevate to master |
| OPS-06 "Configuration via environment variables" | What about secrets? | Conflict with SEC-05 which says "not environment variables for secrets" — clarify the boundary |

### 5.5 Missing Non-Functional Requirements

| Category | Gap | Recommendation |
|----------|-----|----------------|
| Data retention | No retention/deletion policy | Add DATA-07: Define retention periods per data type (transactions: 10 years, audit logs: 7 years, PII: per GDPR Art. 17 right to erasure) |
| Backup and recovery | No RPO/RTO targets | Add OPS-09: RPO < 1 hour, RTO < 4 hours for each subsystem |
| Capacity planning | No growth projections | Add OPS-10: System must handle 10x current load within 6 months with horizontal scaling |
| Internationalization | Limited to Nordic currencies | Acceptable for scope, but note that P27 handles NOK, SEK, DKK, EUR — is USD really needed? |
| Accessibility | Not mentioned | Add if consumer-facing UIs are in scope |

---

## 6. Use Case Review

### 6.1 Use Case Quality Assessment

| Use Case | Quality | Alternative Flows | Security Notes | Issues |
|----------|---------|-------------------|----------------|--------|
| UC-01 Invoice Payment | Excellent | 11 alternative flows | Good | None |
| UC-02 PSD2 Account Access | Excellent | 7 alternative flows | Excellent (security deep-dive) | None |
| UC-03 PSD2 Payment Initiation | Excellent | 11 alternative flows | Excellent (SCA exemption detail) | Minor: AF-05 VoP risk adjustment is +0.3 here but +0.3 in UC-09 and +0.3 in UC-05. Consistent |
| UC-04 Batch Clearing | Very Good | 6 alternative flows | Good | AF-01 fraud timeout says "UNKNOWN, not blocked" but proceeds — consistent with A5 design, but needs clearer risk acceptance statement |
| UC-05 Fraud Detection | Excellent | 6 scenarios | Excellent | Best use case in the set — comprehensive scoring examples |
| UC-06 Recurring Direct Debit | Very Good | 3 alternative flows | Good | AvtaleGiro vs SDD comparison is excellent |
| UC-07 Account Management | Good | 4 scenarios | Adequate | Thinnest use case — see findings below |
| UC-08 Instant Payment | Excellent | 8 alternative flows | Excellent | Time budget analysis is production-grade |
| UC-09 Verification of Payee | Excellent | 5 scenarios | Excellent (anti-enumeration) | None |
| UC-10 Variable Recurring Payments | Very Good | 5 scenarios | Good | None |

### 6.2 Use Case Gaps and Findings

**UC-07 (Account Management) — Missing Scenarios:**

1. **Account closure flow is incomplete**: The state diagram shows ACTIVE -> CLOSED but there are no preconditions (balance must be zero, no pending transactions, no active mandates, no active consents). What happens to incoming payments after closure? What about pending VRP consents?

2. **No account reopening**: Once CLOSED, can an account be reopened? If not, state that explicitly.

3. **Multi-party account access**: No mention of joint accounts, power of attorney, or corporate accounts with multiple authorized signers. For a portfolio project this may be acceptable, but it should be stated as out-of-scope.

4. **Account number portability**: Not mentioned. Acceptable for scope but worth noting.

**UC-01 and UC-08 — Timing Inconsistency:**

UC-01 Step 6 says the VoP check happens at Step 4 (before publishing to Kafka), but A2 also does semantic duplicate detection at Step 6. If VoP fails, does A2 never see the instruction? This is correct (instruction isn't published if VoP aborts), but the flow could be clearer about the VoP-check being a gate before Kafka publication vs. a parallel check.

**UC-04 (Batch Clearing) — Missing Alternative Flow:**

No flow for "batch clearing cycle interrupted mid-processing" (e.g., service crash, K8s pod eviction). Since batch clearing involves netting across 150+ transactions, a crash after partial processing could leave the system in an inconsistent state. Requirements should specify:
- Batch cycle atomicity (all-or-nothing)
- Recovery/restart behavior (replay from beginning of cycle, or checkpoint within cycle)
- What happens to payments in a crashed cycle — are they returned to the queue?

**UC-06 (Recurring) — Missing Attack Scenario:**

No consideration of a compromised merchant exploiting mandates. If a merchant's API key is stolen, the attacker could:
1. Submit invoices against all active mandates
2. These would be auto-approved (within mandate limits)
3. Payments would execute before the consumer notices

The mandate system trusts merchants to submit legitimate invoices. This is a design choice, but the risk should be acknowledged and mitigated:
- Add requirement: notifications to consumers on every auto-approved debit (mentioned in UC-06 but not a formal requirement)
- Add requirement: merchant API key rotation capability
- Add requirement: anomaly detection on mandate usage patterns (sudden burst of invoices from one merchant)

---

## 7. Cross-Subsystem Consistency Review

### 7.1 Kafka Topic Consistency

**Status: CONSISTENT**

All Kafka topics referenced in use cases match the topology in Section 9.1. Producer/consumer assignments are consistent across all documents. The separation of batch and instant topics is consistently maintained.

**One observation:** The `account.freeze-commands` topic (produced by A5, consumed by A1) appears in UC-05 and UC-07 but is not listed in the master Section 9.1 Kafka topology table. It should be added.

### 7.2 VoP Integration Consistency

**Status: CONSISTENT WITH ONE EXCEPTION**

The VoP risk adjustment values are consistent across documents:
- NO_MATCH + override: +0.3 (confirmed in UC-01, UC-03, UC-05, UC-08, UC-09, VOP-I02)
- NOT_FOUND + override: +0.4 (confirmed in UC-05, UC-09, VOP-I02)
- CLOSE_MATCH + ignore correction: +0.1 (confirmed in UC-05, UC-09, VOP-I03)

**Exception:** UC-03 AF-05 states VoP NO_MATCH override adds +0.3, but the same UC-03 "Security Considerations" section says "+0.3 risk" — this is consistent. However, the fraud score adjustment description uses different wording in different documents ("risk signal," "fraud score adjustment," "bonus"). Standardize terminology.

### 7.3 SCA Exemption Consistency

**Status: CONSISTENT**

Low-value thresholds (EUR 30, cumulative EUR 100, max 5 count), TRA thresholds (EBA RTS Article 18 brackets), trusted beneficiary, and recurring exemption rules are consistently described across UC-03, the PSD2 requirements, and the sub-project use cases.

### 7.4 Error Handling Consistency

**Status: MOSTLY CONSISTENT, ONE CONFLICT**

**Conflict:** UC-04 AF-01 states that when the fraud engine times out for a batch transaction, the transaction *proceeds* with UNKNOWN score. But A2-NF03 states "Instant payment fraud+sanctions check budget < 500ms" and CLR-I07 says "fraud timeout auto-pass with UNKNOWN score, sanctions timeout REJECT." The batch path timeout (30 seconds, per UC-04 Step 5) is much more generous, but what happens after 30 seconds? Is it also auto-pass? This needs explicit statement for the batch path.

### 7.5 ISO 20022 Usage Consistency

**Status: CONSISTENT**

Message types are used correctly:
- pain.001 (payment initiation) — inbound to A2
- pacs.008 (credit transfer) — internal and outbound
- pacs.002 (status report) — outbound confirmation/rejection
- pacs.004 (payment return) — return requests
- pacs.028 (status request) — status inquiry
- pain.013/014 (request-to-pay) — A3
- camt.053 (account statement) — A1 and A2 settlement reports

---

## 8. Gap Analysis Review

### 8.1 Quality of Gap Analyses

All six sub-project gap analyses are high-quality, honest self-assessments. They correctly identify:
- What the system has vs. production systems
- Justified "exotic" features (all have clear rationale)
- Intentional scope limitations

### 8.2 Gap Analysis Findings Relevant to Security

| Gap | Source | Security Implication |
|-----|--------|---------------------|
| No graph-based network analysis | A5 gap analysis | Sophisticated money laundering networks (layering) may evade velocity rules. Acceptable for portfolio scope |
| No SEPA Direct Debit | A3 gap analysis | SDD's pull-based model has inherent security risks (creditor-initiated debits). The AvtaleGiro push-based choice is *more secure by design* |
| No PSD3/PSR conditional access | A4 gap analysis | PSD3 will change consent models. System should be ready (configurable consent duration is already there) |
| No settlement finality event | A2 gap analysis | Settlement finality is a legal concept that should be represented as a distinct state/event, separate from SETTLED |
| No business registry integration | A6 gap analysis | VoP for business names relies on abbreviation matching (AS<->Aksjeselskap) rather than registry lookup. Acceptable |

---

## 9. Detailed Security Threat Analysis

This section analyzes the requirements against specific attack vectors relevant to payment systems.

### 9.1 OWASP Top 10 Coverage

| OWASP Category | Status | Relevant Requirements | Gap |
|---------------|--------|----------------------|-----|
| A01: Broken Access Control | **COVERED** | OAuth2 scopes, consent-based access, service-to-service auth | No ABAC (attribute-based access control) — acceptable |
| A02: Cryptographic Failures | **PARTIAL** | JWE, TLS, encrypted at rest | **No key management, no crypto agility** (see CRITICAL-01) |
| A03: Injection | **COVERED** | SEC-09 (Bean Validation), ISO 20022 XSD validation | Could strengthen: parameterized queries not explicitly required |
| A04: Insecure Design | **COVERED** | Threat-aware design throughout | No formal threat model document |
| A05: Security Misconfiguration | **PARTIAL** | SEC-04 (security headers), SEC-10 (CORS) | **No container security requirements** (see HIGH-01) |
| A06: Vulnerable Components | **COVERED** | SEC-06 (dependency scanning, SBOM) | Good |
| A07: Auth Failures | **COVERED** | SCA, token rotation, PKCE, mTLS | Strong |
| A08: Software Integrity | **PARTIAL** | ML model checksums (A5-S03), sanctions list checksums | **No code signing, no deployment integrity** |
| A09: Logging & Monitoring | **COVERED** | Comprehensive audit logging, Prometheus metrics | **No security monitoring/SIEM integration** |
| A10: SSRF | **NOT ADDRESSED** | VoP requester calls external PSPs | See HIGH-02 |

### 9.2 Payment-Specific Attack Vectors

#### ATTACK-01: Authorized Push Payment (APP) Fraud
**Status: WELL DEFENDED**

APP fraud (tricking a victim into sending money to a fraudster's account) is the primary attack vector for payment systems. The requirements provide multiple layers of defense:
1. VoP check catches name/IBAN mismatch (A6)
2. VoP override feeds into fraud scoring (+0.3 risk) (A5-F10)
3. Velocity rules detect unusual patterns (A5-F02)
4. Anomaly detection catches deviations from normal behavior (A5-F03)
5. Semantic duplicate detection catches double-payment attacks (A2-F12)
6. Case management enables human review of borderline transactions (A5-F07)

**Remaining gap:** No requirement for payer confirmation delay ("cooling off period") for high-value first-time transfers. Some banks implement a 24-hour hold on large first-time payments to new beneficiaries. This is a business decision but worth considering.

#### ATTACK-02: Account Takeover (ATO)
**Status: DEFENDED WITH GAPS**

The system detects ATO through:
- Velocity rules (VEL-01, VEL-03, VEL-06)
- Anomaly detection (unusual hours, new creditors)
- Account freeze capability

**Gaps:**
- **No device fingerprinting or session management requirements**: The requirements specify SCA (PIN + TOTP) but don't address session hijacking after successful authentication. If an attacker steals a valid JWT access token (15-minute window), they can make payments without re-authenticating.
- **No IP/geolocation anomaly detection**: A login from Norway followed by a payment from a different country within minutes is a strong ATO indicator. Not mentioned in fraud rules.
- **No concurrent session detection**: Multiple active sessions from different devices/locations is an ATO indicator. Not specified.

**Recommendation:** Add velocity rules:
- VEL-10: Payment from IP/geolocation significantly different from authentication IP (HIGH 0.3)
- VEL-11: Multiple active sessions from different device fingerprints (MEDIUM 0.2)

#### ATTACK-03: Sanctions Evasion
**Status: WELL DEFENDED**

The three-layer sanctions architecture (Bloom filter -> Aho-Corasick -> Jaro-Winkler fuzzy match) is production-grade. The requirements correctly handle:
- Transliterations and aliases (A5-F22)
- Cannot-skip rule (A5-F28)
- Retrospective screening on list updates (A5-F29)
- False positive management (A5-F27)

**Remaining gap:** The requirements don't address **structuring** (splitting a large payment into multiple smaller payments to stay below thresholds). While individual velocity rules may catch some patterns, there's no explicit requirement for detecting structured transactions across multiple accounts or time periods. This is a sophisticated AML concern that could be addressed as a future extension.

#### ATTACK-04: Insider Threat
**Status: PARTIALLY DEFENDED**

**Defended:**
- Audit logging of all case officer decisions (A5-S02)
- OAuth2 scope-based access control (fraud:read vs fraud:manage)
- Immutable audit trail

**Gaps:**
- **No four-eyes principle for high-impact actions**: Sanctions false-positive dismissal (releasing a potentially sanctioned payment) and account unfreeze are single-person decisions. For high-risk actions, require dual authorization.
- **No audit log tamper detection**: Logs are described as "immutable" but the mechanism isn't specified. An insider with database access could modify PostgreSQL audit tables. Require hash-chaining or write-once storage.
- **No separation of duties for fraud threshold configuration**: If a fraud analyst can also modify AUTO_PASS/AUTO_BLOCK thresholds, they could raise the threshold to let fraudulent transactions pass. Configuration changes should require admin approval and produce audit entries.
- **No privileged access management**: No requirement for just-in-time access, session recording for admin access, or break-glass procedures.

#### ATTACK-05: API Abuse / DDoS
**Status: PARTIALLY DEFENDED**

**Defended:**
- Rate limiting on external APIs (SEC-08, A4-F09, VOP-E01)
- Anti-enumeration on VoP (VOP-E02)

**Gaps:**
- **No rate limiting on internal APIs**: If A2 is compromised, it could flood A1 with balance reservation requests, exhausting all account balances. Internal APIs should have circuit breakers and rate limits.
- **No API gateway with WAF requirements**: External-facing APIs (A4, A3 merchant API, A6 responder) should sit behind a WAF that filters common attack patterns (SQL injection attempts, oversized payloads, protocol violations).
- **No request size limits**: No maximum payload size specified for any API. Oversized JSON/XML payloads could cause memory exhaustion.
- **No slowloris/connection exhaustion protection**: Connection limits and timeouts for HTTP connections not specified.

#### ATTACK-06: Supply Chain Attack
**Status: PARTIALLY DEFENDED**

**Defended:**
- SEC-06: Dependency scanning (OWASP Dependency-Check or Trivy) and SBOM (CycloneDX)
- ML model integrity verification (A5-S03)
- Sanctions list integrity verification (A5-S05)

**Gaps:**
- **No signed artifacts**: Built JAR/container images are not required to be signed. An attacker who compromises the CI/CD pipeline could inject malicious code.
- **No base image policy**: Docker base images should come from verified sources with known provenance.
- **No runtime integrity**: No mention of immutable containers (read-only filesystem) or runtime security scanning.

### 9.3 Additional High/Medium Findings

#### HIGH-01: Container and Deployment Security Not Specified

**Affected:** OPS-04 (docker-compose), general deployment  
**Risk:** Container escape, privilege escalation, exposed management ports

Requirements mention Docker and docker-compose but don't specify:
- Containers must run as non-root
- Read-only root filesystem where possible
- Resource limits (CPU, memory) to prevent noisy-neighbor DoS
- No privileged containers
- Network policies (which containers can communicate with which)
- Management ports (9091-9096) must not be exposed externally
- PostgreSQL ports (5441-5445) must not be exposed externally in production

**Recommendation:** Add container security requirements (CNT-01 through CNT-06).

#### HIGH-02: SSRF Risk in VoP Requester

**Affected:** A6-F01 (VoP requester queries external PSPs)  
**Risk:** SSRF if PSP URL is attacker-controlled

The VoP requester calls external PSP endpoints. If the PSP URL is derived from the IBAN's BIC code or from a directory, an attacker who can influence the lookup could redirect VoP queries to an internal service (e.g., `http://localhost:8091/api/v1/accounts`).

**Recommendation:**
- VOP-S07: VoP requester must validate that destination URLs resolve to external IP ranges (not RFC 1918/loopback)
- VOP-S08: Maintain an allowlist of known PSP endpoints; reject queries to unknown endpoints
- VOP-S09: VoP requester HTTP client must have short timeouts and must not follow redirects to internal addresses

#### HIGH-03: Webhook Security Gaps

**Affected:** RTP-W01 through W08 (A3 webhooks), AUTH-F16 (A4 webhooks to TPPs)  
**Risk:** Information leakage, webhook endpoint as attack vector

The webhook requirements are good (HMAC-SHA256, HTTPS-only, retry with backoff) but miss:
- **Webhook URL validation at registration time**: Merchant/TPP could register a webhook URL pointing to an internal service, causing the payment system to make requests to internal infrastructure.
- **No mutual TLS on webhook delivery**: HMAC verifies payload integrity but doesn't authenticate the server receiving the webhook. An attacker who can DNS-spoof the merchant's domain receives webhook data.
- **No webhook payload encryption**: While PII is limited (last 4 digits per RTP-S08), the webhook still reveals payment amounts, statuses, and timing — valuable for competitive intelligence or social engineering.
- **Webhook replay protection**: `X-Webhook-ID` provides idempotency, but there's no timestamp validation. An attacker who captures a webhook could replay it months later.

**Recommendation:**
- RTP-W09: Webhook URL validated against internal/private IP ranges at registration
- RTP-W10: Webhook payload includes timestamp; merchants should reject webhooks older than 5 minutes
- RTP-W11: Consider webhook payload encryption for sensitive notifications

#### HIGH-04: No Database-Level Security Requirements

**Affected:** All subsystems using PostgreSQL  
**Risk:** SQL injection, unauthorized data access, audit log tampering

SEC-03 mentions "column-level or transparent encryption" but the requirements don't specify:
- **PostgreSQL role-based access**: Each service should connect with a dedicated database user with minimal privileges (principle of least privilege)
- **No shared database access**: OPS-05 requires database-per-service, but nothing prevents one service from connecting to another's database with admin credentials
- **Prepared statements/parameterized queries requirement**: SEC-09 covers input validation but doesn't explicitly require parameterized queries for SQL
- **Database connection encryption**: TLS for PostgreSQL connections not specified (only Kafka gets TLS via SEC-01)
- **Database audit logging**: Application-level audit logging is excellent, but database-level access logging (pg_audit) should also be required

**Recommendation:** Add database security requirements (DB-01 through DB-05).

#### HIGH-05: PII Handling Needs Explicit Data Classification

**Affected:** SEC-03, A3-S06, A5-S04, RTP-S07  
**Risk:** Inconsistent PII protection, GDPR non-compliance

Multiple requirements reference "PII" or "sensitive data" without a unified data classification. Different documents classify differently:
- UC-01: "Debtor account number is PII: encrypted at rest"
- A5-S04: "Transaction data is PII: encrypted at rest, access logged"
- A3-S06: "Payment requests contain PII (debtor reference, account): encrypted at rest"
- UC-07: "Account statements contain PII: encrypted in transit and at rest"

**What's missing:**
- A formal data classification scheme (e.g., PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED)
- Classification of each data field (is a fraud score PII? Is a KID reference number PII? Is a BIC code PII?)
- GDPR data subject access request handling (right to access, right to erasure, right to portability)
- Data minimization: is the system collecting more data than needed?
- Cross-border data transfer rules (relevant for P27 multi-country scope)

**Recommendation:** Add data classification requirements (DCLASS-01 through DCLASS-05).

#### MEDIUM-01: TOTP as SCA Possession Factor is Weak

**Affected:** SCA-F02, A4-S06  
**Risk:** TOTP phishing, SIM swap for SMS variant

The SCA implementation uses TOTP (or SMS) as the possession factor. While acceptable for a portfolio project, real-world concerns:
- TOTP codes can be phished in real-time (attacker shows fake bank page, relays TOTP to real bank)
- SMS can be intercepted via SIM swap attacks
- Modern PSD2 implementations use push-based authentication (app-based biometric confirmation) which is more resistant to phishing

**Recommendation:** Document this as a known limitation. Add requirement that SCA method should be pluggable (interface-based) to allow future upgrade to FIDO2/WebAuthn or push-based authentication.

#### MEDIUM-02: No TLS Version and Cipher Suite Requirements

**Affected:** SEC-01, SEC-04, all HTTPS endpoints  
**Risk:** Downgrade attacks, use of weak ciphers

Requirements mandate HTTPS and SASL/SSL but don't specify:
- Minimum TLS version (should be TLS 1.2+, prefer TLS 1.3)
- Allowed cipher suites (should disable weak ciphers like RC4, DES, export-grade)
- HSTS max-age value (SEC-04 requires the header but doesn't specify the value)
- Certificate transparency logging

**Recommendation:** Add TLS-01: TLS 1.2 minimum, TLS 1.3 preferred. TLS-02: Cipher suite allowlist.

#### MEDIUM-03: Event Sourcing Immutability Not Enforced at Infrastructure Level

**Affected:** LED-F01, LED-S05  
**Risk:** Event log tampering by privileged users

LED-S05 states "Events in Kafka immutable after publication" but:
- Kafka topics can be deleted by anyone with admin access
- Kafka messages can be "compacted" away if log compaction is enabled
- Kafka retention policy can be set to delete old messages

The immutability guarantee depends entirely on Kafka configuration, not on any architectural enforcement. For a system where events are the source of truth (not PostgreSQL), this is a significant concern.

**Recommendation:**
- LED-S08: Kafka topics for ledger events must use `cleanup.policy=delete` with `retention.ms=-1` (infinite retention) or have retention exceeding the legal requirement
- LED-S09: Kafka admin operations (topic deletion, configuration change) must be restricted and audited
- LED-S10: Consider hash-chaining events (each event includes hash of previous event) for tamper detection

#### MEDIUM-04: No Security Testing Requirements

**Affected:** General  
**Risk:** Vulnerabilities discovered in production

The requirements specify functional testing (JUnit 5, Testcontainers, Pact) but not:
- SAST (Static Application Security Testing) in CI/CD pipeline
- DAST (Dynamic Application Security Testing) against running application
- Penetration testing schedule
- Security regression testing for fixed vulnerabilities
- Fuzz testing for API endpoints

**Recommendation:** Add security testing requirements (STEST-01 through STEST-04).

#### MEDIUM-05: Logging May Leak Sensitive Data

**Affected:** SEC-07 (audit logging), all subsystems  
**Risk:** PII exposure in logs, log injection

The requirements mandate comprehensive logging but don't specify:
- **What NOT to log**: Passwords, TOTP codes, full account numbers, access tokens, refresh tokens, HMAC secrets should never appear in logs
- **Log injection prevention**: If user-controlled data (e.g., payment description field, 140 chars) is logged without sanitization, an attacker could inject fake log entries or break log parsers
- **Log access control**: Who can read the logs? Structured JSON logs containing transaction data are valuable targets
- **Log retention policy**: Different from data retention — logs may need different treatment than business data

The Secure Spring Boot Platform Starter (Project #4) has `@Audited` annotation with field redaction (UC02_audit_logging.md), which partially addresses this. But the redaction rules need to be formalized.

**Recommendation:** Add logging security requirements (LOG-01 through LOG-04) specifying: sensitive field redaction list, log injection prevention, log access control, log retention.

#### MEDIUM-06: Missing Content-Type Validation

**Affected:** All REST APIs  
**Risk:** Content-type confusion attacks, polyglot file attacks

No requirement specifies that APIs must validate Content-Type headers. An attacker could send XML payload to a JSON endpoint or vice versa, potentially exploiting parser vulnerabilities. APIs should:
- Reject requests with unexpected Content-Type
- Return appropriate Content-Type in responses
- Validate that request body matches declared Content-Type

**Recommendation:** Add to SEC-09 or as a new requirement.

---

## 10. Recommendations Summary

### Priority 1 -- Critical (Address Before Implementation)

| ID | Finding | Section | Effort |
|----|---------|---------|--------|
| CRITICAL-01 | Add key management architecture | 3 | Medium |
| CRITICAL-02 | Add Kafka security requirements (ACLs, message signing, Schema Registry auth) | 3 | Medium |
| CRITICAL-03 | Add incident response requirements | 3 | Low |

### Priority 2 -- High (Address During Implementation Planning)

| ID | Finding | Section | Effort |
|----|---------|---------|--------|
| HIGH-01 | Add container/deployment security requirements | 9.3 | Low |
| HIGH-02 | Add SSRF protection for VoP requester | 9.3 | Low |
| HIGH-03 | Strengthen webhook security (URL validation, replay protection) | 9.3 | Low |
| HIGH-04 | Add database-level security requirements | 9.3 | Low |
| HIGH-05 | Create formal data classification scheme | 9.3 | Medium |

### Priority 3 -- Medium (Address During Implementation)

| ID | Finding | Section | Effort |
|----|---------|---------|--------|
| MEDIUM-01 | Document TOTP weakness, make SCA pluggable | 9.3 | Low |
| MEDIUM-02 | Specify TLS versions and cipher suites | 9.3 | Low |
| MEDIUM-03 | Enforce event log immutability at infrastructure level | 9.3 | Low |
| MEDIUM-04 | Add security testing requirements (SAST, DAST, pentest) | 9.3 | Low |
| MEDIUM-05 | Add logging security requirements (redaction, injection prevention) | 9.3 | Low |
| MEDIUM-06 | Add Content-Type validation requirement | 9.3 | Low |

### Priority 4 -- Enhancements (Nice-to-Have)

| ID | Finding | Section | Effort |
|----|---------|---------|--------|
| ENH-01 | Add cooling-off period for high-value first-time transfers | 9.2 | Low |
| ENH-02 | Add IP/geolocation velocity rules (VEL-10, VEL-11) | 9.2 | Low |
| ENH-03 | Add structuring detection for AML | 9.2 | Medium |
| ENH-04 | Add four-eyes principle for sanctions false-positive and unfreeze | 9.2 | Medium |
| ENH-05 | Add batch clearing crash recovery requirements | 6.2 | Medium |
| ENH-06 | Add account closure precondition requirements | 6.2 | Low |
| ENH-07 | Add mandate abuse detection (compromised merchant API key) | 6.2 | Medium |

---

## 11. Appendix: Requirement Coverage Matrix

### Security Requirement Coverage by Subsystem

| Security Concern | A1 Ledger | A2 Clearing | A3 R2P | A4 PSD2 | A5 Fraud | A6 VoP | Starter |
|-----------------|-----------|-------------|--------|---------|----------|--------|---------|
| Authentication | LED-S01,S02 | CLR-S03 | RTP-S01 | PSD2-S01-S12 | SEC-01 | VOP-S01 | -- |
| Authorization (scopes) | LED-S01 | CLR-S03 | RTP-S01,S02 | PSD2-S01 | SEC-01 | -- | -- |
| Encryption at rest | LED-S04 | -- | RTP-S07 | -- | SEC-04 | -- | -- |
| Encryption in transit | SEC-01 | SEC-01 | RTP-S04 | PSD2-S05,S06 | SEC-01 | -- | -- |
| Audit logging | LED-S03 | CLR-S04 | RTP-S03 | PSD2-S07,S12 | SEC-02,S08 | VOP-S04 | STARTER-F13 |
| Input validation | -- | CLR-B02 | RTP-F09 | -- | -- | VOP-S06 | -- |
| Rate limiting | -- | -- | -- | PSD2-S11 | SEC-09 | VOP-S02 | -- |
| Non-repudiation | -- | CLR-S01,S02 | RTP-S03 | PSD2-S08 | SEC-05 | -- | -- |
| PII protection | LED-S04 | -- | RTP-S07,S08 | PSD2-S06 | SEC-04 | VOP-S03 | -- |
| **Key management** | **GAP** | **GAP** | **GAP** | **GAP** | **GAP** | **GAP** | **GAP** |
| **Incident response** | **GAP** | **GAP** | **GAP** | **GAP** | **GAP** | **GAP** | **GAP** |
| **Container security** | **GAP** | **GAP** | **GAP** | **GAP** | **GAP** | **GAP** | **GAP** |

### Regulatory Compliance Matrix

| Regulation | Requirement | Status | Evidence |
|-----------|-------------|--------|----------|
| PSD2 Art. 97 (SCA) | Two-factor authentication | COMPLIANT | SCA-F01 through F05, 4 exemption types |
| PSD2 RTS Art. 5 (Dynamic linking) | SCA bound to amount+creditor | COMPLIANT | PISP-F05, A4-S06 |
| PSD2 RTS Art. 10 (90-day re-auth) | Consent expiry | COMPLIANT | AUTH-F10 (configurable for PSD3) |
| EU IPR 2024/886 Art. 5a (Instant) | 10-second SLA, 24/7 | COMPLIANT | A2-F20, A2-F21 |
| EU IPR 2024/886 Art. 5c (VoP) | Pre-payment name check | COMPLIANT | A6 subsystem, VOP-* requirements |
| EU IPR 2024/886 Art. 5d (Sanctions) | Real-time screening | COMPLIANT | SAN-F01 through F14 |
| GDPR Art. 25 (Privacy by design) | Data minimization | PARTIAL | VOP-E04 (no name on NO_MATCH), but no formal DPIA |
| GDPR Art. 33 (Breach notification) | 72-hour notification | **GAP** | No incident response requirements |
| AML Directive (6AMLD) | Sanctions screening, SAR | COMPLIANT | A5-F20 through F29, FIU reporting |
| PCI DSS 3.5 (Key management) | HSM/KMS for crypto keys | **GAP** | SEC-05 mentions it but no design |

---

*End of review*

*This review was conducted against the documentation state as of 2026-04-11. Requirements should be re-reviewed after incorporating the recommended changes.*
