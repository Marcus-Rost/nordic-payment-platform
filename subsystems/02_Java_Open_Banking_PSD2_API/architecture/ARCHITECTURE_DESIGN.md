# A4 — PSD2 Open Banking API Gateway: Architecture

*Date: 2026-04-11*
*Version 1.0*
*Sub-system A4 of the Nordic Payment Processing Infrastructure*
*Regulatory basis: PSD2 (Directive 2015/2366), RTS on SCA, PSD3/PSR awareness*
*API standard: BerlinGroup NextGenPSD2*
*Decision record: [ARCHITECTURE_DECISIONS.md](ARCHITECTURE_DECISIONS.md)*

---

## 1. System Context

### 1.1 Role Within the Payment Platform

A4 is the **DMZ-facing security gateway** — the only sub-system that directly exposes APIs to external Third-Party Providers (TPPs). It serves as both the entry point for all third-party access and the gatekeeper protecting all internal services.

**Dual role:**
- **Own security**: OAuth2 consent, SCA, token lifecycle, key management
- **Gatekeeper security**: every request to the payment platform from a TPP passes through A4's defense layers before reaching A1, A2, or A6

### 1.2 Integration Map

```
                         External (DMZ)
                              │
           ┌──────────────────┼──────────────────┐
           │                  │                   │
           │    TPP ──mTLS──► A4 ◄──mTLS── TPP   │
           │                  │                   │
           └──────────────────┼──────────────────┘
                              │
                    Internal (Trusted Zone)
                              │
              ┌───────────────┼───────────────┐
              │               │               │
              ▼               ▼               ▼
    ┌─────────────┐  ┌──────────────┐  ┌───────────┐
    │ A1: Account │  │ A6: VoP      │  │ A2: Clear │
    │ Ledger      │  │ Service      │  │ Engine    │
    │ (REST)      │  │ (REST)       │  │ (Kafka)   │
    └─────────────┘  └──────────────┘  └───────────┘
    Balance queries   Name-IBAN check   Payment instructions
    Transaction data                    Status updates back
```

**Integration summary:**

| Target | Protocol | Direction | Purpose |
|--------|----------|-----------|---------|
| A1 | REST (mTLS) | A4 → A1 | Balance queries, transaction history (AISP) |
| A6 | REST (mTLS) | A4 → A6 | VoP check before payment initiation |
| A2 | Kafka | A4 → A2 | Payment instructions (`payment.instructions`, `payment.instant-instructions`) |
| A2 | Kafka | A2 → A4 | Settlement status updates (`payment.status-updates`) |
| All | Kafka | A4 → | Audit events (`audit.events`) |

---

## 2. Guiding Principles

Three principles drive every architectural decision in A4. They emerged from analysis of A4's unique position as the system's attack surface and were validated across all 14 architectural challenges.

### Principle 1: Security as Architecture

Security is not a feature added to components — it drives the component structure. A4's architecture is organized around defense layers, not just functional decomposition. Every component has an explicit security role. The defense layers serve as a completeness checklist: if a request passes through all layers, security coverage is guaranteed by construction.

### Principle 2: Fortress — Defense in Depth with Independent Layers

Multiple layers of protection, each standing on its own. A failure or bypass of any single layer does not collapse the defense. Each layer is independently testable and independently effective. We assume breach — blast radius is contained by design.

### Principle 3: Crash Resilience by Design

The system may crash at any point. Recovery is principled, not ad-hoc. Money-moving flows use explicit state machines with durable state. Every crash scenario has a defined recovery action. No state is lost that cannot be recovered.

---

## 3. Defense-in-Depth Layer Model

This is the primary structural element of A4's architecture. Every request from a TPP passes through six independent defense layers. *(Decision C-01)*

```
TPP Request
    │
    ▼
┌─────────────────────────────────────────────────────┐
│ L1: NETWORK — mTLS                                  │
│ Only TPPs with valid client certificates connect     │
├─────────────────────────────────────────────────────┤
│ L2: PROTOCOL — OAuth2 / PKCE / JWT                  │
│ Only authorized TPPs with valid tokens call APIs     │
├─────────────────────────────────────────────────────┤
│ L3: BUSINESS — Consent Enforcement                  │
│ Only consented scopes and accounts are accessible    │
├─────────────────────────────────────────────────────┤
│ L4: TRANSACTION — SCA + Exemption Engine            │
│ High-value operations require fresh authentication   │
├─────────────────────────────────────────────────────┤
│         ┌─────────────────────────┐                 │
│         │   Business Logic        │                 │
│         │   (AISP, PISP, VRP)     │                 │
│         └─────────────────────────┘                 │
├─────────────────────────────────────────────────────┤
│ L5: PAYLOAD — JWE Encryption (optional)             │
│ Sensitive fields encrypted even if transport fails   │
├─────────────────────────────────────────────────────┤
│ L6: DETECTION — Audit Trail                         │
│ Every action recorded for investigation              │
└─────────────────────────────────────────────────────┘
    │
    ▼
TPP Response
```

### 3.1 Layer Independence — Failure Analysis

Each layer functions independently. This table demonstrates that bypassing any single layer is caught by the remaining layers:

| Layer bypassed | What the attacker gains | What still blocks them |
|---------------|------------------------|----------------------|
| L1 (mTLS off) | Network access without certificate | L2 requires valid JWT; L3 checks consent in DB |
| L2 (JWT key leaked) | Can forge access tokens | L1 still identifies TPP via certificate; L3 checks consent exists and is ACTIVE in DB; forged consent_id that doesn't exist → denied |
| L3 (consent check bug) | Access beyond granted scope | L4 requires SCA for payments (no money moves without fresh auth); AISP reads possible but no money movement |
| L4 (SCA bypassed) | Payment without authentication | A5 (fraud/sanctions) still screens every payment downstream; audit trail (L6) records the anomaly |
| L5 (JWE disabled) | Payload visible in transit | TLS still encrypts transport; data exposure only if TLS also compromised |
| L6 (audit disabled) | No detection trail | All other layers still prevent the attack; but investigation capability lost |

### 3.2 Re-validation Principle

*(Decision C-04)*

Layers do not blindly trust each other. The principle: **trust cryptographic verification; never trust mutable state from anything other than the database.**

| Layer | Trusts (cryptographic, immutable) | Re-validates from database (mutable) |
|-------|----------------------------------|--------------------------------------|
| L1 | Certificate chain against trust store | — |
| L2 | JWT signature, expiry, issuer | — |
| L3 | consent_id from JWT (signature verified at L2) | Consent status (ACTIVE?), scope coverage, account coverage |
| L4 | — | Exemption counters, SCA session state |

**Key property:** Revoking a consent immediately blocks access. The JWT may be valid for up to 15 minutes, but L3 checks consent status in the database on every request. There is no revocation window.

---

## 4. Component Architecture

Within the defense layer framework, A4 is organized into functional components. Each component maps to one or more defense layers.

```
┌──────────────────────────────────────────────────────────────────────┐
│  A4: PSD2 API Gateway                                                │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐     │
│  │ Spring Authorization Server (customized)              [L2]  │     │
│  │ • /api/v1/authorize (consent page + SCA injection)          │     │
│  │ • /api/v1/token (code exchange, refresh token grant)        │     │
│  │ • /.well-known/jwks.json (multi-key, rotation-aware)        │     │
│  │ • /.well-known/openid-configuration                         │     │
│  └─────────────────────────────────────────────────────────────┘     │
│                                                                      │
│  ┌──────────────────────┐  ┌────────────────────────────────────┐   │
│  │ SCA Engine      [L4] │  │ SCA Exemption Engine          [L4] │   │
│  │ • TOTP (RFC 6238)    │  │ • Low-value (<EUR 30)              │   │
│  │ • Dynamic linking    │  │ • TRA (fraud rate check)           │   │
│  │ • Durable lockout    │  │ • Trusted beneficiary              │   │
│  │ • DB-backed state    │  │ • Recurring (same amt+payee)       │   │
│  │ • 5-min timeout      │  │ • MIT (merchant-initiated)         │   │
│  └──────────────────────┘  │ • Pessimistic counter locking      │   │
│                            └────────────────────────────────────┘   │
│                                                                      │
│  ┌──────────────────────┐  ┌────────────────────────────────────┐   │
│  │ AISP Controller [L3] │  │ PISP Controller              [L3,L4] │ │
│  │ • GET /accounts      │  │ • POST /api/v1/payments             │ │
│  │ • GET /balances      │  │ • GET /api/v1/payments/{id}         │ │
│  │ • GET /transactions  │  │ • PUT /api/v1/payments/{id}/confirm │ │
│  │ • Queries A1 (REST)  │  │ • VoP check via A6 (REST)          │ │
│  └──────────────────────┘  │ • Kafka publish (via outbox)        │ │
│                            └────────────────────────────────────┘   │
│                                                                      │
│  ┌──────────────────────┐  ┌────────────────────────────────────┐   │
│  │ VRP Controller [L3,L4]│ │ Consent Manager              [L3]  │   │
│  │ • POST /vrp/consents │  │ • Create / revoke / list consents  │   │
│  │ • POST /vrp/payments │  │ • 90-day expiry enforcement        │   │
│  │ • Bound enforcement  │  │ • Revocation → token family purge  │   │
│  │ • Counter tracking   │  │ • Scope + account validation       │   │
│  └──────────────────────┘  └────────────────────────────────────┘   │
│                                                                      │
│  ┌──────────────────────┐  ┌────────────────────────────────────┐   │
│  │ Token Lifecycle [L2] │  │ Key Manager                  [L2]  │   │
│  │ • JWT RS256 signing  │  │ • DB-stored signing keys           │   │
│  │ • Refresh rotation   │  │ • 90-day rotation, 30-day overlap  │   │
│  │ • Family revocation  │  │ • JWKS endpoint (multi-key)        │   │
│  │ • Reuse detection    │  │ • Compromise response procedure    │   │
│  └──────────────────────┘  └────────────────────────────────────┘   │
│                                                                      │
│  ┌──────────────────────┐  ┌────────────────────────────────────┐   │
│  │ TPP Manager     [L1] │  │ Rate Limiter               [L1,L2] │  │
│  │ • mTLS certificate   │  │ • Bucket4j per TPP                 │  │
│  │   extraction (DN)    │  │ • 100 req/min configurable         │  │
│  │ • TPP registration   │  │ • HTTP 429 + Retry-After           │  │
│  │ • TPP deactivation   │  │                                    │  │
│  └──────────────────────┘  └────────────────────────────────────┘   │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ Audit Service                                          [L6]  │   │
│  │ • Append-only PostgreSQL audit_events table                  │   │
│  │ • Transactional co-location (same TX as the action)          │   │
│  │ • Outbox relay to audit.events Kafka topic                   │   │
│  │ • Integrates with Platform Starter @Audited annotation       │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ Outbox Relay                                                  │   │
│  │ • Polls outbox table after DB commit                         │   │
│  │ • Publishes payment instructions to Kafka                    │   │
│  │ • Publishes audit events to Kafka                            │   │
│  │ • Idempotent (safe to replay after crash)                    │   │
│  └──────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
```

### 4.1 Spring Authorization Server Integration

*(Decision C-03)*

Spring Authorization Server (SAS) handles OAuth2 protocol mechanics. All business logic is isolated in framework-independent Spring services.

**Endpoint path convention:** OAuth2 protocol endpoints follow the requirements' versioned API paths (`/api/v1/authorize`, `/api/v1/token`) — Spring Authorization Server is configured to use these paths instead of its defaults (`/oauth2/*`). Discovery and JWKS endpoints use standard well-known paths (`/.well-known/jwks.json`, `/.well-known/openid-configuration`). Resource API endpoints (AISP, PISP, VRP) use BerlinGroup-style versioned paths (`/api/v1/accounts`, `/api/v1/payments`).

**Framework-managed (SAS):**
- PKCE verification (code_challenge / code_verifier with S256 method)
- JWT signing and issuance (RS256, key source from our Key Manager)
- Token exchange at `/api/v1/token` (SAS configured to this path)
- JWKS and OpenID discovery endpoints

**Custom implementations via SAS extension points:**

| Extension Point | Our Implementation | Purpose |
|----------------|-------------------|---------|
| `OAuth2AuthorizationConsentService` | `PSD2ConsentService` | PSD2 consent model: 90-day expiry, account selection, VRP parameters |
| `OAuth2AuthorizationService` | `PostgresAuthorizationService` | PostgreSQL-backed storage with refresh token family tracking |
| `OAuth2TokenCustomizer` | `PSD2TokenCustomizer` | Adds consent_id, scopes, account_holder_id to JWT claims |
| `AuthenticationProvider` (authorize endpoint) | `ScaAuthenticationProvider` | Injects multi-step SCA into the authorization code grant |

**Isolation principle:** `PSD2ConsentService`, `ScaEngine`, `ExemptionEngine`, `TokenFamilyManager` are plain Spring `@Service` classes with no imports from `org.springframework.security.oauth2.server.authorization`. Only the integration layer (`ScaAuthenticationProvider`, configuration classes) imports SAS types. Framework upgrades affect only the integration layer.

### 4.2 SCA Engine

*(Decision C-08)*

SCA state is persisted in the database. Controllers are stateless. Lockout is durable.

**Flow:**

```
Account Holder                    A4 (SCA Engine)                    Database
      │                                │                                │
      │── GET /authorize ─────────────►│                                │
      │                                │── create sca_session ─────────►│
      │                                │   (step=AWAITING_PIN,          │
      │◄─ Consent + PIN page ─────────│    expires_at=now+5min)        │
      │                                │                                │
      │── POST pin=**** ──────────────►│                                │
      │                                │── read sca_session ───────────►│
      │                                │   verify PIN (knowledge factor)│
      │                                │── update step=AWAITING_TOTP ──►│
      │◄─ TOTP page (dynamic link) ──│                                │
      │   "Confirm NOK 5,000 to       │                                │
      │    Ola Nordmann. Enter code."  │                                │
      │                                │                                │
      │── POST totp=123456 ───────────►│                                │
      │                                │── read sca_session ───────────►│
      │                                │   verify TOTP (possession)     │
      │                                │── update step=COMPLETED ──────►│
      │◄─ Redirect with auth code ────│                                │
```

**Security properties:**
- **Durable lockout:** 3 failed attempts → `locked_until = now + 5 min`. Persisted in database. Survives server restarts — prevents lockout bypass attacks.
- **Dynamic link immutability:** `dynamic_link_data` (amount + creditor for payments) is written at session creation and never updated. Prevents amount-switching attacks (start SCA for EUR 1, complete for EUR 10,000).
- **Polymorphic context:** `context_type` (CONSENT / PAYMENT / VRP) determines what the SCA page displays. Consent-only: "FinApp AS requests access for 90 days." Payment: "Confirm NOK 5,000 to Ola Nordmann."
- **Timeout:** `expires_at = created_at + 5 min` (PSD2-F21). Check on every access. Expired → payment CANCELLED.

### 4.3 SCA Exemption Engine

*(Decision C-09)*

Chain of Responsibility pattern, modified to evaluate all handlers (no short-circuit).

```
Payment arrives
      │
      ▼
┌─────────────────┐   ┌─────────────────┐   ┌──────────────────┐
│ LowValueHandler │──►│ TrustedHandler  │──►│ RecurringHandler │──► ...
│ <EUR 30?        │   │ Whitelisted?    │   │ Same amt+payee?  │
│ Counters OK?    │   │                 │   │                  │
└─────────────────┘   └─────────────────┘   └──────────────────┘
      │                      │                       │
      ▼                      ▼                       ▼
   EXEMPT              NOT_EXEMPT                 EXEMPT

All results collected → select primary by priority:
LOW_VALUE > TRUSTED_BENEFICIARY > RECURRING > MIT > TRA

All evaluations logged to audit trail (not just the selected one)
```

**Concurrency safety:** Cumulative counters (`exempt_count_since_sca`, `exempt_amount_since_sca`) protected by pessimistic locking (`SELECT ... FOR UPDATE` on `sca_exemption_counters` row) within the same transaction as the payment record. Two concurrent payments for the same account are serialized at the counter row. Counter reset to zero after successful SCA, in the same transaction as SCA completion.

**Performance:** Evaluation is in-memory (counter lookup + threshold comparison). Database round-trip for counter read with pessimistic lock: ~1-2ms. Well within the 50ms budget (PSD2-NF03).

---

## 5. State Model and Crash Recovery

*(Decision C-02)*

### 5.1 Payment State Machine

Money-moving flows use an explicit state machine with durable state in PostgreSQL. The state machine serves double duty: crash resilience AND the TPP-facing payment status API (`GET /api/v1/payments/{id}`).

**Relationship to PISP-F06:** The requirements define TPP-visible states: REQUIRES_SCA, REQUIRES_CONFIRMATION, PENDING, PROCESSING, HELD, SETTLED, REJECTED, CANCELLED. Our state machine extends these with internal states needed for crash recovery and operational visibility. The TPP-facing API (`GET /api/v1/payments/{id}`) maps internal states to PISP-F06 states:

| Internal state(s) | TPP-visible state (PISP-F06) | Rationale |
|-------------------|------------------------------|-----------|
| CREATED, VOP_CHECKING, VOP_COMPLETE, VOP_TIMEOUT | PENDING | TPP doesn't need to see VoP sub-steps |
| REQUIRES_CONFIRMATION | REQUIRES_CONFIRMATION | VoP NO_MATCH — payer must confirm or cancel |
| REQUIRES_SCA | REQUIRES_SCA | SCA redirect needed |
| SCA_IN_PROGRESS | REQUIRES_SCA | Still awaiting SCA completion |
| SCA_COMPLETE, PUBLISHING | PENDING | Post-SCA, pre-Kafka — brief transitional state |
| PROCESSING | PROCESSING | Kafka published, awaiting A2 |
| SETTLED | SETTLED | Payment complete |
| REJECTED | REJECTED | Payment failed (with reason) |
| HELD | HELD | Compliance review |
| RETURN_INITIATED | RETURN_INITIATED | Reversal in progress |
| RETURNED | RETURNED | Reversal completed |
| CANCELLED | CANCELLED | SCA timeout, consent revocation, or payer cancel (with reason) |

```
                                CREATED
                                   │
                            validate request
                                   │
                                   ▼
                             VOP_CHECKING ──── A6 timeout ────► VOP_TIMEOUT
                                   │                                │
                              VoP result                       (proceed with
                                   │                            VOP_TIMEOUT flag)
                                   ▼                                │
                             VOP_COMPLETE                           │
                                   │                                │
                          ┌────────┴────────┐                      │
                          │                 │                      │
                     VoP MATCH         VoP NO_MATCH                │
                     or CLOSE_MATCH         │                      │
                          │                 ▼                      │
                          │      REQUIRES_CONFIRMATION             │
                          │          │           │                 │
                          │     (confirm)    (cancel)              │
                          │          │           │                 │
                          │          │           ▼                 │
                          │          │       CANCELLED             │
                          │          │                             │
                          ▼          ▼                             ▼
                              REQUIRES_SCA
                                   │
                             SCA evaluation
                          ┌────────┴────────┐
                          │                 │
                     SCA needed        SCA exempt
                          │                 │
                          ▼                 │
                    SCA_IN_PROGRESS         │
                     │          │           │
                (SCA success)  (timeout)    │
                     │          │           │
                     │          ▼           │
                     │      CANCELLED       │
                     ▼                      │
                   SCA_COMPLETE             │
                          │                 │
                          ▼                 ▼
                       PUBLISHING
                    (outbox row written
                     in same TX as payment)
                          │
                     outbox relay
                     publishes to Kafka
                          │
                          ▼
                      PROCESSING
                          │
                  (status from A2 via Kafka)
                     │         │          │
                     ▼         ▼          ▼
                 SETTLED   REJECTED    HELD
                    │               (compliance
                    │                review)
                    ▼
             RETURN_INITIATED
              (reversal in progress)
                    │
                    ▼
                RETURNED
              (reversal completed)
```

**VoP result handling:** MATCH proceeds directly to REQUIRES_SCA. CLOSE_MATCH also proceeds directly (close match is an acceptable verification — the name is similar enough). NO_MATCH transitions to REQUIRES_CONFIRMATION — the payer must explicitly confirm or cancel. TIMEOUT proceeds with VOP_TIMEOUT flag per SEC-12.

### 5.2 Recovery Actions After Crash

| State at crash | What happened | Recovery action | Data consistency |
|---------------|--------------|-----------------|-----------------|
| `CREATED` | Request received, not yet processed | Stale — no external side effects. Timeout or client retry | Clean |
| `VOP_CHECKING` | A6 call in flight | A6 call times out (500ms). Payment transitions to VOP_TIMEOUT | Clean — VoP is idempotent |
| `REQUIRES_SCA` | Waiting for user to start SCA | SCA timeout (5 min) → CANCELLED | Clean — no money moved |
| `SCA_IN_PROGRESS` | User mid-SCA | SCA timeout → CANCELLED | Clean — SCA session in DB, no outbox row yet |
| `SCA_COMPLETE` | SCA done, outbox row written, relay not yet published | **Outbox relay publishes on restart.** This is the critical recovery point. The outbox row and payment status were written in the same DB transaction | **Consistent** — outbox pattern guarantees this |
| `PUBLISHING` | Relay picked up the row | Relay is idempotent (checks if already published) | Clean |
| `PROCESSING` | Kafka message sent, waiting for A2 | A2 processes independently. Status update arrives via Kafka | Clean — A2 is idempotent on payment ID |

**Key invariant:** Money only moves after `SCA_COMPLETE` → outbox row written. The outbox row and the payment status update are in the same PostgreSQL transaction. If the transaction commits, both exist. If it rolls back, neither exists. The outbox relay publishes to Kafka after commit. Crash between commit and relay publish → relay retries on restart.

### 5.3 Flows Without State Machines

| Flow | Recovery model | Reasoning |
|------|---------------|-----------|
| Token refresh | Single DB transaction. Crash → rollback. Client retries with same refresh token (still valid because never marked as used) | Atomic — no intermediate state |
| AISP query | Stateless read. Crash → client retries | No state to recover |
| Consent revocation | Single DB transaction (consent → REVOKED, tokens invalidated). Crash → rollback, consent still active. Client retries | Atomic |
| Consent creation | SCA state machine (C-08) handles the multi-step part. Consent record created atomically after SCA_COMPLETE. Crash during SCA → timeout → user restarts | No money at stake |

---

## 6. Data Model

The data model consolidates tables from the requirements with tables introduced by architectural decisions.

### 6.1 Tables from Requirements

*(Defined in [requirements.md](../requirements/requirements.md) Section 8)*

| Table | Primary Key | Purpose | Owner |
|-------|-------------|---------|-------|
| `consents` | `consent_id` (UUID) | PSD2 consent records (STANDARD, VRP) | Consent Manager |
| `vrp_consent_extensions` | `vrp_consent_id` (UUID, FK) | VRP parameter bounds and cumulative tracking | VRP Controller |
| `refresh_tokens` | `token_id` (UUID) | Hashed refresh tokens with family-based reuse detection | Token Lifecycle |
| `sca_exemption_counters` | `account_id` (VARCHAR) | Cumulative exempt payment count/amount since last SCA | Exemption Engine |

### 6.2 Tables from Architectural Decisions

| Table | Primary Key | Purpose | Introduced by |
|-------|-------------|---------|---------------|
| `payments` | `payment_id` (UUID) | Payment state machine — tracks status from CREATED through SETTLED/REJECTED/CANCELLED | C-02 |
| `sca_sessions` | `session_id` (UUID) | Durable SCA state — step, attempts, lockout, dynamic link data | C-08 |
| `signing_keys` | `key_id` (UUID) | JWT signing key pairs with rotation lifecycle (ACTIVE/RETIRED/REVOKED) | C-07 |
| `audit_events` | `event_id` (UUID) | Append-only audit trail — every consent, SCA, token, and API event | C-14 |
| `outbox` | `event_id` (UUID) | Transactional Outbox — payment instructions and audit events pending Kafka publish | PSD2-RES01 |
| `registered_tpps` | `client_id` (VARCHAR) | TPP registration — certificate DN, redirect URIs, rate limit config | TPP Manager |

### 6.3 Payment Table (State Machine)

```
payments
  payment_id:           UUID (PK)
  consent_id:           UUID (FK to consents)
  tpp_client_id:        VARCHAR
  account_holder_id:    VARCHAR
  debtor_iban:          VARCHAR
  creditor_iban:        VARCHAR
  creditor_name:        VARCHAR
  amount:               DECIMAL(19,4)
  currency:             VARCHAR(3) (ISO 4217)
  reference:            VARCHAR (payment reference / remittance info, per PISP-F01)
  service_level:        ENUM (STANDARD, INSTANT)
  status:               ENUM (CREATED, VOP_CHECKING, VOP_COMPLETE, VOP_TIMEOUT,
                               REQUIRES_CONFIRMATION, REQUIRES_SCA,
                               SCA_IN_PROGRESS, SCA_COMPLETE,
                               PUBLISHING, PROCESSING,
                               SETTLED, REJECTED, HELD,
                               RETURN_INITIATED, RETURNED, CANCELLED)
  status_reason:        VARCHAR (nullable — e.g., SCA_TIMEOUT, VOP_NO_MATCH_OVERRIDE)
  vop_result:           ENUM (MATCH, CLOSE_MATCH, NO_MATCH, TIMEOUT, nullable)
  vop_override:         BOOLEAN (default false)
  sca_session_id:       UUID (FK to sca_sessions, nullable)
  sca_exemption_type:   VARCHAR (nullable — LOW_VALUE, TRUSTED_BENEFICIARY, etc.)
  created_at:           TIMESTAMPTZ
  updated_at:           TIMESTAMPTZ
  settled_at:           TIMESTAMPTZ (nullable)
```

### 6.4 Outbox Table

```
outbox
  event_id:             UUID (PK)
  aggregate_type:       VARCHAR (PAYMENT, AUDIT)
  aggregate_id:         UUID
  event_type:           VARCHAR (PAYMENT_INSTRUCTION, AUDIT_EVENT)
  topic:                VARCHAR (target Kafka topic)
  payload:              JSONB (serialized event)
  created_at:           TIMESTAMPTZ
  published_at:         TIMESTAMPTZ (nullable — set by relay after Kafka publish)
```

The relay polls for rows where `published_at IS NULL`, publishes to Kafka, then sets `published_at`. On crash, unpublished rows are retried on restart. Consumers are idempotent on `event_id`.

### 6.5 GDPR Erasure

*Master architecture reference: Decision F.1a (crypto-shredding scope), INTERFACE_CONTRACTS.md Section 4 (GDPR Erasure Protocol).*

A4's `consents` and `payments` tables store account holder IDs and IBANs — pseudonymous personal data under GDPR.

**On receiving an `AccountErasedEvent` from A1:**

1. **Active consents** for the erased account: revoke immediately (transition to `REVOKED`). TPPs are notified via standard consent status endpoints.
2. **Payment records within AML retention period** (5 years): retain with legal basis (AML 5th Directive, Article 40; PSD2 consent records retained for dispute resolution per PSD2 Article 72).
3. **Payment and consent records beyond retention period**: anonymize — `account_holder_id` → SHA-256 hash, IBANs → SHA-256 hash, `creditor_name` → `[REDACTED]`.

**Legal basis for retention:** AML record-keeping (5 years) and PSD2 dispute resolution obligations take precedence over GDPR erasure for payment and consent records (GDPR Article 17(3)(b)).

---

## 7. Integration Patterns

### 7.1 Trust Contracts Between Services

*(Decision C-05)*

Each service authenticates via mTLS and validates its own business invariants. Trust contracts are explicit:

| Service | Guarantees to others | Trusts from others |
|---------|---------------------|-------------------|
| **A4** | Consent enforcement before any outbound call. Metadata headers (X-Account-Holder-Id, X-Request-Id, X-Correlation-Id) on all requests | A1 returns only data for requested account. A6 limits response to match category |
| **A1** | Responds only to mTLS-authenticated internal callers. Validates account exists and is ACTIVE/FROZEN | A4 has verified consent before calling |
| **A6** | Responds only to mTLS-authenticated internal callers. Returns only match/close-match/no-match (no PII leakage) | A4 has verified the request is legitimate |

**Metadata headers are for auditability, not authorization.** A1 does not check consent — A4 owns that responsibility. A1 checks its own invariant: does this account exist and is it accessible?

### 7.2 Resilience for Synchronous Dependencies

*(Decision C-10)*

| Dependency | Timeout | Circuit Breaker | Cache | Failure Mode |
|-----------|---------|----------------|-------|-------------|
| A1 (balances) | 2s | Resilience4j, 50% threshold, 30s open | Caffeine 30s TTL | 503 + Retry-After |
| A1 (transactions) | 2s | Same breaker | None (paginated) | 503 + Retry-After |
| A6 (VoP) | 500ms | Separate breaker | None (per-payment) | Proceed with VOP_TIMEOUT flag (SEC-12) |

VoP is synchronous on the critical path — it must complete before the SCA redirect because VoP NO_MATCH changes the flow (payer must confirm or cancel before SCA). Async VoP was considered and rejected for this reason.

**Error response format:** All REST error responses follow RFC 9457 (Problem Details for HTTP APIs), auto-configured by the Platform Starter's `PaymentExceptionHandler`. This applies to all A4 API endpoints — both TPP-facing (BerlinGroup NextGenPSD2) and internal error cases (e.g., 503 from circuit breaker open, 429 from rate limiting).

### 7.3 Kafka Integration

**Producing (via Transactional Outbox):**

| Topic | Event | Trigger |
|-------|-------|---------|
| `payment.instructions` | PaymentInstruction (Avro) | Payment SCA complete, service_level=STANDARD |
| `payment.instant-instructions` | PaymentInstruction (Avro) | Payment SCA complete, service_level=INSTANT |
| `audit.events` | AuditEvent (Avro) | Every consent, SCA, token, and API event |

All Kafka publishes go through the Transactional Outbox. The payment/audit record and the outbox row are written in the same PostgreSQL transaction. The relay publishes to Kafka after commit.

**Consuming:**

| Topic | Event | Action |
|-------|-------|--------|
| `payment.status-updates` | PaymentStatusUpdate | Update payment state machine (PROCESSING → SETTLED/REJECTED/HELD, SETTLED → RETURN_INITIATED → RETURNED). DLQ after 3 failed retries (PSD2-RES02) |

Consumer is idempotent on `payment_id` + `status` — duplicate status updates are ignored. Payment status values are defined in INTERFACE_CONTRACTS.md Section 1.1. A4 must handle all five PaymentStatus values.

---

## 8. Security Architecture

### 8.1 mTLS — TPP Identity (L1)

*(Decision C-06)*

Application-level mTLS via Spring Boot 3 SSL bundles.

- Project-local CA and test TPP certificates in `certs/dev/`
- `TppCertificateExtractor` component: extracts Distinguished Name (DN) from client certificate → maps to registered TPP entity in `registered_tpps` table
- Connection without valid client certificate → rejected at TLS handshake (never reaches application code)
- Production model (documented): mTLS terminates at load balancer; certificate DN forwarded as header; eIDAS QWAC certificates from qualified trust service providers

### 8.2 OAuth2 and Token Lifecycle (L2)

**Access tokens:** JWT signed RS256. 15-minute expiry. Claims: `sub` (account holder), `client_id` (TPP), `scope`, `consent_id`, `exp`, `iat`. Verified by Spring Security OAuth2 Resource Server.

**Refresh tokens:** Opaque. Stored as SHA-256 hash in `refresh_tokens` table. Single-use (rotating). Expiry = consent expiry (max 90 days).

**Family-based reuse detection:** All refresh tokens from the same authorization code share a `family_id`. On token refresh: if the presented token's `used` flag is already `true`, **theft detected** → revoke all tokens with the same `family_id` → return `error=invalid_grant`. Legitimate client and attacker are both forced to re-authenticate.

**PKCE:** Mandatory on all authorization requests (`code_challenge` with S256 method). Managed by Spring Authorization Server — no custom code.

### 8.3 JWT Key Rotation (L2)

*(Decision C-07)*

Signing keys stored in PostgreSQL with application-managed rotation.

```
Timeline:

Day 0        Day 60       Day 90      Day 120     Day 150     Day 180
  │            │            │            │            │            │
  ├── Key A ───┼── expires ─┤            │            │            │
  │  (signing) │  (retired) │            │            │            │
  │            │            │            │            │            │
  │       ┌────┤            │            │            │            │
  │       │    ├── Key B ───┼── expires ─┤            │            │
  │       │    │  (signing) │  (retired) │            │            │
  │  (new key  │            │            │            │            │
  │  generated │       ┌────┤            │            │            │
  │  at Day 60:│       │    ├── Key C ───┼── expires ─┤            │
  │  Key A     │       │    │  (signing) │  (retired) │            │
  │  expires   │  (generated │            │            │            │
  │  in 30d)   │  at Day 120)│       ┌────┤            │            │
  │            │            │       │    ├── Key D ───┼── expires ─┤
  │            │            │       │    │  (signing) │  (retired) │
  │            │            │            │            │            │
  JWKS: [A]    JWKS: [A,B]  JWKS: [B]   JWKS: [B,C]  JWKS: [C]   JWKS: [C,D]

Each key: 90-day validity. New key generated when newest expires within 30 days.
Overlap window: 30 days (old + new both in JWKS). TPPs cache JWKS safely.
```

- Daily scheduled job: if newest key expires within 30 days, generate new RSA key pair
- JWKS endpoint returns all ACTIVE, non-expired keys
- Token signing uses the newest ACTIVE key
- 30-day overlap: TPPs that cache JWKS still see the old key during transition
- On compromise: set `status=REVOKED` → immediate JWKS removal. Affected JWTs expire in 15 minutes. Consent checks (L3) catch forged tokens in the interim

Private keys encrypted AES-256-GCM. Key encryption key (KEK) from environment variable (portfolio) or KMS (production, per KM-04).

### 8.4 Consent Enforcement (L3)

*(Decision C-11)*

Every authenticated API request checks consent status against the database. No caching.

```sql
SELECT status, expires_at, granted_scopes, accounts
FROM consents
WHERE consent_id = ?
  AND status = 'ACTIVE'
  AND expires_at > now()
```

Primary key lookup: ~0.5ms with warm buffer pool. No performance justification for caching. Consent revocation is immediately effective — zero staleness window.

**Implementation Note — Consent Enforcement by Construction:** The L3 consent check should be implemented as a `HandlerInterceptor` (not a per-controller method call) to ensure construction-level enforcement. The interceptor runs before any controller method and checks consent validity against the database for every authenticated request. A new endpoint added to A4 without explicit exclusion automatically gets consent enforcement. Endpoints that do not require consent (e.g., `/api/v1/authorize`, `/.well-known/*`) are explicitly excluded in the interceptor configuration.

### 8.5 JWE Payload Encryption (L5)

Optional, per-TPP. When a TPP registers a public key, sensitive fields in API responses (IBANs, balances) are JWE-encrypted.

- Algorithm: RSA-OAEP-256 (key encryption), A256GCM (content encryption) per KM-02
- Implementation: Nimbus JOSE library
- Only enabled for TPPs that register a public key — not forced on all TPPs
- Rationale: Mastercard's `client-encryption-java` pattern. Defense-in-depth: protects against TLS termination at proxy, log leakage

### 8.6 Audit Trail (L6)

*(Decision C-14)*

Dual-store: local PostgreSQL (queryable, durable) + Kafka (system-wide aggregation).

**Event types:** CONSENT_GRANTED, CONSENT_REVOKED, SCA_ATTEMPTED, SCA_COMPLETED, SCA_FAILED, SCA_LOCKED, TOKEN_ISSUED, TOKEN_REFRESHED, TOKEN_FAMILY_REVOKED, PAYMENT_INITIATED, EXEMPTION_EVALUATED, API_ACCESS, VOP_CHECKED

**Properties:**
- Append-only: repository exposes only `save()` — no update or delete
- Transactional co-location: audit event written in the same DB transaction as the action it records
- Outbox relay publishes to `audit.events` Kafka topic
- Queryable: "all SCA failures for account X in the last 24 hours"

### 8.7 Rate Limiting

*(Decision C-13)*

Bucket4j token bucket per TPP. 100 req/min configurable (KM-09). Keyed by `client_id` from mTLS certificate or OAuth2 token. HTTP 429 with `Retry-After` header. In-memory for portfolio; Redis-backed for production.

**PSD2 RTS Article 36(5)(b) — Passive AISP Access Limit:** In addition to the general per-TPP rate limit (100 req/min), A4 enforces a per-PSU per-AISP passive access counter. When the PSU is not actively requesting (no PSU interaction in the current session), AISP access is limited to 4 times per 24 hours per account. Counter keyed by `(aisp_client_id, account_id)`. After 4 passive accesses, return 429 with `Retry-After` header. The counter resets every 24 hours. PSU-initiated requests (with active PSU authentication) do not count against this limit.

**PSD2 Article 97(1)(a) — SCA for Account Access:** SCA is required not only for payment initiation but also for accessing a payment account online. AISP access requires SCA at the start of each session and at least every 90 days for ongoing access (RTS Article 10). The 4-per-24h passive access limit (already implemented) applies to accesses without active PSU involvement between SCA renewals.

### Threat Model Traceability (Master A.3a)

| Interface | Threat | Control | Reference |
|-----------|--------|---------|-----------|
| TPP API | Spoofing | L1 mTLS (eIDAS QWAC) + L2 OAuth2/PKCE | Layers L1, L2 |
| TPP API | Tampering | TLS integrity + L5 JWE encryption for sensitive payloads | Layer L5 |
| TPP API | Repudiation | L6 audit trail — all auth and consent decisions logged | Layer L6 |
| TPP API | Information Disclosure | L3 consent enforcement on every request (no cache) | Layer L3 |
| TPP API | Denial of Service | Rate limiting 100 req/min per TPP (Bucket4j) | Section 7.1 |
| TPP API | Elevation of Privilege | L4 SCA + exemption engine with pessimistic locking | Layer L4 |
| SCA flow | Spoofing | Dynamic linking — amount immutable once SCA session created | Section 5 |
| SCA flow | Denial of Service | Durable lockout after 3 failed attempts (DB-persisted) | Section 5 |
| Token management | Tampering | Refresh token family tracking with reuse detection and revocation | Section 4 |
| A1 integration | Spoofing | mTLS with service-specific certificate | A.1a |

### Security Requirements Traceability

| Requirement | Description | Architectural Element |
|------------|-------------|----------------------|
| PSD2-S01 | TPP authentication via mTLS | L1: eIDAS QWAC certificate validation at TLS handshake |
| PSD2-S02 | OAuth2 with PKCE | L2: Mandatory S256 PKCE on all authorization code flows |
| PSD2-S03 | Strong Customer Authentication | L4: SCA with dynamic linking, durable lockout, 5-minute timeout |
| PSD2-S04 | SCA exemption engine | L4: Chain of Responsibility, pessimistic-locked counters, all evaluations audited |
| PSD2-S05 | Consent enforcement on every request | L3: Database check every request, no cache, zero revocation window |
| PSD2-S06 | Refresh token security | SHA-256 hashed storage, family tracking, reuse detection with full family revocation |
| PSD2-S07 | JWE for sensitive payloads | L5: RSA-OAEP-256 + A256GCM encryption |
| PSD2-S08 | JWT signing key management | 90-day rotation, 30-day overlap, database-managed lifecycle |
| PSD2-S09 | Rate limiting per TPP | Bucket4j, 100 req/min per client_id |
| PSD2-S10 | Audit trail | L6: All auth, consent, and payment decisions logged to audit.events |

---

## 9. Testing Strategy

*(Decision C-12)*

Four-level testing pyramid with security property tests as an explicit category.

### 9.1 Test Levels

```
                    ┌───────────────┐
                    │   Security    │  Explicit defense-layer
                    │   Property    │  verification
                    │   Tests       │
                ┌───┴───────────────┴───┐
                │    Contract Tests     │  Pact — TPP-facing
                │    (Pact)             │  API surface
            ┌───┴───────────────────────┴───┐
            │      Integration Tests        │  Spring Boot + Testcontainers
            │      (mock-oauth2-server,     │  + embedded Kafka
            │       Testcontainers)         │
        ┌───┴───────────────────────────────┴───┐
        │            Unit Tests                  │  Exemption engine,
        │            (JUnit 5)                   │  consent logic, VRP bounds
        └───────────────────────────────────────┘
```

### 9.2 Security Property Tests — Organized by Defense Layer

| Layer | Test | Verifies |
|-------|------|----------|
| L1 | Request without client certificate → connection rejected | mTLS enforcement |
| L2 | Tampered JWT → 401 | Signature verification |
| L2 | Expired JWT → 401 | Expiry enforcement |
| L2 | Missing PKCE on /authorize → 400 | PKCE mandatory |
| L3 | Valid JWT + revoked consent → 403 | Consent re-validation from DB |
| L3 | Valid JWT + unauthorized account → 403 | Account-scope enforcement |
| L4 | SCA lockout persists after application restart | Durable lockout (C-08) |
| L4 | Dynamic link data immutable mid-SCA flow | Amount-switching prevention |
| L4 | 6th low-value payment forces SCA | Cumulative counter correctness |
| L5 | JWE-encrypted fields unreadable without TPP private key | Payload encryption |
| L6 | Consent grant produces audit event | Audit completeness |
| L6 | SCA failure produces audit event | Audit completeness |
| L6 | Exemption decision logged with rationale + counters | Regulatory auditability |

These tests directly verify the defense layer model from Section 3. Each layer's independence claim is backed by a test.

### 9.3 Key Testing Tools

| Tool | Purpose | Alignment |
|------|---------|-----------|
| NAV `mock-oauth2-server` | Programmable OAuth2 server for integration tests | Norwegian ecosystem (NAV) |
| Testcontainers | PostgreSQL, Kafka in integration tests | Tietoevry practice |
| Pact | Consumer-driven contract tests for AISP/PISP APIs | Tietoevry practice |
| WireMock | Simulate A1/A6 failures for resilience tests | Standard |
| JUnit 5 | All test levels | Universal |

---

## 10. Deployment Model

### 10.1 Local Development (docker-compose)

```
docker-compose up
```

Starts:
- **a4-gateway**: Spring Boot application (A4)
- **postgres**: PostgreSQL 15 (A4's database)
- **kafka** + **zookeeper**: Kafka broker
- **schema-registry**: Confluent Schema Registry (Avro schemas)

Certificates mounted from `certs/dev/`. All configuration via environment variables in `docker-compose.yml`. Zero manual setup for reviewers (AC-12).

### 10.2 Configuration

| Property | Default | Environment Variable | Purpose |
|----------|---------|---------------------|---------|
| mTLS trust store | `certs/dev/truststore.jks` | `MTLS_TRUSTSTORE_PATH` | CA for TPP certificate validation |
| JWT KEK | (dev default) | `JWT_KEY_ENCRYPTION_KEY` | Encrypts signing key private material |
| SCA timeout | 5 minutes | `SCA_TIMEOUT_SECONDS` | PSD2-F21 |
| SCA max attempts | 3 | `SCA_MAX_ATTEMPTS` | SCA-F05 |
| Rate limit | 100 req/min | `RATE_LIMIT_PER_TPP` | KM-09 |
| A1 base URL | `http://a1-ledger:8081` | `A1_BASE_URL` | Account Ledger REST |
| A6 base URL | `http://a6-vop:8086` | `A6_BASE_URL` | VoP Service REST |
| A1 timeout | 2000ms | `A1_TIMEOUT_MS` | Circuit breaker timeout |
| A6 timeout | 500ms | `A6_TIMEOUT_MS` | Circuit breaker timeout |

### 10.3 Production Upgrade Paths

The following table summarizes where the portfolio implementation makes pragmatic trade-offs and what the production upgrade looks like. In every case, the upgrade is a configuration or implementation swap — not a structural change.

| Aspect | Portfolio | Production | Change type |
|--------|-----------|------------|-------------|
| mTLS termination | Application-level (SSL bundles) | Load balancer + header forwarding | Configuration |
| JWT key storage | KEK from env var | KMS (AWS/Azure/Vault) | KeyProvider implementation swap |
| Rate limiting | In-memory Bucket4j | Redis-backed Bucket4j | Configuration (bucket4j-redis) |
| Consent caching | No cache (DB every request) | Caffeine + eviction (if profiling justifies) | Service implementation swap |
| Service auth | mTLS + metadata headers | Service mesh + scoped tokens | Infrastructure |
| Resilience | Circuit breaker + timeout | Add bulkhead isolation | Configuration |
| Certificates | Project-local CA | eIDAS QWAC from QTSPs | Certificate swap |

---

## 11. Relationship to Other Documents

| Document | Relationship |
|----------|-------------|
| [requirements.md](../requirements/requirements.md) | Source of all functional/non-functional requirements. This architecture implements them |
| [UC01_consent_flow.md](../use_cases/UC01_consent_flow.md) | Detailed consent flow scenarios. Architecture Sections 4.1, 4.2 implement this |
| [UC02_payment_initiation_and_sca.md](../use_cases/UC02_payment_initiation_and_sca.md) | Payment + SCA + exemption scenarios. Architecture Sections 4.3, 5.1 implement this |
| [gap_analysis.md](../extensions/gap_analysis.md) | Comparison with target company APIs and standards. Confirms feature scope |
| [ARCHITECTURE_DECISIONS.md](ARCHITECTURE_DECISIONS.md) | Full reasoning for each decision. This architecture is the result |
| [ARCHITECTURE_CHALLENGES.md](ARCHITECTURE_CHALLENGES.md) | Brainstorming record — all options considered with pros/cons |
| [Master system_requirements.md](../../payment_processing/requirements/system_requirements.md) | Cross-cutting requirements (SEC-*, RES-*, KM-*) that constrain this architecture |
| [NAVIGATION.md](../../payment_processing/NAVIGATION.md) | Cross-references to all sub-project documents |

---

### Platform Starter Integration

A4 uses the following Platform Starter features:
- **Deny-all default**: overridden with custom `SecurityFilterChain` for OAuth2/mTLS six-layer model
- **RFC 9457 error handling**: auto-configured `PaymentExceptionHandler` for all REST error responses
- **Audit logging**: `@Audited` on consent and payment operations, programmatic `AuditLogger` for SCA events
- **Audit hash chain**: auto-configured tamper-evident hash chain on `audit.events`
- **Kafka HMAC**: auto-configured producer/consumer interceptors for message integrity
- **Kafka error handler**: three-category classification (TRANSIENT/POISONED/FATAL) for `payment.status-updates` consumer
- **Micrometer Tracing**: W3C Trace Context propagation across REST and Kafka
- **Structured logging**: logstash-logback-encoder with PII redaction
- **Health/readiness probes**: auto-configured for Kubernetes
- **Graceful shutdown**: connection drain and Kafka offset commit
