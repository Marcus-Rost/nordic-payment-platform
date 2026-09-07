# UC-01: Real-time Transaction Scoring (Automated Pipeline)

*The core flow: every transaction is scored before settlement*

---

## Primary Actor
**System** — the fraud engine processes every transaction automatically, no human interaction

## Trigger
A2 Clearing Engine publishes a transaction to `payment.fraud-check-request` (batch) or `payment.instant-fraud-check-request` (instant)

---

## Main Success Scenario: Low-Risk Transaction (AUTO_PASS)

| Step | Time Budget | Action | Detail |
|------|------------|--------|--------|
| 1 | 0ms | Consume transaction event from Kafka | `{transactionId, debtorAccount, creditorAccount, creditorName, amount: 2500.00, currency: NOK, timestamp, vopResult: MATCH}` |
| 2 | 0-30ms | **Sanctions screening (Layer 0)** | Bloom filter: "creditorName" definitely not in list → **CLEAR** in 0.5ms |
| 3 | 30-80ms | **Velocity rules (Layer 1)** | Check all 10 rules against Kafka Streams state stores. VEL-01: 2 transactions in 60 min (< 10 threshold) → not triggered. All rules: not triggered. velocity_score = 0.0 |
| 4 | 80-120ms | **Anomaly detection (Layer 2)** | Fetch account profile from state store. Amount 2,500 NOK vs. mean 2,200 ± stddev 800. z_amount = 0.375 (< 3.0 threshold). All normal. anomaly_score = 0.05 |
| 5 | 120-130ms | **ML scoring (Layer 3)** | DJL inference. Feature vector: [2500, 14, 4, 0.1, 0.45, 730, 0.375, 0.1]. Model output: 0.03 |
| 6 | 130-135ms | **Score aggregation** | combined = 0.2×0.0 + 0.2×0.05 + 0.2×0.03 + 0.0 (no VoP override) = 0.016 (weights: velocity=0.2, anomaly=0.2, ML=0.2 when ML enabled; sanctions overrides at 1.0) |
| 7 | 135-140ms | **Decision** | 0.016 < 0.5 (auto-pass threshold) → **AUTO_PASS** |
| 8 | 140-150ms | **Publish result** | Kafka `payment.fraud-check-response`: `{transactionId, sanctionsResult: CLEAR, fraudScore: 0.016, decision: AUTO_PASS, rulesTriggered: [], scoringLatencyMs: 150}` |
| 9 | 150ms | **Update account profile** | Increment transaction count, update rolling mean/stddev, add creditor to typical set |

No alert created. No human review needed. Transaction proceeds to settlement.

---

## Alternative: High-Risk Transaction (AUTO_BLOCK)

| Step | Time | Action | Detail |
|------|------|--------|--------|
| 1 | 0ms | Consume: `{amount: 95000.00, vopResult: NO_MATCH, vopOverride: true}` | Large amount + VoP override |
| 2 | 0-1ms | Sanctions: Bloom filter → CLEAR | Not sanctioned |
| 3 | 1-50ms | Velocity: VEL-02 triggered (95K vs. mean 2.2K = 43x → HIGH). VEL-05 triggered (new creditor + large). VEL-09 triggered (VoP override) | velocity_score = 0.4 + 0.2 + 0.3 = 0.9 (normalized to 0.9) |
| 4 | 50-90ms | Anomaly: z_amount = (95000-2200)/800 = 116 → extreme. anomaly_score = 0.99 | |
| 5 | 90-100ms | ML: model output = 0.87 | |
| 6 | 100ms | Aggregation: 0.2×0.9 + 0.2×0.99 + 0.2×0.87 + 0.3 (VoP override) = 0.852 | |
| 7 | 100ms | Decision: 0.852 > 0.7 → **AUTO_BLOCK** | |
| 8 | 105ms | Publish: `{decision: AUTO_BLOCK, fraudScore: 0.852, rulesTriggered: [VEL-02, VEL-05, VEL-09]}` | |
| 9 | 110ms | Publish alert event to `fraud.alert-events` (Alert Consumer persists to PostgreSQL asynchronously: status OPEN, priority HIGH) | |

Payment is rejected by A2. Alert created for analyst review.

---

## Alternative: Sanctions Hit

| Step | Time | Action | Detail |
|------|------|--------|--------|
| 1 | 0ms | Consume: creditorName = "Иванов Сергей" (Cyrillic) | |
| 2 | 0-1ms | Bloom filter: MAYBE (not definitive negative) | |
| 3 | 1-5ms | Aho-Corasick exact match on Latin transliteration "Ivanov Sergei": no exact match | |
| 4 | 5-30ms | Fuzzy match: "Ivanov Sergey" on list. Jaro-Winkler("Ivanov Sergei", "Ivanov Sergey") = 0.96 → **POTENTIAL_HIT** | |
| 5 | 30ms | Decision: **SANCTIONS_HOLD** (overrides all fraud scoring) | |
| 6 | 35ms | Publish: `{decision: SANCTIONS_HOLD, sanctionsResult: POTENTIAL_HIT, matchedEntity: "IVANOV, Sergey (EU list #12345)"}` | |
| 7 | 40ms | Publish alert event to `fraud.alert-events` (Alert Consumer persists to PostgreSQL asynchronously: status OPEN, type SANCTIONS, priority CRITICAL) | |

**Payment is HELD.** Not rejected yet — compliance analyst must confirm or dismiss. Fraud scoring does NOT run (sanctions overrides everything).

---

## Alternative: Instant Payment Path (Strict Latency)

| Step | Budget | Action |
|------|--------|--------|
| 1 | — | Consume from `payment.instant-fraud-check-request` (separate consumer group, higher priority) |
| 2 | < 50ms | Sanctions screening (same logic, same latency) |
| 3 | < 100ms | Velocity rules (same state stores, same logic) |
| 4 | < 100ms | Anomaly detection (same profile lookup) |
| 5 | < 10ms | ML scoring (same model, same inference) |
| 6-8 | < 50ms | Aggregation + decision + publish to `payment.instant-fraud-check-response` |
| **Total** | **< 310ms** | Well within 500ms budget allocated by A2 |

If total exceeds 500ms: A2 times out and applies **AUTO_PASS with score=UNKNOWN** (configurable: could also AUTO_BLOCK on timeout for high-value transactions).

---

## Alternative: ML Model Unavailable (Graceful Degradation)

| Step | Action |
|------|--------|
| 1 | DJL model fails to load at startup (corrupted file, wrong format) |
| 2 | Engine logs ERROR: "ML model unavailable. Falling back to rules + anomaly scoring." |
| 3 | ML weight redistributed: velocity weight 0.35, anomaly weight 0.35 (from 0.2 each) |
| 4 | All transactions scored without ML. System operational, degraded but functional |
| 5 | Prometheus metric: `fraud_ml_available = 0` → Grafana alert fires |

**The system NEVER fails to start because of an ML model issue.** ML is an enhancement, not a dependency.

---

## Data in Scoring Response

```json
{
  "transactionId": "txn-uuid-001",
  "sanctionsResult": "CLEAR",
  "fraudScore": 0.852,
  "decision": "AUTO_BLOCK",
  "rulesTriggered": [
    {"ruleId": "VEL-02", "ruleName": "Large sudden amount", "weight": 0.4, "explanation": "Amount 95,000 NOK is 43x account average of 2,200 NOK"},
    {"ruleId": "VEL-05", "ruleName": "New creditor + large", "weight": 0.2, "explanation": "First transaction to creditor NO12345678901 AND amount > 50,000"},
    {"ruleId": "VEL-09", "ruleName": "VoP override", "weight": 0.3, "explanation": "Payer proceeded despite VoP NO_MATCH warning"}
  ],
  "anomalyScore": 0.99,
  "anomalyDetail": "z_amount=116.0 (extreme), unusual_hour=0.0, new_creditor=0.5",
  "mlScore": 0.87,
  "mlModelVersion": "fraud-v2.3.1",
  "vopResult": "NO_MATCH",
  "vopOverride": true,
  "scoringLatencyMs": 105,
  "timestamp": "2026-04-11T14:23:45.123Z"
}
```

---

## Security Considerations
- All scoring decisions are immutable audit records — once written, never modified or deleted
- Transaction data flowing through Kafka contains PII (account numbers, names) — topics must use SASL/SSL
- Kafka Streams state stores contain account profiles (PII) — RocksDB encryption at rest
- ML model files are signed/checksummed — prevent model poisoning
- Sanctions screening cannot be bypassed via configuration — hardcoded as mandatory
