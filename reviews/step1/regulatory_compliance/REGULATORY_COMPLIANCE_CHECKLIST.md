# Regulatory Compliance Review Checklist

*Purpose: Map each regulatory requirement to its architectural implementation. Identify gaps.*
*Date: 2026-04-12*
*Regulations: EU IPR 2024/886, PSD2+RTS, AML 5th Directive, GDPR, DORA, Norwegian law, EPC Scheme Rulebooks*

---

## Category RC1: EU Instant Payment Regulation (2024/886)

### RC1.1 — Article 5a: Instant Payment Availability
Verify: if the system offers standard credit transfers, it also offers instant credit transfers. 24/7/365 availability. Non-Eurozone deadline: Jan 2027 (receive), Jul 2027 (send).

### RC1.2 — Article 5b: Charges
Verify: instant transfer charges do not exceed non-instant charges.

### RC1.3 — Article 5c: Verification of Payee
Verify A6 implements: (a) requester role — query payee PSP before every credit transfer, (b) responder role — verify IBAN/name and return Match/No Match/Close Match/Not Possible, (c) free of charge, (d) applies to ALL credit transfers (not just instant).

### RC1.4 — Article 5d: Sanctions Screening [CRITICAL]
**The regulation requires CUSTOMER-LEVEL screening (daily + immediately on new designations), NOT per-transaction screening for instant payments.** Article 5d(2) explicitly prohibits holding up instant payments for transaction-level sanctions checks.

Verify: does our architecture comply? Our A5 currently performs per-transaction screening. This may need architectural adjustment.

**Note:** The intent is that PSPs screen their customer base against sanctions lists proactively (daily), so that when an instant payment arrives, the sending/receiving accounts have already been verified. Transaction-level screening delays instant payments, which the regulation aims to prevent.

### RC1.5 — Norwegian IPR Timeline
Verify: architecture documents reference the correct timelines for Norway (EEA, not Eurozone). IPR not yet incorporated into EEA Agreement. Expected 2026-2027.

## Category RC2: PSD2 / RTS on SCA

### RC2.1 — SCA: Two of Three Elements
Verify A4 implements SCA with two independent elements from different categories (knowledge, possession, inherence).

### RC2.2 — SCA: Dynamic Linking
Verify: for remote payments, authentication is linked to specific amount and payee. Amount immutable once SCA session created.

### RC2.3 — SCA Exemptions (All Four Types)
Verify A4 implements: (a) low-value (≤EUR 30, cumulative ≤EUR 100 or 5 txns), (b) trusted beneficiary (SCA to add, exempt after), (c) recurring (SCA for first, exempt for subsequent identical), (d) TRA (tiered by fraud rate).

### RC2.4 — SCA Cumulative Counters
Verify: low-value cumulative counters (EUR 100 / 5 transactions) correctly reset after SCA. Counters protected against manipulation.

### RC2.5 — Consent Management
Verify A4: consent explicitly states what data, how long, with whom. AISP consent valid up to 180 days. Revocation immediate. Re-access requires fresh SCA after expiry.

### RC2.6 — AISP Access Restrictions
Verify A4: AISP cannot request sensitive payment credentials, cannot use data for purposes other than agreed, access limited to designated accounts.

### RC2.7 — PISP Restrictions
Verify A4: PISP cannot modify amount/payee/elements, cannot request data beyond what's needed for payment initiation.

### RC2.8 — Access Rate Limits
Verify: AISP requests without PSU active involvement limited to 4 per 24 hours per account.

### RC2.9 — Fallback Access
Verify: if the dedicated API is unavailable, fallback access mechanism exists (documented even if not implemented for portfolio).

## Category RC3: AML / Sanctions

### RC3.1 — Sanctions Lists
Verify A5: screens against EU Consolidated List. Architecture should support additional lists (UN, national).

### RC3.2 — Screening Frequency
Verify: customer base screened at least daily + immediately on new designations. Per IPR Article 5d, this is customer-level, not transaction-level for instant payments.

### RC3.3 — Match Handling
Verify A5: on sanctions match, assets/funds frozen immediately. No tip-off to customer. STR filed.

### RC3.4 — Record Keeping (5 Years)
Verify: all services retain transaction records for minimum 5 years. Records sufficient to reconstruct individual transactions.

### RC3.5 — Transaction Monitoring
Verify A5: detects patterns (structuring, rapid movement, unusual geography, round amounts, volume spikes).

## Category RC4: GDPR

### RC4.1 — Lawful Basis Documented
Verify: lawful basis for processing documented per data category (contract performance for payment data, legal obligation for AML records).

### RC4.2 — Right to Erasure vs AML Retention
Verify: erasure requests declined during 5-year AML retention period (GDPR Art 17(3)(b)). After retention: data deleted or anonymized. Documented clearly.

### RC4.3 — Data Protection by Design (Art 25)
Verify: data minimization from design phase, pseudonymization where feasible, privacy-protective defaults.

### RC4.4 — Crypto-Shredding Implementation
Verify A1: per-account encryption keys, PII encrypted at event creation, key deletion makes PII irrecoverable. Cascade to all services (A2, A3, A4, A6).

### RC4.5 — Data Retention Schedule
Verify: retention period defined per data category. Automated flagging of data eligible for deletion.

## Category RC5: DORA

### RC5.1 — ICT Risk Management Framework (Art 5-16)
Verify: ICT asset inventory, risk identification, protection measures, detection mechanisms, response/recovery plans documented.

### RC5.2 — Incident Classification (Art 18)
Verify: incident severity taxonomy defined using DORA's 7 criteria. Major incident = meets thresholds for 2+ criteria.

### RC5.3 — Incident Reporting Timeline (Art 19)
Verify: architecture supports producing initial notification within 4 hours, intermediate report within 72 hours, final report within 1 month.

### RC5.4 — Annual Resilience Testing (Art 24-25)
Verify: testing programme includes vulnerability assessments, penetration testing, scenario-based testing, performance testing.

### RC5.5 — Third-Party ICT Risk (Art 28-44)
Verify: critical third-party dependencies identified (Kafka, PostgreSQL, cloud). Contractual requirements documented. Exit strategies exist.

### RC5.6 — Open Source Risk Management
Verify: open-source components (Kafka, PostgreSQL, Spring Boot) monitored for vulnerabilities, end-of-life status tracked, SCA/SBOM in place.

## Category RC6: Norwegian Legislation

### RC6.1 — Betalingssystemloven Compliance
Verify: architecture is compatible with Norwegian Payment Systems Act requirements.

### RC6.2 — Finansavtaleloven (PSD2 Transposition)
Verify: A4's PSD2 implementation aligns with Norwegian transposition (finansavtaleloven).

### RC6.3 — Hvitvaskingsloven (AML Act)
Verify: A5's AML features comply with Norwegian AML Act (implementing EU AML directives).

### RC6.4 — Norwegian Sanctions Regime
Verify: A5 supports Norwegian-specific sanctions lists alongside EU lists.

### RC6.5 — DORA Norwegian Implementation
Verify: architecture references the Norwegian DORA Act (enacted 1 July 2025). Finanstilsynet as competent authority for incident reporting.

## Category RC7: EPC Scheme Rulebooks

### RC7.1 — SCT Inst 2025 v1.1 Timing
Verify: 5-second execution target, 7-second hard limit, 9-second confirmation. Architecture documents reference correct v1.1 timing.

### RC7.2 — VoP Scheme (EPC218-23)
Verify A6: implements both requester and responder roles per scheme. Matching follows EPC288-23 recommendations.

### RC7.3 — SRTP v4.0
Verify A3: implements full R2P lifecycle per SRTP rulebook.

### RC7.4 — Structured Address Requirement
Verify: unstructured address format support deprecated. Structured address required from 15 November 2026.
