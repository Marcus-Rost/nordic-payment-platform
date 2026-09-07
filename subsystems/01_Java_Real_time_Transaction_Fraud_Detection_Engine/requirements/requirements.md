# Project #1: Real-time Transaction Fraud Detection & Sanctions Engine — Requirements

*System A role: A5 — Fraud scoring + sanctions screening for all payment transactions*
*Target companies: DNB (AML/fraud), Sportradar (UFDS), SpareBank 1 (AML), Tietoevry Banking, Mastercard Oslo*
*Regulatory basis: EU AML Package 2024, 6AMLD, EU Instant Payment Regulation (sanctions screening)*

---

## 1. Overview

A real-time streaming service that scores every financial transaction for fraud risk and screens every payment against sanctions lists — before the transaction is settled. The engine processes both batch and instant payment flows, with strict latency requirements for instant payments (<500ms combined fraud + sanctions response).

The system operates as a three-layer detection pipeline:
1. **Sanctions screening** — legal obligation, cannot be skipped. Screens names/accounts against EU/UN lists
2. **Rule-based velocity checks** — configurable rules detecting suspicious patterns (high frequency, unusual amounts, time-of-day anomalies)
3. **ML model scoring** — Deep Java Library (DJL) model inference for complex pattern detection

Plus a **case management API** for human analysts to review and resolve flagged transactions.

### 1.1 Target Company Alignment

| Company | What they build | How our project aligns |
|---------|----------------|----------------------|
| **DNB** | Heavy investment in AML/fraud detection. Real-time event processing with Kafka, ML models, graph analytics. Publicly involved in major AML case that accelerated investment | Our Kafka Streams pipeline + DJL ML scoring mirrors their approach |
| **Sportradar** | UFDS (Universal Fraud Detection System): monitors 550+ betting operators, tracks 30B+ odds changes/year, ML algorithms detect unusual patterns. Uses DJL on AWS EKS | Our DJL integration directly mirrors Sportradar. Our layered pipeline (rules + statistics + ML) matches UFDS architecture |
| **SpareBank 1** | AML/terrorist financing detection with ML-based analytics (Tech Lead role identified). Java/Kotlin + Kafka | Our Kotlin-compatible Kafka Streams approach aligns |
| **Tietoevry Banking** | AML/KYC compliance solutions as part of their banking platform. Event-driven architecture with Kafka | Our event-driven scoring pipeline fits their architecture |
| **Mastercard Oslo** | Payment security is core business. Fraud detection in NICS/AvtaleGiro payment processing | Our integration with the clearing engine (A2) mirrors their payment-level fraud screening |

---

## 2. Functional Requirements — Sanctions Screening

*Mandatory legal obligation. Cannot be skipped, even on timeout.*

| ID | Requirement | Priority |
|----|------------|----------|
| SAN-F01 | Screen originator AND beneficiary names against **EU Consolidated Sanctions List** | Must |
| SAN-F02 | Screen against **UN Security Council Consolidated List** | Must |
| SAN-F03 | Screen account IBANs against sanctioned entity account lists | Must |
| SAN-F04 | Screen originator/beneficiary countries against embargoed countries list | Must |
| SAN-F05 | Fuzzy name matching: transliterations (Cyrillic→Latin, Arabic→Latin), aliases, partial names, reversed name order, prefixes (van, de, al-), diacritics | Must |
| SAN-F06 | Screening latency: **< 50ms** per transaction (fast-path using in-memory data structures) | Must |
| SAN-F07 | List polling: check for sanctions list updates at least **every hour** (configurable). Note: this is the poll frequency; actual processing time once downloaded is < 5 minutes (see NFR-06) | Must |
| SAN-F08 | List integrity verification: checksum validation on ingested list files | Must |
| SAN-F09 | Potential match (hit) → payment **HELD**. Alert created for compliance analyst | Must |
| SAN-F10 | Confirmed match → payment **BLOCKED**, funds frozen. Report to FIU (Financial Intelligence Unit) generated | Must |
| SAN-F11 | False positive → payment **RELEASED**. Analyst decision logged with justification | Must |
| SAN-F12 | **Cannot be skipped.** If screening service is unavailable, payment is **REJECTED** (not auto-passed) | Must |
| SAN-F13 | Retrospective screening: when lists update, re-screen all active accounts against new entries | Should |
| SAN-F14 | Screening statistics: hit rate, false positive rate, average screening latency exposed as Prometheus metrics | Must |

### 2.1 Sanctions Screening Architecture

```
Transaction ──→ [Bloom Filter] ──→ definitely not in list ──→ CLEAR (< 1ms)
                     │
                     └─ maybe in list
                          │
                          v
                  [Aho-Corasick] ──→ exact match ──→ HIT (< 5ms)
                     │
                     └─ no exact match
                          │
                          v
                  [Fuzzy Match] ──→ Jaro-Winkler ≥ 0.85 ──→ POTENTIAL_HIT (< 30ms)
                     │                  + Soundex/Metaphone
                     └─ score < 0.85 ──→ CLEAR
```

| Component | Technology | Rationale |
|-----------|-----------|-----------|
| Bloom filter | Google Guava `BloomFilter` | O(1) negative check. Eliminates 99%+ of transactions in <1ms |
| Multi-pattern match | Aho-Corasick (`org.ahocorasick:ahocorasick`) | Simultaneous search against all ~15,000 sanctioned entity names |
| Fuzzy match | Apache Commons Text `JaroWinklerSimilarity` + `Soundex` | Handle transliterations, spelling variations |
| In-memory store | Caffeine cache or ConcurrentHashMap | Entire sanctions list in memory. ~15K entities for EU list |
| List ingestion | Scheduled Spring task + Kafka event on update | Poll EU/UN XML endpoints, parse, normalize, rebuild indexes |

---

## 3. Functional Requirements — Fraud Detection

| ID | Requirement | Priority |
|----|------------|----------|
| FRD-F01 | Consume transaction events from Kafka (both `payment.fraud-check-request` and `payment.instant-fraud-check-request` topics) | Must |
| FRD-F02 | **Layer 1 — Velocity rules**: configurable rules evaluating transaction patterns in time windows | Must |
| FRD-F03 | **Layer 2 — Statistical anomaly detection**: compare transaction against account's historical profile | Must |
| FRD-F04 | **Layer 3 — ML model scoring**: run transaction features through a pre-trained model via DJL | Should |
| FRD-F05 | Produce combined fraud score (0.0-1.0) with list of triggered rules as explanations | Must |
| FRD-F06 | Integrate VoP result from A6: NO_MATCH override adds +0.3 to fraud score | Must |
| FRD-F07 | Publish enriched result to Kafka (`payment.fraud-check-response` or instant variant) | Must |
| FRD-F08 | Configurable thresholds per decision category | Must |
| FRD-F09 | Rules engine must be configurable without code changes (YAML/JSON rule definitions or Drools) | Should |
| FRD-F10 | Account profile maintenance: rolling statistics (mean, stddev) updated incrementally per transaction | Must |

### 3.1 Velocity Rules (Configurable)

| Rule ID | Rule Name | Default Condition | Default Severity |
|---------|-----------|-------------------|-----------------|
| VEL-01 | High frequency | > 10 transactions from same account in 60 minutes | MEDIUM (0.2) |
| VEL-02 | Large sudden amount | Single transaction > 20x account's average transaction amount | HIGH (0.4) |
| VEL-03 | Rapid drain | Total outflow > 80% of account balance in 24 hours | HIGH (0.4) |
| VEL-04 | Night activity | Transaction between 02:00-05:00 local time, no prior night activity | LOW (0.1) |
| VEL-05 | New creditor + large | First-time creditor AND amount > NOK 50,000 | MEDIUM (0.2) |
| VEL-06 | Failed then success | > 3 rejected transactions followed by successful one in 1 hour | HIGH (0.3) |
| VEL-07 | Round amount burst | > 5 transactions with exact round amounts in 24 hours | LOW (0.1) |
| VEL-08 | Geographic anomaly | Creditor in high-risk country AND amount > NOK 10,000 | MEDIUM (0.2) |
| VEL-09 | VoP override | Payer overrode VoP NO_MATCH warning | HIGH (0.3) |
| VEL-10 | Duplicate override | A2 flagged as PROBABLE_DUPLICATE and payer overrode the warning (proceeded despite "this looks like a duplicate") | MEDIUM (0.2) |

Rules are additive: each triggered rule contributes its severity weight to the combined score.

### 3.2 Statistical Anomaly Detection

Account profile (rolling 90-day window, maintained in Kafka Streams state store):
```
AccountProfile:
  accountId:                    String
  meanTransactionAmount:        BigDecimal (exponential moving average)
  stddevTransactionAmount:      BigDecimal
  meanDailyTransactionCount:    Double
  stddevDailyTransactionCount:  Double
  typicalHours:                 Set<Integer> (hours with historical activity)
  typicalCreditors:             Set<String> (known creditor accounts, max 100)
  totalTransactionCount:        Long
  lastUpdated:                  Instant
```

Anomaly score calculation:
```
z_amount = |transaction.amount - profile.mean| / profile.stddev
z_frequency = |dailyCount - profile.meanDailyCount| / profile.stddevDailyCount
unusual_hour = 1.0 if hour NOT in profile.typicalHours, else 0.0
new_creditor = 0.5 if creditor NOT in profile.typicalCreditors, else 0.0
anomaly_score = sigmoid(w1*z_amount + w2*z_frequency + w3*unusual_hour + w4*new_creditor)
```

Weights (w1-w4) configurable. Default: w1=0.4, w2=0.2, w3=0.2, w4=0.2.

### 3.3 ML Model Scoring (DJL)

| ID | Requirement | Priority |
|----|------------|----------|
| ML-F01 | Load pre-trained model via Deep Java Library (DJL) at startup | Should |
| ML-F02 | Model format: ONNX (portable) or PyTorch (via DJL) | Should |
| ML-F03 | Feature vector: amount, hour_of_day, day_of_week, creditor_country_risk_score, transaction_frequency_percentile, account_age_days, z_amount, z_frequency | Should |
| ML-F04 | Inference latency: < 10ms per transaction | Should |
| ML-F05 | Model integrity: verify SHA-256 checksum of model file at load time | Must (if ML enabled) |
| ML-F06 | Model versioning: log model version with every scoring decision | Must (if ML enabled) |
| ML-F07 | Graceful fallback: if ML model fails to load or inference fails, use rules + anomaly only (no system failure) | Must |

### 3.4 Score Aggregation and Decision

```
combined_score = w_sanctions * sanctions_result     # 1.0 if HIT, 0.0 if CLEAR
               + w_velocity  * velocity_score       # sum of triggered rule weights, normalized 0-1
               + w_anomaly   * anomaly_score        # 0.0-1.0
               + w_ml        * ml_score             # 0.0-1.0
               + vop_override_bonus                 # +0.3 if VoP NO_MATCH overridden
```

Default weights (when ML enabled): w_velocity=0.2, w_anomaly=0.2, w_ml=0.2. Without ML: w_velocity=0.35, w_anomaly=0.35.

**Important: Sanctions screening is NOT a weighted input.** If sanctions returns POTENTIAL_HIT or HIT, fraud scoring layers are **bypassed entirely** — the payment is immediately SANCTIONS_HOLD. The combined_score formula above only applies when sanctions result is CLEAR.

| Score Range | Decision | Action |
|------------|----------|--------|
| Sanctions HIT | **SANCTIONS_HOLD** | Payment held. Compliance alert created. Overrides fraud score |
| 0.0 - 0.5 | **AUTO_PASS** | Payment proceeds to clearing |
| 0.5 - 0.7 | **MANUAL_REVIEW** | Payment held. Fraud alert created for analyst |
| 0.7 - 1.0 | **AUTO_BLOCK** | Payment rejected. Fraud alert created |

---

## 4. Functional Requirements — Case Management

| ID | Requirement | Priority |
|----|------------|----------|
| CASE-F01 | **GET** `/api/v1/fraud/alerts` — list alerts, paginated, filterable by: status, score range, date range, account, rule triggered | Must |
| CASE-F02 | **GET** `/api/v1/fraud/alerts/{id}` — alert detail: transaction data, fraud score breakdown, rules triggered with explanations, account profile summary, VoP result | Must |
| CASE-F03 | **PUT** `/api/v1/fraud/alerts/{id}/resolve` — resolve alert: CONFIRMED_FRAUD (block + freeze account) or DISMISSED (release payment) with analyst notes | Must |
| CASE-F04 | Alert lifecycle: OPEN → INVESTIGATING → CONFIRMED_FRAUD / DISMISSED | Must |
| CASE-F05 | Sanctions alerts: OPEN → INVESTIGATING → CONFIRMED_SANCTION (block + report to FIU) / FALSE_POSITIVE (release) | Must |
| CASE-F06 | Dashboard metrics endpoint: total alerts (open/resolved), false positive rate, average resolution time, fraud detection rate | Should |
| CASE-F07 | Alert assignment: assign alerts to specific analysts | Should |
| CASE-F08 | Alert priority: auto-sort by score descending (highest risk first) | Must |
| CASE-F09 | Bulk operations: resolve multiple related alerts (e.g., all from same compromised account) | Should |

---

## 5. Non-functional Requirements

| ID | Requirement | Target |
|----|------------|--------|
| NFR-01 | Sanctions screening latency | < 50ms (p95) |
| NFR-02 | Fraud scoring latency (rules + anomaly) | < 100ms (p95) |
| NFR-03 | ML model inference latency | < 10ms (p95) |
| NFR-04 | Combined scoring for instant payments | < 500ms (p95, end-to-end including Kafka) |
| NFR-05 | Throughput | >= 1,000 transactions/second |
| NFR-06 | Sanctions list update ingestion | < 5 minutes from download to active |
| NFR-07 | Must not block batch clearing if instant path is overloaded | Separate Kafka consumer groups |
| NFR-08 | Case Management API response latency | < 200ms (p95) |
| NFR-09 | Availability | 99.99% (sanctions screening is critical infrastructure) |

### 5.1 Resilience and Crash Recovery

*Propagated from master system requirements (section 2.7)*

| ID | Requirement | Priority |
|----|------------|----------|
| FRD-RES01 | **Atomic sanctions list swap**: when sanctions lists are updated, build new in-memory structures (Bloom filter, Aho-Corasick index, fuzzy index) in a **staging area**. Swap to production atomically (reference swap, e.g., `AtomicReference`) only after ALL three structures are rebuilt and consistency-verified. Track list version per screening decision for audit traceability | Must |
| FRD-RES02 | **Dead Letter Queue**: every Kafka consumer (`payment.fraud-check-request`, `payment.instant-fraud-check-request`, `sanctions.list-updates`) must route unprocessable messages to `{topic}.dlq` after 3 retries. DLQ messages include original payload + error details. Monitor DLQ depth via Prometheus metric `fraud.dlq.depth` | Must |
| FRD-RES03 | **Graceful shutdown**: on SIGTERM, allow in-flight scoring operations to complete (drain Kafka consumer buffers) before shutting down. Do not leave scoring requests unanswered — if shutdown is imminent and scoring cannot complete, publish a response with `decision: TIMEOUT` so A2 can handle it | Must |
| FRD-RES04 | **Late-arriving responses**: if A5 produces a fraud/sanctions response after A2 has already timed out and auto-passed the payment, A5 must still publish the response (it will be logged). If the late response is SANCTIONS_HIT, A5 must additionally publish an alert (`LATE_SANCTIONS_HIT`) to the case management system for immediate analyst review | Must |

---

## 6. Technology Stack

| Component | Technology | Why (Company Alignment) |
|-----------|-----------|------------------------|
| Framework | **Spring Boot 3.x** | Universal |
| Stream processing | **Kafka Streams** | DNB, SpareBank1, NAV backbone. Stateful processing with windowed aggregations |
| Kafka state stores | **RocksDB** (default Kafka Streams) | Persistent state for velocity windows and account profiles |
| ML inference | **Deep Java Library (DJL)** | Sportradar explicitly uses DJL on EKS. Rare skill, high signal |
| Sanctions matching | **Google Guava BloomFilter** + **Aho-Corasick** + **Apache Commons Text** | High-performance multi-layer screening |
| Database | **PostgreSQL** (JSONB for alert metadata) | Universal. JSONB for flexible rule-triggered data |
| API | **REST + OpenAPI 3.0** | Contract-first. Case management API |
| Auth | **Spring Security OAuth2 Resource Server** | JWT with `fraud:read`, `fraud:manage` scopes |
| Observability | **Micrometer + Prometheus** | Custom metrics: scoring latency, hit rates, false positive rates |
| Testing | **Testcontainers** (Kafka, PostgreSQL), JUnit 5, **ArchUnit** | Tietoevry uses Testcontainers. ArchUnit for architecture rules |
| CI | **GitHub Actions** | NAV, SpareBank1, DNB standard |

---

## 7. Data Model

### FraudAlert Entity
```
alert_id:               UUID (PK)
transaction_id:         UUID (FK to scored transaction)
alert_type:             Enum (FRAUD, SANCTIONS)
account_id:             String
transaction_amount:     BigDecimal
currency:               String (ISO 4217)
fraud_score:            Double (0.0-1.0)
decision:               Enum (AUTO_PASS, MANUAL_REVIEW, AUTO_BLOCK, SANCTIONS_HOLD,
                               CONFIRMED_FRAUD, DISMISSED, CONFIRMED_SANCTION, FALSE_POSITIVE)
rules_triggered:        JSONB (List<{ruleId, ruleName, weight, explanation}>)
sanctions_result:       Enum (CLEAR, HIT, POTENTIAL_HIT) (nullable)
sanctions_matched_entry: String (nullable — which list entry matched)
vop_result:             Enum (MATCH, CLOSE_MATCH, NO_MATCH, NOT_FOUND) (nullable)
vop_override:           Boolean
ml_model_version:       String (nullable)
ml_score:               Double (nullable)
scoring_latency_ms:     Long
assigned_to:            String (nullable — analyst ID)
created_at:             Instant (UTC)
resolved_at:            Instant (nullable)
resolved_by:            String (nullable — analyst ID)
resolution_notes:       String (nullable)
```

### AccountProfile (Kafka Streams State Store)
```
account_id:                     String (key)
mean_transaction_amount:        BigDecimal
stddev_transaction_amount:      BigDecimal
mean_daily_transaction_count:   Double
stddev_daily_transaction_count: Double
typical_hours:                  BitSet (24 bits)
typical_creditors:              Set<String> (max 100, LRU eviction)
total_transaction_count:        Long
last_updated:                   Instant
```

### VelocityWindow (Kafka Streams WindowStore)
```
Key:    account_id
Window: Tumbling, configurable (60 min for frequency, 24 hours for daily amounts)
Value:  {count: Long, totalAmount: BigDecimal}
```

---

## 8. Kafka Topics

| Topic | Direction | Purpose |
|-------|-----------|---------|
| `payment.fraud-check-request` | Consume | Batch payment transactions to score |
| `payment.instant-fraud-check-request` | Consume | Instant payment transactions (< 500ms budget) |
| `payment.fraud-check-response` | Produce | Scoring results for batch |
| `payment.instant-fraud-check-response` | Produce | Scoring results for instant |
| `sanctions.list-updates` | Consume | Notification that sanctions lists have been updated |
| `audit.events` | Produce | Audit trail for all scoring decisions |
| `account.freeze-commands` | Produce | Command to freeze account (on CONFIRMED_FRAUD) |

---

## 9. Security Requirements

| ID | Requirement |
|----|------------|
| SEC-01 | Case Management API requires OAuth2 token with `fraud:read` (view) and `fraud:manage` (resolve) scopes |
| SEC-02 | All scoring decisions logged to immutable audit trail (Kafka `audit.events` + PostgreSQL) |
| SEC-03 | ML model files integrity-verified (SHA-256 checksum) at load time |
| SEC-04 | Transaction data is PII: encrypted at rest in PostgreSQL, access logged |
| SEC-05 | Sanctions screening decisions are legally significant: immutable, timestamped, traceable to specific list version |
| SEC-06 | Sanctions list files integrity-verified (checksum) on ingestion |
| SEC-07 | Kafka Streams state stores contain sensitive windowed data: configure encryption at rest (filesystem-level encryption or RocksDB encryption — mechanism chosen by architecture, see D3 Surface 5) |
| SEC-08 | Analyst actions (CONFIRMED_FRAUD, DISMISSED) logged with analyst identity, timestamp, justification |
| SEC-09 | Rate limiting on Case Management API (prevent data exfiltration) |
| SEC-10 | **PII minimization**: automated scoring pipeline processes anonymized account IDs + transaction patterns only (no account holder names, no addresses). Case management API enriches alerts with PII from A1 at query time (analyst opens alert → A1 lookup). GDPR Art. 5(1)(c) data minimization |

---

## 10. Integration Points

| Integration | Direction | Protocol | Latency Budget |
|------------|-----------|----------|---------------|
| A2 Clearing Engine | Inbound (Kafka) | Kafka consumer | — |
| A2 Clearing Engine | Outbound (Kafka) | Kafka producer | < 500ms total |
| A6 VoP Service | Inbound (event field) | VoP result included in fraud-check-request message | — |
| A1 Account Ledger | Outbound (Kafka) | `account.freeze-commands` | Async |
| Sanctions list providers | Outbound (HTTP) | Scheduled polling of EU/UN XML endpoints | Async (hourly) |
| Prometheus | Outbound (scrape) | HTTP `/actuator/prometheus` | — |

---

## 11. Acceptance Criteria

| ID | Criterion |
|----|----------|
| AC-01 | Given 10,000 transactions on Kafka, the engine scores all within 10 seconds (1,000 TPS) |
| AC-02 | A transaction from a sanctioned entity name (fuzzy match) is HELD with a sanctions alert |
| AC-03 | A transaction with > 10 in 60 minutes triggers VEL-01 and produces a fraud score > 0.2 |
| AC-04 | A transaction 4 standard deviations above the account mean triggers anomaly detection |
| AC-05 | An analyst can view, filter, and resolve alerts via the Case Management API |
| AC-06 | The ML model (if enabled) produces a score in < 10ms without failures |
| AC-07 | If the ML model fails, the engine falls back to rules + anomaly (no system failure) |
| AC-08 | Sanctions screening cannot be disabled — attempting to skip it results in payment rejection |
| AC-09 | docker-compose brings up the engine with Kafka, PostgreSQL, and sample sanctions list |
| AC-10 | Grafana dashboard shows: scoring latency (p50/p95/p99), sanctions hit rate, false positive rate |
