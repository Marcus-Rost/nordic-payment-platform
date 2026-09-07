# Cross-Project Architecture Review — Summary of Findings

*Date: 2026-04-12*
*Scope: 76 documents, ~25,000 lines across master project + 7 subprojects*
*Phases: 6 review phases completed*
*Detailed findings: PHASE_1_FINDINGS.md through PHASE_6_FINDINGS.md*

---

## Overall Assessment

The architecture is **fundamentally sound**. The master decisions are well-propagated, the payment flow is traceable end-to-end, sanctions screening cannot be bypassed, and recovery procedures are consistently documented. The issues found are primarily at the **interface boundaries** between services (where each subproject was designed in a separate session) and in **documentation gaps** (features inherited from the Platform Starter but not explicitly acknowledged).

No finding requires rethinking the architecture. All findings are fixable with documentation updates, parameter corrections, or small design additions.

---

## Findings by Severity

### CRITICAL (3) — Would cause integration failures if implemented as documented

| ID | Finding | Phase | Services | Fix |
|----|---------|-------|----------|-----|
| **F3.1** | Reservation partition key conflict: A2 uses `transactionId`, A1 expects `accountId` | 3 | A2, A1 | Resolve key strategy: recommend `accountId` as Kafka key (A1 serialization) + `transactionId` in payload (A2 correlation) |
| **F3.2** | A3 calls A6 VoP with wrong method (GET) and wrong path (`/api/v1/vop/verify` vs `/internal/vop/verify`) | 3 | A3, A6 | Fix A3 to use `POST /internal/vop/verify` |
| **F1.1** | A2 missing ARCHITECTURE_CHALLENGES.md and ARCHITECTURE_DECISIONS.md entirely | 1 | A2 | Create both documents, extracting decisions from existing DESIGN |

### HIGH (6) — Significant issues that need resolution before implementation

| ID | Finding | Phase | Services | Fix |
|----|---------|-------|----------|-----|
| **F5.1** | Crypto-shredding chain broken: A2, A3, A4 have no GDPR erasure strategy | 5 | A2, A3, A4 | Add erasure section to each, with AML retention justification |
| **F3.3** | A6→A1 REST path prefix mismatch: `/internal/accounts/` vs `/api/v1/accounts/` | 3 | A6, A1 | Standardize internal REST path convention |
| **F3.4** | A2 has no branch for A5's `decision: TIMEOUT` response | 3 | A2, A5 | Add TIMEOUT handling to A2's FraudResponseProcessor |
| **F6.1** | A3/A4 don't handle `RETURNED` payment status from A2 | 6 | A3, A4, A2 | Add RETURNED/RETURN_INITIATED status handling |
| **F4.1** | A4 VoP timeout (3000ms) is 6x A3's (500ms), blows Phase 1 budget | 4 | A4 | Reduce A4 VoP timeout to 500ms |
| **F1.2** | Systematic filename typo: `ARCHITECTUR_DESIGN.md` in A1, A3, A5, + master | 1 | A1, A3, A5, master | Rename to consistent convention |

### MEDIUM (11)

| ID | Finding | Phase | Fix |
|----|---------|-------|-----|
| **F1.3** | 4 Kafka topics used but not in master registry | 1 | Add to NAVIGATION.md |
| **F2.1** | A4 doesn't document RFC 9457 compliance | 2 | Add section to A4 design |
| **F2.2** | A4 has most IMPLICIT master decision compliance | 2 | Add Platform Starter integration section |
| **F2.3** | A5 missing RFC 9457 and hash chain documentation | 2 | Add explicit references |
| **F4.3** | Master UC08 AF-05 ambiguous wording (partial vs complete timeout) | 4 | Clarify in master UC08 |
| **F4.4** | Two different time budget breakdowns (master vs A2) | 4 | Reconcile to one canonical budget |
| **F6.2** | A3 doesn't handle `HELD` status from A2 batch path | 6 | Add to A3 state machine |
| **F6.3** | Port 9092 conflict: A2 management vs Kafka broker | 6 | Change A2 management port |
| **F6.4** | A5 has no Pact contract test references | 6 | Add provider verification |
| **F6.5** | A6↔A1 and A3→A6 REST contracts not Pact-tested | 6 | Add Pact contracts |
| **F4.5** | A5 partial-response mechanism not explicitly documented | 4 | Add to A5 design |

### LOW (9)

| ID | Finding | Phase | Fix |
|----|---------|-------|-----|
| **F1.4** | Missing dates on A4 and Starter documents | 1 | Add dates |
| **F1.5** | Missing version numbers on some design docs | 1 | Add "Version 1.0" |
| **F1.6** | A2 design contains stale self-referencing note | 1 | Remove |
| **F2.4** | A6 missing audit hash chain documentation | 2 | Add note |
| **F2.5** | A2 config architecture not explicitly documented | 2 | Add section |
| **F2.6** | Kafka error category terminology mismatch | 2 | Align terminology |
| **F5.2** | A5 Case Management OAuth2 vs mTLS not reconciled | 5 | Add reconciliation note |
| **F6.6** | Inconsistent metric namespace prefixes | 6 | Consider common root |
| **F6.7** | A5 metric table missing type column | 6 | Add column |

---

## Statistics

| Severity | Count |
|----------|-------|
| Critical | 3 |
| High | 6 |
| Medium | 11 |
| Low | 9 |
| **Total** | **29** |

| Category | Findings |
|----------|----------|
| Interface mismatches (Phase 3) | 7 |
| Documentation gaps (Phase 2) | 6 |
| Structural issues (Phase 1) | 6 |
| Timing/flow issues (Phase 4) | 5 |
| Security/data issues (Phase 5) | 3 |
| Operational issues (Phase 6) | 8 |

| Affected Service | Findings |
|-----------------|----------|
| A2 (Clearing) | 12 (most affected — missing docs + central role) |
| A4 (PSD2) | 7 |
| A3 (R2P) | 6 |
| A5 (Fraud) | 5 |
| A1 (Ledger) | 3 |
| A6 (VoP) | 3 |
| Master | 4 |
| Starter | 1 |

---

## Positive Findings (What Works Well)

1. **Sanctions screening cannot be bypassed** — verified across all payment paths (instant, batch, VRP, mandate auto-accept). No regulatory gaps.
2. **Master decisions are well-propagated** — 48/70 checks passed explicitly, 13 are implicit (correct at runtime, need documentation).
3. **Internal document consistency is strong** — all 12 spot-checked decision-to-design pairs were consistent.
4. **Recovery procedures are correctly adapted** per service — event-sourced (A1), state machine (A2), stateless (A6).
5. **A5's PII minimization is exemplary** — PII is structurally prevented from entering durable stores.
6. **Dual-write strategies are correctly implemented** per the master assignment (Kafka-first, Outbox, N/A).
7. **Time budget arithmetic is safe** — 2,800ms worst case vs 5,000ms target gives 2,200ms margin.

---

## Recommended Fix Order

1. **Critical F3.1** — Reservation partition key (blocks A2↔A1 integration design)
2. **Critical F3.2** — A3 VoP endpoint (blocks A3↔A6 integration)
3. **High F5.1** — Crypto-shredding chain (compliance requirement)
4. **High F3.3 + F3.4** — A6→A1 path + A2 TIMEOUT handling (interface correctness)
5. **High F6.1** — RETURNED status handling (data consistency)
6. **High F4.1** — A4 VoP timeout (timing correctness)
7. **Critical F1.1** — A2 missing documents (documentation completeness)
8. **High F1.2** — Filename standardization (structural hygiene)
9. **Medium findings** — in order of severity
10. **Low findings** — batch cleanup
