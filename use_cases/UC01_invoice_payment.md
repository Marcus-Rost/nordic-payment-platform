# UC-01: Electronic Invoice Payment (eFaktura Flow)

*Sub-systems involved: A3 (Bill Payment), A2 (Clearing), A5 (Fraud Detection), A1 (Account Ledger)*
*Mirrors: Mastercard Oslo eFaktura + AvtaleGiro, used by 90%+ of Norwegian/Danish households*

---

## Primary Actor
**Merchant** (creditor) and **Consumer** (debtor/account holder)

## Preconditions
- Merchant has a registered API key with the Bill Payment Service (A3)
- Consumer has an active account in the Account Ledger (A1)
- Consumer has a valid OAuth2 session
- Consumer's account has sufficient balance

## Trigger
Merchant submits an electronic invoice for a consumer

---

## Main Success Scenario (Instant Payment Path)

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | Merchant | POST `/api/v1/requests` with creditor account, debtor account, amount (NOK 1,250.00), due date, reference, description | A3 validates input, checks idempotency key, creates request with status PENDING. Returns 201 with request ID |
| 2 | Consumer | GET `/api/v1/requests?status=PENDING` | A3 returns list of pending payment requests for the consumer's accounts |
| 3 | Consumer | PUT `/api/v1/requests/{id}/accept` with `{payment_method: "INSTANT"}` | A3 transitions request to ACCEPTED |
| 4 | — | **VoP check (A6)** | A6 queries beneficiary PSP: "Does creditor IBAN belong to stated merchant name?" → **MATCH**. Result logged |
| 5 | — | A3 publishes PaymentInstruction to Kafka `payment.instant-instructions` | Includes: VoP result, requested service level INSTANT |
| 6 | — | Clearing Engine (A2, instant path) consumes instruction | A2 validates ISO 20022 pacs.008. **Semantic duplicate check**: same debtor+creditor+amount in last 10 min? → No match (first payment). Requests **balance reservation** from A1 |
| 7 | — | Account Ledger reserves funds | A1 checks available balance >= NOK 1,250.00 → reserves (tentative hold). Available balance reduced |
| 8 | — | A2 sends to Fraud & Sanctions Engine | Publishes to `payment.instant-fraud-check-request` |
| 9 | — | **Sanctions screening (A5)** | A5 screens originator + beneficiary against EU/UN sanctions lists (<50ms) → **CLEAR** |
| 10 | — | **Fraud scoring (A5)** | A5 applies velocity rules (OK), anomaly detection (OK), ML scoring (score=0.08) → **PASS** |
| 11 | — | A5 publishes combined result | `{sanctions: CLEAR, fraud_score: 0.08, decision: PASS}` to `payment.instant-fraud-check-response` |
| 12 | — | A2 executes gross settlement | Confirms balance reservation in A1 (hold → debit). Credits merchant account. Publishes to `payment.instant-settlement-events` |
| 13 | — | A2 publishes pacs.002 confirmation + status update | SETTLED status to `payment.status-updates`. **Total elapsed: ~3-4 seconds** |
| 14 | — | A3 receives status update | Transitions request to SETTLED |
| 15 | — | A3 sends webhook to merchant | HMAC-SHA256 signed: `{"request_id": "...", "status": "SETTLED", "settled_at": "..."}` |

## Postconditions
- Invoice status = SETTLED in A3
- Consumer account balance decreased by NOK 1,250.00 in A1
- Merchant account balance increased by NOK 1,250.00 in A1
- Complete audit trail in all services
- Merchant received webhook notification

---

## Alternative Flows

### AF-01: VoP Name Mismatch
| Step | Condition | System Response |
|------|-----------|----------------|
| 4a | VoP returns **NO_MATCH** | A3 warns consumer: "The name you entered does not match the account holder." Consumer can override (proceed anyway) or cancel |
| 4b | Consumer overrides NO_MATCH warning | Override logged in audit trail. VoP result forwarded to A5 as HIGH_RISK signal. Payment proceeds but with elevated fraud scrutiny |
| 4c | VoP returns **CLOSE_MATCH** (actual name: "Nordmann AS") | A3 shows consumer: "Did you mean 'Nordmann AS'?" Consumer corrects name and proceeds |

### AF-02: Sanctions Hit
| Step | Condition | System Response |
|------|-----------|----------------|
| 9a | Sanctions screening returns **HIT** (beneficiary name matches sanctioned entity) | Payment immediately HELD. Alert created for compliance analyst in A5 case management |
| 9b | Compliance analyst confirms match | Payment BLOCKED. Funds unfrozen from reservation. Report generated for FIU. Merchant notified: REJECTED (reason: COMPLIANCE) |
| 9c | Compliance analyst determines false positive | Payment released. Proceeds to fraud scoring and settlement |

### AF-03: Fraud Detected
| Step | Condition | System Response |
|------|-----------|----------------|
| 10a | Fraud score = 0.85 (> threshold 0.7) | A5 flags as HIGH_RISK |
| 12a | A2 rejects payment | Status REJECTED (reason: FRAUD_SUSPECTED). Balance reservation released in A1. Alert created in A5 case management |

### AF-04: Insufficient Balance
| Step | Condition | System Response |
|------|-----------|----------------|
| 7a | A1 cannot reserve funds (available balance < amount) | A1 returns INSUFFICIENT_FUNDS. A2 rejects. A3 transitions to FAILED |

### AF-05: Consumer Declines Request (R2P extension)
| Step | Condition | System Response |
|------|-----------|----------------|
| 3a | Consumer chooses PUT `/api/v1/requests/{id}/decline` with `{reason: "ALREADY_PAID"}` | A3 transitions to DECLINED. Webhook to merchant with reason code. No payment initiated |

### AF-06: Consumer Defers Payment (R2P extension)
| Step | Condition | System Response |
|------|-----------|----------------|
| 3b | Consumer chooses PUT `/api/v1/requests/{id}/defer` with `{pay_date: "2026-05-15"}` | A3 transitions to DEFERRED. Scheduled job will present request again on pay_date. Merchant notified via webhook |

### AF-07: Duplicate Request Submission
| Step | Condition | System Response |
|------|-----------|----------------|
| 1a | Merchant submits POST with same idempotency key | A3 returns 200 with existing request (not 201) |

### AF-08: Request Expired
| Step | Condition | System Response |
|------|-----------|----------------|
| — | Due date passes without consumer action | A3 transitions to EXPIRED. Webhook sent to merchant |

### AF-09: Webhook Delivery Failure
| Step | Condition | System Response |
|------|-----------|----------------|
| 15a | Merchant webhook endpoint returns 5xx or unreachable | Retry: exponential backoff 1s, 2s, 4s, 8s, 16s (max 5). After 5 failures: FAILED, log for investigation |

### AF-10: Instant Payment Timeout
| Step | Condition | System Response |
|------|-----------|----------------|
| — | End-to-end processing exceeds 7-second hard limit (EPC 2025) | A2 rejects with TIMEOUT reason code. Balance reservation released. No limbo state |

### AF-11: Semantic Duplicate Detected
| Step | Condition | System Response |
|------|-----------|----------------|
| 6a | A2 semantic duplicate check: same debtor + same creditor + same amount (NOK 1,250) within 10 min. Matches earlier payment P-001 | A2 returns warning to A3: `{duplicateWarning: {duplicateOf: "P-001", submittedAt: "3 min ago"}}` |
| 6b | Consumer sees: "This looks similar to a payment of NOK 1,250 to the same merchant submitted 3 minutes ago. Proceed?" | |
| 6c-cancel | Consumer: "Oops, I already paid" → cancels | Payment abandoned. Duplicate avoided |
| 6c-proceed | Consumer: "This is a different invoice" → confirms override | Payment proceeds. Duplicate override logged. +0.2 fraud risk signal to A5 |

---

## Data Requirements

**Invoice entity:**
```
invoice_id:         UUID (primary key)
idempotency_key:    String (unique, provided by merchant)
creditor_account:   String (IBAN or 11-digit Norwegian account number)
debtor_account:     String
amount:             BigDecimal
currency:           String (ISO 4217: NOK, EUR, SEK, DKK, USD)
due_date:           LocalDate
description:        String (max 140 chars)
reference:          String (KID number or structured reference)
status:             Enum (PENDING, ACCEPTED, PAYMENT_INITIATED, SETTLED, REJECTED, DECLINED, DEFERRED, EXPIRED)
created_at:         Instant (UTC)
updated_at:         Instant (UTC)
merchant_id:        String (API key owner)
webhook_url:        String (HTTPS URL for status notifications)
```

## Security Considerations
- Merchant authenticated via API key in `X-API-Key` header
- Consumer authenticated via OAuth2 Bearer token; can only see/approve invoices for own accounts
- Webhook payloads signed with HMAC-SHA256; merchant must verify signature before processing
- Debtor account number is PII: encrypted at rest in database
