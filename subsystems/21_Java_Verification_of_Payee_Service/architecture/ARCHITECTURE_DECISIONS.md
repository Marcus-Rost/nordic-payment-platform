# A6 — Verification of Payee Service: Architecture Decisions

*Decisions from architecture brainstorming and challenge resolution*
*Date: 2026-04-12*
*Input: ARCHITECTURE_CHALLENGES.md, master ARCHITECTURE_DECISIONS.md, literature review, reference architecture analysis*

---

## How to Read This Document

Each decision records: what was decided, which options were considered, why this option was chosen, and what the production upgrade path is (where applicable). Decisions are grouped by theme and reference the challenge IDs from `ARCHITECTURE_CHALLENGES.md`.

### Relationship to Master Architecture

A6 inherits 32 system-level decisions from the master `ARCHITECTURE_DECISIONS.md`. All 32 were reviewed for A6 compatibility — all are compatible. Four require minor refinements documented here (F.1b response mapping, A.1a deployment topology, F.2a audit hash chain, E.2b latency metrics). This document covers only A6-specific decisions.

---

## Theme 1: Security Architecture

### Decision C1 — Security-by-Design: Structural Security Pipeline + Runtime Verification

**Challenge:** A6 is the only service with a public API that answers questions about customer identity data. Security must be structural, not a checklist of independent controls.

**Options considered:**
- (A) Security as a checklist — enumerate and implement individual controls independently
- (B) Security as structural layers — mandatory filter pipeline, architecturally enforced
- (C) Structural layers + runtime verification — adversarial test suite verifying each layer

**Decision: Option C.**

**Rationale:** Option A cannot structurally guarantee that controls compose correctly or survive code changes. Option B is the right architecture but trusts correct composition without proving it. Option C adds ~15-20 adversarial tests that attempt to bypass each layer and verify the bypass fails — regression-proof security properties.

**Implementation:** Request processing follows a mandatory, ordered Spring Security filter chain:

| Order | Layer | Component | Purpose |
|-------|-------|-----------|---------|
| 1 | Authentication | Spring Security filter | mTLS certificate or API key validation |
| 2 | Input validation | Custom `OncePerRequestFilter` | IBAN format (ISO 13616), name constraints |
| 3 | Rate limiting | Bucket4j filter | Per-PSP query rate enforcement |
| 4 | Anti-enumeration | Custom filter | Repeated-query pattern detection |
| 5 | Matching engine | Service layer | Name normalization + Jaro-Winkler |
| 6 | Response filtering | `ResponseBodyAdvice` | Strip `actual_name` on NO_MATCH |
| 7 | Timing normalization | Servlet filter | Pad response to 10ms minimum |
| 8 | Audit logging | Filter at chain end | Log query to Kafka `audit.events` |

No request reaches the matching engine without passing layers 1-4. Layer 6 is structurally hardcoded — there is no configuration toggle to reveal names on NO_MATCH.

**Verification test examples:**
- Request without certificate → 401
- Malformed IBAN → 400 (rejected before matching)
- 101st request/minute from same PSP → 429
- NO_MATCH response → `actual_name` field structurally absent from JSON
- MATCH vs NO_MATCH timing → within 2ms of each other

### Decision C6 — Timing Side-Channel Protection

**Challenge:** Response timing can leak match results — MATCH may be faster than NO_MATCH, NOT_FOUND may differ from both.

**Options considered:**
- (A) No timing protection
- (B) Minimum response time floor (10ms)
- (C) Constant-time matching + response padding

**Decision: Option B — 10ms minimum response time floor.**

**Rationale:** A servlet filter records start time and sleeps for `max(0, 10ms - elapsed)` before sending. All cache-hit responses become indistinguishable at 10ms. Cache-miss responses (~200ms) are not padded — their timing reveals only that the IBAN wasn't recently queried, not the match result. Option C prevents performance optimizations and is hard to implement correctly. Option A leaves the side channel open.

**Latency impact:** At most 10ms added to fast responses. Well within the 100ms p95 budget (VOP-NF01).

---

## Theme 2: Core Domain — Name Matching

### Decision C2 — Name Matching Algorithm: Structural Pipeline

**Challenge:** Names have structure (given name, family name, legal suffix, compound prefixes). A flat Jaro-Winkler on the full string fails on common cases — "Ola Nordmann" vs "Ola Nordmann AS" scores ~0.82 (below CLOSE_MATCH), reordered compound names score ~0.72 (incorrectly NO_MATCH).

**Options considered:**
- (A) Flat pipeline — normalize both strings, single Jaro-Winkler on full string
- (B) Structural pipeline — decompose into components, normalize, best-permutation match
- (C) Multi-metric pipeline — Jaro-Winkler + Soundex + token Jaccard, weighted combination

**Decision: Option B — Structural pipeline with decomposition and best-permutation matching.**

**Rationale:** Option A fails on multi-token names with varying order and legal suffixes — these are common cases, not edge cases. Option C adds marginal value over B after proper diacritics normalization — phonetic algorithms add nothing when diacritics are already normalized. The EPC288-23 recommendation to handle entity types separately supports Option B.

**The pipeline:**

```
Step 1: Input Normalization (both names)
   Unicode NFD → strip combining marks
   EPC288-23 equivalence rules: ö=o=oe, ä=a=ae, å=a=aa, ø=o, æ=ae, ü=u, é=e
   Lowercase, collapse whitespace, remove non-alphabetic except numbers

Step 2: Structural Decomposition
   Detect legal entity suffix by pattern (AS, ASA, Ltd, GmbH, AB, etc.)
   Separate base name from suffix
   Expand abbreviations: AS→aksjeselskap, GmbH→gesellschaft mit beschrankter haftung

Step 3: Tokenize Base Name
   Split on whitespace
   Identify compound prefixes (van, der, de, von) → keep with following token

Step 4: Best-Permutation Jaro-Winkler
   Generate permutations of tokens (capped at 5 tokens = 120 permutations)
   Jaro-Winkler for each permutation against other name
   Take highest score

Step 5: Combine
   Legal entities: require suffix match (exact after expansion) AND base score
   Person names: use base score directly
   Thresholds: ≥0.95 MATCH, ≥0.85 CLOSE_MATCH, <0.85 NO_MATCH

Step 6: Response Filtering
   MATCH → result only
   CLOSE_MATCH → result + actual name
   NO_MATCH → result only (never reveal actual name — structurally enforced)
```

**Performance:** 5 tokens = 120 permutations × ~0.1ms per Jaro-Winkler = ~12ms worst case. Well within latency budget.

### Decision C10 — Response Design for Edge Cases

**Challenge:** Crypto-shredded accounts, frozen accounts, joint accounts, and name changes need defined responses.

**Options considered:**
- (A) Map all edge cases to existing response types
- (B) Extended internal types, mapped to standard external vocabulary

**Decision: Option B — Rich internal enum, EPC-standard external mapping.**

**Internal enum:** `MATCH`, `CLOSE_MATCH`, `NO_MATCH`, `NOT_FOUND`, `ACCOUNT_CLOSED`, `TIMEOUT`, `ERROR`

**External API mapping:** `ACCOUNT_CLOSED` → `NOT_FOUND`, `TIMEOUT` → `ERROR`. All others map directly.

**Edge case behavior:**

| Situation | Internal | External | Rationale |
|-----------|----------|----------|-----------|
| Crypto-shredded account | `ACCOUNT_CLOSED` | `NOT_FOUND` | No leakage about deletion |
| Frozen account | Normal matching | Normal result | Holder name still valid |
| Joint account | Best match across all holders | Normal result | Match against all, return best |
| Old company name | Normal matching | Normal result | Match current name only |

**Rationale:** External API stays EPC-compliant. Internal types provide audit context — analysts see `ACCOUNT_CLOSED` without cross-referencing A1. Aligns with master decision F.1b.

---

## Theme 3: Cache Architecture

### Decision C3a — Cache Invalidation: TTL-Only

**Challenge:** When an account holder changes their name in A1, A6's cache may serve stale data.

**Options considered:**
- (A) TTL-only — 1-hour expiry, no active invalidation
- (B) TTL + event-driven invalidation via Kafka
- (C) TTL + periodic bulk refresh via REST

**Decision: Option A — TTL-only.** Production upgrade: Option B.

**Rationale:** Name changes are rare — most accounts keep the same holder for years. The 1-hour staleness window affects an extremely small fraction of queries. The consequence is friction (unnecessary warning), not failure — the payer confirms and the payment proceeds. TTL-only preserves A6 as a pure synchronous REST service with no Kafka consumers. Event-driven invalidation (Option B) adds consumer group management, offset tracking, and error handling for an event that occurs a few times per day. For production at scale, Option B is the correct upgrade.

### Decision C3b — Cold Start: Pre-Warm Top-N on Startup

**Challenge:** After restart, empty cache causes latency spike and A1 load surge.

**Options considered:**
- (A) No warming — accept cold start penalty
- (B) Pre-warm top-N IBANs on startup
- (C) L1/L2 cache with Redis

**Decision: Option B.** Production scaling path: Option C.

**Implementation:** Maintain a periodically updated hot-IBAN list (top 5,000, from query log analytics). On startup, fetch names from A1 and populate cache before readiness probe passes (STARTER-F32 gating). No traffic arrives on an empty cache.

### Decision C3c — Cache Sizing: Configurable

**Decision:** Default 100K entries, configurable via `application.yml` (hot-reloadable per master H.2a). Monitor `vop.cache.hit.rate` metric. Tuning guidance: cache size should cover the active account set. Caffeine's W-TinyLFU eviction naturally retains frequently queried IBANs.

---

## Theme 4: Resilience

### Decision C4 — Circuit Breaker + Bulkhead + Fallback Hierarchy

**Challenge:** A6 depends on A1 for account holder names. A1 unavailability must not make A6 unresponsive.

**Options considered:**
- (A) Basic timeout (200ms) + fallback to cache or ERROR
- (B) Resilience4j Circuit Breaker + Bulkhead + explicit fallback hierarchy
- (C) Option B + stale-cache serving with extended TTL

**Decision: Option B.**

**Rationale:** Option A fails under sustained A1 outage — every cache-miss query waits 200ms, exhausting the thread pool. The circuit breaker detects outage and switches to fail-fast within seconds. Option C adds marginal benefit (stale data is almost always correct) at the cost of dual-TTL complexity.

**Configuration:**

```yaml
resilience4j.circuitbreaker:
  instances:
    a1-lookup:
      failure-rate-threshold: 50
      sliding-window-size: 10
      wait-duration-in-open-state: 30s

resilience4j.bulkhead:
  instances:
    a1-lookup:
      max-concurrent-calls: 20
```

**Fallback hierarchy (deterministic):**

| A1 Status | Cache | Behavior |
|-----------|-------|----------|
| Healthy | Hit | Normal (sub-5ms) |
| Healthy | Miss | Fetch from A1 (~200ms) |
| Circuit open | Hit | Serve from cache (may be stale) |
| Circuit open | Miss | ERROR immediately (fail fast) → caller proceeds with VOP_TIMEOUT |

---

## Theme 5: Anti-Enumeration and Scalability

### Decision C5 — Per-Instance In-Memory Detection

**Challenge:** Anti-enumeration counters are in-memory. Multi-instance deployment weakens detection accuracy. Counters are lost on crash.

**Options considered:**
- (A) Per-instance in-memory (Caffeine sliding window)
- (B) Shared state in Redis
- (C) Sticky routing by PSP
- (D) Per-instance + periodic aggregation

**Decision: Option A.** Production upgrade: Option B.

**Rationale:** At portfolio scale (1-2 instances), per-instance detection is accurate. The audit log captures every query regardless — post-hoc analysis catches what real-time detection misses. Adding Redis solely for enumeration counters would be inconsistent with the caching decision (C3a, no Redis). For production, Redis is added coherently for both anti-enumeration (C5) and L2 cache (C3b) — a single infrastructure upgrade.

### Decision C7 — Independent Instances

**Challenge:** Multi-instance deployment with independent caches and counters.

**Options considered:**
- (A) Independent instances, accept divergence
- (B) Shared L2 cache + shared enumeration (Redis)
- (C) Independent caches + shared enumeration only

**Decision: Option A.** Production upgrade: Option C.

**Rationale:** A single instance handles 2,000+ QPS trivially (<1ms per cache-hit request). Multiple instances are for availability (99.99%), not throughput. Cache divergence is minor — popular IBANs are cached in all instances naturally. Consistent with C3 and C5.

---

## Theme 6: Data Privacy

### Decision C8 — GDPR: Anonymize on Account Deletion

**Challenge:** VoP query logs contain PII. AML requires 5-year retention. GDPR requires right to erasure.

**Options considered:**
- (A) Anonymize on account deletion — IBAN→hash, names→`[REDACTED]`, retain structure
- (B) Short retention (90 days) for raw data + long retention for aggregates

**Decision: Option A.**

**Rationale:** AML requires 5-year retention of individual records (not just aggregates), ruling out Option B. Anonymized records are no longer personal data under GDPR, so 5-year retention of anonymized records is compliant with both regulations. This is the standard industry approach.

**Trigger:** Same notification mechanism as cache invalidation — coordinated with A1's erasure process.

---

## Theme 7: Testing

### Decision C9 — Curated Corpus + Property-Based Testing

**Challenge:** Matching algorithm correctness requires comprehensive testing across name variations, diacritics, and edge cases.

**Options considered:**
- (A) Curated test corpus only (~100 cases)
- (B) Curated corpus + property-based testing (jqwik)
- (C) Option B + threshold sensitivity analysis

**Decision: Option B.** Production activity: Option C.

**Two test layers:**

1. **Curated corpus (~100 cases):** Nordic diacritics (Bjørn/Bjorn, Åse/Ase, Ødegård/Odegard), compound names (van der Berg), legal entities (AS/Aksjeselskap, GmbH), name order permutations, prefix handling, adversarial near-misses. Maps to AC-01 through AC-09.

2. **Property-based testing (jqwik):** Generates thousands of random name pairs, verifies invariants:
   - `score(A, B) == score(B, A)` — symmetric
   - `normalize(normalize(x)) == normalize(x)` — idempotent
   - `score(x, x) == 1.0` — identity
   - `score ∈ [0.0, 1.0]` — bounded
   - Diacritics normalization changes score by <0.05

**Rationale:** Curated corpus validates known cases. Property-based testing catches unexpected interactions between normalization steps. Threshold sensitivity analysis (Option C) requires realistic-scale data — a production activity.

---

## Master Architecture Refinements

Three master decisions require minor A6-specific refinements:

### Refinement of F.1b — Post-Shredding Response

**Master decision:** A6 returns `ACCOUNT_CLOSED` for crypto-shredded accounts.
**A6 refinement:** `ACCOUNT_CLOSED` is an internal response type mapped to `NOT_FOUND` in the external API (Decision C10). This prevents information leakage about account deletion while preserving audit context internally.

### Refinement of A.1a — Deployment Topology

**Master decision:** A6 responder is in DMZ zone.
**A6 refinement:** Single deployment spanning both zones. Both endpoints live under `/api/v1/` and are differentiated by authentication mechanism (mTLS service cert for internal callers vs mTLS client cert or API key for external PSPs) and Kubernetes NetworkPolicy — not by URL prefix. Internal endpoint (`POST /api/v1/vop/verify`) is restricted via NetworkPolicy to A3/A4 pods only. External endpoint (`POST /api/v1/vop/check`) gets full authentication + rate limiting pipeline. Both share the same matching engine, cache, and A1 client.

### Refinement of F.2a — Audit Hash Chain

**Master decision:** Audit events use a tamper-evident hash chain.
**A6 refinement:** Audit events published to `audit.events` use the Platform Starter's tamper-evident hash chain mechanism (per-instance SHA-256 chain with crash recovery). See Platform Starter ARCHITECTURE_DESIGN.md Section 5.5 and master decision F.2a.

### Refinement of E.2b — Latency Metrics

**Master decision:** A2 measures end-to-end payment latency. VoP measured separately by A3/A4.
**A6 refinement:** A6 publishes its own metric: `vop.query.duration` with tags `direction=OUTBOUND|INBOUND`, `cache=HIT|MISS`, `result=MATCH|CLOSE_MATCH|NO_MATCH|NOT_FOUND|ERROR`. This gives complete visibility into A6's contribution to overall payment latency.

---

## Production Upgrade Path Summary

Several decisions follow a pattern: simple and correct for the portfolio, with a documented upgrade path when scaling to production. The upgrades are coherent — they share infrastructure (Redis) and can be adopted together.

| Decision | Portfolio | Production Upgrade | Trigger |
|----------|-----------|-------------------|---------|
| C3a Cache invalidation | TTL-only | Event-driven (Kafka consumer) | Name change latency becomes unacceptable |
| C3b Cold start | Pre-warm top-N | L1/L2 with Redis | Multiple instances, frequent restarts |
| C5 Anti-enumeration | Per-instance | Redis shared counters | Multi-instance accuracy requirement |
| C7 Scalability | Independent instances | Shared enumeration via Redis | Scaling beyond 2 instances |

**Common trigger:** Scaling to 3+ instances. At that point, add Redis for shared anti-enumeration counters (C5) and L2 cache (C3b). Event-driven cache invalidation (C3a) can be added independently based on name-change latency requirements.
