# Security Review — Consolidated Findings

*Date: 2026-04-12*
*Frameworks: OWASP ASVS v4.0.3 (L2), OWASP API Security Top 10 (2023), PCI DSS v4.0 principles*
*Scope: Master project + 7 subprojects, architecture + requirements*
*Phases: 5 review phases + 2 cross-cutting themes*

---

## Overall Assessment

The system demonstrates **genuine security-by-design**. Security is embedded in the architecture from the foundational principles through to the implementation patterns. The Platform Starter enforces a secure baseline by construction (deny-all, auto-configured audit, build-time enforcement). The most critical payment security properties — sanctions screening integrity, double-spend prevention, balance integrity — are structurally enforced and cannot be bypassed.

The findings fall into three categories:
1. **One genuine vulnerability** (SSRF in A3 webhooks) that needs immediate attention
2. **Structural gaps** where a claimed security property is weaker than documented (HMAC, consent enforcement)
3. **Missing defense-in-depth layers** that don't create vulnerabilities but reduce margin (request size limits, Kafka ACLs, penetration testing)

---

## Findings by Severity

### CRITICAL (1)

| ID | Finding | Category | Impact |
|----|---------|----------|--------|
| **FS5.1** | A3 webhook dispatch has no URL validation against internal/private addresses — SSRF vulnerability | S11.1 | A merchant can register `https://a1-ledger.internal:8091/...` as webhook URL, enabling requests to internal services from within the trusted zone |

**Fix:** Add URL validation in A3's `WebhookDispatcher` that blocks: RFC 1918 private ranges (10.x, 172.16-31.x, 192.168.x), link-local (169.254.x), loopback (127.x), IPv6 equivalents, and internal DNS suffixes (`.internal`, `.svc.cluster.local`). Apply validation both at webhook URL registration time and at dispatch time (to prevent DNS rebinding).

### HIGH (4)

| ID | Finding | Category | Impact |
|----|---------|----------|--------|
| **FS1.3** | Kafka HMAC signing claimed as Starter feature but no HMAC interceptor found in Starter architecture | Theme B, S4.3 | If HMAC is opt-in utility rather than auto-configured interceptor, services may process unauthenticated Kafka messages. Verify-before-deserialize order not structurally enforced. |
| **FS5.2** | No Kafka ACLs — any authenticated service can produce to any topic | S10.3 | A compromised A3 could produce to `payment.settlement-events` and credit arbitrary accounts. HMAC authenticates the producer but doesn't restrict topics. |
| **FS3.1** | A3 API key lifecycle: no rotation, expiry, revocation, or stolen-key detection documented | S2.1 | A compromised merchant API key has no recovery path. No brute-force lockout on API key authentication. |
| **FS5.3** | No request body size limits on any external API | S10.1 | Multi-gigabyte payloads could exhaust memory on A3, A4, A6. Concrete DoS vector. |

### MEDIUM (10)

| ID | Finding | Category |
|----|---------|----------|
| **FS1.1** | No subproject references master threat model (A.3a) — STRIDE traceability gap | Theme A.3 |
| **FS1.2** | Security requirement IDs inconsistently traced across subprojects | Theme A.4 |
| **FS1.4** | PII encryption in A1 invoked by convention, not enforced by type system | Theme B |
| **FS1.5** | A4 consent enforcement is controller-level, not filter-level | Theme B |
| **FS2.1** | A3 consumer API has no independent rate limit — relies entirely on A4 | S1.5 |
| **FS2.2** | VoP anti-enumeration: CLOSE_MATCH reveals actual name within 5-guess window | S1.7 |
| **FS3.2** | Mass assignment prevention (A3, A4): no explicit DTO field restriction documentation | S3.2 |
| **FS3.3** | Admin endpoint authorization not explicitly documented for A2, A3 | S3.3 |
| **FS4.1** | TLS cipher suite restrictions and version enforcement not documented at app level | S5.1 |
| **FS5.4** | No formal security testing program (DAST/SAST/pen-test) beyond A6 adversarial tests | S12.4 |

### LOW (7)

| ID | Finding | Category |
|----|---------|----------|
| **FS1.6** | Starter deny-all override via @ConditionalOnMissingBean weakens guarantee for custom chains | Theme B |
| **FS2.3** | Semantic duplicate fingerprint window (20 min) may be too short for batch cycles | S1.2 |
| **FS2.4** | VoP anti-enumeration counter resets on restart (documented conscious tradeoff) | S1.7 |
| **FS3.4** | A6 API key lifecycle for external PSPs not documented | S2.1 |
| **FS4.2** | Encryption at rest: no TDE or volume encryption documented (delegated to infrastructure) | S6.4 |
| **FS4.3** | In-cluster pod-to-pod may be plaintext (depends on service mesh, not app-level) | S7.1 |
| **FS4.4** | SecureRandom not explicitly specified for crypto-shredding key generation | S5.5 |
| **FS5.5** | NetworkPolicy manifests not specified for A1, A2, A3, A4 (only A5, A6 mention them) | S9.4 |

---

## Statistics

| Severity | Count |
|----------|-------|
| Critical | 1 |
| High | 4 |
| Medium | 10 |
| Low | 7 |
| **Total** | **22** |

| Category | PASS | PARTIAL | FAIL |
|----------|------|---------|------|
| Theme A (Security first-class) | 3 | 2 | 0 |
| Theme B (Fortress principle) | 5 | 4 | 0 |
| S1 (Business logic) | 8 | 4 | 0 |
| S2 (Authentication) | 7 | 3 | 1 |
| S3 (Authorization) | 4 | 4 | 0 |
| S4 (Input validation) | 2 | 1 | 0 |
| S5 (Cryptography) | 2 | 1 | 0 |
| S6 (Data protection) | 2 | 1 | 0 |
| S7 (Communication) | 1 | 1 | 0 |
| S8 (Audit) | 3 | 0 | 0 |
| S9 (Configuration) | 3 | 1 | 0 |
| S10 (Resilience) | 1 | 2 | 0 |
| S11 (API-specific) | 2 | 0 | 1 |
| S12 (Industry) | 2 | 1 | 0 |
| **Total** | **45** | **25** | **2** |

---

## What Works Well (Strongest Security Properties)

1. **Sanctions screening is structurally mandatory** — no code path, feature flag, or configuration can bypass it. Late sanctions hits on settled payments trigger automatic pacs.004 reversal. Verified across instant, batch, VRP, mandate auto-accept, and duplicate override flows.

2. **Double-spend prevention is construction-level** — compound event model (debit+credit atomic in one Kafka message), three-layer idempotency (command dedup + producer dedup + consumer dedup), per-account Kafka partitioning eliminates concurrent balance manipulation.

3. **Platform Starter enforces security baseline by construction** — deny-all authentication, auto-configured security headers, build-time Gradle enforcement, visible WARNING on override, structured JSON logging with auto-redaction, tamper-evident audit hash chain.

4. **A4's OAuth2/SCA implementation is comprehensive** — PKCE mandatory, refresh token family tracking with reuse detection, durable SCA lockout (survives restart), immutable dynamic linking (prevents amount-switching), pessimistic-locked SCA exemption counters, consent checked from database on every request (no cache, zero revocation window).

5. **VoP response privacy is structurally enforced** — `ResponseBodyAdvice` strips actual name on NO_MATCH. Not configurable, not bypassable by controller code. Timing normalization (10ms floor) prevents side-channel. Adversarial tests verify continuously.

6. **Audit trail is tamper-evident** — per-instance SHA-256 hash chain with crash recovery from Kafka, dual-write (local log + Kafka), daily signed checkpoints, structured JSON preventing log injection.

---

## Recommended Fix Order

### Immediate (before implementation begins)

1. **FS5.1 [CRITICAL]** — Add SSRF protection to A3 webhook dispatch. Block private IPs, loopback, internal DNS at both registration and dispatch time. This is a concrete, exploitable vulnerability.

2. **FS1.3 [HIGH]** — Resolve HMAC gap: either confirm the Starter already provides HMAC interceptors (and document them), or add auto-configured `HmacProducerInterceptor` and `HmacConsumerInterceptor` to the Starter's Kafka module. HMAC verification must run before Avro deserialization.

3. **FS5.3 [HIGH]** — Add request body size limits to all external APIs. Configure `server.max-http-request-header-size` and a servlet filter or Spring property for body size (e.g., 1MB for normal APIs, 10MB for A3 bulk operations).

### Before production deployment

4. **FS5.2 [HIGH]** — Implement Kafka ACLs restricting which services can produce to which topics. A3/A4 can produce only to `payment.instructions` and `payment.instant-instructions`. Only A2 can produce to `payment.settlement-events`. Only A5 can produce to fraud-check-response topics.

5. **FS3.1 [HIGH]** — Document A3 API key lifecycle: generation, rotation schedule, expiry, revocation procedure, brute-force lockout. Consider replacing API keys with mTLS for merchant authentication (stronger, no shared secret).

### During implementation

6. **FS1.4 [MEDIUM]** — Add ArchUnit test or custom Avro logical type to enforce PII encryption at event creation in A1.

7. **FS1.5 [MEDIUM]** — Implement A4 consent enforcement as a Spring Security filter or `HandlerInterceptor`, not controller-level.

8. **FS2.1 [MEDIUM]** — Add per-consumer-ID rate limit to A3's consumer API.

9. **FS3.2 [MEDIUM]** — Document explicit DTO field restrictions for A3 and A4 request objects.

10. **FS3.3 [MEDIUM]** — Document explicit scope requirements for A2 and A3 admin endpoints.

### Documentation improvements

11. **FS1.1 [MEDIUM]** — Add threat model traceability (STRIDE) to each subproject.
12. **FS1.2 [MEDIUM]** — Add security requirement ID traceability tables.
13. **FS4.1 [MEDIUM]** — Document TLS cipher suite and version requirements.
14. **FS5.4 [MEDIUM]** — Establish security testing program (DAST/SAST/pen-test plan).
15. **FS5.5 [LOW]** — Add concrete NetworkPolicy specifications for all services.

---

## Detailed Findings

See phase-specific reports for full evidence and line references:
- `PHASE_1_FINDINGS.md` — Cross-cutting themes (security-first-class, fortress principle)
- Phase 2-5 results — detailed in this session's conversation

## Fixes Applied

All critical and high findings have been addressed:

| ID | Severity | Fix Applied |
|----|----------|-------------|
| FS5.1 | CRITICAL | Added SSRF protection to A3 webhooks (URL validation, IP blocking, DNS rebinding protection) |
| FS1.3 | HIGH | Added HMAC interceptor auto-configuration to Platform Starter (HmacProducerInterceptor, HmacConsumerInterceptor, verify-before-deserialize) |
| FS5.2 | HIGH | Added Kafka ACL specification to INTERFACE_CONTRACTS.md (per-service produce/consume permissions) |
| FS3.1 | HIGH | Added API key lifecycle to A3 (generation, rotation, revocation, expiry, brute-force protection) |
| FS5.3 | HIGH | Added request body size limits to INTERFACE_CONTRACTS.md (1MB default, 10MB bulk) |
| FS1.4 | MEDIUM | Added PII encryption enforcement note to A1 (ArchUnit test + custom Avro type recommendation) |
| FS1.5 | MEDIUM | Added consent enforcement implementation note to A4 (HandlerInterceptor recommendation) |
| FS2.1 | MEDIUM | Added consumer API rate limit to A3 (200 req/min per consumer_id) |
| FS3.2 | MEDIUM | Added DTO field restriction documentation to A3 |
| FS3.3 | MEDIUM | Added admin endpoint authorization documentation to A2 |
| FS4.1 | MEDIUM | Added TLS cipher suite specification to master architecture |
| FS5.4 | MEDIUM | Added security testing program section to master architecture |

Deferred (documentation-only, not blocking):
- FS1.1: STRIDE traceability tables per subproject
- FS1.2: Security requirement ID traceability tables per subproject
- FS2.2: CLOSE_MATCH info leakage — inherent to EPC VoP scheme, cannot change
- LOW items: minor documentation improvements
