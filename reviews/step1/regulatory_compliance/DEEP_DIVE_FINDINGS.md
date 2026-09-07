# Regulatory Compliance Deep Dive — Additional Findings

*Date: 2026-04-12*
*Scope: Detailed EU + Norwegian legislation review beyond initial Review 5*

---

## Assessment: What Needs Discussion vs Routine Fix

### NEEDS DISCUSSION (3 items — affect architecture)

**1. EUR vs NOK Differentiation [MUST]**
The EU IPR applies ONLY to EUR-denominated transfers. NOK transfers operate under Norwegian national rules (NICS, Norges Bank). Our architecture treats all currencies uniformly under IPR obligations. We need to decide: do we architecturally separate EUR and NOK processing paths, or do we apply the stricter standard (IPR) to all currencies?

**My recommendation:** Apply the stricter standard (IPR rules) to all currencies. This is simpler architecturally (one processing path) and future-proofs for when Norway incorporates IPR for NOK. Document that NOK is not legally required to follow IPR but we choose to for consistency. This avoids dual processing paths.

**2. Dual Sanctions Screening Mode — IPR Article 5d Clarification [MUST]**
Our initial review (Review 5) found A5 already has the dual model (daily customer screening + per-transaction beneficiary screening). The deep dive confirms this is correct but adds a nuance: for the instant path, originator screening should use the `blockedAccounts` fast-path only (not the full fuzzy pipeline). We should document that the originator on the instant path is checked against the daily-updated blockedAccounts set, not re-screened through the full sanctions pipeline.

**My recommendation:** This is actually already how A5 works (fast-path check first). We just need to document it more explicitly as the Article 5d compliance mechanism.

**3. Automated Refund for Unauthorized Transactions — PSD2 Art 73 [MUST]**
PSD2 requires PSPs to refund unauthorized transactions by end of next business day. Our architecture has no refund/reversal mechanism for unauthorized transactions. This is a new payment operation type (similar to but distinct from the late-sanctions pacs.004 return).

**My recommendation:** Document this as a requirement for A1 and A2. The mechanism is similar to the existing pacs.004 return flow. Add it to the requirements, not as an architectural redesign — the existing compound event model (debit+credit) and A2's return flow can handle it.

### ROUTINE FIXES (can be done without discussion)

All other findings are documentation additions — adding requirements, notes, or guideline entries. No architectural changes needed.

---

## All Findings

### EU Legislation — New Gaps

| # | Gap | Regulation | Fix Type |
|---|-----|-----------|----------|
| 1 | IBAN discrimination prohibition | SEPA Reg Art 9 | Add requirement: no differentiation by IBAN country for EUR |
| 2 | EUR-only IPR scope vs NOK | IPR | **DISCUSSION** — document approach |
| 3 | Sanctions rejection rate reporting | EBA ITS | Add monitoring requirement |
| 4 | SCA required for AISP access | PSD2 Art 97(1)(a) | Add to A4 requirements |
| 5 | SCA dynamic linking must encode amount+payee | PSD2 RTS Art 5 | Document in A4 — already implemented (immutable dynamic_link_data) |
| 6 | TRA exemption annual audit obligation | PSD2 RTS Art 18-20 | Add requirement note |
| 7 | Authentication evidence retention (burden of proof) | PSD2 Art 72 | Add to audit requirements |
| 8 | Automated refund within 1 business day | PSD2 Art 73 | **DISCUSSION** — new operation type |
| 9 | EUR 50 payer liability cap | PSD2 Art 74 | Add requirement for dispute resolution |
| 10 | Non-discriminatory TPP API access | PSD2 Art 35-36 | Add requirement note |
| 11 | DPO designation mandatory | GDPR Art 37 | Add organizational requirement |
| 12 | Legitimate Interest Assessment for fraud detection | GDPR Recital 47 | Add documentation requirement |
| 13 | EEA data residency | GDPR Arts 44-49 | Add infrastructure requirement |
| 14 | FTR field preservation through Kafka pipeline | FTR 2023/1113 Art 7 | Add to INTERFACE_CONTRACTS |
| 15 | Payer identity verification for >EUR 1,000 | FTR Art 5 | Add verification step requirement |
| 16 | AI Act: fraud score not for creditworthiness | AI Act Annex III | Add boundary requirement to A5 |
| 17 | SCA abstraction for future EUDIW | eIDAS 2.0 | Add architecture note to A4 |
| 18 | TIPS interface as future integration | Norway/TIPS | Add note to A2 |
| 19 | ROPA per service | GDPR Art 30 | Add documentation requirement |
| 20 | DORA supersedes PSD2 incident reporting | EBA/DORA | Add clarification note |

### Norwegian Legislation — Specific Requirements

| # | Requirement | Source | Fix Type |
|---|------------|--------|----------|
| N1 | BankID integration for SCA | Market standard | Add architecture note to A4 |
| N2 | Norwegian-specific sanctions lists | Hvitvaskingsloven | Add to A5 requirements |
| N3 | STR reporting to Økokrim/EFE via Altinn | Hvitvaskingsloven §26 | Add as future integration |
| N4 | 5-day waiting period after STR filing | Hvitvaskingsloven | Add workflow requirement |
| N5 | KID number (payment reference) system | Bits AS standard | Add to interoperability requirements |
| N6 | NICS Real for NOK instant payments | Betalingssystemloven | Add as NOK clearing path |
| N7 | KAR register for account routing | Bits AS | Add as integration point |
| N8 | Fødselsnummer processing rules | Personopplysningsloven §9-11 | Add to GDPR section |
| N9 | Bits AS standards (AvtaleGiro, eFaktura) | Bits AS | Already partially addressed in A3 |
| N10 | Finansklagenemnda dispute resolution | Finansavtaleloven | Add organizational requirement |
| N11 | NOK øre rounding (electronic vs cash) | Norwegian practice | Add to currency handling |
| N12 | Norwegian-language customer communications | Finansavtaleloven | Add requirement note |
| N13 | Brønnøysund beneficial ownership register | Hvitvaskingsloven | Add as integration reference |
