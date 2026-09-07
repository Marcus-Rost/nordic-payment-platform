# Nordic Payment Processing Infrastructure — Architectural Design

*Version 1.0*
*Date: 2026-04-11*

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Architectural Principles](#2-architectural-principles)
3. [System Decomposition](#3-system-decomposition)
4. [Security Architecture](#4-security-architecture)
5. [Communication Architecture](#5-communication-architecture)
6. [Data Architecture](#6-data-architecture)
7. [Payment Lifecycle and State Management](#7-payment-lifecycle-and-state-management)
8. [Consistency and Crash Recovery](#8-consistency-and-crash-recovery)
9. [Performance Architecture](#9-performance-architecture)
10. [Privacy and Regulatory Architecture](#10-privacy-and-regulatory-architecture)
11. [Cross-Cutting Concerns — Platform Starter](#11-cross-cutting-concerns--platform-starter)
12. [Observability Architecture](#12-observability-architecture)
13. [Testing Architecture](#13-testing-architecture)
14. [Deployment and Operations](#14-deployment-and-operations)
15. [Extensibility](#15-extensibility)
16. [Subsystem Architecture Summaries](#16-subsystem-architecture-summaries)
17. [Architectural Decision Log](#17-architectural-decision-log)

---

## 1. Introduction

### 1.1 Purpose

This document describes the architectural design of the Nordic Payment Processing Infrastructure — a system of six integrated microservices that process electronic payments end-to-end. It translates the functional and non-functional requirements from `system_requirements.md` into structural decisions that guide implementation.

### 1.2 Scope

The architecture covers:
- Six application services (A1–A6) plus a cross-cutting Platform Starter library
- The infrastructure they run on (Kafka, PostgreSQL, Docker)
- The communication, consistency, security, and operational mechanisms that bind them together

Out of scope: organizational processes (GDPR DPO, AML officer), physical infrastructure (data center, network hardware), and CI/CD pipeline design (covered separately).

### 1.3 Key Constraints

| Constraint | Source | Impact |
|-----------|--------|--------|
| Instant payments: 5s execution / 7s hard limit / 9s confirmation | EPC 2025 SCT Inst rulebook v1.1 | Drives time-budget allocation, deployment isolation, timeout design |
| Sanctions screening cannot be skipped | EU AML, IPR Art. 5d | Sanctions failure = payment rejection, never auto-pass |
| 24/7/365 availability for instant path | EU IPR, OPS-08 | Drives zero-downtime deployment, no maintenance windows |
| GDPR right to erasure vs. immutable event log | GDPR Art. 17, event sourcing | Drives crypto-shredding architecture |
| Portfolio demonstrability in <5 minutes | Section 10 of requirements | Drives docker-compose packaging, scripted demo |

### 1.4 Related Documents

| Document | Location | Relationship |
|----------|----------|-------------|
| System Requirements | `requirements/system_requirements.md` | What the system must do |
| Architecture Challenges | `architecture/ARCHITECTURE_CHALLENGES.md` | Open problems this design resolves |
| Architecture Decisions | `architecture/ARCHITECTURE_DECISIONS.md` | Options analysis for each decision |
| Subsystem Drafts | `{subsystem}/architecture/architecture_draft.md` | Per-service architectural detail |
| Navigation | `NAVIGATION.md` | Cross-references to all sub-project documents |

---

## 2. Architectural Principles

These principles guide every design decision. When two principles conflict, they are listed in priority order.

The principles and the design decisions that follow draw on established patterns from: *Enterprise Integration Patterns* (Hohpe & Woolf) for Kafka topology design; *Domain-Driven Design* (Evans) and *Implementing DDD* (Vernon) for service boundary justification; *Designing Data-Intensive Applications* (Kleppmann) for event sourcing, exactly-once semantics, and schema evolution; *Release It!* (Nygard) for circuit breakers, timeouts, and bulkhead isolation; and *OWASP ASVS v4.0* for security verification levels. Reference architectures from Allevo FinTP (investigation queues, configuration-over-code, reconciliation), Mastercard/NICS (multi-window clearing, centralized account registry), and Deutsche Bank (globally standardized AFC framework, miniservices) informed specific decisions documented in `ARCHITECTURE_DECISIONS.md`.

### P1: Security by Construction

Security is a structural property, not a feature. The system must make the insecure path harder than the secure path. The Platform Starter enforces security defaults (authenticated-by-default endpoints, security headers, audit logging). Sanctions screening is a mandatory pipeline stage — payments physically cannot bypass it.

*Source: Brainstorming points 1, 2. Reference: Deutsche Bank's globally standardized AFC framework.*

### P2: Crash Recovery as a First-Class Concern

The system must tolerate crashes at any point in a payment's lifecycle without losing money, duplicating payments, or leaving the system in an inconsistent state. Every intermediate state must have a defined recovery path. Durable mechanisms (database timers, Kafka offsets) replace in-memory state.

*Source: Brainstorming point 3. Requirements: RES-01 through RES-11.*

### P3: Quality Over Complexity

Use the simplest correct solution. Three similar lines of code are better than a premature abstraction. Configuration-driven behavior where parameters change; code changes where structure changes. No plugin frameworks, no runtime class loading, no speculative extensibility.

*Source: Brainstorming points 8, 9 (extensibility). Reference: FinTP's configuration-over-code.*

### P4: Justified Service Boundaries

Each service exists because it has distinct regulatory requirements, scaling needs, or failure isolation requirements — not because "microservices" is fashionable. If two functions can fail independently and have different availability requirements, they should be separate services. If they share a transaction boundary, they should not.

*Source: Brainstorming point 9 (microservices skepticism). Validation: DDD bounded contexts.*

### P5: Async for State Changes, Sync for Queries

State-changing operations (payments, settlements, fraud decisions) flow through Kafka — durable, auditable, recoverable. Read-only queries (balance lookups, VoP name checks) use synchronous REST — immediate response, no durability needed.

*Source: Brainstorming point 5. Requirements: Section 9.1 Kafka topology + A4-F10 synchronous REST.*

---

## 3. System Decomposition

### 3.1 Service Map

```
┌─────────────────────────────────────────────────────────────────────┐
│                        EXTERNAL ZONE                                │
│   TPPs, Merchants, Other PSPs, Payers                              │
└─────────────┬──────────────────┬──────────────────┬────────────────┘
              │ OAuth2/mTLS      │ API key           │ mTLS/API key
              v                  v                   v
┌─────────────────────────────────────────────────────────────────────┐
│                        DMZ ZONE                                     │
│                                                                     │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  │
│  │  A4: PSD2 API    │  │  A3: R2P /       │  │  A6: VoP         │  │
│  │  Gateway         │  │  Bill Payment    │  │  (responder      │  │
│  │                  │  │                  │  │   endpoint)      │  │
│  │  OAuth2 AuthZ    │  │  Merchant API    │  │                  │  │
│  │  SCA Engine      │  │  Consumer API    │  │  Rate limiting   │  │
│  │  VRP             │  │  Webhooks        │  │  Anti-enum       │  │
│  └────────┬─────────┘  └────────┬─────────┘  └────────┬─────────┘  │
└───────────┼──────────────────── ┼──────────────────────┼────────────┘
            │ mTLS               │ mTLS+HMAC            │ mTLS
            v                    v                      v
┌─────────────────────────────────────────────────────────────────────┐
│                        INTERNAL ZONE                                │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  A2: Clearing Engine                                        │   │
│  │  ┌────────────────────┐  ┌────────────────────────────────┐ │   │
│  │  │ Batch Instance     │  │ Instant Instance               │ │   │
│  │  │ (Spring Batch)     │  │ (Kafka Streams, 24/7)          │ │   │
│  │  └────────────────────┘  └────────────────────────────────┘ │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  ┌──────────────────────┐  ┌────────────────────────────────────┐  │
│  │  A5: Fraud &         │  │  A1: Account Ledger               │  │
│  │  Sanctions Engine    │  │  (Event-Sourced, CQRS)            │  │
│  │  3-layer pipeline    │  │  Balance holds, Multi-currency    │  │
│  │  Case management     │  │  Reconciliation                   │  │
│  └──────────────────────┘  └────────────────────────────────────┘  │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  INFRASTRUCTURE                                              │   │
│  │  Kafka (3 brokers, Schema Registry) │ PostgreSQL (per svc)  │   │
│  │  Prometheus │ Grafana │ Grafana Tempo                        │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 Service Boundary Justification

| Service | Why It Is Separate |
|---------|-------------------|
| **A1 Account Ledger** | Single source of truth for balances. Event sourcing has fundamentally different data management patterns. Must never be directly exposed to external traffic. |
| **A2 Clearing Engine** | Orchestrator with two processing models (batch/instant). Most complex operational requirements (24/7 SLA). Failure here affects all payments — isolation limits blast radius. |
| **A3 R2P Service** | External merchant/consumer API surface. Different authentication model (API keys for B2B, OAuth2 for B2C). Independent lifecycle from clearing. |
| **A4 PSD2 Gateway** | Regulatory mandate (PSD2) with specific API standards (BerlinGroup). OAuth2 Authorization Server is a distinct security boundary. Separate lifecycle from payment processing. |
| **A5 Fraud/Sanctions** | Legal obligation (AML). Different data sources (sanctions lists, ML models). Different operational team (compliance analysts). Must be independently deployable (list updates). |
| **A6 VoP Service** | External-facing (other PSPs query us). Different availability profile (lightweight, high-throughput, stateless). Could be operated by a different team. |

### 3.3 Shared Libraries

| Library | Purpose | Mechanism |
|---------|---------|-----------|
| `payment-security-starter` | Cross-cutting security, audit, metrics, tracing, error handling | Spring Boot auto-configuration |
| `payment-domain-model` | Shared domain types: MoneyAmount, PaymentInstruction, FraudScore, VoPResult, exception hierarchy | Plain Java library |
| `payment-avro-schemas` | Avro schema definitions for all Kafka topics | Schema Registry + generated classes |
| `payment-iso20022` | JAXB-generated classes from ISO 20022 XSD | XML binding library |

---

## 4. Security Architecture

### 4.1 Trust Zones and Authentication

The system uses a **three-zone model with internal HMAC and mTLS verification**.

**External → DMZ boundary:**
- A4: OAuth2 Bearer tokens + mTLS (eIDAS QWAC simulation) for TPPs
- A3: API keys (merchants), OAuth2 tokens (consumers)
- A6 responder: mTLS or API keys for querying PSPs
- All external-facing endpoints: rate limiting, input validation, CORS deny-by-default

**DMZ → Internal boundary:**
- REST calls: mTLS with service-specific certificates (no external tokens forwarded)
- Kafka messages: HMAC-SHA256 signatures in headers (KM-06). Consumers verify before processing. Per-producer HMAC keys prevent cross-service impersonation.

**Internal → Infrastructure boundary:**
- Kafka: SASL/SSL (SEC-01)
- PostgreSQL: TLS connections, per-service credentials, database-per-service isolation (OPS-05)

**PostgreSQL Configuration Requirements:** `fsync=on` (default, must not be disabled — disabling voids all durability guarantees for committed transactions). `synchronous_commit=on` (default, must not be disabled for payment databases).

### 4.2 Authenticated by Default

The Platform Starter auto-configures Spring Security with `anyRequest().authenticated()`. A service that does not explicitly configure its security still rejects all unauthenticated requests. Only actuator health/readiness/metrics endpoints are pre-permitted.

Services with public-facing endpoints (A3 merchant API, A4 consent page, A6 responder) override this default with their own `SecurityFilterChain` — the Starter's default yields via `@ConditionalOnMissingBean`.

### 4.3 Cryptographic Standards

| Purpose | Algorithm | Requirement |
|---------|-----------|-------------|
| Data at rest | AES-256-GCM | KM-01 |
| Data in transit | TLS 1.2+ (prefer 1.3). Cipher suites: TLS_AES_256_GCM_SHA384, TLS_AES_128_GCM_SHA256, TLS_CHACHA20_POLY1305_SHA256 (TLS 1.3); ECDHE+AESGCM (TLS 1.2). Disabled: CBC mode, RC4, 3DES, SHA1-based MACs, RSA key exchange (no forward secrecy). | KM-01 |
| JWT signing | RS256 (RSA-2048+) | KM-01, A4-S01 |
| JWE encryption | RSA-OAEP-256 + A256GCM | KM-02 |
| Kafka message integrity | HMAC-SHA256 | KM-06 |
| Webhook signing | HMAC-SHA256 | KM-07 |
| Hashing | SHA-256+ | KM-01 |
| Crypto-shredding | AES-256-GCM per-account keys | Section 10.1 |

### 4.4 Key Management

**Two-tier architecture** with a `KeyProvider` abstraction:
- **Portfolio/Development:** Keys injected at deployment time (Docker secrets, environment variables)
- **Production:** Centralized KMS (HashiCorp Vault, AWS KMS). Same application code, different `KeyProvider` implementation.

```java
public interface KeyProvider {
    SecretKey getEncryptionKey(String keyId);
    KeyPair  getSigningKeyPair(String purpose);
    void     rotateKey(String keyId);
}
```

Key rotation schedules: JWT signing keys every 90 days (30-day overlap), Kafka SASL every 180 days, database encryption annually (KM-03).

### 4.5 Threat Model Scope

Threats are analyzed via **boundary STRIDE** (at trust zone crossings) plus **attack trees** for three high-value assets:

1. **Unauthorized payment execution** — attacker triggers a payment without legitimate authorization
2. **Sanctions screening bypass** — payment settles without being screened
3. **PII exfiltration** — account holder names, IBANs, balances exposed to unauthorized party

**Explicitly out of scope:** Physical security, social engineering, nation-state cryptographic attacks, L3/L4 DDoS (application-level rate limiting is in scope), omnipotent insider with root access to all systems.

**Encryption at Rest (Infrastructure Requirement):** Application-level encryption is handled by crypto-shredding (AES-256-GCM per-account keys for PII in events). Infrastructure-level encryption at rest is an operational requirement delegated to the deployment environment: PostgreSQL databases should use Transparent Data Encryption (TDE) or encrypted volumes. Kafka broker storage should use encrypted persistent volumes. Kubernetes etcd should enable encryption at rest for Secrets. These are documented as infrastructure requirements, not application-level controls.

---

## 5. Communication Architecture

### 5.1 Communication Principles

| Principle | Implementation |
|-----------|---------------|
| State changes flow through Kafka | Payment instructions, fraud results, settlement events, status updates, freeze commands |
| Queries use synchronous REST | Balance lookups (A4→A1), VoP checks (A3/A4→A6), management APIs |
| No service calls another service synchronously in the payment clearing path | A2 communicates with A1 and A5 via Kafka, not REST |
| Fire-and-forget where the producer doesn't need confirmation | Settlement events (A2→A1), status updates (A2→A3/A4), audit events |
| Request-response over Kafka where the producer needs a result | Fraud check (A2→A5→A2), balance reservation (A2→A1→A2) |

### 5.2 Kafka Topic Topology

```
Entry Points (Phase 1)                    Clearing (Phase 2)
┌─────────┐  payment.instructions         ┌─────────────────┐
│ A3, A4  ├──────────────────────────────→│ A2 (batch)      │
│         │  payment.instant-instructions │ A2 (instant)    │
│         ├──────────────────────────────→│                 │
└─────────┘                               └────────┬────────┘
                                                   │
                  ┌────────────────────────────────┤
                  │                                │
                  v                                v
           payment.fraud-check-request      payment.balance-reservation
           payment.instant-fraud-check-req  payment.balance-reservation-response
                                            payment.settlement-events
                  │                         payment.instant-settlement-events
                  v                                │
           ┌──────────┐                            v
           │ A5 Fraud │                     ┌──────────┐
           │          │                     │ A1 Ledger│
           └────┬─────┘                     └──────────┘
                │
                v
           payment.fraud-check-response
           payment.instant-fraud-check-resp ──→ A2

Status & Control
  payment.status-updates        A2 → A3, A4
  payment.reconciliation-results A1 → A2, monitoring
  account.freeze-commands       A5 → A1
  payment.investigation         A2 → operator/monitoring
  sanctions.list-updates        A5 internal
  audit.events                  All → audit consumer

Event Sourcing
  ledger.events                 A1 → A1 (projection consumer)
                                Infinite retention
```

**Kafka durability configuration** (all payment-related topics):
- Replication factor: 3
- `min.insync.replicas`: 2
- `acks`: all
- `ledger.events` retention: infinite (`retention.ms=-1`)
- Other topics: 7 days minimum
- `unclean.leader.election.enable=false` (mandatory — without this, a broker behind on replication can become leader after failure and lose acknowledged writes)

**Kafka partition key strategy:**

| Topic | Partition Key | Rationale |
|-------|--------------|-----------|
| `payment.instructions` | `transactionId` | Even distribution across partitions. No per-account ordering needed for batch (netting reorders anyway). |
| `payment.instant-instructions` | `debtorAccountId` | Per-account ordering prevents concurrent instant payments from the same account racing. |
| `payment.fraud-check-request` | `transactionId` | Matches response topic key for Kafka Streams join in A2. |
| `payment.instant-fraud-check-request` | `transactionId` | Same — correlation join requires matching keys. |
| `payment.fraud-check-response` | `transactionId` | Must match request key for stream join. |
| `payment.instant-fraud-check-response` | `transactionId` | Must match request key for stream join. |
| `payment.settlement-events` | `transactionId` | Even distribution. A1 is idempotent — ordering not critical. |
| `payment.instant-settlement-events` | `transactionId` | Same. |
| `payment.balance-reservation` | `accountId` | Per-account ordering ensures reserve/confirm/release arrive in order for the same account. |
| `payment.balance-reservation-response` | `transactionId` | A1 → A2. Must match A2's correlation join key. |
| `payment.status-updates` | `transactionId` | A3/A4 look up by transactionId. |
| `payment.reconciliation-results` | `clearingCycleId` | Groups reconciliation results by cycle. |
| `ledger.events` | `accountId` | Per-account ordering is essential for event sourcing correctness (LED-NF04). |
| `account.freeze-commands` | `accountId` | Per-account ordering. |
| `payment.investigation` | `transactionId` | Even distribution. |
| `sanctions.list-updates` | `listId` | Single partition sufficient (low volume). |
| `audit.events` | `correlationId` | Groups related audit entries. Cardinality is high — distributes well. |

**Hot partition mitigation:** The `debtorAccountId` key on `payment.instant-instructions` could create a hot partition for high-volume corporate accounts. Mitigation: monitor per-partition lag (`kafka.consumer.lag` by partition) and increase partition count if skew exceeds 3x the average. For the portfolio project with synthetic data, this is not a concern.

### 5.3 Synchronous REST Interactions

| Caller | Callee | Purpose | Auth | Timeout |
|--------|--------|---------|------|---------|
| A4 | A1 | AISP balance/transaction queries | mTLS | 2s |
| A3/A4 | A6 | VoP check before payment | mTLS | 500ms |
| A5 case mgmt | A1 | PII enrichment at display time | mTLS | 1s |
| A6 | A1 | Account holder name lookup (cache miss) | mTLS | 200ms |

### 5.4 Serialization

- **Kafka:** Avro with Confluent Schema Registry. Compatibility mode: BACKWARD. All schemas in `payment-avro-schemas` library. CI/CD enforces compatibility on every schema change.
- **REST APIs:** JSON. External APIs follow BerlinGroup NextGenPSD2 format (A4) or RFC 9457 Problem Details for errors.
- **ISO 20022 XML:** JAXB-generated classes. XML parser hardened against XXE (KM-08).

**In-Cluster Communication Security:** Application-level mTLS is enforced for all inter-service REST calls. For Kafka and PostgreSQL connections within the cluster, SASL/SSL and TLS are configured at the application level. Pod-to-pod network traffic encryption beyond application-level TLS depends on the Kubernetes deployment: a service mesh (Istio, Linkerd) with mTLS provides additional defense-in-depth. This is documented as an infrastructure recommendation — the application provides end-to-end encryption at the application layer regardless.

---

## 6. Data Architecture

### 6.1 Data Ownership

Each service owns its data. No service reads another service's database directly.

| Service | Data Store | Owns | Strategy |
|---------|-----------|------|----------|
| **A1** | Kafka (`ledger.events`) + PostgreSQL (projections) | Account balances, transaction history, reservations, reconciliation, per-account encryption keys | **Kafka-first**: Kafka is the sole source of truth. PostgreSQL is a derived read model, also updated synchronously on the reservation hot path as an optimization. |
| **A2** | PostgreSQL | Clearing cycles, settlement records, liquidity positions, outbox | **Transactional Outbox**: DB + outbox in same transaction, relay to Kafka. |
| **A3** | PostgreSQL | Payment requests, mandates, webhook deliveries, outbox | **Transactional Outbox** |
| **A4** | PostgreSQL | Consents, refresh tokens, SCA counters, VRP consents, outbox | **Transactional Outbox** |
| **A5** | Kafka Streams state stores (RocksDB) + PostgreSQL | Account profiles, velocity windows (Kafka Streams), fraud alerts (PostgreSQL) | **Kafka Streams internal** for scoring pipeline. **Transactional Outbox** for case management actions. |
| **A6** | Caffeine (in-memory) + PostgreSQL (audit logs) | Name cache, VoP query logs | **Not applicable** — no Kafka in the payment flow. |

### 6.2 Event Sourcing (A1)

A1 uses event sourcing with CQRS:

```
Command ──→ Validate ──→ Publish event to ledger.events (Kafka)
                                    │
                              ┌─────┘
                              v
                    Projection consumer
                              │
                              v
                    PostgreSQL projections
                    (account_balances, transaction_history)
                              │
                              v
                    Query API reads from PostgreSQL
```

- **Events are immutable.** Once published, they cannot be changed.
- **Projections are disposable.** They can be rebuilt from Kafka at any time (LED-F09).
- **Infinite retention** on `ledger.events` (`retention.ms=-1`).
- **Snapshots** every 1,000 events per account for fast projection rebuild (LED-F40).

**Event Store Backup (Infrastructure Requirement):** The `ledger.events` Kafka topic is the source of truth for all financial data. Loss of this topic is unrecoverable — projections can be rebuilt from events, but events cannot be rebuilt from projections. Kafka's built-in replication (factor=3) protects against single-broker failure but not against correlated failures (data center loss, operator error deleting the topic). **Production requirement:** periodic export of `ledger.events` to write-once storage (e.g., S3 with Object Lock, Azure Immutable Blob, or GCS retention policy) via Kafka Connect S3 Sink or MirrorMaker 2. Export frequency: continuous or hourly. Retention in write-once storage: aligned with AML requirements (5 years minimum).

**Hot-path exception:** Balance reservations update the PostgreSQL projection synchronously (in the command handler) in addition to publishing the event. This ensures that an immediately-following balance query sees the reservation (<100ms latency requirement, LED-NF03). The Kafka event remains the durable record.

### 6.3 Schema Evolution

**Allowed changes** (backward-compatible under Avro rules):
- Add a new field with a default value
- Add a new enum value (with reader default for unknown values)
- Add a new event type

**Forbidden changes:**
- Remove or rename a field
- Change a field's type
- Change a field from optional to required

**Enforcement:** Schema Registry compatibility check in CI/CD. Every PR that modifies an Avro schema triggers `schema-registry:test-compatibility` against the registered schema. Build fails on incompatible change.

**Major version change** (breaking): New topic (e.g., `ledger.events.v2`) with migration period.

### 6.4 API Versioning

External-facing APIs use **URL path versioning** (`/api/v1/`). Maximum 2 concurrent versions. Deprecation period: 6 months (12 months for PSD2 APIs). After deprecation, old version returns `410 Gone`.

Internal service-to-service REST APIs do NOT use versioning — they evolve in lockstep since we control both sides.

### Kafka Storage Lifecycle Management

**The Problem:** `ledger.events` (infinite retention) and `audit.events` (5-year retention) grow without bound. At 1,000 TPS with ~1KB messages and replication factor 3: ~93TB/year raw disk for `ledger.events` alone.

**Strategy — Tiered Storage:**
- **Portfolio:** All data on broker local disk. Acceptable for demo volumes.
- **Production:** Kafka Tiered Storage (KIP-405, available in Confluent Platform and Apache Kafka 3.6+). Cold segments automatically moved to object storage (S3/GCS/Azure Blob). Hot segments (recent data, configurable window) remain on broker local disk for low-latency access.
- **Configuration:** `remote.log.storage.enable=true`, `local.retention.ms` (hot window, e.g., 7 days), `retention.ms=-1` (infinite logical retention). Cold data served from object storage on replay.
- **Alternative (without tiered storage):** Periodic export via Kafka Connect S3 Sink, then truncate topic to exported offset. Requires verified export before truncation.
- **`audit.events`:** Same strategy. After 5-year regulatory retention, archived segments can be moved to glacier-tier storage.

### PostgreSQL Table Partitioning

High-write tables must use PostgreSQL declarative partitioning by date to manage growth and maintain query performance.

| Table | Service | Growth Rate (at 1K TPS) | Partition Strategy |
|-------|---------|------------------------|-------------------|
| `transaction_history` | A1 | 172M rows/day | Monthly range by `created_at` |
| `clearing_records` | A2 | 86M rows/day | Monthly range by `received_at` |
| `vop_query_log` | A6 | 173M rows/day (at 2K QPS) | Monthly range by `created_at` |
| `psp_liquidity_movements` | A2 | 2M rows/day | Quarterly range by `created_at` |
| `processed_event_ids` | A1 | TTL-based cleanup | Daily range, drop partitions older than 24 hours |

Archival: partitions older than the retention period (5 years for AML-relevant tables) can be detached (`ALTER TABLE ... DETACH PARTITION`) and moved to cold storage or dropped. Queries must always include the partition key in the WHERE clause for partition pruning.

---

## 7. Payment Lifecycle and State Management

### 7.1 Unified Payment State Machine

A payment moves through three phases, each owned by a different service.

*Note: Phase 1 states derive from A3-F05/A3-F07 and Phase 3 reservation states from A1-F11. The Phase 2 states (INSTRUCTION_RECEIVED through SETTLEMENT_EXECUTING) are architectural refinements that make A2's internal processing explicit. These should be backported to sub-project #11 requirements during implementation.*

```
PHASE 1 — Owned by A3 or A4
─────────────────────────────────────────────────────────────
  CREATED ──→ PENDING ──→ VoP check (A6) ──→ ACCEPTED
                │                                │
                ├──→ DECLINED (with reason)       │
                ├──→ DEFERRED (pay later date)    │
                └──→ EXPIRED (past due date)      │
                                                  v
                                         PAYMENT_INITIATED
                                                  │
                                            ┌─────┘
                                  Kafka publish (handoff)
                                            │
PHASE 2 — Owned by A2                      v
─────────────────────────────────────────────────────────────
  INSTRUCTION_RECEIVED ──→ BALANCE_RESERVED ──→ FRAUD_CHECK_PENDING
                                                       │
                          ┌──────────────────────┬─────┘
                          v                      v
                   FRAUD_CLEARED          FRAUD_BLOCKED
                          │                      │
                          v                      v
                   SETTLEMENT_EXECUTING    REJECTED (reason)
                          │                      │
                          v                 release reservation
                   SETTLED                       │
                          │               Kafka publish (status)
                    ┌─────┘
          Kafka publish (settlement + status)
                    │
PHASE 3 — Owned by A1 (ledger) + A3/A4 (reporting)
─────────────────────────────────────────────────────────────
  A1: confirm reservation ──→ debit payer, credit beneficiary
  A3/A4: receive SETTLED/REJECTED ──→ update local status ──→ webhook/response to TPP
```

### 7.2 Ownership Transitions

| Transition | Mechanism | Durability |
|-----------|-----------|------------|
| Phase 1 → Phase 2 | A3/A4 publishes PaymentInstruction to Kafka | Kafka acks=all, replication 3 |
| Phase 2 → Phase 3 | A2 publishes settlement event + status update to Kafka | Kafka acks=all, replication 3 |

The handoff is the Kafka publish. Once Kafka acknowledges the message, the receiving service owns the state. Kafka's durability guarantees (RES-07) make the handoff reliable.

### 7.3 Coordination Model

**Instant path: Orchestrated Saga (A2 as orchestrator)**

A2 manages the payment state and drives compensation on failure:
1. Reserve balance (A1) → if fails: REJECT
2. Request fraud/sanctions check (A5) → if timeout: auto-pass fraud (UNKNOWN score), reject sanctions
3. Verify liquidity → if insufficient: release reservation, REJECT
4. Settle → publish settlement event + confirm reservation
5. On any failure after reservation: release reservation, publish REJECTED

A2 holds a global 7-second hard-limit timer. When it fires, A2 immediately triggers compensation regardless of which step is in progress.

**Batch path: Choreographed**

A2 publishes settlement events. A1 processes them independently. A3/A4 receive status updates independently. No orchestrated compensation needed — batch settlement is a single step per payment after netting.

---

## 8. Consistency and Crash Recovery

### 8.1 Dual-Write Resolution

| Service | Strategy | Mechanism |
|---------|----------|-----------|
| A1 | Kafka-first | Kafka is the sole source of truth. The command handler writes to Kafka first, then updates PostgreSQL synchronously as a derived optimization for the reservation hot path. This is not a dual-write problem because PostgreSQL is always rebuildable from Kafka — Kafka is authoritative. |
| A2, A3, A4 | Transactional Outbox | DB + outbox in same transaction. In-process Kafka publish after commit. Scheduled relay as fallback for failed publishes. |
| A5 scoring | Kafka Streams internal | `exactly_once_v2` handles produce-and-commit atomically. |
| A5 case mgmt | Transactional Outbox | Same as A2/A3/A4 for alert resolution actions. |
| A6 | N/A | No Kafka in payment flow. |

### 8.2 Recovery on Service Restart

Every service follows a two-phase recovery on startup:

1. **Database scan:** Query for records in intermediate states (e.g., reservations older than 30 seconds, payments in FRAUD_CHECK_PENDING older than timeout). Resolve them: release stale reservations, timeout stuck payments.
2. **Kafka replay:** Resume consuming from last committed offset. Idempotent processing (RES-05) ensures no double-counting even if the database scan already handled some messages.

Both phases are idempotent. The recovery scan runs before the readiness probe passes — the service does not accept traffic until recovery is complete.

### 8.3 Idempotency

**Hierarchical transaction ID strategy:**
- A3 or A4 generates the top-level `transactionId` (UUID) at payment initiation. This ID travels through the entire lifecycle.
- Each service generates operation-specific IDs deterministically: `SHA-256(transactionId + operationType)`. Retries produce the same operation ID.
- Each service deduplicates by its relevant ID in a `processed_message_ids` table (PostgreSQL, with TTL: 24h for batch, 1h for instant).

### 8.4 Investigation Queues

Beyond Dead Letter Queues (for unprocessable messages), the system has a **dedicated investigation mechanism** for payments stuck in intermediate states:

- A2 exposes `GET /api/v1/admin/investigations` — payments stuck in Phase 2 beyond their expected timeout
- Operator actions: force-complete, force-reject, retry, escalate
- All operator actions logged to `audit.events`
- A Kafka topic `payment.investigation` receives stuck payments for centralized monitoring

**Escalation thresholds:**
- Instant payment stuck >30 seconds: auto-escalate (should have resolved)
- Batch payment stuck >2x clearing window: alert
- Any payment stuck >1 hour: page on-call

### 8.5 Late-Arriving Fraud Responses

If A5 responds with SANCTIONS_HIT after A2 has already auto-passed and settled (race condition on timeout):
1. A5 publishes the response normally (it always publishes)
2. A2 detects the late response via its Kafka Streams topology (the payment is already SETTLED)
3. A2 creates a `SETTLED_BEFORE_SCREENING` alert
4. A2 initiates a payment return procedure (pacs.004)

This is an edge case but architecturally handled by design, not as an afterthought.

### 8.6 Circuit Breakers

Synchronous REST calls between services are protected by circuit breakers (Resilience4j, auto-configured by the Platform Starter). A circuit breaker prevents a slow or failing downstream service from exhausting the caller's thread pool.

| Caller | Callee | Failure Threshold | Fallback Behavior |
|--------|--------|-------------------|-------------------|
| A3/A4 | A6 (VoP) | 50% failure rate over 10 calls, 30s half-open | Proceed with `VOP_TIMEOUT` flag (SEC-12). Payment continues, flagged for review. |
| A4 | A1 (AISP) | 50% failure rate over 10 calls, 30s half-open | Return HTTP 503 with `Retry-After` header to TPP. Serve from Caffeine cache if available (stale data, marked as cached). |
| A5 case mgmt | A1 (PII enrichment) | 50% failure rate over 10 calls, 30s half-open | Display alert without PII. Analyst sees account ID but not holder name. |
| A6 | A1 (name lookup) | 50% failure rate over 10 calls, 30s half-open | Serve from Caffeine cache if hit. On cache miss, return `ERROR` result. |
| A2 outbox relay | Kafka | N/A — not a circuit breaker | Outbox table retains unsent events. Scheduled relay retries. No data loss. |
| A5 | EU/UN sanctions endpoints | 3 consecutive failures, 5min half-open | Continue using current in-memory sanctions list. Alert: `SANCTIONS_LIST_STALE`. |

Circuit breakers are configured in `application.yml` via the Starter's auto-configuration:

```yaml
payment:
  resilience:
    circuit-breaker:
      default:
        failure-rate-threshold: 50
        minimum-number-of-calls: 10
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
```

Circuit breaker state transitions (CLOSED → OPEN → HALF_OPEN) are logged at WARN level and exposed as Prometheus metrics (`resilience4j.circuitbreaker.state`).

---

## 9. Performance Architecture

### 9.1 Instant Payment Time Budget

```
EPC 2025 SCT Inst Rulebook v1.1:
  Execution target:    5,000ms
  Hard limit:          7,000ms
  Confirmation:        9,000ms

Phase 1 (A3/A4, before A2):
  VoP check (A6):                  ≤ 500ms
  Validation + Kafka publish:      ≤ 300ms
  ──────────────────────────────────────────
  Phase 1 subtotal:                ≤ 800ms

Phase 2 (A2 orchestration):
  Kafka consume + validate:        ≤ 200ms
  Balance reservation (A1):        ≤ 200ms
  Fraud + sanctions (A5):          ≤ 800ms  (500ms target + 300ms margin)
  Settlement logic:                ≤ 300ms
  Ledger confirm (A1):             ≤ 200ms
  Kafka publish (status):          ≤ 100ms
  ──────────────────────────────────────────
  Phase 2 subtotal:                ≤ 1,800ms

Network/Kafka overhead:             ≤ 400ms
  ──────────────────────────────────────────
  Total execution path:            ≤ 3,000ms  (well within 5,000ms target)
  Safety margin:                    2,000ms
  Hard limit (global timer):        7,000ms  (non-negotiable reject)
```

### 9.2 Timeout Strategy

**Static per-service timeouts with global hard-limit enforcement.**

Each service has a static timeout for its operations. A2 additionally maintains a global timer wrapping the entire instant payment processing pipeline:

```java
CompletableFuture<PaymentResult> processInstantPayment(PaymentInstruction instruction) {
    return doProcessing(instruction)
        .orTimeout(7, TimeUnit.SECONDS)
        .exceptionally(ex -> {
            releaseReservation(instruction);
            return PaymentResult.rejected(TIMEOUT);
        });
}
```

When the global timer fires, A2 executes the orchestrated Saga compensation immediately.

Individual timeout policies:
- A5 fraud timeout → auto-pass with UNKNOWN score, flag for post-settlement review
- A5 sanctions timeout → **REJECT** (sanctions cannot be skipped, A5-F28)
- A1 reservation timeout → REJECT (insufficient balance or service unavailable)
- Global 7-second timeout → REJECT with TIMEOUT reason code

### 9.3 Batch/Instant Resource Isolation

A2 deploys as **two separate JVM instances** using Spring profiles:

| Instance | Profile | Kafka Topics | Processing Model |
|----------|---------|-------------|-----------------|
| `clearing-engine-batch` | `batch` | `payment.instructions`, `payment.fraud-check-*` | Spring Batch, scheduled cycles |
| `clearing-engine-instant` | `instant` | `payment.instant-instructions`, `payment.instant-fraud-check-*` | Kafka Streams, 24/7 |

Both share the same PostgreSQL database with **separate connection pools**. The instant instance gets guaranteed minimum connections. Kubernetes resource limits (CPU, memory) are set independently.

**Why separate JVMs:** A batch clearing cycle processing 10,000 payments must not affect instant payment latency. Process isolation ensures a batch OOM, GC pause, or I/O saturation cannot impact the instant SLA.

A5 does NOT need separate instances — it uses separate Kafka consumer groups for batch and instant topics (A5-NF05). A5's processing is lightweight per-transaction (in-memory sanctions check + rule evaluation), and both paths share velocity state stores (which is desirable for complete fraud detection).

---

## 10. Privacy and Regulatory Architecture

### 10.1 Crypto-Shredding for GDPR Erasure

Event sourcing stores all balance changes as immutable Kafka events with infinite retention. GDPR Article 17 (right to erasure) is satisfied via **crypto-shredding**: PII is encrypted with a per-account key, and deleting the key makes PII irrecoverable.

**What is encrypted:**

| Field | Encrypted | Rationale |
|-------|-----------|-----------|
| Account holder name | Yes | Direct PII identifier |
| Account holder address | Yes | Direct PII |
| Transaction description / remittance info | Yes | May contain PII |
| IBAN | No | Pseudonymous personal data (GDPR Recital 26 — identification requires cross-referencing bank customer databases). Left in plaintext because it is essential for routing, deduplication, reconciliation, and the accounting identity invariant. Subject to access controls and audit logging as personal data. |
| Amount, currency | No | Financial data, not PII |
| Timestamps, IDs | No | Operational/technical data |

**Key storage:** Per-account AES-256-GCM keys stored in A1's PostgreSQL (`account_encryption_keys` table), themselves encrypted with the master key via `KeyProvider`.

**Consumer behavior after key deletion:** PII fields decrypt to `[REDACTED]`. Balance calculations use unencrypted amounts — financial integrity is preserved. A6 VoP queries for an erased account return `ACCOUNT_CLOSED` (erasure is only available for closed accounts after the mandatory retention period).

### 10.2 PII Minimization

A5's automated scoring pipeline processes only anonymized account IDs, amounts, timestamps, and patterns — never account holder names or addresses (A5-F12, SEC-10). The case management API enriches alerts with PII from A1 at query time (analyst opens an alert → REST call to A1 → name displayed).

PII is redacted from centralized logs by the Platform Starter (STARTER-F12).

### 10.3 Audit Trail with Tamper Evidence

All state-changing operations are logged to the `audit.events` Kafka topic.

**Tamper evidence (portfolio implementation):** Each audit event includes a SHA-256 hash of the previous event, forming a per-service hash chain. Tampering with any event invalidates all subsequent hashes. A periodic integrity check walks the chain and verifies consistency.

**Hash chain continuity across restarts:** On graceful shutdown, each service persists its latest hash value to a local file (`audit-chain-state.json`) or a dedicated database table. On startup, the hash chain resumes from the persisted value. If no persisted value exists (first start or state loss), the chain starts from a well-known `GENESIS` value. Chain breaks are detectable during integrity verification and are flagged as "service restart boundary" (expected) rather than "tamper evidence" (unexpected). The `audit.events` topic includes a `chainEpoch` field that increments on each restart, allowing verifiers to distinguish legitimate chain restarts from gaps.

**Production target (documented, not implemented):** Hash chaining + per-event digital signatures (non-repudiation) + daily signed checkpoint to separate write-once storage.

**Retention:**
- Payment/sanctions audit: 5 years (AML)
- Consent/authorization audit: 5 years (PSD2)
- Operational audit: 1 year (DORA)

**Incident Classification (DORA Article 18):**

| Severity | Criteria | Response Time | Examples |
|----------|---------|---------------|---------|
| P1 (Critical) | Data loss, financial integrity breach, sanctions screening bypass, system-wide outage | Immediate | Reconciliation amount discrepancy, ledger.events data loss, all instant payments failing |
| P2 (High) | Single service outage affecting payment processing, SLA breach | <30 minutes | A2 instant instance down, p95 latency >7s, A5 scoring unavailable |
| P3 (Medium) | Degraded service, elevated error rate, non-critical feature unavailable | <4 hours | VoP serving from cache (A1 down), batch clearing delayed, consumer lag growing |
| P4 (Low) | Minor anomaly, no customer impact | Next business day | Metric naming inconsistency, single DLQ entry, non-critical alert |

**DORA Regulatory Reporting Timeline (Article 19):**

| Report | Deadline | Content |
|--------|---------|---------|
| Initial notification | Within **4 hours** of classification as major incident, no later than **24 hours** from detection | Incident type, affected services, initial impact assessment |
| Intermediate report | Within **72 hours** of initial notification | Updated impact, root cause hypothesis, remediation status |
| Final report | Within **1 month** after latest intermediate report | Confirmed root cause, full impact analysis, remediation completed, lessons learned |

Reports are submitted to **Finanstilsynet** (Norwegian Financial Supervisory Authority). The architecture supports producing required data: audit trail provides timeline, distributed tracing provides root cause data, SLA metrics provide impact assessment.

---

## 11. Cross-Cutting Concerns — Platform Starter

The Platform Starter (`payment-security-starter`) is a Spring Boot auto-configuration library that enforces uniform behavior across all services.

### 11.1 What the Starter Provides

| Capability | Implementation | Activated By |
|-----------|---------------|-------------|
| **Security defaults** | `anyRequest().authenticated()`, actuator endpoints pre-permitted | `@ConditionalOnWebApplication` |
| **Security headers** | HSTS, X-Content-Type-Options, X-Frame-Options, CSP, Referrer-Policy | Auto-configured, per-header configurable |
| **Audit logging** | `@Audited` annotation (Spring AOP), `AuditLogger` programmatic API | Opt-in per endpoint/method |
| **Audit hash chain** | SHA-256 chaining, Kafka publishing to `audit.events` | Auto-configured |
| **Error handling (REST)** | RFC 9457 Problem Details, global exception handler | Auto-configured |
| **Error handling (Kafka)** | Three-category: retryable / DLQ / DLQ+alert | `PaymentErrorHandler` bean |
| **Distributed tracing** | Micrometer Tracing with Kafka header propagation, correlation ID | Auto-configured |
| **Prometheus metrics** | Custom MeterBinders: Kafka lag, PG pool, payment counters | Auto-configured |
| **Health checks** | PostgreSQL, Kafka broker, Kafka consumer lag | Auto-configured |
| **Graceful shutdown** | Drain in-flight, commit offsets, close connections | Auto-configured |
| **HMAC verification** | Kafka message HMAC verify utility | Utility class, opt-in |
| **Circuit breakers** | Resilience4j circuit breaker with default thresholds for REST calls | Auto-configured, per-call configurable |
| **Key management** | `KeyProvider` interface with deployment-time implementation | Auto-configured |

### 11.2 Gradle Plugin

The companion Gradle plugin (`payment-gradle-plugin`) enforces build-time standards:
- Minimum Java version (17)
- Required dependencies (the Starter itself — ensures no service skips security)
- Banned dependencies (known-vulnerable libraries)
- CycloneDX SBOM generation on every build

### 11.3 Exception Hierarchy

Defined in `payment-domain-model`, mapped by the Starter:

```
PaymentException (abstract)
├── DomainException (business rule violations — HTTP 4xx)
│   ├── InsufficientBalanceException (422)
│   ├── AccountFrozenException (422)
│   ├── SanctionsHitException (422)
│   ├── VoPNoMatchException (422)
│   ├── SCARequiredException (403)
│   ├── DuplicatePaymentException (409)
│   └── PaymentTimeoutException (504)
└── InfrastructureException (system failures — HTTP 503)
    ├── KafkaUnavailableException
    ├── DatabaseUnavailableException
    └── DownstreamServiceException
```

---

## 12. Observability Architecture

### 12.1 Three Pillars

| Pillar | Technology | Scope |
|--------|-----------|-------|
| **Metrics** | Micrometer + Prometheus + Grafana | Per-service: request latency, error rate, Kafka consumer lag. System-wide: `payment.instant.e2e.duration` histogram in A2 |
| **Logging** | SLF4J + Logback + logstash-logback-encoder (structured JSON) | Correlation ID in every log entry. PII redacted (STARTER-F12). Centralized aggregation (Grafana Loki for portfolio). |
| **Tracing** | Micrometer Tracing + Grafana Tempo | Trace context propagated through Kafka headers via Starter auto-configuration. End-to-end trace from A3/A4 through A2, A5, A1. |

### 12.2 SLA Monitoring

A2 publishes a composite metric: `payment.instant.e2e.duration` (histogram) with tags `outcome=SETTLED|REJECTED|TIMEOUT`.

Alert rules:
- p95 > 4.0s: **warning** (approaching 5s execution target)
- p99 > 5.0s: **critical** (breaching execution target)
- Any single transaction > 7.0s: **incident** (hard limit violated)

### 12.3 Standard Metrics (All Services)

Auto-configured by the Starter:
- `http.server.requests` (Spring Boot default) — request count, latency by endpoint
- `kafka.consumer.lag` — consumer group lag per topic/partition
- `db.pool.active` / `db.pool.idle` — PostgreSQL connection pool
- `jvm.memory.used` / `jvm.gc.pause` — JVM health
- `payment.audit.events.published` — audit event counter
- `payment.dlq.depth` — Dead Letter Queue depth per topic

---

## 13. Testing Architecture

### 13.1 Testing Pyramid

| Level | What | Tools | Speed | Scope |
|-------|------|-------|-------|-------|
| **Unit** | Domain logic, algorithms, state machines, validators | JUnit 5, Mockito | <1s/class | Per-class, no I/O |
| **Service integration** | Single service with real Kafka + PostgreSQL | Testcontainers, Spring Boot Test | 10-30s/class | One service end-to-end |
| **Contract** | Inter-service message and API schemas | Pact (REST + Kafka messages), Schema Registry compatibility | 5-10s/class | Two-service boundary |
| **End-to-end** | Full system with scripted payment flows | docker-compose, bash/httpie | 2-5 min | All services |

### 13.2 Key Testing Strategies

**Contract testing (Pact):**
- A2 (consumer) defines Pact contracts for messages on `payment.instructions` — A3, A4 (producers) verify they generate matching messages
- A4 (provider) publishes Pact contracts for AISP/PISP REST APIs — TPP integration test suites can validate against them

**Timing tests:**
- `awaitility` library for async assertions ("within 5 seconds, payment should be SETTLED")
- Injected delays in mock A5 to test timeout scenarios in A2

**Sanctions test data:**
- Synthetic test list (~100 entries) covering: exact match, transliterations, partial names, name order variations, Nordic diacritics
- Production list is EU Consolidated List; test list is synthetic and does not represent real persons

**OAuth2 testing:**
- NAV `mock-oauth2-server` (`no.nav.security:mock-oauth2-server`) for A4 integration tests

### 13.3 Demo Script

`scripts/run-demo.sh` — a shell script calling httpie/curl that:
1. Starts all services via docker-compose (waits for health checks)
2. Seeds test data (accounts, merchant, TPP, test sanctions list)
3. Executes key flows with timing assertions
4. Prints pass/fail summary with elapsed times

### 13.4 Security Testing

**Security regression tests (per service):**
- A6 leads with ~15-20 adversarial tests verifying each security pipeline layer cannot be bypassed (decision C1). Other services should follow the same pattern for their security-critical properties.
- Tests verify: unauthenticated requests rejected, malformed input rejected before processing, rate limits enforced, sensitive data absent from error responses and NO_MATCH VoP responses.

**Dependency vulnerability scanning:**
- CycloneDX SBOM generated on every build (Platform Starter auto-configures).
- Dependency deny-list with CVE references maintained in Starter.
- CI/CD pipeline fails on known-vulnerable dependencies.

**Security testing program (implementation phase):**
- **SAST (Static Application Security Testing):** SpotBugs with FindSecBugs plugin integrated into CI. Catches common Java vulnerabilities (SQL injection, path traversal, deserialization, hardcoded credentials).
- **DAST (Dynamic Application Security Testing):** OWASP ZAP or similar against running service instances in the end-to-end test environment. Covers OWASP API Top 10 automated checks.
- **Penetration testing:** Planned for pre-production. Focus areas: payment flow manipulation, authorization bypass, sanctions screening bypass, VoP enumeration, SSRF via webhooks.
- **Fuzzing:** HTTP endpoint fuzzing with randomized payloads to test input validation robustness. Kafka message fuzzing with malformed Avro to test deserialization safety.

### 13.5 Resilience Testing (DORA Article 26)

A chaos engineering test plan defines 8 specific failure injection experiments for verifying system behavior under real failure conditions. See `reviews/crash_resilience/CRASH_RESILIENCE_CHECKLIST.md` Category R10 for the full plan. Key experiments: kill A2 mid-payment, kill A1 with active reservations, Kafka broker outage, PostgreSQL outage, network partition between services, rolling deployment under load, concurrent A2+A5 crash. Infrastructure: Chaos Mesh (Kubernetes-native) or Toxiproxy. Cadence: per-PR for single-service, weekly for multi-service, quarterly for production (DORA Article 26).

---

## 14. Deployment and Operations

### 14.1 Deployment Strategy

**Rolling deployment** (Kubernetes native) with safeguards:

| Safeguard | Purpose |
|-----------|---------|
| **Cooperative Sticky Assignor** | Minimizes Kafka partition reassignment during rolling updates |
| **Expand-contract DB migrations** | Flyway migration N is backward-compatible with application version N-1 |
| **Readiness probe gating** | New instance's Kafka consumers start only after readiness passes |
| **Startup probe** | Gives application time for projection loading and cache warming |
| **Graceful shutdown** | Complete in-flight instant payments before closing (STARTER-F40-43, RES-10) |

**Rebalance Latency Impact:** With Cooperative Sticky Assignor, only partitions owned by the departing instance are reassigned. The processing pause for affected partitions is typically <5 seconds (Kafka metadata propagation + new assignment). For instant payments on unaffected partitions, there is zero impact. Worst case: one instant payment on an affected partition experiences an additional ~5 seconds of latency during rebalance — still within the 7-second hard limit but close. Monitor `kafka.consumer.rebalance.latency` during rolling deployments.

**Mandatory JVM Flags:**
- `-XX:+ExitOnOutOfMemoryError` — ensures the JVM terminates on OOM rather than entering a partial-zombie state where the liveness probe passes but the application is non-functional. Kubernetes restarts the pod, which is the correct recovery for OOM.
- `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp` — captures heap dump for post-mortem analysis before exit.

**GC Recommendation:**
- **Latency-sensitive services (A2 instant, A5 instant):** Use ZGC (`-XX:+UseZGC`). Sub-millisecond pause times regardless of heap size. Slight throughput overhead (5-10%) is acceptable for payment latency requirements.
- **Throughput-oriented services (A2 batch, A1, A3, A4):** Use G1GC (default) with `-XX:MaxGCPauseMillis=200`. Adequate for services without sub-second latency requirements.
- **A6 (VoP):** G1GC is sufficient — VoP processing is <10ms and GC pauses of 50-100ms are absorbed by the 10ms timing floor + 100ms p95 target.
- **Heap sizing:** `-Xms` = `-Xmx` (avoid heap resizing). Target 2-4GB for most services. Container memory limit = heap × 1.33 (leaving ~25% for native memory, metaspace, thread stacks).

**Production HA Topology (Infrastructure Requirements):**

| Component | Portfolio | Production |
|-----------|-----------|------------|
| Kafka | 3 brokers, single cluster | 3+ brokers, consider cross-AZ. Schema Registry: 2+ instances behind load balancer. |
| PostgreSQL (per service) | Single instance | Primary + streaming replica. Automatic failover (Patroni or cloud-managed). Read replica for A1 query path. |
| Services | 2 replicas per service | 3+ replicas for instant-path services (A2 instant, A5 instant). 2+ for others. |
| Schema Registry | Single instance | 2+ instances (Kafka-backed storage, stateless). |
| Prometheus/Grafana | Single instance | HA pair or cloud-managed. |

### 14.2 Configuration Management

**Split configuration model:**

| Category | Mechanism | Change Process |
|----------|-----------|---------------|
| **Infrastructure** | Environment variables | Redeployment |
| (DB URLs, Kafka bootstrap, topic names, SASL credentials) | | |
| **Operational** | `application.yml` with `@ConfigurationProperties` + `@RefreshScope` | ConfigMap update + `/actuator/refresh` |
| (fraud thresholds, rate limits, VoP TTL, SCA limits, clearing schedule) | | |
| **Structural** | Code | Code change + redeployment |
| (service boundaries, API contracts, Avro schemas, state machine) | | |

All `@ConfigurationProperties` use Bean Validation annotations — the service fails to start if a required parameter is missing or out of range.

Shared parameters that must be consistent across services (e.g., reservation timeout in A1 and A2) are documented in a central `shared-config.md` and referenced in each service's `application.yml`.

**Kubernetes NetworkPolicy Requirements:**

| Service | Ingress Allowed From | Egress Allowed To |
|---------|---------------------|-------------------|
| A1 | A2, A4, A5, A6 (REST); Kafka (consumer) | Kafka (producer); PostgreSQL |
| A2 | Kafka (consumer); Operator dashboard | Kafka (producer); A1 (Kafka); A5 (Kafka); PostgreSQL |
| A3 | DMZ ingress (merchant + consumer APIs) | A6 (REST); Kafka (producer); PostgreSQL; merchant webhook URLs (external) |
| A4 | DMZ ingress (TPP APIs) | A1 (REST); A6 (REST); Kafka (producer); PostgreSQL |
| A5 | Kafka (consumer); Fraud analyst dashboard | Kafka (producer); A1 (REST, PII enrichment); PostgreSQL |
| A6 | DMZ ingress (PSP API); A3, A4 (REST) | A1 (REST); Kafka (producer); PostgreSQL |

All services: deny all ingress/egress not explicitly listed. Prometheus scraping allowed from monitoring namespace.

### 14.3 Project Structure

```
nordic-payment-platform/
├── docker-compose.yml                  # Full system
├── payment-domain-model/               # Shared domain types + exception hierarchy
├── payment-avro-schemas/               # Avro schemas for all Kafka topics
├── payment-iso20022/                   # JAXB from ISO 20022 XSD
├── payment-security-starter/           # Platform Starter (#4)
├── payment-gradle-plugin/              # Build-time enforcement
├── account-ledger/                     # A1
├── clearing-engine/                    # A2 (batch + instant profiles)
├── request-to-pay-service/             # A3
├── psd2-api-gateway/                   # A4
├── fraud-sanctions-engine/             # A5
├── verification-of-payee/              # A6
├── docs/
│   ├── architecture-decisions/         # ADRs
│   ├── api/                            # OpenAPI specs
│   ├── security/                       # Threat model
│   └── iso20022/                       # XSD schemas, sample messages
└── scripts/
    ├── generate-test-data.sh
    └── run-demo.sh
```

---

## 15. Extensibility

### 15.1 Extension Points

The system is extended through three mechanisms:

**1. Adding a new payment scheme** (e.g., SEPA Direct Debit):
- Add a new `schemeType` enum value to the `PaymentInstruction` Avro schema (backward-compatible: optional field with default)
- A2 routes based on `schemeType` to the appropriate processing logic
- New Kafka topic if isolation is needed

**2. Adding a new service** (e.g., reporting, analytics):
- Subscribe to existing Kafka topics (`payment.status-updates`, `ledger.events`, `audit.events`)
- No modification to existing services — Kafka pub/sub extensibility
- Include the Platform Starter for security, metrics, tracing

**3. Adding a new fraud rule:**
- Add a new `VEL-XX` entry in A5's configuration (YAML)
- Rules are parameterized (threshold, window, weight) — no code change
- Configuration change is audited

No plugin API, no runtime class loading, no extension registry. Extensibility comes from Kafka's pub/sub model, Avro schema evolution, and configuration-driven behavior.

### 15.2 Configuration-Driven Behavior

Operational parameters that change without code changes:

| Parameter | Service | Default |
|-----------|---------|---------|
| Fraud velocity rule thresholds | A5 | VEL-01: 10 tx/60 min |
| SCA exemption limits | A4 | Low-value: EUR 30, cumulative: EUR 100 |
| Rate limits per API | A3, A4, A6 | 100 req/min |
| Reservation timeout | A1, A2 | 30 seconds |
| Clearing cycle window | A2 (batch) | 15 minutes |
| VoP cache TTL | A6, A3 | 1 hour (general), 90 days (mandate) |
| Jaro-Winkler thresholds | A6 | MATCH: 0.95, CLOSE: 0.85 |
| Sanctions list poll interval | A5 | 1 hour |

---

## 16. Subsystem Architecture Summaries

Detailed subsystem architectures are in each sub-project's `architecture/architecture_draft.md`. This section summarizes the key architectural characteristics.

### 16.1 A1 — Event-Sourced Account Ledger

**Architecture:** Kafka-first event sourcing with CQRS. Kafka (`ledger.events`) is the source of truth; PostgreSQL holds derived projections.

**Key mechanism:** Balance reservation concurrency is handled by Kafka partition-based per-account serialization — all commands for the same account arrive on the same partition and are processed sequentially by a single consumer thread. The consumer writes to Kafka first (source of truth), then updates the PostgreSQL projection synchronously for the hot path (bypassing consumer lag). `SELECT FOR UPDATE` is used only by the async projection consumer for compound event atomicity, not for reservation concurrency control.

**Crypto-shredding:** Per-account AES-256-GCM keys in A1's database. PII encrypted at event creation time. `[REDACTED]` after key deletion; balances remain correct.

**Recovery:** Database scan for stale reservations (>30s) on startup + Kafka replay from last committed offset.

### 16.2 A2 — Payment Clearing & Settlement Engine

**Architecture:** Two JVM instances (batch + instant) sharing a PostgreSQL database with separate connection pools.

**Batch path:** Spring Batch with restartable job execution (ExecutionContext persisted). Multilateral netting algorithm (pure function, deterministic). Transactional Outbox for Kafka publishing.

**Instant path:** Kafka Streams topology with `exactly_once_v2`. Request-response to A5 via correlation ID stream join. Global 7-second hard-limit timer via `CompletableFuture.orTimeout()`. Orchestrated Saga for compensation.

**Investigation queue:** Dedicated `payment.investigation` topic + operator API for stuck payments.

### 16.3 A3 — Bill Payment & Request-to-Pay Service

**Architecture:** Standard Spring Boot REST service with Transactional Outbox.

**Key mechanism:** VoP check (A6) called synchronously before Kafka publish. Webhook delivery tracked in PostgreSQL with durable retry (exponential backoff, max 5 attempts). Mandate auto-acceptance with `SELECT FOR UPDATE` for concurrency safety.

**Bulk submission:** Accepts NDJSON/CSV, validates per-line, persists all in one transaction, publishes via outbox relay.

### 16.4 A4 — PSD2 Open Banking API Gateway

**Architecture:** Spring Authorization Server for OAuth2/OIDC. Heaviest security surface in the system.

**Key mechanisms:** SCA engine (TOTP + dynamic linking). SCA exemption engine (chain-of-responsibility: low-value, TRA, trusted beneficiary, recurring). Refresh token family-based revocation. JWT key rotation with 30-day overlap. VRP consent with parameter enforcement.

**A1 queries:** Synchronous REST with 2s timeout and 30s Caffeine cache.

### 16.5 A5 — Fraud Detection & Sanctions Engine

**Architecture:** Kafka Streams three-layer pipeline. Single instance, separate consumer groups for batch/instant.

**Sanctions:** Bloom filter → Aho-Corasick → fuzzy match (Jaro-Winkler). Atomic swap via `AtomicReference` on list update. Cannot be skipped.

**Fraud:** Velocity rules (10 configurable rules, Kafka Streams WindowStore) → statistical anomaly (account profile in KeyValueStore) → ML scoring (DJL, graceful fallback).

**PII minimization:** Scoring pipeline uses anonymized IDs only. Case management API enriches from A1 at display time.

### 16.6 A6 — Verification of Payee Service

**Architecture:** Stateless REST service with Caffeine cache. Simplest service in the system.

**Key mechanism:** Name normalization (Unicode NFD, abbreviation expansion, name order permutations) → Jaro-Winkler similarity. Dual-zoned: internal API (mTLS, no rate limit) + external responder API (mTLS/API key, rate limited, anti-enumeration).

**Resilience:** Graceful degradation on A1 unavailability — serve from cache or return ERROR.

### 16.7 Platform Starter (#4)

**Architecture:** Spring Boot auto-configuration library + Gradle plugin. Not a service.

**Key contribution:** Enforces security-by-default, uniform error handling (RFC 9457 + three-category Kafka), distributed tracing (Micrometer), audit hash chain, and operational standards across all services.

**Override pattern:** `@ConditionalOnMissingBean` — services with specific needs provide their own beans; Starter defaults yield.

---

## 17. Architectural Decision Log

Complete analysis with options and tradeoffs is in `ARCHITECTURE_DECISIONS.md`. This table summarizes the final decisions.

| ID | Decision | Choice |
|----|----------|--------|
| A.1a | Trust zone model | Three-zone (External/DMZ/Internal) with HMAC + mTLS internally |
| A.1b | Security default | Deny-all in Platform Starter; services opt-out explicitly |
| A.2a | Key management | Two-tier: `KeyProvider` abstraction. Deployment-time for dev, KMS for prod |
| A.2b | Crypto-shredding keys | Per-account keys in A1 PostgreSQL, encrypted with master key |
| A.3a | Threat modeling | Boundary STRIDE + attack trees for top 3 assets |
| B.1a | State machine | Formalized hybrid: A3/A4 own Phase 1, A2 owns Phase 2, A1+A3/A4 own Phase 3 |
| B.1b | Crash recovery | Kafka replay + database recovery scan on startup |
| B.2a | Dual-write per service | A1: Kafka-first. A2/A3/A4: Outbox. A5 scoring: Kafka Streams. A6: N/A |
| B.2b | Outbox relay | In-process publish after commit; scheduled relay as fallback |
| B.3a | Idempotency | Hierarchical: `transactionId` + deterministic per-service operation IDs |
| B.4a | Investigation queues | Dedicated topic + operator API in A2 |
| C.1a | Saga pattern | Orchestrated Saga for instant, choreographed for batch |
| C.2a | Time budget | Static per-service timeouts + global 7s hard-limit in A2 |
| C.3a | Batch/instant isolation | Separate JVM instances for A2 (Spring profiles), shared DB, separate pools |
| D.1a | Schema evolution | Strict allowed/forbidden rules, Schema Registry enforcement in CI/CD |
| D.2a | API versioning | URL path (`/v1/`), max 2 concurrent versions |
| E.1a | REST errors | RFC 9457 Problem Details, auto-configured by Starter |
| E.1b | Kafka errors | Three-category (retryable / DLQ / DLQ+alert) |
| E.1c | Exceptions | Two-layer: DomainException + InfrastructureException |
| E.2a | Tracing | Micrometer Tracing, Kafka header propagation, Grafana Tempo |
| E.2b | SLA metric | `payment.instant.e2e.duration` in A2, p95/p99 alerts |
| F.1a | Crypto-shredding scope | Encrypt: name, address, description. Plaintext: IBAN, amounts, timestamps |
| F.1b | Post-shredding behavior | `[REDACTED]` for PII; balances correct; VoP returns ACCOUNT_CLOSED |
| F.2a | Audit tamper evidence | Hash chaining (portfolio); + signatures + snapshots (production) |
| G.1a | Testing pyramid | Four levels: unit, service integration, contract (Pact), end-to-end |
| G.2a | Demo script | Shell script with httpie/curl, timing assertions, reproducible seed data |
| H.1a | Deployment | Rolling with Cooperative Sticky Assignor + expand-contract migrations |
| H.2a | Configuration | Env vars for infrastructure, `application.yml` with hot-reload for operational |
| I.1a | Config validation | `@ConfigurationProperties` with Bean Validation, fail-fast on startup |
| I.2a | Extension points | Documented patterns (Kafka pub/sub, schema evolution, config), no plugin API |
| NEW | Circuit breaker strategy | Resilience4j on all synchronous REST calls, auto-configured by Starter, per-call fallback |
| NEW | Kafka partition keys | Per-topic strategy: accountId for ordering-sensitive, transactionId for even distribution |
| NEW | Audit chain continuity | Persist last hash on shutdown, resume on startup, chainEpoch for restart detection |

---

## Appendix A: End-to-End Instant Payment Flow (Complete)

This traces a single instant payment through the entire architecture.

*Note: All interactions between A2↔A1 and A2↔A5 in this diagram flow through Kafka topics (see Section 5.2), not direct REST calls. Arrows are drawn directly for readability. The only synchronous REST calls in the payment flow are A3→A6 (VoP check) and A4→A1 (AISP queries).*

```
 Merchant              A3 R2P          A6 VoP           Kafka
    │                    │                │                │
    │  POST /requests    │                │                │
    │───────────────────→│                │                │
    │  201 Created       │                │                │
    │←───────────────────│                │                │
    │                    │                │                │
 Consumer               │                │                │
    │  PUT /accept       │                │                │
    │  {instant}         │                │                │
    │───────────────────→│                │                │
    │                    │  POST /verify  │                │
    │                    │───────────────→│                │
    │                    │  200 MATCH     │                │
    │                    │←───────────────│                │
    │                    │                                 │
    │                    │  publish PaymentInstruction     │
    │                    │  (vopResult=MATCH)              │
    │                    │────────────────────────────────→│
    │  202 Accepted      │                                 │  payment.instant-instructions
    │←───────────────────│                                 │
    │                                                      │
                                                           │
    A2 Instant         A1 Ledger       A5 Fraud           │
    │  consume          │                │                 │
    │←─────────────────────────────────────────────────────│
    │                   │                │
    │  reserve balance  │                │
    │──────────────────→│                │
    │  reserved OK      │                │
    │←──────────────────│                │
    │                                    │
    │  fraud-check-request              │
    │──────────────────────────────────→│
    │                                    │  sanctions: CLEAR (< 50ms)
    │                                    │  fraud: 0.08 PASS (< 100ms)
    │  fraud-check-response              │
    │←──────────────────────────────────│
    │
    │  verify liquidity: OK
    │  execute settlement
    │
    │  settlement event  │
    │──────────────────→ A1: confirm reservation
    │                    │   debit payer, credit beneficiary
    │
    │  status-update     │
    │──────────────────→ A3: update to SETTLED
    │                    │   → webhook to Merchant (HMAC-signed)
    │
    │  pacs.002 positive confirmation
    │
    │  Total elapsed: ~3-4 seconds
```

## Appendix B: Technology Stack Summary

| Layer | Technology | Version |
|-------|-----------|---------|
| Language | Java | 17+ (LTS) |
| Framework | Spring Boot | 3.2+ |
| Auth server | Spring Authorization Server | 1.2+ |
| Messaging | Apache Kafka | 3.6+ |
| Schema registry | Confluent Schema Registry | 7.5+ |
| Stream processing | Kafka Streams | (bundled with Kafka) |
| Batch processing | Spring Batch | 5.1+ |
| Database | PostgreSQL | 15+ |
| DB migration | Flyway | 9+ |
| Money handling | JSR 354 / Moneta | 1.4+ |
| ISO 20022 XML | JAXB | Jakarta XML Binding 4.0 |
| ML inference | Deep Java Library (DJL) | 0.27+ |
| Sanctions matching | Guava BloomFilter + Aho-Corasick + Apache Commons Text | |
| Fuzzy matching | Apache Commons Text (JaroWinklerSimilarity) | 1.11+ |
| QR codes | ZXing | 3.5+ |
| Rate limiting | Bucket4j | 8+ |
| Circuit breakers | Resilience4j | 2.2+ |
| Caching | Caffeine | 3.1+ |
| JWT/JWE | Nimbus JOSE+JWT | 9+ |
| TOTP | GoogleAuth (or custom RFC 6238) | |
| Metrics | Micrometer + Prometheus | 1.12+ |
| Tracing | Micrometer Tracing + Grafana Tempo | |
| Logging | SLF4J + Logback + logstash-logback-encoder | |
| Build | Gradle (Kotlin DSL) | 8+ |
| Containers | Docker + docker-compose | |
| CI/CD | GitHub Actions | |
| Testing | JUnit 5, Testcontainers, Pact, Mockito, WireMock, ArchUnit | |
| OAuth2 test | NAV mock-oauth2-server | |
