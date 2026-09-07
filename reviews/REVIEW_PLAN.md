# Architecture Review Plan — Dedicated Reviews

*Purpose: Define all dedicated reviews to conduct at the end of architectural design, before proceeding to detailed design and implementation.*
*Date: 2026-04-12*

---

## Principle

It is much easier and cheaper to fix issues discovered during architecture than during system test or production. An architectural finding costs a document edit. The same finding during system test costs refactoring, retesting, and schedule delay. During production, it costs an incident, customer impact, and potentially regulatory consequences. Every review we conduct now saves exponentially more effort later.

---

## Reviews Completed

| # | Review | Findings | Status |
|---|--------|----------|--------|
| 1 | **Architecture Consistency** — cross-project structural consistency, master decision propagation, inter-service interface contracts, payment flow tracing, timing budget | 29 findings, all resolved | Complete ✓ |
| 2 | **Security (OWASP-based)** — OWASP ASVS L2, API Security Top 10, PCI DSS principles, fortress principle verification | 22 findings, all resolved | Complete ✓ |
| 3 | **Crash Resilience** — crash-point-by-crash-point analysis, dual-write consistency, reservation safety, infrastructure guarantees, DORA | 12 findings, all resolved | Complete ✓ |

---

## Reviews To Conduct

### Review 4: Financial Integrity and Correctness

**Why:** This is the core promise of a payment system. If the money math is wrong — even by one cent — the system is fundamentally broken. Security protects against attacks, crash resilience protects against failures, but financial integrity protects against the system itself being wrong.

**What to verify:**
- Accounting invariants: sum(debits) == sum(credits) at all times, structurally enforced
- BigDecimal used everywhere for monetary values — no float, no double, no int
- Currency handling: correct precision per currency (EUR=2 decimals, JPY=0), ISO 4217 codes validated
- Rounding rules: documented and consistent (half-even/banker's rounding for financial calculations)
- Netting algorithm correctness: deterministic, net positions sum to zero, bilateral matrix correct
- Balance can never go negative: enforced by domain type, projection validation, and database constraint
- Compound events: debit+credit always atomic, structurally impossible to separate
- Reservation arithmetic: available = total - reserved, holds correctly maintained under all operations
- Multi-currency: JSR 354/Moneta usage, no implicit currency conversions, currency mismatch detection
- Reconciliation: A1 vs A2 comparison, discrepancy detection, all invariants verified periodically

**Checklist location:** `reviews/financial_integrity/`

### Review 5: Regulatory Compliance Mapping

**Why:** Compliance gaps are legal liability, not just bugs. The architecture addresses multiple regulations, but we haven't systematically mapped each regulatory requirement to its architectural implementation. A regulation-by-regulation walk-through ensures nothing is missed.

**Regulations to map:**

| Regulation | Scope | Key Articles/Requirements |
|-----------|-------|--------------------------|
| **EU Instant Payment Regulation (2024/886)** | VoP, instant availability, sanctions | Articles 5a (availability), 5c (VoP), 5d (sanctions) |
| **PSD2 (2015/2366) + RTS on SCA** | Authentication, consent, AISP/PISP | SCA requirements, exemption types, consent management, TPP access |
| **AML 5th Directive (2018/843)** | Sanctions screening, audit retention | Customer due diligence, transaction monitoring, record keeping (5 years) |
| **GDPR (2016/679)** | Data protection, right to erasure | Articles 5 (principles), 6 (lawful basis), 17 (right to erasure), 25 (data protection by design), 32 (security) |
| **DORA (2022/2554)** | Operational resilience | Articles 5-16 (ICT risk), 17-23 (incidents), 24-27 (testing), 28-44 (third-party) |
| **Norwegian Payment Systems Act (betalingssystemloven)** | Norwegian payment infrastructure | Applicable to systems operating in Norway |
| **Norwegian Financial Supervision regulations** | PSP requirements | Finanstilsynet requirements for payment service providers |

**For each regulation:** list every applicable requirement, map it to the architectural element that addresses it, flag any gap.

**Checklist location:** `reviews/regulatory_compliance/`

### Review 6: Scalability

**Why:** Architectural decisions made now can create scalability bottlenecks that are expensive to fix later. Even though the portfolio runs at modest scale, the architecture should not contain decisions that structurally prevent scaling.

**What to verify:**
- **Kafka partitioning:** Can partition count be increased without rebalancing pain? Are partition keys well-distributed (no hot partitions)?
- **Database-per-service:** No shared databases that would become bottlenecks? Connection pool sizing documented?
- **Stateless services:** Can A3, A4, A6 scale horizontally by adding instances? What state is lost? (Cache, anti-enumeration counters — documented upgrade to Redis)
- **Stateful services:** Can A2 (Kafka Streams state store), A5 (state store) scale by adding partitions? Is repartitioning documented?
- **A1 event store:** Can `ledger.events` handle growing event volume? Projection rebuild time as events grow?
- **Cache sizing:** A6's 100K cache limit — sufficient at scale? Configurable?
- **Batch processing:** A2 batch with 10K instructions per cycle — what about 100K? 1M? Is Spring Batch chunk size configurable?
- **Single points of failure:** Any component that cannot be scaled or replicated?
- **Data growth:** PostgreSQL table growth over time — are retention policies and archival strategies documented?

**Checklist location:** `reviews/scalability/`

### Review 7: Performance Under Load — Architectural Bottleneck Identification

**Why:** We can't run load tests without code, but we can identify architectural decisions that will become performance bottlenecks under load. Finding these now prevents expensive redesign later.

**What to verify:**
- **Time budget composition:** Do per-service latency targets add up within the 5-second EPC SLA under load (not just happy path)?
- **Synchronous bottlenecks:** Any synchronous call chain that blocks under contention? (e.g., database locks, connection pool exhaustion)
- **Kafka consumer lag:** If A2 falls behind, what happens? Backpressure mechanism?
- **Lock contention:** A4's SCA exemption counters use `SELECT FOR UPDATE` — potential contention under high concurrent SCA requests?
- **Connection pool sizing:** Are pools sized for peak load? A2 instant (min=10, max=20) — sufficient for 2000 TPS?
- **GC pressure:** A5 ML scoring with DJL — large object allocations? Can GC pauses affect the 500ms scoring budget?
- **Cache miss storms:** A6 cold start or cache eviction under high query rate — thundering herd to A1?
- **Netting complexity:** A2 netting with N participants — is the algorithm O(N^2) or better? At what N does it become a bottleneck?
- **Serialization overhead:** Avro serialization/deserialization on every Kafka message — significant at high throughput?
- **Network bandwidth:** Settlement reports (camt.053 XML) — size at scale? Batch status updates for 10K payments?

**Checklist location:** `reviews/performance/`

### Review 8: Certification Readiness

**Why:** The CERTIFICATIONS.md document maps the certification landscape. This review verifies that the architecture actually meets the requirements of each applicable certification/standard, not just claims alignment.

**What to verify (from CERTIFICATIONS.md):**

| Certification | Key Verification |
|--------------|-----------------|
| **EPC SCT Inst 2025 v1.1** | Does our timing (5s/7s/9s) match the rulebook? Are all mandatory rulebook requirements addressed? |
| **EPC VoP Scheme (EPC218-23)** | Does A6 implement both requester and responder per the scheme? Does the matching follow EPC288-23 recommendations? |
| **EPC SRTP** | Does A3 implement the full R2P lifecycle per the rulebook? |
| **PCI DSS v4.0** | For each of the 12 requirements: does our architecture address it? (Even though we don't handle card data, PCI DSS alignment is a quality signal) |
| **ISO 27001 Annex A** | Do our technical controls map to the relevant Annex A controls? |
| **SWIFT CSP** | Do our security controls align with the 32 mandatory SWIFT CSP controls? |
| **DORA** | Are all DORA articles (ICT risk, incidents, testing, third-party) addressed? |
| **eIDAS** | Does A4 support QWAC certificate validation for TPPs? |

**Checklist location:** `reviews/certification/`

### Review 9: Interoperability and Standards Compliance

**Why:** A payment system that doesn't speak the industry's standard language cannot interoperate. ISO 20022 message compliance, EPC scheme alignment, and BerlinGroup API conformance are not optional — they're the price of entry.

**What to verify:**
- **ISO 20022 messages:** Do our Avro schemas and JAXB bindings correctly represent pacs.008 (payment instruction), pacs.002 (status report), pacs.004 (return), camt.053 (account statement), pain.013/014 (R2P)?
- **EPC VoP API:** Does A6's API match the EPC VoP API specification (EPC103-24)?
- **BerlinGroup NextGenPSD2:** Does A4's API conform to the BerlinGroup specification?
- **IBAN handling:** ISO 13616 compliance — format validation, checksum, country-specific rules?
- **Currency codes:** ISO 4217 — full set or restricted? Consistent across all services?
- **Character encoding:** UTF-8 everywhere? EPC character set restrictions for VoP?
- **Date/time formats:** ISO 8601? UTC? Timezone handling consistent?

**Checklist location:** `reviews/interoperability/`

---

## Review Execution Order

| Order | Review | Priority | Rationale |
|-------|--------|----------|-----------|
| 1 | **Financial Integrity** (#4) | **Critical** | Core promise of a payment system — if the math is wrong, nothing else matters |
| 2 | **Regulatory Compliance** (#5) | **Critical** | Legal liability — compliance gaps can prevent deployment |
| 3 | **Scalability** (#6) | **High** | Architectural bottlenecks are expensive to fix later |
| 4 | **Performance** (#7) | **High** | Identifies bottlenecks before code is written |
| 5 | **Certification** (#8) | **Medium** | Verifies claims against actual certification requirements |
| 6 | **Interoperability** (#9) | **Medium** | Standards compliance — important but more implementation-level |

---

## Summary — Final Status

| # | Review | Findings | Status |
|---|--------|----------|--------|
| 1 | Architecture Consistency | 29 | All resolved ✓ |
| 2 | Security (OWASP ASVS L2) | 22 | All resolved ✓ |
| 3 | Crash Resilience | 12 | All resolved ✓ |
| 4 | Financial Integrity | 5 | All resolved ✓ |
| 5 | Regulatory Compliance | 9 | All resolved ✓ |
| 6+7 | Scalability + Performance | 14 | All resolved ✓ |
| 8 | Certification Readiness | 7 | All resolved ✓ |
| 9 | Interoperability | 7 | All resolved ✓ |
| **Total** | **9 reviews** | **105+** | **All resolved** |

**All 9 dedicated architecture reviews are complete. All findings resolved. Architecture is ready for implementation.**
