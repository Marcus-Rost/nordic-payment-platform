# A6 — Verification of Payee Service: Architectural Challenges

*Consolidated from brainstorming session, literature review, and reference architecture analysis*
*Date: 2026-04-12*
*Status: All 10 challenges decided (2026-04-12)*

---

## How to Read This Document

Each challenge describes an architectural decision that A6 must make. For each challenge we present:
- **The problem** — what needs to be decided and why it matters
- **Solution options** — realistic alternatives with pros and cons
- **Decision** — the chosen option with rationale (all challenges decided 2026-04-12)

Challenges are ordered by priority: foundational decisions that other decisions depend on come first.

### Relationship to Master Architecture

The master project's `ARCHITECTURE_DECISIONS.md` contains 32 system-level decisions. We reviewed all of them for compatibility with A6. Result: **all 32 are compatible**. Three require minor refinements during A6 detailed design (response type mapping for crypto-shredded accounts, deployment topology, latency metrics). None need to be changed.

This document covers only **A6-specific challenges** — decisions where A6 must make its own choice, either because the master architecture doesn't prescribe a solution at the subsystem level, or because A6 has specific constraints that require a subsystem-level decision.

### Sources

- User brainstorming contributions (security as architecture, crash resilience, literature review, reference architectures)
- Reference implementations: SurePay (NL), UK CoP (Pay.UK), EPC VoP Scheme
- Literature: Cohen et al. (2003) on name matching, Nygard (2018) on resilience, OWASP API Security Top 10 (2023), NIST SP 800-228 (2025), EPC288-23 matching recommendations
- Master architecture: `payment_processing/architecture/ARCHITECTURE_DECISIONS.md`

---

## Priority 1 — Foundational Decisions

These must be decided first. Other decisions depend on them.

---

### C1 — Security-by-Design: Structural Security Properties

**The problem:** A6 is the only service in our system with a public-facing API that answers questions about customers' identity data. The responder endpoint lets external parties probe whether a name matches an IBAN. This is simultaneously the service's core purpose and its primary attack surface. Security cannot be an add-on — it must be woven into the architecture so that insecure behavior is structurally impossible, not merely policy-prohibited.

**Why it matters:** SurePay (NL) and UK CoP both treat anti-abuse as a foundational architectural concern, not a feature. OWASP API6:2023 ("Unrestricted Access to Sensitive Business Flows") identifies verification endpoints as prime targets for automated abuse. A VoP service that is functionally correct but architecturally insecure is worse than no VoP at all — it gives a false sense of safety while leaking identity data.

**What is already decided (inherited from master):**
- Three-zone model with A6 responder in DMZ (A.1a)
- Deny-all default in Platform Starter (A.1b)
- mTLS for service-to-service and external PSP communication (A.1a)

**What remains open for A6:**

#### Option A: Security as a Checklist — Enumerate and Implement Individual Controls

Identify each security requirement (authentication, rate limiting, input validation, response filtering, audit logging) and implement them as separate, independent components.

Pros:
- Straightforward to plan and track
- Each control can be tested independently
- Familiar approach — most projects work this way

Cons:
- Controls can be accidentally bypassed or forgotten
- No structural guarantee that they compose correctly
- A new endpoint added later might miss one or more controls
- "Checklist mentality" — security is as strong as the weakest item

#### Option B: Security as Structural Layers — Defense-in-Depth Pipeline

Design the request processing as a mandatory pipeline where every request passes through security layers in a fixed order. No request can reach the matching engine without passing through all layers. The pipeline is architecturally enforced (Spring Security filter chain), not just documented.

```
Request → mTLS/API Key Auth → Input Validation (IBAN format, name constraints)
       → Rate Limiting (per-PSP) → Anti-Enumeration Check
       → Matching Engine → Response Filtering (strip actual name on NO_MATCH)
       → Timing Normalization → Audit Logging → Response
```

Each layer is a separate Spring component (filter or interceptor) with its own unit tests. The pipeline composition is in the security configuration — adding a new endpoint requires explicitly choosing which pipeline it uses.

Pros:
- Structural guarantee: no request bypasses any layer
- Each layer has a single responsibility
- New endpoints must explicitly declare their security pipeline
- The pipeline itself is testable as a unit
- Aligns with NIST SP 800-228 recommendation for defense-in-depth

Cons:
- More upfront design effort than a checklist
- Pipeline ordering matters — a bug in layer ordering could have security implications
- Slightly more rigid — adding an exception (e.g., a health check that bypasses auth) requires explicit pipeline configuration

#### Option C: Security as Architecture + Runtime Verification

Like Option B, but add runtime verification: a test suite that attempts to bypass each layer and verifies it fails. This acts as a continuous security regression test.

For example:
- Test: send a request without mTLS certificate → verify 401
- Test: send a request with a valid cert but malformed IBAN → verify 400 (rejected before matching)
- Test: send 101 requests in 1 minute from same PSP → verify 429 on request 101
- Test: trigger a NO_MATCH → verify actual_name is absent from response (not just null, but structurally absent from the JSON)
- Test: measure response time for MATCH vs. NO_MATCH → verify they are within 2ms of each other (timing normalization works)

Pros:
- All benefits of Option B, plus continuous verification
- Security properties are tested, not just assumed
- Regression protection: a code change that accidentally weakens a layer is caught by tests
- Demonstrates security maturity to interviewers and auditors

Cons:
- More test code to write and maintain
- Some tests (timing normalization) can be flaky in CI environments
- Most upfront effort of the three options

**Decision: Option C — Structural Security Pipeline + Runtime Verification.**

Security is not a feature layer — it is the architecture. Request processing follows a mandatory, ordered filter pipeline (Spring Security filter chain) where each layer is a separate, testable component. No request reaches the matching engine without passing all layers. A dedicated security test suite (~15-20 adversarial tests) continuously verifies that each layer cannot be bypassed.

Pipeline implementation maps to Spring Security components:
- mTLS/API key auth → Spring Security authentication filter
- IBAN format validation → custom `OncePerRequestFilter`
- Rate limiting → Bucket4j filter
- Anti-enumeration detection → custom filter
- Response filtering → `ResponseBodyAdvice` (strips `actual_name` on NO_MATCH)
- Timing normalization → servlet filter wrapping the response
- Audit logging → filter at end of chain

Rationale: Option A (checklist) cannot structurally guarantee that security properties compose correctly or survive code changes. Option B (pipeline without verification) is the right architecture but trusts correct composition without proving it. Option C adds the proof — adversarial tests that attempt to bypass each layer and verify the bypass fails. The cost (~15-20 tests) is modest; the benefit (regression-proof security properties) is significant for a financial service.

---

### C2 — Name Matching Algorithm Architecture

**The problem:** Name matching is A6's core domain logic. The algorithm must handle: exact matches, typos, diacritics (Bjorn/Bjorn), name order permutations (Nordmann, Ola / Ola Nordmann), legal entity suffixes (AS/Aksjeselskap), compound prefixes (van der Berg), and produce a similarity score that maps to MATCH/CLOSE_MATCH/NO_MATCH. The choice of algorithm and normalization pipeline directly determines the false positive rate (unnecessary user friction) and false negative rate (missed fraud).

**Why it matters:** UK CoP found that matching inconsistency across banks was a significant problem — the same name pair could get different results depending on which bank responded. SurePay's CTO identifies diacritics as "the hardest problem" at European scale. The EPC deliberately does NOT prescribe an algorithm (EPC288-23), making this the responding PSP's competitive differentiator. Cohen et al. (2003) showed that algorithm choice matters more than threshold tuning.

**What is already decided:**
- Jaro-Winkler as primary metric (from requirements, VOP-N07)
- Diacritics normalization via Unicode NFD (VOP-N02)
- Configurable thresholds: MATCH >= 0.95, CLOSE_MATCH >= 0.85 (VOP-N07)
- Abbreviation expansion: AS <-> Aksjeselskap, etc. (VOP-N03)
- Name order handling (VOP-N04)

**What remains open — the pipeline architecture:**

#### Option A: Flat Pipeline — Normalize, Then Single Jaro-Winkler Score

```
Input names → Normalize both (NFD, strip marks, lowercase, collapse whitespace,
              expand abbreviations) → Jaro-Winkler on full normalized strings
              → Score → Threshold → Result
```

Pros:
- Simplest to implement and explain
- Single score, single comparison
- Easy to tune and audit ("the score was 0.87, here's why")
- Sufficient for single-token names and most person names

Cons:
- Struggles with multi-token names where order varies significantly
- "Ola Nordmann Aksjeselskap" vs. "Nordmann Ola AS" — full-string Jaro-Winkler gives a lower score than component-wise comparison would
- Does not leverage the structural difference between person names and legal entity names

#### Option B: Structural Pipeline — Decompose, Normalize, Best-Permutation Match

```
Input names → Detect type (person vs. legal entity, by suffix pattern)
           → Separate base name from legal suffix
           → Normalize components independently
           → For base name: generate token permutations
           → Jaro-Winkler on each permutation → take highest score
           → If legal entity: compare suffix separately (exact match after expansion)
           → Combine scores → Threshold → Result
```

Pros:
- Handles name order permutations robustly (comparing best permutation)
- Separates the legal suffix problem from the name matching problem
- More accurate for compound names ("Jan Erik van der Berg" vs. "van der Berg, Jan Erik")
- Aligns with EPC288-23 recommendation to handle entity types separately

Cons:
- More complex to implement (tokenization, permutation generation, suffix detection)
- Permutation generation is O(n!) for n tokens — must limit to reasonable token counts (<=5)
- Two different code paths (person vs. entity) increase testing surface
- Score combination logic (base name score + suffix match) needs careful design

#### Option C: Multi-Metric Pipeline — Jaro-Winkler + Phonetic + Token-Based

```
Input names → Normalize → Run three metrics in parallel:
  1. Jaro-Winkler on best permutation (character-level)
  2. Soundex/Metaphone comparison (phonetic-level)
  3. Token overlap score (Jaccard on name tokens) (token-level)
→ Weighted combination → Threshold → Result
```

Cohen et al. (2003) found that SoftTFIDF (combining token-level and character-level) outperformed any single metric. This option generalizes that finding.

Pros:
- Most robust against edge cases — if one metric fails, others may catch it
- Phonetic metric handles transliteration cases (Mueller/Muller) that Jaro-Winkler may score as CLOSE_MATCH rather than MATCH
- Token overlap handles word-order independence naturally
- Academically grounded (Cohen et al.)

Cons:
- Most complex to implement, tune, and explain
- Weight tuning for the combination requires empirical data (which we don't have)
- Harder to audit — "the score was 0.87" becomes "the weighted combination of three scores was 0.87"
- May be over-engineering for a service where normalization handles most edge cases
- Regulators and auditors prefer explainable decisions — a single metric is easier to justify

**Decision: Option B — Structural Pipeline with decomposition and best-permutation matching.**

Names are not arbitrary strings — they have structure (given name, family name, legal suffix, compound prefixes). The matching algorithm must reflect this structure. The concrete pipeline:

1. **Input Normalization**: Unicode NFD → strip combining marks, EPC288-23 equivalence rules (ö=o=oe, ä=a=ae, å=a=aa, ø=o, æ=ae), lowercase, collapse whitespace, remove non-alphabetic except numbers.
2. **Structural Decomposition**: Detect legal entity suffix by pattern (AS, ASA, Ltd, GmbH, AB, etc.), separate base name from suffix, expand abbreviations (AS→aksjeselskap).
3. **Tokenize Base Name**: Split on whitespace, identify compound prefixes (van, der, de, von) and keep with following token.
4. **Best-Permutation Jaro-Winkler**: Generate permutations of tokens (capped at 5 tokens = 120 permutations), compute Jaro-Winkler for each against other name, take highest score.
5. **Combine**: Legal entities require suffix match AND base score. Person names use base score directly. Thresholds: ≥0.95 MATCH, ≥0.85 CLOSE_MATCH, <0.85 NO_MATCH.
6. **Response Filtering**: MATCH → result only. CLOSE_MATCH → result + actual name. NO_MATCH → result only (never reveal actual name).

Rationale: Option A (flat Jaro-Winkler on full strings) fails on common real-world cases — "Ola Nordmann" vs. "Ola Nordmann AS" scores ~0.82 (below CLOSE_MATCH threshold) because the suffix dilutes the score. Reordered compound names like "Jan Erik van der Berg" vs. "van der Berg, Jan Erik" score ~0.72 (NO_MATCH — clearly wrong). Option B handles both correctly. Option C (multi-metric voting) adds marginal value over B after proper normalization — phonetic algorithms add nothing when diacritics are already normalized to equivalent base characters. The EPC288-23 recommendation to handle entity types separately supports Option B.

---

### C3 — Cache Architecture

**The problem:** A6's performance depends almost entirely on cache hit rate. With >95% hits, p95 latency is <5ms. With a cold cache, every query requires a 200ms REST call to A1. The cache holds account holder names keyed by IBAN, with a 100K entry limit and 1-hour TTL. The architectural decisions are: how to invalidate stale entries, how to handle cold starts, and how to size the cache.

**Why it matters:** SurePay achieves 52ms average response time at 11 billion+ total checks. Our <100ms p95 target for the responder is achievable only with high cache hit rates. Cache misses are the primary source of latency variance and A1 load.

**What is already decided:**
- Caffeine as the cache library (from requirements)
- 100K entry limit (VOP-NF05 context)
- 1-hour general TTL, 90-day mandate cache TTL (VOP-RES02)
- >95% hit rate target (VOP-NF05)

**What remains open:**

**Sub-challenge C3a: Cache Invalidation Strategy**

#### Option A: TTL-Only

Entries expire after 1 hour. No active invalidation. If an account holder changes their name, VoP queries may return incorrect results for up to 1 hour.

Pros:
- Simplest possible approach — no Kafka consumption, no coordination
- A6 remains purely synchronous (no event consumption in the service)
- Bounded staleness (1 hour maximum)
- Name changes are rare — the vast majority of queries during the staleness window are unaffected

Cons:
- Up to 1 hour of incorrect results after a name change
- During the staleness window, a legitimate payment might get an unnecessary NO_MATCH warning (friction) or a fraudulent payment might get a false MATCH (missed fraud)
- The draft architecture describes A6 as having "no Kafka consumption in the payment flow" — TTL-only preserves this property

#### Option B: TTL + Event-Driven Invalidation

A6 consumes name-change events from A1 (via a new event type on `ledger.events` or a dedicated lightweight topic). On receiving a name-change event, A6 immediately invalidates the specific cache entry. TTL remains as a safety net.

Pros:
- Near-real-time cache consistency (invalidation within seconds of name change)
- Eliminates the staleness window for the specific account that changed
- TTL still protects against missed events

Cons:
- A6 now has a Kafka consumer — it is no longer purely synchronous
- Adds a coupling to A1's event schema (A1 must publish name-change events)
- Kafka consumer adds operational complexity (consumer group, offset management, error handling)
- For a rare event (name changes), this is significant infrastructure

#### Option C: TTL + Periodic Bulk Refresh

A6 periodically (e.g., every 15 minutes) queries A1 for a list of IBANs whose holder names changed since the last refresh. A6 invalidates those entries. Simpler than event-driven but not real-time.

Pros:
- Reduces staleness window from 1 hour to 15 minutes without Kafka consumption
- Simpler than event-driven — a scheduled REST call, not a Kafka consumer
- A6 remains a REST-only service

Cons:
- Requires A1 to expose a "changed accounts since timestamp" API
- 15-minute staleness window is still non-zero
- Polling approach — less efficient than event-driven for rare events

**Decision: Option A — TTL-only invalidation.** Production upgrade path: Option B (event-driven).

Name changes are rare — most accounts keep the same holder for years. The 1-hour staleness window affects an extremely small fraction of queries. The consequence of stale data is friction (unnecessary CLOSE_MATCH/NO_MATCH warning), not failure — the payer confirms and the payment proceeds. TTL-only preserves A6's architectural simplicity as a pure synchronous REST service with no Kafka consumers. Adding event-driven invalidation would introduce consumer group management, offset tracking, and event deserialization error handling — all for an event that occurs perhaps a few times per day across the entire account base. For production at scale, Option B is the correct upgrade: A6 consumes name-change events from A1 and invalidates specific cache entries, with TTL as a safety net.

**Sub-challenge C3b: Cold Start / Cache Warming**

#### Option A: No Warming — Accept Cold Start Penalty

On startup, cache is empty. The first queries all hit A1. Cache fills organically through normal query traffic.

Pros:
- Zero additional code or infrastructure
- Simple and predictable

Cons:
- Latency spike after restart — all queries are cache misses until the cache warms up
- Load surge on A1 during warming period
- If A6 restarts during peak traffic, it could briefly violate the <100ms p95 SLA

#### Option B: Pre-Warm Top-N IBANs on Startup

Maintain a periodically updated list of the most-queried IBANs (e.g., top 5,000). On startup, A6 fetches names for these IBANs from A1 before accepting traffic (before passing readiness probe).

Pros:
- Eliminates cold-start latency for the most common queries
- Pareto distribution means a small set of IBANs covers most traffic
- Readiness probe gating prevents serving traffic until cache is partially warm

Cons:
- Requires maintaining a "hot IBANs" list (from query log analytics)
- Adds startup time (5,000 REST calls to A1, even batched, take a few seconds)
- The hot list may drift from actual query patterns

#### Option C: L1/L2 Cache — Caffeine + Redis

Caffeine as L1 (per-instance, sub-millisecond). Redis as L2 (shared across instances, ~1ms). On L1 miss, check L2 before calling A1. Entries populated in both layers. L2 survives instance restarts.

Pros:
- Eliminates cold-start entirely — L2 has data from other instances
- Consistent cache across instances (beneficial for anti-enumeration accuracy)
- L2 survives individual instance restarts

Cons:
- Adds Redis as an infrastructure dependency
- L2 adds ~1ms per cache miss (still much faster than A1's 200ms, but not zero)
- Operational complexity of managing Redis
- May be over-engineering for a portfolio project

**Decision: Option B — Pre-warm top-N IBANs on startup.** Production scaling path: Option C (L1/L2 with Redis).

Pre-warming the top 5,000 IBANs on startup provides critical cold-start protection with minimal infrastructure. A periodically updated hot-IBAN list (from query log analytics) is loaded before the readiness probe passes, so no traffic arrives on an empty cache. The readiness probe gating is already available from the Platform Starter (STARTER-F32). For production with multiple instances, Option C (Caffeine L1 + Redis L2) eliminates cold-start entirely and reduces redundant A1 calls across instances.

**Sub-challenge C3c: Cache Sizing**

The 100K entry limit is from the requirements context. Is it sufficient?

The answer depends on the account base size and query distribution:
- If the bank has 100K accounts, 100K entries = 100% coverage. Cache hit rate approaches 100%.
- If the bank has 1M accounts and queries follow Zipfian distribution, 100K entries cover ~90-95% of queries.
- If the bank has 1M accounts and queries are uniformly distributed, 100K entries cover only 10%.

**Decision: Configurable cache size, default 100K, monitored.**

Cache size is a tunable operational parameter, not a fixed architectural decision. Default to 100K entries via `application.yml` (hot-reloadable per master decision H.2a). Monitor `vop.cache.hit.rate` metric. Tuning guidance: cache size should cover the "active account" set — the accounts that receive payments frequently enough to benefit from caching. With Caffeine's W-TinyLFU eviction policy, frequently queried IBANs are naturally retained while one-off lookups are evicted. If hit rate drops below 95%, increase cache size or investigate whether the query distribution has changed.

---

### C4 — Resilience and Graceful Degradation

**The problem:** A6 depends on A1 (Account Ledger) for account holder names. If A1 is unavailable, A6 cannot answer queries on cache miss. Additionally, A6 holds three forms of in-memory state that are lost on crash: the name cache, anti-enumeration counters, and in-flight requests. The architecture must define explicit degradation behavior and crash recovery.

**Why it matters:** The EU regulation specifies that VoP is advisory — payments should proceed even if VoP is unavailable. But "proceed without verification" must be a deliberate, audited degradation, not an unhandled error. Nygard's "Release It!" (2018) emphasizes that resilience patterns must be designed in, not retrofitted.

**What is already decided:**
- VOP-RES01: Graceful degradation on A1 unavailability — serve from cache, return ERROR on cache miss
- VOP-R06: On TIMEOUT or ERROR, proceed with payment but flag for review
- Master B.1b: Kafka replay + database recovery scan on restart (minimal impact on A6)

**What remains open:**

#### Option A: Basic Resilience — Timeout + Fallback

A1 REST client has a 200ms timeout. On timeout or error, check cache. On cache miss, return ERROR. No circuit breaker.

Pros:
- Simplest implementation
- Timeout provides bounded latency

Cons:
- If A1 is down, every cache-miss query waits 200ms before failing — wasting time and threads
- No protection against A1 being "slow but not dead" (200ms latency on every call, saturating thread pool)
- No automatic recovery detection (when A1 comes back, A6 doesn't know without trying)

#### Option B: Circuit Breaker + Bulkhead + Fallback Hierarchy

Wrap A1 REST client with Resilience4j:
- **Circuit Breaker**: After 5 consecutive failures or >50% failure rate in 10-second window, open the circuit. In open state, skip A1 entirely and serve from cache or return ERROR immediately. Half-open after 30 seconds — try one request to check if A1 is back.
- **Bulkhead**: Limit concurrent A1 calls to 20 (semaphore). Prevents A1 slowness from exhausting all request-handling threads.
- **Fallback hierarchy**: Circuit closed + cache hit → normal. Circuit closed + cache miss → call A1. Circuit open + cache hit → serve stale. Circuit open + cache miss → ERROR immediately (fail fast).

Pros:
- Circuit breaker prevents wasting time and threads when A1 is down
- Bulkhead protects A6's request handling capacity
- Fail-fast on cache miss + circuit open gives immediate response (no 200ms wait)
- Automatic recovery detection via half-open state
- All state transitions are observable via Micrometer metrics

Cons:
- More configuration to tune (failure thresholds, window sizes, bulkhead limits)
- Circuit breaker state is in-memory — lost on restart (but recovers quickly)

#### Option C: Option B + Stale-Cache Serving with Extended TTL

Like Option B, but when the circuit is open, serve cache entries even if their TTL has expired (stale data). Only evict entries when the cache is full (LRU eviction) or when the circuit is closed and fresh data is available.

Pros:
- Maximizes availability during A1 outages — every IBAN that was ever cached can be served
- Stale data is better than no data for VoP (a slightly outdated name is still useful)
- Reduces ERROR responses during outages

Cons:
- Stale data could be incorrect (name changed since cache entry was created)
- Need to track "stale" flag in the response so callers know the data may be outdated
- More complex cache management (two TTLs: normal TTL and emergency TTL)

**Decision: Option B — Circuit Breaker + Bulkhead + Fallback Hierarchy (Resilience4j).**

A1 REST client is wrapped with Resilience4j CircuitBreaker and Bulkhead:
- **Circuit Breaker**: 50% failure rate threshold, 10-request sliding window, 30-second wait in open state. On open: skip A1 entirely, serve from cache or return ERROR immediately (fail fast).
- **Bulkhead**: Semaphore, max 20 concurrent A1 calls. Prevents A1 slowness from exhausting request-handling threads.
- **Fallback hierarchy** (deterministic, explicit):
  - A1 healthy + cache hit → normal (sub-5ms)
  - A1 healthy + cache miss → fetch from A1, populate cache (~200ms)
  - Circuit open + cache hit → serve from cache (may be stale but almost certainly correct — name changes are rare)
  - Circuit open + cache miss → ERROR immediately; caller proceeds with VOP_TIMEOUT flag; payment is NOT blocked (VOP-R06)

Rationale: Option A (basic timeout) fails under sustained A1 outage — every cache-miss query waits 200ms before failing, exhausting the thread pool. Option B eliminates this: the circuit breaker detects the outage and switches to fail-fast within seconds. Option C (stale-cache serving) adds marginal benefit at the cost of dual-TTL complexity. Resilience4j is the standard library for these patterns in Spring Boot, with built-in Micrometer metrics export for monitoring circuit breaker state.

---

## Priority 2 — Important Decisions

These have significant impact on security or operational quality but don't block other decisions.

---

### C5 — Anti-Enumeration Detection

**The problem:** The responder API can be abused to map names to IBANs — an attacker sends queries with common names against a range of IBANs, collecting MATCH results to build a name-IBAN directory. VOP-E02 specifies simple detection (>5 different names for same IBAN from same PSP in 1 hour). But the detection state is in-memory, creating two problems: (1) it's lost on crash, and (2) in a multi-instance deployment, queries are load-balanced across instances, so each instance sees only a fraction of queries.

**Why it matters:** Visa's anti-enumeration guidance emphasizes multi-dimensional velocity checks. SurePay's centralized hub model gives them complete visibility into query patterns. Our distributed model (per-instance detection) is architecturally weaker. An attacker who can observe or trigger restarts gets a fresh enumeration window.

#### Option A: Per-Instance In-Memory Detection (Simple)

Each A6 instance maintains its own sliding-window counters in Caffeine. Counters are lost on restart. In a multi-instance deployment, each instance sees only its share of queries.

Pros:
- Simplest implementation — no external dependencies
- Sub-millisecond counter operations
- Sufficient for the simple heuristic (>5 names per IBAN per PSP per hour)

Cons:
- Multi-instance deployment weakens detection — with 3 instances, an attacker can probe 15 names (5 per instance) before triggering detection
- Counters lost on crash — attacker can exploit restart windows
- No cross-instance visibility

#### Option B: Shared Detection State in Redis

Anti-enumeration counters stored in Redis. All instances share the same counters. Redis atomic increments ensure accurate counting regardless of load balancing.

Pros:
- Accurate counting across all instances
- Survives individual instance restarts
- Atomic operations prevent race conditions
- Enables more sophisticated patterns (track total query volume per PSP, not just per-IBAN)

Cons:
- Adds Redis as an infrastructure dependency
- ~1ms per counter operation (still fast, but not sub-millisecond)
- Redis itself needs availability guarantees

#### Option C: Sticky Routing by PSP

Configure the load balancer to route all queries from a given PSP (identified by client certificate or API key) to the same A6 instance. Each instance has complete visibility for its assigned PSPs.

Pros:
- Accurate per-PSP counting without shared state
- No external dependency
- Simple conceptually

Cons:
- Creates hot spots if one PSP has much higher volume than others
- Complicates failover — if an instance dies, its PSPs' counters are lost
- Load balancer configuration is more complex

#### Option D: Per-Instance Detection + Periodic Aggregation

Each instance tracks counters locally. A periodic task (every 30 seconds) aggregates counters across instances via a lightweight REST call or shared database table. Detection threshold is applied to the aggregate.

Pros:
- No always-on shared state dependency (Redis)
- Aggregation is eventual but bounded (30-second window)
- Each instance can make local decisions immediately and refine with aggregate data

Cons:
- 30-second aggregation lag means detection is slightly delayed
- Aggregation logic adds complexity
- Coordination between instances adds failure modes

**Decision: Option A — Per-instance in-memory detection.** Production upgrade: Option B (Redis shared counters).

At portfolio scale (1-2 instances), per-instance detection is accurate. The simple heuristic (>5 different names per IBAN per PSP per hour) works correctly with a single instance. With multiple instances under round-robin load balancing, detection weakens proportionally (3 instances → 15 names before trigger), but: (1) the audit log captures every query regardless — post-hoc analysis can detect enumeration that real-time detection missed, (2) adding Redis solely for anti-enumeration when we chose against it for caching (C3) would be inconsistent. For production with multiple instances, Redis is added coherently for both anti-enumeration counters (C5) and L2 cache (C3b upgrade path) — a single infrastructure addition that addresses both scaling concerns.

---

### C6 — Timing Side-Channel Protection

**The problem:** If MATCH responses are faster than NO_MATCH responses (e.g., because MATCH can short-circuit after normalization while NO_MATCH always runs the full Jaro-Winkler computation), an attacker can infer the match result from response timing alone, even without reading the response body. Similarly, NOT_FOUND (IBAN doesn't exist) may have different timing than NO_MATCH (IBAN exists but name doesn't match), leaking account existence information.

**Why it matters:** Visa's anti-enumeration guidance explicitly warns about timing as an information channel. This is a well-known attack vector against verification services. OWASP includes timing attacks in its API security guidance.

#### Option A: No Timing Protection

Accept that response times vary. Focus on the response body (never reveal actual name on NO_MATCH) and rate limiting as the primary defenses.

Pros:
- Zero implementation effort
- No added latency

Cons:
- Sophisticated attacker can distinguish MATCH from NO_MATCH from NOT_FOUND by timing
- Undermines the privacy guarantees of the response filtering

#### Option B: Minimum Response Time Floor

Pad all responses to a minimum duration (e.g., 10ms). If processing takes 2ms (cache hit, MATCH), wait 8ms before responding. If processing takes 9ms, wait 1ms. If processing takes >10ms (cache miss), no padding.

Pros:
- Simple to implement (Thread.sleep or ScheduledExecutor delay)
- Eliminates timing differences for cache-hit queries (the vast majority)
- Minimal latency impact (adding at most 10ms to fast responses)

Cons:
- Does not protect cache-miss queries (which naturally take ~200ms) — but these are a small minority
- An attacker can still distinguish cache-hit from cache-miss by timing (but this doesn't reveal match result, only cache state)
- Constant floor value must be chosen carefully — too low and fast responses are still distinguishable, too high and it wastes latency budget

#### Option C: Constant-Time Matching with Response Padding

Ensure the matching computation takes constant time regardless of result (always run the full pipeline, never short-circuit). Additionally, pad responses to a fixed duration.

Pros:
- Strongest timing protection — no information leakage via timing
- Constant-time matching is a recognized security best practice for sensitive comparisons

Cons:
- Prevents performance optimization (can't skip Jaro-Winkler if normalization already shows exact match)
- Constant-time matching is harder to implement correctly (must avoid branch-dependent timing in string comparisons)
- May be over-engineering for a service that already has rate limiting and anti-enumeration detection

**Decision: Option B — Minimum response time floor (10ms).**

A servlet filter records the request start time. After the response is computed, the filter sleeps for `max(0, 10ms - elapsed)` before sending. This makes all cache-hit responses (MATCH, CLOSE_MATCH, NO_MATCH, NOT_FOUND) indistinguishable at 10ms. Cache-miss responses (~200ms) are not padded — their timing is dominated by network to A1 and reveals only that the IBAN wasn't recently queried, not the match result. Implementation is trivial (single filter, ~10 lines). Latency cost is negligible (at most 10ms added to fast responses, well within the 100ms p95 budget). Option A (no protection) leaves the timing side channel open. Option C (constant-time matching) prevents performance optimizations and is hard to implement correctly — over-engineering given our other defenses (rate limiting, anti-enumeration, response filtering).

---

### C7 — Horizontal Scalability

**The problem:** A6 is described as "stateless" but has in-memory state: name cache and anti-enumeration counters. When running multiple instances (for availability and throughput), each instance has independent state. This affects cache efficiency (redundant A1 calls across instances) and anti-enumeration accuracy (partial visibility per instance).

**Why it matters:** The requirements specify 2,000+ QPS throughput (VOP-NF04) and 99.99% availability (VOP-NF03). This likely requires multiple instances. The architecture must define how multi-instance deployment affects behavior.

#### Option A: Independent Instances — Accept Divergence

Each instance has its own cache and counters. No coordination between instances. Load balancer distributes queries round-robin.

Pros:
- Simplest architecture — no coordination infrastructure
- Each instance is fully independent — one instance failing doesn't affect others
- Cache divergence is minor in practice (popular IBANs are cached in all instances)

Cons:
- Redundant A1 calls (different instances cache-miss on different IBANs)
- Anti-enumeration accuracy reduced (addressed in C5)
- Cache warming after scaling up takes time

#### Option B: Shared L2 Cache (Redis) + Shared Enumeration State

Redis as shared state for both cache (L2) and anti-enumeration counters. Each instance checks local Caffeine (L1) first, then Redis (L2), then A1.

Pros:
- Eliminates redundant A1 calls
- Accurate anti-enumeration across instances
- New instances immediately benefit from shared cache

Cons:
- Redis dependency for all read operations (not just enumeration)
- Redis latency (~1ms) on every L1 miss
- Redis as a single point of failure (mitigated by Redis Sentinel/Cluster)

#### Option C: Independent Instances + Strategic Sharing

Independent caches (each instance manages its own Caffeine). Shared only where accuracy matters: anti-enumeration counters in Redis (or shared database). Accepts cache divergence as a minor efficiency cost.

Pros:
- Cache reads remain fully local (sub-millisecond, no network dependency)
- Anti-enumeration is accurate (shared counters)
- Redis dependency is limited to enumeration detection (low-volume operations)
- Cache miss to A1 is infrequent enough that redundancy across instances is acceptable

Cons:
- Two different state management approaches in one service (local cache + shared counters)
- Still has redundant A1 calls (but these are <5% of queries)

**Decision: Option A — Independent instances, accept divergence.** Production upgrade: Option C (shared enumeration via Redis).

This decision is consistent with C3 (independent caches, TTL-only) and C5 (per-instance enumeration counters). A single Spring Boot instance with Caffeine + Jaro-Winkler handles 2,000+ QPS trivially (<1ms per cache-hit request, well under 1% of a single instance's capacity). Multiple instances are for availability (99.99% requires surviving instance failures), not throughput. Two instances behind a load balancer provide this. Cache divergence between instances is a minor efficiency cost — popular IBANs are cached in all instances naturally. For production, Option C adds Redis for shared anti-enumeration counters and L2 cache — a coherent single-infrastructure upgrade addressing both C5 and C3b scaling concerns.

---

## Priority 3 — Refinements

These are important but lower-risk decisions. They refine the architecture without changing its fundamental shape.

---

### C8 — GDPR Compliance for VoP Query Logs

**The problem:** VoP query logs contain PII: IBANs and names (both stated and actual). These are stored in PostgreSQL for audit and analytics (VOP-S04). GDPR right to erasure applies. Additionally, the responder API reveals whether an IBAN exists (NOT_FOUND response), which could be considered data leakage.

**Why it matters:** AML regulations require 5-year retention of financial transaction audit data. GDPR requires data minimization and right to erasure. These two requirements are in tension. The architecture must define how to reconcile them.

#### Option A: Anonymize on Account Deletion

When an account is deleted in A1 (and its PII crypto-shredded), A6 receives a notification (same mechanism as cache invalidation) and anonymizes the corresponding query log entries: IBAN replaced with a hash, actual_name replaced with `[REDACTED]`, stated_name replaced with `[REDACTED]`.

Pros:
- Preserves the audit trail structure (timestamps, results, PSP IDs remain)
- Audit queries are still countable and analyzable (how many queries, what results, which PSPs)
- Satisfies GDPR erasure — PII is irrecoverable after anonymization
- Aligns with the master architecture's crypto-shredding approach (F.1a/F.1b)

Cons:
- Anonymized records cannot be used for post-hoc fraud investigation involving specific IBANs
- Must coordinate with A1's erasure process (A6 must be notified)

#### Option B: Separate Retention — Short for PII, Long for Aggregates

Store raw query logs (with PII) for 90 days. After 90 days, aggregate into anonymous statistics (query volume per PSP, match rate, latency distribution) and delete the raw data. Keep aggregates for 5 years.

Pros:
- Minimizes PII exposure window
- Aggregates satisfy long-term analytical needs
- Simpler GDPR compliance (PII auto-deleted after 90 days)

Cons:
- 90-day window may not satisfy AML audit requirements (which demand 5-year retention of individual records)
- Loss of individual query data after 90 days limits fraud investigation capability

**Decision: Option A — Anonymize on account deletion.**

When an account's PII is crypto-shredded in A1, A6 anonymizes the corresponding query log entries: IBAN → hash, actual_name → `[REDACTED]`, stated_name → `[REDACTED]`. The audit trail structure remains intact (timestamps, PSP IDs, match results, response times) and is retained for 5 years per AML requirements. Anonymized records are no longer personal data under GDPR, so 5-year retention is compliant with both regulations. The notification mechanism that triggers anonymization is the same one used for cache invalidation — coordinated with A1's erasure process. Option B (short retention + aggregates) is inadequate because AML requires 5-year retention of individual records, not just aggregates. This is the standard industry approach, consistent with the master architecture's crypto-shredding design (F.1a/F.1b).

---

### C9 — Testing Strategy for the Matching Algorithm

**The problem:** The matching algorithm is the core value proposition. Its correctness cannot be verified by type checking or standard unit tests alone — it requires a comprehensive test corpus covering Nordic names, diacritics, abbreviations, compound names, and edge cases. The algorithm must also be tested for threshold sensitivity (small threshold changes should not cause large swings in results).

**Why it matters:** SurePay found that diacritics and special characters in company names (150,000 Dutch companies have "&" in their name) were the most common source of matching errors. UK CoP found that data quality in banks' own records caused most CLOSE_MATCH results. Testing must cover both algorithm correctness and data quality robustness.

#### Option A: Curated Test Corpus

A handcrafted set of ~100 test cases covering known edge cases: Nordic names (Bjorn/Bjorn, Ase/Ase), compound names (van der Berg), legal entities (AS/Aksjeselskap), name order, diacritics, and adversarial cases (very similar but different names).

Pros:
- Targeted — each test case is chosen for a specific edge case
- Easy to understand and maintain
- Directly maps to acceptance criteria (AC-01 through AC-09)

Cons:
- Limited coverage — only tests what the developer thought of
- May miss unexpected interactions between normalization steps
- Static — doesn't adapt as the algorithm evolves

#### Option B: Curated Corpus + Property-Based Testing

Like Option A, plus property-based tests using a library like jqwik or junit-quickcheck:
- Property: matching is symmetric — score(A, B) == score(B, A)
- Property: normalization is idempotent — normalize(normalize(x)) == normalize(x)
- Property: score is always in [0.0, 1.0]
- Property: exact string produces score 1.0
- Property: completely different strings produce score < 0.5
- Property: adding/removing diacritics does not change the score by more than 0.1

Pros:
- Discovers edge cases the developer didn't think of
- Properties document invariants of the algorithm
- Automatically generates thousands of test inputs

Cons:
- Property-based tests can be slow (many iterations)
- Some properties are hard to express (what does "completely different" mean?)
- Failed tests may produce obscure counterexamples that are hard to debug

#### Option C: Option B + Threshold Sensitivity Analysis

Like Option B, plus a parameterized test that sweeps the threshold range and reports how many results change per 0.01 threshold increment. This produces a sensitivity report showing which threshold regions are "stable" (few result changes) and which are "volatile" (many result changes).

Pros:
- Reveals whether the chosen thresholds (0.95/0.85) are in a stable region
- Informs threshold tuning — if moving from 0.95 to 0.94 changes 500 results, the threshold is fragile at 0.95
- Valuable documentation for auditors ("we chose 0.95 because the sensitivity analysis shows it's in a stable region")

Cons:
- Requires a realistic-scale test corpus to be meaningful (100 test cases are too few for sensitivity analysis)
- Generating a realistic corpus without real customer data is non-trivial

**Decision: Option B — Curated corpus + property-based testing.** Production activity: Option C (threshold sensitivity analysis).

Two complementary test layers:
1. **Curated corpus** (~100 test cases): Nordic diacritics (Bjørn/Bjorn, Åse/Ase, Ødegård/Odegard), compound names (van der Berg), legal entities (AS/Aksjeselskap, GmbH), name order permutations, prefix handling, adversarial near-misses. Maps directly to acceptance criteria AC-01 through AC-09.
2. **Property-based testing** (jqwik): generates thousands of random name pairs and verifies invariants — matching is symmetric (`score(A,B) == score(B,A)`), normalization is idempotent, identity produces score 1.0, scores are bounded in [0.0, 1.0], diacritics normalization changes score by <0.05.

The curated corpus validates correctness on known cases. Property-based testing validates that the algorithm's invariants hold across arbitrary inputs and catches unexpected interactions between normalization steps. Option C (threshold sensitivity analysis — sweeping the threshold range and reporting how many results change per 0.01 increment) requires realistic-scale data and belongs in production tuning, not portfolio architecture.

---

### C10 — Response Design for Edge Cases

**The problem:** Several edge cases are not fully specified in the requirements. The architecture should define responses for:
1. Crypto-shredded accounts (PII deleted via GDPR erasure)
2. Frozen accounts (account exists but is frozen by A5 fraud case management)
3. Joint accounts (multiple holders for one IBAN)
4. Legal entity name changes (company renamed, old name still in common use)

**Why it matters:** UK CoP introduced a "Name Switched" response for accounts transferred to new holders. The EPC scheme has four response codes (MTCH, NMTC, CMTC, NOAP). Our response vocabulary should handle these cases correctly.

#### Option A: Map All Edge Cases to Existing Response Types

- Crypto-shredded: `NOT_FOUND` (indistinguishable from non-existent IBAN — no information leakage)
- Frozen: Normal matching (account is frozen, not closed — holder name is still valid)
- Joint accounts: Match against all holders, return best match
- Name changes: Match against current name only (old name produces NO_MATCH or CLOSE_MATCH)

Pros:
- No new response types — simpler API
- External PSPs see a standard response vocabulary
- "NOT_FOUND" for crypto-shredded accounts prevents information leakage about account existence/deletion

Cons:
- Internal services lose the distinction between "IBAN doesn't exist" and "IBAN existed but PII was erased"
- Joint account matching adds complexity to the matching engine

#### Option B: Extended Internal Response Type, Mapped for External API

Internal response includes additional types (`ACCOUNT_CLOSED`, `MULTIPLE_HOLDERS`) that are mapped to standard types in the external API:
- Internal `ACCOUNT_CLOSED` → External `NOT_FOUND`
- Internal `MULTIPLE_HOLDERS` with match → External `MATCH`
- Internal `MULTIPLE_HOLDERS` without match → External `NO_MATCH`

Pros:
- Internal services have full context for decision-making
- External API remains standard and simple
- Audit logs capture the full context (internal type)

Cons:
- Two response vocabularies (internal and external) to maintain
- Mapping logic adds a small amount of code

**Decision: Option B — Extended internal types, mapped to standard external vocabulary.**

Internal response enum includes richer types for audit context: `MATCH`, `CLOSE_MATCH`, `NO_MATCH`, `NOT_FOUND`, `ACCOUNT_CLOSED`, `TIMEOUT`, `ERROR`. External API presents the EPC-aligned vocabulary only: `MATCH`, `CLOSE_MATCH`, `NO_MATCH`, `NOT_FOUND`, `ERROR`. Mapping via a `toExternalResult()` method on the enum:
- `ACCOUNT_CLOSED` → `NOT_FOUND` (no information leakage about account deletion)
- All others map directly

Edge case handling:
- **Crypto-shredded account**: internal `ACCOUNT_CLOSED`, external `NOT_FOUND`
- **Frozen account**: normal matching (account is frozen, not closed — holder name is valid)
- **Joint account**: match against all holders, return best match result
- **Old company name**: match against current name only — old name naturally produces CLOSE_MATCH or NO_MATCH

Rationale: The external API stays EPC-compliant and simple. The internal enum provides audit context — when an analyst reviews a query log entry showing `ACCOUNT_CLOSED`, they immediately understand why `NOT_FOUND` was returned without cross-referencing A1. Implementation cost is trivial (one enum, one mapping method). Aligns with master decision F.1b.

---

## Summary: Decision Index

| ID | Challenge | Priority | Decision | Production Upgrade |
|----|-----------|----------|----------|-------------------|
| C1 | Security-by-design | 1 | **Option C**: Structural security pipeline + runtime verification (~15-20 adversarial tests) | — |
| C2 | Name matching algorithm | 1 | **Option B**: Structural pipeline — decompose, normalize, best-permutation Jaro-Winkler | — |
| C3a | Cache invalidation | 1 | **Option A**: TTL-only (1-hour expiry) | Event-driven invalidation |
| C3b | Cache cold start | 1 | **Option B**: Pre-warm top-N IBANs on startup | L1/L2 with Redis |
| C3c | Cache sizing | 1 | Configurable, default 100K, monitor hit rate | — |
| C4 | Resilience and degradation | 1 | **Option B**: Circuit Breaker + Bulkhead + Fallback Hierarchy (Resilience4j) | — |
| C5 | Anti-enumeration detection | 2 | **Option A**: Per-instance in-memory (Caffeine sliding window) | Redis shared counters |
| C6 | Timing side-channel | 2 | **Option B**: Minimum response time floor (10ms) | — |
| C7 | Horizontal scalability | 2 | **Option A**: Independent instances, accept divergence | Shared enumeration via Redis |
| C8 | GDPR for query logs | 3 | **Option A**: Anonymize on account deletion | — |
| C9 | Testing matching algorithm | 3 | **Option B**: Curated corpus (~100 cases) + property-based testing (jqwik) | Threshold sensitivity analysis |
| C10 | Response edge cases | 3 | **Option B**: Extended internal types, mapped to standard external vocabulary | — |
