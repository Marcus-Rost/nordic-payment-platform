# A3 — Bill Payment & Request-to-Pay Service: Architectural Design

*Version 1.0*
*Date: 2026-04-11*

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Internal Architecture](#2-internal-architecture)
3. [Security Architecture](#3-security-architecture)
4. [Communication Architecture](#4-communication-architecture)
5. [Data Architecture](#5-data-architecture)
6. [Payment Request Lifecycle](#6-payment-request-lifecycle)
7. [Recurring Mandates](#7-recurring-mandates)
8. [Webhook Delivery System](#8-webhook-delivery-system)
9. [Bulk Invoice Submission](#9-bulk-invoice-submission)
10. [QR Code Generation](#10-qr-code-generation)
11. [VoP Integration](#11-vop-integration)
12. [Consistency and Crash Recovery](#12-consistency-and-crash-recovery)
13. [Scheduling](#13-scheduling)
14. [Cross-Cutting Concerns](#14-cross-cutting-concerns)
15. [Performance](#15-performance)
16. [Testing Architecture](#16-testing-architecture)
17. [Configuration](#17-configuration)
18. [Architectural Decision Log](#18-architectural-decision-log)

---

## 1. Introduction

### 1.1 Purpose

This document describes the architectural design of A3, the Bill Payment & Request-to-Pay Service. It translates the functional requirements (requirements.md) and use cases (UC01_request_to_pay_lifecycle.md) into structural decisions that guide implementation, grounded in the master architecture (payment_processing/architecture/ARCHITECTURAL_DESIGN.md).

### 1.2 Role in the Master System

A3 is a **DMZ-facing entry point** for creditors (merchants) and debtors (consumers). It owns **Phase 1** of the payment lifecycle (master decision B.1a): receiving payment requests, managing the accept/decline/defer/amend flow, triggering VoP checks, and publishing payment instructions to Kafka for clearing (A2).

A3 is the only subsystem with two distinct external-facing API audiences (merchants and consumers) and the only subsystem that sends outbound HTTP to external systems (webhooks to merchant endpoints).

### 1.3 Key Constraints

| Constraint | Source | Impact |
|-----------|--------|--------|
| Phase 1 time budget: ≤ 800ms | Master C.2a | VoP (500ms) + validation + Kafka publish (300ms) must fit |
| Transactional Outbox for dual-write | Master B.2a/B.2b | DB + outbox in same transaction; in-process relay + scheduled fallback |
| Platform Starter provides security defaults | Master A.1b | A3 overrides with dual SecurityFilterChain |
| Three-zone model: A3 in DMZ | Master A.1a | A3 handles external auth, rate limiting, input validation |
| Kafka HMAC signing on outbound messages | Master KM-06 | PaymentInstruction messages carry HMAC signature |

### 1.4 Related Documents

| Document | Location | Relationship |
|----------|----------|-------------|
| Requirements | `requirements/requirements.md` | What A3 must do |
| Use Cases | `use_cases/UC01_request_to_pay_lifecycle.md` | Scenarios A3 must support |
| Gap Analysis | `extensions/gap_analysis.md` | Comparison with eFaktura, SRTP, Vipps |
| Architecture Challenges | `architecture/ARCHITECTURE_CHALLENGES.md` | Open problems this design resolves |
| Architecture Decisions | `architecture/ARCHITECTURE_DECISIONS.md` | Options analysis for each decision |
| Architecture Draft | `architecture/architecture_draft.md` | Non-binding early overview |
| Master Architecture | `payment_processing/architecture/ARCHITECTURAL_DESIGN.md` | System-level design that constrains A3 |
| Master Decisions | `payment_processing/architecture/ARCHITECTURE_DECISIONS.md` | System-level decisions inherited by A3 |

---

## 2. Internal Architecture

### 2.1 Component Diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│  A3: Request-to-Pay Service                                             │
│                                                                          │
│  ┌─────────────────────────┐  ┌──────────────────────────────────────┐  │
│  │ Merchant API Layer       │  │ Consumer API Layer                   │  │
│  │ /api/v1/merchant/**      │  │ /api/v1/consumer/**                  │  │
│  │                          │  │                                      │  │
│  │ SecurityFilterChain #1   │  │ SecurityFilterChain #2               │  │
│  │ (API key auth)           │  │ (OAuth2 JWT auth)                    │  │
│  │                          │  │                                      │  │
│  │ MerchantRequestCtrl      │  │ ConsumerRequestCtrl                  │  │
│  │ MerchantBatchCtrl        │  │ ConsumerMandateCtrl                  │  │
│  │ MerchantQrCtrl           │  │                                      │  │
│  └────────────┬─────────────┘  └──────────────────┬───────────────────┘  │
│               │                                    │                     │
│               └──────────────┬─────────────────────┘                     │
│                              v                                           │
│  ┌───────────────────────────────────────────────────────────────────┐   │
│  │ Service Layer                                                     │   │
│  │                                                                   │   │
│  │ RequestLifecycleManager         MandateService                    │   │
│  │  - State machine (transition    - Create / modify / revoke        │   │
│  │    table + CAS enforcement)     - Auto-acceptance check           │   │
│  │  - VoP orchestration            - SELECT FOR UPDATE concurrency   │   │
│  │  - Outbox record creation                                         │   │
│  │                                                                   │   │
│  │ WebhookService                  QrCodeService                     │   │
│  │  - Delivery record creation     - EPC069-12 formatting            │   │
│  │  - HMAC-SHA256 signing          - ZXing QR generation             │   │
│  │                                                                   │   │
│  │ BulkSubmissionService                                             │   │
│  │  - NDJSON streaming parse                                         │   │
│  │  - Per-line validation                                            │   │
│  │  - Batch persist                                                  │   │
│  └───────────────────────────────────┬───────────────────────────────┘   │
│                                      │                                   │
│               ┌──────────────────────┼─────────────────────┐             │
│               v                      v                     v             │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────┐   │
│  │ PostgreSQL        │  │ Outbox Relay      │  │ VoP Client           │   │
│  │                   │  │                   │  │ (→ A6 REST, mTLS)    │   │
│  │ payment_request   │  │ In-process pub    │  │                      │   │
│  │ recurring_mandate │  │ after commit      │  │ Resilience4j:        │   │
│  │ webhook_delivery  │  │   +               │  │  - 500ms timeout     │   │
│  │ outbox            │  │ Scheduled fallback│  │  - Circuit breaker   │   │
│  │ merchant_config   │  │ (500ms poll)      │  │  - Mandate VoP cache │   │
│  └──────────────────┘  └──────────────────┘  └──────────────────────┘   │
│                                                                          │
│  ┌──────────────────────┐  ┌──────────────────────────────────────────┐  │
│  │ Status Consumer       │  │ Webhook Dispatcher                      │  │
│  │ (Kafka: payment.      │  │                                         │  │
│  │  status-updates)      │  │ Scheduled retry (exponential backoff)   │  │
│  │                       │  │ Circuit breaker per merchant             │  │
│  │ → CAS status update   │  │ HMAC-SHA256 signing                     │  │
│  │ → Webhook creation    │  │ Max 5 retries → FAILED + alert          │  │
│  └──────────────────────┘  └──────────────────────────────────────────┘  │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐    │
│  │ Schedulers                                                       │    │
│  │  - Request expiry (60s poll, CAS SQL)                            │    │
│  │  - Deferred reactivation (60s poll, CAS SQL)                     │    │
│  │  - Outbox relay fallback (500ms poll)                            │    │
│  │  - Webhook retry (5s poll)                                       │    │
│  └──────────────────────────────────────────────────────────────────┘    │
└──────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Package Structure

```
no.nordpay.rtp/
├── config/                          # Spring configuration
│   ├── MerchantSecurityConfig       # SecurityFilterChain #1 (API key)
│   ├── ConsumerSecurityConfig       # SecurityFilterChain #2 (OAuth2 JWT)
│   ├── KafkaConfig                  # Producer + consumer configuration
│   ├── ResilienceConfig             # Circuit breaker definitions
│   └── SchedulingConfig             # @Scheduled job configuration
│
├── controller/
│   ├── merchant/                    # Merchant-facing REST controllers
│   │   ├── MerchantRequestController
│   │   ├── MerchantBatchController
│   │   └── MerchantQrController
│   └── consumer/                    # Consumer-facing REST controllers
│       ├── ConsumerRequestController
│       └── ConsumerMandateController
│
├── service/                         # Business logic
│   ├── RequestLifecycleManager      # State machine, VoP orchestration, outbox
│   ├── MandateService               # Mandate CRUD + auto-acceptance
│   ├── WebhookService               # Delivery creation + HMAC signing
│   ├── WebhookDispatcher            # Scheduled retry + circuit breaker
│   ├── BulkSubmissionService        # NDJSON parse + batch persist
│   ├── QrCodeService                # EPC069-12 format + ZXing generation
│   ├── OutboxRelay                  # In-process publish + scheduled fallback
│   └── VopClient                    # A6 REST client with circuit breaker
│
├── domain/                          # Domain model
│   ├── PaymentRequest               # JPA entity
│   ├── RecurringMandate             # JPA entity
│   ├── WebhookDelivery              # JPA entity
│   ├── OutboxRecord                 # JPA entity
│   ├── MerchantConfig               # JPA entity (webhook URL, API key hash, HMAC secret)
│   ├── Status                       # Enum
│   ├── Trigger                      # Enum
│   ├── DeclineReason                # Enum
│   ├── PaymentMethod                # Enum (INSTANT, STANDARD)
│   ├── MandateStatus                # Enum (ACTIVE, SUSPENDED, REVOKED)
│   ├── MandateFrequency             # Enum
│   └── StateMachine                 # Static transition table (Map<Status, Set<Trigger>>)
│
├── repository/                      # Spring Data JPA repositories
│   ├── PaymentRequestRepository     # All queries include tenant filter
│   ├── RecurringMandateRepository
│   ├── WebhookDeliveryRepository
│   ├── OutboxRepository
│   └── MerchantConfigRepository
│
├── kafka/                           # Kafka producers + consumers
│   ├── PaymentInstructionProducer   # Publishes to payment.instructions / payment.instant-instructions
│   └── StatusUpdateConsumer         # Consumes from payment.status-updates
│
├── auth/                            # Authentication + authorization
│   ├── ApiKeyAuthFilter             # Extracts + validates API key from X-API-Key header
│   ├── AuthorizationChecker         # @authz bean for @PreAuthorize expressions
│   └── MerchantPrincipal            # Authentication principal for merchant context
│
└── scheduler/                       # Scheduled jobs
    ├── RequestExpiryJob             # Expire overdue PENDING requests
    ├── DeferredReactivationJob      # Reactivate DEFERRED requests on pay date
    └── StartupRecoveryRunner        # B.1b database recovery scan on startup
```

### 2.3 Dependencies

```
A3 depends on:
├── payment-security-starter         # Platform Starter (auto-configured)
├── payment-domain-model             # Shared types: MoneyAmount, PaymentInstruction, VoPResult, exceptions
├── payment-avro-schemas             # Avro schemas for Kafka messages
├── spring-boot-starter-web          # REST API
├── spring-boot-starter-data-jpa     # PostgreSQL access
├── spring-boot-starter-oauth2-resource-server  # OAuth2 JWT validation
├── spring-kafka                     # Kafka producer + consumer
├── flyway-core                      # Database migrations
├── resilience4j-spring-boot3        # Circuit breaker for VoP + webhooks
├── zxing-core + zxing-javase        # QR code generation
└── springdoc-openapi-starter-webmvc-ui  # OpenAPI documentation

Test dependencies:
├── spring-boot-testcontainers       # Kafka + PostgreSQL containers
├── wiremock-spring-boot             # Mock A6 (VoP) + merchant webhook endpoints
├── au.com.dius.pact                 # Contract tests for Kafka messages
└── no.nav.security:mock-oauth2-server  # OAuth2 testing
```

---

## 3. Security Architecture

### 3.1 Dual Authentication Model

A3 is the only service with two external authentication mechanisms on the same deployment:

| API Surface | Authentication | Identity Source | Rate Limiting |
|------------|---------------|----------------|---------------|
| `/api/v1/merchant/**` | API key in `X-API-Key` header | `MerchantConfig` table (API key hash) | 100 req/min per API key (RTP-NF06) |
| `/api/v1/consumer/**` | OAuth2 Bearer token (JWT) | A4 PSD2 Gateway (JWKS endpoint) | Inherited from A4's token issuance |

Two `SecurityFilterChain` beans, ordered by specificity:

```java
@Bean @Order(1)
SecurityFilterChain merchantFilterChain(HttpSecurity http) {
    return http
        .securityMatcher("/api/v1/merchant/**")
        .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .build();
}

@Bean @Order(2)
SecurityFilterChain consumerFilterChain(HttpSecurity http) {
    return http
        .securityMatcher("/api/v1/consumer/**")
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwkSetUri(a4JwksUri)))
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .build();
}
```

The Platform Starter's default `SecurityFilterChain` (`anyRequest().authenticated()`) remains active for any paths not matched by these two — acting as a catch-all that rejects unknown paths.

### 3.2 API Key Authentication

The `ApiKeyAuthFilter` extracts the `X-API-Key` header, hashes it (SHA-256), and looks up the hash in the `merchant_config` table. If found, a `MerchantPrincipal` is set in the `SecurityContext` containing `merchantId`, `webhookUrl`, and `hmacSecret` (for webhook signing).

API keys are stored as SHA-256 hashes — the plaintext key is never stored. The merchant receives the key once at onboarding.

**API Key Lifecycle:**
- **Generation:** Cryptographically random 256-bit key via `SecureRandom`. Issued once at merchant onboarding via admin API.
- **Storage:** SHA-256 hash stored in `merchant_config.api_key_hash`. Plaintext never stored.
- **Rotation:** Merchants can request a new key via admin API. The old key remains valid for a configurable overlap period (default: 24 hours) to allow migration. After overlap, old key is invalidated.
- **Revocation:** Immediate revocation via admin API (sets `api_key_status = REVOKED`). Revoked keys rejected at authentication filter.
- **Expiry:** Keys expire after configurable period (default: 365 days). Expired keys rejected at authentication filter. Merchant notified 30 days before expiry via webhook.
- **Brute-force protection:** After 10 consecutive failed authentication attempts with invalid API keys from the same IP, temporarily block the IP for 5 minutes. Logged as security event.
- **Stolen key detection:** Monitor for anomalous usage patterns — sudden change in source IP, geographic location, or request volume. Alert but do not auto-revoke (to avoid disrupting legitimate key migration).

### 3.3 OAuth2 JWT Validation

Consumer tokens are JWTs issued by A4 (PSD2 Gateway). A3 validates them locally using A4's JWKS endpoint (cached by Spring Security's `NimbusJwtDecoder`). No synchronous call to A4 per request — keys are fetched and cached, refreshed on signature validation failure.

The JWT `sub` claim identifies the consumer. A3 uses this as `consumerId` for all tenant-scoped queries.

### 3.4 Tenant Data Isolation

Two layers prevent cross-tenant data access:

**Layer 1 — Repository-level filtering:** Every query includes a tenant predicate. No repository method returns data across tenants.

```java
@Query("SELECT r FROM PaymentRequest r WHERE r.merchantId = :merchantId AND r.id = :id")
Optional<PaymentRequest> findByIdForMerchant(UUID id, String merchantId);

@Query("SELECT r FROM PaymentRequest r WHERE r.consumerId = :consumerId AND r.status = :status")
List<PaymentRequest> findByConsumerAndStatus(String consumerId, Status status);
```

**Layer 2 — Spring Security method-level authorization:** `@PreAuthorize` annotations on controller methods verify the authenticated principal owns the target resource.

```java
@PreAuthorize("@authz.isMerchantOwner(#id, authentication)")
@PutMapping("/api/v1/merchant/requests/{id}/amendment-response")
```

**Production target (documented, not implemented):** PostgreSQL Row-Level Security as a third defense layer.

### 3.5 Webhook HMAC Signing

Outbound webhooks are signed with per-merchant HMAC-SHA256 secrets:

```
X-Webhook-Signature: HMAC-SHA256(secret, requestBody)
X-Webhook-ID: <unique UUID per delivery attempt>
```

The HMAC secret is stored in `merchant_config` alongside the API key hash. It is generated at merchant onboarding and can be rotated via an admin API.

### 3.6 Input Validation

All external input is validated via Bean Validation (JSR 380) annotations on request DTOs before reaching the service layer. The Platform Starter's RFC 9457 error handler converts `MethodArgumentNotValidException` to a structured Problem Details response.

**Request DTO Restrictions:** All REST request DTOs use explicit field allowlists. Fields not in the DTO are silently ignored (Spring's default Jackson behavior with `@JsonIgnoreProperties(ignoreUnknown = true)`). System-controlled fields are never exposed in request DTOs:
- `status` — always server-assigned
- `transaction_id` — generated on accept, not at creation
- `vop_result` — set by service layer after A6 call
- `fraud_score` — set by A5, never client-provided
- `created_at`, `updated_at` — server timestamps

Additional validation:
- IBAN format check (modulo-97 checksum)
- Currency code against ISO 4217
- Amount > 0 and within reasonable bounds
- Due date in the future (for request creation)
- Webhook URL must be HTTPS (RTP-S04)

### 3.7 Kafka Message Signing

Outbound Kafka messages (`payment.instructions`, `payment.instant-instructions`) carry HMAC-SHA256 signatures in a `X-Message-HMAC` header (KM-06). The signing key is A3's producer-specific HMAC key, managed via the Platform Starter's `KeyProvider`. A2 verifies the signature before processing.

### Threat Model Traceability (Master A.3a)

| Interface | Threat | Control | Reference |
|-----------|--------|---------|-----------|
| Merchant API | Spoofing | API key authentication (SHA-256 hashed storage) | Section 3.2 |
| Merchant API | Denial of Service | Rate limiting 100 req/min per API key | Section 3.5 |
| Merchant API | Elevation of Privilege | Repository-level tenant isolation + @PreAuthorize | Section 3.4 |
| Consumer API | Spoofing | OAuth2 JWT from A4 | Section 3.3 |
| Consumer API | Denial of Service | Rate limiting 200 req/min per consumer_id | Section 15.3 |
| Webhook dispatch | SSRF (Spoofing internal services) | URL validation blocking private IPs, loopback, internal DNS | Section 8.1.1 |
| Webhook dispatch | Tampering | HMAC-SHA256 signature on webhook payload | RTP-S04 |
| Webhook dispatch | Repudiation | All webhook deliveries logged with payload hash | Section 8 |
| Kafka outbound (payment instructions) | Tampering | HMAC-SHA256 signing via Starter interceptor | KM-06 |
| Multi-tenant data | Information Disclosure | Dual-layer isolation: repository filter + @PreAuthorize | Section 3.4 |

### Security Requirements Traceability

| Requirement | Description | Architectural Element |
|------------|-------------|----------------------|
| RTP-S01 | Merchant authentication | API key in `X-API-Key` header, SHA-256 hashed storage |
| RTP-S02 | Consumer authentication | OAuth2 JWT from A4 |
| RTP-S03 | Multi-tenant data isolation | Repository-level tenant filter + `@PreAuthorize` |
| RTP-S04 | Webhook over HTTPS only | URL validation enforces HTTPS; SSRF protection blocks internal URLs |
| RTP-S05 | Webhook HMAC signing | HMAC-SHA256 signature in `X-Signature` header |
| RTP-S06 | Rate limiting | 100 req/min per merchant API key; 200 req/min per consumer |
| RTP-S07 | PII encrypted at rest | PostgreSQL with encrypted columns for debtor account/name |
| RTP-S08 | No full account numbers in webhooks | Webhook payload uses masked IBAN (last 4 digits) |
| RTP-S09 | Audit trail | All R2P lifecycle events logged via Platform Starter `@Audited` |

---

## 4. Communication Architecture

### 4.1 Interaction Map

```
                    EXTERNAL                              INTERNAL
                    ────────                              ────────

  Merchants ──API key──→ /api/v1/merchant/**
                              │
                              ├─── create request
                              ├─── submit batch
                              ├─── generate QR
                              ├─── respond to amendment
                              │
  Consumers ──OAuth2──→ /api/v1/consumer/**
                              │
                              ├─── list pending requests
                              ├─── accept / decline / defer / amend
                              ├─── manage mandates
                              │
                              v
                    ┌─────────────────┐
                    │ A3 Service Layer │
                    └────┬───┬───┬────┘
                         │   │   │
            ┌────────────┘   │   └────────────────┐
            v                v                     v
    A6 (VoP, mTLS)    Kafka (HMAC-signed)    Merchant webhook
    ← sync REST →     → payment.instructions  endpoints
    500ms timeout      → payment.instant-      ← async HTTP →
    Circuit breaker      instructions          HMAC-SHA256 signed
                       ← payment.status-       Exponential backoff
                         updates               Circuit breaker
```

### 4.2 REST APIs — Outbound

| Target | Purpose | Auth | Timeout | Resilience |
|--------|---------|------|---------|------------|
| A6 (VoP) | Verify creditor name before payment | mTLS | 500ms | Circuit breaker (Resilience4j): open after 5 failures, 30s cooldown. Fallback: `VOP_TIMEOUT` |
| Merchant webhook | Status change notification | N/A (outbound) | 5s | Circuit breaker per merchant: open after 5 failures, 60s cooldown. Durable retry with exponential backoff |

### 4.3 Kafka — Outbound (Producer)

| Topic | Message | Key | When |
|-------|---------|-----|------|
| `payment.instructions` | PaymentInstruction (Avro) | Debtor IBAN | Consumer accepts with `paymentMethod: STANDARD` |
| `payment.instant-instructions` | PaymentInstruction (Avro) | Debtor IBAN | Consumer accepts with `paymentMethod: INSTANT` |
| `audit.events` | AuditEvent (Avro) | Service name | Every state change (auto-configured by Starter) |

The PaymentInstruction Avro message includes:
- `transactionId` (UUID, generated by A3 at accept time)
- `creditorAccount`, `debtorAccount` (IBANs)
- `amount`, `currency`
- `reference`, `description`
- `vopResult` (MATCH, CLOSE_MATCH, NO_MATCH, TIMEOUT, UNAVAILABLE)
- `originatingService` ("A3")
- `initiationType` (REQUEST_TO_PAY, MANDATE_AUTO_ACCEPT, QR_INITIATED)

Published via the Transactional Outbox — the message is written to the outbox table in the same DB transaction as the status update to PAYMENT_INITIATED.

### 4.4 Kafka — Inbound (Consumer)

| Topic | Message | Consumer Group | Processing |
|-------|---------|---------------|------------|
| `payment.status-updates` | PaymentStatusUpdate (Avro) | `rtp-service-status` | CAS update of local request status (SETTLED, REJECTED, HELD, RETURN_INITIATED, RETURNED). On success, create webhook delivery record. |

Error handling (three-category, per master E.1b):
- **Retryable:** Kafka consumer temporarily behind, DB connection timeout → retry with backoff (max 3)
- **Permanent → DLQ:** Deserialization error, unknown `transactionId` (no matching request) → `payment.status-updates.dlq`
- **Critical → DLQ + alert:** Status update for a request in an unexpected state (e.g., SETTLED received for a DECLINED request)

---

## 5. Data Architecture

### 5.1 Database Schema

A3 owns a single PostgreSQL database with the following tables:

```sql
-- Core: Payment requests
CREATE TABLE payment_request (
    request_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_key   VARCHAR(255) NOT NULL UNIQUE,
    transaction_id    UUID UNIQUE,                        -- set on accept, travels to A2/A5/A1
    creditor_account  VARCHAR(34) NOT NULL,               -- IBAN
    debtor_account    VARCHAR(34) NOT NULL,               -- IBAN
    amount            NUMERIC(19,4) NOT NULL,
    currency          VARCHAR(3) NOT NULL,                -- ISO 4217
    due_date          DATE NOT NULL,
    description       VARCHAR(140),
    reference         VARCHAR(35),                        -- KID/structured reference
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payment_method    VARCHAR(10),                        -- INSTANT or STANDARD, set on accept
    merchant_id       VARCHAR(100) NOT NULL,
    consumer_id       VARCHAR(100),                       -- set when debtor identified via OAuth2
    mandate_id        UUID REFERENCES recurring_mandate(mandate_id),
    decline_reason    VARCHAR(20),
    decline_message   VARCHAR(500),
    defer_date        DATE,
    amendment         JSONB,                              -- {proposedAmount, proposedDate, merchantResponse}
    vop_result        VARCHAR(20),                        -- MATCH, CLOSE_MATCH, NO_MATCH, TIMEOUT
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version           INTEGER NOT NULL DEFAULT 0,         -- optimistic locking

    CONSTRAINT valid_status CHECK (status IN (
        'PENDING', 'ACCEPTED', 'DECLINED', 'DEFERRED', 'EXPIRED',
        'AMENDED', 'PAYMENT_INITIATED', 'SETTLED', 'REJECTED',
        'PAYMENT_HELD', 'RETURN_IN_PROGRESS', 'PAYMENT_RETURNED'
    )),
    CONSTRAINT valid_payment_method CHECK (payment_method IN ('INSTANT', 'STANDARD'))
);

CREATE INDEX idx_request_merchant ON payment_request(merchant_id, status);
CREATE INDEX idx_request_consumer ON payment_request(consumer_id, status);
CREATE INDEX idx_request_expiry   ON payment_request(status, due_date) WHERE status = 'PENDING';
CREATE INDEX idx_request_deferred ON payment_request(status, defer_date) WHERE status = 'DEFERRED';

-- Recurring mandates
CREATE TABLE recurring_mandate (
    mandate_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    consumer_id       VARCHAR(100) NOT NULL,
    debtor_account    VARCHAR(34) NOT NULL,
    merchant_id       VARCHAR(100) NOT NULL,
    max_single_amount NUMERIC(19,4) NOT NULL,
    currency          VARCHAR(3) NOT NULL,
    frequency         VARCHAR(20) NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at        TIMESTAMPTZ,

    CONSTRAINT valid_mandate_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    CONSTRAINT valid_frequency CHECK (frequency IN ('WEEKLY', 'MONTHLY', 'QUARTERLY', 'ANNUAL', 'ON_DEMAND'))
);

CREATE INDEX idx_mandate_consumer ON recurring_mandate(consumer_id, status);
CREATE INDEX idx_mandate_merchant ON recurring_mandate(merchant_id, status);

-- Webhook delivery tracking
CREATE TABLE webhook_delivery (
    delivery_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id        UUID NOT NULL REFERENCES payment_request(request_id),
    merchant_id       VARCHAR(100) NOT NULL,
    webhook_url       VARCHAR(2048) NOT NULL,
    payload           JSONB NOT NULL,
    signature         VARCHAR(128) NOT NULL,              -- HMAC-SHA256 hex
    webhook_id        UUID NOT NULL UNIQUE,               -- X-Webhook-ID header value
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count     INTEGER NOT NULL DEFAULT 0,
    last_attempt_at   TIMESTAMPTZ,
    next_retry_at     TIMESTAMPTZ,
    response_status   INTEGER,                            -- HTTP status from merchant
    error_message     VARCHAR(1000),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT valid_delivery_status CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED'))
);

CREATE INDEX idx_webhook_retry ON webhook_delivery(status, next_retry_at) WHERE status = 'PENDING';

-- Transactional Outbox (for Kafka publishing)
CREATE TABLE outbox (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id      UUID NOT NULL,                      -- payment request ID
    topic             VARCHAR(100) NOT NULL,
    message_key       VARCHAR(255),                       -- Kafka message key
    payload           BYTEA NOT NULL,                     -- serialized Avro
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published         BOOLEAN NOT NULL DEFAULT FALSE,
    published_at      TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox(created_at) WHERE published = FALSE;

-- Merchant configuration (API keys, webhook settings)
CREATE TABLE merchant_config (
    merchant_id       VARCHAR(100) PRIMARY KEY,
    api_key_hash      VARCHAR(64) NOT NULL UNIQUE,        -- SHA-256 of API key
    webhook_url       VARCHAR(2048) NOT NULL,
    hmac_secret       BYTEA NOT NULL,                     -- for webhook signing
    rate_limit        INTEGER NOT NULL DEFAULT 100,       -- req/min
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Idempotency deduplication for Kafka messages (master B.3a)
CREATE TABLE processed_message_ids (
    message_id        VARCHAR(255) PRIMARY KEY,
    processed_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_processed_ttl ON processed_message_ids(processed_at);
-- TTL: delete records older than 24 hours (scheduled cleanup)
```

### 5.2 Database Migrations

Managed by Flyway. Migration files follow the naming convention:

```
V001__create_payment_request.sql
V002__create_recurring_mandate.sql
V003__create_webhook_delivery.sql
V004__create_outbox.sql
V005__create_merchant_config.sql
V006__create_processed_message_ids.sql
```

All migrations follow the expand-contract pattern (master H.1a): each migration is backward-compatible with the previous application version. New columns are added as nullable; NOT NULL constraints are added in a subsequent migration after all instances run the new code.

### 5.4 GDPR Erasure

*Master architecture reference: Decision F.1a (crypto-shredding scope), INTERFACE_CONTRACTS.md Section 4 (GDPR Erasure Protocol).*

A3's `payment_request` table stores IBANs (`creditor_account`, `debtor_account`) and references consumer/merchant identities. These are pseudonymous personal data under GDPR.

**On receiving an `AccountErasedEvent` from A1:**

1. **Active mandates** referencing the erased account: revoke (transition to `MANDATE_REVOKED`) and notify merchant via webhook
2. **Payment request records within AML retention period** (5 years): retain with legal basis (AML 5th Directive, Article 40). IBANs remain for audit traceability.
3. **Payment request records beyond retention period**: anonymize — `creditor_account` → SHA-256 hash, `debtor_account` → SHA-256 hash, `consumer_id` → SHA-256 hash

**Legal basis for retention:** Same as A2 — AML record-keeping obligations (5 years) take precedence over GDPR erasure for payment records (GDPR Article 17(3)(b)).

---

## 6. Payment Request Lifecycle

### 6.1 State Machine

```
                          ┌──────────── CONSUMER_DECLINE ──────────────→ DECLINED (terminal)
                          │
                          ├──────────── CONSUMER_DEFER ────────────────→ DEFERRED
                          │                                                 │
                          │                              SCHEDULER_REACTIVATE (on defer_date)
                          │                                                 │
              MERCHANT    │                                                 v
   CREATED ──────→ PENDING ←──────────────────────────────────────────────┘
                      │   │
                      │   ├──────────── CONSUMER_AMEND ────────────────→ AMENDED
                      │   │                                                 │
                      │   │                      MERCHANT_AMENDMENT_RESPONSE │
                      │   │                                                 │
                      │   │                                                 v
                      │   │                                         (back to PENDING,
                      │   │                                          amount may be updated)
                      │   │
                      │   └──────────── SCHEDULER_EXPIRE ──────────────→ EXPIRED (terminal)
                      │
                      │ CONSUMER_ACCEPT
                      v
                  ACCEPTED
                      │
                      │ SYSTEM_VOP_PASSED (VoP check → MATCH or CLOSE_MATCH confirmed)
                      v
              PAYMENT_INITIATED
                      │
                      │ (Kafka publish, Phase 1 → Phase 2 handoff)
                      │
              ┌───────┬────────┬────────────────┐
              │       │        │                │
              v       v        v                v
          SETTLED  REJECTED  PAYMENT_HELD  RETURN_IN_PROGRESS
          (terminal)(terminal) (non-terminal)   │
                                                v
                                          PAYMENT_RETURNED
                                           (terminal)

          All received via payment.status-updates from A2.
```

### 6.2 Transition Table

| From State | Trigger | To State | Actor |
|-----------|---------|----------|-------|
| (new) | Merchant creates request | PENDING | Merchant API |
| PENDING | CONSUMER_ACCEPT | ACCEPTED | Consumer API |
| PENDING | CONSUMER_DECLINE | DECLINED | Consumer API |
| PENDING | CONSUMER_DEFER | DEFERRED | Consumer API |
| PENDING | CONSUMER_AMEND | AMENDED | Consumer API |
| PENDING | SCHEDULER_EXPIRE | EXPIRED | Scheduler |
| DEFERRED | SCHEDULER_REACTIVATE | PENDING | Scheduler |
| AMENDED | MERCHANT_AMENDMENT_RESPONSE | PENDING | Merchant API |
| ACCEPTED | SYSTEM_VOP_PASSED | PAYMENT_INITIATED | Service internal |
| PAYMENT_INITIATED | KAFKA_SETTLED | SETTLED | Kafka consumer |
| PAYMENT_INITIATED | KAFKA_REJECTED | REJECTED | Kafka consumer |
| PAYMENT_INITIATED | KAFKA_HELD | PAYMENT_HELD | Kafka consumer |
| SETTLED | KAFKA_RETURN_INITIATED | RETURN_IN_PROGRESS | Kafka consumer |
| RETURN_IN_PROGRESS | KAFKA_RETURNED | PAYMENT_RETURNED | Kafka consumer |

**All other (state, trigger) combinations are illegal and rejected by the transition table.**

### 6.3 Enforcement Layers

1. **Application layer:** `StateMachine.isAllowed(currentStatus, trigger)` checks the static `EnumMap<Status, Set<Trigger>>`. Returns false for illegal transitions. The `RequestLifecycleManager` rejects the operation before touching the database.

2. **Database layer:** Compare-and-swap SQL. `UPDATE ... WHERE id = ? AND status = ?` ensures atomicity against concurrent transitions. Affected rows = 0 means someone else got there first.

3. **Database constraint:** `CHECK (status IN (...))` prevents invalid status values from ever being stored, regardless of application bugs.

### 6.4 Accept Flow (Detailed)

The accept flow is the most complex transition — it spans multiple steps:

```
Consumer calls PUT /api/v1/consumer/requests/{id}/accept {paymentMethod: INSTANT}
    │
    v
1. Authenticate (OAuth2 JWT) → extract consumerId
2. Load request → verify consumerId matches → verify status = PENDING
3. Call A6 (VoP): verify creditorName matches creditorIban
    │
    ├─ MATCH → proceed
    ├─ CLOSE_MATCH → return warning to consumer, require confirmation
    │                 (second accept call with closeMatchConfirmed: true)
    ├─ NO_MATCH → reject accept, return error, request stays PENDING
    ├─ TIMEOUT/UNAVAILABLE → proceed with vopResult = TIMEOUT
    │
4. BEGIN TRANSACTION:
    a. CAS update: SET status = PAYMENT_INITIATED, transaction_id = <UUID>,
                       payment_method = INSTANT, vop_result = MATCH
                   WHERE id = ? AND status = PENDING
       → if 0 rows: ROLLBACK, return 409 Conflict
    b. INSERT outbox record (topic = payment.instant-instructions, payload = PaymentInstruction Avro)
    c. INSERT webhook_delivery record (status = PENDING, event = PAYMENT_INITIATED)
   COMMIT
    │
5. In-process outbox relay: immediately publish to Kafka
6. Return 200 {requestId, status: PAYMENT_INITIATED, transactionId, vopResult}
```

---

## 7. Recurring Mandates

### 7.1 Mandate Lifecycle

```
Consumer creates → ACTIVE ←──── SUSPENDED (consumer suspends)
                     │               │
                     │          (consumer resumes)
                     │
                     └──── REVOKED (terminal, consumer revokes)
```

### 7.2 Auto-Acceptance Logic

When a merchant submits a request with `mandateId` and `autoAccept: true`:

```
1. Load mandate with SELECT FOR UPDATE (pessimistic lock)
2. Verify: mandate.status = ACTIVE
3. Verify: mandate.merchantId = request.merchantId
4. Verify: request.amount <= mandate.maxSingleAmount
5. Verify: request.currency = mandate.currency
6. Check VoP cache (or call A6 if cache miss or expired)
    - CLOSE_MATCH on mandate → do NOT auto-accept, route to PENDING
7. If all checks pass:
    - Create request with status = PAYMENT_INITIATED (skip PENDING)
    - Set mandate_id on request
    - Write outbox record
    - Write webhook delivery record (informational notification to consumer)
8. If any check fails:
    - Create request with status = PENDING (consumer must manually approve)
    - Webhook to merchant indicating auto-accept failed + reason
```

The `SELECT FOR UPDATE` on the mandate prevents a race between auto-acceptance and concurrent mandate revocation. If the consumer revokes the mandate while a merchant request is being processed, one of them blocks until the other commits. The revocation sets status = REVOKED; the auto-acceptance check then sees REVOKED and fails — routing the request to PENDING.

### 7.3 VoP Cache for Mandates

Per RTP-RES03, VoP results are cached for mandate auto-acceptance:

| Cache Key | TTL | Invalidation |
|-----------|-----|-------------|
| `(creditorIban, creditorName)` | 90 days | Mandate modification, A6 name-change notification, manual flush |

Implemented with a Caffeine cache local to A3. Cache entries store the full `VopResult` including the timestamp of the check.

---

## 8. Webhook Delivery System

### 8.1 Delivery Flow

```
Status change occurs (any transition)
    │
    v
WebhookService.createDelivery(request, newStatus)
    │
    v
1. Build payload JSON (requestId, status, timestamp, amount, currency)
    - Debtor account: last 4 digits only (RTP-S08)
2. Compute HMAC-SHA256 signature using merchant's hmacSecret
3. Generate unique webhookId (UUID)
4. INSERT webhook_delivery (status = PENDING, next_retry_at = NOW())
    │
    v
WebhookDispatcher (scheduled, runs every 5 seconds)
    │
    v
1. SELECT deliveries WHERE status = PENDING AND next_retry_at <= NOW()
   ORDER BY created_at LIMIT 50
2. For each delivery:
    a. Check circuit breaker for merchant
        - If open: skip (will retry after cooldown)
    b. Send HTTP POST to webhook_url
        - Headers: Content-Type: application/json
                   X-Webhook-Signature: <HMAC>
                   X-Webhook-ID: <webhookId>
        - Body: payload JSON
        - Timeout: 5 seconds
    c. On 2xx response:
        - UPDATE status = DELIVERED, response_status = <http status>
    d. On non-2xx or timeout:
        - INCREMENT attempt_count
        - If attempt_count >= 5:
            UPDATE status = FAILED
            Raise alert (Prometheus counter + log)
        - Else:
            SET next_retry_at = NOW() + backoff(attempt_count)
            backoff: 1s, 2s, 4s, 8s, 16s
```

### 8.1.1 SSRF Protection (FS5.1)

Webhook URLs are validated at **both** registration time (when a merchant provides a URL via the onboarding API) **and** dispatch time (before each outbound HTTP call). This dual check prevents time-of-check/time-of-use gaps where a previously safe URL resolves to an internal address at dispatch time.

**Blocked destinations:**

- RFC 1918 private ranges: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`
- Loopback: `127.0.0.0/8`
- Link-local: `169.254.0.0/16`
- IPv6 equivalents: `::1`, `fc00::/7` (unique local), `fe80::/10` (link-local)
- Internal DNS suffixes: `.internal`, `.svc.cluster.local`, `.local`

**DNS rebinding protection:** At dispatch time, the webhook URL hostname is resolved to an IP address **before** the HTTP connection is opened. The resolved IP is validated against the blocked ranges above. The HTTP client then connects to the validated IP directly (not re-resolving the hostname), preventing DNS rebinding attacks where a hostname resolves to an internal IP between validation and connection.

**HTTPS required:** All webhook URLs must use the `https://` scheme (enforced by RTP-S04). HTTP URLs are rejected at registration time.

**OWASP reference:** API7:2023 — Server-Side Request Forgery.

### 8.2 Circuit Breaker Per Merchant

Resilience4j circuit breaker with key = `merchantId`:

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Failure threshold | 5 consecutive failures | Conservative — avoids opening on transient errors |
| Wait duration in open state | 60 seconds | Typical recovery time for a merchant backend restart |
| Permitted calls in half-open | 1 | One test call to verify recovery |

When the circuit is open, the webhook dispatcher skips that merchant's deliveries (they remain PENDING with their current `next_retry_at`). When the circuit closes, normal retry resumes.

Circuit breaker state is in-memory. On A3 restart, all circuits start closed — the worst case is a few extra retries before the circuit opens again for a persistently failing merchant.

### 8.3 Merchant Polling Fallback

```
GET /api/v1/merchant/requests/{id}/events
```

Returns the status change history for a request (derived from the `webhook_delivery` table). This gives merchants a recovery path when webhooks fail.

---

## 9. Bulk Invoice Submission

### 9.1 Endpoint

```
POST /api/v1/merchant/batches
Content-Type: application/x-ndjson

{"creditorAccount":"NO93...","debtorAccount":"NO12...","amount":1250.00,"currency":"NOK","dueDate":"2026-05-01","reference":"INV-001","description":"April electricity"}
{"creditorAccount":"NO93...","debtorAccount":"NO34...","amount":890.50,"currency":"NOK","dueDate":"2026-05-01","reference":"INV-002","description":"April electricity"}
...
```

### 9.2 Processing

```
1. Authenticate merchant (API key)
2. Parse NDJSON body line by line (streaming — no full-body buffering)
3. Validate each line:
    - Schema validation (required fields, types)
    - Business validation (IBAN format, currency, amount > 0, due date in future)
    - Idempotency key check (skip if already exists)
4. Collect valid requests and per-line errors
5. If valid count > 0:
    BEGIN TRANSACTION:
      Batch INSERT valid requests (hibernate.jdbc.batch_size = 50)
      Batch INSERT outbox records
    COMMIT
6. Return synchronous response:
    {
      "batchId": "uuid",
      "total": 5000,
      "valid": 4987,
      "invalid": 13,
      "errors": [
        {"line": 42, "reason": "Invalid IBAN format"},
        {"line": 99, "reason": "Amount must be positive"},
        ...
      ]
    }
7. Outbox relay publishes payment instructions at normal pace
```

**Size limit:** 10,000 lines maximum (configurable). HTTP 413 if exceeded.

**Rate limiting:** Counts as 1 API request. The outbox relay smooths downstream impact.

---

## 10. QR Code Generation

### 10.1 Dynamic QR (Per Request)

```
POST /api/v1/merchant/requests/{id}/qr
→ Returns: { "qrCode": "<base64-encoded PNG>", "format": "EPC069-12" }
```

The QR encodes EPC069-12 formatted text:

```
BCD                        ← Service tag
002                        ← Version
1                          ← Character set (UTF-8)
SCT                        ← Identification code
DNBANOKK                   ← BIC
Strøm AS                   ← Beneficiary name
NO1234567890123            ← IBAN
NOK1250.00                 ← Amount + currency
                           ← Purpose (empty)
RF18539007547034           ← Structured reference
April electricity          ← Unstructured remittance info
```

Generated using ZXing (`com.google.zxing`): `BarcodeFormat.QR_CODE`, 300x300 pixels, PNG output, Base64-encoded in the JSON response.

### 10.2 Static QR (Per Merchant)

```
GET /api/v1/merchant/{merchantId}/qr
→ Returns: QR containing IBAN + beneficiary name only (no amount)
```

Consumer scans, enters amount manually, submits payment.

### 10.3 Security

Dynamic QR codes should have a short TTL. A3 does not enforce TTL on the QR itself (it's just an image), but the underlying payment request expires on its `due_date` (RTP-F10). A scanned QR for an expired request results in an error when the consumer tries to accept.

---

## 11. VoP Integration

### 11.1 Call Flow

```
Consumer accepts request
    │
    v
VopClient.checkVop(creditorName, creditorIban)
    │
    ├── Circuit breaker CLOSED:
    │       Call A6: POST /api/v1/vop/verify  (mTLS)
    │       Body: {beneficiary_iban, stated_name, request_id}
    │       Timeout: 500ms
    │       │
    │       ├── 200 + MATCH       → proceed, vopResult = MATCH
    │       ├── 200 + CLOSE_MATCH → return to consumer for confirmation
    │       ├── 200 + NO_MATCH    → reject accept, request stays PENDING
    │       ├── Timeout           → proceed, vopResult = TIMEOUT
    │       └── 5xx / error       → proceed, vopResult = UNAVAILABLE
    │
    └── Circuit breaker OPEN:
            → fallback: vopResult = UNAVAILABLE, proceed
```

### 11.2 CLOSE_MATCH Handling

For regular (non-mandate) payments: A3 returns the CLOSE_MATCH result to the consumer API. The consumer-facing application shows a warning. The consumer must explicitly confirm (second accept call with `closeMatchConfirmed: true`). A3 includes `vopResult = CLOSE_MATCH` in the PaymentInstruction.

For mandate auto-acceptance: CLOSE_MATCH does **not** trigger auto-accept. The request is created as PENDING, requiring manual consumer approval. Rationale: a name mismatch on a recurring mandate may indicate a beneficiary account change, which could be an account takeover.

### 11.3 VoP Cache for Mandates

See Section 7.3. The Caffeine cache stores VoP results keyed by `(creditorIban, creditorName)` with 90-day TTL.

---

## 12. Consistency and Crash Recovery

### 12.1 Transactional Outbox

Every state transition that requires a Kafka message uses the outbox:

```
BEGIN TRANSACTION:
  UPDATE payment_request SET status = :newStatus WHERE id = :id AND status = :expectedStatus
  INSERT INTO outbox (aggregate_id, topic, message_key, payload)
      VALUES (:requestId, :topic, :debtorIban, :avroBytes)
  INSERT INTO webhook_delivery (...) -- if status change triggers webhook
COMMIT
→ In-process relay: immediately publish outbox records to Kafka
→ Fallback relay (500ms poll): pick up any unpublished records
```

This guarantees: if the status changed, the Kafka message will be published (eventually). If the process crashes after COMMIT but before Kafka publish, the fallback relay catches it on restart or from another instance.

### 12.2 Startup Recovery (Master B.1b)

On startup, before the readiness probe passes:

1. **Database scan for stale states:**
   - Requests in PAYMENT_INITIATED for > 60 seconds without a corresponding SETTLED/REJECTED/PAYMENT_HELD → log warning, mark for investigation
   - Requests in PENDING past due_date → expire (same logic as the scheduler)
   - Requests in DEFERRED past defer_date → reactivate
   - Outbox records with `published = false` and `created_at < NOW() - 5s` → republish

2. **Kafka consumer resume:** Start consuming `payment.status-updates` from last committed offset. Idempotent processing (check `processed_message_ids` table) ensures no double-processing even if the database scan already handled some messages.

### 12.3 Idempotency

| Operation | Idempotency Mechanism | Key |
|-----------|----------------------|-----|
| Request creation (merchant) | Unique `idempotency_key` column | Merchant-provided |
| Payment instruction (Kafka) | `transactionId` in message, deduplicated by A2 | Generated by A3 at accept |
| Status update processing (Kafka) | `processed_message_ids` table + CAS SQL | `messageId` from Kafka header |
| Consumer accept/decline/defer | CAS SQL (status precondition) | Natural idempotency via state |
| Webhook delivery | `webhook_id` (unique per attempt), merchant deduplicates by `X-Webhook-ID` | Generated by A3 per delivery |

### 12.4 Investigation Queue Support

A3 exposes an admin endpoint for the master investigation queue architecture (B.4a):

```
GET /api/v1/admin/stuck-payments
```

Returns requests in intermediate states beyond expected thresholds:
- PAYMENT_INITIATED for > 30 seconds (instant) or > 2x clearing window (batch)
- Outbox records unpublished for > 60 seconds

A2's investigation API aggregates this information via REST call to A3.

---

## 13. Scheduling

### 13.1 Scheduled Jobs

| Job | Interval | SQL | Multi-Instance Safety |
|-----|----------|-----|----------------------|
| Request expiry | 60s | `UPDATE payment_request SET status = 'EXPIRED' WHERE status = 'PENDING' AND due_date < CURRENT_DATE RETURNING id` | CAS — only one instance succeeds per row |
| Deferred reactivation | 60s | `UPDATE payment_request SET status = 'PENDING' WHERE status = 'DEFERRED' AND defer_date <= CURRENT_DATE RETURNING id` | CAS — same |
| Outbox relay fallback | 500ms | `SELECT FROM outbox WHERE published = false AND created_at < NOW() - '1 second' LIMIT 100` | Publish is idempotent (Kafka deduplication) |
| Webhook retry | 5s | `SELECT FROM webhook_delivery WHERE status = 'PENDING' AND next_retry_at <= NOW() LIMIT 50` | Delivery attempt is idempotent (X-Webhook-ID) |
| Processed ID cleanup | 1 hour | `DELETE FROM processed_message_ids WHERE processed_at < NOW() - '24 hours'` | Safe to run concurrently |
| Outbox cleanup | 1 hour | `DELETE FROM outbox WHERE published = true AND published_at < NOW() - '24 hours'` | Safe to run concurrently |

### 13.2 Webhook Creation After Expiry/Reactivation

When the expiry scheduler transitions requests to EXPIRED, it also creates webhook delivery records for each expired request (notifying the merchant). The `RETURNING id` clause provides the request IDs for webhook creation. Similarly, deferred reactivation does not trigger a webhook (the consumer sees it in their pending list; the merchant already received the DEFERRED webhook earlier).

---

## 14. Cross-Cutting Concerns

### 14.1 Platform Starter Integration

The Platform Starter (`payment-security-starter`) auto-configures the following for A3:

| Capability | How A3 Uses It |
|-----------|----------------|
| Security defaults | A3 **overrides** with two custom `SecurityFilterChain` beans. Starter's default yields via `@ConditionalOnMissingBean`. |
| Security headers | Auto-configured. A3 inherits HSTS, X-Content-Type-Options, CSP, etc. |
| `@Audited` annotation | Applied to state-changing service methods. Audit events published to `audit.events`. |
| Audit hash chain | Auto-configured. Each audit event includes SHA-256 hash of the previous event. |
| RFC 9457 error handling | Auto-configured global exception handler. A3 throws `DomainException` subclasses; Starter maps them to Problem Details. |
| Kafka error handler | Auto-configured `PaymentErrorHandler`. A3 provides exception classifier for its domain exceptions. |
| Micrometer tracing | Auto-configured. Trace context propagated through Kafka headers. Correlation ID in all logs. |
| Prometheus metrics | Auto-configured. Standard metrics: `http.server.requests`, `kafka.consumer.lag`, `db.pool.active`. |
| Health checks | Auto-configured. PostgreSQL + Kafka broker + Kafka consumer lag. |
| Graceful shutdown | Auto-configured. On SIGTERM: stop accepting requests, drain in-flight, commit Kafka offsets, close connections. |
| `KeyProvider` | A3 uses it for: Kafka HMAC signing key, webhook HMAC secrets (if centralized). |

### 14.2 A3-Specific Domain Exceptions

Added to or extending `payment-domain-model`:

```java
public class RequestExpiredException extends DomainException { }       // 410 Gone
public class RequestAlreadyProcessedException extends DomainException { } // 409 Conflict
public class MandateRevokedException extends DomainException { }       // 422
public class MandateAmountExceededException extends DomainException { } // 422
public class VoPNoMatchException extends DomainException { }           // 422 (from shared model)
public class InvalidIdempotencyKeyException extends DomainException { } // 409 Conflict
```

### 14.3 A3-Specific Metrics

In addition to the Starter's standard metrics:

| Metric | Type | Tags |
|--------|------|------|
| `rtp.requests.created` | Counter | `merchant_id`, `initiation_type` (api, mandate, qr) |
| `rtp.requests.transitions` | Counter | `from_status`, `to_status` |
| `rtp.webhook.deliveries` | Counter | `outcome` (delivered, failed, circuit_open) |
| `rtp.webhook.delivery.latency` | Histogram | `merchant_id` |
| `rtp.vop.calls` | Counter | `result` (match, close_match, no_match, timeout, unavailable) |
| `rtp.vop.latency` | Histogram | |
| `rtp.bulk.submissions` | Counter | `merchant_id` |
| `rtp.bulk.requests_per_batch` | Histogram | |

---

## 15. Performance

### 15.1 Latency Targets

| Operation | Target (p95) | Bottleneck | Strategy |
|-----------|-------------|-----------|----------|
| Request creation | < 200ms (RTP-NF01) | DB insert | Single insert + idempotency check |
| Accept (instant) | < 800ms (Phase 1 budget) | VoP call (500ms) | Circuit breaker fallback on timeout |
| Accept (standard) | < 800ms | Same | Same |
| QR code generation | < 500ms (RTP-NF03) | ZXing rendering | In-memory, no DB |
| Request search | < 300ms (RTP-NF05) | DB query | Indexed by (merchant_id, status) and (consumer_id, status) |
| Webhook first delivery | < 5s (RTP-NF02) | Webhook dispatcher poll interval (5s) | Immediate attempt possible via in-process dispatch |
| Bulk submission (10K lines) | < 10s | DB batch insert | `hibernate.jdbc.batch_size = 50`, streaming parse |

### 15.2 Connection Pool

| Pool | Size | Rationale |
|------|------|-----------|
| HikariCP main | 20 connections | Serves API requests, Kafka consumer, schedulers. Sized for 100 concurrent API requests (RTP-NF04) with margin. |

The outbox relay and webhook dispatcher share the main pool. Under normal load, they consume < 5 connections. The scheduler queries are fast (indexed, CAS).

### 15.3 Rate Limiting

100 requests/min per merchant API key (RTP-NF06). Implemented with a Caffeine-backed rate limiter in the `ApiKeyAuthFilter`:

- Key: `merchantId`
- Algorithm: sliding window counter (1-minute window)
- Exceeded: HTTP 429 with `Retry-After` header

**Consumer API Rate Limiting:** In addition to the rate limits inherited from A4's token issuance, A3 applies its own per-consumer rate limit of 200 requests/minute (configurable), keyed by the `consumer_id` extracted from the OAuth2 JWT. This provides defense-in-depth — if A4 is bypassed or a token is stolen, A3 independently limits the blast radius.

---

## 16. Testing Architecture

### 16.1 Testing Pyramid

| Level | Focus for A3 | Tools | Speed |
|-------|-------------|-------|-------|
| **Unit** | State machine transitions (exhaustive), mandate auto-acceptance logic, EPC QR formatting, HMAC signature computation, expiry/reactivation date logic | JUnit 5, Mockito | < 1s/class |
| **Service integration** | Accept flow (DB → outbox → Kafka), status update consumption (Kafka → DB → webhook record), concurrency (parallel accept), bulk submission, outbox relay, expiry scheduler | Testcontainers (Kafka, PostgreSQL), Spring Boot Test | 10-30s/class |
| **Contract** | A3↔A2 Kafka message contracts (PaymentInstruction, PaymentStatusUpdate), OpenAPI spec validation | Pact (message-based + REST), springdoc-openapi | 5-10s/class |
| **End-to-end** | Invoice payment flow (UC-01), mandate auto-accept (UC-06), QR payment | docker-compose, master `run-demo.sh` | 2-5 min |

### 16.2 Integration Test Infrastructure

```java
@SpringBootTest
@Testcontainers
class AcceptFlowIntegrationTest {

    @Container static KafkaContainer kafka = new KafkaContainer(...);
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(...);

    @MockBean VopClient vopClient;  // or WireMock for HTTP-level testing

    @Test
    void consumerAcceptsInstantPayment_publishesPaymentInstruction() {
        // Given: a PENDING request exists
        // When: consumer calls PUT /accept with paymentMethod: INSTANT
        // Then: request status = PAYMENT_INITIATED
        //   AND outbox record exists with topic = payment.instant-instructions
        //   AND Kafka message published with correct Avro schema
        //   AND webhook delivery record created
    }
}
```

### 16.3 WireMock Targets

| Mock Target | Tests |
|-------------|-------|
| A6 (VoP) | MATCH → proceed. CLOSE_MATCH → return warning. NO_MATCH → reject. Timeout → fallback. 500 → circuit breaker opens. |
| Merchant webhook endpoint | 200 → delivered. 500 → retry. Persistent failure → circuit opens → FAILED after 5 retries. Slow response → timeout. |

### 16.4 Concurrency Tests

- Two threads call `PUT /accept` on the same request simultaneously → one succeeds (200), one fails (409).
- One thread revokes a mandate while another submits a mandate-based request → either auto-accept succeeds (mandate was still active) or request goes to PENDING (mandate was revoked). Never both.

---

## 17. Configuration

### 17.1 Configuration Properties

```yaml
# Infrastructure (env vars in production)
spring:
  datasource:
    url: ${DATABASE_URL:jdbc:postgresql://localhost:5432/rtp}
    username: ${DATABASE_USER:rtp}
    password: ${DATABASE_PASSWORD:rtp}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}

# Operational (hot-reloadable via @RefreshScope)
payment:
  rtp:
    # VoP integration
    vop:
      base-url: ${VOP_BASE_URL:https://localhost:8086/api/v1/vop}
      timeout: 500ms
      cache-ttl-mandate: 90d
      circuit-breaker:
        failure-threshold: 5
        wait-duration: 30s

    # Webhook delivery
    webhook:
      max-retries: 5
      backoff-initial: 1s
      backoff-multiplier: 2
      dispatch-interval: 5s
      request-timeout: 5s
      circuit-breaker:
        failure-threshold: 5
        wait-duration: 60s

    # Scheduling
    scheduler:
      expiry-interval: 60s
      reactivation-interval: 60s
      outbox-relay-interval: 500ms

    # Bulk
    bulk:
      max-batch-size: 10000

    # Rate limiting
    rate-limit:
      default-requests-per-minute: 100

    # Kafka topics
    topics:
      payment-instructions: payment.instructions
      instant-payment-instructions: payment.instant-instructions
      status-updates: payment.status-updates
```

### 17.2 Configuration Validation

```java
@ConfigurationProperties(prefix = "payment.rtp")
@Validated
public record RtpConfig(
    @Valid VopConfig vop,
    @Valid WebhookConfig webhook,
    @Valid SchedulerConfig scheduler,
    @Valid BulkConfig bulk,
    @Valid RateLimitConfig rateLimit,
    @Valid TopicsConfig topics
) {
    public record VopConfig(
        @NotNull URI baseUrl,
        @NotNull Duration timeout,
        @NotNull Duration cacheTtlMandate
    ) {}

    public record WebhookConfig(
        @Min(1) @Max(10) int maxRetries,
        @NotNull Duration backoffInitial,
        @Min(1) @Max(10) int backoffMultiplier,
        @NotNull Duration dispatchInterval,
        @NotNull Duration requestTimeout
    ) {}

    public record BulkConfig(
        @Min(100) @Max(100_000) int maxBatchSize
    ) {}
    // ... etc
}
```

Spring Boot validates at startup. Missing or out-of-range values prevent the service from starting (fail-fast).

### 17.3 Shared Parameters

Parameters that must be consistent with other services:

| Parameter | A3 Value | Must Match | Document |
|-----------|---------|------------|----------|
| VoP timeout | 500ms | A6 processing time (< 500ms) | shared-config.md |
| Kafka topic names | `payment.instructions`, etc. | A2, A4 (producers/consumers of same topics) | shared-config.md |
| Avro schema versions | Latest compatible | All services using `payment-avro-schemas` | Schema Registry |
| `transactionId` format | UUID v4 | A2, A5, A1 (deduplication) | payment-domain-model |

---

## 18. Architectural Decision Log

Complete analysis with options and tradeoffs is in `ARCHITECTURE_DECISIONS.md`. This table summarizes the final decisions.

| # | Decision | Choice | Master Reference |
|---|----------|--------|-----------------|
| 1 | State machine enforcement | Enum transition table + CAS SQL + CHECK constraint | B.1a (Phase 1 ownership) |
| 2 | Dual-write strategy | Transactional Outbox, in-process relay + scheduled fallback | B.2a/B.2b (inherited) |
| 3 | Concurrency control | CAS for transitions, pessimistic lock for mandates, idempotency keys for creation | B.3a (idempotency strategy) |
| 4 | Webhook reliability | Durable table + retry + circuit breaker per merchant + polling fallback | A3-specific |
| 5 | Tenant data isolation | Repository filtering + @PreAuthorize. RLS as production target | A.1a (trust zones) |
| 6 | Dual API surface | Separate URL prefixes + separate SecurityFilterChains + contract-first OpenAPI | D.2a (API versioning) |
| 7 | Bulk operations | Synchronous with 10K limit + streaming NDJSON + outbox backpressure | A3-specific |
| 8 | Scheduling | Spring @Scheduled (60s) + CAS SQL. No leader election | B.1b (recovery scan) |
| 9 | VoP integration | Synchronous + timeout + circuit breaker + CLOSE_MATCH pass-through | C.2a (time budget) |
| 10 | Testing strategy | Master pyramid: unit, integration (Testcontainers), contract (Pact), e2e. WireMock for VoP + webhooks | G.1a (testing pyramid) |
