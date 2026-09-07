# UC-02: Fraud & Sanctions Alert Case Management

*Human-in-the-loop: analysts review, investigate, and resolve flagged transactions*

---

## Scenario 2A: Fraud Alert Investigation and Confirmation

### Primary Actor
**Fraud Analyst** — reviews flagged transactions and makes decisions

### Preconditions
- Transaction was scored AUTO_BLOCK or MANUAL_REVIEW
- Alert exists in PostgreSQL with status OPEN
- Analyst has OAuth2 token with `fraud:read` and `fraud:manage` scopes

### Main Success Scenario

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | Analyst | `GET /api/v1/fraud/alerts?status=OPEN&sort=score,desc&limit=20` | Returns paginated list of open alerts, highest risk first |
| 2 | Analyst | `GET /api/v1/fraud/alerts/{id}` | Returns full detail: transaction, score breakdown, rules, account profile, VoP result |
| 3 | Analyst | Reviews: account X had 12 rapid transactions in 30 min totaling NOK 180,000 to a new creditor. VoP returned NO_MATCH and payer overrode. ML score 0.87 | Analyst concludes: compromised account, authorized push payment fraud |
| 4 | Analyst | `PUT /api/v1/fraud/alerts/{id}/resolve` with `{decision: "CONFIRMED_FRAUD", notes: "Account compromised. 12 rapid transactions to unknown creditor. VoP override. Consistent with APP fraud pattern."}` | Alert → CONFIRMED_FRAUD. Account freeze command published to Kafka. All pending payments for account X rejected |
| 5 | — | A1 Account Ledger receives freeze command | Account X status → FROZEN. All debits blocked |

### Postconditions
- Alert status: CONFIRMED_FRAUD
- Account X: FROZEN in A1
- Audit trail: analyst ID, timestamp, decision, justification notes
- All pending payments from account X rejected by A2

---

## Scenario 2B: Fraud Alert Dismissed (False Positive)

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | Analyst | Reviews alert: business account making bulk supplier payments (normal pattern for this account type) | |
| 2 | Analyst | `PUT /api/v1/fraud/alerts/{id}/resolve` with `{decision: "DISMISSED", notes: "Business account. Bulk supplier payments are normal operating pattern."}` | Alert → DISMISSED |
| 3 | — | If payment was HELD (MANUAL_REVIEW), it is now **released** to A2 for settlement | Payment proceeds through clearing |
| 4 | — | False positive counter incremented | Metric: `fraud_false_positive_total` +1. Used for rule tuning |

---

## Scenario 2C: Sanctions Alert — Confirmed Match

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | Compliance Analyst | `GET /api/v1/fraud/alerts?type=SANCTIONS&status=OPEN` | Returns sanctions alerts |
| 2 | Analyst | Reviews: creditor name "Ivanov Sergey" matched EU list entry #12345 "IVANOV, Sergey Mikhailovich" with similarity 0.96 | |
| 3 | Analyst | Checks additional sources: EU sanctions list detail shows this is the sanctioned individual (same country, DOB match) | |
| 4 | Analyst | `PUT /api/v1/fraud/alerts/{id}/resolve` with `{decision: "CONFIRMED_SANCTION", notes: "Match confirmed against EU Consolidated List #12345. DOB and country match."}` | Alert → CONFIRMED_SANCTION |
| 5 | — | Payment **BLOCKED** permanently. Funds frozen | |
| 6 | — | FIU report generated (structured data for filing) | Contains: transaction details, matched entity, analyst decision, evidence |

---

## Scenario 2D: Sanctions Alert — False Positive

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | Analyst | Reviews: creditor name "Ivanova, Sergei" matched "IVANOV, Sergey" but is a different person (different country, different DOB) | Common name, false positive |
| 2 | Analyst | `PUT /api/v1/fraud/alerts/{id}/resolve` with `{decision: "FALSE_POSITIVE", notes: "Different individual. Country: Norway (not Russia). DOB mismatch."}` | Alert → FALSE_POSITIVE |
| 3 | — | Payment **RELEASED** to A2 for settlement | |
| 4 | — | Name added to local "known false positive" cache (speeds future screening) | |

---

## Scenario 2E: Dashboard and Metrics

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | Manager | `GET /api/v1/fraud/dashboard` | Returns: `{openAlerts: 42, resolvedToday: 156, avgResolutionMinutes: 12, falsePositiveRate: 0.73, confirmedFraudCount: 5, sanctionsHitCount: 1, totalTransactionsScored: 1250000}` |
| 2 | — | Grafana dashboard (built from Prometheus metrics) | Visualizes: scoring latency, alert volumes, false positive trend, sanctions hit rate, ML model performance |

**False positive rate of 0.73 (73%)** is realistic for sanctions screening — most hits on fuzzy name matching are false positives. The goal is to never miss a true positive (100% recall) even at the cost of high false positive rate.

---

## API Design

### List Alerts
```
GET /api/v1/fraud/alerts?status=OPEN&type=FRAUD&minScore=0.5&maxScore=1.0
    &fromDate=2026-04-01&toDate=2026-04-11&accountId=NO93860111117947
    &sort=score,desc&page=0&size=20

Response: 200 OK
{
  "content": [...],
  "page": {"size": 20, "number": 0, "totalElements": 42, "totalPages": 3}
}
```

### Get Alert Detail
```
GET /api/v1/fraud/alerts/{id}

Response: 200 OK
{
  "alertId": "uuid",
  "alertType": "FRAUD",
  "status": "OPEN",
  "transaction": { ... },
  "fraudScore": 0.852,
  "scoreBreakdown": {
    "sanctionsResult": "CLEAR",
    "velocityScore": 0.9,
    "anomalyScore": 0.99,
    "mlScore": 0.87,
    "vopOverrideBonus": 0.3
  },
  "rulesTriggered": [ ... ],
  "accountProfile": { ... },
  "createdAt": "2026-04-11T14:23:45Z",
  "assignedTo": null
}
```

### Resolve Alert
```
PUT /api/v1/fraud/alerts/{id}/resolve
{
  "decision": "CONFIRMED_FRAUD",
  "notes": "Account compromised. Consistent with APP fraud pattern."
}

Response: 200 OK
{
  "alertId": "uuid",
  "status": "CONFIRMED_FRAUD",
  "resolvedBy": "analyst-42",
  "resolvedAt": "2026-04-11T14:35:12Z"
}
```

---

## Security Considerations
- `fraud:read` scope: can view alerts but not resolve them (for reporting/monitoring roles)
- `fraud:manage` scope: can resolve alerts, trigger account freezes (for analyst roles)
- Analyst identity captured from JWT `sub` claim — every resolution decision is attributed
- Sanctions decisions generate legal records — must be retained per AML retention requirements (minimum 5 years)
- Case management API rate-limited to prevent data exfiltration (alert data contains PII)
