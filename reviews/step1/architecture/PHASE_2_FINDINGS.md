# Phase 2 Findings — Master Decision Propagation

*Category 2 from REVIEW_CHECKLIST.md*
*Date: 2026-04-12*

---

## Summary

10 master decisions checked across 7 subprojects (70 checks total):

- **PASS:** 48 (69%)
- **IMPLICIT:** 13 (19%) — likely correct at runtime via Starter, but not documented
- **FAIL:** 1 (1%)
- **N/A:** 8 (11%)

---

## Critical Finding

### F2.1 — A4 (PSD2 API Gateway) Does Not Document RFC 9457 Compliance [HIGH]

**Decision:** E.1a — All REST APIs use RFC 9457 Problem Details
**Finding:** A4's design document contains zero references to RFC 9457, Problem Details, or structured error response format. It mentions the Platform Starter only for `@Audited`, not for error handling.

**Impact:** A4 is the external-facing PSD2 gateway — the most regulation-sensitive API surface. The BerlinGroup NextGenPSD2 specification expects structured error responses. The Platform Starter auto-configures RFC 9457, so runtime behavior is likely correct, but the architecture document should explicitly declare this.

**Action:** Add an explicit RFC 9457 section to A4's ARCHITECTURE_DESIGN.md, similar to A3's section 3.6 or A6's section 7.3.

---

## Medium Findings — IMPLICIT Decisions Needing Documentation

### F2.2 — A4 Has Most IMPLICIT Verdicts (3 of 10) [MEDIUM]

A4's design is the least explicit about Platform Starter cross-cutting concerns. It has IMPLICIT verdicts for:
- E.1a (RFC 9457 error format) — FAIL, see F2.1
- E.1b (Kafka error classification) — mentions DLQ but not the three-category model
- H.2a (Configuration architecture) — no explicit env vars vs. application.yml separation

**Action:** Add a "Platform Starter Integration" section to A4's design, similar to A3's section 16, explicitly listing which Starter features A4 uses.

### F2.3 — A5 Missing Explicit RFC 9457 and Hash Chain Documentation [MEDIUM]

A5's Case Management REST API doesn't mention RFC 9457 error format. A5 also doesn't mention audit hash chaining. Both are inherited from the Starter.

**Action:** Add explicit references in A5's design document.

### F2.4 — A6 Missing Audit Hash Chain Documentation [LOW]

A6 doesn't explicitly mention audit hash chaining/tamper evidence. As a security-critical service handling identity data, this should be made explicit.

**Action:** Add a note in A6's security or observability section that audit events use the Starter's hash chain mechanism.

### F2.5 — A2 Configuration Architecture Not Explicitly Documented [LOW]

A2's design doesn't explicitly document the env vars vs. application.yml separation pattern that A1, A3, and A6 document clearly.

**Action:** Add a configuration section to A2's design.

---

## Minor Finding

### F2.6 — Kafka Error Category Terminology Mismatch [LOW]

The Starter uses `TRANSIENT / POISONED / FATAL` while the master decision says `retryable / permanent / critical`. The Fatal category is stricter (stops the consumer) than master's "DLQ+alert." Services override appropriately.

**Action:** Align terminology in either the master or the Starter. Recommend updating the master to use the Starter's terminology since the Starter is the implementation.

---

## Positive Findings

- **Dual-write strategy (B.2a):** All 6 services correctly implement their assigned strategy. No deviations.
- **State machine phase ownership (B.1a):** A3, A4, A2, A1 all correctly implement their phase ownership with explicit references to the master decision.
- **Batch/instant isolation (C.3a):** Both A2 and A5 deploy as separate JVM instances with Spring profiles, exactly as specified.
- **Idempotency keys (B.3a):** A1, A2, A3, A5 all implement hierarchical deduplication with transactionId as the correlation key.
- **API versioning (D.2a):** All external APIs use `/api/v1/` path prefix.
- **Crypto-shredding (F.1a):** A1 and A6 both implement the correct field-level encryption scope.

Overall: the master decisions are well-propagated. The issues are primarily documentation gaps (IMPLICIT → should be explicit), not behavioral contradictions.
