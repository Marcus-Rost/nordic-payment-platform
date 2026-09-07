# UC-05: Real-time Fraud Detection, Sanctions Screening, and Case Management

*Sub-systems involved: A5 (Fraud & Sanctions Engine), A6 (VoP — provides input), A2 (Clearing Engine)*
*Mirrors: DNB AML/fraud detection, Sportradar UFDS (30B+ odds changes/year), SpareBank 1 AML monitoring*
*Regulatory basis: EU AML Package 2024, EU Instant Payment Regulation (sanctions screening)*

---

## Scenario 5A: Transaction Scoring (Automated)

### Primary Actor
**System** — the fraud detection engine processes every transaction automatically

### Trigger
A2 Clearing Engine publishes a transaction to `payment.fraud-check-request`

### Main Success Scenario

| Step | Action | Detail |
|------|--------|--------|
| 1 | Consume transaction event from Kafka | Transaction: account X, amount NOK 45,000, creditor account Y, timestamp |
| 2 | **Layer 1 — Velocity rules** | Check: has account X made > 10 transactions in the last 60 minutes? Has total outflow exceeded NOK 100,000 in 24 hours? Result: 2 rules triggered → velocity_score = 0.3 |
| 3 | **Layer 2 — Statistical anomaly detection** | Fetch account X's historical transaction profile (average amount, standard deviation, typical time-of-day). NOK 45,000 is 2.8 standard deviations above mean (mean=NOK 2,500, stddev=NOK 15,000). Result: anomaly_score = 0.4 |
| 4 | **Layer 3 — ML model scoring** | Run transaction features through DJL-hosted model. Features: amount, hour-of-day, day-of-week, creditor country, transaction frequency, account age. Result: ml_score = 0.25 |
| 5 | **Score aggregation** | Combined score = weighted_average(velocity=0.3 * 0.3, anomaly=0.4 * 0.4, ml=0.25 * 0.3) = 0.325 |
| 6 | **Threshold evaluation** | score < 0.5 (auto-pass threshold) → PASS. No alert created |
| 7 | Publish result to `payment.fraud-check-response` | `{transaction_id, score: 0.325, decision: PASS, rules_triggered: ["velocity_high_count"], latency_ms: 42}` |

### Threshold Configuration
| Score Range | Decision | Action |
|------------|----------|--------|
| 0.0 - 0.5 | AUTO_PASS | Transaction proceeds to clearing automatically |
| 0.5 - 0.7 | MANUAL_REVIEW | Transaction held; alert created for case officer (UC-05B) |
| 0.7 - 1.0 | AUTO_BLOCK | Transaction rejected automatically; alert created |

---

## Scenario 5B: Fraud Alert Case Management (Human-in-the-loop)

### Primary Actor
**Fraud Case Officer** (human) — reviews and resolves flagged transactions

### Preconditions
- Transaction scored in range 0.5-0.7 (MANUAL_REVIEW) or 0.7-1.0 (AUTO_BLOCK)
- Alert created in fraud case database

### Trigger
Case officer opens the case management dashboard

### Main Success Scenario

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | Case Officer | GET `/api/v1/fraud/alerts?status=OPEN&sort=score,desc` | A5 returns list of open alerts, highest score first |
| 2 | Case Officer | GET `/api/v1/fraud/alerts/{id}` | A5 returns alert detail: transaction data, fraud score, triggered rules with explanations, account history summary |
| 3 | Case Officer | Reviews: account X made 12 transactions in 30 min totaling NOK 180,000. Previous month average: 3 transactions/day, NOK 5,000/day. ML model flagged unusual creditor country. | Case officer judges: this is a compromised account (card fraud) |
| 4 | Case Officer | PUT `/api/v1/fraud/alerts/{id}/resolve` with `{decision: CONFIRMED_FRAUD, notes: "Account compromised, 12 rapid transactions to unusual creditor"}` | A5 transitions alert to CONFIRMED_FRAUD. Publishes account block event |
| 5 | — | Account freeze | A1 Account Ledger receives account freeze event → sets account status to FROZEN. All pending payments for this account are rejected by A2 |

### Alternative: False Positive Dismissal

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 3a | Case Officer | Reviews: account holder is a business making bulk supplier payments (normal for this account) | Legitimate activity |
| 4a | Case Officer | PUT `/api/v1/fraud/alerts/{id}/resolve` with `{decision: DISMISSED, notes: "Business account, bulk supplier payments are normal pattern"}` | A5 transitions alert to DISMISSED. Held transaction released to clearing |

---

## Scenario 5C: Velocity Rule Trigger Examples

| Rule ID | Rule Name | Condition | Severity |
|---------|-----------|-----------|----------|
| VEL-01 | High frequency | > 10 transactions from same account in 60 minutes | MEDIUM |
| VEL-02 | Large sudden amount | Single transaction > 20x account's average transaction amount | HIGH |
| VEL-03 | Rapid drain | Total outflow > 80% of account balance in 24 hours | HIGH |
| VEL-04 | Night activity | Transaction between 02:00-05:00 local time from account with no prior night activity | LOW |
| VEL-05 | New creditor + large amount | First-time creditor AND amount > NOK 50,000 | MEDIUM |
| VEL-06 | Multiple failed then success | > 3 rejected transactions followed by a successful one within 1 hour | HIGH |
| VEL-07 | Round amount pattern | > 5 transactions in 24 hours with exact round amounts (NOK 10,000, 20,000, etc.) | LOW |

## Scenario 5D: Anomaly Detection Method

Statistical profile per account (maintained as rolling window):
```
account_profile:
  mean_transaction_amount:     BigDecimal (rolling 90-day average)
  stddev_transaction_amount:   BigDecimal
  mean_daily_transaction_count: Double
  typical_hours:               Set<Integer> (hours with historical activity)
  typical_creditors:           Set<String> (known creditor accounts)
  total_transactions:          Long (lifetime count)
  profile_last_updated:        Instant
```

Anomaly score calculation:
- z_amount = |transaction_amount - mean| / stddev
- z_frequency = |daily_count - mean_daily_count| / stddev_daily_count
- unusual_hour = 1.0 if hour not in typical_hours, else 0.0
- new_creditor = 0.5 if creditor not in typical_creditors, else 0.0
- anomaly_score = normalize(weighted_sum(z_amount, z_frequency, unusual_hour, new_creditor))

---

## Scenario 5E: Sanctions Screening (Mandatory — Cannot Be Skipped)

*Sanctions screening runs BEFORE fraud scoring. A sanctions hit overrides everything.*

### Happy Path: Transaction is Clear

| Step | Time | Action | Detail |
|------|------|--------|--------|
| 1 | 0ms | Receive transaction for screening | Extract: originator name, beneficiary name, originator IBAN, beneficiary IBAN, originator country, beneficiary country |
| 2 | 0-1ms | **Bloom filter** check on beneficiary name | Definitely NOT in any list → **CLEAR** in 0.5ms. Skip further matching. (~99% of transactions stop here) |
| 3 | 1ms | Proceed to fraud scoring (Scenarios 5A-5D) | Sanctions result: CLEAR |

### Potential Hit: Fuzzy Name Match

| Step | Time | Action | Detail |
|------|------|--------|--------|
| 1 | 0ms | Receive transaction. Beneficiary: "Ivanov Sergei" | |
| 2 | 0-1ms | Bloom filter: MAYBE (not definitive negative) | Proceed to deeper matching |
| 3 | 1-5ms | Aho-Corasick exact match on "Ivanov Sergei" against all ~15,000 sanctioned entity names | No exact match found |
| 4 | 5-25ms | Fuzzy match: Jaro-Winkler("Ivanov Sergei", "IVANOV, Sergey Mikhailovich") = 0.88. Soundex also matches | **POTENTIAL_HIT** (score >= 0.85 threshold) |
| 5 | 25ms | **SANCTIONS_HOLD** — payment immediately HELD | Overrides ALL fraud scoring. Fraud scoring does NOT run |
| 6 | 30ms | Compliance alert created | `{alertType: SANCTIONS, matchedEntity: "IVANOV, Sergey Mikhailovich (EU Consolidated List #12345)", similarityScore: 0.88}` |

### Compliance Analyst Review

| Step | Actor | Action | Response |
|------|-------|--------|---------|
| 1 | Compliance Analyst | `GET /api/v1/fraud/alerts?type=SANCTIONS&status=OPEN` | Views sanctions alerts (separate from fraud alerts, higher priority) |
| 2 | Analyst | Reviews: matched entity details from EU list (DOB, nationality, aliases). Compares against transaction beneficiary. Checks additional sources | |
| 3a | Analyst (confirmed) | `PUT /api/v1/fraud/alerts/{id}/resolve` with `{decision: "CONFIRMED_SANCTION", notes: "DOB and nationality match EU list entry. Confirmed sanctioned individual."}` | Payment **BLOCKED permanently**. FIU report generated. Account investigation initiated |
| 3b | Analyst (false positive) | `PUT /api/v1/fraud/alerts/{id}/resolve` with `{decision: "FALSE_POSITIVE", notes: "Different individual. Norwegian citizen, DOB mismatch, common name."}` | Payment **RELEASED** to clearing. Name added to local false-positive cache (speeds future screening for this name) |

### Critical Rule: Sanctions Screening Cannot Be Skipped

| Scenario | Fraud Engine Behavior | Sanctions Behavior |
|----------|----------------------|-------------------|
| A5 is fully operational | Both run | Both run |
| Fraud scoring times out (>500ms) | AUTO_PASS (score=UNKNOWN), review later | Sanctions still runs — independent of fraud timeout |
| Sanctions screening unavailable | Fraud scoring still runs | **REJECT payment** — cannot proceed without sanctions clearance |
| Both timeout | Payment **REJECTED** | Because sanctions cannot be skipped |

**This is the key difference from fraud scoring:** Fraud can auto-pass on timeout (degraded but operational). Sanctions screening is a legal obligation — if unavailable, the payment must be rejected.

---

## Scenario 5F: VoP Result Integration with Fraud Scoring

*VoP (A6) runs before the transaction reaches A5. The VoP result is included in the fraud-check-request message.*

| VoP Result | Payer Action | Fraud Score Adjustment | Rationale |
|-----------|-------------|----------------------|-----------|
| MATCH | Proceeds | +0.0 (neutral) | Name verified — no additional risk |
| CLOSE_MATCH | Corrects name, proceeds | +0.0 (neutral) | Legitimate typo, user corrected |
| CLOSE_MATCH | Ignores correction, overrides | +0.1 (mild risk) | Why ignore the correction? |
| NO_MATCH | Cancels payment | N/A (no payment) | No transaction to score |
| NO_MATCH | **Overrides warning, proceeds** | **+0.3 (significant risk)** | Classic APP fraud indicator — paying someone who isn't who they said |
| NOT_FOUND | **Overrides warning, proceeds** | **+0.4 (high risk)** | IBAN doesn't exist at beneficiary PSP |

**Example impact:** A transaction with a base fraud score of 0.45 (just below the 0.5 manual-review threshold) + NO_MATCH override (+0.3) = 0.75, which exceeds the 0.7 AUTO_BLOCK threshold. The VoP override alone can elevate a borderline transaction to automatic blocking.

---

## Data Requirements

**FraudAlert entity:**
```
alert_id:               UUID
transaction_id:         UUID (reference to the scored transaction)
alert_type:             Enum (FRAUD, SANCTIONS)
account_id:             String
transaction_amount:     BigDecimal
currency:               String (ISO 4217)
fraud_score:            Double (0.0-1.0)
decision:               Enum (AUTO_PASS, MANUAL_REVIEW, AUTO_BLOCK, SANCTIONS_HOLD,
                               CONFIRMED_FRAUD, DISMISSED, CONFIRMED_SANCTION, FALSE_POSITIVE)
rules_triggered:        List<RuleResult> (rule_id, rule_name, contribution_to_score, explanation)
sanctions_result:       Enum (CLEAR, POTENTIAL_HIT, HIT) (nullable)
sanctions_matched_entry: String (nullable — which list entry matched)
sanctions_similarity:   Double (nullable — Jaro-Winkler score)
vop_result:             Enum (MATCH, CLOSE_MATCH, NO_MATCH, NOT_FOUND) (nullable)
vop_override:           Boolean
ml_model_version:       String (e.g., "fraud-v2.3.1")
scoring_latency_ms:     Long
created_at:             Instant (UTC)
resolved_at:            Instant (nullable)
resolved_by:            String (nullable, case officer ID)
resolution_notes:       String (nullable)
```

**Kafka Streams State Stores:**
```
velocity-window-store:  WindowStore<AccountId, TransactionCount>  (tumbling 60-min window)
daily-amount-store:     WindowStore<AccountId, BigDecimal>        (tumbling 24-hour window)
account-profile-store:  KeyValueStore<AccountId, AccountProfile>  (rolling stats)
```

## Security Considerations
- Case Management API requires OAuth2 token with `fraud:read` (view alerts) and `fraud:manage` (resolve alerts) scopes
- Case officer actions (CONFIRMED_FRAUD, DISMISSED) logged with officer identity, timestamp, and justification text
- Transaction data is PII (account numbers, amounts): encrypted at rest, access logged
- ML model integrity: model files have SHA-256 checksums verified at load time
- Fraud scoring decisions are immutable audit records: once written, they cannot be modified or deleted
- Kafka Streams state stores contain sensitive windowed transaction data: must be configured with encryption at rest
