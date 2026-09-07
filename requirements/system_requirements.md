# Nordic Payment Processing Infrastructure — System Requirements

*System A from PROJECT_SUGGESTIONS.md*
*Target companies: Mastercard Oslo, DNB, SpareBank 1, Tietoevry Banking, Sportradar, Vipps*
*Version: 2.0 — Updated with EU Instant Payment Regulation compliance*

---

## 1. System Overview

A realistic Nordic payment processing infrastructure consisting of six integrated microservices. The system processes electronic payments end-to-end: from invoice submission through verification, clearing (batch and instant), settlement, fraud/sanctions screening, and Open Banking API access — all backed by an event-sourced account ledger.

The system mirrors the core functions of:
- **Mastercard Oslo** — AvtaleGiro (direct debit), eFaktura (electronic invoicing), NICS (Norwegian Interbank Clearing System), P27 (Nordic multi-currency payments)
- **DNB** — Open Banking APIs, fraud detection, Kafka-based event infrastructure
- **SpareBank 1** — PSD2 APIs (BerlinGroup standard), BankID-integrated consent flows
- **Tietoevry Banking** — Core banking platform, Banking-as-a-Service APIs
- **EU payment regulation** — Instant Payment Regulation 2024/886, PSD2/PSD3, AML Package

### 1.1 Sub-systems

| ID | Sub-system | Part 1 Project | Primary Function |
|----|-----------|---------------|------------------|
| A1 | Account Ledger | #9 | Event-sourced double-entry bookkeeping, multi-currency, balance reservations |
| A2 | Clearing Engine | #11 | Dual-mode: batch clearing (netting, ISO 20022) AND instant payment processing (EPC 2025 SLA: 5s execution / 7s hard limit, gross settlement). Includes liquidity management |
| A3 | Bill Payment & Request-to-Pay Service | #12 | eFaktura-style invoicing evolved toward SEPA Request-to-Pay (SRTP): accept, decline, defer, amend. Recurring mandates (AvtaleGiro). QR code initiation |
| A4 | PSD2 API Gateway | #2 | Open Banking AISP/PISP with OAuth2 consent, SCA with exemption engine, Variable Recurring Payments (VRP) |
| A5 | Fraud Detection & Sanctions Engine | #1 | Real-time transaction risk scoring (rules + anomaly + ML/DJL) AND real-time sanctions screening (EU/UN lists) |
| A6 | Verification of Payee Service | **#21 (NEW)** | Pre-payment name-IBAN verification. Requester role (check before sending) and responder role (answer queries about our accounts). Mandatory under EU IPR |

### 1.2 Architecture Overview

```
                                  +-------------------+
                                  |  A4: PSD2 Open    |
                                  |  Banking API      |
                                  |  + SCA Exemptions |
                                  |  + VRP            |
                                  +--------+----------+
                                           |
                          +----------------+----------------+
                          |                                 |
                          v                                 v
               +----------+---------+            +----------+---------+
               |  A6: Verification  |            |  A3: Request-to-Pay|
               |  of Payee (VoP)   |            |  & Bill Payment    |
               +----------+---------+            |  + QR codes        |
                          |                      +----------+---------+
                   name check                               |
                          |                                 |
                          v                                 v
               +----------+------+------+----------+---------+
               |         A2: Clearing Engine                  |
               |  ┌──────────────┬──────────────────┐        |
               |  │ Batch Path   │  Instant Path     │        |
               |  │ (netting,    │  (gross, 5-sec    │        |
               |  │  Spring Batch│   Kafka Streams)  │        |
               |  └──────────────┴──────────────────┘        |
               |  + Liquidity Management                      |
               +----------+------+------+--------------------+
                          |             |
               fraud/sanctions check    |
                          |             |
                          v             v
               +----------+---------+  +-----------+---------+
               |  A5: Fraud &       |  |  A1: Account Ledger |
               |  Sanctions Engine  |  |  (Event-Sourced)    |
               |  + ML scoring      |  |  + Balance holds    |
               |  + Sanctions lists |  |  + Multi-currency   |
               +--------------------+  +---------------------+
```

---

## 2. Cross-cutting Requirements

These requirements apply to ALL sub-systems.

### 2.1 Technology Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| Language | **Java 17+** (LTS) | Mandated by Skatteetaten; standard at DNB, Mastercard, Tietoevry |
| Framework | **Spring Boot 3.x** | Universal across all target companies |
| Messaging | **Apache Kafka** | DNB, SpareBank1, NAV backbone; Aiven-compatible configuration |
| Database | **PostgreSQL 15+** | Universal; with Flyway for schema migrations |
| API spec | **OpenAPI 3.0** via springdoc-openapi | Contract-first API design; SpareBank1, Mastercard publish OpenAPI specs |
| Build | **Gradle** (Kotlin DSL) | Skatteetaten standard; more flexible than Maven for multi-module |
| Containers | **Docker** + **docker-compose** | All services runnable locally with `docker-compose up` |
| CI/CD | **GitHub Actions** | NAV, SpareBank1, DNB, Gjensidige standard |
| Observability | **Micrometer + Prometheus** metrics, structured JSON logging | Universal; Skatteetaten aurora-prometheus pattern |
| Testing | **JUnit 5**, **Testcontainers** (Kafka, PostgreSQL), **Mockito**, **Pact** (contract testing) | Tietoevry explicitly uses Testcontainers and Pact |
| Stream processing | **Kafka Streams** | For instant payment path and fraud scoring low-latency pipeline |

### 2.2 Security Requirements (Cross-cutting)

| ID | Requirement | Rationale |
|----|------------|-----------|
| SEC-01 | All inter-service communication via Kafka must use SASL/SSL | Encrypt data in transit between services |
| SEC-02 | All REST APIs must require authentication (OAuth2 Bearer token or API key) | No unauthenticated access to any endpoint |
| SEC-03 | All sensitive data at rest must be encrypted (PostgreSQL column-level or transparent encryption) | PCI DSS and GDPR requirement |
| SEC-04 | All API responses must include security headers: `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Content-Security-Policy` | OWASP best practice |
| SEC-05 | No secrets in source code or environment variables. Use Spring Cloud Config or Kubernetes Secrets | Prevent credential leakage |
| SEC-06 | All dependencies must be scanned for known CVEs (OWASP Dependency-Check or Trivy). SBOM generated in CycloneDX format | Supply chain security |
| SEC-07 | Structured audit logging for all state-changing operations: who, what, when, from where, outcome | Regulatory requirement for financial systems |
| SEC-08 | Rate limiting on all external-facing APIs | Prevent abuse and DoS |
| SEC-09 | Input validation on all API boundaries (Bean Validation / JSR 380) | Prevent injection attacks |
| SEC-10 | CORS must be explicitly configured per sub-system (deny by default) | Prevent cross-origin attacks |
| SEC-11 | Every payment must pass sanctions screening before settlement (cannot be skipped) | AML legal obligation |
| SEC-12 | Every credit transfer must have a VoP check initiated before execution (mandatory under EU IPR). **VoP is initiated by A3 or A4** (whichever receives the payment request) before publishing to Kafka. A2 does NOT repeat VoP — it forwards the VoP result to A5 via the `vopResult` field in the payment instruction. If VoP times out, payment may proceed with `VOP_TIMEOUT` flag and is flagged for review | EU Instant Payment Regulation |

### 2.3 Operational Requirements

| ID | Requirement | Rationale |
|----|------------|-----------|
| OPS-01 | Each sub-system exposes `/actuator/health`, `/actuator/readiness`, `/actuator/prometheus` | Standard Spring Boot Actuator endpoints for K8s probes and monitoring |
| OPS-02 | Graceful shutdown: drain in-flight requests before stopping | Zero-downtime deployments |
| OPS-03 | All services must start independently (no startup ordering dependency) | Resilient microservice startup |
| OPS-04 | docker-compose must bring up the entire system with `docker-compose up` | Reviewers and interviewers must be able to run it in <5 minutes |
| OPS-05 | Each service must have its own PostgreSQL schema (database-per-service pattern) | Loose coupling between services |
| OPS-06 | Configuration via environment variables or Spring profiles (not hardcoded) | 12-factor app compliance |
| OPS-07 | Each sub-system must have a README with: purpose, how to run, API documentation link, architecture decisions | Portfolio project must be self-documenting |
| OPS-08 | Instant payment path must be available **24/7/365** — no scheduled downtime | EU IPR requirement |

### 2.4 Data Standards

| ID | Requirement | Rationale |
|----|------------|-----------|
| DATA-01 | All monetary amounts represented as `BigDecimal` (Java) or JSR 354 `MonetaryAmount`. Never `double` or `float` | Precision requirement for financial calculations |
| DATA-02 | All monetary amounts accompanied by ISO 4217 currency code (NOK, EUR, USD, SEK, DKK) | Multi-currency support (P27 platform inspiration) |
| DATA-03 | All timestamps in UTC, formatted as ISO 8601 in APIs. All velocity rule windows use UTC. Account timestamps normalized to UTC on ingestion | Unambiguous time handling across time zones |
| DATA-04 | ISO 20022 messages must conform to **CBPR+ 2023 edition**, using external code lists from **ISO 20022 ECL 2024 Edition**. Message types: pain.001, pacs.008, pacs.002, pacs.004, pain.013/014, camt.053. Field-level specifications documented in `docs/iso20022/` during implementation | Mastercard Oslo works with ISO 20022 daily; SWIFT CBPR+ alignment |
| DATA-05 | Account numbers follow IBAN format (or simplified 11-digit Norwegian account number). Norwegian IBAN: `NO` + 2 check digits + 11-digit account number | Norwegian banking standard |
| DATA-06 | All Kafka events use Avro serialization with Schema Registry. Schema compatibility mode: BACKWARD (consumers can read old and new). Schema evolution documented per topic | Production-grade event schema management |
| DATA-07 | VoP result field (`vopResult`) must be included in the PaymentInstruction Avro schema and propagated through the pipeline: A3/A4 → Kafka → A2 → A5 | VoP integration across service boundaries (CON review INT-2) |

### 2.5 Cryptographic and Key Management Requirements

*Added based on OWASP, security, and certification reviews*

| ID | Requirement | Rationale |
|----|------------|-----------|
| KM-01 | **Algorithm specifications**: AES-256-GCM for data at rest, TLS 1.2+ (prefer 1.3) for data in transit, RSA-2048+ or ECDSA P-256+ for signing, SHA-256+ for hashing. No MD5, SHA-1, DES, 3DES, RC4 | PCI DSS v4.0 Requirement 4.2; OWASP A02:2021 |
| KM-02 | **JWE specification**: alg=RSA-OAEP-256, enc=A256GCM for payload encryption in A4. Key derivation via HKDF-SHA256 for symmetric keys | Mastercard client-encryption-java alignment |
| KM-03 | **Key rotation**: JWT signing keys rotated every 90 days with 30-day overlap (old key still valid for verification). Kafka SASL credentials rotated every 180 days. Database encryption keys rotated annually | Key management best practice |
| KM-04 | **Key storage**: Production keys stored in KMS (AWS KMS, Azure Key Vault, or HashiCorp Vault). Development/portfolio: keys in environment variables or Docker secrets (never in source code) | SEC-05 enforcement |
| KM-05 | **Key compromise procedure**: documented incident response for compromised keys — revoke, rotate, re-encrypt affected data, notify affected parties. Maximum response time: 4 hours for signing keys, 24 hours for encryption keys | DORA alignment; SWIFT CSP |
| KM-06 | **Kafka message-level integrity**: payment instruction and settlement event messages must include HMAC-SHA256 signature in Kafka headers. Consumers verify signature before processing. Protects against broker compromise | OWASP A08:2021; defense-in-depth beyond SASL/SSL |
| KM-07 | **Webhook replay protection**: webhook payloads must include `issued_at` timestamp. Recipient must reject payloads older than 5 minutes. Include unique `webhook_id` (nonce) per delivery. Merchant must verify HMAC signature AND timestamp before processing | OWASP A02:2021 |
| KM-08 | **XML parser hardening**: all ISO 20022 XML parsing must disable DTD processing, external entities (XXE), and XInclude. Use JAXB with `XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES=false` | OWASP A03:2021 Injection |
| KM-09 | **API rate limiting specification**: A3 merchant API: 100 req/min per API key. A4 TPP API: 100 req/min per TPP (configurable). A6 VoP responder: 100 req/min per querying PSP. All return HTTP 429 with `Retry-After` header | OWASP A04:2021; DoS prevention |
| KM-10 | **SQL injection prevention**: all database queries must use parameterized statements or JPA/Spring Data repositories. No string concatenation in SQL. This applies especially to reconciliation queries (LED-F38) | OWASP A03:2021 |

### 2.6 Regulatory Awareness

*This portfolio system implements technical foundations for regulatory compliance. Full organizational compliance (GDPR DPO appointment, DORA ICT risk framework, AML compliance officer) is out of scope for a portfolio project but is referenced here to demonstrate regulatory awareness.*

| Regulation | Applicable Articles | Our Technical Implementation | Out of Scope (Organizational) |
|-----------|-------------------|------------------------------|-------------------------------|
| **GDPR** (Regulation 2016/679) | Art. 5 (data minimization), Art. 6 (lawful basis), Art. 17 (right to erasure), Art. 25 (data protection by design), Art. 32 (security), Art. 33 (breach notification) | SEC-03 (encryption at rest), SEC-07 (audit logging), STARTER-F12 (PII redaction in logs), A1 event sourcing (immutable audit trail — right to erasure handled via crypto-shredding: encrypt PII with per-account key, delete key to "erase"). Data retention: payment data 5 years (AML), audit logs 5 years (PCI DSS 10.7), PII minimized in fraud scoring (A5 uses anonymized account ID for velocity rules) | DPO appointment, DPIA process, data subject access request workflow, breach notification to supervisory authority |
| **DORA** (Regulation 2022/2554) | Art. 5-16 (ICT risk management), Art. 17-23 (incident reporting), Art. 24-27 (resilience testing) | OPS-01 (health checks), OPS-02 (graceful shutdown), OPS-08 (24/7 availability), Prometheus/Grafana (monitoring), structured audit events (incident detection), circuit breakers (resilience), Kafka replication (durability) | ICT risk management framework, incident classification team, annual resilience testing program, third-party ICT risk assessment |
| **PSD2** (Directive 2015/2366) + RTS on SCA | Art. 97 (SCA), Art. 5 RTS (dynamic linking), Art. 10 RTS (90-day re-auth) | A4: full OAuth2 consent + SCA + 4 exemption types + dynamic linking (SCA challenge displays amount + creditor). VRP consent | PSP licensing, complaint handling, passporting |
| **EU IPR** (Regulation 2024/886) | Art. 5a (instant), Art. 5c (VoP), Art. 5d (sanctions screening timing) | A2: instant path with EPC 2025 SLA (5s execution / 7s hard limit). A6: VoP requester + responder. A5: sanctions screening < 50ms. Sanctions screening cannot be skipped | Price parity enforcement, regulatory reporting to NCA |
| **AML Package** (AMLR 2024) | Art. 50-60 (transaction monitoring), Art. 69 (sanctions) | A5: real-time transaction monitoring (velocity + anomaly + ML). A5: sanctions screening (EU/UN lists). Note: CDD (Customer Due Diligence) is an onboarding concern — our system processes transactions, not customer onboarding. CDD boundary: "A5 monitors transactions; A5 does NOT perform KYC/CDD. CDD results from upstream systems may be consumed as risk input" | CDD/EDD procedures, SAR filing to FIU, tipping-off prevention, AML compliance officer |
| **Settlement Finality Directive** (98/26/EC) | Art. 3 (moment of finality) | A2: settlement finality event (CLR-I13) — distinct from SETTLED status. For instant payments: finality is at the moment of credit in A1. For batch: finality is at clearing cycle close. Settlement finality timestamp included in pacs.002 confirmation | Designation as a "system" under SFD (requires central bank approval) |
| **eIDAS** (Regulation 910/2014) | Art. 28 (QWAC), Art. 35 (QSEAL) | A4: mTLS simulates QWAC certificate validation. In production, TPPs present QWAC certificates issued by QTSPs. Note: eIDAS2/EUDIW (European Digital Identity Wallet) may affect future auth flows | QTSP contract, certificate lifecycle management |
| **Norwegian context** | Hvitvaskingsloven (AML Act), Finansforetaksloven | Norwegian IBAN validation (NO + 2 check + 11 digits, mod-11 checksum). Sanctions screening includes Norwegian national list. Compliance with Finanstilsynet guidelines | Finanstilsynet PSP license, Norwegian AML officer |

### PSD2 Unauthorized Transaction Refund (Article 73)

| ID | Requirement | Priority |
|----|------------|----------|
| PSD2-REF01 | On notification of an unauthorized transaction, the PSP must refund the payer by end of next business day | Must |
| PSD2-REF02 | Refund amount = full transaction amount minus maximum EUR 50 payer liability (Article 74), unless fraud or gross negligence by payer | Must |
| PSD2-REF03 | Refund mechanism uses the existing pacs.004 return flow (A2) with compound event reversal (A1) | Must |
| PSD2-REF04 | Authentication evidence must be retained to support burden of proof (Article 72): SCA method, challenge/response, timestamps, IP address, device fingerprint. Retention: 13 months minimum (PSD2 dispute window) | Must |

### 2.7 Resilience and Crash Recovery Requirements

*Added based on computational stability review. These address data consistency under failure conditions.*

| ID | Requirement | Rationale |
|----|------------|-----------|
| RES-01 | **Balance reservation timeout**: reservations that are not confirmed or released within **30 seconds** (configurable) must be automatically released. Timeout mechanism must be durable (database-scheduled or Kafka-delayed message, NOT in-memory timer). **Priority: Must** | Prevents permanent fund locking if A2 crashes mid-payment (computational stability CRITICAL-01) |
| RES-02 | **Atomic double-entry events**: debit and credit for a single transaction must be published as a **single compound Kafka message** (both entries in one event). Never publish debit without credit. Use Kafka transactions if separate messages required | Prevents accounting invariant violation on crash between events (CRITICAL-02) |
| RES-03 | **Transactional Outbox Pattern**: when a service writes to PostgreSQL AND publishes to Kafka, use the Transactional Outbox — write the event to an `outbox` table in the same DB transaction, then a separate relay publishes to Kafka. Alternatively, use Kafka as sole source of truth and derive PostgreSQL state from consumers | Solves dual-write consistency problem (CRITICAL-03) |
| RES-04 | **Batch clearing restartability**: Spring Batch clearing jobs must be restartable. Each payment's status tracked individually within a cycle. If A2 crashes mid-batch, restart resumes from the last uncommitted payment, not from the beginning. Spring Batch `ExecutionContext` must be persisted | Prevents partial settlement without recovery (CRITICAL-04) |
| RES-05 | **Idempotent message processing**: every Kafka consumer must deduplicate by `messageId` (unique per message) or `transactionId`. Use Kafka exactly-once semantics (`processing.guarantee=exactly_once_v2`) within each service. Cross-service exactly-once achieved via idempotency keys | Prevents double-processing across service boundaries (CRITICAL-05) |
| RES-06 | **Atomic sanctions list swap**: when sanctions lists are updated, build new in-memory structures (Bloom filter, Aho-Corasick index, fuzzy index) in a staging area. Swap to production atomically (reference swap) only after ALL three structures are rebuilt and verified. Track list version per screening decision | Prevents inconsistent screening during rebuild (CRITICAL-06) |
| RES-07 | **Kafka durability**: replication factor >= 3, min.insync.replicas >= 2, acks=all for all payment-related topics. Retention: `ledger.events` topic has infinite retention (`retention.ms=-1`). Other topics: 7 days minimum | Prevents data loss (CRITICAL-07) |
| RES-08 | **PostgreSQL durability**: synchronous_commit=on. WAL archiving enabled. Streaming replication with at least 1 standby (for portfolio: documented as production requirement, single instance acceptable for demo) | Prevents data loss |
| RES-09 | **Dead Letter Queue**: every Kafka consumer must route unprocessable messages (deserialization error, schema mismatch, unexpected error) to a `{topic}.dlq` topic after N retries (default: 3). DLQ messages include original payload + error details. Monitor DLQ depth via Prometheus | Prevents message loss and consumer blocking (HIGH-08) |
| RES-10 | **Graceful shutdown with in-flight payments**: during shutdown, complete or abort all in-flight instant payments (confirm or release balance reservations) before closing Kafka consumers. Do not leave reservations orphaned | Prevents orphaned reservations during deployment (HIGH-01) |
| RES-11 | **Late-arriving fraud responses**: if A5 responds with SANCTIONS_HIT after A2 has already settled the payment (race condition on timeout), create a retrospective alert (`SETTLED_BEFORE_SCREENING`) and initiate payment return procedure (pacs.004) | Handles async race condition (MEDIUM-04) |

---

## 3. Sub-system A1: Event-Sourced Account Ledger

### 3.1 Functional Requirements

| ID | Requirement | Priority |
|----|------------|----------|
| A1-F01 | Maintain account balances using double-entry bookkeeping: every transaction is a debit from one account and credit to another | Must |
| A1-F02 | All balance changes stored as immutable events in Kafka (append-only event log) | Must |
| A1-F03 | Current balances are CQRS read projections over the event stream, stored in PostgreSQL | Must |
| A1-F04 | Support multi-currency accounts (NOK, EUR, USD, SEK, DKK) with ISO 4217 codes | Must |
| A1-F05 | Expose REST API: GET account balance, GET transaction history (paginated), GET account statement (date range) | Must |
| A1-F06 | Command API: POST debit/credit pair (internal, consumed by other sub-systems via Kafka) | Must |
| A1-F07 | Event schema versioned with backward-compatible evolution (Avro or JSON Schema) | Must |
| A1-F08 | Support snapshot mechanism: periodically materialize balances to avoid replaying all events | Should |
| A1-F09 | Idempotent event processing: duplicate events must not alter balances | Must |
| A1-F10 | Account creation and closure lifecycle (ACTIVE, FROZEN, CLOSED states) | Must |
| A1-F11 | **Balance reservation (hold)**: tentatively reserve funds for in-flight instant payments. Three-state: AVAILABLE → RESERVED → CONFIRMED (or RELEASED). Note: CONFIRMED aligns with Avro event type BALANCE_CONFIRMED | Must |
| A1-F12 | **Available vs. booked balance**: available = booked minus reserved. APIs return both | Must |
| A1-F13 | Account statement generation in ISO 20022 camt.053 format | Should |
| A1-F14 | Account freeze: when frozen, reject debits but allow credits (don't lose incoming payments). **Consume `account.freeze-commands` Kafka topic** — on receipt of freeze command from A5, transition account to FROZEN state | Must |
| A1-F15 | **External reference ID on events**: every balance change event must carry an `externalReference` field linking to the clearing cycle ID and settlement reference from A2. Required for reconciliation | Must |
| A1-F16 | **Reconciliation status per transaction**: UNRECONCILED (just settled internally) → RECONCILED (confirmed by clearing house) → DISCREPANCY (mismatch detected). Queryable via API | Must |
| A1-F17 | **Reconciliation API**: POST `/api/v1/reconciliation/run` — accepts external settlement report (from A2 camt.053), compares against internal ledger entries, produces reconciliation result with list of matches and discrepancies | Must |

### 3.2 Non-functional Requirements

| ID | Requirement | Target |
|----|------------|--------|
| A1-NF01 | Query latency for current balance | < 50ms (p95) |
| A1-NF02 | Event processing throughput | >= 1,000 events/second on single instance |
| A1-NF03 | Event ordering | Guaranteed per account (Kafka partition key = account ID) |
| A1-NF04 | Projection rebuild | Must be possible to rebuild all projections from event log |
| A1-NF05 | Balance reservation latency (for instant payments) | < 100ms |

### 3.3 Security Requirements

| ID | Requirement |
|----|------------|
| A1-S01 | Account balance queries require valid OAuth2 token with `accounts:read` scope |
| A1-S02 | Balance changes (commands) only accepted from internal services (service-to-service auth) |
| A1-S03 | Audit log entry for every balance query and every balance change |
| A1-S04 | Account holder name stored for VoP responder queries (A6 queries A1 for name matching) |

---

## 4. Sub-system A2: Clearing Engine (Dual-mode: Batch + Instant)

### 4.1 Functional Requirements — Batch Path

| ID | Requirement | Priority |
|----|------------|----------|
| A2-F01 | Accept payment instructions from Kafka topics (published by A3, A4) | Must |
| A2-F02 | Validate payment instructions against ISO 20022 schema (pain.001/pacs.008) | Must |
| A2-F03 | Perform **multilateral netting**: calculate net debit/credit position per bank for each clearing cycle | Must |
| A2-F04 | Support **batch clearing**: collect instructions over configurable window, process as batch | Must |
| A2-F05 | Produce settlement files in ISO 20022 format (pacs.008 credit transfer, camt.053 statement) | Must |
| A2-F06 | Publish settlement events to Kafka (consumed by A1 Account Ledger) | Must |
| A2-F07 | Route each transaction through A5 (fraud + sanctions) before settlement | Must |
| A2-F08 | Reject payments flagged as fraudulent or sanctioned | Must |
| A2-F09 | Maintain clearing cycle history: payments included, net positions, settlement outcome | Must |
| A2-F10 | Expose REST API: GET clearing cycle status, GET settlement report, GET payment status | Must |
| A2-F11 | Idempotent payment processing: same instruction submitted twice must not clear twice | Must |
| A2-F12 | **Semantic duplicate detection**: detect probably-duplicate payments even with different idempotency keys. Match: same debtor + same creditor + same amount + same currency within configurable time window (default: 10 minutes). Return warning (not block). **Ordering: semantic duplicate check runs BEFORE balance reservation** — don't lock funds while waiting for payer to confirm duplicate override | Must |
| A2-F13 | **Reconciliation-ready settlement output**: every settlement event must include a unique `settlementReference` and `clearingCycleId` that A1 stores as `externalReference` for reconciliation. Settlement reports (camt.053) must be machine-parseable for automated reconciliation | Must |

### 4.2 Functional Requirements — Instant Path (NEW)

| ID | Requirement | Priority |
|----|------------|----------|
| A2-F20 | Process instant credit transfers end-to-end: **execution target < 5 seconds**, hard limit **< 7 seconds**, confirmation **< 9 seconds** (EPC 2025 SCT Inst rulebook v1.1) | Must |
| A2-F21 | Available **24/7/365** — no maintenance windows | Must |
| A2-F22 | Maximum transaction amount: **EUR 100,000** (configurable) | Must |
| A2-F23 | Settlement is **immediate, irrevocable, and final** — gross settlement per transaction (no netting) | Must |
| A2-F24 | Return **positive confirmation** (pacs.002) within 9-second window (EPC 2025 confirmation deadline) | Must |
| A2-F25 | If processing cannot complete within **7 seconds** (hard limit), **reject** with timeout reason code (no limbo state) | Must |
| A2-F26 | Instant payments use dedicated Kafka topics (separate from batch to avoid head-of-line blocking) | Must |
| A2-F27 | Before instant settlement: request balance reservation from A1 (tentative hold) | Must |
| A2-F28 | On successful settlement: confirm reservation → debit. On failure: release reservation | Must |
| A2-F29 | Support pacs.028 (PaymentStatusRequest) for status inquiry | Should |
| A2-F30 | Support pacs.004 (PaymentReturn) for post-settlement return requests | Should |

### 4.3 Functional Requirements — Liquidity Management

| ID | Requirement | Priority |
|----|------------|----------|
| A2-F40 | Each participating PSP has a pre-funded settlement account with tracked balance | Must |
| A2-F41 | Before instant settlement: verify sufficient liquidity in settlement account | Must |
| A2-F42 | Threshold alerts: warn when liquidity drops below configurable minimum | Should |
| A2-F43 | Expose REST API: GET liquidity position, GET liquidity history | Should |

### 4.4 Non-functional Requirements

| ID | Requirement | Target |
|----|------------|--------|
| A2-NF01 | Batch clearing cycle for 10,000 instructions | < 30 seconds |
| A2-NF02 | **Instant payment end-to-end latency** | **Execution < 5 seconds** (target), **hard limit < 7 seconds**, **confirmation < 9 seconds** (EPC 2025 SCT Inst rulebook v1.1) |
| A2-NF03 | Instant payment fraud+sanctions check budget | < 500ms |
| A2-NF04 | Zero payment loss: every instruction must eventually settle or be explicitly rejected | Exactly-once semantics |
| A2-NF05 | Instant path availability | 99.99% (24/7/365) |
| A2-NF06 | Instant path throughput | >= 500 TPS |

### 4.5 Security Requirements

| ID | Requirement |
|----|------------|
| A2-S01 | Payment instructions must include digital signature or HMAC for non-repudiation |
| A2-S02 | Settlement files signed (JWS or XML digital signature) |
| A2-S03 | Only authorized services may submit payment instructions (mTLS or JWT with `payments:initiate` scope) |
| A2-S04 | All processing decisions logged to immutable audit trail |
| A2-S05 | Sanctions screening must execute on every payment — cannot be skipped even on timeout (reject if screening unavailable) |

---

## 5. Sub-system A3: Request-to-Pay & Bill Payment Service

*Evolved from eFaktura toward SEPA Request to Pay (SRTP) model*

### 5.1 Functional Requirements

| ID | Requirement | Priority |
|----|------------|----------|
| A3-F01 | **Merchant/Creditor API**: POST payment request (amount, currency, due date, reference, description) | Must |
| A3-F02 | **Consumer/Debtor API**: GET pending requests. Respond with: **ACCEPT**, **DECLINE** (with reason), **DEFER** (accept but pay later, specify date), or **REQUEST_AMENDMENT** (propose different amount/date) | Must |
| A3-F03 | On ACCEPT: consumer chooses payment method — **INSTANT** (SCT Inst path) or **STANDARD** (batch path). Default: instant if available | Must |
| A3-F04 | Accepted requests trigger payment initiation event to Kafka | Must |
| A3-F05 | Request lifecycle: CREATED → PENDING → ACCEPTED → PAYMENT_INITIATED → SETTLED / REJECTED / DECLINED / EXPIRED / DEFERRED | Must |
| A3-F06 | Webhook notifications to merchant on status changes, with HMAC-SHA256 signature and retry (exponential backoff, max 5 retries) | Must |
| A3-F07 | Idempotency key on request creation (POST): duplicates return existing request | Must |
| A3-F08 | **Recurring mandates (AvtaleGiro)**: consumer pre-authorizes auto-acceptance for a merchant up to a max amount per period. **VoP caching for mandates**: VoP result may be cached for recurring mandate payments to the same beneficiary. Re-check required if: > 90 days since last VoP check, mandate details modified, or beneficiary PSP notifies of account holder name change | Must |
| A3-F09 | **QR code generation**: generate EPC-standard QR code (EPC069-12) per payment request. Consumer scans → sees request → approves → payment executes | Should |
| A3-F10 | Request expiry: not accepted by due date → automatically EXPIRED | Should |
| A3-F11 | Amendment flow: consumer proposes change → merchant can accept or reject amendment | Should |
| A3-F12 | ISO 20022 message support: pain.013 (CreditorPaymentActivationRequest), pain.014 (status) | Should |
| A3-F13 | Search/filter requests: by status, date range, merchant, amount | Should |

### 5.2 Non-functional Requirements

| ID | Requirement | Target |
|----|------------|--------|
| A3-NF01 | Request creation API latency | < 200ms (p95) |
| A3-NF02 | Webhook delivery first attempt | < 5 seconds after status change |
| A3-NF03 | Concurrent API requests | >= 100 |

### 5.3 Security Requirements

| ID | Requirement |
|----|------------|
| A3-S01 | Merchant API authenticated via API key (issued per merchant) |
| A3-S02 | Consumer API authenticated via OAuth2 Bearer token with `requests:read` and `requests:respond` scopes |
| A3-S03 | Webhook payloads signed with HMAC-SHA256 using per-merchant secret |
| A3-S04 | Webhook endpoints must be HTTPS only |
| A3-S05 | Recurring mandate creation requires explicit consumer consent with revocation capability |
| A3-S06 | Payment requests contain PII (debtor reference, account): encrypted at rest |

---

## 6. Sub-system A4: PSD2 API Gateway

### 6.1 Functional Requirements

| ID | Requirement | Priority |
|----|------------|----------|
| A4-F01 | **AISP**: GET accounts list, GET account balance (available + booked), GET transactions (paginated) | Must |
| A4-F02 | **PISP**: POST payment initiation (single immediate SCT or SCT Inst), GET payment status | Must |
| A4-F03 | **OAuth2 Consent Flow**: authorization endpoint where account holder grants TPP access | Must |
| A4-F04 | Consent management: scopes, expiry (max 90 days), revocation, consent dashboard | Must |
| A4-F05 | **Strong Customer Authentication (SCA)**: two-factor (knowledge + possession) during consent | Must |
| A4-F06 | **SCA Exemption Engine**: automatically determine if SCA can be skipped for a transaction | Must |
| A4-F07 | SCA exemptions supported: low-value (<EUR 30, cumulative limits), TRA (risk-based), trusted beneficiary, recurring (same amount + payee), merchant-initiated (MIT) | Must |
| A4-F08 | TPP registration: client_id, client_secret, redirect URIs, client certificate | Must |
| A4-F09 | Rate limiting per TPP: configurable requests-per-minute, HTTP 429 | Must |
| A4-F10 | Account data sourced from A1 (Account Ledger) via **synchronous internal REST calls** (not Kafka — AISP queries require immediate response) | Must |
| A4-F11 | Payment initiation published to Kafka (consumed by A2 Clearing Engine) | Must |
| A4-F12 | PISP must specify: `service_level: INSTANT` or `STANDARD`. Instant triggers SCT Inst path in A2 | Must |
| A4-F13 | Before payment initiation: trigger VoP check via A6 | Must |
| A4-F14 | **Variable Recurring Payments (VRP)**: consent with parameter bounds (max amount per payment, max cumulative per period, max frequency, specific payee, date range). Payments within bounds skip per-payment SCA | Should |
| A4-F15 | VRP enforcement: bank-side validation of each payment against consent parameters. Reject if exceeded | Should |
| A4-F16 | BerlinGroup NextGenPSD2-compliant API surface | Should |
| A4-F17 | Consent dashboard: list/revoke active consents and VRP mandates | Should |

### 6.2 Non-functional Requirements

| ID | Requirement | Target |
|----|------------|--------|
| A4-NF01 | API response latency | < 300ms (p95) |
| A4-NF02 | OAuth2 token issuance | < 500ms |
| A4-NF03 | SCA exemption decision | < 50ms |

### 6.3 Security Requirements

| ID | Requirement |
|----|------------|
| A4-S01 | OAuth2 Authorization Server using Spring Authorization Server |
| A4-S02 | JWT access tokens signed with RS256; token expiry max 15 minutes |
| A4-S03 | Refresh tokens: rotating, single-use, stored hashed in database. Reuse detection → revoke entire token family |
| A4-S04 | Token exchange / On-Behalf-Of (OBO) flow for service-to-service delegation |
| A4-S05 | JWE (JSON Web Encryption) for sensitive fields in API responses (optional, Mastercard pattern) |
| A4-S06 | SCA: knowledge factor (PIN) + possession factor (TOTP). Dynamic linking for payments (display amount + payee during SCA) |
| A4-S07 | Consent stored with: TPP identity, granted scopes, account holder, expiry, creation timestamp |
| A4-S08 | All consent grants and revocations logged to immutable audit trail |
| A4-S09 | TPP client certificate validation (mTLS) for PSD2 compliance simulation |
| A4-S10 | Test suite must use `no.nav.security:mock-oauth2-server` for OAuth2 testing |
| A4-S11 | PKCE (Proof Key for Code Exchange) required on authorization requests |
| A4-S12 | SCA exemption decisions logged to audit trail with exemption type and rationale |
| A4-S13 | VRP consent parameters immutable after creation — consumer must create new consent to change bounds |

---

## 7. Sub-system A5: Fraud Detection & Sanctions Engine

*Extended with real-time sanctions screening*

### 7.1 Functional Requirements — Fraud Detection

| ID | Requirement | Priority |
|----|------------|----------|
| A5-F01 | Consume transaction events from Kafka (both batch and instant paths) | Must |
| A5-F02 | Apply **velocity rules**: flag if account has > N transactions in M minutes, or total amount > threshold | Must |
| A5-F03 | Apply **statistical anomaly detection**: flag if amount deviates > 3 stddev from account history | Must |
| A5-F04 | Apply **ML model scoring** via Deep Java Library (DJL) | Should |
| A5-F05 | Produce combined fraud score (0.0-1.0) + triggered rules as explanation | Must |
| A5-F06 | Publish enriched transaction with fraud score back to Kafka | Must |
| A5-F07 | **Case Management API**: GET alerts (paginated, filterable), PUT approve/dismiss | Must |
| A5-F08 | Alert lifecycle: OPEN → INVESTIGATING → CONFIRMED_FRAUD / DISMISSED | Must |
| A5-F09 | Configurable thresholds: auto-block, manual review, auto-pass | Must |
| A5-F10 | Integrate VoP result from A6: NO_MATCH override feeds into fraud score as high-risk signal | Must |
| A5-F11 | **Semantic duplicate flag from A2**: if A2 detects a probable duplicate and the payer overrides the warning, treat as fraud risk signal (+0.2). Rapid duplicate submissions are a pattern in account takeover attacks | Must |
| A5-F12 | **PII minimization**: the automated scoring pipeline (velocity rules, anomaly detection, ML) must operate on **anonymized account IDs**, amounts, timestamps, and patterns only — NOT account holder names or full addresses. The case management API (human analyst review) enriches alerts with account holder details by querying A1 **at display time**, not at scoring time. This satisfies GDPR data minimization (Art. 5(1)(c)) | Must |

### 7.2 Functional Requirements — Sanctions Screening (NEW)

| ID | Requirement | Priority |
|----|------------|----------|
| A5-F20 | Screen originator AND beneficiary name, IBAN, country against EU Consolidated Sanctions List | Must |
| A5-F21 | Screen against UN Security Council sanctions list | Must |
| A5-F22 | Fuzzy name matching: handle transliterations, aliases, partial names, name order variations | Must |
| A5-F23 | Screening latency: **< 50ms** per transaction for the fast-path check | Must |
| A5-F24 | Sanctions list ingested and updated within **1 hour** of publication | Must |
| A5-F25 | Potential match (hit) → payment HELD, alert created for compliance analyst | Must |
| A5-F26 | Confirmed match → payment BLOCKED, funds frozen, reported to FIU | Must |
| A5-F27 | False positive → payment released, analyst decision logged | Must |
| A5-F28 | **Sanctions screening cannot be skipped.** If screening unavailable, reject the payment (unlike fraud scoring which can auto-pass on timeout) | Must |
| A5-F29 | Retrospective screening: when lists update, re-screen all existing accounts | Should |

### 7.3 Non-functional Requirements

| ID | Requirement | Target |
|----|------------|--------|
| A5-NF01 | Fraud scoring latency per transaction | < 100ms (p95) |
| A5-NF02 | **Sanctions screening latency** | **< 50ms** (p95) |
| A5-NF03 | Combined fraud+sanctions for instant path | < 500ms total |
| A5-NF04 | Throughput | >= 1,000 transactions/second |
| A5-NF05 | Must not block batch clearing if instant path is overloaded (separate consumer groups) | Isolation |

### 7.4 Security Requirements

| ID | Requirement |
|----|------------|
| A5-S01 | Case Management API requires OAuth2 token with `fraud:read` and `fraud:manage` scopes |
| A5-S02 | All scoring and screening decisions logged to immutable audit trail |
| A5-S03 | ML model files stored with SHA-256 integrity verification |
| A5-S04 | Transaction data is PII: encrypted at rest, access logged |
| A5-S05 | Sanctions list files integrity-verified (checksum) on ingestion |
| A5-S06 | Sanctions screening decisions are legally significant: immutable, timestamped, traceable to analyst |

### 7.5 Sanctions Screening Technology

| Component | Technology | Rationale |
|-----------|-----------|-----------|
| Fast negative check | **Bloom filter** (Guava) | O(1), sub-ms. Eliminates 99%+ of transactions |
| Multi-pattern match | **Aho-Corasick** (ahocorasick library) | Simultaneous search against all sanctioned names |
| Fuzzy match | **Apache Commons Text** (Jaro-Winkler, Soundex) | Handle transliterations and variations |
| In-memory store | **Caffeine cache** | Entire EU list (~15,000 entries) in memory |
| List ingestion | **Scheduled Spring task** | Poll EU/UN endpoints, normalize to internal format |

---

## 8. Sub-system A6: Verification of Payee Service (NEW)

### 8.1 Functional Requirements

| ID | Requirement | Priority |
|----|------------|----------|
| A6-F01 | **Requester role**: before any credit transfer (instant or batch), query beneficiary PSP with (IBAN, stated name). Return MATCH / CLOSE_MATCH / NO_MATCH / NOT_FOUND | Must |
| A6-F02 | **Responder role**: accept incoming queries from other PSPs about our account holders. Look up name in A1 Account Ledger, return match result | Must |
| A6-F03 | On CLOSE_MATCH: return the actual account holder name (to help payer correct a near-miss) | Must |
| A6-F04 | On NO_MATCH for responder: do NOT return actual name (privacy — don't reveal names for unmatched queries) | Must |
| A6-F05 | Name matching: case insensitive, handle diacritics (ø,å,ä,ü), common abbreviations (AS↔Aksjeselskap), name order, prefixes (van, de, von) | Must |
| A6-F06 | Feed VoP result into fraud scoring (A5): NO_MATCH with payer override is a high-risk signal | Must |
| A6-F07 | VoP check latency: **< 500ms** total (including network to external PSP) | Must |
| A6-F08 | Service available 24/7 (required when instant payments are available) | Must |
| A6-F09 | VoP check must be free of charge to the payer | Must (regulatory) |
| A6-F10 | If VoP check times out or external PSP unavailable: proceed with payment but flag for review (VoP is not a hard blocker like sanctions) | Should |

### 8.2 Non-functional Requirements

| ID | Requirement | Target |
|----|------------|--------|
| A6-NF01 | Responder query latency | < 100ms (p95) |
| A6-NF02 | Requester end-to-end (including network to external) | < 500ms (p95) |
| A6-NF03 | Availability | 99.99% |

### 8.3 Security Requirements

| ID | Requirement |
|----|------------|
| A6-S01 | Responder API authenticated (only registered PSPs may query) — mTLS or API key |
| A6-S02 | Rate limiting on responder API (prevent name enumeration attacks) |
| A6-S03 | All VoP queries and results logged to audit trail |
| A6-S04 | Account holder names cached with TTL — invalidated on name change in A1 |

### 8.4 Name Matching Algorithm

Fuzzy matching using Jaro-Winkler similarity with configurable thresholds:

| Jaro-Winkler Score | Result |
|-------------------|--------|
| >= 0.95 | MATCH |
| >= 0.85 and < 0.95 | CLOSE_MATCH (return actual name) |
| < 0.85 | NO_MATCH |

Technologies: `java.text.Normalizer` (NFD for diacritics), Apache Commons Text `JaroWinklerSimilarity`, Caffeine cache for account holder names.

---

## 9. Integration Requirements

### 9.1 Kafka Topic Topology

| Topic | Producer(s) | Consumer(s) | Purpose |
|-------|-----------|------------|---------|
| `payment.instructions` | A3, A4 | A2 (batch path) | Standard payment instructions |
| `payment.instant-instructions` | A3, A4 | A2 (instant path) | Instant payment instructions (dedicated, high-priority) |
| `payment.fraud-check-request` | A2 | A5 | Transaction to be scored (batch) |
| `payment.instant-fraud-check-request` | A2 | A5 | Transaction to be scored (instant, <500ms budget) |
| `payment.fraud-check-response` | A5 | A2 | Fraud score + sanctions result + decision |
| `payment.instant-fraud-check-response` | A5 | A2 | Fraud/sanctions result for instant path |
| `payment.settlement-events` | A2 | A1 | Balance changes after settlement |
| `payment.instant-settlement-events` | A2 | A1 | Immediate balance updates for instant path |
| `payment.status-updates` | A2 | A3, A4 | Payment status changes (SETTLED, REJECTED, etc.) |
| `payment.balance-reservation` | A2 | A1 | Reserve/confirm/release balance holds |
| `payment.reconciliation-results` | A1 | A2, monitoring | Reconciliation outcomes: matched, discrepancy, unmatched |
| `ledger.events` | A1 (command side) | A1 (projection consumer) | Event-sourced ledger: all balance changes as immutable events. Infinite retention (`retention.ms=-1`) |
| `account.freeze-commands` | A5 | A1 | Freeze account on confirmed fraud |
| `sanctions.list-updates` | Scheduled ingestion task (within A5) | A5 (screening engine) | Internal signal: sanctions lists have been updated, rebuild indexes |
| `audit.events` | All services | Audit consumer | Immutable audit trail |

### 9.2 Shared Libraries

| Library | Purpose | Used by |
|---------|---------|--------|
| `payment-domain-model` | Shared domain: Account, Transaction, PaymentInstruction, FraudScore, MoneyAmount, VoPResult | All |
| `payment-avro-schemas` | Avro schema definitions for all Kafka events | All |
| `payment-security-starter` | Custom Spring Boot starter: security headers, audit logging, Prometheus metrics (Project #4) | All |
| `payment-iso20022` | JAXB-generated classes from ISO 20022 XSD schemas (pacs.008, pacs.002, pain.001, pain.013, camt.053) | A2, A3, A4 |

### 9.3 End-to-End Flow: Instant Payment (Happy Path)

```
1. Consumer approves payment request in A3 (or TPP initiates via A4) with service_level=INSTANT
2. A6 (VoP): verify beneficiary name against IBAN → MATCH
3. A3/A4 → Kafka [payment.instant-instructions]: publish instant payment instruction
4. A2 (instant path): consume, validate ISO 20022
5. A2 → A1 [payment.balance-reservation]: reserve funds (tentative hold)
6. A1: check available balance >= amount → reserve OK
7. A2 → Kafka [payment.instant-fraud-check-request]: send to A5
8. A5: sanctions screening (<50ms) → CLEAR
9. A5: fraud scoring (<100ms) → score 0.08, PASS
10. A5 → Kafka [payment.instant-fraud-check-response]: CLEAR + score 0.08
11. A2: verify liquidity → OK
12. A2: execute gross settlement → publish to [payment.instant-settlement-events]
13. A1: confirm reservation → debit payer, credit beneficiary (final, irrevocable)
14. A2 → pacs.002 positive confirmation
15. A2 → Kafka [payment.status-updates]: SETTLED
16. Total elapsed: ~3-4 seconds
```

### 9.4 End-to-End Flow: Request-to-Pay with Decline (New)

```
1. Merchant → A3: POST payment request (NOK 1,250, due 2026-05-01)
2. Consumer → A3: GET pending requests → sees request
3. Consumer → A3: PUT /requests/{id}/decline with {reason: "AMOUNT_INCORRECT", message: "Invoice already paid"}
4. A3: transition request to DECLINED
5. A3 → webhook to merchant: DECLINED with reason code
6. No payment initiated
```

---

## 10. Demonstration Requirements

The system must be demonstrable in under 5 minutes:

| Demo step | What it shows |
|-----------|--------------|
| `docker-compose up` | All 6 services + Kafka + PostgreSQL start |
| Submit payment request via curl | A3 Request-to-Pay API works |
| Approve request (instant) | Triggers instant payment flow |
| View confirmation | Payment settled in <5 seconds (show timing vs. EPC 2025 SLA) |
| View account balances | A1 shows debited/credited balances (available vs. booked) |
| Submit request with wrong name | A6 VoP returns NO_MATCH warning |
| Submit suspicious transaction | A5 flags fraud, clearing rejects |
| Submit with sanctioned name | A5 sanctions screening blocks, alert created |
| PSD2 consent flow | OAuth2 authorization, token, account query |
| VRP consent + auto-payment | Consent with bounds, payment within bounds skips SCA |
| Grafana dashboard | Prometheus metrics: latency, throughput, fraud rate, SLA compliance |

---

## 11. Project Structure

```
nordic-payment-platform/
├── docker-compose.yml                  # Full system: 6 services + Kafka + PostgreSQL + Schema Registry
├── payment-domain-model/               # Shared domain library
├── payment-avro-schemas/               # Shared Kafka event schemas
├── payment-iso20022/                   # JAXB classes from ISO 20022 XSD
├── payment-security-starter/           # Custom Spring Boot starter (#4)
├── account-ledger/                     # A1 — Event-sourced ledger + balance holds
├── clearing-engine/                    # A2 — Dual-mode: batch (Spring Batch) + instant (Kafka Streams) + liquidity
├── request-to-pay-service/             # A3 — R2P / eFaktura + recurring mandates + QR
├── psd2-api-gateway/                   # A4 — Open Banking + SCA exemptions + VRP
├── fraud-sanctions-engine/             # A5 — Fraud scoring (DJL) + sanctions screening
├── verification-of-payee/              # A6 — VoP requester + responder (NEW)
├── docs/
│   ├── architecture-decisions/         # ADRs
│   ├── api/                            # OpenAPI specs
│   ├── security/                       # Threat model, security design
│   └── iso20022/                       # XSD schemas, sample messages
└── scripts/
    ├── generate-test-data.sh           # Seed system with sample data
    └── run-demo.sh                     # Automated demo script
```
