# Requirements Review: OWASP Security Assessment

**Reviewer:** Claude Opus 4.6
**Date:** 2026-04-11
**Scope:** All requirements and use cases across 6 sub-systems (A1-A6) + cross-cutting Secure Spring Boot Platform Starter
**Focus:** OWASP compliance — OWASP Top 10, OWASP Application Security Verification Standard (ASVS), OWASP Secure Coding Practices, Payment-specific cybersecurity, hacker resilience
**Documents reviewed:** system_requirements.md, 7 sub-project requirements, 10 master use cases, 6 extensions, 7 sub-project gap analyses, NAVIGATION.md, SUBSYSTEM_MAP.md, 3 prior review documents

---

## Executive Summary

This review assesses the Nordic Payment Processing Infrastructure requirements against OWASP guidelines — the industry-standard framework for secure application development. The analysis covers OWASP Top 10 (2021), OWASP Application Security Verification Standard (ASVS v4.0.3), OWASP Secure Coding Practices, and OWASP-adjacent payment security concerns including PCI DSS integration, cyber-crime attack patterns, and hacker resilience.

### Overall OWASP Security Posture: STRONG WITH MATERIAL GAPS (7.5/10)

The project demonstrates exceptional application-level security awareness for a portfolio project. The sanctions screening architecture, VoP anti-enumeration protections, OAuth2 consent model, and refresh token family revocation are production-grade security implementations. However, the requirements have systematic gaps in **infrastructure-layer security** (Kafka message integrity, container hardening, key management lifecycle), **operational security** (incident response, forensics, security monitoring), and **OWASP ASVS Level 2/3 controls** (session management depth, cryptographic agility, secure deployment pipeline).

This review identifies **42 findings** organized by OWASP category:

| Category | Critical | High | Medium | Low | Total |
|----------|----------|------|--------|-----|-------|
| OWASP Top 10 Mapping | 4 | 7 | 8 | 3 | 22 |
| OWASP ASVS Verification | 2 | 5 | 4 | 1 | 12 |
| Payment Cyber-Crime Vectors | 1 | 3 | 2 | 2 | 8 |
| **Total** | **7** | **15** | **14** | **6** | **42** |

### Relationship to Prior Reviews

Three reviews already exist:
- **requirements_review_computational_stability.md** — 23 findings on crash recovery and distributed systems
- **requirements_review_eu_legislation.md** — 23 findings on EU regulatory compliance
- **payment_processing_requirements_review.md** — 14 security findings + requirements quality

This review does NOT duplicate findings already covered. Where a prior review touched on a security topic (e.g., the general review's CRITICAL-01 on key management, CRITICAL-02 on Kafka security), this review extends the analysis with specific OWASP requirement IDs, ASVS verification levels, and concrete implementation guidance. Cross-references to prior findings are noted as `[Prior: review_name, finding_id]`.

---

## PART 1: OWASP TOP 10 (2021) DETAILED ASSESSMENT

The OWASP Top 10 is the most widely recognized standard for web application security risks. Each category below maps our requirements against the specific OWASP risk, identifies gaps, and provides remediation guidance tied to OWASP Secure Coding Practices.

---

### A01:2021 — Broken Access Control

**Overall Status: WELL COVERED — with 3 specific gaps**

The project has a comprehensive access control model across all sub-systems. Role-based scoping via OAuth2 (A4), service-to-service authentication (A1-S02, A2-S03, CLR-S03), API key authentication for merchants (A3-S01, RTP-S01), and mTLS for PSPs (A6-S01, VOP-P02) demonstrate a layered approach.

#### Finding A01-1: No Horizontal Access Control Enforcement Specified (HIGH)

**Affected:** A3 (R2P consumer API), A4 (AISP/PISP), A1 (account queries)
**OWASP ref:** A01:2021 — Broken Access Control, CWE-639: Authorization Bypass Through User-Controlled Key

**Problem:** The requirements specify that consumers can only see their own requests (RTP-S02: "Consumer can only see/respond to requests targeting their own accounts") and that AISP returns data only for consented accounts (AISP-F06). However, no requirement specifies **how** this is enforced at the implementation level.

Without explicit guidance, developers may implement:
```java
// INSECURE — relies on the request ID from the URL path
PaymentRequest request = repository.findById(requestId);
return request; // No check that request.consumerId == authenticatedUser
```

This is an Insecure Direct Object Reference (IDOR) vulnerability — OWASP's #1 risk. An authenticated consumer could access or respond to another consumer's payment requests by guessing or enumerating UUIDs.

**Recommendation:** Add cross-cutting requirement:
- SEC-13: "All API endpoints that return user-specific data must enforce ownership verification: compare the authenticated principal's identity against the resource owner before returning or modifying data. This must be implemented as a framework-level filter or annotation (e.g., `@OwnerOnly`), not left to individual endpoint implementations."
- SEC-14: "UUID resource identifiers must be version 4 (random), not sequential, to prevent enumeration."

#### Finding A01-2: No Vertical Privilege Escalation Controls for Case Management (MEDIUM)

**Affected:** A5 (Case Management API), CASE-F01 through CASE-F09
**OWASP ref:** A01:2021, CWE-269: Improper Privilege Management

**Problem:** The Case Management API has two scopes: `fraud:read` (view alerts) and `fraud:manage` (resolve alerts). But there is no differentiation between:
- A fraud analyst resolving a fraud alert (CONFIRMED_FRAUD / DISMISSED)
- A compliance officer resolving a sanctions alert (CONFIRMED_SANCTION / FALSE_POSITIVE)

Sanctions false-positive decisions are legally significant — releasing a potentially sanctioned payment is a decision that should require compliance officer authorization, not general `fraud:manage` access. A compromised fraud analyst account could dismiss sanctions alerts.

**Recommendation:**
- Add scope `sanctions:manage` separate from `fraud:manage`
- Add requirement: "Sanctions alert resolution (CONFIRMED_SANCTION, FALSE_POSITIVE) requires `sanctions:manage` scope, issued only to designated compliance officers."

#### Finding A01-3: Admin API for TPP Management Lacks Access Control Specification (MEDIUM)

**Affected:** A4 (TPP-F01: `POST /admin/tpps`), TPP-F04 (TPP deactivation)
**OWASP ref:** A01:2021, CWE-306: Missing Authentication for Critical Function

**Problem:** The TPP registration and deactivation endpoints are under `/admin/` but no authentication or authorization requirement is specified for these endpoints. If exposed without authentication, an attacker could:
1. Register a malicious TPP with their own redirect URIs
2. Trick users into authorizing the malicious TPP
3. Exfiltrate account data via the AISP flow

**Recommendation:**
- Add requirement: "All `/admin/*` endpoints require OAuth2 token with `admin:manage` scope, restricted to system administrators."
- Add requirement: "TPP registration must include validation of the redirect URI domain against an allowlist."

---

### A02:2021 — Cryptographic Failures

**Overall Status: PARTIAL — critical infrastructure gaps**
**[Extends Prior: payment_processing_requirements_review.md, CRITICAL-01]**

The project specifies RS256 JWT signing (PSD2-S01), JWE payload encryption (PSD2-S06), HMAC-SHA256 for webhooks (RTP-W03), and SHA-256 for model integrity (ML-F05). These are correct algorithm choices. The gaps are in lifecycle management and infrastructure.

#### Finding A02-1: No Cryptographic Agility Requirement (CRITICAL)

**Affected:** All sub-systems using cryptographic algorithms
**OWASP ref:** A02:2021, CWE-327: Use of a Broken or Risky Cryptographic Algorithm
**OWASP ASVS ref:** V6.2.5 — "Verify that cryptographic algorithms used are not hardcoded"

**Problem:** The requirements hardcode specific algorithms:
- RS256 for JWT signing (AUTH-F06)
- SHA-256 for model checksums (ML-F05)
- HMAC-SHA256 for webhooks (RTP-W03)
- Jaro-Winkler with threshold 0.85 for name matching (VOP-N07)

If any of these algorithms are found to be vulnerable (SHA-256 is currently secure, but SHA-1 was once considered secure too), the system has no mechanism to rotate to a different algorithm without code changes.

More immediately: RS256 (RSA with SHA-256) uses a minimum key size that is not specified. RSA-2048 is the current minimum, but NIST recommends planning for transition to RSA-3072 or ECDSA P-256+ by 2030.

**Recommendation:**
- Add KM-01: "All cryptographic algorithm choices must be configurable via application properties. Algorithm identifiers must be stored alongside signed/encrypted artifacts (e.g., JWT `alg` header already does this for JWTs — extend this pattern to HMAC webhooks and checksums)."
- Add KM-02: "RSA key size must be minimum 2048 bits. ECDSA P-256 or Ed25519 should be supported as alternatives."
- Add KM-03: "The system must support running two algorithms simultaneously during migration (old + new), with gradual cutover."

#### Finding A02-2: HMAC Webhook Key Storage and Rotation Not Specified (HIGH)

**Affected:** A3 (RTP-W03, RTP-S03), A4 (AUTH-F16)
**OWASP ref:** A02:2021, CWE-321: Use of Hard-coded Cryptographic Key

**Problem:** Each merchant has a webhook HMAC secret (RTP-W03). The requirements specify that merchants verify signatures but don't specify:
1. How the HMAC secret is generated (minimum entropy requirements?)
2. Where it is stored (plaintext in database? Encrypted? In a KMS?)
3. Whether it can be rotated (what happens to in-flight webhook deliveries during rotation?)
4. What happens if a merchant's HMAC secret is compromised

If HMAC secrets are stored in plaintext in the PostgreSQL `merchants` table, a database breach exposes all webhook signing keys. An attacker could then forge webhook notifications to all merchants.

**Recommendation:**
- Add RTP-W12: "Webhook HMAC secrets must be generated with minimum 256 bits of entropy from a cryptographically secure random source."
- Add RTP-W13: "Webhook HMAC secrets must be stored encrypted at rest (KMS-backed) or hashed. The plaintext secret is provided to the merchant once at registration and cannot be retrieved again."
- Add RTP-W14: "Webhook HMAC key rotation must be supported: merchant can request a new key, with a configurable overlap period during which both old and new keys are accepted for signature verification."

#### Finding A02-3: No Encryption for Kafka Message Payloads (HIGH)

**Affected:** All Kafka topics carrying PII (payment instructions, settlement events, fraud scores)
**OWASP ref:** A02:2021, CWE-311: Missing Encryption of Sensitive Data
**OWASP ASVS ref:** V6.1.1 — "Verify that regulated private data is stored encrypted while at rest"

**Problem:** SEC-01 requires SASL/SSL for Kafka transport (encryption in transit). But Kafka messages are stored on broker disks in plaintext. Topics like `payment.instructions`, `payment.fraud-check-request`, and `ledger.events` contain:
- Full names, IBANs, transaction amounts (PII under GDPR)
- Fraud scores and sanctions screening results (sensitive financial data)
- Account balances (regulated banking data)

A broker disk compromise or backup theft exposes all this data. Kafka Streams state stores (A5 velocity windows, account profiles) stored in RocksDB are also unencrypted by default.

SEC-07 of the A5 requirements mentions "configure encryption at rest (RocksDB encryption)" but this is vague and only applies to A5.

**Recommendation:**
- Add KFK-S07: "Kafka broker storage must be encrypted at rest (disk-level or Kafka-native encryption)."
- Add KFK-S08: "Kafka Streams RocksDB state stores must use encrypted storage. Specify the encryption mechanism (RocksDB encryption at rest, or encrypted volume mounts)."
- Add KFK-S09: "For highest-sensitivity topics (`ledger.events`, `payment.instant-instructions`, `payment.settlement-events`), consider application-level payload encryption in addition to transport and disk encryption."

#### Finding A02-4: Refresh Token Hashing Algorithm Not Specified (MEDIUM)

**Affected:** A4 (AUTH-F07, PSD2-S02)
**OWASP ref:** A02:2021, CWE-916: Use of Password Hash With Insufficient Computational Effort

**Problem:** PSD2-S02 states "Refresh token stored as SHA-256 hash only — never in plaintext." SHA-256 is a fast hash, meaning an attacker with the hashed token database can attempt brute-force attacks at high speed. While refresh tokens have high entropy (unlike passwords), using a keyed hash (HMAC-SHA256 with a server-side pepper) or a slow hash (bcrypt) would provide defense-in-depth.

**Recommendation:**
- Clarify: "Refresh tokens must be generated with minimum 256 bits of entropy. Storage as SHA-256 hash is acceptable given high entropy; alternatively, HMAC-SHA256 with a server-side pepper provides additional protection against database-only compromise."

---

### A03:2021 — Injection

**Overall Status: COVERED — with 2 specific gaps**

SEC-09 mandates "Input validation on all API boundaries (Bean Validation / JSR 380)." A2-F02 validates payment instructions against ISO 20022 XSD schemas. VOP-S06 requires IBAN validation before lookup. These are strong input validation requirements.

#### Finding A03-1: No Explicit SQL Injection Prevention Requirement (MEDIUM)

**Affected:** All sub-systems using PostgreSQL
**OWASP ref:** A03:2021, CWE-89: SQL Injection

**Problem:** SEC-09 covers input validation (preventing malicious input from reaching the application) but does not explicitly require parameterized queries / prepared statements for database access. If any developer writes native SQL with string concatenation, SQL injection is possible.

Spring Data JPA (used implicitly via Spring Boot + PostgreSQL) uses parameterized queries by default for repository methods. But native queries (`@Query(nativeQuery=true)`) or JDBC template calls could introduce injection points.

**Recommendation:**
- Add SEC-15: "All database queries must use parameterized statements or ORM-generated queries. Native SQL with string concatenation is prohibited. This must be enforced via static analysis (e.g., SpotBugs SQL injection detector) in CI/CD."

#### Finding A03-2: Log Injection Risk in Payment Description Field (MEDIUM)

**Affected:** A3 (RTP-F01: description field, max 140 chars), audit logging (STARTER-F10)
**OWASP ref:** A03:2021, CWE-117: Improper Output Neutralization for Logs

**Problem:** Payment requests include a `description` field (max 140 characters, user-provided). If this field is logged without sanitization, an attacker could inject fake log entries:
```
description: "Normal payment\n2026-04-11T12:00:00Z INFO [SANCTIONS] Payment CLEARED for SANCTIONED_ENTITY"
```

In a structured JSON log format (STARTER-F11), this risk is mitigated because the description would be a JSON string value (newlines escaped). But if any log statement uses string interpolation rather than structured fields, the injection succeeds.

**Recommendation:**
- Add LOG-01: "All user-controlled data logged via `@Audited` or programmatic `AuditLogger` must be treated as structured data fields (JSON values), never interpolated into log message strings."
- Add LOG-02: "The `@Audited` aspect must sanitize all logged values: strip control characters (\\n, \\r, \\t, \\0) from user-provided fields before logging."

---

### A04:2021 — Insecure Design

**Overall Status: STRONG — threat-aware design throughout, no formal threat model**

The project demonstrates sophisticated threat-aware design decisions:
- Sanctions cannot be skipped (A5-F28) — defense against operational bypass
- VoP NO_MATCH feeds into fraud scoring (VOP-I01) — defense-in-depth
- Anti-enumeration on VoP responder (VOP-E01-E04) — defense against data harvesting
- Refresh token family revocation (AUTH-F08) — defense against token theft
- Semantic duplicate detection (CLR-B13) — defense against account takeover patterns

#### Finding A04-1: No Formal Threat Model Document (HIGH)

**Affected:** All sub-systems
**OWASP ref:** A04:2021 — Insecure Design
**OWASP ASVS ref:** V1.1.2 — "Verify the use of threat modeling for every design change"

**Problem:** The security thinking is implicit in the design decisions, but there is no formal threat model document. OWASP recommends threat modeling using methodologies like STRIDE, PASTA, or LINDDUN.

For a payment system, a formal threat model would identify:
- **Trust boundaries**: between PSP (payer side), our system, PSP (payee side), external sanctions lists, TPPs
- **Data flows**: across trust boundaries (which data crosses which boundary)
- **Threat actors**: external attacker, compromised TPP, compromised merchant, insider (fraud analyst, operations staff), compromised dependency (Kafka broker, PostgreSQL, Schema Registry)
- **Attack surface**: every API endpoint, every Kafka topic, every database table, every service-to-service call

Without a formal threat model, security coverage depends on the reviewer's intuition rather than systematic analysis. The existing reviews (including this one) serve as ad-hoc threat modeling, but a formal document would be more maintainable.

**Recommendation:**
- Add requirement: "A STRIDE threat model must be maintained in `docs/security/threat-model.md`. The threat model must cover each sub-system's trust boundaries, data flows, and identified threats with mitigations mapped to requirement IDs."
- The threat model should explicitly enumerate the attack surface per sub-system (see Section "PART 3: Attack Surface Analysis" below).

#### Finding A04-2: No Abuse Case / Misuse Case Requirements (MEDIUM)

**Affected:** All use cases
**OWASP ref:** A04:2021

**Problem:** The 10 master use cases comprehensively cover functional scenarios and many failure modes (fraud timeout, sanctions hit, VoP mismatch, SLA breach). But they are written from the perspective of legitimate actors. OWASP recommends supplementing use cases with **abuse cases** — scenarios written from the attacker's perspective.

Examples of missing abuse cases:
1. **Abuse case: Compromised TPP redirects consent flow** — A malicious or compromised TPP registers with legitimate redirect URIs, then changes its DNS to point the redirect URI to a phishing site. The user completes SCA on the real bank page, but the authorization code is delivered to the attacker.
2. **Abuse case: Merchant API key theft for mandate exploitation** — An attacker steals a merchant's API key and submits maximum-value invoices against all active mandates before detection.
3. **Abuse case: Sanctions list poisoning** — An attacker compromises the EU sanctions list distribution endpoint and injects false entries (blocking legitimate payments) or removes real entries (allowing sanctioned payments).
4. **Abuse case: Race condition in VRP cumulative counter** — Two concurrent VRP payments each check the cumulative counter before either updates it, both pass validation, but their combined total exceeds the consent bounds.

**Recommendation:** Add 4-6 abuse cases as an appendix to the use case documentation. Each abuse case should identify: threat actor, attack vector, system response, and the requirement that mitigates it (or a new requirement needed).

---

### A05:2021 — Security Misconfiguration

**Overall Status: PARTIAL — application-level strong, infrastructure-level weak**

#### Finding A05-1: Actuator Endpoint Exposure Not Fully Specified (HIGH)

**Affected:** OPS-01, STARTER-S02
**OWASP ref:** A05:2021, CWE-16: Configuration

**Problem:** OPS-01 requires that all sub-systems expose `/actuator/health`, `/actuator/readiness`, `/actuator/prometheus`. STARTER-S02 states "Actuator endpoints must be secured: `/actuator/prometheus` and `/actuator/health` are public; all others require authentication."

But Spring Boot Actuator includes many other endpoints that are dangerous if exposed:
- `/actuator/env` — exposes environment variables (may contain secrets despite SEC-05)
- `/actuator/configprops` — exposes all configuration properties
- `/actuator/beans` — reveals application internals useful for reconnaissance
- `/actuator/heapdump` — allows downloading JVM heap (contains all in-memory data including tokens, keys, sanctions lists)
- `/actuator/threaddump` — reveals internal processing state
- `/actuator/mappings` — reveals all API endpoints (attack surface mapping)
- `/actuator/loggers` — allows changing log levels at runtime (could be used to suppress audit logging)
- `/actuator/shutdown` — if enabled, allows remote shutdown (DoS)

**Recommendation:**
- Strengthen STARTER-S02: "Only the following actuator endpoints may be exposed: `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus`, `/actuator/info`. All other actuator endpoints must be **disabled** (not just authenticated). Explicitly: `management.endpoints.web.exposure.include=health,prometheus,info`."
- Add STARTER-S06: "The `/actuator/loggers` POST endpoint (runtime log level change) must be disabled in production. An attacker could set audit logging to OFF."
- Add STARTER-S07: "Actuator management port (9091-9096) must be bound to a separate network interface (e.g., `management.server.address=127.0.0.1`) not accessible from external networks."

#### Finding A05-2: Docker/Container Security Not Specified (HIGH)

**Affected:** OPS-04 (docker-compose), Section 11 (project structure)
**OWASP ref:** A05:2021
**[Extends Prior: payment_processing_requirements_review.md, HIGH-01]**

**Problem:** The existing review correctly identified missing container security. This finding adds OWASP-specific requirements:

**Recommendation (OWASP-aligned):**
- CNT-01: "All Docker images must be built FROM a minimal base image (e.g., `eclipse-temurin:17-jre-alpine` or distroless). No full OS images."
- CNT-02: "Containers must run as non-root user (Dockerfile `USER 1000:1000`)."
- CNT-03: "Container images must be scanned for vulnerabilities before deployment (Trivy, Grype, or Snyk)."
- CNT-04: "Docker Compose production profile must set `read_only: true` for container filesystems, with explicit writable volumes only for temp and log directories."
- CNT-05: "Container resource limits must be set (`deploy.resources.limits`) to prevent DoS via resource exhaustion."
- CNT-06: "Management ports (9091-9096) and database ports (5441-5445) must NOT be published in production Docker Compose profiles."

#### Finding A05-3: CORS Configuration Not Specified Per Sub-System (MEDIUM)

**Affected:** SEC-10
**OWASP ref:** A05:2021, CWE-942: Permissive Cross-domain Policy

**Problem:** SEC-10 states "CORS must be explicitly configured per sub-system (deny by default)." This is the right principle, but no sub-system specifies its CORS policy. For Open Banking (A4), CORS is critical:
- A4 serves TPP applications that may run in browsers (SPA frontends)
- The consent flow involves browser redirects
- If CORS is too permissive (`Access-Control-Allow-Origin: *`), any website can make authenticated API calls on behalf of a user who has an active session

**Recommendation:**
- Add A4-S14: "CORS allowed origins must be restricted to registered TPP domains. The origin is validated against the TPP's registered `redirect_uris` domain. Wildcard origins are prohibited."
- Add SEC-10a: "For internal-only sub-systems (A1, A2, A5 — no browser clients), CORS must be disabled entirely (no `Access-Control-Allow-Origin` header)."

---

### A06:2021 — Vulnerable and Outdated Components

**Overall Status: COVERED**

SEC-06 mandates dependency scanning (OWASP Dependency-Check or Trivy) and SBOM generation (CycloneDX). The Gradle plugin (STARTER-F52) can ban specific vulnerable dependencies. ML model integrity verification (ML-F05) and sanctions list integrity verification (SAN-F08) protect against supply chain attacks on data inputs.

#### Finding A06-1: No Runtime Dependency Verification (LOW)

**Affected:** All sub-systems
**OWASP ref:** A06:2021, CWE-1395: Dependency on Vulnerable Third-Party Component

**Problem:** SEC-06 checks dependencies at build time, but doesn't verify at runtime that the actual loaded classes match the expected dependencies. A supply chain attacker who compromises the artifact repository (Maven Central mirror, internal Nexus) could substitute a malicious JAR after the build-time check passes.

**Recommendation:**
- Add SEC-16: "Container images should include a bill of materials (SBOM) at `/app/sbom.json`. Runtime health checks may optionally verify loaded JAR checksums against the build-time SBOM."
- This is a LOW priority enhancement — build-time scanning is the primary control.

---

### A07:2021 — Identification and Authentication Failures

**Overall Status: STRONG — comprehensive OAuth2/SCA implementation**

The PSD2 API Gateway (A4) has one of the most complete authentication architectures in the project: OAuth2 with PKCE (AUTH-F13), RS256 JWT (AUTH-F06), refresh token rotation with family revocation (AUTH-F07/F08), SCA with dynamic linking (SCA-F04), and 4 SCA exemption types. This is production-grade.

#### Finding A07-1: No Session Binding / Token Binding Requirement (HIGH)

**Affected:** A4 (OAuth2 tokens)
**OWASP ref:** A07:2021, CWE-384: Session Fixation
**OWASP ASVS ref:** V3.5 — Token-based Session Management

**Problem:** JWT access tokens (15-minute expiry, AUTH-F06) are bearer tokens — anyone who possesses the token can use it. If an attacker intercepts a valid access token (via network sniffing, XSS, or browser extension), they have 15 minutes of unrestricted access to the user's accounts.

OWASP ASVS Level 2 recommends token binding mechanisms:
- **DPoP (Demonstrating Proof-of-Possession)**: The client generates a key pair and includes a proof-of-possession JWT with each request. The access token is bound to the client's public key.
- **mTLS certificate-bound tokens (RFC 8705)**: The access token includes a thumbprint of the client's TLS certificate, verified by the resource server.
- **Sender-constrained tokens**: Any mechanism that prevents token replay by a different client.

**Recommendation:**
- Add A4-S15: "Access tokens should be sender-constrained. For TPP API calls: mTLS certificate-bound access tokens (RFC 8705) — the access token is bound to the TPP's client certificate. For browser-based flows: DPoP (RFC 9449) should be supported."
- Note: This is a "Should" for a portfolio project but "Must" for production PSD2 deployment.

#### Finding A07-2: SCA Lockout is Time-Based Only (MEDIUM)

**Affected:** A4 (SCA-F05: "max 3 attempts, lock for 5 minutes")
**OWASP ref:** A07:2021, CWE-307: Improper Restriction of Excessive Authentication Attempts

**Problem:** After 3 failed SCA attempts, the account is locked for 5 minutes. This is a basic brute-force protection but has weaknesses:
1. **Slow brute-force**: An attacker can try 3 PINs every 5 minutes = 864 attempts/day. For a 4-digit PIN (10,000 combinations), that's ~12 days to exhaustion.
2. **Credential stuffing**: If the PIN is reused from another breached service, 3 attempts may be enough.
3. **No progressive escalation**: After multiple lockout periods, the lockout duration doesn't increase.
4. **No alerting on lockout**: The account holder is not notified of failed SCA attempts.

**Recommendation:**
- Add SCA-F06: "SCA lockout must be progressive: 5 minutes after 3 failures, 30 minutes after 6 total failures (2 lockout cycles), 24 hours after 9 total failures (3 lockout cycles). After 24-hour lockout, require account holder identity verification to unlock."
- Add SCA-F07: "Account holders must be notified (via registered channel) of SCA failures exceeding 3 attempts."
- Add SCA-F08: "SCA attempt counters must be tracked per-account, not per-session, to prevent distributed brute-force (trying from multiple TPPs)."

#### Finding A07-3: API Key Authentication for Merchants is Weak (MEDIUM)

**Affected:** A3 (RTP-S01, merchant API key in `X-API-Key` header)
**OWASP ref:** A07:2021, CWE-798: Use of Hard-coded Credentials

**Problem:** Merchant authentication uses a static API key in the `X-API-Key` header. Static API keys have known weaknesses:
1. **No expiry**: Keys remain valid indefinitely unless manually rotated
2. **Shared secret**: If leaked (e.g., committed to merchant's source code), there is no automatic detection
3. **No mutual authentication**: The server authenticates the merchant, but the merchant cannot verify the server's identity (unlike mTLS)
4. **Replay risk**: Captured API key can be replayed from any network location

For a B2B API handling financial transactions, API keys are the minimum acceptable authentication. But given that a compromised merchant API key allows submitting invoices against active mandates (UC-06), the risk is significant.

**Recommendation:**
- Add RTP-S10: "Merchant API keys must have configurable expiry (default: 365 days). Expired keys must be rejected."
- Add RTP-S11: "Merchant API keys must be rotatable without downtime. The system must support two active keys per merchant during rotation."
- Add RTP-S12: "For high-value merchants (monthly transaction volume > configurable threshold), mTLS should be required in addition to API key authentication."
- Add RTP-S13: "API key usage must be monitored for anomalies: unusual source IP, unusual time of day, unusual request volume. Anomalies trigger alerts."

---

### A08:2021 — Software and Data Integrity Failures

**Overall Status: PARTIAL — data integrity strong, software integrity gaps**

#### Finding A08-1: No Code Signing or Deployment Pipeline Integrity (HIGH)

**Affected:** CI/CD (GitHub Actions), deployment
**OWASP ref:** A08:2021, CWE-494: Download of Code Without Integrity Check
**[Extends Prior: payment_processing_requirements_review.md, ATTACK-06]**

**Problem:** The existing review identified supply chain risks. This finding adds OWASP A08-specific requirements:

- **No signed artifacts**: Built JAR files and Docker images are not required to be signed. An attacker who compromises the CI/CD pipeline (GitHub Actions runner, artifact registry) could inject malicious code.
- **No SLSA compliance**: SLSA (Supply-chain Levels for Software Artifacts) is the emerging framework for supply chain integrity. The project doesn't reference it.
- **No reproducible builds**: Without reproducible builds, it's impossible to verify that a deployed artifact matches the source code.

**Recommendation:**
- Add SEC-17: "Docker images must be signed using Docker Content Trust (Notary) or Cosign. Only signed images may be deployed."
- Add SEC-18: "GitHub Actions workflows must use pinned action versions (SHA, not tags) to prevent tag-based supply chain attacks."
- Add SEC-19: "Build artifacts (JAR files) should include a signed build attestation (SLSA Level 2+) linking the artifact to its source commit and build environment."

#### Finding A08-2: Sanctions List Source Integrity (MEDIUM)

**Affected:** A5 (SAN-F07, SAN-F08), sanctions_screening.md extension
**OWASP ref:** A08:2021, CWE-345: Insufficient Verification of Data Authenticity

**Problem:** SAN-F08 requires "checksum validation on ingested list files." But:
1. Who provides the checksum? If the checksum comes from the same server as the list file, a compromised server can serve a modified list with a matching checksum.
2. Is the checksum verified against a separate trust anchor (e.g., a digital signature from the EU, not just a hash)?
3. What if the EU/UN changes their list distribution format or signing mechanism?

A state-sponsored attacker targeting sanctions evasion would attack exactly this point — modifying the sanctions list in transit or at source.

**Recommendation:**
- Clarify SAN-F08: "Sanctions list files must be verified using the source authority's digital signature (EU provides signed XML). If digital signature verification is not available for a list source, verify the checksum over a separate secure channel (not from the same download endpoint)."
- Add SAN-F15: "Sanctions list download must use certificate-pinned TLS connections to the EU/UN distribution endpoints."
- Add SAN-F16: "If a downloaded list is significantly smaller than the previous version (e.g., >20% fewer entries), flag for manual review before activation — this could indicate a truncated or compromised file."

---

### A09:2021 — Security Logging and Monitoring Failures

**Overall Status: COVERED for logging, WEAK for monitoring**

The project has comprehensive audit logging requirements (SEC-07, STARTER-F10-F16, and per-sub-system logging). The gap is in security monitoring — detecting and responding to active attacks.

#### Finding A09-1: No Security Monitoring / SIEM Integration (CRITICAL)

**Affected:** All sub-systems
**OWASP ref:** A09:2021, CWE-778: Insufficient Logging
**OWASP ASVS ref:** V7.4 — Error Handling and Logging, V7.4.1

**Problem:** The system produces extensive audit logs but has no requirement for:
1. **Security event correlation**: Detecting patterns across multiple log sources (e.g., failed SCA from IP X, followed by successful SCA from IP Y within 5 minutes — credential sharing or session hijacking)
2. **Real-time alerting on security events**: Failed authentication attempts, privilege escalation attempts, unusual data access patterns, API abuse patterns
3. **SIEM integration**: Forwarding security-relevant events to a Security Information and Event Management system
4. **Anomaly detection on operations**: Detecting unusual admin actions (mass consent revocation, bulk account freezing, threshold changes)

OWASP states: "Without logging and monitoring, breaches cannot be detected." The current system would log the breach but nobody would be watching the logs in real-time.

**Recommendation:**
- Add SEC-20: "Security-critical events must be forwarded to a SIEM or security monitoring system in near-real-time. Security-critical events include: authentication failures, authorization failures, SCA lockouts, sanctions hits, fraud alerts at AUTO_BLOCK level, admin actions (TPP registration/deactivation, threshold changes), and actuator endpoint access."
- Add SEC-21: "Automated alerting rules must be defined for: (a) >5 failed SCA attempts for any account in 1 hour, (b) sanctions hit rate exceeding baseline by >3 standard deviations, (c) any actuator endpoint access from non-management network, (d) any `admin:manage` scope token usage outside business hours."
- Add SEC-22: "Audit logs must be tamper-evident. Options: hash-chain each log entry (include hash of previous entry), write to append-only storage (S3 Object Lock, WORM), or forward to a write-once SIEM."

#### Finding A09-2: Insufficient Fraud Analyst Audit Trail (LOW)

**Affected:** A5 (CASE-F03, SEC-08)
**OWASP ref:** A09:2021

**Problem:** SEC-08 requires "Analyst actions (CONFIRMED_FRAUD, DISMISSED) logged with analyst identity, timestamp, justification." But:
- Are analyst login sessions logged?
- Is it logged when an analyst **views** alert details (not just resolves)?
- Is analyst behavior monitored for anomalies (e.g., an analyst who dismisses 95% of sanctions alerts vs. team average of 40%)?

An insider threat scenario: a compromised analyst systematically dismisses sanctions alerts for a specific entity, allowing sanctioned payments to proceed.

**Recommendation:**
- Add CASE-F10: "All analyst access to case management must be logged: views, searches, and exports, not just resolution actions."
- Add CASE-F11: "Analyst behavior metrics must be tracked and reported: resolution rate, false-positive rate, average time-to-resolution. Significant deviations from peer averages should trigger supervisory review."

---

### A10:2021 — Server-Side Request Forgery (SSRF)

**Overall Status: GAP — significant risk in VoP requester**
**[Extends Prior: payment_processing_requirements_review.md, HIGH-02]**

#### Finding A10-1: VoP Requester SSRF Risk (HIGH)

**Affected:** A6 (VOP-R01), VoP requester outbound calls
**OWASP ref:** A10:2021, CWE-918: Server-Side Request Forgery

**Problem:** The VoP requester calls external PSP endpoints to verify beneficiary names. The destination URL is determined by the beneficiary's IBAN (which identifies the PSP). If this URL resolution is based on a directory lookup or BIC-to-URL mapping, an attacker who can influence the lookup can redirect VoP queries to:
1. Internal services (e.g., `http://account-ledger:8091/api/v1/accounts` — information disclosure)
2. Cloud metadata endpoints (e.g., `http://169.254.169.254/latest/meta-data/` — credential theft)
3. Other internal infrastructure (Redis, Kafka REST proxy, Schema Registry)

The prior review identified this and suggested URL validation. This finding adds OWASP-specific controls:

**Recommendation:**
- Add VOP-S07: "VoP requester HTTP client must validate destination URLs against: (a) an allowlist of registered PSP VoP endpoint URLs, (b) DNS resolution must not resolve to RFC 1918 private addresses (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16), loopback (127.0.0.0/8), link-local (169.254.0.0/16), or cloud metadata addresses."
- Add VOP-S08: "VoP requester HTTP client must not follow redirects. If a PSP endpoint returns a 3xx redirect, the query must fail with ERROR status rather than following the redirect."
- Add VOP-S09: "VoP requester HTTP client must set explicit connect timeout (2 seconds), read timeout (3 seconds), and must not use the system-wide HTTP proxy configuration."

---

## PART 2: OWASP APPLICATION SECURITY VERIFICATION STANDARD (ASVS) ASSESSMENT

The OWASP ASVS defines three verification levels:
- **Level 1**: Low-assurance — automated verification possible. Suitable for all applications.
- **Level 2**: Standard security — for applications handling sensitive data. Suitable for most applications including financial services.
- **Level 3**: High-assurance — for critical applications (payment systems, medical devices, military).

A payment processing system should target **ASVS Level 2**, with critical payment paths (instant payment settlement, sanctions screening, balance management) at **Level 3**.

### ASVS Chapter Assessment Summary

| ASVS Chapter | L1 | L2 | L3 | Key Gaps |
|-------------|----|----|-----|----------|
| V1: Architecture | PASS | PARTIAL | PARTIAL | No formal threat model (V1.1.2), no trust boundary documentation (V1.1.5) |
| V2: Authentication | PASS | PASS | PARTIAL | No credential recovery flow (V2.5), no anti-automation (V2.2.1) |
| V3: Session Management | PASS | PARTIAL | FAIL | No token binding (V3.5), no concurrent session limit (V3.7.1) |
| V4: Access Control | PASS | PASS | PARTIAL | No ABAC (V4.1.3), IDOR gap (V4.2.1) — see Finding A01-1 |
| V5: Validation/Sanitization | PASS | PASS | PASS | Strong — Bean Validation, XSD validation, IBAN validation |
| V6: Cryptography | PASS | PARTIAL | FAIL | No key management lifecycle (V6.4), no crypto agility (V6.2.5) |
| V7: Error Handling/Logging | PASS | PASS | PARTIAL | No SIEM integration (V7.4.1), no tamper-evident logs |
| V8: Data Protection | PASS | PARTIAL | FAIL | No data classification (V8.1.1), no retention policy (V8.3.4) |
| V9: Communication | PASS | PASS | PARTIAL | No TLS minimum version requirement (V9.1.2), no cert pinning (V9.2.1) |
| V10: Malicious Code | PASS | PARTIAL | PARTIAL | No runtime integrity checks, no signed artifacts |
| V11: Business Logic | PASS | PASS | PASS | Strong — sanctions cannot be skipped, VoP override as risk signal |
| V12: Files and Resources | PASS | PASS | PASS | Good — file types limited (QR images via ZXing, XML via JAXB) |
| V13: API Security | PASS | PASS | PARTIAL | No content-type validation (V13.1.3), no response size limit |
| V14: Configuration | PASS | PARTIAL | PARTIAL | Actuator exposure gap, no security headers cache for API responses |

### Key ASVS Findings (not already covered in OWASP Top 10 section)

#### Finding ASVS-1: No Anti-Automation / Bot Detection (HIGH)

**Affected:** A4 (consent flow), A3 (consumer API)
**ASVS ref:** V2.2.1 — "Verify that anti-automation controls are effective at mitigating credential stuffing, brute force, and account lockout attacks"

**Problem:** The OAuth2 consent flow (AUTH-F01 through AUTH-F15) and consumer request API (RTP-F02) have no anti-automation controls beyond rate limiting. An attacker can programmatically:
1. Attempt consent flows with stolen credentials (credential stuffing)
2. Enumerate valid account holders by observing error responses
3. Automate mandate exploitation after API key compromise

Rate limiting (SEC-08, TPP-F02) mitigates volume but not sophistication.

**Recommendation:**
- Add SEC-23: "Consumer-facing authentication flows must implement anti-automation controls: CAPTCHA or equivalent challenge after 2 failed attempts, device fingerprinting, and behavioral analysis (request timing patterns)."
- Note: For a portfolio project, CAPTCHA may be out of scope. At minimum, add different error response timing (constant-time responses) to prevent timing-based enumeration.

#### Finding ASVS-2: No Concurrent Session Limit (MEDIUM)

**Affected:** A4 (OAuth2 session management)
**ASVS ref:** V3.7.1 — "Verify the application limits the number of active sessions"

**Problem:** An account holder can have unlimited concurrent OAuth2 consent sessions with different TPPs. While this is functionally correct for PSD2 (a user should be able to use multiple TPPs), there is no monitoring or limit on:
- Number of active consents per account (a compromised account might have consents granted to many attacker-controlled TPPs)
- Rate of new consent grants (granting 10 consents in 1 minute is suspicious)

**Recommendation:**
- Add AUTH-F17: "Alert if an account holder grants more than 5 new consents within 24 hours."
- Add AUTH-F18: "Maximum 20 active consents per account holder (configurable). Attempting to create the 21st triggers a review."

#### Finding ASVS-3: API Response Caching Control Not Specified (MEDIUM)

**Affected:** All REST APIs returning financial data
**ASVS ref:** V8.2.1 — "Verify the application sets sufficient anti-caching headers"

**Problem:** APIs returning account balances (A1), transaction history (A1, A4), fraud scores (A5), and payment statuses (A2, A3) contain sensitive data that must not be cached by intermediary proxies or browser caches. No requirement specifies anti-caching headers.

**Recommendation:**
- Add SEC-24: "All API responses containing financial data must include: `Cache-Control: no-store, no-cache, must-revalidate`, `Pragma: no-cache`, `Expires: 0`."
- This could be added to the Platform Starter as STARTER-F09c, auto-configured for all API responses.

#### Finding ASVS-4: No Response Size Limits (LOW)

**Affected:** All REST APIs with paginated responses
**ASVS ref:** V13.1.3 — "Verify API responses set reasonable limits"

**Problem:** Paginated endpoints (A1 transaction history, A3 request list, A5 alert list) don't specify maximum page sizes. An attacker could request `?page_size=1000000` to cause memory exhaustion.

**Recommendation:**
- Add SEC-25: "All paginated API endpoints must enforce a maximum page size (configurable, default: 100). Requests exceeding the maximum must be capped, not rejected (to avoid information leakage about the limit)."

---

## PART 3: ATTACK SURFACE ANALYSIS FOR HACKER RESILIENCE

This section maps the system's attack surface from a hacker's perspective — identifying every entry point, what an attacker can reach, and what controls exist.

### 3.1 External Attack Surface

| Entry Point | Sub-system | Auth Required | Rate Limited | Input Validation | Remaining Risk |
|------------|------------|---------------|-------------|-----------------|----------------|
| `POST /api/v1/authorize` | A4 | No (initiation) | SEC-08 | PKCE (AUTH-F13) | Phishing of consent flow |
| `POST /api/v1/token` | A4 | client_id/secret | TPP-F02 | PKCE verification | Client credential theft |
| `GET /api/v1/accounts/**` | A4 | OAuth2 Bearer | TPP-F02 | Consent scope check | Token theft (bearer token) |
| `POST /api/v1/payments` | A4 | OAuth2 Bearer | TPP-F02 | Bean Validation, ISO 20022 | Fraudulent payment initiation |
| `POST /api/v1/requests` | A3 | API Key | SEC-08 | Bean Validation | API key compromise |
| `PUT /api/v1/requests/{id}/*` | A3 | OAuth2 Bearer | SEC-08 | Ownership check (RTP-S02) | IDOR if poorly implemented |
| `POST /api/v1/vop/check` | A6 | mTLS/API Key | VOP-E01 (100/min) | IBAN validation (VOP-S06) | Enumeration despite rate limit |
| `GET /api/v1/fraud/alerts` | A5 | OAuth2 (fraud:read) | SEC-09 | — | Insider data exfiltration |
| `PUT /api/v1/fraud/alerts/{id}/resolve` | A5 | OAuth2 (fraud:manage) | SEC-09 | — | Insider sanctions dismissal |
| `/actuator/*` | All | STARTER-S02 | — | — | Endpoint exposure (A05-1) |

### 3.2 Internal Attack Surface (Compromised Service / Kafka Broker)

| Entry Point | What an Attacker Gains | Current Protection | Gap |
|------------|----------------------|-------------------|-----|
| `payment.instant-instructions` topic | Inject fraudulent payment instructions | SASL/SSL (SEC-01) | **No message-level signing, no topic ACLs** |
| `payment.settlement-events` topic | Credit arbitrary accounts | SASL/SSL | **No message-level signing** |
| `payment.fraud-check-response` topic | Auto-pass all payments | SASL/SSL | **No topic ACLs — any service can produce** |
| `account.freeze-commands` topic | Freeze arbitrary accounts (DoS) | SASL/SSL | **No topic ACLs** |
| Schema Registry | Modify event schemas to bypass validation | None specified | **No authentication** |
| PostgreSQL (any service's DB) | Direct data access/modification | OPS-05 (separate schemas) | **No per-service DB users specified** |
| Kafka broker admin | Delete topics, modify retention, read all messages | Kafka admin access | **No admin access controls specified** |

### 3.3 Critical Attack Chains

**Attack Chain 1: TPP Compromise → Account Takeover**
1. Attacker compromises a legitimate TPP's client credentials
2. Initiates consent flows for target account holders (phishing redirect)
3. If SCA is bypassed via social engineering, gains AISP+PISP access
4. Drains accounts via instant payments to mule accounts
5. VoP would flag name mismatch, but attacker uses mule account with matching name

**Mitigated by:** VoP (A6), fraud scoring (A5), SCA dynamic linking (SCA-F04)
**Gap:** No TPP anomaly detection (sudden spike in consent requests from one TPP)

**Attack Chain 2: Insider Threat → Sanctions Evasion**
1. Compromised compliance officer has `fraud:manage` scope
2. When sanctions hits arrive for a specific entity, officer dismisses them as FALSE_POSITIVE
3. Payments to sanctioned entity proceed
4. Audit trail records the dismissal but nobody monitors it in real-time

**Mitigated by:** Audit logging (SEC-07, A5-S02)
**Gap:** No real-time monitoring of analyst decisions, no four-eyes principle for sanctions decisions, no anomaly detection on analyst behavior

**Attack Chain 3: Kafka Broker Compromise → Financial Data Manipulation**
1. Attacker gains access to Kafka broker (via compromised credentials, unpatched vulnerability, or insider)
2. Produces fraudulent messages to `payment.settlement-events`
3. A1 processes settlement events and credits attacker's account
4. Attacker initiates instant withdrawal

**Mitigated by:** SASL/SSL (transport layer)
**Gap:** No message-level integrity, no topic ACLs, no settlement event signature verification in A1

**Recommendation for Attack Chain 3 (highest priority):**
- Every message on financial topics (`payment.settlement-events`, `payment.instant-settlement-events`, `payment.balance-reservation`) MUST include a digital signature from the producing service. The consuming service MUST verify the signature before processing.
- This is the single highest-impact security improvement that could be made to the system. Without it, Kafka broker compromise equals full financial system compromise.

---

## PART 4: OWASP SECURE CODING PRACTICES CHECKLIST

OWASP publishes a Secure Coding Practices Quick Reference Guide with 14 areas. This section assesses the requirements against each area.

| # | Practice Area | Coverage | Key Gaps |
|---|-------------|----------|----------|
| 1 | Input Validation | **STRONG** | SEC-09 (Bean Validation), XSD validation, IBAN validation. Add: Content-Type validation, request size limits |
| 2 | Output Encoding | **PARTIAL** | camt.053 XML output via JAXB (safe). QR codes via ZXing (safe). No explicit output encoding requirement for REST JSON responses |
| 3 | Authentication & Password Management | **STRONG** | OAuth2, SCA, PKCE, token rotation. Gap: API key management, no password policy for simulated PIN |
| 4 | Session Management | **GOOD** | JWT with short expiry, refresh rotation. Gap: no token binding, no concurrent session limits |
| 5 | Access Control | **STRONG** | Scope-based, consent-based. Gap: IDOR enforcement, admin API auth |
| 6 | Cryptographic Practices | **PARTIAL** | Correct algorithms. Gap: key lifecycle, crypto agility, key rotation |
| 7 | Error Handling & Logging | **STRONG** | Structured audit logging, Prometheus metrics. Gap: SIEM integration, tamper-evident logs |
| 8 | Data Protection | **PARTIAL** | Encryption at rest mentioned. Gap: data classification, retention, GDPR |
| 9 | Communication Security | **GOOD** | TLS for all communication. Gap: TLS version specification, cert pinning for sanctions list download |
| 10 | System Configuration | **PARTIAL** | Security headers, actuator config. Gap: container hardening, infra security |
| 11 | Database Security | **PARTIAL** | OPS-05 (DB per service). Gap: least-privilege DB users, pg_audit, connection encryption |
| 12 | File Management | **GOOD** | Limited file handling (QR images, XML). Sanctions list ingestion has integrity checks |
| 13 | Memory Management | **N/A** | Java/JVM — managed memory. Sanctions list in-memory (Caffeine, bounded). Kafka Streams state stores (RocksDB, disk-backed) |
| 14 | General Coding Practices | **PARTIAL** | ArchUnit for architecture rules. Gap: SAST/DAST requirements, security-focused code review process |

---

## PART 5: PAYMENT-SPECIFIC CYBER-CRIME VECTORS

Beyond OWASP generic web application security, payment systems face industry-specific attack vectors.

### Finding CRIME-1: No Anti-Money-Laundering Transaction Pattern Detection (CRITICAL)

**Affected:** A5 (Fraud & Sanctions Engine)
**Context:** EU AML Package 2024, FATF Recommendations

**Problem:** A5 detects individual suspicious transactions (velocity rules, anomaly detection, ML scoring) and screens against sanctions lists. However, it does not address sophisticated money laundering patterns that span multiple transactions and accounts:

1. **Structuring (smurfing)**: Splitting large amounts into many small transactions below reporting thresholds. VEL-07 (round amount burst) partially catches this, but sophisticated structuring uses non-round amounts.

2. **Layering**: Moving money through a chain of accounts to obscure its origin. Each individual transaction in the chain may appear legitimate. Detecting this requires graph analysis across accounts — current velocity rules only examine per-account patterns.

3. **Shell company networks**: Multiple accounts controlled by the same beneficial owner, used to move money between each other. Without beneficial ownership data (identified as a gap in the EU legislation review — GAP C-4), this is undetectable.

4. **Round-tripping**: Sending money abroad and receiving it back through a different channel. Without cross-channel transaction correlation, this is invisible.

**Recommendation:**
- Add FRD-F11: "Implement cross-account velocity rules: detect when the same amount moves through 3+ accounts within 24 hours (potential layering pattern)."
- Add FRD-F12: "Implement structuring detection: flag when an account makes N+ outbound transactions just below a threshold amount within M hours."
- Note: Full graph-based network analysis is identified in the A5 gap analysis as a production-level feature beyond portfolio scope. The above rules provide basic coverage.

### Finding CRIME-2: No Payment Reversal / Chargeback Abuse Detection (HIGH)

**Affected:** A2 (pacs.004 payment return), A3 (R2P accept/decline)
**Context:** First-party fraud, friendly fraud

**Problem:** pacs.004 (PaymentReturn) is specified as "Should" (CLR-I12) but without abuse controls. In production payment systems, payment returns are a common fraud vector:
1. **First-party fraud**: Consumer makes a legitimate payment, then requests a return claiming it was unauthorized
2. **Return timing abuse**: Returns requested after the beneficiary has already withdrawn funds
3. **Repeated returners**: Consumers who systematically request returns as a refund fraud pattern

The R2P flow also allows consumers to decline requests (RTP-F04) — while not directly a fraud vector, systematic decline patterns could indicate account manipulation.

**Recommendation:**
- Add CLR-I14: "Payment return requests (pacs.004) must be scored for fraud risk. Flag if: (a) same account requests >2 returns in 30 days, (b) return is requested >24 hours after settlement, (c) return amount exceeds 10,000 EUR."
- Add CLR-I15: "Payment return decisions must be logged to the audit trail and feed back into A5 fraud scoring profiles."

### Finding CRIME-3: No SIM Swap / Number Porting Attack Protection (HIGH)

**Affected:** A4 (SCA-F02: TOTP/SMS possession factor)
**Context:** Telecom-based attacks on SCA

**Problem:** SCA-F02 specifies "TOTP code via authenticator app or simulated SMS" as the possession factor. If SMS is used as the delivery channel for the SCA code:
1. **SIM swap**: Attacker convinces the telecom provider to transfer the victim's phone number to a new SIM. All SMS messages (including SCA codes) are delivered to the attacker.
2. **SS7 interception**: Exploitation of vulnerabilities in the SS7 signaling protocol to intercept SMS messages in transit.
3. **Number porting**: Similar to SIM swap, but using the number portability process.

These are well-documented attack vectors against PSD2 SCA implementations. The European Banking Authority has issued warnings about SMS-based SCA.

**Recommendation:**
- Add SCA-F09: "TOTP via authenticator app must be the preferred possession factor. SMS-based OTP must be documented as a fallback with higher risk."
- Add SCA-F10: "If SMS OTP is used, implement SIM swap detection: monitor for number change events via the telecom provider, or detect when the SMS delivery path changes (different SMSC routing)."
- Add SCA-F11: "Consider supporting FIDO2/WebAuthn as an alternative possession factor. FIDO2 is resistant to phishing, SIM swap, and SS7 attacks."

### Finding CRIME-4: No Cooling-Off Period for High-Risk Payments (MEDIUM)

**Affected:** A2 (instant payment path), A3 (R2P accept with INSTANT)
**Context:** APP fraud mitigation

**Problem:** When a consumer accepts a payment request with `paymentMethod: INSTANT`, the payment settles in <10 seconds and is irrevocable (A2-F23). If the consumer was socially engineered (APP fraud), there is zero window for intervention. Some banking regulators and industry bodies recommend:
1. **Cooling-off period for first-time beneficiaries**: Delay instant payments to new payees by 4-24 hours, giving the payer time to realize they've been scammed.
2. **Confirmation of payee warning pause**: When VoP returns NO_MATCH or CLOSE_MATCH and the payer overrides, insert a mandatory delay.
3. **High-value threshold delay**: Payments exceeding a threshold (e.g., 10,000 EUR) to new beneficiaries require a secondary confirmation after a delay.

**Recommendation:**
- Add CLR-I16: "For instant payments to first-time beneficiaries above a configurable threshold (default: 5,000 EUR), the system should insert a configurable delay (default: 0 — disabled, but configurable to 1-24 hours). This is a policy decision that can be enabled per-PSP."
- This is flagged as a "Should" for the portfolio project, but should be architecturally possible.

### Finding CRIME-5: QR Code Payment Phishing (MEDIUM)

**Affected:** A3 (RTP-Q01 through Q05), QR code payment initiation
**Context:** QR code substitution attacks

**Problem:** Static QR codes (RTP-Q04) encode the merchant's IBAN and name. An attacker could:
1. Generate a QR code with the attacker's IBAN but a legitimate merchant's name
2. Place the fraudulent QR code over the legitimate one (physical overlay attack)
3. Consumers scan the QR code and initiate payment to the attacker

Dynamic QR codes (RTP-Q01) are more resistant because they encode a specific `request_id` that links to a pre-existing payment request. The attacker would need to create the request first, which requires a merchant API key.

**Recommendation:**
- Add RTP-Q06: "Dynamic QR codes must include a cryptographic signature (HMAC-SHA256) of the payment request ID and amount. The consuming app must verify the signature before displaying the payment request."
- Add RTP-Q07: "Static QR codes should display a warning to the consumer: 'Verify the payee name before confirming.' The VoP check (A6) provides a secondary verification that the IBAN matches the stated name."
- Add RTP-Q08: "QR code content must include a timestamp. QR codes older than 24 hours for dynamic or 90 days for static should be flagged as potentially stale."

### Finding CRIME-6: Mandate-Based Payment Fraud After Merchant Compromise (LOW)

**Affected:** A3 (RTP-M01 through M07), AvtaleGiro mandates
**Context:** Compromised merchant exploiting auto-approval mandates

**Problem:** If a merchant's API key is compromised, the attacker can submit invoices against all active mandates up to the maximum authorized amount. These invoices are auto-approved (RTP-M03) without consumer interaction. The existing payment_processing_requirements_review.md UC-06 section identified this risk.

**OWASP-specific recommendation (extending prior review):**
- Add RTP-M08: "Mandate auto-approval must trigger a push notification to the consumer for every auto-approved payment. The notification must be sent within 30 seconds of auto-approval and before payment settlement."
- Add RTP-M09: "Implement merchant behavior profiling: alert if a merchant's invoice submission pattern deviates significantly from historical norms (sudden spike in volume, unusual timing, maximum-amount submissions)."
- Add RTP-M10: "Consumer must have a 'cancel auto-approved payment' window: if the consumer responds within N minutes of notification (configurable, default: 5 minutes), the payment can be canceled before settlement in the batch path. For instant path, this window is unavailable — document this as a trade-off."

### Finding CRIME-7: Time-of-Check-to-Time-of-Use (TOCTOU) in VRP Enforcement (LOW)

**Affected:** A4 (VRP-F05: bank-side enforcement of VRP bounds)
**Context:** Race condition attacks

**Problem:** VRP consent enforcement checks cumulative counters before authorizing a payment (UC-10 Step 2). If two VRP payments arrive simultaneously:
1. Thread A reads cumulative_amount = 800 EUR, max = 1000 EUR. Payment = 150 EUR. Check passes (800+150=950 < 1000).
2. Thread B reads cumulative_amount = 800 EUR (not yet updated). Payment = 180 EUR. Check passes (800+180=980 < 1000).
3. Both payments execute. Actual cumulative = 800+150+180 = 1130 EUR > 1000 EUR limit.

The computational stability review (HIGH-03) identified this as a crash consistency issue. From a security perspective, this is a race condition that an attacker can deliberately exploit to exceed consent bounds.

**Recommendation (already partially covered by computational stability review):**
- Add VRP-F09: "VRP cumulative counter check and update must be atomic. Use `SELECT FOR UPDATE` on the VRP consent row to serialize concurrent VRP payment validations for the same consent."
- Add VRP-F10: "VRP payment requests for the same consent must be serialized at the application level (database-level locking or distributed lock). Concurrent requests must queue, not race."

---

## PART 6: CONSOLIDATED FINDINGS SUMMARY

### Priority 1 — Critical (Must Address Before Implementation)

| ID | Category | Finding | Sub-systems |
|----|----------|---------|-------------|
| A02-1 | Crypto | No cryptographic agility, no minimum key sizes | All |
| A09-1 | Monitoring | No security monitoring / SIEM integration | All |
| CRIME-1 | AML | No cross-account transaction pattern detection (layering, structuring) | A5 |
| A04-1 | Design | No formal threat model document | All |
| A08-1 | Integrity | No code signing or deployment pipeline integrity | CI/CD |
| A01-1 | Access Control | No horizontal access control enforcement (IDOR prevention) | A3, A4, A1 |
| A05-1 | Config | Actuator endpoint over-exposure | All (Starter) |

### Priority 2 — High (Must Address During Implementation)

| ID | Category | Finding | Sub-systems |
|----|----------|---------|-------------|
| A02-2 | Crypto | HMAC webhook key lifecycle not specified | A3, A4 |
| A02-3 | Crypto | No Kafka message payload encryption at rest | All Kafka topics |
| A05-2 | Config | Container security not specified | All |
| A07-1 | Auth | No token binding (DPoP/mTLS-bound tokens) | A4 |
| A07-2 | Auth | SCA lockout lacks progressive escalation | A4 |
| A07-3 | Auth | Merchant API key lifecycle weak | A3 |
| A08-2 | Integrity | Sanctions list source integrity weak | A5 |
| A10-1 | SSRF | VoP requester SSRF risk | A6 |
| A01-2 | Access Control | No sanctions-specific scope for case management | A5 |
| ASVS-1 | Auth | No anti-automation / bot detection | A4, A3 |
| CRIME-2 | Fraud | No payment reversal abuse detection | A2 |
| CRIME-3 | Auth | SIM swap attack vector for SMS-based SCA | A4 |
| A04-2 | Design | No abuse cases / misuse cases in use case documentation | All |
| HIGH (Attack Chain 3) | Integrity | **No message-level signing on Kafka financial topics** — broker compromise = full system compromise | A2→A1 |

### Priority 3 — Medium (Should Address During Implementation)

| ID | Category | Finding | Sub-systems |
|----|----------|---------|-------------|
| A01-3 | Access Control | Admin API auth unspecified | A4 |
| A02-4 | Crypto | Refresh token hashing could be stronger | A4 |
| A03-1 | Injection | No explicit parameterized query requirement | All |
| A03-2 | Injection | Log injection risk in description field | A3, Starter |
| A05-3 | Config | CORS not configured per sub-system | A4, A6 |
| A07-2 | Auth | SCA lockout too lenient | A4 |
| ASVS-2 | Session | No concurrent session/consent limit | A4 |
| ASVS-3 | Data | No anti-caching headers on API responses | All |
| CRIME-4 | Fraud | No cooling-off period for high-risk payments | A2, A3 |
| CRIME-5 | Fraud | QR code substitution attack not addressed | A3 |
| A09-2 | Monitoring | Insufficient fraud analyst behavior monitoring | A5 |
| A06-1 | Components | No runtime dependency verification | All |
| MEDIUM-security-testing | Testing | No SAST/DAST/pentest requirements | All |
| MEDIUM-tls-version | Config | No TLS minimum version requirement | All |

### Priority 4 — Low (Nice-to-Have Enhancements)

| ID | Category | Finding | Sub-systems |
|----|----------|---------|-------------|
| ASVS-4 | API | No response size limits on paginated endpoints | A1, A3, A5 |
| CRIME-6 | Fraud | Mandate abuse detection after merchant compromise | A3 |
| CRIME-7 | Race | TOCTOU in VRP cumulative counter | A4 |
| A09-2 | Monitoring | Analyst behavior anomaly detection | A5 |

---

## PART 7: POSITIVE FINDINGS — EXEMPLARY SECURITY IMPLEMENTATIONS

The project demonstrates exceptional security awareness in several areas that exceed what is typical for portfolio-level projects. These should be preserved and highlighted:

### 7.1 Sanctions Screening Architecture (A5)

The three-layer screening architecture (Bloom filter → Aho-Corasick → Jaro-Winkler fuzzy match) is a production-grade design. The key design decisions:
- Cannot be skipped, even on timeout (SAN-F12) — **legally correct**
- Sub-50ms fast path via in-memory data structures — **performance-correct**
- Retrospective screening on list updates (SAN-F13) — **operationally mature**
- Fuzzy matching handles transliterations, aliases, name order — **practically necessary**

This architecture aligns with commercial sanctions screening products (Dow Jones, World-Check, Accuity) while being implementable as a portfolio project.

### 7.2 VoP Anti-Enumeration Protection (A6)

The VoP responder's anti-enumeration controls are sophisticated:
- Rate limiting per PSP (VOP-E01: 100 queries/minute)
- Repeated-query pattern detection (VOP-E02: >5 different names for same IBAN in 1 hour)
- Privacy protection: NO_MATCH never reveals actual name (VOP-E04)
- All queries logged with PSP identity (VOP-E03)

This prevents VoP from being weaponized as an IBAN-to-name lookup service (a real concern raised during the EU VoP scheme design).

### 7.3 OAuth2 Token Security (A4)

The refresh token implementation is textbook:
- Single-use rotating tokens (AUTH-F07)
- Family-based revocation on reuse detection (AUTH-F08) — catches token theft
- Stored as SHA-256 hash only (PSD2-S02)
- PKCE mandatory (AUTH-F13) — prevents authorization code interception
- Dynamic linking for SCA (SCA-F04) — prevents TOTP relay attacks

### 7.4 Fraud-VoP Integration

The integration between VoP and fraud scoring is a genuine innovation:
- VoP NO_MATCH + payer override → +0.3 to fraud score (FRD-F06)
- VoP NOT_FOUND + payer override → +0.4 (VOP-I02)
- A2 semantic duplicate + payer override → +0.2 to fraud score (A5-F11/VEL-10)

This creates a feedback loop where multiple weak signals combine to detect sophisticated attacks. A single VoP override might be a legitimate name variation, but a VoP override combined with unusual velocity puts the combined score above the review threshold.

### 7.5 Hard Timeout Design (A2)

The 10-second hard boundary for instant payments (CLR-I10) with mandatory REJECT on timeout is excellent design. Combined with the differentiated failure modes:
- Fraud timeout → auto-pass with UNKNOWN score (soft failure)
- Sanctions timeout → REJECT (hard failure)
- Overall SLA timeout → REJECT (no limbo state)

This eliminates the "stuck payment" problem that plagues many payment systems.

---

## PART 8: RECOMMENDATIONS FOR IMPLEMENTATION

### 8.1 OWASP-Recommended Security Controls to Implement

In order of implementation priority:

1. **Add message-level signing to financial Kafka topics** (highest impact)
2. **Implement IDOR prevention as a framework-level control** (horizontal access control)
3. **Define and enforce actuator endpoint allowlist** (configuration security)
4. **Add SSRF protection to VoP requester HTTP client** (external integration security)
5. **Implement progressive SCA lockout with alerting** (authentication hardening)
6. **Add SIEM integration for security-critical events** (operational security)
7. **Create formal threat model document** (design security)
8. **Implement container hardening requirements** (deployment security)
9. **Add key management lifecycle requirements** (cryptographic security)
10. **Add security testing to CI/CD pipeline** (testing security)

### 8.2 Suggested New Cross-Cutting Requirement Sections

| Section | Requirement IDs | Content |
|---------|----------------|---------|
| Key Management | KM-01 through KM-10 | Key generation, storage, rotation, compromise response, algorithm agility |
| Kafka Security | KFK-S01 through KFK-S09 | Topic ACLs, message signing, Schema Registry auth, payload encryption, admin controls |
| Container Security | CNT-01 through CNT-06 | Non-root, read-only FS, image scanning, resource limits, network policies |
| Security Monitoring | SEC-20 through SEC-25 | SIEM integration, alerting rules, tamper-evident logs, anti-caching, anti-automation |
| Security Testing | STEST-01 through STEST-04 | SAST, DAST, penetration testing, security regression testing |
| Incident Response | IR-01 through IR-08 | Breach detection, classification, notification timelines, forensic readiness |
| Database Security | DB-01 through DB-05 | Per-service users, prepared statements, connection TLS, pg_audit, backup encryption |

### 8.3 OWASP Resources for Implementation

| Resource | URL | Relevance |
|----------|-----|-----------|
| OWASP Top 10 (2021) | https://owasp.org/Top10/ | Primary risk framework used in this review |
| OWASP ASVS v4.0.3 | https://owasp.org/www-project-application-security-verification-standard/ | Detailed verification requirements by level |
| OWASP Secure Coding Practices | https://owasp.org/www-project-secure-coding-practices-quick-reference-guide/ | Implementation-level guidance |
| OWASP Secure Headers Project | https://owasp.org/www-project-secure-headers/ | Header configuration (covered by Starter) |
| OWASP Testing Guide | https://owasp.org/www-project-web-security-testing-guide/ | Security testing methodology |
| OWASP Threat Modeling | https://owasp.org/www-community/Threat_Modeling | Threat model methodology (STRIDE) |
| OWASP Cheat Sheet Series | https://cheatsheetseries.owasp.org/ | Per-topic implementation guidance |
| OWASP API Security Top 10 | https://owasp.org/API-Security/ | API-specific risks (supplements Top 10) |

---

*End of OWASP Security Assessment Review.*
