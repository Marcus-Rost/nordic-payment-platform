# Project #11: Clearing & Settlement Engine — Gap Analysis

*Comparison against TIPS, RT1, NICS, P27, UK Faster Payments*

---

## 1. Comparison with Real Infrastructure

### Mastercard Oslo / NICS (Primary Reference)

| Feature | NICS (Real) | Our System | Gap? |
|---------|------------|-----------|------|
| Batch clearing with netting | Yes | Yes (CLR-B04) | No |
| Real-time/instant clearing | Yes (via TIPS) | Yes (CLR-I01-I12) | No |
| ISO 20022 | Migrated/migrating | Yes (pacs.008/002/004, camt.053) | No |
| Multi-currency | Through P27 (SEK, DKK, NOK, EUR) | Via A1 multi-currency ledger | No |
| 24/7 instant availability | Yes | Yes (CLR-I03) | No |
| Settlement in central bank money | Yes (Norges Bank) | Simplified (pre-funded accounts) | **Simplified** — we model settlement accounts but not central bank money |
| Participant management | Full PSP onboarding | Simplified (pre-configured PSPs) | Acceptable for portfolio |

### TIPS (ECB)

| Feature | TIPS | Our System | Gap? |
|---------|------|-----------|------|
| Gross settlement per transaction | Yes | Yes (instant path) | No |
| Central bank money settlement | Yes (DCAs at ECB) | Pre-funded accounts (commercial money) | **Different** — we don't model central bank money. Acceptable |
| 24/7/365 | Yes | Yes | No |
| EPC 2025 SLA (5s/7s/9s) | Yes (target < 5s for TIPS leg) | Yes (execution < 5s, hard limit < 7s, confirmation < 9s) | No |
| Liquidity management | DCA funding, threshold management | Simplified liquidity tracking | **Simplified** — same concept, less granular |
| Multi-CSM interoperability | Yes (RT1 bridge) | Single clearing engine | Acceptable for portfolio |

### RT1 (EBA Clearing)

| Feature | RT1 | Our System | Gap? |
|---------|-----|-----------|------|
| Commercial bank money settlement | Yes | Yes (our model) | No — our pre-funded accounts are closer to RT1 than TIPS |
| Pre-funded settlement | Yes (via TARGET) | Yes | No |
| 24/7/365 | Yes | Yes | No |
| Reachability through TIPS | Now required (ECB mandate) | N/A (single system) | N/A |

### P27 Nordic Payments Platform

| Feature | P27 | Our System | Gap? |
|---------|-----|-----------|------|
| Multi-currency (SEK, DKK, NOK, EUR) | Yes | Yes (via A1) | No |
| Batch + instant on one platform | Yes | Yes (dual-mode A2) | No |
| Cross-border domestic-like payments | Yes (Sweden→Denmark like domestic) | Not modeled (single-country focus) | **Gap** but acceptable |
| ISO 20022 native | Yes | Yes | No |

---

## 2. "Exotic" Features Assessment

### Feature: Dual-mode (Spring Batch + Kafka Streams) in One Service

**Is this unusual?** Yes — most systems are either batch OR real-time. Combining both in one service is architecturally interesting.
**Justification:** Mastercard Oslo's NICS handles both batch (end-of-day settlement) and instant (TIPS-integrated). Tietoevry's core banking modernization also supports both modes. This dual-mode approach is production reality.
**Risk:** Code complexity. The batch path (Spring Batch) and instant path (Kafka Streams) share configuration but are fundamentally different processing models.
**Verdict:** **Keep. Mirrors real infrastructure. The architectural trade-off discussion (when to use batch vs. instant) is great interview material.**

### Feature: Multilateral Netting Algorithm

**Is this unusual for a portfolio project?** Very unusual. Most portfolio projects show simple A→B transfers.
**Is it realistic?** Yes — this is exactly what NICS does (and every clearing house in the world). Netting is the entire point of batch clearing: reduce N×(N-1) gross obligations to N net positions.
**Verdict:** **Keep. This is a core differentiator that shows domain knowledge.**

### Feature: ISO 20022 JAXB Bindings

**Is this unusual?** Using JAXB to generate Java classes from ISO 20022 XSD schemas is standard practice in the payment industry but extremely rare in portfolio projects.
**Justification:** Mastercard Oslo works with ISO 20022 daily. DNB, SpareBank 1, Tietoevry are all migrating to ISO 20022. SWIFT mandated ISO 20022 for cross-border payments. This is THE global payment messaging standard.
**Verdict:** **Keep. ISO 20022 familiarity is a powerful domain signal.**

### Feature: 7-Second Hard Timeout with Mandatory Reject (EPC 2025)

**Is this unusual?** The hard timeout (reject on breach, no limbo state) is good engineering practice but not commonly implemented in portfolio projects.
**Justification:** The EPC 2025 SCT Inst rulebook v1.1 mandates: execution in 5 seconds, hard limit at 7 seconds, confirmation by 9 seconds. If you can't complete within the hard limit, the payment must be rejected — keeping it in an unknown state would violate the regulation and create reconciliation problems.
**Verdict:** **Keep. Demonstrates understanding of regulatory constraints on system design.**

---

## 3. Features Others Have That We Don't

| Feature | Who Has It | Priority | Recommendation |
|---------|-----------|----------|----------------|
| **Central bank money settlement** | TIPS | Low | Abstract as SettlementAsset enum (COMMERCIAL_BANK, CENTRAL_BANK, CBDC). Document in ADR. Don't implement CBDC |
| **Cross-border FX clearing** | P27 | Low | Would need FX rate service, conversion at clearing time. Out of scope. Document as extension |
| **Recall/return mechanism** | SEPA (pacs.004/camt.056) | Medium | We have pacs.004 support (CLR-I12) as Should. Valuable for production realism |
| **Settlement finality confirmation** | TIPS | Medium | Explicit "this settlement is legally final and irrevocable" event. We have SETTLED status but no separate finality event |
| **Clearing house risk management** | All CSMs | Low | Stress testing, default fund management, loss allocation. Too complex for portfolio |
| **Multi-CSM routing** | EU infrastructure | Low | Route to TIPS vs. RT1 based on participant preference. Single-system scope |
| **Standing order processing** | FPS (UK) | Low | Scheduled recurring payments at clearing level. Could be modeled but low priority |

---

## 4. Summary

| Category | Assessment |
|----------|-----------|
| **EU regulatory compliance** | Strong. EPC 2025 SLA (5s/7s/9s), 24/7/365, sanctions-before-settlement, ISO 20022 CBPR+ 2023 |
| **Target company alignment** | Excellent. Mirrors Mastercard NICS (batch + instant), DNB (Kafka), Tietoevry (Spring Batch + event-driven) |
| **Real infrastructure comparison** | Realistic simplification of TIPS/RT1/NICS. Pre-funded accounts model RT1 pattern |
| **Exotic features** | All justified. Dual-mode, netting, ISO 20022 JAXB, hard timeout — each demonstrates specific domain knowledge or engineering discipline |
| **Key gap** | Settlement finality as a distinct concept (not just SETTLED status but a legally-final event). Small enhancement |
