# Security Review Checklist — Nordic Payment Processing Infrastructure

*Purpose: Systematic security verification of architecture and requirements across all subprojects*
*Date: 2026-04-12*
*Frameworks: OWASP ASVS v4.0.3 (Level 2), OWASP API Security Top 10 (2023), PCI DSS v4.0 principles*
*Scope: Master project + 7 subprojects (A1-A6 + Platform Starter)*

---

## How to Use This Checklist

Each category contains specific verification items. For each item:
- **What to verify** — the specific security property
- **ASVS/API/PCI reference** — traceability to the standard
- **Where to check** — which services and documents are relevant
- **Payment-specific concern** — why this matters for our system specifically

Items are ordered by risk within each category. The categories themselves are ordered by criticality for a payment processing system.

---

## Cross-Cutting Verification Themes

Before the category-by-category review, two architectural principles must be verified as consistently applied across the entire project. These are not individual checklist items — they are lenses through which every category should be examined.

### Theme A: Security as a First-Class Architectural Concern

**Principle:** Security must be considered from the first architectural discussion, not added afterwards. If security is an afterthought, it becomes difficult or impossible to integrate later — the architecture hardens around insecure patterns.

**How to verify across the project:**

| Check | What to Look For | Where |
|-------|-----------------|-------|
| A.1 | Do ARCHITECTURE_CHALLENGES documents include **security challenges**, not just functional ones? | All subproject CHALLENGES docs |
| A.2 | Do ARCHITECTURE_DECISIONS show security as a factor in **non-security decisions**? (e.g., the dual-write decision considers tamper risk; the caching decision considers data leakage; the state machine decision considers state manipulation attacks) | All subproject DECISIONS docs |
| A.3 | Is the master threat model (A.3a — boundary STRIDE + attack trees) **referenced** by subproject architectures? Do subprojects identify their own threat surface? | All subproject DESIGN docs |
| A.4 | Are security requirements (SEC-\*, VOP-S\*, LED-S\*, etc.) **traceable to architectural decisions**, not just listed as implementation notes to handle later? | Requirements vs. DECISIONS cross-reference |
| A.5 | Does the **Platform Starter** embed security as a structural default that all services inherit, rather than providing security utilities that each service may or may not use? | Starter DESIGN, all service DESIGN docs |

### Theme B: Security by Construction — The Fortress Principle

**Principle:** Security properties must be **structurally enforced** by the architecture, not **policy-enforced** by documentation or developer discipline. A structural guarantee means the insecure behavior is architecturally impossible — not that it's prohibited by a coding guideline.

**How to verify across the project:**

For each critical security property, determine whether it is enforced by **construction** (C) or by **convention** (V). Construction is strongly preferred. Convention items are potential vulnerabilities.

| Security Property | Expected Enforcement | How to Verify |
|-------------------|---------------------|---------------|
| Sanctions screening cannot be skipped | **Construction** — A2's state machine has no path from instruction to settlement without A5 response | Trace A2's state machine for any bypass path |
| Actual name never revealed on NO_MATCH | **Construction** — A6's `ResponseBodyAdvice` strips the field; no configuration toggle | Verify A6's response filtering is in a filter, not in business logic |
| All endpoints authenticated by default | **Construction** — Starter configures `anyRequest().authenticated()`; public endpoints require explicit opt-out | Verify Starter's SecurityFilterChain default |
| Kafka messages tamper-evident | **Construction** — Starter's producer interceptor signs all messages; consumer interceptor verifies before deserialization | Verify Starter's interceptor auto-configuration |
| Per-account serialization (no concurrent balance manipulation) | **Construction** — Kafka partitioning by `accountId` ensures single-threaded processing per account | Verify A1's partition key strategy |
| PII encrypted in event store | **Construction** — crypto-shredding encrypts PII fields at event creation time; cannot be written unencrypted | Verify A1's event creation pipeline |
| Audit trail is append-only | **Construction** — Kafka topic configuration (no delete/compact), hash chaining detects tampering | Verify Kafka ACLs and topic config |
| Double-spend prevention | **Construction** — idempotency keys checked before processing; compound events ensure atomic debit+credit | Verify A1/A2 idempotency + A1 compound event model |
| OAuth2 consent checked on every request | **Construction** — A4's filter chain checks consent in database on every authenticated request (no cache) | Verify A4's consent enforcement is in a filter, not optional |
| Anti-enumeration detection active | **Convention** — in-memory counters, lost on restart | Flag: this is convention-level, not construction-level. Acceptable for portfolio with documented upgrade path (Redis). |

**Convention-level items are acceptable IF:** (1) they are explicitly documented as convention-level, (2) the production upgrade to construction-level is documented, and (3) the risk of the convention being violated is assessed. An undocumented convention-level security property is a vulnerability.

---

## Category S1: Business Logic Security — Payment Flow Integrity

*The most critical category for a payment system. These are attacks that exploit the business logic, not the technology. ASVS V11 + OWASP API6.*

### S1.1 — Payment Flow Step Order Enforcement

**What:** Verify that payment processing follows a strict sequential flow that cannot be skipped or reordered. A payment must pass through VoP → reservation → sanctions screening → fraud scoring → settlement, in that order. No step can be bypassed.

**ASVS:** 11.1.1 (sequential step order, no skipping)
**Where:** A2 ARCHITECTURE_DESIGN (instant + batch state machines), A3/A4 (Phase 1 flow), INTERFACE_CONTRACTS

**Payment concern:** An attacker who can skip sanctions screening or fraud scoring can process payments to sanctioned entities or commit fraud. This is the single most dangerous business logic vulnerability.

Specific checks:
- Can a payment instruction reach settlement without passing through A5?
- Can a payment instruction bypass VoP (A6)?
- Can the batch path skip any step that the instant path requires?
- Is the step order enforced structurally (code/architecture) or only by convention?

### S1.2 — Double-Spend / Duplicate Payment Prevention

**What:** Verify that the same payment cannot be processed twice. Idempotency keys must prevent replay attacks and accidental double-submission.

**ASVS:** 11.1.6 (TOCTOU / race conditions for sensitive operations)
**Where:** A2 (idempotency keys), A3 (idempotency keys), A1 (compound events, balance reservations), INTERFACE_CONTRACTS (B.3a)

**Payment concern:** A double-processed payment means someone gets charged twice. A replayed payment instruction could drain an account.

Specific checks:
- What is the idempotency key for each boundary? Is it derived deterministically?
- How long are idempotency keys retained? Can a very late replay succeed after TTL expiry?
- Can concurrent requests for the same payment create a race condition in A1's balance reservation?
- Does A1's per-account Kafka partitioning actually prevent concurrent reservation races?

### S1.3 — Balance Integrity — Cannot Go Negative

**What:** Verify that no code path allows an account balance to go below zero (or below the reserved amount). Balance checks must be atomic with balance modifications.

**ASVS:** 11.1.5 (business logic limits), 11.1.6 (TOCTOU)
**Where:** A1 ARCHITECTURE_DESIGN (reservation logic, compound events)

**Payment concern:** A negative balance means money was created from nothing — a fundamental integrity failure.

Specific checks:
- Is the balance check + reservation atomic? Or can a race condition between check and reservation allow over-reservation?
- What happens if two concurrent reservations arrive for the same account with combined amount exceeding the balance?
- Does the compound event model (debit + credit in one Kafka message) structurally prevent unbalanced entries?

### S1.4 — Sanctions Screening Cannot Be Bypassed

**What:** Verify there is no code path — including error paths, timeout paths, and configuration options — that allows a payment to settle without sanctions screening.

**ASVS:** 11.1.1 (no step skipping), 11.1.5 (business logic limits)
**Where:** A2 ARCHITECTURE_DESIGN (FraudResponseProcessor, TimeoutPunctuator), A5 ARCHITECTURE_DESIGN

**Payment concern:** Processing a payment for a sanctioned entity is a criminal offense (AML regulations). Unlike fraud scoring (which can auto-pass), sanctions screening can NEVER be skipped.

Specific checks:
- On A5 timeout: does A2 always reject? (Not auto-pass)
- On A5 returning `sanctions: UNAVAILABLE`: does A2 reject?
- Is there any feature flag or configuration that could disable sanctions checking?
- Is the sanctions-cannot-be-skipped property enforced structurally or by convention?
- Can an operator force-settle a payment that failed sanctions screening via the investigation queue?

### S1.5 — Rate Limiting and Anti-Automation for Payment APIs

**What:** Verify that all payment-facing APIs have rate limiting to prevent automated abuse (mass payment submission, account enumeration, credential stuffing).

**ASVS:** 11.1.4 (anti-automation), OWASP API4 (unrestricted resource consumption)
**Where:** A3 (merchant API), A4 (TPP API), A6 (VoP responder)

**Payment concern:** An attacker who can submit thousands of payment requests per second could drain accounts, overwhelm fraud detection, or use the system for money laundering.

Specific checks:
- What are the rate limits for each external API? Are they per-client, per-IP, or global?
- Can rate limits be bypassed by rotating API keys or client certificates?
- Are rate limits applied before or after authentication? (Should be after — otherwise unauthenticated requests consume rate limit quota for legitimate users)

### S1.6 — Transaction Amount Limits and Velocity Controls

**What:** Verify that the architecture enforces transaction amount limits and velocity controls that prevent abnormal transaction patterns.

**ASVS:** 11.1.3 (appropriate limits per user), 11.1.7 (monitoring for unusual events)
**Where:** A5 (velocity rules VEL-01 through VEL-10), A4 (SCA exemption thresholds), A2 (clearing limits)

**Payment concern:** Even authenticated legitimate users can be compromised. Velocity controls (e.g., "no more than 10 transactions per hour for this account") catch compromised accounts.

### S1.7 — VoP Anti-Enumeration — Cannot Map Names to IBANs

**What:** Verify that the VoP service cannot be used to systematically discover which names are associated with which IBANs.

**ASVS:** 11.1.4 (anti-automation), OWASP API6 (sensitive business flow abuse)
**Where:** A6 ARCHITECTURE_DESIGN (anti-enumeration, timing normalization, response filtering)

**Payment concern:** A name-IBAN mapping database enables targeted social engineering, invoice fraud, and identity theft.

Specific checks:
- On NO_MATCH: is the actual account holder name NEVER returned? Is this enforced structurally?
- Can response timing reveal the match result? (Timing side-channel)
- Can response timing reveal whether an IBAN exists? (NOT_FOUND vs NO_MATCH timing)
- Can repeated queries with different names for the same IBAN extract information before anti-enumeration triggers?
- What happens on service restart — are anti-enumeration counters lost?

---

## Category S2: Authentication and Identity

*Verify that every interface correctly authenticates callers and that authentication cannot be bypassed. ASVS V2 + OWASP API2.*

### S2.1 — External API Authentication

**What:** Verify that all external-facing APIs require authentication and that the authentication mechanism is appropriate.

**ASVS:** 2.2.1 (anti-automation), 1.2.3 (single vetted mechanism)
**OWASP:** API2 (broken authentication)
**Where:** A3 (merchant API key), A4 (OAuth2 + mTLS for TPPs), A6 (mTLS or API key for PSPs)

Specific checks:
- A3 merchant API: how are API keys generated, stored, rotated, revoked?
- A4 TPP API: is the OAuth2 implementation complete (PKCE, token rotation, family revocation)?
- A6 responder API: can an attacker use a stolen API key? How are keys revoked?
- Is there a mechanism to detect and block compromised credentials?

### S2.2 — Service-to-Service Authentication

**What:** Verify that all inter-service communication (REST and Kafka) is authenticated. No service should accept unauthenticated requests from another service.

**ASVS:** 1.2.2 (authenticated components), 9.2.1 (trusted TLS certificates)
**Where:** All services, INTERFACE_CONTRACTS (Section 3), master A.1a (three-zone model)

Specific checks:
- Is mTLS enforced for all inter-service REST calls?
- Are Kafka messages HMAC-signed and verified (KM-06)?
- Can a service be impersonated by presenting a different service's certificate?
- What is the certificate issuance and rotation process?

### S2.3 — OAuth2 / SCA Implementation Security

**What:** Verify that A4's OAuth2 and SCA implementation is secure against known attacks.

**ASVS:** V2.7-V2.9 (verifier security), V3.5 (token-based session management)
**Where:** A4 ARCHITECTURE_DESIGN (defense-in-depth layers, SCA exemption engine)

Specific checks:
- PKCE enforced for all authorization code flows?
- Token refresh with family ID and reuse detection?
- SCA session timeout (5 minutes per PSD2)?
- Dynamic linking: is payment amount immutable once SCA session is created?
- Can SCA exemptions be manipulated? (Low-value, trusted beneficiary, TRA thresholds)
- Lockout after failed SCA attempts — is it durable (survives restart)?

### S2.4 — Default-Deny Authentication

**What:** Verify that the Platform Starter's deny-all default is active in all services and cannot be accidentally overridden.

**ASVS:** 1.2.4 (consistent security strength), 4.1.5 (fail securely, deny by default)
**Where:** Platform Starter ARCHITECTURE_DESIGN, all service configurations

Specific checks:
- Does the Starter configure `anyRequest().authenticated()` as the default?
- Which endpoints are explicitly permitted (health, readiness, metrics)?
- Can a developer accidentally add a new endpoint without authentication?

---

## Category S3: Authorization and Access Control

*Verify that authenticated callers can only access what they're authorized to access. ASVS V4 + OWASP API1/API3/API5.*

### S3.1 — Object-Level Authorization (BOLA/IDOR)

**What:** Verify that API endpoints enforce that a caller can only access their own data, not other users'/accounts' data.

**OWASP:** API1 (Broken Object Level Authorization) — the #1 API risk
**Where:** A1 (account queries), A4 (AISP — account access), A3 (R2P request access)

**Payment concern:** A TPP authorized for Account A should not be able to query Account B. A merchant should not see another merchant's R2P requests.

Specific checks:
- A4 AISP: when a TPP queries `/api/v1/accounts/{accountId}/balances`, is the `accountId` validated against the consent scope?
- A1: when serving account queries, does A1 verify the caller is authorized for that specific account?
- A3: is multi-tenant isolation enforced at the repository level? Can a merchant access another merchant's requests by guessing IDs?

### S3.2 — Property-Level Authorization (Mass Assignment)

**What:** Verify that API endpoints do not allow callers to modify object properties they shouldn't control.

**OWASP:** API3 (Broken Object Property Level Authorization)
**ASVS:** 5.1.2 (mass parameter assignment protection)
**Where:** All REST APIs, especially A3 (payment request creation), A4 (payment initiation)

Specific checks:
- Can a merchant set the `status` field when creating an R2P request? (Should be server-assigned)
- Can a TPP set the `fraudScore` or `vopResult` when initiating a payment? (Should be server-computed)
- Are request DTOs restricted to only the fields the caller should provide?

### S3.3 — Function-Level Authorization (Privilege Escalation)

**What:** Verify that regular users cannot access admin functions, and that cross-service privilege escalation is impossible.

**OWASP:** API5 (Broken Function Level Authorization)
**ASVS:** 4.1.3 (least privilege), 4.3.3 (step-up auth for high-value ops)
**Where:** A2 (investigation queue — operator-only), A5 (case management — analyst-only), A1 (reconciliation — admin-only)

Specific checks:
- Can a TPP access A2's investigation queue endpoints?
- Can a merchant trigger A1's reconciliation run?
- Are admin endpoints on separate URL prefixes with separate auth requirements?

### S3.4 — Consent Enforcement on Every Request

**What:** Verify that A4 checks consent validity on every API request, not just at token issuance.

**ASVS:** 4.1.1 (enforce on trusted layer), 4.1.2 (attributes not manipulable)
**Where:** A4 ARCHITECTURE_DESIGN (consent lifecycle)

**Payment concern:** A revoked consent must immediately stop access. Caching consent decisions could allow access after revocation.

Specific checks:
- Is consent checked against the database on every request (not cached)?
- What happens if a consent is revoked between token issuance and API call?
- Can a TPP use a valid token with an expired/revoked consent?

---

## Category S4: Input Validation and Injection Prevention

*Verify that all input is validated and that injection attacks are prevented. ASVS V5 + OWASP API3.*

### S4.1 — SQL Injection Prevention

**What:** Verify that all database queries use parameterized queries or ORM-generated queries. No string concatenation in SQL.

**ASVS:** 5.3.4 (parameterized queries)
**Where:** All services with PostgreSQL (A1, A2, A3, A4, A5, A6)

**Payment concern:** SQL injection in a payment system could allow an attacker to modify account balances, bypass sanctions screening, or exfiltrate all customer data.

### S4.2 — IBAN Validation Before Processing

**What:** Verify that IBANs are validated (format + checksum per ISO 13616) before any database lookup or processing. Malformed IBANs must be rejected at the input boundary.

**ASVS:** 5.1.3 (positive validation), 5.1.4 (structured data validated against schema)
**Where:** A3 (payment request), A4 (payment initiation), A6 (VoP check), A1 (account lookup)

Specific checks:
- Is IBAN validation performed before the IBAN reaches any database query?
- Does validation include both format (country code + length) and checksum (mod-97)?
- Are non-Latin characters in IBANs handled safely?

### S4.3 — Kafka Message Deserialization Safety

**What:** Verify that Kafka message consumers safely handle malformed or malicious messages. Deserialization failures must not crash the consumer or execute arbitrary code.

**ASVS:** 5.5.1-5.5.4 (deserialization prevention)
**Where:** All Kafka consumers (A1, A2, A3, A4, A5), Platform Starter (error handler)

**Payment concern:** A malicious Kafka message (from a compromised producer or broker) could exploit a deserialization vulnerability to execute arbitrary code on the consuming service.

Specific checks:
- Is Avro used with Schema Registry validation? (Avro is safer than Java serialization)
- What happens when a message fails schema validation? (Should go to DLQ, not crash)
- Is HMAC verification performed BEFORE deserialization? (Prevents processing tampered messages)
- Are Kafka consumers configured to reject messages that don't match the expected schema version?

### S4.4 — Name Input Sanitization (A6 Matching Engine)

**What:** Verify that name inputs to the VoP matching engine are sanitized against injection and abuse patterns.

**ASVS:** 5.1.3 (positive validation), 5.2.2 (unstructured data sanitized)
**Where:** A6 ARCHITECTURE_DESIGN (input validation layer, matching engine)

Specific checks:
- Max length enforcement (140 chars per EPC spec)?
- Control character rejection?
- Unicode homoglyph detection (Cyrillic characters that look like Latin)?
- Can specially crafted names cause ReDoS (regular expression denial of service) in the normalization pipeline?

### S4.5 — Amount and Currency Validation

**What:** Verify that payment amounts are validated for range, precision, and currency code validity.

**ASVS:** 5.1.4 (structured data validated), 11.1.3 (appropriate limits)
**Where:** A3, A4 (payment creation), A2 (clearing), A1 (balance operations)

Specific checks:
- Can a negative amount be submitted? (Should be rejected)
- Can a zero amount be submitted? (Depends on business rules)
- Is the decimal precision consistent with the currency? (JPY has 0 decimals, EUR has 2)
- Are amounts stored as BigDecimal (not floating point)?
- Can an overflow/underflow occur in amount arithmetic?

---

## Category S5: Cryptography and Key Management

*Verify that all cryptographic operations use approved algorithms and that keys are managed securely. ASVS V6 + PCI DSS 3, 4.*

### S5.1 — TLS Configuration

**What:** Verify that TLS 1.2+ is used for all communications, with strong cipher suites.

**ASVS:** 9.1.1-9.1.3 (TLS for all, strong ciphers, no fallback), 9.2.2 (all connections encrypted)
**PCI DSS:** Requirement 4 (strong cryptography in transit)
**Where:** All services, Kafka, PostgreSQL connections

Specific checks:
- REST inter-service: TLS 1.2+ with mTLS?
- Kafka: TLS for broker connections? SASL authentication?
- PostgreSQL: TLS for database connections? Certificate verification?
- Any plaintext connections anywhere (even localhost in docker-compose)?

### S5.2 — Crypto-Shredding Implementation

**What:** Verify that the crypto-shredding implementation for GDPR erasure uses approved algorithms, secure key storage, and correct field-level encryption.

**ASVS:** 6.2.1-6.2.6 (approved algorithms, no weak modes), 6.4.1-6.4.2 (key vault)
**Where:** A1 ARCHITECTURE_DESIGN (crypto-shredding), master F.1a

Specific checks:
- AES-256-GCM (authenticated encryption)?
- Per-account keys — where are they stored? Encrypted with a master key?
- Key rotation — can the master key be rotated without re-encrypting all per-account keys?
- After key deletion — is the key material actually zeroed in memory, not just dereferenced?

### S5.3 — HMAC on Kafka Messages

**What:** Verify that Kafka message HMAC signing (KM-06) uses approved algorithms and that keys are managed securely.

**ASVS:** 6.2.2 (approved algorithms), 6.3.1 (CSPRNG for key generation)
**Where:** Platform Starter (HMAC interceptor), all Kafka producers and consumers

Specific checks:
- HMAC-SHA256 (not MD5, not SHA1)?
- Key per producer service (not a shared key across all services)?
- Key rotation procedure defined?
- What happens if HMAC verification fails? (Should go to DLQ + security alert, never processed)

### S5.4 — JWT Signing Key Management

**What:** Verify that A4's JWT signing keys are managed securely with rotation.

**ASVS:** 6.4.1 (key vault), 2.9.1 (cryptographic keys stored securely)
**Where:** A4 ARCHITECTURE_DESIGN (JWT signing, key rotation)

Specific checks:
- RSA or ECDSA (not HMAC with symmetric secret for public JWTs)?
- Key rotation with overlap period (old key valid for verification during transition)?
- JWKS endpoint serves current + previous keys?
- Private key stored securely (not in application.yml)?

### S5.5 — Random Number Generation

**What:** Verify that all random values (UUIDs, tokens, nonces, keys) use cryptographically secure random number generators.

**ASVS:** 6.3.1 (CSPRNG), 6.3.2 (GUID v4 with CSPRNG)
**Where:** All services (transaction IDs, request IDs, API keys, tokens)

Specific checks:
- `java.security.SecureRandom` or `UUID.randomUUID()` (which uses SecureRandom)?
- No `java.util.Random` or `Math.random()` for security-sensitive values?
- SCA challenge codes generated with CSPRNG?

---

## Category S6: Data Protection and Privacy

*Verify that sensitive data is protected at rest, in transit, and in logs. ASVS V8 + PCI DSS 3, 7, 10.*

### S6.1 — PII Minimization

**What:** Verify that each service collects and stores only the PII it actually needs. No service should store PII it doesn't require for its function.

**ASVS:** 8.3.4 (identify all sensitive data), 1.8.1 (classify sensitive data)
**Where:** All services, master F.1a

Specific checks:
- A5: does it truly avoid persisting creditor names? (PII transient in memory only)
- A2: does it need to store debtor/creditor names, or only IBANs?
- A6: query logs store stated_name — is this necessary? (Yes, for audit and enumeration detection)
- Are there any PII fields stored "just in case" without a documented purpose?

### S6.2 — Sensitive Data Not in Logs

**What:** Verify that log output never contains account holder names, IBANs, account balances, payment amounts, or authentication credentials.

**ASVS:** 7.1.1 (never log credentials/payment details), 7.1.2 (no sensitive data per privacy laws)
**PCI DSS:** Requirement 10 (log access without exposing data)
**Where:** All services, Platform Starter (structured logging with redaction)

Specific checks:
- Does the Platform Starter's `@Redacted` annotation actually scrub fields from log output?
- Are Kafka message payloads logged at DEBUG level? (Could expose PII)
- Are REST request/response bodies logged? (Could expose names, IBANs, amounts)
- Is correlation ID propagated without exposing transaction details?

### S6.3 — Sensitive Data Not in Error Responses

**What:** Verify that error responses do not leak internal details (stack traces, SQL errors, internal paths, configuration values).

**ASVS:** 7.4.1 (generic messages with unique ID), 14.3.2 (debug disabled in production)
**Where:** All services, Platform Starter (RFC 9457 error handler)

Specific checks:
- Does the RFC 9457 error handler strip stack traces in production?
- Can a malformed IBAN in a request trigger an error that reveals the SQL query or table structure?
- Do validation errors reveal which internal fields exist? (Mass assignment probe)

### S6.4 — Encryption at Rest

**What:** Verify that sensitive data is encrypted at rest in all databases.

**ASVS:** 6.1.1 (PII encrypted at rest), 6.1.3 (financial data encrypted at rest)
**PCI DSS:** Requirement 3 (protect stored data)
**Where:** All PostgreSQL databases, Kafka event store

Specific checks:
- A1: PII fields in Kafka events encrypted with per-account keys (crypto-shredding)?
- PostgreSQL: transparent data encryption (TDE) or volume encryption?
- Kafka: topic-level encryption or reliance on disk encryption?
- Backups: encrypted?

### S6.5 — Data Retention and Disposal

**What:** Verify that data retention policies are defined and enforced. Data beyond the retention period must be deleted or anonymized.

**ASVS:** 8.3.8 (retention classification, old data deleted)
**Where:** All services, INTERFACE_CONTRACTS (GDPR Erasure Protocol)

Specific checks:
- Is the 5-year AML retention period defined for each data type?
- Is there an automated process to anonymize/delete data beyond the retention period?
- Are Kafka topics with infinite retention (ledger.events) handled by crypto-shredding rather than deletion?

---

## Category S7: Communication Security

*Verify that all communication channels are secured. ASVS V9.*

### S7.1 — No Plaintext Communication

**What:** Verify that NO communication between ANY components uses plaintext. Every connection must be TLS-encrypted.

**ASVS:** 9.1.1 (no fallback to insecure), 9.2.2 (ALL inbound/outbound encrypted)
**PCI DSS:** Requirement 4
**Where:** All services, docker-compose configuration

Specific checks:
- Service-to-service REST: TLS?
- Service-to-Kafka: TLS?
- Service-to-PostgreSQL: TLS?
- Prometheus scraping: TLS?
- Docker-compose: are any ports exposed without TLS? (Development convenience vs. security)

### S7.2 — Certificate Validation

**What:** Verify that TLS certificates are validated (not skipped) on all connections.

**ASVS:** 9.2.1 (trusted certificates, internal CA trust), 9.2.4 (revocation checking)
**Where:** All services

Specific checks:
- No `InsecureTrustManagerFactory` or `TrustAllCerts` in any service?
- No `-Djavax.net.ssl.trustStore=` pointing to a trust-all store?
- Certificate chain validation enforced (not just hostname verification)?
- For development: self-signed certs with a project-local CA, not disabled verification?

---

## Category S8: Error Handling, Logging, and Audit

*Verify that errors are handled safely and that the audit trail is complete and tamper-evident. ASVS V7 + PCI DSS 10.*

### S8.1 — Audit Trail Completeness

**What:** Verify that every security-relevant event is logged to the immutable audit trail.

**ASVS:** 7.1.3 (log security events), 7.1.4 (detailed investigation info)
**PCI DSS:** Requirement 10 (log all access)
**Where:** All services, Platform Starter (`@Audited`, `AuditLogger`)

Events that must be audited:
- Authentication success/failure (all services)
- Authorization decisions (A4 consent checks, A3 merchant access, A5 case management)
- Payment state transitions (A3 R2P lifecycle, A2 clearing, A1 settlement)
- Sanctions screening results (A5 — every screening, including the list version used)
- VoP queries (A6 — every query with PSP identity, IBAN, result)
- Configuration changes (all services — threshold changes, rate limit changes)
- Account lifecycle events (A1 — create, freeze, unfreeze, close)
- Fraud case management actions (A5 — analyst decisions)

### S8.2 — Audit Trail Tamper Evidence

**What:** Verify that the audit trail cannot be modified after the fact, and that tampering is detectable.

**ASVS:** 7.3.3 (logs protected from unauthorized access and modification)
**Where:** Platform Starter (hash chain), A1 (audit checkpoints)

Specific checks:
- Per-instance hash chain implemented via Platform Starter?
- Daily signed checkpoints published to `audit.checkpoints`?
- Verification tool exists to recompute and validate the hash chain?
- Who has access to modify `audit.events` Kafka topic? (Should be append-only, no delete/modify ACL)

### S8.3 — Log Injection Prevention

**What:** Verify that log output cannot be injected with forged entries.

**ASVS:** 7.3.1 (encode data to prevent log injection)
**Where:** Platform Starter (structured JSON logging)

Specific checks:
- Structured JSON logging (logstash-logback-encoder) prevents newline injection?
- User-provided input (names, IBANs) is encoded when logged?
- Can an attacker craft a name like `"Ola\n2026-04-12 INFO Payment SETTLED"` to forge a log entry?

---

## Category S9: Configuration and Deployment Security

*Verify that the system is securely configured and that secrets are managed properly. ASVS V14 + PCI DSS 2, 6.*

### S9.1 — Secrets Management

**What:** Verify that no secrets (API keys, database passwords, HMAC keys, JWT signing keys) are hardcoded in source code, configuration files committed to version control, or Docker images.

**AVSV:** 6.4.1 (key vault), 14.1.3 (hardened configuration)
**PCI DSS:** Requirements 2 (no defaults), 8 (secure credentials)
**Where:** All services, master A.2a (KeyProvider abstraction)

Specific checks:
- Are secrets injected via environment variables or Kubernetes Secrets?
- No secrets in `application.yml` files committed to Git?
- No default passwords that work in production?
- KeyProvider abstraction separates development (env vars) from production (KMS)?

### S9.2 — Dependency Security

**What:** Verify that all dependencies are from trusted sources, up to date, and scanned for known vulnerabilities.

**ASVS:** 14.2.1-14.2.6 (dependency management, SBOM)
**PCI DSS:** Requirement 6 (maintain secure systems)
**Where:** All services, Platform Starter Gradle plugin

Specific checks:
- Dependency vulnerability scanning in CI/CD (OWASP Dependency-Check or similar)?
- SBOM (Software Bill of Materials) generated (CycloneDX plugin mentioned in Starter)?
- All dependencies from Maven Central (not unverified repositories)?
- No outdated dependencies with known CVEs?

### S9.3 — Debug and Development Features Disabled in Production

**What:** Verify that debug endpoints, verbose error messages, development profiles, and test data are not present in production.

**ASVS:** 14.3.2 (debug disabled), 14.3.3 (no version disclosure)
**Where:** All services

Specific checks:
- Spring Boot Actuator endpoints: which are exposed? Are sensitive endpoints (env, beans, configprops) restricted?
- Is `spring.profiles.active=dev` impossible in production? (Profile validation?)
- Do error responses include stack traces in production? (Should not)
- Is the H2 console disabled? (If H2 is used for development)

### S9.4 — Network Segmentation

**What:** Verify that network policies enforce the three-zone model: external traffic cannot reach internal services directly.

**ASVS:** 1.14.1 (segregate by trust level), 1.14.5 (network isolation)
**PCI DSS:** Requirement 1 (network security controls)
**Where:** Master A.1a (zone model), all service deployment configurations

Specific checks:
- Kubernetes NetworkPolicies defined for each service?
- A1, A2, A5 pods unreachable from outside the cluster?
- A3, A4, A6 external endpoints reachable only through ingress controller?
- PostgreSQL and Kafka ports not exposed outside the cluster?
- Can a compromised DMZ service (A3) directly access A1's database?

---

## Category S10: Resilience Against Attacks

*Verify that the system degrades gracefully under attack rather than failing catastrophically. OWASP API4 + ASVS 11.1.4.*

### S10.1 — Denial of Service Protection

**What:** Verify that each service has protections against resource exhaustion attacks.

**OWASP:** API4 (unrestricted resource consumption)
**Where:** All services

Specific checks:
- Rate limiting on all external endpoints?
- Request size limits (max body size, max header size)?
- Connection limits per client?
- Query complexity limits (pagination, max results)?
- Kafka consumer backpressure handling (what if a topic floods)?

### S10.2 — Graceful Degradation Under Attack

**What:** Verify that a DoS attack on one service does not cascade to take down the entire system.

**ASVS:** 1.11.2 (no unsynchronized state in high-value flows)
**Where:** All services, resilience patterns (circuit breakers, bulkheads)

Specific checks:
- Circuit breakers prevent cascade: A1 down → A6 degrades (serves from cache) → A3/A4 proceed with VOP_TIMEOUT?
- Bulkheads prevent thread exhaustion: A6 limits concurrent A1 calls to 20?
- Separate JVM instances for batch/instant: a batch DoS doesn't affect instant payment SLA?
- Kafka consumer backpressure: a flood on `payment.instructions` doesn't block `payment.instant-instructions`?

### S10.3 — Compromise Containment

**What:** Verify that compromise of one service limits blast radius — an attacker who compromises A3 cannot access A1's database, drain accounts, or bypass sanctions.

**ASVS:** 1.14.1 (segregation by trust level)
**Where:** Master A.1a, all service security architectures

Specific checks:
- Per-service database: A3 cannot access A1's PostgreSQL?
- Per-service Kafka ACLs: A3 can only produce to its assigned topics?
- mTLS with service-specific certificates: A3's certificate cannot authenticate as A2?
- HMAC with per-producer keys: A3 cannot forge messages appearing to be from A2?

---

## Category S11: API-Specific Security

*OWASP API Security Top 10 items not covered above.*

### S11.1 — Server-Side Request Forgery (SSRF) Protection

**What:** Verify that services that make outbound HTTP calls validate the destination URL.

**OWASP:** API7 (SSRF)
**ASVS:** 12.6.1 (SSRF protection)
**Where:** A6 (calls A1), A3 (calls A6, sends webhooks), A4 (calls A1, calls A6)

Specific checks:
- A3 webhook delivery: is the merchant callback URL validated against an allowlist? Or can a merchant register `http://internal-service:8091/admin/delete` as their webhook URL?
- A6 → A1: URL is configured, not user-supplied? (Should be safe)
- Can any user-supplied input influence the destination of an outbound HTTP call?

### S11.2 — Unsafe API Consumption

**What:** Verify that data received from external APIs and inter-service APIs is validated, not blindly trusted.

**OWASP:** API10 (unsafe consumption of APIs)
**Where:** All services that consume data from other services

Specific checks:
- A2 consuming fraud-check-response from A5: does A2 validate the response fields, or blindly trust them?
- A3/A4 consuming status-updates from A2: is the status value validated against the known enum?
- A6 consuming holder-name from A1: is the response validated before passing to the matching engine?

### S11.3 — API Inventory and Versioning

**What:** Verify that all API endpoints are documented, and that deprecated/obsolete versions are not accidentally exposed.

**OWASP:** API9 (improper inventory management)
**ASVS:** 14.2.2 (remove unnecessary features)
**Where:** INTERFACE_CONTRACTS.md, all service ARCHITECTURE_DESIGN docs

Specific checks:
- Is there a complete inventory of all REST endpoints across all services? (INTERFACE_CONTRACTS.md provides this for inter-service; each service's DESIGN should list its own)
- Are there any undocumented endpoints?
- When `/v2/` is introduced, is `/v1/` properly deprecated and eventually removed?

---

## Category S12: Payment-Industry-Specific Security

*Derived from PCI DSS principles adapted for bank transfer processing (not card processing).*

### S12.1 — Audit Trail for All Payment Operations

**What:** Verify that every payment operation (creation, authorization, screening, clearing, settlement, reversal) is audited with who, what, when, and outcome.

**PCI DSS:** Requirement 10
**Where:** All services, `audit.events` topic

### S12.2 — Least Privilege Access to Payment Data

**What:** Verify that each service and each user role has access only to the payment data it needs. No broad "read all accounts" permission.

**PCI DSS:** Requirement 7
**Where:** A1 (account access scoping), A4 (consent-scoped access), A5 (case management roles)

### S12.3 — Incident Response Capability

**What:** Verify that the architecture supports detecting, investigating, and responding to security incidents.

**PCI DSS:** Requirement 12 (incident response)
**Where:** Investigation queues (A2), fraud case management (A5), alerting (all services)

Specific checks:
- Can an operator freeze an account immediately on fraud detection?
- Can a late sanctions hit trigger automatic payment reversal?
- Are security alerts (HMAC failure, enumeration detection, rate limit breach) routed to the right team?

### S12.4 — Regular Security Testing

**What:** Verify that the architecture includes provisions for regular security testing.

**PCI DSS:** Requirement 11 (test regularly)
**Where:** Testing architecture sections of all services

Specific checks:
- Security regression test suite (A6's adversarial tests)?
- Contract tests for security-sensitive interfaces (Pact)?
- Dependency vulnerability scanning in CI/CD?
- Penetration testing plan documented?

---

## Summary: Review Item Count

| Category | Items | Criticality |
|----------|-------|-------------|
| S1: Business Logic / Payment Flow | 7 | **Critical** |
| S2: Authentication | 4 | **Critical** |
| S3: Authorization / Access Control | 4 | **Critical** |
| S4: Input Validation / Injection | 5 | **High** |
| S5: Cryptography / Key Management | 5 | **High** |
| S6: Data Protection / Privacy | 5 | **High** |
| S7: Communication Security | 2 | **High** |
| S8: Error Handling / Audit | 3 | **High** |
| S9: Configuration / Deployment | 4 | **Medium** |
| S10: Attack Resilience | 3 | **Medium** |
| S11: API-Specific | 3 | **Medium** |
| S12: Payment Industry | 4 | **Medium** |
| **Total** | **49** | |

## Review Execution Approach

Recommended order:
1. **S1 (Business Logic)** — most payment-critical, highest impact
2. **S2 + S3 (AuthN + AuthZ)** — access control is the #1 API risk (OWASP API1)
3. **S4 (Injection)** — most common implementation vulnerability
4. **S5 + S7 (Crypto + Communication)** — data in transit and at rest
5. **S6 (Data Protection)** — PII handling and privacy
6. **S8 (Audit)** — regulatory compliance
7. **S9-S12 (Config, Resilience, API, Industry)** — defense in depth
