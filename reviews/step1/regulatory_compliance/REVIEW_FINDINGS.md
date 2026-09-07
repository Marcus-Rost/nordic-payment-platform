# Regulatory Compliance Review — Findings

*Date: 2026-04-12*
*Checklist: REGULATORY_COMPLIANCE_CHECKLIST.md*

---

## Summary

30 checks performed. 19 PASS, 7 PARTIAL, 2 FAIL, 2 not applicable (organizational scope).

**Most significant outcome:** The EU IPR Article 5d sanctions screening concern (flagged as potentially critical) is **NOT a gap**. A5 already implements a dual screening model — daily customer-level screening (Mode 1, Article 5d compliant) + per-transaction beneficiary screening (Mode 2, legitimate AML control). The architecture is correctly designed.

---

## FAIL Findings

### RRC1 — PSD2 Passive AISP 4-per-24h Rate Limit (RC2.7) [HIGH]

PSD2 RTS Article 36(5)(b) limits passive AISP access (without PSU actively requesting) to 4 times per 24 hours per account. A4's architecture has a general 100 req/min per-TPP rate limit but no per-PSU, per-AISP counter for passive access. This is a compliance gap.

**Action:** Add a per-PSU per-AISP passive access counter to A4's design. Counter tracks access requests not initiated by the PSU. After 4 passive accesses in 24 hours, return 429 with "access frequency exceeded" per PSD2 RTS.

### RRC2 — DORA Reporting Timeline Not Mapped (RC5.3) [MEDIUM]

No mapping from incident severity to DORA reporting deadlines: initial notification within 4 hours, intermediate report within 72 hours, final report within 1 month. Current P1-P4 classification has internal response times but not regulatory reporting timelines.

**Action:** Add DORA reporting timeline to the incident classification table in the master architecture.

---

## PARTIAL Findings (requiring documentation)

| ID | Finding | Action |
|----|---------|--------|
| RC1.4 | Norwegian EEA transposition timeline not documented | Add note to master requirements about Norwegian IPR timeline |
| RC2.5 | Consent 90-day expiry compliant but 180-day option not discussed | Document that 90 days is the conservative/compliant choice |
| RC3.3 | Anti-tipping-off controls not documented in Case Management API | Add note to A5 design |
| RC4.5 | Retention schedule automation not specified | Document automated retention expiry monitoring as implementation requirement |
| RC5.1-RC5.5 | DORA organizational requirements documented as out of scope | Acceptable for portfolio — document as production requirements |
| RC7.3 | SRTP compliance not fully verified | Acceptable for portfolio — A3 evolved toward SRTP |

---

## Key Positive Outcomes

- **Article 5d sanctions screening:** Architecture is compliant. Dual model correctly separates customer screening (daily, Article 5d) from beneficiary screening (per-transaction, AML obligation)
- **PSD2 SCA:** Complete — all 4 exemption types, dynamic linking immutable, durable lockout, cumulative counters with pessimistic locking
- **GDPR crypto-shredding cascade:** Fully documented across all 6 services
- **AML record keeping:** 5-year retention across all services with legal basis documented
- **EPC SCT Inst 2025 v1.1:** Timing correctly implemented (5/7/9 seconds)
- **VoP scheme:** Both roles, EPC vocabulary, free of charge
