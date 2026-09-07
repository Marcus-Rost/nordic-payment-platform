# Requirements Work Summary — Nordic Payment Processing Infrastructure

*System A from PROJECT_SUGGESTIONS.md*
*Work period: April 10-11, 2026*

---

## 1. What We Built

A complete requirements specification for a Nordic payment processing infrastructure consisting of **6 integrated microservices** (A1-A6) plus a cross-cutting platform starter library. The system mirrors what **Mastercard Payment Services Norway** (100+ Java engineers) builds for NICS, eFaktura, and AvtaleGiro — used by 90%+ of Norwegian and Danish households.

### Sub-systems

| ID | Name | Role | Part 1 Project |
|----|------|------|---------------|
| A1 | Event-Sourced Account Ledger | Single source of truth for balances. CQRS, multi-currency, balance holds, reconciliation | #9 |
| A2 | Payment Clearing & Settlement Engine | Dual-mode: batch (netting, Spring Batch) + instant (EPC 2025 SLA: 5s/7s/9s, Kafka Streams). Liquidity management, semantic duplicate detection | #11 |
| A3 | Request-to-Pay & Bill Payment Service | eFaktura evolved toward SEPA SRTP: accept/decline/defer/amend. Recurring mandates (AvtaleGiro). QR codes. HMAC-signed webhooks | #12 |
| A4 | PSD2 Open Banking API Gateway | OAuth2 consent, AISP/PISP, SCA with 4-type exemption engine, Variable Recurring Payments (VRP) | #2 |
| A5 | Fraud Detection & Sanctions Engine | Three-layer pipeline: sanctions (Bloom filter + Aho-Corasick + fuzzy) → velocity rules → ML scoring (DJL). Case management. PII-minimized scoring | #1 |
| A6 | Verification of Payee Service | Pre-payment name-IBAN check (EU IPR mandatory). Requester + responder roles. Fuzzy matching. Anti-enumeration. Feeds into A5 | #21 |
| -- | Secure Spring Boot Platform Starter | Cross-cutting: security headers, @Audited annotation, Prometheus metrics, health checks, graceful shutdown | #4 |

---

## 2. Document Inventory

### Master System (space/payment_processing/)

| Category | Documents | Content |
|----------|----------|---------|
| **Requirements** | system_requirements.md | 11 sections, ~600 lines. Sub-system specs (A1-A6), cross-cutting (security, ops, data standards, crypto/key management, regulatory awareness, resilience/crash recovery), Kafka topology, integration flows, demo requirements, project structure |
| **Use Cases** | 10 use cases (UC-01 through UC-10) | Invoice payment, PSD2 account access, PSD2 payment initiation + SCA exemptions, batch clearing, fraud detection + sanctions, recurring debit, account management, instant payment (EPC 2025), VoP, VRP |
| **Extensions** | 6 documents | Gap analysis (EU comparison), instant payments detail, VoP detail, sanctions screening detail, further extensions overview, Allevo FinTP analysis |
| **Navigation** | NAVIGATION.md, SUBSYSTEM_MAP.md, CERTIFICATIONS.md | Cross-references to sub-projects, Kafka topic map, certification landscape, port assignments |

### Sub-project Documentation (space/NN_*/)

| Sub-project | Requirements | Use Cases | Gap Analysis |
|-------------|-------------|-----------|-------------|
| #4 Spring Boot Starter | 9 sections: auto-config, @Audited, MeterBinder, Gradle plugin, module structure, config properties | 3 UCs: security headers, audit logging, metrics/health/shutdown | vs. Skatteetaten Aurora, OWASP, PCI DSS |
| #1 Fraud Detection | 11 sections: sanctions pipeline, velocity rules (10 rules), anomaly detection, ML/DJL, score aggregation, case management, data model | 2 UCs: automated scoring pipeline, analyst case management | vs. Sportradar UFDS, NICE Actimize, Featurespace, Feedzai, EU AML Package |
| #2 PSD2 API | 12 sections: AISP, PISP, OAuth2 consent, SCA + 4 exemption types, VRP, TPP management, data model (consent, VRP, refresh token, SCA counter) | 2 UCs: OAuth2 consent flow (with PKCE, family revocation), payment initiation + all SCA exemptions + VRP | vs. BerlinGroup NextGenPSD2, UK Open Banking, DNB/SpareBank1 APIs, PSD3 |
| #21 VoP Service | 8 sections: requester/responder, name matching algorithm, anti-enumeration, fraud integration, API design | 1 UC: all match scenarios (exact, close, no-match, diacritics, responder, enumeration) | vs. SurePay (NL), UK CoP, EU VoP scheme |
| #9 Account Ledger | 7 sections + Avro schema: event sourcing, CQRS, balance holds (reserve/confirm/release), reconciliation (4 requirements), multi-currency, snapshots | 1 UC: all operations (credit/debit, reservation lifecycle, concurrent reservations, projection rebuild, freeze, reconciliation, semantic duplicate) | vs. Axon Framework, EventStoreDB, Thought Machine Vault, Mambu |
| #11 Clearing Engine | 9 sections: batch (netting algorithm, Spring Batch), instant (EPC 2025 SLA, Kafka Streams), liquidity management, semantic duplicate detection, reconciliation output, ISO 20022 messages | 1 UC: batch cycle + instant happy path + all failure scenarios (fraud timeout, sanctions timeout, SLA breach, duplicate detection, reconciliation output) | vs. TIPS, RT1, NICS, P27, UK FPS |
| #12 R2P Service | 10 sections: R2P lifecycle (accept/decline/defer/amend), recurring mandates, QR codes (EPC069-12), webhooks (HMAC + retry), bulk submission | 1 UC: full lifecycle (submit, accept instant, decline with reason, defer, amend, mandate auto-accept, QR payment) | vs. eFaktura, AvtaleGiro, SEPA SRTP, Vipps MobilePay |

**Total: 47 documents, ~3,000+ lines of requirements and use cases**

---

## 3. Research That Shaped the Requirements

### Company Research (23 companies in Oslo)
The requirements were designed to mirror the actual tech stacks and projects of target companies:
- **Mastercard Oslo**: NICS clearing, eFaktura, AvtaleGiro, P27, ISO 20022, Java/Spring Boot, OAuth 1.0a, 100+ engineers
- **DNB**: Kafka, GCP, event-driven microservices, fraud/AML, Open Banking APIs
- **SpareBank 1**: Kotlin/Java, Kafka, PSD2 BerlinGroup APIs, Troxy test proxy, continuous deployment
- **Tietoevry Banking**: Event sourcing/CQRS, Pact contract testing, Testcontainers, Banking-as-a-Service
- **Sportradar**: DJL for ML inference on EKS, 30B+ events/year fraud detection (UFDS)
- **NAV**: mock-oauth2-server, token-support (used in our testing strategy)
- **Skatteetaten**: Aurora Spring Boot starters (our #4 is modeled after these)

### EU Payment Infrastructure Research
- SEPA schemes: SCT, SCT Inst, SDD, SRTP, VoP (EPC rulebooks)
- Infrastructure: TIPS (ECB), RT1 (EBA Clearing), P27 (Mastercard/Vocalink), UK Faster Payments
- Regulations: EU IPR 2024/886, PSD2/PSD3, AML Package 2024, DORA, GDPR, eIDAS, Settlement Finality Directive

### Competitive Analysis
- **Allevo FinTP** (Bucharest): open-source payment processor. Led to adding reconciliation requirements and semantic duplicate detection
- **Thought Machine Vault**: validated our balance reservation (pending→confirmed) pattern
- **NICE Actimize, Featurespace, Feedzai**: commercial fraud detection benchmarks
- **SurePay (NL), UK CoP**: VoP implementation references

### Certification Research
- SWIFT CBPR+ self-attestation (Allevo has this)
- EPC SCT Inst 2025 rulebook v1.1 (updated timing: 5s/7s/9s)
- PCI DSS v4.0 alignment
- EPC VoP scheme compliance

---

## 4. Reviews and Fixes

### Reviews Received (11 review files, 254+ issues)

| Review | Issues | Key Finding |
|--------|--------|-------------|
| Internal Consistency | 27 | Status enum divergence, API endpoint paths, score weight conflicts |
| Payment Processing Requirements | 24 | Key management architecture missing, API rate limiting absent |
| Proposed EU Legislation | 58 new reqs | GDPR, DORA, AML, Settlement Finality, Norwegian context |
| Allevo Comparison | 5 gaps | Reconciliation gap, semantic duplicate detection gap |
| Certification Readiness | 16 blockers | ISO 20022 message versions, validation rules |
| Computational Stability | 23 | Orphaned reservations, dual-write problem, crash recovery |
| Deutsche Bank Comparison | 7 areas | Operational maturity, multi-format support |
| EU Legislation | 23 gaps | GDPR near-absent, DORA missing, settlement finality |
| ISO 20022 Certification | 31 | Message versions, code lists, conformance statements |
| Mastercard Comparison | 6 areas | NICS operator perspective, cross-format support |
| OWASP Security | 42 | Key management, Kafka message integrity, XML hardening |

### Fixes Applied

**Direct fixes (17):**
- Velocity rule VEL-02 aligned (relative threshold)
- Score weights corrected (0.2/0.2/0.2 when ML enabled)
- Sanctions hit bypasses fraud scoring (clarified)
- API endpoint standardized (`/resolve`)
- Sanctions list update timing clarified (poll freq vs. processing time)
- Balance reservation state standardized (CONFIRMED)
- Request status standardized (ACCEPTED per SEPA R2P)
- Missing Kafka consumers added (account.freeze-commands → A1)
- Payment status enum extended (REQUIRES_SCA, HELD, CANCELLED + reason codes)
- Missing Kafka topics added to topology (ledger.events, account.freeze-commands, sanctions.list-updates)
- VoP initiator clarified (A3/A4 initiate, A2 forwards result)
- Semantic duplicate check ordering (before balance reservation)
- VoP caching for mandates (90-day TTL)
- VoP timeout behavior (may proceed with VOP_TIMEOUT flag)
- Reservation timeout elevated to Must (durable mechanism)
- A4↔A1 communication resolved (synchronous REST)
- VoP result propagation specified (vopResult in Avro schema)

**New sections added to system_requirements.md (3):**
- **Section 2.5: Cryptographic and Key Management** (KM-01 to KM-10) — algorithm specs (AES-256-GCM, TLS 1.2+, RSA-2048+), JWE spec (RSA-OAEP-256 + A256GCM), key rotation, key storage, key compromise procedure, Kafka message-level HMAC signing, webhook replay protection (timestamp + nonce), XML parser hardening (XXE prevention), API rate limiting per endpoint, SQL injection prevention
- **Section 2.6: Regulatory Awareness** — summary table mapping 8 EU/EEA regulations to our technical implementations and out-of-scope organizational items. Covers GDPR (crypto-shredding for erasure), DORA, PSD2, EU IPR, AML Package, Settlement Finality, eIDAS, Norwegian context
- **Section 2.7: Resilience and Crash Recovery** (RES-01 to RES-11) — balance reservation timeout (Must, durable), atomic double-entry events (compound Kafka message), Transactional Outbox Pattern, batch clearing restartability, idempotent message processing, atomic sanctions list swap, Kafka durability (replication 3, min ISR 2, acks=all, infinite retention for ledger events), PostgreSQL durability, Dead Letter Queues, graceful shutdown with in-flight payments, late-arriving fraud responses

**EPC 2025 timing update (~35 edits across 12 files):**
- All references to "10-second SLA" updated to "5-second execution / 7-second hard limit / 9-second confirmation" (EPC 2025 SCT Inst rulebook v1.1)
- Time budgets rewritten in UC-08 and extensions/instant_payments.md
- Monitoring thresholds tightened (p95 alert at 4s, p99 alert at 5s)
- Sub-project #11 requirements and use cases updated
- CERTIFICATIONS.md action item marked as DONE

**Additional fixes:**
- PII minimization in fraud scoring (A5-F12): scoring pipeline uses anonymized account IDs; case management enriches from A1 at display time (GDPR Art. 5(1)(c))
- Timezone specification: all velocity rule windows use UTC (DATA-03 updated)
- ISO 20022 version specified: CBPR+ 2023 edition, ECL 2024 code lists (DATA-04 updated)

### Review resolution log
Full tracking of every issue at `space/reviews/REVIEW_RESOLUTION.md`

---

## 5. Key Architectural Decisions

| Decision | Rationale |
|----------|-----------|
| **Event sourcing + CQRS** for ledger (A1) | Kafka as event store (target company alignment: DNB, Tietoevry, SpareBank 1). PostgreSQL for projections. Immutable audit trail |
| **Dual-mode clearing** (A2) | Spring Batch for batch netting + Kafka Streams for instant. Mirrors NICS (Mastercard Oslo). Separate Kafka topics prevent head-of-line blocking |
| **Three-layer fraud detection** (A5) | Sanctions (Bloom + Aho-Corasick + fuzzy) → velocity rules → ML (DJL). Sanctions bypasses fraud on HIT. PII-minimized scoring |
| **OAuth2 + SCA exemption engine** (A4) | Spring Authorization Server. 4 exemption types (low-value, TRA, trusted beneficiary, recurring). VRP with parameter enforcement |
| **VoP as separate service** (A6) | External API (other PSPs query us) + internal API. Cannot be part of A5 (different lifecycle, availability requirements) |
| **Crypto-shredding for GDPR erasure** | Per-account encryption keys. Delete key to "erase" — events remain but PII irrecoverable. Resolves tension with immutable event log |
| **Transactional Outbox OR Kafka-first** | Left as implementation decision (RES-03). Both solve dual-write problem |
| **ISO 20022 only (no MT format)** | MT is being retired (SWIFT Nov 2025). Deliberate simplification for portfolio scope |
| **EPC 2025 SCT Inst timing** | 5s execution / 7s hard limit / 9s confirmation. Our ~2-4s happy path is well within target |
| **Semantic duplicate detection** | Warn but don't block (learned from Allevo FinTP). Many legitimate payments look like duplicates |
| **Reconciliation** | External references on ledger events. Daily comparison against A2 settlement reports. Discrepancies are operationally critical |
| **NAV mock-oauth2-server for testing** | Deliberate Norwegian ecosystem signal. Production-quality test tool |
| **Pact contract testing** | Tietoevry requirement. Professional microservice testing maturity |
| **DJL for ML inference** | Sportradar uses DJL on EKS. Rare, high-signal skill for portfolio |

---

## 6. Target Company Alignment

| Company | How our system aligns | Key evidence |
|---------|----------------------|-------------|
| **Mastercard Oslo** | Our System A IS a simplified version of their NICS + eFaktura + AvtaleGiro + P27 | ISO 20022, batch netting, instant clearing, bill payment, reconciliation |
| **DNB** | Kafka backbone, event-driven microservices, fraud/AML, Open Banking APIs | Same architecture patterns, same Kafka-centric design |
| **SpareBank 1** | PSD2 BerlinGroup APIs, Kotlin/Java, Kafka, continuous deployment | Same API standard, same tech stack, testing culture |
| **Tietoevry Banking** | Event sourcing/CQRS, Pact contract testing, Banking-as-a-Service | Direct architectural match for ledger and testing approach |
| **Sportradar** | DJL for ML fraud scoring, real-time event processing | Same ML inference library, same streaming architecture |
| **Skatteetaten** | Spring Boot platform starter, Gradle plugin, Maven Central publishing | Our #4 is modeled directly on their Aurora platform |
| **NAV** | mock-oauth2-server, token-support | Used in our test infrastructure |

---

## 7. What's Next

### Immediate next steps
1. **Connect sub-project requirements to master system** — cross-reference requirement IDs (partially done via NAVIGATION.md)
2. **Begin implementation** — start with #4 (Spring Boot Starter) per build order
3. **Set up Git repository** — monorepo structure from Section 11 of system_requirements.md

### Build order (from PROJECT_SUGGESTIONS.md)
1. #4 Spring Boot Starter (foundation)
2. #10 CI/CD Security Pipeline (wraps everything)
3. #1 Fraud Detection Engine (highest company coverage)
4. #2 PSD2 Open Banking API
5. #21 Verification of Payee
6. #9 Account Ledger
7. #11 Clearing Engine
8. #12 R2P / Bill Payment
→ **System A complete (6 services)**

### Regulatory timeline awareness
- **October 2025**: EPC 2025 SCT Inst rulebook v1.1 effective. VoP mandatory for eurozone
- **November 2025**: SWIFT CBPR+ coexistence period ends (ISO 20022 only)
- **2027-2028**: PSD3/PSR expected implementation deadline
- **2028-2029**: Digital Euro earliest possible launch
