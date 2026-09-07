# Nordic Payment Processing Infrastructure — Project Highlights

*Notes for project promotion and portfolio presentation. These are the things that make this project stand out — things an interviewer, reviewer, or technical audience would find impressive or differentiated. Capture now so we don't forget later.*
*Date: 2026-04-12*
*Status: Living document — add to this as the project progresses*

---

## Security

### Built as a Fortress From Day One
Security is not a feature — it is the architecture. Every subproject's first architectural challenge is security. The Platform Starter enforces a secure baseline by construction: deny-all authentication, auto-configured HMAC on Kafka, structured logging with PII redaction, tamper-evident audit trail. A service cannot be built without the security baseline — the Gradle plugin fails the build.

### Verified Against OWASP ASVS Level 2
The architecture was systematically reviewed against OWASP Application Security Verification Standard v4.0.3 at Level 2 — the standard for financial applications. 49 specific security review items. 22 findings identified and resolved before a single line of code was written.

### Verified Against OWASP API Security Top 10 (2023)
All 10 API-specific risks addressed. The SSRF vulnerability in webhook dispatch (API7:2023) was caught and fixed during architecture review. Anti-enumeration protection on VoP (API6:2023) was designed as a core architectural feature.

### DORA Compliance (EU Digital Operational Resilience Act)
ICT risk management, incident classification, resilience testing, and third-party risk requirements mapped to the architecture. Chaos engineering test plan included.

### Defense in Depth — Multiple Independent Layers
No single security control is trusted alone. A4 (PSD2 Gateway) has 6 independent defense layers. A6 (VoP) has an 8-layer security pipeline. Kafka has 3 independent protections (SASL, HMAC, Avro schema). Balance integrity has 3 independent protections.

### Sanctions Screening Is Structurally Mandatory
Not a configuration toggle — the Kafka Streams topology has no code path from payment instruction to settlement without passing through fraud/sanctions screening. Verified across all payment paths: instant, batch, VRP, mandate auto-accept, duplicate override. Late sanctions hit on settled payment triggers automatic reversal (pacs.004).

### Timing Side-Channel Protection on VoP
Response times are normalized to a 10ms minimum floor, preventing attackers from inferring match results by timing analysis. This is a level of security sophistication rarely seen in portfolio projects.

---

## Architecture

### Six Microservices — One Coherent System
Not 6 independent services duct-taped together — a single coherent payment processing platform with:
- Centralized interface contracts (`INTERFACE_CONTRACTS.md`) defining every Kafka and REST boundary
- Shared domain model with common exception hierarchy and data types
- Platform Starter providing cross-cutting concerns to all services
- Kafka ACLs restricting which service can produce to which topic

### Event Sourcing for Financial Data (A1)
The account ledger uses event sourcing with Kafka as the event store and PostgreSQL as derived projections. Compound events (debit + credit in one message) make unbalanced entries structurally impossible. Projections are fully rebuildable from the event stream.

### Dual-Mode Clearing Engine (A2)
A single codebase deployed as two JVM instances handles both:
- **Instant payments:** Kafka Streams with Processor API, 5-second EPC SLA, global 7-second hard-limit punctuator
- **Batch clearing:** Spring Batch with multilateral netting, deterministic algorithm, restart-safe execution

### Structural Name Matching (A6)
Not just "run Jaro-Winkler on two strings" — a structural pipeline that decomposes names into components (given name, family name, legal suffix, compound prefix), generates permutations, and matches component-by-component. Handles Nordic diacritics (Bjorn/Bjorn), name order (Nordmann, Ola / Ola Nordmann), and legal entity suffixes (AS/Aksjeselskap) correctly.

### Three-Phase Payment State Machine
Explicit ownership transitions: A3/A4 own Phase 1 (VoP, validation, submission), A2 owns Phase 2 (clearing, screening, settlement), A1+A3/A4 own Phase 3 (ledger truth, status reporting). Each phase has clear timeout responsibility and recovery procedures.

---

## Crash Resilience

### Crash-Point-by-Crash-Point Analysis
Every step in the instant payment flow was analyzed for "what happens if the system crashes right here?" — 15 specific crash scenarios with documented recovery for each. Not "we use Kafka so it's fine" but "at this exact point, this specific mechanism ensures recovery."

### Three-Layer Idempotency
Command handler dedup cache → Kafka idempotent producer → projection consumer dedup table. No crash, replay, or retry can cause a payment to be processed twice.

### 30-Second Reservation Safety Net
Balance reservations auto-expire after 30 seconds via A1's periodic scan (every 5 seconds). Even if A2 crashes and never releases a reservation, money is never locked permanently.

### Chaos Engineering Test Plan
8 specific failure injection experiments defined: kill services mid-payment, introduce network partitions, simulate Kafka/PostgreSQL outages. Steady-state metrics defined. Infrastructure specified (Chaos Mesh, Toxiproxy).

---

## Regulatory & Compliance

### EU Instant Payment Regulation (2024/886)
Full implementation of Article 5c (Verification of Payee), Article 5a (instant payment availability 24/7/365), Article 5d (sanctions screening). VoP implements both requester and responder roles per EPC VoP Scheme (EPC218-23).

### PSD2 with Full SCA Exemption Engine
Not just "we do SCA" — a complete exemption engine implementing all 4 exemption types (low-value, trusted beneficiary, recurring, TRA) with pessimistic-locked cumulative counters, all evaluations audited, and dynamic linking with immutable payment data.

### GDPR Crypto-Shredding
Right to erasure implemented via crypto-shredding: per-account AES-256-GCM encryption keys. Deleting the key makes PII irrecoverable across all Kafka replicas and backups while preserving financial data integrity (balances remain correct). Full erasure cascade across all 6 services documented.

### AML-Compliant Audit Trail
Tamper-evident hash chain (SHA-256, per-instance, crash-recoverable). Dual-write to local log (sync, compliance-primary) and Kafka (async, queryable). 5-year retention. Daily signed checkpoints. Every payment operation, sanctions decision, and operator action audited.

---

## Engineering Quality

### Architecture Reviewed Before Code
76 documents, ~25,000 lines of architecture reviewed across 3 systematic reviews (consistency, security, crash resilience) before implementation. 51 architecture findings + 22 security findings identified and resolved at the design level — not in code review or testing.

### 4 Critical Integration Failures Caught at Architecture Level
- Kafka partition key conflict between A2 and A1 (would have caused serialization failures)
- A3 calling A6 with wrong HTTP method and path (would have caused 404)
- A2 missing handler for A5's TIMEOUT response (would have caused undefined behavior)
- SSRF vulnerability in A3 webhooks (would have been exploitable)

All caught and fixed before a single line of code was written.

### Reference Implementation Alignment
Architecture compared against real-world payment systems: SurePay (Netherlands, 11B+ VoP checks), UK CoP (Pay.UK, 2M+ checks/day), EPC VoP Scheme, NICS/KAR (Norway), Tietoevry, Deutsche Bank, TIPS (ECB), P27 (Nordics), Allevo FinTP.

### Property-Based Testing for Name Matching
Not just handcrafted test cases — jqwik property-based testing generates thousands of random name pairs and verifies algorithmic invariants: matching is symmetric, normalization is idempotent, scores are bounded, diacritics normalization is effective.

### Complete Technology Stack — No Gaps
Every component specified: Spring Boot 3.x, Kafka (event store + messaging), PostgreSQL (per-service), Avro + Schema Registry, Resilience4j, Caffeine, Bucket4j, Apache Commons Text, Micrometer + Prometheus + Grafana, JUnit 5 + jqwik + Pact + Testcontainers + WireMock.

---

## Norwegian / Nordic Focus

### Designed for the Norwegian Payment Infrastructure
- NICS clearing integration (Mastercard Payment Services / Bits AS)
- KAR register alignment (account-holder directory)
- Norwegian business entity handling (AS/Aksjeselskap, ASA/Allmennaksjeselskap)
- BankID-compatible authentication patterns (Kantega)

### Nordic Name Matching Excellence
EPC288-23 diacritics normalization with Nordic-specific equivalences (ø=o, å=a=aa, æ=ae). Test corpus includes Bjorn/Bjorn, Ase/Ase, Odegard/Odegard, Gronn Energi AS / Gronn Energi Aksjeselskap.

### Target Company Alignment
Architecture mirrors what DNB, SpareBank 1, Tietoevry, Mastercard Oslo, Kantega, and Vipps need to build. Every design decision is linked to target company relevance.

---

## Portfolio Differentiators

### Not a Tutorial Project
This is not "build a REST API with Spring Boot." It is a production-grade payment processing platform addressing real-world challenges: exactly-once semantics across service boundaries, EU regulatory compliance, tamper-evident audit trails, sub-second clearing with sanctions screening.

### Architecture-First, Not Code-First
The project demonstrates senior engineering judgment: understanding requirements → analyzing challenges → making decisions with documented rationale → reviewing systematically → then implementing. The architecture documents alone are a portfolio artifact.

### Depth Over Breadth
6 services, each with its own requirements, use cases, gap analysis, architecture challenges, decisions, and comprehensive design. Not 20 shallow microservices — 6 deeply thought-through services that work together as a coherent system.

### Interview-Ready Artifacts
- 10 master use cases with step-by-step flows and failure scenarios
- Gap analysis against real European payment systems
- OWASP-verified security architecture
- Chaos engineering test plan
- Interface contracts with bilateral verification

---

## Financial Integrity (Added from Review 4)

### IEEE 754 Pitfalls Eliminated
No floating-point arithmetic for money anywhere in the system. All monetary calculations use BigDecimal with HALF_EVEN (Banker's Rounding). Avro schemas encode money as string. PostgreSQL uses NUMERIC. The architecture documents why float/double is dangerous for money (Vancouver Stock Exchange incident, accumulation errors) and structurally prevents it.

### Accounting Invariants Verified by Construction
- Sum(debits) == Sum(credits) — enforced by compound events (debit+credit in one Kafka message)
- Balance = Sum(entries) — projections rebuildable from event store at any time
- Conservation of money — verified at unit, property-based, and integration test levels
- Netting positions sum to zero — runtime postcondition check throws if violated

### Currency-Aware from Day One
ISO 4217 currency codes validated at every boundary. Decimal scale derived from currency (not hardcoded to 2). Multi-currency account structure with per-currency balances. No implicit currency conversions.

---

## Regulatory Compliance (Added from Review 5)

### EU IPR Article 5d — Dual Sanctions Screening Model
The architecture correctly implements the Article 5d requirement: daily customer-level screening (compliant with "no per-transaction delays for instant payments") PLUS per-transaction beneficiary screening (legitimate AML control for checking unknown counterparties). This dual model was verified during dedicated regulatory compliance review.

### 7 EU/Norwegian Regulations Mapped Article-by-Article + Deep Dive
EU IPR 2024/886, PSD2+RTS on SCA, AML 5th Directive/AMLR, GDPR, DORA, Norwegian legislation (Finansavtaleloven, Hvitvaskingsloven, Betalingssystemloven, Personopplysningsloven), EPC Scheme Rulebooks — each regulation mapped to specific architectural elements. Initial review (30 checks) followed by deep dive identifying 20 additional EU gaps and 13 Norwegian-specific requirements, all resolved.

### Norwegian Market Awareness
Not a generic "European payment system" — specifically designed for Norway: BankID integration for SCA, NICS/NICS Real for NOK clearing, KAR register alignment, KID payment references, Norwegian sanctions lists, fødselsnummer handling, Økokrim/EFE reporting path, Bits AS standards (AvtaleGiro, eFaktura). Documented separately from EU requirements.

### EU AI Act Compliance for ML Fraud Scoring
A5's ML fraud model is explicitly classified under EU AI Act Annex III Section 5(b) — fraud detection is exempt from high-risk classification. The exemption boundary is architecturally enforced: fraud scores cannot be repurposed for creditworthiness assessment.

### DORA-Ready from Architecture Phase
Incident classification (P1-P4) with DORA Article 18 criteria. Regulatory reporting timeline (4h/72h/1m per Article 19). Chaos engineering test plan (DORA Article 26). SBOM generation on every build. Third-party risk awareness for Kafka/PostgreSQL.

---

## Scalability and Performance (Added from Reviews 6-7)

### Scalability Bottlenecks Identified Before Code
Dedicated architecture review identified every scaling limit: Kafka partition count (12, configurable), PostgreSQL table growth (partitioning strategy documented), A1 reservation throughput ceiling (~150-190/sec at current partitions), event store growth (tiered storage strategy documented). Each bottleneck has a documented scaling path — no surprise redesigns during load testing.

### Performance Budget Verified Under Contention
Not just happy-path timing — the review analyzed latency under 10x load: Kafka `acks=all` cumulative overhead (30-90ms per payment), connection pool contention, lock contention on SCA counters, reservation serialization limits. All within budget with documented margins.

---

## 9 Dedicated Architecture Reviews

Before writing a single line of code, the architecture was verified through 9 systematic reviews:

| # | Review | Findings | Resolved |
|---|--------|----------|----------|
| 1 | Architecture Consistency | 29 | All |
| 2 | Security (OWASP ASVS L2) | 22 | All |
| 3 | Crash Resilience | 12 | All |
| 4 | Financial Integrity | 5 | All |
| 5 | Regulatory Compliance | 9 | All |
| 6 | Scalability | 14 | All |
| 7 | Performance Under Load | (combined with 6) | — |
| 8 | Certification Readiness | 7 | All |
| 9 | Interoperability | 7 | All |
| **Total** | | **105+** | **All** |
