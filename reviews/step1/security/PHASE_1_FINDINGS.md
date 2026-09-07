# Security Review Phase 1 — Cross-Cutting Themes A + B

*Date: 2026-04-12*

---

## Theme A: Security as a First-Class Architectural Concern

| Check | Verdict | Finding |
|-------|---------|---------|
| A.1 Security in CHALLENGES docs | **PASS** | All 7 subprojects include security as a first-class theme. A4 makes it the first challenge. A5 and A6 make it a foundational principle. |
| A.2 Security in non-security DECISIONS | **PASS** | Security surfaces consistently in caching (A4 rejects consent caching on security grounds), netting (A2 treats determinism as security property), state machine, and API design decisions. |
| A.3 Master threat model referenced | **PARTIAL** | Master defines Boundary STRIDE + attack trees (A.3a). No subproject references STRIDE or A.3a by name. Controls exist but connection to threat model is implicit. |
| A.4 Security requirements traceable | **PARTIAL** | Controls are implemented but security requirement IDs (LED-S*, PSD2-S*, VOP-S*) are inconsistently referenced. A6 has best traceability. |
| A.5 Starter embeds security structurally | **PASS** | Genuine "secure by default": deny-all authentication, auto-configured headers, build-time Gradle enforcement, visible WARNING on override. |

**Findings requiring action:**

### FS1.1 — No Subproject References Master Threat Model (A.3a) [MEDIUM]

The master specifies Boundary STRIDE + attack trees. None of the 7 subproject DESIGN documents reference STRIDE, attack trees, or decision A.3a. The controls exist but the traceability chain (identified threat → control → verification) is undocumented.

**Action:** Each subproject should add a brief threat model section or table mapping its critical interfaces to STRIDE categories and the architectural controls that mitigate each threat.

### FS1.2 — Security Requirement IDs Inconsistently Traced [MEDIUM]

A6 traces VOP-S01 through VOP-S06 explicitly. Other subprojects implement security requirements but don't reference them by ID (LED-S*, PSD2-S*, RTP-S*).

**Action:** Each subproject's DESIGN should include a security requirements traceability table: requirement ID → architectural element that addresses it.

---

## Theme B: Fortress Principle — Construction vs. Convention

| # | Security Property | Expected | Actual | |
|---|-------------------|----------|--------|---|
| 1 | Sanctions cannot be skipped | CONSTRUCTION | **CONSTRUCTION** | No bypass path in state machine or batch steps |
| 2 | Name never revealed on NO_MATCH | CONSTRUCTION | **CONSTRUCTION** | ResponseBodyAdvice — structural, not configurable |
| 3 | All endpoints authenticated by default | CONSTRUCTION | **MIXED** | Starter deny-all is structural, but @ConditionalOnMissingBean allows full override |
| 4 | Kafka HMAC tamper evidence | CONSTRUCTION | **MIXED** | Claimed as Starter feature but no HMAC interceptor found in Starter architecture |
| 5 | Per-account serialization | CONSTRUCTION | **CONSTRUCTION** | Kafka partition key = accountId — structural |
| 6 | PII encrypted in event store | CONSTRUCTION | **MIXED** | Encryption invoked by convention in handlers, not enforced by type system |
| 7 | Audit trail append-only + tamper-evident | CONSTRUCTION | **CONSTRUCTION (caveat)** | Hash chaining structural; topic immutability depends on Kafka admin config |
| 8 | Double-spend prevention | CONSTRUCTION | **CONSTRUCTION** | Compound events + 3-layer idempotency + partition serialization |
| 9 | Consent checked on every request | CONSTRUCTION | **MIXED** | Controller-level, not filter-level — new endpoints could miss consent check |
| 10 | Anti-enumeration detection | CONVENTION | **CONVENTION** | In-memory, lost on restart — documented as conscious tradeoff |

**Score: 5 CONSTRUCTION, 4 MIXED, 1 CONVENTION**

**Findings requiring action:**

### FS1.3 — Kafka HMAC: Claimed But Not Verified in Starter Architecture [HIGH]

Multiple services (A1, A2, A5) and the master architecture (KM-06) specify HMAC-SHA256 on Kafka messages, claiming it's provided by the Platform Starter. However, the Starter's own ARCHITECTURE_DESIGN.md does not describe an HMAC auto-configuration class or interceptor. The Starter's Kafka module contains tracing interceptors, not HMAC interceptors.

**Risk:** If HMAC signing is not auto-configured, each service must implement it manually (CONVENTION), and some may not implement it at all.

**Action:** Verify whether the Starter's `payment-security-starter-kafka` module includes HMAC signing/verification interceptors. If not, add them. This is a must-have for message integrity in a payment system.

### FS1.4 — PII Encryption Not Type-Enforced [MEDIUM]

A1's crypto-shredding encrypts PII fields at event creation, but encryption is invoked by convention in command handlers. The Avro schema uses `string` type, not a custom `EncryptedString` type that would refuse to serialize plaintext.

**Risk:** A new command handler could publish an event with plaintext PII by forgetting to call the encryption layer.

**Action:** Consider a custom Avro logical type (`encrypted-string`) or a serialization wrapper that enforces encryption. Alternatively, add an ArchUnit test that verifies all event producers call the crypto-shredding layer.

### FS1.5 — A4 Consent Enforcement Is Controller-Level, Not Filter-Level [MEDIUM]

A4's consent check (Layer 3) runs in each controller/service method. A new endpoint added without the consent check would bypass L3.

**Risk:** Developer adds a new PSD2 endpoint and forgets the consent check. All existing endpoints are covered, but the architecture doesn't structurally prevent this for future endpoints.

**Action:** Consider implementing consent enforcement as a Spring Security filter (or `HandlerInterceptor`) that runs before any controller. This would make it CONSTRUCTION.

### FS1.6 — Starter Deny-All Override Is Necessary But Weakens Guarantee [LOW]

The Starter's `@ConditionalOnMissingBean(SecurityFilterChain.class)` means services that need custom auth (A4 with OAuth2, A1 with scoped queries) replace the entire deny-all chain. The Starter logs a WARNING but cannot prevent misconfiguration.

**Risk:** Low in practice — the services with custom chains (A4, A1, A5) have well-defined, reviewed security configurations. But the structural guarantee is lost for these services.

**Action:** Acceptable tradeoff. Document that services with custom SecurityFilterChain are reviewed for "no accidental permitAll" during security review.

---

## Overall Assessment

**Security was treated as a first-class architectural concern from the beginning.** All 7 subprojects embed security in their foundational principles and challenges. The Platform Starter enforces security by default with build-time gates. Security considerations surface in non-security decisions (caching, netting, state machines).

**The fortress principle is mostly achieved.** 5 of 10 critical properties are genuinely CONSTRUCTION-level (sanctions bypass, name privacy, account serialization, double-spend prevention, audit hash chain). 4 are MIXED (require attention but have mitigating controls). 1 is CONVENTION (documented and acceptable).

**The most significant finding is FS1.3** — Kafka HMAC signing is claimed across the architecture but may not be implemented in the Starter. This needs immediate verification.
