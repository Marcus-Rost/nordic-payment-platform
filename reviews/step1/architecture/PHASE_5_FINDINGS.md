# Phase 5 Findings — Security, Data, Resilience, Duplication

*Categories 6, 7, 8, 15 from REVIEW_CHECKLIST.md*
*Date: 2026-04-12*

---

## Summary

4 checks performed: trust boundaries, crypto-shredding chain, recovery procedures, overlapping functionality.

- **PASS:** 3 (trust boundaries, recovery, duplication)
- **FAIL:** 1 (crypto-shredding chain — serious gap)

---

## Critical Finding

### F5.1 — Broken Crypto-Shredding Chain: A2, A3, A4 Have No Erasure Strategy [CRITICAL]

When an account is deleted in A1 and its PII crypto-shredded:
- **A1:** Correctly crypto-shreds all PII in events (PASS)
- **A5:** No PII stored — PII is transient in memory only (PASS by design)
- **A6:** Anonymizes query logs: IBAN→hash, names→`[REDACTED]` (PASS)
- **A2:** **No mention of GDPR, erasure, or PII in entire design document.** Stores IBANs in `clearing_records` table and settlement reports.
- **A3:** **No mention of GDPR, erasure, or PII.** Stores IBANs, consumer IDs, merchant IDs, and creditor names (via VoP cache) in `payment_request` table.
- **A4:** **No mention of GDPR or erasure.** Stores account holder IDs, creditor names, IBANs in `payments` and `consents` tables.

**Impact:** GDPR right to erasure is incomplete. If a customer requests deletion, A1 shreds their data but A2/A3/A4 still hold their IBANs and potentially names. This is a compliance gap.

**Action for each service:**
- **A2:** Add erasure section. IBANs in clearing records may be retained under AML (5-year obligation), but this must be explicitly documented with legal basis. After retention period, anonymize.
- **A3:** Add erasure section. R2P records containing account holder data must be anonymized on erasure notification from A1. Mandate records referencing deleted accounts must be revoked.
- **A4:** Add erasure section. Consent records for deleted accounts must be revoked and anonymized. Payment records follow AML retention rules.

Each service should consume an `AccountDeletedEvent` from A1 (or receive notification via a dedicated topic/REST call) and trigger its anonymization procedure.

---

## Medium Findings

### F5.2 — A5 Case Management API Uses OAuth2 in Internal Zone [LOW]

A5's Case Management API uses OAuth2 with scopes (`fraud:read`, `fraud:manage`) rather than mTLS. This is inconsistent with the master decision that internal services use mTLS. However, the API serves human fraud analysts (not other services), so user-level authorization via OAuth2 is justified.

**Action:** Add a note to A5's design explicitly reconciling this with the three-zone model: "The Case Management API uses OAuth2 for user-level authorization (analyst access control) despite being in the internal zone. Network policies restrict access to internal traffic only."

### F5.3 — IBAN Validation Duplicated Across Services [LOW]

A3 and A6 both implement IBAN validation independently (mod-97 checksum per ISO 13616). The Platform Starter defines `InvalidIbanException` but doesn't provide a shared validator implementation.

**Action:** Consider adding an `IbanValidator` utility to the `payment-domain-model` shared library. Low priority — the algorithm is trivial and unlikely to diverge.

---

## Positive Findings

- **Trust boundaries:** All 6 services correctly position themselves in the three-zone model
- **Recovery procedures:** All 6 services document restart recovery consistent with master B.1b, each adapted appropriately (event-sourced, state machine, stateless)
- **No significant duplication:** Rate limiting, circuit breakers, and audit logging are appropriately distributed (context-specific in services, shared infrastructure in Starter)
- **A5 PII minimization:** A5's design structurally prevents PII from entering durable stores — an exemplary application of "security by design"
