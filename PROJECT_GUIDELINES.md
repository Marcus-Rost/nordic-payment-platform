# Nordic Payment Processing Infrastructure — Project-Wide Guidelines

*These are cross-cutting principles that govern the entire project. They are not functional requirements for a specific subsystem — they are architectural and engineering guidelines that every subproject must follow.*
*Date: 2026-04-12*

---

## 1. Security by Construction — The Fortress Principle

**Guideline:** Security is not a feature to be added — it is a structural property of the architecture. The system must be built as a fortress from the very beginning. Security properties must be enforced by the architecture (structurally impossible to violate), not by developer discipline (documented rules that could be forgotten).

**What this means in practice:**
- The Platform Starter auto-configures deny-all authentication — a new endpoint is secure by default
- Sanctions screening cannot be bypassed — there is no code path, feature flag, or configuration toggle
- PII is never revealed on VoP NO_MATCH — the response filter is structural, not configurable
- Kafka messages are HMAC-signed by auto-configured interceptors — a service cannot accidentally skip signing
- Every security property has an adversarial test that verifies the property continuously in CI

**Anti-pattern this prevents:** "We'll add security later" → security becomes an afterthought, bolted on inconsistently, with gaps discovered only during penetration testing or, worse, in production.

---

## 2. Verified Against Security Standards

**Guideline:** The architecture is systematically reviewed against established security frameworks, not just designed with security "in mind."

**Standards applied:**
- **OWASP ASVS Level 2** (Application Security Verification Standard) — 253 requirements, tailored for financial applications
- **OWASP API Security Top 10 (2023)** — all 10 API-specific risks addressed
- **PCI DSS v4.0 principles** — payment industry security controls (adapted for bank transfers)
- **NIST SP 800-228** — API security for cloud-native systems

**What this means in practice:**
- A formal security review checklist with 49 items was executed against the architecture
- Each finding was tracked, categorized by severity, and resolved
- The SSRF vulnerability in webhook dispatch was caught and fixed before any code was written
- The HMAC signing gap was caught and fixed at the architecture level

---

## 3. Crash Resilience by Design

**Guideline:** The system must handle crashes at any point during a payment transaction without losing or corrupting data. Every crash point results in either correct completion or clean rollback — never lost money, permanent limbo, or silent corruption.

**What this means in practice:**
- Event sourcing (A1): Kafka events are the source of truth. PostgreSQL projections are derived and rebuildable.
- Idempotent processing: three-layer deduplication prevents double-processing after crash recovery
- Compound events: debit and credit are in a single Kafka message — structurally impossible to apply one without the other
- Reservation safety net: unreleased balance reservations auto-expire after 30 seconds
- Transactional Outbox: database writes and Kafka publishes are never inconsistent
- Investigation queues: payments stuck in intermediate states are automatically detected and escalated

**Anti-pattern this prevents:** "It works in normal operation" → crashes during edge cases cause silent data corruption discovered days later during reconciliation.

---

## 4. EU Regulatory Compliance Built-In

**Guideline:** Regulatory requirements are not bolted on as compliance checks — they are embedded in the architecture from the start.

**Regulations addressed:**
- **EU Instant Payment Regulation (2024/886)** — VoP mandatory, instant payments 24/7/365, sanctions screening mandatory
- **PSD2 / PSD3** — SCA, consent management, AISP/PISP, SCA exemptions
- **AML 5th Directive** — sanctions screening cannot be bypassed, 5-year audit retention
- **GDPR** — crypto-shredding for right to erasure, PII minimization, data retention policies
- **DORA** — digital operational resilience, mandatory resilience testing, incident classification

---

## 5. Comprehensive Review Culture

**Guideline:** Architecture is reviewed systematically before implementation begins. Reviews cover multiple dimensions, not just functionality.

**Reviews conducted:**
- **Architecture consistency review** — 29 findings across 76 documents, all resolved
- **Security review** (OWASP-based) — 22 findings, all resolved
- **Crash resilience review** — crash-point-by-crash-point analysis of the instant payment flow
- **Interface contract verification** — bilateral verification of every inter-service boundary

**What this means in practice:**
- The partition key conflict between A2 and A1 was caught in architecture review (would have caused integration failure)
- The SSRF vulnerability in A3 webhooks was caught in security review (would have been exploitable)
- The missing RETURNED status handling in A3/A4 was caught in architecture review (would have caused data inconsistency)

---

## 6. Interface Contracts as First-Class Artifacts

**Guideline:** Cross-service interfaces are defined in a single authoritative document (`INTERFACE_CONTRACTS.md`), not independently by each service. Both sides of every interface must conform to the contract.

**What this means in practice:**
- 18 Kafka message contracts with topic, key, schema, timeout, and error handling
- 4 inter-service REST contracts with path, method, auth, and timeout
- Shared enumerations (PaymentStatus, VoPResult, FraudDecision, etc.) defined once
- GDPR erasure protocol defined centrally with per-service required actions
- Kafka ACLs specified per service

---

## 7. Defense in Depth

**Guideline:** No single security control is trusted alone. Multiple independent layers protect every interface, so that bypassing one layer does not compromise the system.

**Examples:**
- A4 (PSD2) uses 6 independent defense layers (L1-L6), each protecting against different threats
- A6 (VoP) uses an 8-layer security pipeline where every request passes through all layers
- Kafka messages have 3 independent protections: SASL authentication, HMAC signing, Avro schema validation
- Balance integrity has 3 independent protections: domain type validation, per-account Kafka serialization, database CHECK constraint
- Audit trail has 3 independent protections: local log (sync), Kafka topic (async), hash chain (tamper evidence)

---

## 8. Observable by Design

**Guideline:** Every service exports metrics, participates in distributed tracing, and publishes structured audit events. Observability is auto-configured by the Platform Starter, not manually added per service.

**What this means in practice:**
- Micrometer metrics exported to Prometheus from every service
- Distributed tracing (W3C Trace Context) across REST and Kafka boundaries
- Structured JSON logging with automatic PII redaction
- Tamper-evident audit hash chain for regulatory compliance
- Alert thresholds defined for SLA violations, security events, and operational anomalies

---

## 9. Production-Ready Architecture with Documented Upgrade Paths

**Guideline:** The portfolio implementation is simple and correct. Where production-grade infrastructure is not needed for the portfolio scope (Redis, Kafka consumers for cache invalidation, distributed anti-enumeration), the simpler option is chosen AND the production upgrade path is explicitly documented.

**What this means in practice:**
- Cache invalidation: TTL-only for portfolio → event-driven for production (documented)
- Anti-enumeration: per-instance in-memory → Redis shared counters (documented)
- Cold start: pre-warm top-N → L1/L2 Caffeine/Redis (documented)
- Key management: environment variables → centralized KMS (documented via KeyProvider abstraction)

---

## 10. Chaos Engineering Readiness

**Guideline:** The system is designed to be testable under failure conditions, not just under normal operation. A chaos engineering test plan defines specific failure injection experiments.

**What this means in practice:**
- 8 specific chaos experiments defined (kill A2 mid-payment, kill A1 with active reservations, etc.)
- Steady-state metrics defined (success rate, latency, balance consistency)
- Chaos test infrastructure specified (Chaos Mesh, Toxiproxy)
- Test cadence defined: per-PR for single-service, weekly for multi-service, quarterly for production (DORA)

---

## 11. Financial Calculation Precision

**Guideline:** All monetary calculations use `BigDecimal` with explicit rounding. The default rounding mode is `HALF_EVEN` (Banker's Rounding) — statistically unbiased, mandated by many financial standards, and the IEEE 754 default.

**Rules:**
- **BigDecimal only** — no float, double, Float, or Double for monetary amounts (scores and percentages may use Double)
- **String construction** — always `new BigDecimal("0.1")` or `BigDecimal.valueOf()`, never `new BigDecimal(0.1)`
- **Explicit rounding** — every `divide()` call must specify `RoundingMode`. Bare `divide()` is prohibited (throws ArithmeticException for non-terminating decimals)
- **HALF_EVEN default** — all financial rounding uses `RoundingMode.HALF_EVEN` unless a specific business rule requires otherwise (documented)
- **Currency-aware scale** — decimal scale derived from ISO 4217 minor units per currency, not hardcoded to 2
- **Intermediate precision** — for multi-step calculations (rate conversion, fee splits), use currency scale + 4 extra digits for intermediates, round to currency scale only at the final step

**Enforcement:** The `payment-domain-model` shared library provides a `MoneyMath` utility class that wraps all arithmetic with mandatory rounding. An ArchUnit rule prohibits direct `BigDecimal.divide()` calls outside `MoneyMath`.

---

## 12. Currency and Data Format Standards

**Supported currencies:** {NOK, EUR, SEK, DKK, USD} — validated against ISO 4217 at every system boundary. Unknown currency codes are rejected.

**Decimal scale:** Derived from ISO 4217 minor units per currency (all current currencies use 2 minor units). Not hardcoded — see Guideline 11.

**Character encoding:** UTF-8 for all text data — API requests/responses, Kafka messages, database storage, log output. No implicit encoding conversions.

**Timestamps:** ISO 8601 format. All timestamps stored and transmitted in UTC. PostgreSQL columns use `TIMESTAMP WITH TIME ZONE` (not `TIMESTAMP`). Java code uses `Instant` (inherently UTC).

**IBAN format:** ISO 13616 with mod-97 checksum validation at every system entry point (A3, A4, A6).

**EPC character set:** VoP name inputs validated against the SEPA character set (EPC217-08) — Latin characters, digits, and a defined set of special characters. Characters outside this set are rejected at A6's input validation layer.

---

## 13. Kafka Consumer Tuning

**Guideline:** Kafka consumer parameters must be explicitly tuned per service based on processing characteristics.

| Parameter | Default | Instant Path (A2, A5) | Batch Path (A2 batch) |
|-----------|---------|----------------------|----------------------|
| `max.poll.records` | 500 | 50 (each record takes ~100ms to process through state machine) | 500 (batch processing, higher throughput) |
| `max.poll.interval.ms` | 300000 (5 min) | 30000 (30s — fail fast if processing stalls) | 600000 (10 min — batch cycles take longer) |
| `session.timeout.ms` | 45000 | 30000 | 45000 |
| `heartbeat.interval.ms` | 3000 | 3000 | 3000 |
| `fetch.max.wait.ms` | 500 | 100 (low latency) | 500 (throughput) |

**Rationale:** `max.poll.records` × processing_time_per_record must be less than `max.poll.interval.ms`. For A2 instant: 50 × 100ms = 5s, well within 30s limit.

---

## 14. Currency Scope and Regulatory Alignment

**Guideline:** The system applies EU Instant Payment Regulation (2024/886) standards to ALL currencies, not just EUR. This is a deliberate architectural choice:
- **Legal requirement:** IPR mandates apply to EUR transfers only. NOK transfers operate under Norwegian national rules (NICS/Norges Bank).
- **Our approach:** Apply the stricter IPR standard uniformly to all currencies (EUR, NOK, SEK, DKK, USD). This is simpler architecturally (one processing path), provides consistent security guarantees, and future-proofs for when Norway incorporates IPR for NOK (expected 2027-2028 with TIPS integration).
- **Implication:** NOK instant payments receive VoP verification, sanctions screening, and timing guarantees identical to EUR, even though not legally required. This is a security benefit.
- **IBAN non-discrimination:** Per SEPA Regulation Article 9, processing and fees must not differentiate based on the country prefix of an IBAN for EUR-denominated transfers.

---

## 15. Norwegian Market Integration

**Guideline:** The system is designed for the Norwegian payment market. These Norwegian-specific integrations are documented as requirements for market viability:

| Integration | Status | Notes |
|------------|--------|-------|
| **BankID** | Required (de facto) | SCA mechanism for Norwegian customers. A4's authentication abstraction layer supports BankID as an SCA method alongside TOTP. |
| **NICS / Bits AS** | Required for domestic clearing | NOK interbank clearing goes through NICS (operated by Bits AS). A2's batch path interfaces with NICS for domestic settlement. |
| **NICS Real** | Required for NOK instant | NOK instant payments settle through NICS Real (not SEPA Instant). TIPS integration planned for H1 2028. |
| **KAR Register** | Should | Account-to-holder mapping for payment routing. A6's VoP responder role is the architectural equivalent. |
| **KID Number** | Should | Norwegian payment reference system (modulus 10/11 check digit). A3 should support KID in payment references for AvtaleGiro/eFaktura compatibility. |
| **Brønnøysund Register** | Should | Beneficial ownership register for business account CDD. Future integration for A3 merchant onboarding. |
| **Økokrim/EFE Reporting** | Required for AML | STR filing via Altinn portal. Out of scope for portfolio but documented as production integration. |
| **Norwegian Sanctions Lists** | Required | A5 must screen against Norwegian-specific sanctions lists in addition to EU/UN lists. |
| **Fødselsnummer** | Restricted | Processing of Norwegian national ID numbers requires documented factual need per Personopplysningsloven §9-11. |
| **Norwegian Language** | Required | Customer-facing communications must be available in Norwegian per Finansavtaleloven. |

---

## 16. Data Protection Organizational Requirements

**Guideline:** The operating entity must meet these GDPR organizational requirements:
- **Data Protection Officer (DPO):** Mandatory per GDPR Art 37(1)(b) — the system's core activities involve regular and systematic monitoring of data subjects (transaction monitoring, fraud scoring, AML screening) on a large scale.
- **Records of Processing Activities (ROPA):** Each service must maintain a ROPA entry per GDPR Art 30.
- **Data Protection Impact Assessment (DPIA):** Required for A5 (fraud profiling), A1 (large-scale financial data), and A6 (identity verification at scale) per GDPR Art 35.
- **Legitimate Interest Assessment (LIA):** Required for fraud detection processing that relies on GDPR Art 6(1)(f) legitimate interest rather than Art 6(1)(c) legal obligation.
- **EEA Data Residency:** All data-at-rest and data-in-transit must remain within the EEA. No non-EEA replication or support access to personal data without appropriate safeguards (SCCs + TIA).
