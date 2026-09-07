# Requirements Review — Comparative Analysis vs. Allevo FinTP

*Reviewer: Claude (deep comparative review)*
*Date: 2026-04-11*
*Scope: Our Nordic Payment Processing Infrastructure requirements vs. Allevo FinTP (Bucharest, Romania)*
*Sources: Our full requirements suite (system_requirements.md, 10 use cases, 6 extensions, 7 sub-project requirements), Allevo/FinTP documentation (allevo.ro, fintp.org, github.com/fintp), production deployment information, press releases, and product datasheets*

---

## Executive Summary

Allevo is a Romanian fintech company (founded 1998, ~30-57 employees) that builds **FinTP** (Financial Transactions Processing), an open-source (GPL v3) payment processing middleware deployed in production at Romanian banks including BCR (Erste Group), Raiffeisen Bank Romania, Garanti BBVA Romania, EximBank, CEC Bank, and ING Romania. FinTP has been processing real bank payments since 2003 — it is not a prototype or portfolio project. It connects bank back-office systems to SWIFT, SEPA, TARGET2, national ACH (SENT/TransFonD), and instant payment infrastructure (TIPS, RT1, TransFonD IPS).

**The comparison is inherently asymmetrical.** FinTP is production middleware focused on message routing, format conversion, and infrastructure connectivity. Our system is a full-stack payment platform with its own account ledger, fraud engine, clearing logic, and Open Banking gateway. They solve different problems at different layers of the payment stack. That said, the comparison reveals genuine gaps in our requirements and areas where Allevo's production experience exposes our design-stage blind spots.

### Bottom Line

| Dimension | Our System | Allevo FinTP | Verdict |
|-----------|-----------|-------------|---------|
| **Architecture scope** | Full platform (6 sub-systems end-to-end) | Middleware layer (routing + connectivity) | **We are broader** |
| **Message format handling** | ISO 20022 only (pacs/pain/camt) | Multi-format: SWIFT FIN (MT), ISO 20022, SEPA XML, flat files, proprietary | **Allevo is significantly better** |
| **Infrastructure connectivity** | Abstract/simulated | Real: SWIFT, TARGET2, TIPS, RT1, national ACH | **Allevo is better (production reality)** |
| **Transaction routing / STP** | Not modeled | Core competency — content-based + rule-based routing engine | **Allevo is better — we have a gap** |
| **Reconciliation** | Modeled (LED-F36 through F39, A1-F15 through F17) | Core strength — production-proven, ML-enhanced exception handling | **Allevo is deeper — we learned from them** |
| **Duplicate detection** | Modeled (semantic dedup, CLR-B13-B15) | Modeled (configurable, multi-level) | **Comparable** |
| **Fraud detection** | Sophisticated (3-layer: sanctions + rules + ML/DJL) | Basic (list-based filtering, behavioral alerts) | **We are much better** |
| **Sanctions screening** | Detailed (Bloom filter + Aho-Corasick + fuzzy match) | List-based filtering (no documented algorithmic detail) | **We are much better** |
| **Open Banking / PSD2** | Full: OAuth2, SCA exemptions, VRP, AISP/PISP | FinTP-Connect: PISP/AISP, SCA, TPP validation | **We are better (SCA exemptions, VRP)** |
| **Instant payments** | Detailed (10-sec SLA, time budget, Kafka Streams) | FinTP-Instant: connects to TIPS/RT1/TransFonD IPS (production) | **Comparable approach, Allevo has production proof** |
| **Account ledger** | Full event-sourced CQRS ledger (A1) | Not in scope (relies on bank's core banking) | **We are broader** |
| **Verification of Payee** | Full VoP service (A6) — requester + responder | Not documented | **We are better** |
| **Liquidity management** | Modeled (CLR-L01 through L06) | Reporting + forecasting | **Comparable scope** |
| **Multi-format / legacy support** | None — ISO 20022 only | Extensive (MT/MX coexistence, proprietary, flat files) | **Allevo is much better** |
| **Business continuity** | Not modeled | Transaction mirroring, backup platform connectivity | **Allevo is better — we have a gap** |
| **Corporate treasury** | Not in scope | FinTPc / FinOps Suite | **Allevo has more, but outside our scope** |
| **Microfinance / mobile money** | Not in scope | FinTPm (M-PESA, MTN integration) | **Different market** |
| **Documentation maturity** | Very detailed requirements + use cases | Limited public documentation | **We are better** |

---

## Part 1: Where Allevo FinTP Is Better Than Our System

These are the areas where Allevo's production experience and real-world deployment reveal genuine gaps or weaknesses in our requirements.

### 1.1 Multi-Format Message Processing (CRITICAL GAP)

**Allevo's approach:** FinTP is fundamentally a message format converter and router. It handles:
- SWIFT FIN messages (MT103, MT202, MT940, etc.)
- SWIFT MX / ISO 20022 (pacs, pain, camt)
- SEPA XML (both SCT and SDD)
- National proprietary formats (Romanian SENT/REGIS)
- Flat files (legacy corporate formats)
- Arbitrary XML structures

FinTP can transform between any of these formats — a bank running FinTP can receive a SWIFT MT103 from a correspondent bank and convert it to an ISO 20022 pacs.008 for SEPA clearing, all automatically.

**Our approach:** We support ISO 20022 only (pacs.008, pacs.002, pacs.004, pain.001, pain.013/014, camt.053). Our requirements assume all messages are already in ISO 20022 format.

**Why this matters: HIGH IMPORTANCE**

The real-world payment landscape is in the middle of a multi-year ISO 20022 migration. The SWIFT MT-to-MX migration deadline has been extended to November 2025 for cross-border payments, but the transition will take years. Any production payment system today must handle both MT and MX messages. By supporting only ISO 20022, our system cannot process the majority of cross-border payment messages that still arrive in SWIFT FIN format.

For a portfolio project targeting Mastercard Oslo (which processes NICS clearing), DNB, and Tietoevry, demonstrating awareness of the MT/MX coexistence challenge is important. These companies deal with format migration daily.

**Impact on our requirements:**
- Our system would be unable to receive or process SWIFT MT messages
- Correspondent banking flows (common at DNB, SpareBank 1) would fail
- The MT-to-MX migration is a major industry project — showing awareness of it signals domain expertise

**Recommendation:** Add a requirement (or at minimum an ADR) acknowledging the MT/MX coexistence period. Consider:
- A `payment-format-converter` library that can parse MT103 and map to our internal pacs.008 model
- This does not need to be a full implementation — even a JAXB+regex MT103 parser with field mapping would demonstrate the skill
- Document which MT messages map to which MX messages (MT103 → pacs.008, MT202 → pacs.009, MT940 → camt.053)

---

### 1.2 Transaction Routing / Straight-Through Processing (SIGNIFICANT GAP)

**Allevo's approach:** FinTP's routing engine (`fintp_routingengine`) is its architectural heart. It provides:
- **Content-based routing**: Examines message content (payment type, amount, currency, destination country, urgency) and routes to the appropriate clearing system (SWIFT for cross-border, national ACH for domestic low-value, RTGS for domestic high-value, TIPS/RT1 for instant)
- **Rule-based routing**: Configurable routing rules that can be changed without code changes
- **STP (Straight-Through Processing)**: Messages that pass all validation and routing rules are processed end-to-end without human intervention
- **Investigation queues**: Messages that fail routing or validation are placed in operator queues for manual review and repair

**Our approach:** Our clearing engine (A2) has two hard-coded paths: batch and instant. Payment instructions arrive on one of two Kafka topics (`payment.instructions` or `payment.instant-instructions`) and the path is determined by the `service_level` field set by the originator (A3 or A4). There is no intelligent routing logic.

**Why this matters: MEDIUM-HIGH IMPORTANCE**

In a real payment system, the routing decision is often made by the clearing infrastructure, not by the payment originator. A single payment instruction might need to be routed to:
- National ACH (low-value domestic, non-urgent)
- RTGS (high-value domestic, urgent)
- TIPS or RT1 (instant domestic or cross-border SEPA)
- SWIFT (cross-border non-SEPA)
- Internal book transfer (both accounts at the same bank)

Our current architecture puts the routing burden on A3/A4, which must know which Kafka topic to publish to. A production system would have a routing layer that makes this decision based on the payment's characteristics.

**Impact on our requirements:**
- We cannot model the common "payment factory" pattern where a single API accepts all payment types and the system determines the optimal route
- We cannot demonstrate awareness of how real clearing systems (NICS, P27) route payments
- Internal book transfers (debtor and creditor at the same bank) have no optimized path — they go through the full clearing engine unnecessarily

**Recommendation:** Add a routing decision component to A2:
- `A2-F50`: Payment routing engine: determine optimal clearing path based on payment characteristics (amount, currency, urgency, debtor/creditor bank, domestic/cross-border)
- `A2-F51`: Internal book transfer detection: if debtor and creditor are both in our ledger, execute direct ledger transfer without going through the full clearing cycle
- `A2-F52`: Routing rules configurable via YAML/properties (not hardcoded)
- This adds significant realism without excessive implementation effort

---

### 1.3 Business Continuity and Disaster Recovery (GAP)

**Allevo's approach:** FinTP includes:
- **Transaction mirroring** to a backup platform for business continuity
- **eArchive** capabilities for regulatory retention
- Recovery mechanisms for in-flight transactions

**Our approach:** Not modeled. Our requirements mention availability (99.99% for instant payments) but say nothing about:
- How the system recovers from a crash mid-transaction
- What happens to in-flight instant payments during a failover
- Whether transaction data is replicated for disaster recovery
- Retention policies for payment data (regulatory requirement)

**Why this matters: MEDIUM IMPORTANCE**

For a portfolio project, full DR implementation is out of scope. However, the *absence of any discussion* of business continuity is noticeable. Payment systems are critical infrastructure — any interviewer at Mastercard or DNB would expect awareness of:
- What happens to an instant payment if the system crashes at step 7 of 10
- How Kafka's durability guarantees (acks=all, replication factor 3) contribute to recovery
- Data retention requirements (typically 5-10 years for payment records under EU/Norwegian law)

**Recommendation:** Add:
- `OPS-09`: Data retention: payment records, audit logs, and settlement reports retained for minimum 5 years (configurable). Archival strategy documented
- `OPS-10`: Crash recovery: in-flight instant payments that were not confirmed within 10 seconds are auto-rejected on restart. Balance reservations have TTL-based auto-release (already partially addressed in LED-F14)
- An ADR documenting how Kafka's durability guarantees enable crash recovery without a separate DR mechanism

---

### 1.4 Investigation Queues and Operator Workflows (GAP)

**Allevo's approach:** FinTP provides operator investigation queues where messages that fail validation, routing, or compliance checks are presented to human operators for manual review, repair, and resubmission.

**Our approach:** We have case management for fraud/sanctions alerts (CASE-F01 through CASE-F09), but we have no equivalent for:
- Payment instructions that fail ISO 20022 schema validation — they are simply REJECTED
- Payments where VoP returns NO_MATCH but the payer is not available to confirm — stuck in limbo
- Batch payments that partially fail — individual failures are logged but there is no operator review workflow

**Why this matters: MEDIUM IMPORTANCE**

In production payment processing, a meaningful percentage of transactions (1-5%) hit edge cases that require human intervention. Simply rejecting these means losing business. FinTP's investigation queues allow operators to fix and resubmit payments, which is standard practice in bank payment operations.

**Recommendation:** Consider adding an operations queue concept to A2:
- `A2-F55`: Payments that fail non-terminal validation (schema fixable, amount limit configurable, VoP inconclusive) are placed in an investigation queue rather than immediately rejected
- `A2-F56`: Operator API: view queued payments, edit/correct, resubmit or reject with reason
- This is lower priority than routing, but adds realism

---

### 1.5 SEPA Direct Debit (Already Identified)

**Allevo's approach:** FinTP supports SDD (SEPA Direct Debit) natively — both Core and B2B variants, with pain.008 message handling.

**Our approach:** We chose the AvtaleGiro (push) model over SDD (pull). This is documented and justified in UC-06 (safer by design, Norwegian market alignment, works with instant payments).

**Why this matters: MEDIUM IMPORTANCE**

Our gap analysis already identified this (GAP 5) and documented it thoroughly in UC-06 with a detailed comparison table. The decision to use AvtaleGiro over SDD is well-reasoned. However, for pan-European credibility, having at least a design-level SDD requirement would strengthen the system.

**Recommendation:** Already in our Phase 2 roadmap. No change needed — the existing gap analysis handles this well.

---

## Part 2: Where Our System Is Better Than Allevo FinTP

### 2.1 Fraud Detection and Sanctions Screening (MUCH BETTER)

**Our approach:** Three-layer detection pipeline:
1. Sanctions screening with Bloom filter + Aho-Corasick + Jaro-Winkler fuzzy matching (<50ms)
2. Rule-based velocity checks (10 configurable rules, Kafka Streams windowed state stores)
3. ML model scoring via Deep Java Library (DJL)
Plus case management with full analyst workflow, VoP integration as fraud signal, and semantic duplicate detection feeding into fraud scoring.

**Allevo's approach:** List-based transaction filtering against sanctions/watch lists. "Competitive behavioral alerts" for anomaly detection. No documented ML capability for fraud detection (ML is mentioned only for reconciliation exception handling). No documented algorithmic detail for sanctions matching (no Bloom filter, no Aho-Corasick, no fuzzy matching algorithms described).

**Significance:** Our fraud and sanctions architecture is dramatically more sophisticated. The three-layer pipeline, the explicit algorithm choices (Bloom filter for O(1) negative check, Aho-Corasick for multi-pattern matching), the VoP-to-fraud feedback loop, and the DJL integration represent a genuinely advanced design. This is one of the strongest differentiators in our system and directly aligns with target company needs (DNB AML investment, Sportradar UFDS architecture).

### 2.2 Verification of Payee (BETTER — Allevo Has No Equivalent)

**Our approach:** Full VoP service (A6) with:
- Requester role (check before sending)
- Responder role (answer queries about our accounts)
- Jaro-Winkler fuzzy name matching with configurable thresholds
- Nordic diacritics handling (ø, å, ä, ü)
- Anti-enumeration protection (rate limiting, repeated query detection)
- Integration with fraud scoring (NO_MATCH override = +0.3 risk signal)
- Mandatory under EU IPR from October 2025

**Allevo's approach:** No VoP/CoP capability documented. FinTP predates the EU VoP mandate.

**Significance:** VoP is now legally mandatory. Our proactive inclusion of a full VoP service — with both roles, fuzzy matching, and fraud integration — demonstrates forward-looking regulatory awareness. This is a clear advantage.

### 2.3 Event-Sourced Account Ledger (BROADER SCOPE)

**Our approach:** Full event-sourced CQRS account ledger (A1) with:
- Immutable event log in Kafka
- Double-entry bookkeeping invariant
- Multi-currency support (JSR 354/Moneta)
- Balance reservations (hold/confirm/release) for instant payments
- Point-in-time balance queries
- Projection rebuild capability
- Reconciliation against external settlement reports

**Allevo's approach:** FinTP does not include an account ledger — it relies on the bank's existing core banking system for account data. FinTP is middleware that sits between the core banking system and external payment infrastructure.

**Significance:** Our system is architecturally more complete. Having our own ledger means we can demonstrate the full payment lifecycle end-to-end, including the critical balance reservation pattern for instant payments. However, this is partly a scope difference — FinTP deliberately avoids reimplementing core banking functionality.

### 2.4 Open Banking Sophistication (BETTER)

**Our approach:** Full PSD2 API Gateway (A4) with:
- OAuth2 Authorization Server (Spring Authorization Server)
- SCA with four exemption types (low-value, TRA, trusted beneficiary, recurring)
- Cumulative counter tracking for low-value exemptions
- Variable Recurring Payments (VRP) with parameterized consent bounds
- Refresh token rotation with family revocation for theft detection
- JWE encryption for sensitive API responses
- PKCE, mTLS simulation, dynamic linking for payments

**Allevo's approach:** FinTP-Connect provides PSD2 PISP/AISP with SCA and TPP validation, plus an API gateway. But no SCA exemption engine, no VRP, no documented token rotation strategy.

**Significance:** Our SCA exemption engine is a genuine differentiator. Modeling the four exemption types with cumulative counters and TRA fraud rate tracking demonstrates deep understanding of the PSD2 RTS. VRP is forward-looking (currently UK-only). These features directly matter to every target company (DNB, SpareBank 1, Mastercard, Tietoevry all implement PSD2).

### 2.5 Request-to-Pay Lifecycle (BETTER)

**Our approach:** Full R2P lifecycle (A3) evolved from eFaktura toward SRTP:
- Accept, decline (with reason code), defer (pay later), request amendment
- QR code generation (EPC069-12 standard)
- Recurring mandates (AvtaleGiro pattern)
- HMAC-SHA256 signed webhooks with exponential backoff retry
- ISO 20022 pain.013/014 message support

**Allevo's approach:** FinTP handles payment initiation and processing but does not model the request-to-pay lifecycle. R2P (SRTP) is a newer scheme that FinTP predates.

**Significance:** Our R2P lifecycle — especially the full decline/defer/amend flows — demonstrates understanding of the emerging European payment landscape. This is a forward-looking feature.

### 2.6 Detailed Requirements Documentation (BETTER)

**Our approach:** 568+ lines of master requirements, 10 detailed use cases with step-by-step scenarios, alternative flows, data models, security considerations, and explicit acceptance criteria. Cross-referenced via NAVIGATION.md with Kafka topic topology, requirement ID mapping, and sub-system relationships.

**Allevo's approach:** Limited public documentation. Product descriptions are marketing-oriented. GitHub repos have minimal documentation. The detailed technical documentation is presumably internal and provided to bank clients under support contracts.

**Significance:** For a portfolio project, documentation quality is itself a deliverable. Our documentation demonstrates the ability to produce clear, complete, and cross-referenced requirements — a skill valued at every target company.

---

## Part 3: Importance Assessment of Allevo's Advantages

How important are the areas where Allevo is better, in the context of our project goals (portfolio targeting Norwegian payment companies)?

| Gap | Importance for Portfolio | Importance for Production | Recommendation |
|-----|------------------------|--------------------------|----------------|
| **Multi-format message processing (MT/MX)** | **HIGH** — Mastercard Oslo, DNB, Tietoevry all deal with MT/MX migration daily. Showing awareness is important. A working MT103 parser would be impressive | **CRITICAL** — No production system can ignore SWIFT MT messages today | Add MT103→pacs.008 converter as a library module |
| **Transaction routing / STP** | **HIGH** — Payment routing is core functionality at Mastercard Oslo (NICS) and Tietoevry. Our current two-path model is oversimplified | **CRITICAL** — Every real clearing system has intelligent routing | Add routing decision engine to A2 |
| **Business continuity / DR** | **MEDIUM** — Interviewers expect awareness but not full implementation in a portfolio project | **CRITICAL** — Payment systems are critical infrastructure | Add ADR + minimal requirements (OPS-09, OPS-10) |
| **Investigation queues** | **LOW-MEDIUM** — Nice to have, shows operational awareness | **HIGH** — 1-5% of transactions need human intervention in production | Consider for Phase 3 |
| **SEPA Direct Debit** | **MEDIUM** — Already identified in gap analysis, planned for Phase 2 | **HIGH** — Core European payment type | Already planned |
| **Corporate treasury** | **LOW** — Out of scope for our project | **MEDIUM** — Some overlap with payment processing | No action needed |
| **Legacy/proprietary format support** | **LOW** — Our ISO 20022-only approach is defensible for a new system | **HIGH** — Banks have decades of legacy formats | Document the decision in an ADR |

---

## Part 4: Detailed Feature Comparison Matrix

| # | Feature | Our System | Allevo FinTP | Notes |
|---|---------|-----------|-------------|-------|
| 1 | ISO 20022 pacs.008 (credit transfer) | YES | YES | Both handle the core interbank credit transfer message |
| 2 | ISO 20022 pacs.002 (status report) | YES | YES | Payment confirmation/rejection |
| 3 | ISO 20022 pacs.004 (payment return) | YES (Should) | YES | Post-settlement returns |
| 4 | ISO 20022 pain.001 (customer initiation) | YES | YES | Customer-to-bank payment initiation |
| 5 | ISO 20022 pain.013/014 (R2P) | YES (Should) | Not documented | Request-to-pay messages |
| 6 | ISO 20022 camt.053 (statement) | YES | YES | Account statements and settlement reports |
| 7 | SWIFT MT103 (customer transfer) | **NO** | YES | Single customer credit transfer — most common cross-border message |
| 8 | SWIFT MT202 (bank transfer) | **NO** | YES | Bank-to-bank transfer (cover payments) |
| 9 | SWIFT MT940/950 (statements) | **NO** | YES | Legacy account statement formats |
| 10 | Proprietary message formats | **NO** | YES | Bank-specific formats, flat files |
| 11 | Format conversion (MT↔MX) | **NO** | YES | **Key FinTP capability** |
| 12 | Content-based routing | **NO** | YES | Route by payment characteristics |
| 13 | Rule-based routing | **NO** | YES (core feature) | Configurable routing rules |
| 14 | SWIFT connectivity | **NO** (simulated) | YES (production) | Real SWIFT network integration |
| 15 | TARGET2 / RTGS | **NO** (simulated) | YES (production) | Real-time gross settlement system |
| 16 | TIPS connectivity | Modeled (abstract) | YES (production) | Instant EUR payments |
| 17 | RT1 connectivity | Modeled (abstract) | YES (production) | EBA Clearing instant payments |
| 18 | National ACH connectivity | Modeled (abstract) | YES (TransFonD SENT) | Domestic low-value clearing |
| 19 | Batch clearing / netting | YES (Spring Batch) | YES (batch processing) | Multilateral netting |
| 20 | Instant payment processing | YES (Kafka Streams, 10-sec SLA) | YES (FinTP-Instant, 24/7) | Both model instant payments |
| 21 | Event-sourced ledger | YES (Kafka + PostgreSQL CQRS) | **NO** (relies on core banking) | Different scope |
| 22 | Multi-currency accounts | YES (JSR 354/Moneta, 5 currencies) | YES (multi-currency treasury) | Both support multi-currency |
| 23 | Balance reservations (holds) | YES (reserve/confirm/release) | **NO** (not in scope) | Critical for instant payments |
| 24 | Sanctions screening (real-time) | YES (Bloom+Aho-Corasick+fuzzy) | YES (list-based filtering) | Our approach is far more sophisticated |
| 25 | Fuzzy name matching (sanctions) | YES (Jaro-Winkler+Soundex+Metaphone) | Not documented in detail | Algorithm-level specification is our advantage |
| 26 | Fraud detection (ML-based) | YES (DJL, 3-layer pipeline) | **NO** (basic behavioral alerts) | Major differentiator for us |
| 27 | Velocity rules engine | YES (10 rules, Kafka Streams state) | Not documented in detail | Our rules are explicit and configurable |
| 28 | Case management (fraud/sanctions) | YES (full analyst workflow) | Basic (investigation queues) | Our case management is more structured |
| 29 | PSD2 AISP/PISP | YES (full) | YES (FinTP-Connect) | Both handle Open Banking |
| 30 | OAuth2 / SCA | YES (Spring Auth Server, TOTP) | YES (SCA support) | Both model authentication |
| 31 | SCA exemption engine | YES (4 types, cumulative counters) | **NO** | Significant differentiator |
| 32 | Variable Recurring Payments (VRP) | YES (parameterized consent) | **NO** | Forward-looking feature |
| 33 | Verification of Payee (VoP) | YES (requester + responder) | **NO** | EU-mandated, we're ahead |
| 34 | Request-to-Pay lifecycle | YES (accept/decline/defer/amend) | **NO** | SRTP model, forward-looking |
| 35 | QR code payments | YES (EPC069-12, ZXing) | Not documented | |
| 36 | Recurring mandates | YES (AvtaleGiro pattern) | Not documented as feature | |
| 37 | SEPA Direct Debit (SDD) | **NO** (planned Phase 2) | YES | Core EU payment type |
| 38 | Reconciliation | YES (LED-F36-F39, automated) | YES (core strength, ML-enhanced) | Allevo is production-proven here |
| 39 | Duplicate detection | YES (semantic, configurable) | YES (configurable, multi-level) | Comparable |
| 40 | Liquidity management | YES (CLR-L01-L06) | YES (reporting + forecasting) | Comparable scope |
| 41 | Transaction mirroring / DR | **NO** | YES | Gap in our system |
| 42 | eArchive / data retention | **NO** | YES | Gap in our system |
| 43 | Operator investigation queues | **NO** (fraud alerts only) | YES (core feature) | Gap for non-fraud exceptions |
| 44 | Webhook notifications | YES (HMAC-SHA256, retry) | Not documented | |
| 45 | Pact contract testing | YES | **NO** | Our testing strategy is more modern |
| 46 | Docker-compose deployment | YES (one command) | **NO** (traditional deployment) | Advantage for portfolio demo |
| 47 | Prometheus/Grafana monitoring | YES (Micrometer + custom metrics) | Performance monitoring (proprietary) | Our approach is cloud-native |
| 48 | CBDC/Digital Euro readiness | YES (ADR, settlement asset abstraction) | **NO** | Strategic signal |
| 49 | SWIFT gpi | **NO** | YES (CEC Bank deployment) | Tracking cross-border payments |
| 50 | Corporate treasury | **NO** (out of scope) | YES (FinTPc/FinOps Suite) | Different market |

---

## Part 5: Recommendations

Based on this comparative analysis, here are specific recommendations for improving our requirements, ordered by priority:

### Priority 1: Add MT/MX Awareness (HIGH VALUE, MEDIUM EFFORT)

**What to add:**
- New shared library: `payment-format-converter`
- Requirements:
  - `FMT-F01`: Parse SWIFT MT103 (single customer credit transfer) into internal payment instruction model
  - `FMT-F02`: Parse SWIFT MT202 (bank-to-bank transfer) into internal payment instruction model
  - `FMT-F03`: Generate MT940 account statement from internal ledger data (alternative to camt.053)
  - `FMT-F04`: Bidirectional MT↔MX field mapping with data enrichment (MT103 has fewer fields than pacs.008 — handle the mapping gaps)
- ADR: "Why ISO 20022 is our primary format and how we handle the MT/MX coexistence period"

**Rationale:** Mastercard Oslo engineers work with MT/MX migration. A working MT103 parser demonstrates that you understand the real-world complexity. The effort is manageable — MT103 is a fixed-tag format that can be parsed with regex/state machine, and the field mapping to pacs.008 is well-documented by SWIFT.

### Priority 2: Add Payment Routing Decision Engine (HIGH VALUE, LOW-MEDIUM EFFORT)

**What to add to A2:**
- `A2-F50`: **Routing decision engine**: determine clearing path based on payment characteristics:
  - Internal book transfer (both accounts in our ledger) → direct ledger transfer, skip clearing
  - Domestic, non-urgent, below threshold → batch path
  - Domestic, urgent or instant-flagged → instant path
  - Cross-border SEPA → batch path (or instant if SCT Inst)
  - Cross-border non-SEPA → (out of scope, log routing decision)
- `A2-F51`: **Internal book transfer optimization**: if debtor and creditor accounts are both in A1, execute direct debit/credit without Kafka round-trip through clearing engine. Still run sanctions screening and fraud check, but skip netting and external settlement
- `A2-F52`: **Routing rules configuration**: routing criteria defined in YAML configuration, not hardcoded. New clearing paths can be added by configuration change

**Rationale:** This transforms our A2 from a "dumb pipe with two modes" into an intelligent payment router — which is what real clearing engines do. The internal book transfer optimization alone is a significant realism improvement (real banks process millions of internal transfers daily, and routing them through the full clearing cycle is wasteful).

### Priority 3: Add Business Continuity Awareness (MEDIUM VALUE, LOW EFFORT)

**What to add:**
- `OPS-09`: **Data retention policy**: payment records, audit trails, and settlement reports retained for minimum 10 years per Norwegian Bokforingsloven and EU AML Directive. Archival strategy: active data in PostgreSQL (2 years), archived data in cold storage
- `OPS-10`: **Crash recovery for instant payments**: in-flight instant payments not confirmed within the 10-second SLA are auto-rejected on system restart. Balance reservations auto-released after configurable TTL (default: 30 seconds). Kafka consumer offsets committed only after successful processing (at-least-once delivery with idempotent processing ensures no payment loss)
- `OPS-11`: **Transaction log durability**: Kafka replication factor = 3, min.insync.replicas = 2, acks = all. Document in ADR: "Why Kafka's durability guarantees provide our crash recovery mechanism"

**Rationale:** These requirements are low-effort (no implementation change needed — they document what we already have and add missing policy requirements) but demonstrate awareness of production operational concerns. Any interviewer at a bank will appreciate seeing data retention and crash recovery addressed explicitly.

### Priority 4: Add Operational Investigation Queue (MEDIUM VALUE, MEDIUM EFFORT)

**What to add to A2:**
- `A2-F55`: **Investigation queue**: payment instructions that fail non-terminal validation are queued for operator review instead of being immediately rejected. Queued reasons include: schema validation warnings (non-blocking), amount exceeding soft limit (configurable), VoP inconclusive (timeout/error), semantic duplicate detected (pending payer confirmation unavailable)
- `A2-F56`: **Operator API**: GET queued payments (paginated, filterable by reason), PUT correct and resubmit, PUT reject with reason
- `A2-F57`: **Investigation queue metrics**: queue depth, average resolution time, auto-escalation after configurable timeout

**Rationale:** This fills the gap identified in 1.4. It is lower priority than routing and MT/MX, but would make the system feel production-complete. It can be a Phase 3 item.

### Priority 5: Document Format Strategy Decision (LOW EFFORT, HIGH SIGNAL)

**What to add:**
- An Architecture Decision Record (ADR) in `docs/architecture-decisions/`:
  - **Title**: "ISO 20022 as Primary Message Format — MT/MX Migration Strategy"
  - **Context**: The payment industry is in a multi-year migration from SWIFT FIN (MT) to ISO 20022 (MX). Our system is designed for the post-migration world.
  - **Decision**: ISO 20022 is our primary and default message format. We provide an optional `payment-format-converter` library for MT message ingestion during the transition period.
  - **Consequences**: Cross-border payments from institutions still using MT messages must be converted at the ingestion boundary. Our internal data model is always ISO 20022-native.

**Rationale:** Even without implementing the full format converter, this ADR signals that you are aware of the MT/MX migration challenge. An interviewer at Mastercard Oslo — who lives this migration daily — would notice and appreciate this.

---

## Part 6: Lessons from FinTP's Production Experience

Beyond specific feature gaps, FinTP's 20+ years of production deployment teach broader lessons:

### 6.1 The 80/20 Rule of Payment Processing
FinTP's core value proposition is not in any single feature but in the **unglamorous plumbing**: message parsing, format conversion, routing, error handling, and retry logic. Our system focuses heavily on the "interesting" features (ML fraud detection, event sourcing, VRP) but underinvests in the "boring" features (format conversion, routing, operator workflows) that consume 80% of engineering time in production payment systems.

### 6.2 Configuration Over Code
FinTP's routing engine uses configurable rules rather than hardcoded logic. Our system hardcodes the batch/instant routing decision into the Kafka topic chosen by the originator. Real payment systems evolve their routing rules frequently (new clearing windows, new amount thresholds, new schemes) and need to change routing without code deployments.

### 6.3 Multi-Scheme Coexistence
FinTP simultaneously connects to SWIFT, TARGET2, TIPS, RT1, and national ACH — all with different message formats, protocols, and SLAs. Our system models one clearing abstraction. While our approach is simpler and appropriate for a portfolio project, the awareness that real systems must juggle multiple schemes simultaneously is worth documenting.

### 6.4 The Gap Between "Requirements" and "Production"
FinTP's GitHub repos (14 repos, primarily C++) reveal the sheer volume of infrastructure code needed for a production payment system: transport adapters, database abstraction layers, logging frameworks, event watchers, payload evaluators. Our requirements are clean and well-structured, but the implementation will inevitably surface hundreds of infrastructure concerns not covered in the requirements. This is normal — but worth acknowledging.

---

## Part 7: Summary Assessment

### What we should learn from Allevo:
1. **Message format diversity is reality** — add MT/MX awareness
2. **Routing intelligence matters** — add a routing decision engine
3. **Production systems need operator workflows** — consider investigation queues
4. **Business continuity is expected** — add data retention and crash recovery requirements
5. **Configuration over hardcoding** — make routing rules configurable

### What Allevo should learn from us (if they were comparing):
1. **Modern fraud detection architecture** — our 3-layer pipeline with ML is far ahead of list-based filtering
2. **VoP is now mandatory** — Allevo FinTP predates the EU VoP mandate
3. **SCA exemptions are essential for PSD2** — our exemption engine is a competitive feature
4. **Event sourcing for financial ledgers** — immutability and auditability by design
5. **Cloud-native observability** — Prometheus, Grafana, Docker deployment vs. traditional deployment
6. **Requirements documentation as a deliverable** — our documentation quality exceeds what's publicly visible from FinTP

### Overall verdict:

Our requirements are **architecturally stronger and more forward-looking** than FinTP in the areas of fraud detection, regulatory compliance (VoP, SCA exemptions), and modern software architecture (event sourcing, Kafka Streams, DJL). FinTP is stronger in the **production plumbing**: message format handling, transaction routing, operator workflows, and infrastructure connectivity. The recommended improvements (Priorities 1-5 above) would close the most important gaps without fundamentally changing our architecture. The highest-impact additions are the **MT/MX format converter** and the **routing decision engine** — both of which directly signal domain expertise to the target companies.

---

*End of comparative analysis. This review should be read alongside:*
- *[payment_processing_requirements_review.md](payment_processing_requirements_review.md) — general requirements review*
- *[requirements_review_eu_legislation.md](requirements_review_eu_legislation.md) — EU legislation compliance review*
- *[requirements_review_computational_stability.md](requirements_review_computational_stability.md) — computational stability review*
- *[payment_processing/extensions/allevo_fintp_analysis.md](../payment_processing/extensions/allevo_fintp_analysis.md) — earlier FinTP analysis (reconciliation + duplicate detection)*
