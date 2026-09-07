# Project #12: Bill Payment & Request-to-Pay Service — Requirements

*System A role: A3 — eFaktura / R2P / AvtaleGiro / QR-initiated payments*
*Target companies: Mastercard Oslo (eFaktura/AvtaleGiro), DNB, SpareBank 1, Vipps MobilePay*
*Standards: SEPA Request to Pay (SRTP, EPC014-20), EPC QR (EPC069-12), ISO 20022 pain.013/014*

---

## 1. Overview

A service that handles the "request" side of payments: creditors submit payment requests (invoices), debtors respond (accept/decline/defer/amend), and accepted requests trigger payment execution through the clearing engine (A2). Evolved from the Norwegian eFaktura model toward the pan-European SEPA Request-to-Pay (SRTP) standard.

Also supports recurring mandates (AvtaleGiro pattern) and QR code-initiated payments.

### 1.1 Target Company Alignment

| Company | What they build | Alignment |
|---------|----------------|-----------|
| **Mastercard Oslo** | eFaktura — Norway's electronic invoicing system. Used by 90%+ of Norwegian/Danish households. AvtaleGiro — direct debit mandates | **Direct mirror.** Our R2P extends eFaktura toward the EU SRTP standard. AvtaleGiro is our recurring mandate (RTP-M01-M05) |
| **DNB** | eFaktura integration, bill payment in mobile banking app | Our API surface matches what DNB's mobile app would consume |
| **SpareBank 1** | PSD2 Developer Portal. Payment initiation integration | Our R2P APIs align with their developer portal approach |
| **Vipps MobilePay** | In-app bill payment ("Regninger"), request money (P2P R2P), QR code payments | Our QR initiation + R2P model matches Vipps' bill payment and request-money features |

---

## 2. Functional Requirements — Request-to-Pay (Core)

| ID | Requirement | Priority |
|----|------------|----------|
| RTP-F01 | **Merchant API**: POST `/api/v1/requests` — create payment request (creditor, debtor, amount, currency, due date, reference, description) | Must |
| RTP-F02 | **Consumer API**: GET `/api/v1/requests?status=PENDING` — list pending requests for authenticated consumer | Must |
| RTP-F03 | **Accept**: PUT `/api/v1/requests/{id}/accept` with `{paymentMethod: INSTANT or STANDARD}` | Must |
| RTP-F04 | **Decline**: PUT `/api/v1/requests/{id}/decline` with `{reason: enum, message: optional text}` | Must |
| RTP-F05 | **Defer**: PUT `/api/v1/requests/{id}/defer` with `{payDate: ISO date}` — accept but pay later | Must |
| RTP-F06 | **Request amendment**: PUT `/api/v1/requests/{id}/amend` with `{proposedAmount, proposedDate}` → merchant can accept/reject | Should |
| RTP-F07 | Request lifecycle: CREATED → PENDING → ACCEPTED → PAYMENT_INITIATED → SETTLED / REJECTED / DECLINED / DEFERRED / EXPIRED / AMENDED | Must |
| RTP-F08 | On ACCEPT: VoP check (via A6) → publish PaymentInstruction to Kafka (instant or batch topic) | Must |
| RTP-F09 | Idempotency key on POST: duplicate submissions return existing request | Must |
| RTP-F10 | Request expiry: not responded to by due date → auto-transition to EXPIRED | Should |
| RTP-F11 | ISO 20022: model pain.013 (CreditorPaymentActivationRequest) and pain.014 (status) message structures | Should |
| RTP-F12 | **Bulk invoice submission**: POST `/api/v1/requests/batch` — accept NDJSON or CSV body with multiple payment requests in one call. Return batch_id + per-request status. For utility/telecom merchants submitting thousands of invoices monthly | Should |

### 2.1 Decline Reason Codes

| Code | Meaning |
|------|---------|
| ALREADY_PAID | This invoice was already paid through another channel |
| AMOUNT_INCORRECT | The amount is wrong |
| NOT_RECOGNIZED | I don't recognize this creditor or invoice |
| DUPLICATE | I already received this request |
| OTHER | Other reason (free text) |

---

## 3. Functional Requirements — Recurring Mandates (AvtaleGiro)

| ID | Requirement | Priority |
|----|------------|----------|
| RTP-M01 | POST `/api/v1/mandates` — consumer creates recurring authorization for a merchant | Must |
| RTP-M02 | Mandate parameters: merchant_id, debtor_account, max_single_amount, currency, frequency (MONTHLY, QUARTERLY, etc.) | Must |
| RTP-M03 | When a merchant submits a request matching an active mandate AND amount <= max: auto-accept (no consumer interaction) | Must |
| RTP-M04 | If amount > mandate max: request goes to PENDING (consumer must manually approve) | Must |
| RTP-M05 | Mandate lifecycle: ACTIVE → SUSPENDED → ACTIVE (resume) or ACTIVE → REVOKED (terminal) | Must |
| RTP-M06 | Consumer can modify mandate: PATCH `/api/v1/mandates/{id}` (change max amount, suspend, revoke) | Must |
| RTP-M07 | Merchant CANNOT modify consumer's mandates — only query their status | Must |

---

## 4. Functional Requirements — QR Code Payment Initiation

| ID | Requirement | Priority |
|----|------------|----------|
| RTP-Q01 | **Dynamic QR**: POST `/api/v1/requests/{id}/qr` — generate QR code for a specific payment request | Should |
| RTP-Q02 | QR encodes EPC069-12 format: BIC, IBAN, beneficiary name, amount, currency, reference | Should |
| RTP-Q03 | Consumer scans QR → auto-fills payment request details → approves → triggers payment | Should |
| RTP-Q04 | **Static QR**: GET `/api/v1/merchants/{id}/qr` — merchant's static QR (IBAN + name, no amount) | Should |
| RTP-Q05 | QR code returned as PNG image (Base64 encoded in JSON response) or SVG | Should |

---

## 5. Functional Requirements — Webhooks

| ID | Requirement | Priority |
|----|------------|----------|
| RTP-W01 | Merchant registers webhook URL during onboarding | Must |
| RTP-W02 | Status change → POST webhook to merchant with event payload | Must |
| RTP-W03 | Payload signed with HMAC-SHA256 using per-merchant secret. Header: `X-Webhook-Signature` | Must |
| RTP-W04 | Retry on failure: exponential backoff (1s, 2s, 4s, 8s, 16s). Max 5 retries | Must |
| RTP-W05 | After 5 failures: mark delivery FAILED, log for manual investigation | Must |
| RTP-W06 | Webhook payload includes: request_id, new_status, timestamp, amount, currency | Must |
| RTP-W07 | Include `X-Webhook-ID` (unique per delivery attempt) for idempotent processing by merchant | Should |
| RTP-W08 | Webhook endpoint must be HTTPS | Must |

---

## 6. Non-functional Requirements

| ID | Requirement | Target |
|----|------------|--------|
| RTP-NF01 | Request creation latency | < 200ms (p95) |
| RTP-NF02 | Webhook first delivery attempt | < 5 seconds after status change |
| RTP-NF03 | QR code generation | < 500ms |
| RTP-NF04 | Concurrent API requests | >= 100 |
| RTP-NF05 | Request search/filter | < 300ms (p95) |
| RTP-NF06 | API rate limiting | 100 req/min per merchant API key (configurable). HTTP 429 with `Retry-After` header |

### 6.1 Resilience and Crash Recovery

*Propagated from master system requirements (section 2.7)*

| ID | Requirement | Priority |
|----|------------|----------|
| RTP-RES01 | **Transactional Outbox Pattern**: when A3 transitions a request to ACCEPTED/PAYMENT_INITIATED and publishes to Kafka, use the Transactional Outbox — write the status change and the outbound Kafka event to PostgreSQL in the same transaction. A relay process then publishes to Kafka. This prevents "status changed but event lost" on crash | Must |
| RTP-RES02 | **Dead Letter Queue**: Kafka consumer for `payment.status-updates` must route unprocessable messages to `payment.status-updates.dlq` after 3 retries. Monitor DLQ depth via Prometheus | Must |
| RTP-RES03 | **VoP caching for mandates**: VoP result may be cached for recurring mandate payments to the same beneficiary. Re-check required if: (a) > 90 days since last VoP check, (b) mandate details modified, or (c) beneficiary PSP notifies of account holder name change. This avoids repeated VoP queries for predictable recurring payments | Should |

---

## 7. Technology Stack

| Component | Technology | Company Alignment |
|-----------|-----------|-------------------|
| Framework | **Spring Boot 3.x** | Universal |
| API | **REST + OpenAPI 3.0** (springdoc-openapi) | Contract-first. Mastercard, SpareBank1 pattern |
| Auth (merchant) | **API key** in `X-API-Key` header | B2B pattern |
| Auth (consumer) | **OAuth2 Bearer token** | B2C pattern (from A4) |
| Database | **PostgreSQL** with **Flyway** | Universal |
| Messaging | **Kafka** (publish payment instructions, consume status updates) | Universal |
| Webhooks | **Spring RestTemplate/WebClient** with retry | Async outbound HTTP |
| Webhook signing | **HMAC-SHA256** (javax.crypto.Mac) | Vipps MobilePay pattern. Mastercard pattern |
| QR generation | **ZXing** (com.google.zxing) for QR code image generation | Standard Java QR library |
| EPC QR format | **Custom formatter** following EPC069-12 spec | Text-based format, straightforward |
| Scheduling | **Spring Scheduler** (for mandate auto-acceptance and request expiry) | Standard |
| Testing | **Testcontainers**, **WireMock** (webhook testing), JUnit 5 | Production-grade |

---

## 8. Data Model

### PaymentRequest
```
request_id:         UUID (PK)
idempotency_key:    String (unique, merchant-provided)
creditor_account:   String (IBAN)
debtor_account:     String (IBAN)
amount:             BigDecimal
currency:           String (ISO 4217)
due_date:           LocalDate
description:        String (max 140 chars)
reference:          String (KID/structured reference)
status:             Enum (PENDING, ACCEPTED, DECLINED, DEFERRED, EXPIRED, 
                          PAYMENT_INITIATED, SETTLED, REJECTED, AMENDED)
payment_method:     Enum (INSTANT, STANDARD) (nullable — set on accept)
merchant_id:        String (API key owner)
consumer_id:        String (OAuth2 token subject)
mandate_id:         UUID (nullable — if auto-accepted via mandate)
decline_reason:     String (nullable)
defer_date:         LocalDate (nullable)
amendment:          JSONB (nullable — {proposedAmount, proposedDate, merchantResponse})
created_at:         Instant (UTC)
updated_at:         Instant (UTC)
```

### RecurringMandate
```
mandate_id:         UUID (PK)
consumer_id:        String
debtor_account:     String (IBAN)
merchant_id:        String
max_single_amount:  BigDecimal
currency:           String (ISO 4217)
frequency:          Enum (WEEKLY, MONTHLY, QUARTERLY, ANNUAL, ON_DEMAND)
status:             Enum (ACTIVE, SUSPENDED, REVOKED)
created_at:         Instant
revoked_at:         Instant (nullable)
```

### WebhookDelivery
```
delivery_id:        UUID (PK)
request_id:         UUID (FK)
merchant_id:        String
webhook_url:        String (HTTPS)
payload:            JSONB
signature:          String (HMAC-SHA256)
status:             Enum (PENDING, DELIVERED, FAILED)
attempt_count:      Integer
last_attempt_at:    Instant
next_retry_at:      Instant (nullable)
response_status:    Integer (nullable — HTTP status from merchant)
```

---

## 9. Security Requirements

| ID | Requirement |
|----|------------|
| RTP-S01 | Merchant authenticated via API key. Consumer via OAuth2 Bearer token |
| RTP-S02 | Consumer can only see/respond to requests targeting their own accounts |
| RTP-S03 | Webhook HMAC-SHA256 signature verification: merchants must verify signature before processing |
| RTP-S04 | HTTPS required for all webhook endpoints |
| RTP-S05 | Recurring mandate creation requires consumer authentication (OAuth2) |
| RTP-S06 | Merchant cannot create/modify/revoke mandates — consumer-only operation |
| RTP-S07 | PII (debtor account, name) encrypted at rest in PostgreSQL |
| RTP-S08 | Webhook payloads must not contain full account numbers (last 4 digits only) |
| RTP-S09 | Idempotency key prevents replay attacks on request creation |

---

## 10. Acceptance Criteria

| ID | Criterion |
|----|----------|
| AC-01 | Merchant creates request → consumer sees it in pending list → accepts (instant) → settled in <10 seconds |
| AC-02 | Consumer declines with reason "ALREADY_PAID" → merchant receives webhook with decline reason |
| AC-03 | Consumer defers to May 15 → request reappears on May 15 for approval |
| AC-04 | Duplicate idempotency key → 200 with existing request (not 201) |
| AC-05 | Mandate with max 5,000 NOK → merchant submits 3,000 → auto-accepted. Merchant submits 6,000 → goes to PENDING |
| AC-06 | Consumer revokes mandate → merchant's next auto-accept attempt fails |
| AC-07 | QR code generated → scanning with camera/app shows payment request details |
| AC-08 | Webhook delivery: WireMock returns 500 → retry 5 times with backoff → mark FAILED |
| AC-09 | Webhook signature verifiable by merchant using shared secret |
| AC-10 | Request expires past due date → auto-transition to EXPIRED → webhook sent |
