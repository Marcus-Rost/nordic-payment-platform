# A6 — Verification of Payee Service: Architectural Design

*Version 1.0*
*Date: 2026-04-12*
*Based on: requirements.md, ARCHITECTURE_CHALLENGES.md, ARCHITECTURE_DECISIONS.md*
*Master architecture: payment_processing/architecture/ARCHITECTURAL_DESIGN.md*

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Architectural Role in the System](#2-architectural-role-in-the-system)
3. [Security Architecture](#3-security-architecture)
4. [Name Matching Engine](#4-name-matching-engine)
5. [Cache Architecture](#5-cache-architecture)
6. [Resilience Architecture](#6-resilience-architecture)
7. [API Design](#7-api-design)
8. [Data Architecture](#8-data-architecture)
9. [Observability](#9-observability)
10. [Testing Architecture](#10-testing-architecture)
11. [Deployment](#11-deployment)
12. [Production Scaling Path](#12-production-scaling-path)

---

## 1. Introduction

### 1.1 Purpose

This document describes the architectural design of A6, the Verification of Payee (VoP) Service. It translates the functional requirements (VOP-R01 through VOP-S06), non-functional requirements (VOP-NF01 through VOP-NF05), and architectural decisions (C1 through C10) into a structural blueprint for implementation.

### 1.2 Scope

A6 is a **stateless, synchronous REST service** that verifies whether a beneficiary's name matches the account holder at a given IBAN before a payment is executed. It is the simplest service in the master system architecturally — no Kafka consumption in the payment flow, no dual-write problem, no payment state ownership — but it is security-critical because it exposes customer identity data to external parties.

### 1.3 Key Constraints

| Constraint | Source | Impact |
|-----------|--------|--------|
| Responder latency <100ms (p95) | VOP-NF01 | Drives cache architecture, matching algorithm performance |
| Requester end-to-end <500ms | VOP-NF02 | Includes network to external PSP or A1 lookup |
| Throughput ≥2,000 QPS | VOP-NF04 | Trivially met by single instance; multi-instance for availability |
| 99.99% availability | VOP-NF03 | Requires resilience patterns and multi-instance deployment |
| Never reveal actual name on NO_MATCH | VOP-S03 | Structurally enforced in response pipeline |
| EU IPR 2024/886 Article 5c | Regulatory | VoP is mandatory, free, non-blocking, channel-agnostic |
| EPC VoP Scheme (EPC218-23) | Industry | Response vocabulary: MATCH, CLOSE_MATCH, NO_MATCH, NOT_FOUND |

### 1.4 Architectural Principles

These principles guided the design decisions:

1. **Security is architecture, not a feature.** Security properties are structurally enforced and continuously verified, not configured or documented. A VoP service that is functionally correct but architecturally insecure is worse than no VoP at all.

2. **Simple and correct for the portfolio, with a documented production upgrade path.** Where the portfolio scope doesn't require production-grade infrastructure (Redis, Kafka consumers), the simpler option is chosen and the upgrade path is documented.

3. **The matching algorithm reflects the structure of names.** Names are not arbitrary strings — they have components (given name, family name, legal suffix). The algorithm treats them structurally.

4. **Explicit degradation over silent failure.** When dependencies fail, A6 returns a defined degradation response (ERROR/TIMEOUT). The calling service decides whether to proceed. Degradation behavior is deterministic and tested.

---

## 2. Architectural Role in the System

### 2.1 Position in the Payment Flow

A6 is called during **Phase 1** of the payment lifecycle (before clearing). The calling service (A3 or A4) queries A6 synchronously via REST, receives a VoP result, includes it in the payment instruction, and publishes to Kafka for A2.

```
Phase 1 (A3/A4 owns):
  Payer enters beneficiary name + IBAN
    → A3/A4 calls A6: POST /api/v1/vop/verify
    → A6 returns MATCH / CLOSE_MATCH / NO_MATCH / NOT_FOUND / ERROR
    → A3/A4 shows result to payer (confirm, correct, or cancel)
    → A3/A4 includes VoP result in PaymentInstruction Avro message
    → Publishes to payment.instructions or payment.instant-instructions

Phase 2 (A2 owns):
  A2 receives instruction with VoP result already attached
  VoP result forwarded to A5 as fraud scoring input (VOP-I01)
```

A6 does not participate in Phase 2 or Phase 3. It has no state to recover, no compensation to perform, no timeout to manage beyond its own A1 call timeout.

### 2.1.1 Fraud Integration (VOP-I01 through VOP-I03)

A6 does not communicate with A5 (Fraud Engine) directly. The VoP result travels to A5 via the payment instruction:

1. A6 returns the VoP result to A3/A4
2. A3/A4 includes the result (and any payer override) in the `PaymentInstruction` Avro message (`vopResult` field)
3. A2 forwards the VoP result to A5 as part of the fraud-check-request
4. A5 adjusts the fraud score based on VoP result:
   - NO_MATCH + payer override → +0.3 risk (VOP-I02)
   - NOT_FOUND + payer override → +0.4 risk (VOP-I02)
   - CLOSE_MATCH + payer ignores correction → +0.1 risk (VOP-I03)

A6's responsibility ends at returning the result. The fraud score adjustments are A5's domain.

### 2.2 Interaction Patterns

```
                          ┌─────────────────────────────────────┐
  External PSPs ──mTLS──→ │  /api/v1/vop/check  (Responder)    │
                          │                                     │
  A3/A4 ────────mTLS────→ │  /api/v1/vop/verify (Requester)  │
                          │                                     │
                          │         A6: VoP Service             │
                          │                                     │
                          │  ┌────────────┐  ┌──────────────┐  │
                          │  │ Name Cache  │  │ A1 REST      │  │
                          │  │ (Caffeine)  │←─│ Client       │──│──→ A1 Account Ledger
                          │  └────────────┘  └──────────────┘  │    (GET /api/v1/accounts/
                          │                                     │     {iban}/holder-name)
                          │  ┌────────────────────────────┐    │
                          │  │ Audit Producer (Kafka)      │────│──→ audit.events topic
                          │  └────────────────────────────┘    │
                          └─────────────────────────────────────┘
```

**Communication summary:**

| Direction | Protocol | Auth | Rate Limited | Purpose |
|-----------|----------|------|-------------|---------|
| External PSP → A6 | REST (HTTPS) | mTLS or API key | Yes (100/min/PSP) | Responder queries |
| A3/A4 → A6 | REST (HTTPS) | mTLS | No (internal) | Requester verification |
| A6 → A1 | REST (HTTPS) | mTLS | No (internal) | Account holder name lookup |
| A6 → Kafka | Kafka producer | SASL | N/A | Audit events (fire-and-forget) |

### 2.3 What A6 Does NOT Do

- **No Kafka consumption** in the payment flow (TTL-only cache invalidation)
- **No payment state ownership** — VoP result is a field on the payment instruction, not a state transition
- **No dual-write** — audit publishing is fire-and-forget
- **No compensation** — VoP has no side effects to undo
- **No database writes in the critical path** — query log writes are asynchronous

---

## 3. Security Architecture

*Decision reference: C1 (Security-by-Design), C6 (Timing Side-Channel)*

### 3.1 Design Principle: Security as Structure

A6's security is implemented as a **mandatory, ordered filter pipeline**. Every request passes through all layers in sequence. No configuration toggle can disable a layer. No request reaches the matching engine without passing authentication, input validation, rate limiting, and anti-enumeration checks.

This is the "fortress" principle: the architecture makes insecure behavior structurally impossible, not merely policy-prohibited.

### 3.2 Security Pipeline

```
┌──────────────────────────────────────────────────────────────┐
│  Incoming Request                                            │
│                                                              │
│  ┌─ Layer 1: Authentication ──────────────────────────────┐  │
│  │  External: mTLS client certificate or API key          │  │
│  │  Internal: mTLS service certificate                    │  │
│  │  Reject → 401 Unauthorized                             │  │
│  └────────────────────────────────────────────────────────┘  │
│                          │                                    │
│  ┌─ Layer 2: Input Validation ────────────────────────────┐  │
│  │  IBAN: ISO 13616 format validation + checksum          │  │
│  │  Name: non-empty, max 140 chars, no control characters │  │
│  │  EPC charset: SEPA Latin set (EPC217-08) enforced      │  │
│  │  Reject → 400 Bad Request (RFC 9457 problem detail)    │  │
│  └────────────────────────────────────────────────────────┘  │
│                          │                                    │
│  ┌─ Layer 3: Rate Limiting ───────────────────────────────┐  │
│  │  Per-PSP: max 100 queries/minute (Bucket4j)            │  │
│  │  Configurable per PSP (application.yml, hot-reloadable)│  │
│  │  Internal endpoints: no rate limiting                  │  │
│  │  Reject → 429 Too Many Requests                        │  │
│  └────────────────────────────────────────────────────────┘  │
│                          │                                    │
│  ┌─ Layer 4: Anti-Enumeration ────────────────────────────┐  │
│  │  Track: distinct names per IBAN per PSP (sliding 1hr)  │  │
│  │  Threshold: >5 different names → block IBAN for PSP    │  │
│  │  In-memory (Caffeine window)                           │  │
│  │  Reject → 429 with enumeration warning                 │  │
│  └────────────────────────────────────────────────────────┘  │
│                          │                                    │
│  ┌─ Layer 5: Matching Engine ─────────────────────────────┐  │
│  │  (See Section 4: Name Matching Engine)                  │  │
│  └────────────────────────────────────────────────────────┘  │
│                          │                                    │
│  ┌─ Layer 6: Response Filtering ──────────────────────────┐  │
│  │  NO_MATCH / NOT_FOUND: actual_name NEVER included      │  │
│  │  CLOSE_MATCH: actual_name included                     │  │
│  │  MATCH: actual_name optional                           │  │
│  │  Enforced by ResponseBodyAdvice — not configurable     │  │
│  └────────────────────────────────────────────────────────┘  │
│                          │                                    │
│  ┌─ Layer 7: Timing Normalization ────────────────────────┐  │
│  │  Pad response to minimum 10ms                          │  │
│  │  Eliminates timing side-channel for cache-hit queries   │  │
│  └────────────────────────────────────────────────────────┘  │
│                          │                                    │
│  ┌─ Layer 8: Audit Logging ───────────────────────────────┐  │
│  │  Every query: PSP identity, IBAN, result, timestamp    │  │
│  │  Published to Kafka audit.events (fire-and-forget)     │  │
│  │  Written to PostgreSQL query log (async)               │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│  Response                                                    │
└──────────────────────────────────────────────────────────────┘
```

**EPC Character Set Enforcement (EPC217-08):** Name inputs are validated against the SEPA Latin character set defined in EPC217-08. Characters outside this set (e.g., Cyrillic, CJK, emoji) are rejected with 400 Bad Request. This prevents processing of names that cannot be represented in SEPA payment messages and aligns with VoP scheme interoperability requirements.

### 3.3 Dual-Zone Deployment

A6 serves two API surfaces with different trust profiles from a **single deployment**. Both surfaces live under `/api/v1/` — they are differentiated by **authentication mechanism** and **Kubernetes NetworkPolicy**, not by URL prefix:

| Aspect | External (Responder) | Internal (Requester) |
|--------|---------------------|---------------------|
| Endpoint | `POST /api/v1/vop/check` | `POST /api/v1/vop/verify` |
| Authentication | mTLS client cert or API key | mTLS service cert |
| Rate limiting | Yes (100/min/PSP) | No |
| Anti-enumeration | Yes | No |
| Network isolation | Public-facing (DMZ ingress) | Kubernetes NetworkPolicy: A3/A4 only |
| Matching engine | Shared | Shared |
| Cache | Shared | Shared |

Both endpoints use the same matching engine, cache, and A1 client. The difference is in the security pipeline: the internal endpoint skips layers 3 and 4 (rate limiting and anti-enumeration) because internal traffic is trusted. The caller type is identified by the presented credential (service cert vs client cert/API key), and network-level isolation is enforced by Kubernetes NetworkPolicy — only A3/A4 pods can reach the requester endpoint.

Implementation: Two Spring Security `SecurityFilterChain` beans, ordered by `@Order`, selecting chain by authentication credential type.

**External PSP API Key Lifecycle:** PSP API keys are generated as 256-bit cryptographically random values via `SecureRandom`. Keys are stored as SHA-256 hashes in a PSP registry table. Key rotation: PSPs can request a new key via admin API; old key remains valid for 24-hour overlap. Key revocation: immediate via admin API. Key expiry: configurable (default 365 days). All key lifecycle events are audited.

### 3.4 Runtime Security Verification

A dedicated test suite (~15-20 tests) continuously verifies security properties in CI:

| Test | Verifies |
|------|---------|
| Request without certificate → 401 | Layer 1 rejects unauthenticated |
| Request with invalid API key → 401 | Layer 1 validates credentials |
| Malformed IBAN (wrong checksum) → 400 | Layer 2 rejects before matching |
| IBAN with SQL injection payload → 400 | Layer 2 blocks injection |
| Name with >140 characters → 400 | Layer 2 enforces constraints |
| Name with Cyrillic/CJK/emoji characters → 400 | Layer 2 EPC charset enforcement |
| 101st request in 60s from same PSP → 429 | Layer 3 enforces rate limit |
| 6th different name for same IBAN → 429 | Layer 4 detects enumeration |
| NO_MATCH response body → `actual_name` absent | Layer 6 never leaks |
| NOT_FOUND response body → `actual_name` absent | Layer 6 never leaks |
| MATCH timing vs NO_MATCH timing → <2ms difference | Layer 7 normalizes timing |
| Internal endpoint from non-A3/A4 pod → rejected | NetworkPolicy enforced |

### 3.5 Threat Model Traceability (Master A.3a)

| Interface | Threat | Control | Reference |
|-----------|--------|---------|-----------|
| External responder API | Spoofing | mTLS or API key authentication (Layer 1) | C1 pipeline |
| External responder API | Information Disclosure | Response filtering — actual name NEVER on NO_MATCH (Layer 6) | C1, VOP-S03 |
| External responder API | Information Disclosure (timing) | 10ms response time floor eliminates timing side-channel (Layer 7) | C6 |
| External responder API | Denial of Service | Rate limiting 100/min per PSP (Layer 3) | VOP-E01 |
| External responder API | Denial of Service (enumeration) | Anti-enumeration detection >5 names/IBAN/PSP/hour (Layer 4) | VOP-E02 |
| External responder API | Repudiation | Every query logged with PSP identity, IBAN, result (Layer 8) | VOP-S04 |
| A1 REST integration | Spoofing | mTLS with service certificate | A.1a |
| A1 REST integration | Denial of Service | Circuit breaker + bulkhead (Resilience4j) | C4 |
| Name cache | Information Disclosure | Cache contains holder names (PII) — in-memory only, lost on restart | C3 |
| IBAN input | Injection | ISO 13616 format validation before any processing (Layer 2) | VOP-S06 |

### Security Requirements Traceability

| Requirement | Description | Architectural Element |
|------------|-------------|----------------------|
| VOP-S01 | Authenticated PSPs only | mTLS or API key — Layer 1 of security pipeline |
| VOP-S02 | Anti-enumeration | Rate limiting (Layer 3) + repeated-query detection (Layer 4) |
| VOP-S03 | Never reveal actual name on NO_MATCH | ResponseBodyAdvice — structural, not configurable (Layer 6) |
| VOP-S04 | Audit every query | Every query logged with PSP identity, IBAN, result, timestamp (Layer 8) |
| VOP-S05 | Cache invalidation on name change | TTL-only (1-hour) for portfolio; event-driven for production (documented deviation) |
| VOP-S06 | IBAN validation before lookup | ISO 13616 format + checksum validation (Layer 2) |

---

## 4. Name Matching Engine

*Decision reference: C2 (Name Matching Algorithm)*

### 4.1 Design Principle: Names Have Structure

Names are not arbitrary strings. They have components: given name, family name, compound prefixes (van, der, von), and legal entity suffixes (AS, GmbH, Ltd). The matching algorithm treats them structurally — decomposing before comparing, rather than comparing the raw concatenation.

### 4.2 Pipeline Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Name Matching Engine                                       │
│                                                             │
│  Input: stated_name, actual_name (from cache or A1)        │
│                                                             │
│  ┌─ Step 1: Input Normalization ────────────────────────┐  │
│  │                                                      │  │
│  │  Unicode NFD decomposition → strip combining marks   │  │
│  │  EPC288-23 equivalences:                             │  │
│  │    ö→o (also oe→o), ä→a (also ae→a), å→a (also aa→a)│  │
│  │    ø→o, æ→ae, ü→u, é→e                              │  │
│  │  Lowercase                                           │  │
│  │  Collapse whitespace, trim                           │  │
│  │  Remove non-alphabetic except digits                 │  │
│  │                                                      │  │
│  │  "Bjørn Ødegård AS" → "bjorn odegard as"            │  │
│  └──────────────────────────────────────────────────────┘  │
│                          │                                  │
│  ┌─ Step 2: Structural Decomposition ───────────────────┐  │
│  │                                                      │  │
│  │  Detect legal entity suffix:                         │  │
│  │    Pattern match against: AS, ASA, Ltd, Limited,     │  │
│  │    GmbH, AB, SA, NV, BV, AG, SE, ...               │  │
│  │                                                      │  │
│  │  If found:                                           │  │
│  │    base = name without suffix                        │  │
│  │    suffix = normalized expansion                     │  │
│  │    "bjorn odegard as"                                │  │
│  │      → base: "bjorn odegard"                         │  │
│  │      → suffix: "aksjeselskap"                        │  │
│  │                                                      │  │
│  │  If not found:                                       │  │
│  │    base = full name, suffix = null (person name)     │  │
│  └──────────────────────────────────────────────────────┘  │
│                          │                                  │
│  ┌─ Step 3: Tokenization ──────────────────────────────┐   │
│  │                                                      │  │
│  │  Split base name on whitespace                       │  │
│  │  Identify compound prefixes:                         │  │
│  │    "van", "der", "de", "von", "af"                  │  │
│  │    → keep with following token                       │  │
│  │                                                      │  │
│  │  "jan erik van der berg"                              │  │
│  │    → ["jan", "erik", "van der berg"]                  │  │
│  └──────────────────────────────────────────────────────┘  │
│                          │                                  │
│  ┌─ Step 4: Best-Permutation Jaro-Winkler ─────────────┐  │
│  │                                                      │  │
│  │  Generate permutations of tokens from name A         │  │
│  │  (capped at 5 tokens = 120 permutations max)         │  │
│  │                                                      │  │
│  │  For each permutation:                               │  │
│  │    Join tokens → compute Jaro-Winkler against name B │  │
│  │                                                      │  │
│  │  base_score = max(all permutation scores)            │  │
│  │                                                      │  │
│  │  "jan erik van der berg" vs "van der berg jan erik"   │  │
│  │    Permutation ["van der berg", "jan", "erik"]        │  │
│  │    → "van der berg jan erik" vs "van der berg jan     │  │
│  │       erik" → score 1.0                              │  │
│  └──────────────────────────────────────────────────────┘  │
│                          │                                  │
│  ┌─ Step 5: Score Combination ─────────────────────────┐   │
│  │                                                      │  │
│  │  If legal entity (both names have suffix):           │  │
│  │    suffix_match = (suffixA == suffixB) after expand   │  │
│  │    If suffix_match: final_score = base_score          │  │
│  │    If !suffix_match: final_score = base_score * 0.9   │  │
│  │                                                      │  │
│  │  If legal entity (one has suffix, one doesn't):      │  │
│  │    final_score = base_score * 0.95                    │  │
│  │    (penalize missing suffix slightly)                 │  │
│  │                                                      │  │
│  │  If person name (no suffix):                          │  │
│  │    final_score = base_score                           │  │
│  └──────────────────────────────────────────────────────┘  │
│                          │                                  │
│  ┌─ Step 6: Threshold Application ─────────────────────┐   │
│  │                                                      │  │
│  │  final_score ≥ 0.95  →  MATCH                        │  │
│  │  final_score ≥ 0.85  →  CLOSE_MATCH                  │  │
│  │  final_score <  0.85  →  NO_MATCH                     │  │
│  │                                                      │  │
│  │  Thresholds are configurable (application.yml)       │  │
│  │  Hot-reloadable without restart                      │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### 4.3 Performance Characteristics

| Scenario | Operations | Time |
|----------|-----------|------|
| Cache hit, 2-token name | Normalize + decompose + 2 permutations × JW | <1ms |
| Cache hit, 3-token name | Normalize + decompose + 6 permutations × JW | <1ms |
| Cache hit, 5-token name (worst case) | Normalize + decompose + 120 permutations × JW | ~12ms |
| Cache miss | Above + A1 REST call (200ms timeout) | ~200ms |

The 5-token worst case (~12ms) is well within the 100ms p95 target. Names with >5 tokens are extremely rare and are handled by truncating to the 5 most significant tokens.

### 4.4 Technology

| Component | Library | Purpose |
|-----------|---------|---------|
| Jaro-Winkler | Apache Commons Text `JaroWinklerSimilarity` | Primary similarity metric |
| Unicode normalization | `java.text.Normalizer` (NFD) | Diacritics decomposition |
| EPC equivalences | Custom mapping (small lookup table) | ö→o, å→a, etc. |
| Suffix detection | Regex pattern against known suffixes | Legal entity detection |
| Tokenization | Custom (whitespace split + compound prefix handling) | Name decomposition |

### 4.5 Test Examples

| Stated Name | Account Holder | Step | Score | Result |
|---|---|---|---|---|
| Ola Nordmann | Ola Nordmann | Exact after normalize | 1.0 | MATCH |
| Bjørn Nilsen | Bjorn Nilsen | Normalize: bjorn nilsen = bjorn nilsen | 1.0 | MATCH |
| Nordmann, Ola | Ola Nordmann | Permutation: ola nordmann vs ola nordmann | 1.0 | MATCH |
| Ola Nordman | Ola Nordmann | JW: ola nordman vs ola nordmann | 0.97 | MATCH |
| Ola Nordmann | Ola Nordmann AS | Decompose: base "ola nordmann" matches, missing suffix penalty | 0.95 | MATCH |
| Grønn Energi AS | Gronn Energi Aksjeselskap | Normalize+expand: bases match, suffixes match | 1.0 | MATCH |
| Jan Erik van der Berg | van der Berg, Jan Erik | Permutation match | 1.0 | MATCH |
| Ola Nordmann | Kari Hansen | JW: completely different | 0.32 | NO_MATCH |

---

## 5. Cache Architecture

*Decision reference: C3a (Invalidation), C3b (Cold Start), C3c (Sizing)*

### 5.1 Design

```
┌───────────────────────────────────────────────────┐
│  Name Cache (Caffeine)                            │
│                                                   │
│  Key:   IBAN (String)                             │
│  Value: CacheEntry {                              │
│           holderName: String,                     │
│           normalizedName: String,  (pre-computed)  │
│           holderNames: List<String>, (joint acct) │
│           lastUpdated: Instant                    │
│         }                                         │
│                                                   │
│  Eviction: W-TinyLFU (Caffeine default)           │
│  TTL: 1 hour (expireAfterWrite)                   │
│  Max entries: 100,000 (configurable)              │
│  Target hit rate: >95%                            │
│                                                   │
│  Pre-computed normalized name eliminates           │
│  redundant normalization on repeated queries.      │
└───────────────────────────────────────────────────┘
```

### 5.2 Lookup Flow

```
Query arrives with IBAN
  │
  ├─ Cache HIT → return cached holder name(s)
  │              Matching engine runs immediately
  │              Response in <5ms (+ 10ms timing pad)
  │
  └─ Cache MISS
       │
       ├─ Circuit CLOSED → call A1 REST API
       │    │                GET /api/v1/accounts/{accountId}/holder-name
       │    │                Timeout: 200ms
       │    │
       │    ├─ A1 returns name → populate cache, run matching
       │    ├─ A1 returns 404 → NOT_FOUND (IBAN doesn't exist)
       │    └─ A1 timeout/error → ERROR (caller proceeds with VOP_TIMEOUT)
       │
       └─ Circuit OPEN → ERROR immediately (fail fast)
                         Caller proceeds with VOP_TIMEOUT flag
```

### 5.3 Invalidation

**Portfolio:** TTL-only. Entries expire after 1 hour. No active invalidation. Name changes (rare) cause at most 1 hour of stale data — the consequence is friction (unnecessary warning), not failure.

**Note — conscious deviation from VOP-S05:** The requirements specify "cache invalidated immediately on account holder name change." The TTL-only decision accepts up to 1 hour of staleness for architectural simplicity. This is a deliberate tradeoff documented in decision C3a — the production upgrade (event-driven invalidation) satisfies VOP-S05 fully.

**Production upgrade:** A6 consumes name-change events from A1 and invalidates specific cache entries. TTL remains as safety net. This satisfies VOP-S05.

### 5.4 Cold Start / Cache Warming

On startup, before the readiness probe passes:

1. Load hot-IBAN list (top 5,000 most-queried IBANs, from periodic analytics)
2. Batch-fetch holder names from A1
3. Populate cache
4. Pass readiness probe → start accepting traffic

No traffic arrives on an empty cache. The readiness probe gating is provided by the Platform Starter (STARTER-F32).

### 5.5 Mandate Cache (VOP-RES02)

For recurring mandate payments (AvtaleGiro), A3 may request a cached VoP check for a specific mandate+beneficiary combination. This uses a separate cache partition with a 90-day TTL, reducing load for predictable recurring payments.

Re-verification is required if:
- 90 days have elapsed since the last check
- A1 notifies of account holder name change (production: event-driven, portfolio: TTL expiry)
- The mandate is modified (A3 sends a new verification request)

Implementation: a second Caffeine cache instance with `expireAfterWrite(90, DAYS)`, keyed by `mandateId + IBAN`. This is a Should-priority requirement (VOP-RES02) — the 1-hour general cache already covers most mandate queries. The 90-day cache is an optimization for high-volume recurring payments.

### 5.6 Metrics

| Metric | Tags | Purpose |
|--------|------|---------|
| `vop.cache.hit.rate` | — | Monitor cache effectiveness. Alert if <95% |
| `vop.cache.size` | — | Current entry count |
| `vop.cache.evictions` | — | Eviction rate (indicates sizing pressure) |

---

## 6. Resilience Architecture

*Decision reference: C4 (Circuit Breaker + Bulkhead)*

### 6.1 A1 Dependency Protection

```
┌─────────────────────────────────────────────────┐
│  A1 REST Client                                 │
│                                                 │
│  ┌─ Bulkhead (Semaphore) ───────────────────┐  │
│  │  Max concurrent calls: 20                │  │
│  │  Prevents thread pool exhaustion          │  │
│  └──────────────────────────────────────────┘  │
│                    │                            │
│  ┌─ Circuit Breaker ────────────────────────┐  │
│  │  Failure rate threshold: 50%             │  │
│  │  Sliding window: 10 requests             │  │
│  │  Wait in open state: 30 seconds          │  │
│  │  Half-open: permit 1 probe request       │  │
│  │                                          │  │
│  │  CLOSED → normal operation               │  │
│  │  OPEN → fail fast (skip A1, use fallback)│  │
│  │  HALF_OPEN → try one request             │  │
│  └──────────────────────────────────────────┘  │
│                    │                            │
│  ┌─ HTTP Client ────────────────────────────┐  │
│  │  Timeout: 200ms                          │  │
│  │  Connection pool: keep-alive, reuse      │  │
│  │  mTLS authentication                     │  │
│  └──────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
```

### 6.2 Fallback Hierarchy

| A1 Status | Cache | A6 Response | Caller Behavior |
|-----------|-------|-------------|----------------|
| Healthy | Hit | MATCH / CLOSE_MATCH / NO_MATCH | Normal payment flow |
| Healthy | Miss | MATCH / CLOSE_MATCH / NO_MATCH / NOT_FOUND | Normal, cache populated |
| Down (circuit open) | Hit | MATCH / CLOSE_MATCH / NO_MATCH (from stale cache) | Normal — cache data almost always correct |
| Down (circuit open) | Miss | ERROR | Caller proceeds with VOP_TIMEOUT flag. Payment not blocked (VOP-R06) |
| Slow (within timeout) | Miss | Normal but slower (~200ms) | Normal, bulkhead limits impact |
| Slow (exceeds timeout) | Miss | ERROR (timeout) | Same as circuit-open + miss |

### 6.3 Crash Recovery

A6 crash loses three things:

| State Lost | Impact | Recovery |
|-----------|--------|----------|
| Name cache (100K entries) | Latency spike, A1 load surge | Pre-warm top-N on restart (Section 5.4) |
| Anti-enumeration counters | Brief detection gap | Accept window; audit log provides post-hoc detection |
| In-flight requests | Callers get connection error | Callers retry; A6 is naturally idempotent |

Recovery is fast: restart pod → pre-warm cache → pass readiness probe → accept traffic. No database recovery scan needed (A6 has no in-flight payment state). No Kafka offset replay needed (A6 has no consumers).

---

## 7. API Design

*Decision reference: C10 (Response Edge Cases)*

### 7.1 Internal Requester API

Called by A3/A4 before submitting a payment to clearing.

```
POST /api/v1/vop/verify
Content-Type: application/json
Authorization: (mTLS service certificate)

{
  "beneficiary_iban": "NO9386011117947",
  "stated_name": "Ola Nordmann",
  "request_id": "550e8400-e29b-41d4-a716-446655440000"
}

→ 200 OK
{
  "request_id": "550e8400-e29b-41d4-a716-446655440000",
  "result": "CLOSE_MATCH",
  "actual_name": "Ola Nordmann AS",
  "similarity_score": 0.95,
  "response_time_ms": 12
}
```

**Response fields by result:**

| Result | `actual_name` | `similarity_score` |
|--------|--------------|-------------------|
| MATCH | Optional (included if available) | Yes |
| CLOSE_MATCH | Always included | Yes |
| NO_MATCH | **Never included** | Yes |
| NOT_FOUND | Never included | No |
| ACCOUNT_CLOSED | Never included (mapped to NOT_FOUND) | No |
| ERROR | Never included | No |
| TIMEOUT | Never included | No |

### 7.2 External Responder API

Called by external PSPs to verify their customers' payments to our accounts.

```
POST /api/v1/vop/check
Content-Type: application/json
X-API-Key: {psp-api-key}
(or mTLS client certificate)

{
  "iban": "NO9386011117947",
  "name": "Ola Nordmann"
}

→ 200 OK
{
  "result": "MATCH"
}
```

**External response vocabulary (EPC-aligned):**

| Internal Result | External Result | Response Body |
|----------------|----------------|---------------|
| MATCH | MATCH | `{"result": "MATCH"}` |
| CLOSE_MATCH | CLOSE_MATCH | `{"result": "CLOSE_MATCH", "name_suggestion": "Ola Nordmann AS"}` |
| NO_MATCH | NO_MATCH | `{"result": "NO_MATCH"}` |
| NOT_FOUND | NOT_FOUND | `{"result": "NOT_FOUND"}` |
| ACCOUNT_CLOSED | NOT_FOUND | `{"result": "NOT_FOUND"}` |
| TIMEOUT | ERROR | `{"result": "ERROR"}` |
| ERROR | ERROR | `{"result": "ERROR"}` |

Note: `ACCOUNT_CLOSED` and `TIMEOUT` are never visible in the external API — both map to standard types (`NOT_FOUND` and `ERROR` respectively) to prevent information leakage.

### 7.3 Error Responses

All errors follow RFC 9457 (Problem Details for HTTP APIs), auto-configured by the Platform Starter:

```json
{
  "type": "https://api.nordpay.example/errors/rate-limited",
  "title": "Rate Limit Exceeded",
  "status": 429,
  "detail": "PSP psp-12345 exceeded 100 queries/minute",
  "correlationId": "req-7f3a8b2c-4d1e-4f5a-8c9d-2e1f3a4b5c6d",
  "timestamp": "2026-04-12T14:32:00.000Z"
}
```

---

## 8. Data Architecture

*Decision reference: C8 (GDPR)*

### 8.1 Data Stores

A6 uses three data stores, each for a different purpose:

| Store | Technology | Data | Lifetime |
|-------|-----------|------|----------|
| Name cache | Caffeine (in-memory) | IBAN → holder name(s) | 1-hour TTL, lost on restart |
| Anti-enumeration counters | Caffeine (in-memory) | (PSP, IBAN) → distinct name count | 1-hour sliding window, lost on restart |
| Query log | PostgreSQL | VoP query audit trail | 5 years (AML), anonymized on account deletion |

### 8.2 Query Log Schema (PostgreSQL)

```sql
CREATE TABLE vop_query_log (
    query_id            UUID PRIMARY KEY,
    direction           VARCHAR(20) NOT NULL,  -- OUTBOUND_REQUEST, INBOUND_QUERY
    querying_psp_id     VARCHAR(50) NOT NULL,
    iban                VARCHAR(34) NOT NULL,   -- anonymized to hash on GDPR erasure
    stated_name         VARCHAR(140) NOT NULL,  -- anonymized to [REDACTED] on erasure
    actual_name         VARCHAR(140),           -- only for MATCH/CLOSE_MATCH; anonymized on erasure
    result              VARCHAR(20) NOT NULL,   -- internal result type
    similarity_score    DOUBLE PRECISION,
    response_time_ms    BIGINT NOT NULL,
    payer_override      BOOLEAN,                -- only for outbound: payer proceeded despite warning
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_vop_query_log_iban ON vop_query_log (iban);
CREATE INDEX idx_vop_query_log_psp ON vop_query_log (querying_psp_id, created_at);
CREATE INDEX idx_vop_query_log_created ON vop_query_log (created_at);
```

### 8.3 GDPR Anonymization

When an account is deleted in A1 and its PII crypto-shredded:

```sql
UPDATE vop_query_log
SET iban = encode(sha256(iban::bytea), 'hex'),
    stated_name = '[REDACTED]',
    actual_name = '[REDACTED]'
WHERE iban = :deleted_iban;
```

The audit trail structure remains intact: timestamps, PSP IDs, match results, and response times are preserved. Anonymized records are no longer personal data under GDPR, so 5-year AML retention is compliant.

### 8.4 Query Log Writing

Query log writes are **asynchronous** — they do not block the VoP response. Implementation: Spring `@Async` with a dedicated thread pool, or batch inserts on a short interval (e.g., every 100ms). If the database is temporarily unavailable, log entries are buffered in memory (bounded queue) and flushed on recovery.

This ensures that database issues never affect VoP query latency.

---

## 9. Observability

### 9.1 Metrics

| Metric | Type | Tags | Purpose |
|--------|------|------|---------|
| `vop.query.duration` | Histogram | `direction`, `cache`, `result` | Latency distribution |
| `vop.query.count` | Counter | `direction`, `result` | Query volume and result distribution |
| `vop.cache.hit.rate` | Gauge | — | Cache effectiveness (alert <95%) |
| `vop.cache.size` | Gauge | — | Current cache size |
| `vop.a1.circuit.state` | Gauge | — | Circuit breaker state (0=closed, 1=open, 2=half-open) |
| `vop.a1.call.duration` | Histogram | `outcome` | A1 REST call latency |
| `vop.enumeration.blocked` | Counter | `psp_id` | Enumeration detection triggers |
| `vop.rate.limited` | Counter | `psp_id` | Rate limit triggers |

All metrics are auto-exported to Prometheus via Micrometer (Platform Starter).

### 9.2 Distributed Tracing

A6 participates in distributed traces via Micrometer Tracing (master decision E.2a):
- Incoming REST requests carry trace context in HTTP headers
- A1 REST calls propagate trace context
- Kafka audit event publishing carries trace context in Kafka headers

The trace shows: A3/A4 → A6 → A1 (on cache miss), with timing for each hop.

### 9.3 Alerting

| Condition | Severity | Action |
|-----------|----------|--------|
| `vop.cache.hit.rate` < 95% for 5 min | Warning | Investigate cache sizing or query pattern change |
| `vop.a1.circuit.state` == 1 (open) | Warning | A1 is unavailable — A6 degraded |
| `vop.query.duration` p95 > 80ms | Warning | Approaching SLA limit |
| `vop.query.duration` p95 > 100ms | Critical | SLA violated |
| `vop.enumeration.blocked` > 10/hour | Info | Enumeration attempts detected |

### 9.4 Audit Hash Chain

Audit events published to `audit.events` use the Platform Starter's tamper-evident hash chain mechanism (per-instance SHA-256 chain with crash recovery). See Platform Starter ARCHITECTURE_DESIGN.md Section 5.5 and master decision F.2a.

---

## 10. Testing Architecture

*Decision reference: C9 (Testing Strategy)*

### 10.1 Testing Pyramid

| Level | What | Tools | Coverage |
|-------|------|-------|----------|
| **Unit** | Matching algorithm, normalization, decomposition, threshold logic | JUnit 5, jqwik | All matching logic without Spring context |
| **Property-based** | Algorithm invariants (symmetry, idempotency, boundedness) | jqwik | Thousands of generated name pairs |
| **Security** | Pipeline bypass verification (Section 3.4) | Spring Boot Test, MockMvc | ~15-20 adversarial tests |
| **Service integration** | Full REST flow with Caffeine cache + mocked A1 | Testcontainers (PostgreSQL), WireMock (A1) | Happy path + degradation scenarios |
| **Contract** | Responder API contract with external PSPs | Pact | API schema + response vocabulary |

**Pact contracts:** A6 runs Pact provider verification for the VoP verify contract consumed by A3 and A4. A6 also defines a Pact consumer contract for the A1 holder-name REST API (`GET /api/v1/accounts/{accountId}/holder-name`). Both contracts run in CI.

### 10.2 Curated Test Corpus (~100 cases)

Categories:
- **Nordic diacritics:** Bjørn/Bjorn, Åse/Ase, Ødegård/Odegard, Grønn/Gronn
- **Name order:** Nordmann, Ola / Ola Nordmann
- **Compound prefixes:** van der Berg, von Müller, de la Cruz
- **Legal entities:** AS/Aksjeselskap, ASA/Allmennaksjeselskap, GmbH, Ltd, AB
- **Mixed:** Grønn Energi AS / Gronn Energi Aksjeselskap
- **Adversarial near-misses:** similar but different names that should be NO_MATCH
- **Unicode edge cases:** combining characters, zero-width characters, homoglyphs
- **Empty/minimal input:** single character names, single token names

### 10.3 Property-Based Tests (jqwik)

```
Property: score(A, B) == score(B, A)                    — symmetric
Property: normalize(normalize(x)) == normalize(x)        — idempotent
Property: score(x, x) == 1.0                             — identity
Property: score ∈ [0.0, 1.0]                              — bounded
Property: diacritics change score by <0.05 after normalize — normalization effective
```

---

## 11. Deployment

### 11.1 Single Service Deployment

```
┌─────────────────────────────────────────┐
│  Kubernetes Pod: a6-vop-service         │
│                                         │
│  Container: Spring Boot 3.x             │
│  JVM: Java 17+                          │
│  Port: 8096 (app), 9096 (management)    │
│  Health: /actuator/health               │
│  Readiness: /actuator/readiness         │
│  Startup probe: 60s timeout             │
│                                         │
│  Resources:                             │
│    CPU: 500m request, 1000m limit       │
│    Memory: 512Mi request, 1Gi limit     │
│    (100K cache entries ≈ 50-100MB)      │
│                                         │
│  Replicas: 2 (availability)             │
│  Strategy: RollingUpdate                │
│  NetworkPolicy:                         │
│    /api/v1/vop/verify → A3, A4 only     │
│    /api/v1/vop/check → DMZ ingress      │
│    /actuator/* → monitoring namespace   │
└─────────────────────────────────────────┘
```

### 11.2 Dependencies

```
A6 depends on:
  ├── A1 Account Ledger (REST, runtime)
  │     Used for: account holder name lookup on cache miss
  │     Failure mode: circuit breaker → serve from cache or ERROR
  │
  ├── Kafka (producer, runtime)
  │     Used for: audit event publishing (fire-and-forget)
  │     Failure mode: audit events buffered, no impact on VoP queries
  │
  ├── PostgreSQL (async writes, runtime)
  │     Used for: query log persistence
  │     Failure mode: log entries buffered, no impact on VoP queries
  │
  └── Platform Starter (compile-time)
        Used for: security headers, audit logging, metrics, tracing, health checks
```

A6 starts independently (master OPS-03). It does not require A1 to be available at startup — the cache warming (Section 5.4) is best-effort. If A1 is down at startup, A6 starts with an empty cache and warms organically.

### 11.3 Configuration

```yaml
# application.yml — operational parameters (hot-reloadable)

vop:
  matching:
    match-threshold: 0.95
    close-match-threshold: 0.85
    max-permutation-tokens: 5
  cache:
    max-entries: 100000
    ttl: PT1H                    # 1 hour
    warm-on-startup: true
    warm-list-size: 5000
  rate-limiting:
    default-queries-per-minute: 100
  anti-enumeration:
    max-names-per-iban-per-psp: 5
    window: PT1H                 # 1 hour
  timing:
    min-response-ms: 10
  a1-client:
    timeout: 200ms
    base-url: ${A1_BASE_URL}     # env var (infrastructure)

# Resilience4j configuration
resilience4j:
  circuitbreaker:
    instances:
      a1-lookup:
        failure-rate-threshold: 50
        sliding-window-size: 10
        wait-duration-in-open-state: 30s
  bulkhead:
    instances:
      a1-lookup:
        max-concurrent-calls: 20
```

---

## 12. Production Scaling Path

The portfolio architecture is designed for correctness at moderate scale (1-2 instances, single bank scope). Production deployment at scale requires specific upgrades, documented here as a coherent roadmap.

### 12.1 Trigger: Scaling Beyond 2 Instances

When scaling to 3+ instances, add **Redis** for shared state:

| Concern | Portfolio (1-2 instances) | Production (3+ instances) |
|---------|--------------------------|--------------------------|
| Cache L2 | None (each instance independent) | Redis as shared L2 cache — eliminates redundant A1 calls |
| Anti-enumeration | Per-instance counters | Redis atomic counters — accurate across all instances |
| Cold start | Pre-warm from A1 | L2 cache survives restarts — no cold start |

This is a **single infrastructure addition** (Redis) that addresses three concerns simultaneously.

### 12.2 Trigger: Name Change Latency Unacceptable

When the 1-hour staleness window for name changes becomes unacceptable:

| Concern | Portfolio | Production |
|---------|-----------|-----------|
| Cache invalidation | TTL-only (1 hour) | Event-driven: consume name-change events from A1 + TTL safety net |

This adds a Kafka consumer to A6. The matching engine, cache structure, and API remain unchanged.

### 12.3 Trigger: EPC VoP Scheme Alignment

When the EPC VoP scheme finalizes technical specifications for inter-PSP routing:

| Concern | Portfolio | Production |
|---------|-----------|-----------|
| Requester routing | Direct A1 lookup (single-bank) | Route via EPC Directory Service (EDS) to external PSPs |
| Response format | Our vocabulary | ISO 20022 message elements per EPC specification |
| Authentication | mTLS / API key | eIDAS QWAC PSD2 certificates (mandated by EPC) |

The architectural separation between API layer and matching engine supports this — the matching logic is identical regardless of how queries are routed.

---

## Appendix A: Component Summary

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Framework | Spring Boot 3.x | Application framework |
| Security | Spring Security (dual filter chain) | Authentication, authorization |
| Matching | Apache Commons Text (Jaro-Winkler) | String similarity |
| Normalization | java.text.Normalizer + custom EPC288-23 rules | Diacritics handling |
| Cache | Caffeine (W-TinyLFU) | In-memory name cache |
| Rate limiting | Bucket4j | Per-PSP rate enforcement |
| Resilience | Resilience4j (CircuitBreaker, Bulkhead) | A1 dependency protection |
| API documentation | OpenAPI 3.0 / springdoc | API specification |
| Audit | Kafka producer (audit.events) | Immutable audit trail |
| Query log | PostgreSQL | Audit analytics, GDPR-managed |
| Metrics | Micrometer + Prometheus | Observability |
| Tracing | Micrometer Tracing (via Platform Starter) | Distributed trace propagation |
| Testing | JUnit 5, jqwik, Pact, WireMock, Testcontainers | Multi-level testing |
| Platform | Secure Spring Boot Platform Starter | Cross-cutting concerns |

## Appendix B: Decision Cross-Reference

| Section | Decisions Applied |
|---------|------------------|
| 3. Security Architecture | C1 (pipeline), C6 (timing) |
| 4. Name Matching Engine | C2 (structural pipeline) |
| 5. Cache Architecture | C3a (TTL), C3b (warming), C3c (sizing) |
| 6. Resilience Architecture | C4 (circuit breaker + bulkhead) |
| 7. API Design | C10 (edge case responses) |
| 8. Data Architecture | C8 (GDPR anonymization) |
| 10. Testing Architecture | C1 (security verification), C9 (corpus + property testing) |
| 11. Deployment | C5 (per-instance enumeration), C7 (independent instances) |
| 12. Production Scaling | C3a/b, C5, C7 (Redis upgrade path) |
