# Payment Processing System — Certification Landscape

*What certifications exist for payment processing systems, who has them, and how our system aligns*
*Last updated: 2026-04-11*

---

## 1. Context

Allevo's FinTP (Bucharest, Romania) holds a **SWIFT CBPR+ self-attestation** — a vendor readiness declaration that their software correctly handles ISO 20022 cross-border payment messages. This is published on swift.com and visible to 11,800 banks worldwide. It prompted us to map the full certification landscape for payment processing systems in Europe.

Mastercard Oslo operates NICS (Norwegian Interbank Clearing System) under authorization from **Norges Bank** (Norway's central bank), managed through **Bits AS** (the Norwegian banking industry's infrastructure company). This is the highest level of regulatory authorization for a payment system in Norway.

---

## 2. Certification Map

### Tier 1 — Directly Relevant to Our System

#### SWIFT CBPR+ Self-Attestation

| Aspect | Detail |
|--------|--------|
| **What** | Vendor declares their payment application correctly processes ISO 20022 CBPR+ messages (pacs.008, pacs.002, camt.053, etc.) for cross-border payments |
| **Who needs it** | Payment software vendors whose products connect to the SWIFT network |
| **Process** | Vendor tests against SWIFT's published CBPR+ test cases → fills self-attestation form → sends to provider.readiness@swift.com → SWIFT publishes vendor on "CBPR+ Ready Listing" |
| **Important** | **SWIFT does NOT audit or test the product.** Vendor self-declares under own responsibility. Valid for 1 year |
| **Who has it** | Allevo FinTP (since Dec 2020), major banking software vendors listed on swift.com |
| **Our alignment** | Our system processes pacs.008, pacs.002, pacs.004, camt.053, pain.001, pain.013/014. Our ISO 20022 JAXB bindings in A2 and the shared `payment-iso20022` library handle the same message types. We could theoretically self-attest |
| **Portfolio value** | Document: "ISO 20022 message handling tested against CBPR+ message specifications." Don't claim certification (we're not a vendor), but show awareness |

#### EPC SEPA Scheme Compliance

| Aspect | Detail |
|--------|--------|
| **What** | Adherence to European Payments Council rulebooks for SEPA payment schemes |
| **Schemes** | SCT (SEPA Credit Transfer), SCT Inst (Instant), SDD Core/B2B (Direct Debit), SRTP (Request to Pay), VoP (Verification of Payee) |
| **Who needs it** | Payment Service Providers (PSPs/banks) participating in SEPA schemes |
| **Process** | PSPs must comply with scheme rulebooks. EPC provides test toolboxes for self-certification. For VoP, the EPC is building a dedicated "VoP API Reference Toolbox" for API-based self-certification |
| **Rulebook versions** | 2025 SCT rulebook v1.1, 2025 SCT Inst rulebook v1.1 (effective Oct 5, 2025). Fully compliant with EU Instant Payment Regulation 2024/886 |
| **Our alignment** | We implement: SCT (batch clearing in A2), SCT Inst (instant path in A2), R2P/SRTP (A3), VoP (A6). Our functional requirements trace to EPC rulebook specifications |
| **Portfolio value** | Reference the specific EPC rulebook versions in our documentation. Show that our requirements map to the rulebook |

**Critical timing update (2025 SCT Inst rulebook v1.1):**

| Timing | Old standard | New 2025 rulebook |
|--------|-------------|-------------------|
| Execution target | 10 seconds | **5 seconds** |
| Hard limit | 10 seconds | **7 seconds** |
| Final confirmation | 10 seconds | **9 seconds** |

Our current requirements specify a 10-second SLA. The 2025 EPC rulebook tightens this to **5-second execution** with a 7-second hard limit. Our actual implementation targets ~3-4 seconds for the happy path, which already meets the tighter standard. The requirement text should be updated to reference the 2025 rulebook timing.

#### PCI DSS v4.0

| Aspect | Detail |
|--------|--------|
| **What** | Payment Card Industry Data Security Standard. 12 requirement categories covering: network security, data protection, vulnerability management, access control, monitoring, security policy |
| **Version** | v4.0 effective March 31, 2025 (replacing v3.2.1) |
| **Who needs it** | Any entity that stores, processes, or transmits cardholder data (card numbers, CVVs) |
| **Our alignment** | We handle IBANs and payment data, not card data — so PCI DSS is not directly mandatory. However, our security controls align with PCI DSS best practices: |

| PCI DSS Requirement | Our Implementation |
|--------------------|-------------------|
| Req 1: Network security | SEC-01: Kafka SASL/SSL. mTLS for service-to-service |
| Req 3: Protect stored data | SEC-03: Encryption at rest in PostgreSQL |
| Req 6: Secure development | SEC-06: Dependency scanning (OWASP Dependency-Check, Trivy) |
| Req 7: Restrict access | SEC-02: OAuth2 Bearer tokens, API keys, RBAC |
| Req 8: Identify users | AUTH-F01-F15: OAuth2 consent, SCA, JWT with principal identity |
| Req 10: Monitor access | STARTER-F10-F16: @Audited annotation, structured audit logging, Kafka audit events |
| Req 10.3.6: Affected resource ID | STARTER-F16: @Audited(resource="paymentId") — added based on PCI DSS gap analysis |
| Req 10.5: Protect audit trail | Kafka `audit.events` topic is append-only (immutable) |
| Req 10.7: Retain 12 months | Infrastructure concern — documented as deployment requirement |
| Req 12: Security policy | Security design document planned for each sub-system |

#### ISO 27001

| Aspect | Detail |
|--------|--------|
| **What** | International standard for Information Security Management Systems (ISMS). Organizational policies, risk assessment, security controls |
| **Who needs it** | Any organization processing sensitive financial data. Most banks and PSPs are ISO 27001 certified |
| **Our alignment** | ISO 27001 is an organizational certification, not a software certification. However, our technical controls map to ISO 27001 Annex A controls: access control (A.9), cryptography (A.10), operations security (A.12), communications security (A.13), system acquisition/development (A.14) |
| **Portfolio value** | Reference ISO 27001 control mappings in security documentation. Shows awareness of the organizational security framework |

#### SWIFT CSP (Customer Security Programme)

| Aspect | Detail |
|--------|--------|
| **What** | 32 mandatory + advisory cybersecurity controls for SWIFT-connected institutions. Mapped to NIST CSF, PCI DSS, ISO 27001 |
| **Who needs it** | Financial institutions connected to SWIFT |
| **Key controls** | Restrict internet access, segregate environments, encrypt data at rest and in transit, detect anomalous activity, manage identities and access |
| **Our alignment** | Our system doesn't connect to SWIFT directly, but the security control patterns are identical: encryption in transit (SEC-01), access management (SEC-02), audit trails (SEC-07), vulnerability scanning (SEC-06) |

---

### Tier 2 — Applicable to Specific Components

#### Common Criteria (ISO 15408)

| Aspect | Detail |
|--------|--------|
| **What** | Security evaluation framework for IT products. Evaluation Assurance Levels: EAL1 (functionally tested) through EAL7 (formally verified and tested) |
| **Relevance** | Thales Norway's XOmail (military messaging) passed Common Criteria evaluation. Used for high-security products (crypto devices, HSMs, smart cards) |
| **Our alignment** | Our system is a portfolio project, not a product seeking CC evaluation. However, our A5 (sanctions screening) and A6 (VoP) have security-critical functions that would theoretically be CC evaluation candidates |
| **Cost** | CC evaluation costs EUR 100K-500K+ and takes 6-18 months. Not applicable for portfolio projects |

#### DORA (Digital Operational Resilience Act)

| Aspect | Detail |
|--------|--------|
| **What** | EU regulation (2022/2554) for ICT risk management in financial entities. Effective January 17, 2025 |
| **Requirements** | ICT risk management framework, incident reporting (within 4 hours for major incidents), digital operational resilience testing, third-party ICT risk management |
| **Our alignment** | Our system design incorporates DORA-relevant patterns: health checks (OPS-01), graceful shutdown (OPS-02, STARTER-F40-F43), observability (Prometheus/Grafana), structured incident data (audit events), resilience (circuit breakers in A3 income verification, timeout handling in A2 instant path) |
| **Portfolio value** | Reference DORA in our resilience documentation. Show that our health checks, graceful shutdown, and timeout patterns support operational resilience |

#### eIDAS (electronic Identification, Authentication and trust Services)

| Aspect | Detail |
|--------|--------|
| **What** | EU regulation for electronic identification and trust services. Provides framework for QWAC (website authentication) and QSEAL (electronic seal) certificates used in PSD2 |
| **Relevance** | PSD2 requires TPPs to present eIDAS QWAC/QSEAL certificates when accessing bank APIs |
| **Our alignment** | A4 (PSD2 API) simulates mTLS with client certificates (A4-S09). In production, TPPs would present QWAC certificates issued by a Qualified Trust Service Provider |
| **Portfolio value** | Document the eIDAS certificate requirement and show that our mTLS implementation would accept QWAC certificates in production |

#### SOC 2 Type II

| Aspect | Detail |
|--------|--------|
| **What** | Third-party audit of security, availability, processing integrity, confidentiality, and privacy controls. Report covers a period (typically 6-12 months) |
| **Who needs it** | Service organizations processing data on behalf of customers. Expected for Banking-as-a-Service (BaaS) providers like Tietoevry |
| **Our alignment** | If our system were offered as BaaS, SOC 2 would be expected. Our audit logging, access controls, and encryption align with SOC 2 Trust Service Criteria |

---

### Tier 3 — Regulatory Authorizations (Context)

| Authorization | Issuer | Who Needs It | Context |
|--------------|--------|-------------|---------|
| **Norges Bank Payment System Authorization** | Norges Bank (Norwegian Central Bank) | Payment systems important to financial stability (e.g., NICS) | Bits AS holds this for NICS. Mastercard Oslo operates NICS under this authorization |
| **Finanstilsynet PSP License** | Norwegian Financial Supervisory Authority | Any payment service provider operating in Norway | Required to offer payment services. DNB, SpareBank 1, Vipps all hold this |
| **ECB/NCB Authorization for TIPS** | ECB / National Central Banks | Direct participants in TIPS (instant settlement) | Banks connecting directly to TIPS need authorization and Dedicated Cash Accounts |
| **EBA Clearing Participation** | EBA Clearing | Participants in RT1 (instant clearing) | RT1 participants need to meet EBA Clearing's admission criteria |

---

## 3. Certification Alignment Summary for Our System

| Certification/Standard | Status | Evidence in Our System |
|-----------------------|--------|----------------------|
| **SWIFT CBPR+** | Aligned — same ISO 20022 messages | A2 uses JAXB-generated classes from ISO 20022 XSD (pacs.008, pacs.002, camt.053). `payment-iso20022` shared library |
| **EPC SCT Inst 2025** | Aligned — instant path meets timing | A2 instant path: 3-4 sec happy path (within 5-sec execution target). Should update requirement text to reference 2025 rulebook |
| **EPC SCT** | Aligned — batch clearing | A2 batch path: multilateral netting, settlement reports |
| **EPC SRTP** | Aligned — R2P implementation | A3 implements: accept/decline/defer/amend, pain.013/014 |
| **EPC VoP** | Aligned — requester + responder | A6 implements both roles with fuzzy matching, anti-enumeration |
| **PCI DSS v4.0** | Partially aligned | Audit logging (10.x), encryption (3.x), access control (7.x, 8.x). Not handling card data |
| **ISO 27001** | Controls aligned | Technical controls map to Annex A. Organizational policies out of scope (portfolio) |
| **SWIFT CSP** | Controls aligned | Encryption, access management, anomaly detection, audit trails |
| **DORA** | Patterns aligned | Health checks, graceful shutdown, observability, timeout handling, resilience |
| **eIDAS** | Simulated | mTLS in A4 simulates QWAC certificate validation |
| **Common Criteria** | Not applicable | Portfolio project — too expensive/long for CC evaluation |

---

## 4. Recommendation: What to Reference in Portfolio Documentation

Each sub-project's README or architecture document should reference the applicable certifications:

| Sub-project | Reference |
|------------|-----------|
| **A1 (Ledger)** | PCI DSS 10.x (audit trails, immutable events), ISO 27001 A.12 (operations security) |
| **A2 (Clearing)** | EPC SCT/SCT Inst 2025 rulebook, SWIFT CBPR+ message specifications, ISO 20022 |
| **A3 (R2P)** | EPC SRTP rulebook, EPC QR standard (EPC069-12) |
| **A4 (PSD2 API)** | PSD2 RTS on SCA, eIDAS QWAC/QSEAL, BerlinGroup NextGenPSD2 |
| **A5 (Fraud/Sanctions)** | EU AML Package 2024, EBA Guidelines on transaction monitoring, EU sanctions screening requirements (IPR Art. 5d) |
| **A6 (VoP)** | EU IPR 2024/886 Art. 5c, EPC VoP rulebook, EPC VoP API test toolbox |
| **Starter (#4)** | PCI DSS 10.x (audit logging), OWASP Secure Headers Project, DORA (resilience) |

---

## 5. Action Item: Update Instant Payment Timing

The 2025 EPC SCT Inst rulebook v1.1 (effective October 5, 2025) specifies tighter timing than our current requirements:

| Previous requirement | 2025 EPC rulebook | Status |
|---------------------|-------------------|--------|
| A2-F20: "< 10 seconds" | Execution: 5 sec, hard limit: 7 sec, confirmation: 9 sec | **DONE** — A2-F20 updated |
| UC-08 time budget: 10,000ms total | 5,000ms execution + 2,000ms margin + 2,000ms confirmation | **DONE** — UC-08 time budget rewritten |
| Monitoring: alert if p99 > 8s | Alert if p95 > 4s, p99 > 5s | **DONE** — UC-08 monitoring updated |

Note: Our actual happy-path implementation targets ~3-4 seconds, which already meets the tighter 5-second execution target. The change is mainly in the documented SLA and monitoring thresholds.
