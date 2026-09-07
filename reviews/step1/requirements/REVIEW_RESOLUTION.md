# Review Resolution Log

*Tracks how each review finding was resolved*
*Last updated: 2026-04-11*

---

## Summary

| Review | Total Issues | Fixed | Covered by New Sections | Deferred/Out of Scope | Needs Discussion |
|--------|-------------|-------|------------------------|----------------------|-----------------|
| Internal Consistency | 27 | 12 | 8 | 4 | 3 |
| Payment Processing Requirements | 24 | 6 | 14 | 2 | 2 |
| Proposed EU Legislation | 58 | 0 | 58 (via Section 2.6 summary) | 0 | 0 |
| Allevo Comparison | 5 gaps | 0 | 2 | 2 | 1 |
| Certification Readiness | 16 | 1 | 3 | 10 | 2 |
| Computational Stability | 23 | 1 | 18 | 2 | 2 |
| Deutsche Bank Comparison | 7 areas | 0 | 2 | 5 | 0 |
| EU Legislation | 23 | 0 | 23 (via Section 2.6) | 0 | 0 |
| ISO 20022 Certification | 31 | 1 | 3 | 25 | 2 |
| Mastercard Comparison | 6 areas | 0 | 2 | 4 | 0 |
| OWASP Security | 42 | 2 | 34 | 3 | 3 |

---

## Fixes Applied

### Internal Consistency (File 1)

| Issue | Resolution |
|-------|-----------|
| CON-1: VEL-02 absolute vs relative | **Fixed**: UC-05 updated to use relative threshold (20x average) matching A5 requirements |
| CON-2: Score weights 0.4 vs 0.3 | **Fixed**: UC-01 scoring example corrected to 0.2/0.2/0.2 (ML enabled weights) |
| CON-3: Sanctions hit — fraud still runs? | **Fixed**: A5 requirements clarified: "sanctions HIT bypasses fraud scoring entirely" |
| CON-4: API endpoint path | **Fixed**: UC-05 standardized on `/api/v1/fraud/alerts/{id}/resolve` |
| CON-5: List update 1 hour vs 5 min | **Fixed**: SAN-F07 clarified as poll frequency; NFR-06 as processing time |
| CON-6: SETTLED vs CONFIRMED state | **Fixed**: A1-F11 standardized on CONFIRMED (matching Avro schema) |
| DANG-1: account.freeze-commands no consumer | **Fixed**: A1-F14 updated to include "Consume account.freeze-commands topic" |
| DANG-3: Missing payment statuses | **Fixed**: PISP-F06 extended with REQUIRES_SCA, REQUIRES_CONFIRMATION, HELD, CANCELLED |
| DANG-4: A2 only publishes SETTLED/REJECTED | **Fixed**: CLR-B09 extended with PROCESSING, HELD + reason codes |
| DANG-6: ledger.events missing from topology | **Fixed**: Added ledger.events, account.freeze-commands, sanctions.list-updates to Section 9.1 |
| TERM-1: APPROVED vs ACCEPTED | **Fixed**: Standardized on ACCEPTED (SEPA R2P) in UC-01 data model |
| AMB-1: Who initiates VoP | **Fixed**: SEC-12 clarified: "A3 or A4 initiates VoP. A2 forwards result to A5" |
| AMB-2: Duplicate check ordering | **Fixed**: A2-F12 clarified: "runs BEFORE balance reservation" |
| AMB-3: VoP for recurring mandates | **Fixed**: A3-F08 added VoP caching rule (cache OK if <90 days, no changes) |
| PRIO-1: VoP timeout Must vs Should | **Fixed**: SEC-12 clarified: "may proceed with VOP_TIMEOUT flag" |
| PRIO-2: Reservation timeout Should → Must | **Fixed**: LED-F14 elevated to Must + durable mechanism requirement. Also RES-01 |
| INT-3: A4↔A1 REST or Kafka | **Fixed**: A4-F10 resolved: "synchronous internal REST calls" |

### New Sections Added (addressing bulk of reviews)

| Section | Addresses |
|---------|----------|
| **Section 2.5: Cryptographic and Key Management** (KM-01 to KM-10) | OWASP CRITICAL-01 (key management), CRITICAL-02 (Kafka signing), CRITICAL-03 (rate limiting), HIGH-02 (JWE spec), HIGH-03 (webhook replay), HIGH-05 (input validation), MED-04 (XML hardening), MED-06 (timezone) |
| **Section 2.6: Regulatory Awareness** (table format) | EU Legislation CRITICAL C-1 (GDPR), C-2 (DORA), C-4 (AML), C-5 (regulatory index), C-7 (settlement finality). All 58 proposed requirements addressed via summary table approach |
| **Section 2.7: Resilience and Crash Recovery** (RES-01 to RES-11) | Computational Stability CRITICAL-01 through CRITICAL-07, HIGH-01, HIGH-08, MEDIUM-04 |
| **DATA-04 update** | ISO 20022 version specified (CBPR+ 2023 edition, ECL 2024) |
| **DATA-07 new** | VoP result propagation in Avro schema (INT-2) |

---

## Items Deferred — Acceptable for Portfolio Scope

These are valid production requirements but would massively expand scope without demonstrating additional technical skills:

| Issue | Why Deferred |
|-------|-------------|
| ISO 20022 field-level specifications (130+ fields per message, 50+ validation rules, conformance statements) | Implementation-level detail, not requirements. Will be documented in `docs/iso20022/` during implementation. DATA-04 now specifies CBPR+ 2023 edition as the target |
| MT/MX multi-format support | MT is being retired (SWIFT Nov 2025). We deliberately chose ISO 20022 only. Documented as ADR: "MT format support via `payment-format-converter` library if needed" |
| Intelligent routing engine | Our dual-mode (batch/instant) is the routing. Adding configurable routing rules is a future extension |
| Full GDPR organizational procedures (DPO, DPIA process, data subject access request workflow) | Organizational, not technical. Section 2.6 references GDPR and documents our technical implementations |
| Full DORA ICT risk framework | Organizational. Section 2.6 references DORA and maps our technical controls |
| Full AML CDD/EDD procedures | Onboarding concern, not transaction processing. Section 2.6 clarifies boundary |
| Common Criteria evaluation | EUR 100K-500K+, 6-18 months. Not applicable for portfolio |
| Multi-datacenter deployment | Production concern. RES-07/RES-08 specify durability requirements. docker-compose is single-node for demo |
| Graph-based network analysis for AML | Would require Neo4j. Documented as future extension |
| PEP screening, adverse media screening | KYC/onboarding concerns, not transaction monitoring |
| Automated SAR/STR filing | Regulatory filing process, not payment processing |
| ISO 20022 test data sets (100+ scenarios) | Will be developed during implementation, not in requirements |

---

## Items for Discussion

These are cases where I proceeded with my recommendation but there's room for a different decision:

### Discussion 1: GDPR Right to Erasure vs. Immutable Event Log

**The tension:** GDPR Art. 17 gives data subjects the right to erasure. But our event-sourced ledger (A1) stores immutable events — by design, events cannot be deleted.

**My resolution (in Section 2.6):** Crypto-shredding — encrypt PII fields with a per-account encryption key. To "erase" a person's data, delete the key. The events remain but the PII is irrecoverable.

**Why this might need discussion:** Crypto-shredding is accepted practice but adds complexity (per-account keys, key management for erasure keys, performance impact of per-field encryption/decryption).

**Alternative:** Accept that payment data has a 5-year legal retention requirement (AML), which overrides the right to erasure during that period. After 5 years, compact/delete old events.

### Discussion 2: AI Act — Fraud Model Assessment

**The issue:** The EU AI Act (Regulation 2024/1689) classifies AI systems used in creditworthiness assessment as "high-risk" (Annex III). Our fraud ML model scores transactions — is it "high-risk" under the AI Act?

**My resolution:** Not explicitly addressed. The fraud model scores *transactions* (not people), and the output is a risk score that humans review (case management). This likely falls under "limited risk" not "high-risk" because it's fraud prevention (security), not creditworthiness.

**Why this might need discussion:** If a regulator decides payment fraud scoring IS high-risk AI, we'd need: bias monitoring, human oversight documentation, risk assessment, CE marking. Unlikely for a portfolio project, but worth being aware of.

### Discussion 3: SCT Inst Timing — 10 Seconds vs. 5 Seconds — **RESOLVED**

**Decision:** Updated all documents to EPC 2025 SCT Inst rulebook v1.1 timing.

**Changes made:** ~35 edits across: system_requirements.md (A2-F20, A2-F24, A2-F25, A2-NF02), UC-01, UC-03, UC-04, UC-08 (title, time budget, AF-06, monitoring thresholds), sub-project #11 (CLR-I02, CLR-I10, CLR-NF02, CLR-S06, AC-04, AC-07, use case scenarios, gap analysis), NAVIGATION.md, SUBSYSTEM_MAP.md, CERTIFICATIONS.md (marked as DONE), extensions/instant_payments.md (IP-F01, IP-F06, IP-F07, IP-NF01, IP-S02, IP-S03, time budget, timeout flow).

New timing: execution target 5s, hard limit 7s, confirmation 9s. Our happy path (~2-4 seconds) is well within target.

### Discussion 4: Transactional Outbox vs. Kafka-as-Source-of-Truth

**The issue (RES-03):** We specified either Transactional Outbox or Kafka-as-source-of-truth to solve the dual-write problem. Both are valid. We haven't chosen which.

**My resolution:** Left as "either/or" in the requirement. The choice depends on implementation trade-offs:
- **Outbox**: simpler (write to PostgreSQL outbox table + relay to Kafka). Risk: outbox relay lag.
- **Kafka-first**: more elegant (Kafka is the sole source of truth, PostgreSQL derived from consumers). Risk: query latency during projection rebuild.

**Why this might need discussion:** The architectural choice affects all 6 sub-systems. Should we commit to one approach in the requirements, or leave it as an implementation decision?

### Discussion 5: PII Minimization in Fraud Scoring — **RESOLVED**

**Decision:** A5 scoring pipeline uses anonymized account IDs only. Case management API enriches with names from A1 at display time.

**Changes made:** Added A5-F12 to master system_requirements.md and SEC-10 to sub-project #1 requirements. Scoring uses anonymized account IDs + patterns. When analyst opens an alert in case management, A5 queries A1 for the account holder name at that moment. Clean separation: GDPR data minimization (Art. 5(1)(c)) for automated processing, enrichment for human review.
