# Project #2: Open Banking PSD2 API Gateway — Requirements

*System A role: A4 — Open Banking APIs with OAuth2 consent, SCA, VRP*
*Target companies: DNB (developer.dnb.no), SpareBank 1 (developer.sparebank1.no), Tietoevry Banking (BaaS), Mastercard Oslo*
*Regulatory basis: PSD2 (Directive 2015/2366), RTS on SCA, PSD3/PSR proposal*
*API standard: BerlinGroup NextGenPSD2 (primary), with UK Open Banking awareness*

---

## 1. Overview

A PSD2-compliant Open Banking API Gateway that enables Third-Party Providers (TPPs) to access account information (AISP) and initiate payments (PISP) on behalf of account holders, with proper consent management and Strong Customer Authentication.

This is the **security-heaviest** sub-project in the payment platform. The entire value proposition is in the authentication and authorization architecture: OAuth2 consent flows, SCA with intelligent exemption engine, token lifecycle management, and optional Variable Recurring Payments (VRP).

### 1.1 Target Company Alignment

| Company | What they build | How our project aligns |
|---------|----------------|----------------------|
| **DNB** | developer.dnb.no — Open Banking APIs for account info and payment initiation. PSD2-compliant. OAuth2-based | Direct mirror. Our BerlinGroup API surface matches their API structure |
| **SpareBank 1** | developer.sparebank1.no — PSD2 APIs based on BerlinGroup standard. OAuth2 consent. Kotlin/Java backend | Direct mirror. Same standard, same tech stack |
| **Tietoevry Banking** | Banking-as-a-Service APIs. PSD2 compliance. Pact contract testing between services | Our Pact testing matches their practice. BaaS API design matches their offering |
| **Mastercard Oslo** | OAuth 1.0a body hash signing (not standard OAuth2). client-encryption-java for JWE payload encryption. "Why Mastercard Doesn't Use OAuth 2.0" blog post | Our JWE encryption mirrors their client-encryption-java. Our OAuth2 is standard PSD2, but understanding Mastercard's alternative approach shows depth |
| **NAV** | mock-oauth2-server on Maven Central. token-support for JWT validation. TokenX for token exchange | We use their mock-oauth2-server for testing. Shows Norwegian ecosystem awareness |

---

## 2. Functional Requirements — Account Information (AISP)

| ID | Requirement | Priority |
|----|------------|----------|
| AISP-F01 | `GET /api/v1/accounts` — list all accounts the TPP has consent to access. Returns: IBAN, currency, account type, account holder name | Must |
| AISP-F02 | `GET /api/v1/accounts/{id}/balances` — account balances: available (= booked minus reserved), booked. Multi-currency if applicable | Must |
| AISP-F03 | `GET /api/v1/accounts/{id}/transactions` — transaction history, paginated. Filters: dateFrom, dateTo, bookingStatus (booked/pending/both) | Must |
| AISP-F04 | Account data sourced from A1 (Account Ledger) via internal REST call or Kafka | Must |
| AISP-F05 | All AISP calls require valid OAuth2 access token with `accounts:read` or `transactions:read` scope | Must |
| AISP-F06 | Data returned only for accounts covered by the active consent (scope enforcement) | Must |
| AISP-F07 | BerlinGroup NextGenPSD2-style response format: `_links` for navigation, `account` structure per XS2A spec | Should |

---

## 3. Functional Requirements — Payment Initiation (PISP)

| ID | Requirement | Priority |
|----|------------|----------|
| PISP-F01 | `POST /api/v1/payments` — initiate a single credit transfer. Body: debtor IBAN, creditor IBAN, creditor name, amount, currency, reference | Must |
| PISP-F02 | Payment can specify `service_level: INSTANT` (triggers SCT Inst in A2) or `STANDARD` (batch clearing) | Must |
| PISP-F03 | Before execution: trigger VoP check via A6. Display result to TPP | Must |
| PISP-F04 | SCA required per payment (PSD2 Art. 97) unless exemption applies | Must |
| PISP-F05 | Dynamic linking: SCA challenge must display the specific payment amount and creditor name (PSD2 RTS Art. 5) | Must |
| PISP-F06 | Payment status queryable: `GET /api/v1/payments/{id}` returns status enum: REQUIRES_SCA, REQUIRES_CONFIRMATION (VoP mismatch — payer must confirm), PENDING, PROCESSING, HELD (compliance review), SETTLED, REJECTED, CANCELLED (SCA timeout or consent revocation). Status includes `reason` field for REJECTED/HELD/CANCELLED | Must |
| PISP-F07 | Published to Kafka `payment.instructions` (standard) or `payment.instant-instructions` (instant) | Must |
| PISP-F08 | Requires OAuth2 token with `payments:initiate` scope + active consent covering debtor account | Must |

---

## 4. Functional Requirements — OAuth2 Consent & Authentication

### 4.1 Consent Lifecycle

| ID | Requirement | Priority |
|----|------------|----------|
| AUTH-F01 | `GET /api/v1/authorize` — OAuth2 authorization endpoint. Redirects account holder to consent page | Must |
| AUTH-F02 | Consent page displays: TPP name, requested scopes, accounts affected, duration (max 90 days per PSD2) | Must |
| AUTH-F03 | Account holder can: **Grant** (full or partial scopes), **Deny** (redirect with `error=access_denied`) | Must |
| AUTH-F04 | On grant: authorization code issued, redirected to TPP's `redirect_uri` with `code` and `state` | Must |
| AUTH-F05 | `POST /api/v1/token` — exchange authorization code for access token + refresh token | Must |
| AUTH-F06 | Access token: JWT signed RS256, max 15-minute expiry. Contains: sub (account holder), client_id (TPP), scope, consent_id, exp, iat | Must |
| AUTH-F07 | Refresh token: opaque, single-use (rotating), stored hashed in database. Expiry = consent expiry | Must |
| AUTH-F08 | Refresh token reuse detection: if an old refresh token is used, revoke the entire token family (detect theft) | Must |
| AUTH-F09 | Consent stored with: TPP identity, granted scopes, account holder, accounts, expiry (max 90 days), creation timestamp | Must |
| AUTH-F10 | Consent expiry: max 90 days from creation (PSD2). TPP must re-authenticate after expiry | Must |
| AUTH-F11 | Consent revocation: account holder can revoke via `DELETE /api/v1/consents/{id}`. All tokens invalidated | Must |
| AUTH-F12 | Consent dashboard: `GET /api/v1/consents` — list active consents for the authenticated account holder | Should |
| AUTH-F16 | **Payment status webhooks to TPPs**: POST `/api/v1/webhook-subscriptions` — TPP registers webhook URL. On payment status change, A4 pushes HMAC-signed notification. Avoids polling `GET /payments/{id}` | Should |
| AUTH-F13 | PKCE (Proof Key for Code Exchange) required on all authorization requests | Must |
| AUTH-F14 | JWKS endpoint: `GET /.well-known/jwks.json` — public keys for TPPs to verify JWT signatures | Must |
| AUTH-F15 | OpenID Discovery: `GET /.well-known/openid-configuration` — metadata endpoint | Should |

### 4.2 Strong Customer Authentication (SCA)

| ID | Requirement | Priority |
|----|------------|----------|
| SCA-F01 | SCA requires two factors from different categories: knowledge (PIN/password), possession (TOTP/SMS code), inherence (biometric) | Must |
| SCA-F02 | Our implementation: knowledge (PIN simulation) + possession (TOTP code via authenticator app or simulated SMS) | Must |
| SCA-F03 | SCA required: at consent creation, at each payment initiation (unless exempt), at every 90-day consent refresh | Must |
| SCA-F04 | Dynamic linking for payments: SCA challenge must include the payment amount and creditor name. The user sees "Confirm payment of NOK 5,000 to Ola Nordmann. Enter your TOTP code." | Must |
| SCA-F05 | SCA failure handling: max 3 attempts. After 3 failures, lock SCA for 5 minutes. Redirect with `error=sca_failed` | Must |

### 4.3 SCA Exemption Engine

| ID | Requirement | Priority |
|----|------------|----------|
| EXE-F01 | **Low-value exemption**: payment < EUR 30. Cumulative limit: max 5 consecutive exempt payments OR EUR 100 cumulative since last SCA. Then force SCA | Must |
| EXE-F02 | **Transaction Risk Analysis (TRA)**: if our fraud rate is below EBA thresholds, exempt payments up to EUR 500 (lowest bracket). Requires real-time fraud rate calculation | Should |
| EXE-F03 | **Trusted beneficiary**: account holder has whitelisted this payee via `POST /api/v1/trusted-beneficiaries`. Payments to trusted beneficiaries skip SCA | Should |
| EXE-F04 | **Recurring**: same amount + same payee as a previous SCA-authenticated payment. SCA only on first occurrence | Should |
| EXE-F05 | **Merchant-initiated transaction (MIT)**: pre-agreed charges (subscription renewal, hotel no-show). Outside SCA scope | Should |
| EXE-F06 | Exemption decision logged in audit trail with exemption type and rationale | Must |
| EXE-F07 | Cumulative counters per account: track exempt payment count and amount since last SCA | Must |
| EXE-F08 | If exemption conditions not met: fall back to SCA | Must |

### 4.4 Variable Recurring Payments (VRP) — Differentiating Feature

| ID | Requirement | Priority |
|----|------------|----------|
| VRP-F01 | `POST /api/v1/vrp/consents` — TPP creates VRP consent request with parameter bounds | Should |
| VRP-F02 | VRP consent parameters: max amount per payment, max cumulative per period, max frequency per period, specific payee, valid date range | Should |
| VRP-F03 | VRP consent creation requires SCA (same as regular consent) | Should |
| VRP-F04 | `POST /api/v1/vrp/payments` — TPP initiates payment within VRP consent bounds. **No per-payment SCA** | Should |
| VRP-F05 | Bank-side enforcement: every VRP payment validated against consent parameters. Reject if any parameter exceeded | Should |
| VRP-F06 | VRP consent immutable after creation — to change bounds, create new consent | Should |
| VRP-F07 | VRP consent revocable by account holder at any time | Should |
| VRP-F08 | Cumulative tracking: total amount and count per period updated after each VRP payment | Should |

---

## 5. Functional Requirements — TPP Management

| ID | Requirement | Priority |
|----|------------|----------|
| TPP-F01 | TPP registration: `POST /admin/tpps` — register client_id, client_secret, redirect URIs, client certificate (mTLS), TPP name | Must |
| TPP-F02 | Rate limiting per TPP: configurable max requests/minute. HTTP 429 with `Retry-After` header | Must |
| TPP-F03 | TPP client certificate validation for mTLS (simulating eIDAS QWAC certificates) | Should |
| TPP-F04 | TPP deactivation: disable a TPP, revoking all active consents | Should |

---

## 6. Non-functional Requirements

| ID | Requirement | Target |
|----|------------|--------|
| PSD2-NF01 | API response latency (AISP queries) | < 300ms (p95) |
| PSD2-NF02 | Token issuance latency | < 500ms |
| PSD2-NF03 | SCA exemption decision | < 50ms |
| PSD2-NF04 | Consent flow (user-facing) | < 3 seconds |
| PSD2-NF05 | Concurrent API requests | >= 200 |
| PSD2-NF06 | JWT validation (per request) | < 5ms |

### 6.1 Additional Functional Requirements

*Propagated from master system requirements and use cases*

| ID | Requirement | Priority |
|----|------------|----------|
| PSD2-F20 | **Daily transaction limit**: per-account configurable daily payment limit. If cumulative payments initiated today exceed the limit, reject with HTTP 422 `daily_limit_exceeded`. Limit applies across all TPPs. Configurable per account or globally (default: NOK 500,000) | Should |
| PSD2-F21 | **SCA timeout**: if account holder does not complete SCA within **5 minutes** of redirect, the payment is cancelled with reason `SCA_TIMEOUT`. Payment status transitions to CANCELLED. Avoids dangling payment records awaiting SCA indefinitely | Must |

### 6.2 Resilience and Crash Recovery

*Propagated from master system requirements (section 2.7)*

| ID | Requirement | Priority |
|----|------------|----------|
| PSD2-RES01 | **Transactional Outbox Pattern**: when A4 publishes payment instructions to Kafka after SCA completion, use the Transactional Outbox — write the payment status change and the Kafka event in the same PostgreSQL transaction. A relay publishes to Kafka. Prevents "SCA completed but payment instruction lost" on crash | Must |
| PSD2-RES02 | **Dead Letter Queue**: Kafka consumer for `payment.status-updates` must route unprocessable messages to `payment.status-updates.dlq` after 3 retries. Monitor DLQ depth via Prometheus | Must |
| PSD2-RES03 | **Graceful shutdown**: on SIGTERM, allow in-flight SCA flows and token exchanges to complete before shutting down. Payments awaiting SCA will time out naturally via PSD2-F21 | Should |

---

## 7. Technology Stack

| Component | Technology | Company Alignment |
|-----------|-----------|-------------------|
| Framework | **Spring Boot 3.x** | Universal |
| Auth server | **Spring Authorization Server** | For OAuth2/OIDC consent endpoint |
| JWT signing | **RS256** asymmetric. Keys in JWKS format | Standard PSD2 |
| JWT validation | **Spring Security OAuth2 Resource Server** | Standard |
| Token exchange | **On-Behalf-Of (OBO)** flow | NAV's TokenX pattern |
| API spec | **OpenAPI 3.0** via springdoc-openapi | SpareBank1, Mastercard pattern |
| Payload encryption | **JWE** (JSON Web Encryption) via Nimbus JOSE | Mastercard's client-encryption-java pattern |
| Database | **PostgreSQL** with **Flyway** | Universal |
| Testing | **no.nav.security:mock-oauth2-server** + **Pact** | NAV ecosystem + Tietoevry practice |
| TOTP | **GoogleAuth** library or custom TOTP implementation (RFC 6238) | For SCA possession factor simulation |
| Rate limiting | **Bucket4j** | Per-TPP rate limiting |
| Caching | **Caffeine** | Token validation, consent lookup |

---

## 8. Data Model

### Consent
```
consent_id:         UUID (PK)
tpp_client_id:      String
account_holder_id:  String
accounts:           List<String> (IBANs covered by this consent)
granted_scopes:     Set<String>
consent_type:       Enum (STANDARD, VRP)
status:             Enum (ACTIVE, EXPIRED, REVOKED)
created_at:         Instant (UTC)
expires_at:         Instant (UTC, max 90 days from creation)
revoked_at:         Instant (nullable)
sca_method:         String (TOTP, SMS)
sca_completed_at:   Instant
```

### VRP Consent Extension (for consent_type = VRP)
```
vrp_consent_id:             UUID (FK to consent)
max_amount_per_payment:     BigDecimal
max_amount_currency:        String (ISO 4217)
max_cumulative_per_period:  BigDecimal
cumulative_period:          Enum (DAILY, WEEKLY, MONTHLY)
max_frequency_per_period:   Integer
payee_account:              String (IBAN — locked to specific payee)
payee_name:                 String
valid_from:                 LocalDate
valid_until:                LocalDate
current_period_amount:      BigDecimal (running total)
current_period_count:       Integer (running count)
period_reset_at:            Instant (when current period started)
```

### Refresh Token
```
token_id:           UUID (PK)
token_hash:         String (SHA-256 hash of the opaque token)
family_id:          UUID (for reuse detection — all tokens in a family share this)
consent_id:         UUID (FK)
tpp_client_id:      String
account_holder_id:  String
created_at:         Instant
expires_at:         Instant (= consent expiry)
used:               Boolean (set true on first use; if already true → reuse detected → revoke family)
```

### SCA Exemption Counter
```
account_id:                 String (PK)
exempt_count_since_sca:     Integer (reset to 0 after SCA)
exempt_amount_since_sca:    BigDecimal (reset to 0 after SCA)
last_sca_at:                Instant
trusted_beneficiaries:      Set<String> (IBANs whitelisted by account holder)
```

---

## 9. Security Requirements

| ID | Requirement |
|----|------------|
| PSD2-S01 | RS256 JWT signing with key rotation support (publish multiple keys in JWKS, retire old keys) |
| PSD2-S02 | Refresh token stored as SHA-256 hash only — never in plaintext |
| PSD2-S03 | Refresh token family-based revocation on reuse detection |
| PSD2-S04 | PKCE mandatory (code_verifier + code_challenge with S256 method) |
| PSD2-S05 | mTLS for TPP identification (client certificate validation) |
| PSD2-S06 | JWE encryption for sensitive response fields: account numbers, balances (optional per-TPP) |
| PSD2-S07 | All consent grants, revocations, SCA events, and token issuances logged to audit trail |
| PSD2-S08 | SCA dynamic linking: TOTP challenge bound to specific payment amount + creditor |
| PSD2-S09 | Anti-CSRF: state parameter mandatory in authorization requests |
| PSD2-S10 | Redirect URI validation: exact match against registered URIs (no wildcard) |
| PSD2-S11 | Rate limiting per TPP to prevent abuse |
| PSD2-S12 | SCA exemption decisions logged with exemption type, rationale, cumulative counters |

---

## 10. Kafka Integration

| Topic | Direction | Purpose |
|-------|-----------|---------|
| `payment.instructions` | Produce | Standard payment initiation |
| `payment.instant-instructions` | Produce | Instant payment initiation (service_level=INSTANT) |
| `payment.status-updates` | Consume | Receive settlement status for initiated payments |
| `audit.events` | Produce | All consent, auth, and API access events |

---

## 11. Acceptance Criteria

| ID | Criterion |
|----|----------|
| AC-01 | Full OAuth2 consent flow: authorize → SCA → consent → code → token → API call → data returned |
| AC-02 | Expired consent returns 403 with clear error message |
| AC-03 | Refresh token rotation works: new refresh token on each use, old one invalidated |
| AC-04 | Refresh token reuse triggers family revocation — all tokens in the family invalidated |
| AC-05 | Low-value exemption: 5 payments < EUR 30 without SCA, 6th requires SCA |
| AC-06 | Dynamic linking: TOTP challenge displays payment amount and creditor |
| AC-07 | VRP: payment within consent bounds succeeds without SCA |
| AC-08 | VRP: payment exceeding bounds is rejected with specific error |
| AC-09 | JWE encryption: sensitive fields encrypted in response when TPP's public key is registered |
| AC-10 | Pact contract tests pass for AISP and PISP APIs |
| AC-11 | mock-oauth2-server used in integration tests |
| AC-12 | `docker-compose up` starts the gateway with all dependencies |
