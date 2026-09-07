# Project #1: Fraud Detection & Sanctions Engine — Gap Analysis

*Comparison against commercial EU systems, target company implementations, and regulatory requirements*

---

## 1. Comparison with Target Company Implementations

### Sportradar UFDS (Universal Fraud Detection System) — Closest Technical Reference

Sportradar's UFDS is the most directly comparable system, even though it targets sports betting fraud rather than payment fraud. Key comparisons:

| Feature | Sportradar UFDS | Our System | Gap? |
|---------|----------------|-----------|------|
| Real-time monitoring | 550+ betting operators, 30B+ odds changes/year | Real-time Kafka stream processing | No — architecturally similar |
| ML inference | DJL (Deep Java Library) on AWS EKS | DJL on Spring Boot / Docker | No — same library |
| Rule + ML hybrid | Rules identify known patterns, ML catches complex patterns | Three-layer: rules + anomaly stats + ML | No — we have an extra layer (statistical) |
| Alert analysts | 40+ integrity analysts in London/Manila/Sydney | Case management API for analysts | Our scope is smaller but pattern is the same |
| Wager-level data | Processes account-level betting data from 400+ bookmakers | Processes transaction-level data from Kafka | Similar granularity |
| Track record | 9,000+ suspicious matches detected, 750+ sanctions, 70+ convictions | Portfolio project — no production track record | Expected difference |

**Key insight from Sportradar:** Their system processes "thousands of wagers per second, consuming hundreds of data points per game." Our throughput target (1,000 TPS) is realistic for a portfolio project and demonstrates similar architectural patterns.

### DNB AML/Fraud Detection

DNB invested heavily in AML after public scrutiny. Their approach (from public information):
- Real-time event processing with Kafka
- ML models for fraud and AML pattern detection
- Graph analytics (unusual for payment fraud — typically used for network-level money laundering detection)

| Feature | DNB | Our System | Gap? |
|---------|-----|-----------|------|
| Kafka event backbone | Yes | Yes | No |
| ML-based scoring | Yes (proprietary models) | Yes (DJL) | No |
| Graph analytics | Yes (for AML network detection) | **No** | Gap — but acceptable for portfolio scope |
| GCP infrastructure | Yes (BigQuery for analytics) | Docker/PostgreSQL | Different scope — we're building the engine, not the data platform |

**Graph analytics gap:** DNB uses graph databases/analytics to detect complex money laundering networks (circular transactions, layering through multiple accounts). This is a genuinely advanced feature we don't have. **Recommendation:** Document as a "future extension" but don't build it — it would require a graph database (Neo4j) and significantly expand scope.

### SpareBank 1 AML

SpareBank 1 has a dedicated AML Tech Lead role focused on "advanced analytics and machine learning for financial crime detection." Their stack: Kotlin, Kafka, PostgreSQL.

**Our alignment is good.** We use the same base technologies. The AML/ML focus aligns with our Layer 3 (DJL scoring).

### Tietoevry Banking AML/KYC

Tietoevry provides compliance solutions as part of their banking platform. They build KYC (Know Your Customer) and AML solutions for their 100+ bank clients.

**Difference:** Tietoevry's AML is part of a broader Banking-as-a-Service platform with KYC, sanctions, PEP (Politically Exposed Persons), and adverse media screening. We focus on transaction-level fraud + sanctions. KYC/PEP is a customer onboarding concern, not a transaction screening concern.

---

## 2. Comparison with Commercial EU Fraud Detection Platforms

### NICE Actimize (Market Leader)

| Feature | NICE Actimize | Our System | Assessment |
|---------|--------------|-----------|------------|
| Real-time transaction monitoring | Yes, enterprise-grade | Yes, Kafka-based | Similar pattern |
| AI/ML models | Proprietary, pre-trained on industry data | DJL with custom model | We train our own — different approach |
| Sanctions screening | Full OFAC/EU/UN coverage, PEP screening | EU/UN sanctions only, no PEP | **Gap**: no PEP screening |
| Case management | Full workflow, assignment, escalation, SLA tracking | Basic: list, view, resolve | **Gap**: no escalation, no SLA tracking |
| Regulatory reporting | Automated SAR/STR filing | FIU report generated, not automated filing | **Partial gap** |
| Customer risk profiling | Risk-rate customers at onboarding | Account profiles built from transaction history | Different: we profile from behavior, not onboarding |
| Network/link analysis | Yes (graph-based) | No | **Gap** — same as DNB |
| Adverse media screening | Yes | No | **Gap** — acceptable for portfolio |

### Featurespace (ARIC Platform)

| Feature | Featurespace | Our System | Assessment |
|---------|-------------|-----------|------------|
| Adaptive behavioral analytics | Self-learning profiles per customer | Rolling statistical profiles | Similar concept, simpler implementation |
| Real-time scoring | Sub-10ms | < 100ms (< 10ms for ML alone) | Their engine is faster, but our target is realistic |
| Explainability | Transparent AI with rule explanations | Rule explanations + score breakdown | Similar approach |
| Multi-channel | Cards, payments, account activity | Credit transfers only | **Narrower scope** — acceptable |

### Feedzai

| Feature | Feedzai | Our System | Assessment |
|---------|---------|-----------|------------|
| AI-first approach | Deep learning with feature engineering | DJL model + handcrafted features | Similar |
| Real-time + batch | Both | Both (separate Kafka topics) | Same |
| Open Banking fraud | PSD2-specific fraud detection | VoP integration | Similar |
| Risk engine | Central decisioning platform | Kafka Streams pipeline | Different architecture, same function |

---

## 3. EU Regulatory Compliance Assessment

### EU AML Package 2024

| Requirement | Our Status | Assessment |
|------------|-----------|------------|
| Transaction monitoring for suspicious patterns | **Have** (velocity rules + anomaly detection) | Compliant |
| Sanctions screening (EU Consolidated List) | **Have** (SAN-F01) | Compliant |
| UN sanctions screening | **Have** (SAN-F02) | Compliant |
| Near-real-time screening (not batch against stale lists) | **Have** (< 50ms, lists updated hourly) | Compliant |
| Suspicious Transaction Report (STR) capability | **Partial** (FIU report generated but not in regulatory format) | Could improve |
| PEP (Politically Exposed Persons) screening | **Gap** | Not legally required for transaction screening (PEP is for onboarding/KYC) |
| Record retention (5 years minimum) | **Not configured** | Infrastructure concern, documented as requirement |
| Employee training records | **Out of scope** | Organizational, not technical |

### EU Instant Payment Regulation (IPR) — Sanctions Screening

| Requirement | Our Status | Assessment |
|------------|-----------|------------|
| Screening must not delay 10-second SLA | **Have** (< 50ms screening) | Compliant |
| Screening against EU Consolidated List | **Have** | Compliant |
| PSP must verify payer/payee are not sanctioned | **Have** (screen both) | Compliant |
| Lists must be kept current | **Have** (hourly updates) | Compliant |
| Cannot skip screening — must reject if unavailable | **Have** (SAN-F12) | Compliant |

### PSD2 Fraud Reporting (EBA Guidelines)

PSPs must report fraud data to their national competent authority. This includes:
- Fraud rates by payment type (credit transfer, direct debit, card)
- Fraud categorization (unauthorized, authorized push payment)
- Geographic distribution
- Authentication method used

**Our status:** We produce scoring metrics and case resolution data but don't format it as regulatory reports. **Recommendation:** Add a reporting endpoint that aggregates data in the format required by national authorities. Low priority for portfolio but good to document.

---

## 4. "Exotic" Features Assessment

### Feature: Deep Java Library (DJL) for ML Scoring

**Is this unusual for a portfolio project?** Very unusual. Most portfolio fraud detection projects use simple if/else rules.
**Justification:** Sportradar explicitly uses DJL in production on AWS EKS. This is verified from their AWS case study. Using the same library in our portfolio directly mirrors a target company's technology choice.
**Verdict:** **Keep — this is the single strongest differentiator.** Few candidates will demonstrate DJL in a payment context.

### Feature: Three-layer Pipeline (Sanctions + Rules + Anomaly + ML)

**Is this unusual?** The three-layer approach (rules + statistical + ML) mirrors commercial platforms (NICE Actimize, Featurespace). Having sanctions as a separate, mandatory layer is the correct production architecture. Most portfolio projects use rules only.
**Verdict:** **Keep — it demonstrates understanding of production architecture.**

### Feature: Kafka Streams State Stores for Velocity Tracking

**Is this unusual?** Using Kafka Streams (not just Kafka consumers) for stateful stream processing is less common in portfolio projects. Most use simple consumers + external database queries.
**Justification:** DNB, SpareBank 1, and NAV all use Kafka as their event backbone. Kafka Streams state stores provide exactly-once semantics for windowed aggregations without external database round-trips — critical for the 500ms latency budget.
**Verdict:** **Keep — it's the right tool for the job and shows Kafka Streams expertise.**

### Feature: Bloom Filter for Sanctions Fast-Path

**Is this unusual?** In portfolio projects, yes. In production sanctions screening, no — it's standard practice.
**Justification:** With ~15,000 entities on the EU sanctions list, a Bloom filter provides O(1) negative checks in <1ms, eliminating 99%+ of transactions from further screening. Without it, every transaction would need fuzzy matching against 15,000 names, which is much slower.
**Verdict:** **Keep — it demonstrates understanding of performance optimization for real-time systems.**

---

## 5. Features Others Have That We Don't (Potential Extensions)

| Feature | Who Has It | Priority | Recommendation |
|---------|-----------|----------|----------------|
| **Graph-based network analysis** | DNB, NICE Actimize | Low | Document as future extension. Would need Neo4j. Very advanced |
| **PEP screening** | NICE Actimize, commercial platforms | Low | PEP is for KYC/onboarding, not transaction-level. Out of scope for payment processing |
| **Adverse media screening** | Commercial platforms | Low | Would need NLP + news API integration. Out of scope |
| **Automated SAR/STR filing** | Commercial platforms | Medium | Add report generation endpoint, but not automated regulatory filing |
| **Case escalation workflow** | Commercial platforms | Medium | Add INVESTIGATING state + escalation to senior analyst. Small effort |
| **SLA tracking on case resolution** | Commercial platforms | Low | Time from alert creation to resolution. Nice metric but not core |
| **Multi-channel fraud** | Feedzai, NICE Actimize | Low | We focus on credit transfers. Card fraud is a different domain |
| **Customer risk scoring at onboarding** | Commercial platforms | Low | KYC concern, not transaction monitoring |
| **Behavioral biometrics** | Some advanced platforms | Very Low | Mouse movement, typing patterns. Far out of scope |

---

## 6. Summary

| Category | Assessment |
|----------|-----------|
| **EU regulatory compliance** | Strong. Sanctions screening meets IPR requirements. Transaction monitoring meets AML Package requirements. PEP/KYC out of scope (correct decision) |
| **Target company alignment** | Excellent. Kafka Streams matches DNB/SpareBank1/NAV. DJL matches Sportradar. Three-layer pipeline matches commercial platforms |
| **Commercial platform comparison** | Realistic subset. We cover core features (real-time scoring, sanctions, case management) without the breadth of enterprise platforms (which is expected for a portfolio project) |
| **Exotic features** | All justified. DJL, Kafka Streams state stores, Bloom filter, three-layer pipeline — each has a clear rationale tied to target companies or production best practices |
| **Key gap** | Graph-based network analysis (DNB uses this for AML). Document as future extension, don't build |
