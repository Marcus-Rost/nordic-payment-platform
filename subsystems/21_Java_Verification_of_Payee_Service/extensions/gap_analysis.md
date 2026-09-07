# Project #21: Verification of Payee — Gap Analysis

*Comparison against SurePay (NL), UK CoP, EU VoP scheme, and target company approaches*

---

## 1. Comparison with Existing Implementations

### SurePay (Netherlands) — Operational since 2017

SurePay is the world's first nationwide IBAN-name check service. All Dutch banks participate.

| Feature | SurePay | Our System | Gap? |
|---------|---------|-----------|------|
| Name-IBAN verification | Yes | Yes | No |
| Fuzzy matching | Yes (proprietary algorithms) | Yes (Jaro-Winkler + Soundex) | No |
| Match/Close-Match/No-Match responses | Yes | Yes | No |
| Central routing service | Yes (SurePay acts as hub) | Simulated as direct lookup against A1 | Different architecture but same function |
| Multi-bank participation | All Dutch banks | Single-system (portfolio scope) | Expected — we're not building a national scheme |
| Business name matching | Yes (KvK/Chamber of Commerce integration) | Abbreviation expansion (AS, GmbH, Ltd) | **Partial** — we don't integrate with business registries |
| Cross-border verification | Limited | Not supported | **Gap** — but EU VoP scheme will standardize this |
| False positive rate | ~2-5% for CLOSE_MATCH | Unknown (depends on threshold tuning) | Need to test with realistic data |

**Key lesson from SurePay:** They achieved 30-40% reduction in misdirected payments and APP fraud in the Netherlands. The biggest impact came from CLOSE_MATCH (typo correction) rather than NO_MATCH (fraud detection). This validates our CLOSE_MATCH → show actual name approach.

### UK Confirmation of Payee (CoP) — Operational since 2020

Pay.UK operates CoP for the UK. Initially mandated for 6 largest banking groups, expanded to all PSPs.

| Feature | UK CoP | Our System | Gap? |
|---------|--------|-----------|------|
| Name matching | Yes | Yes | No |
| Response types | Match / Close Match / No Match / Not Available / Name Switched (secondary account) | MATCH / CLOSE_MATCH / NO_MATCH / NOT_FOUND / TIMEOUT / ERROR | **Partial** — we don't have "Name Switched" (when an account has been transferred to a new holder) |
| Distributed model | Each bank responds about its own accounts | Same (responder role) | No |
| Central directory | Open Banking directory for routing | Not needed (single-system) | No |
| Phase 2 (expanded coverage) | Now covers almost all PSPs + building societies | Single-system scope | Expected |
| CoP for business accounts | Supported with Companies House integration | Abbreviation matching only | **Partial** |
| Secondary Reference Data | Some banks return additional info (business registration number) | Not supported | Low priority |

**Key lesson from UK CoP:** The main implementation challenge was not the matching algorithm — it was the data quality of account holder names in bank systems. Misspellings, outdated names, and inconsistent formatting in the banks' own records caused most CLOSE_MATCH results. We should ensure A1 stores clean, normalized names.

### EU VoP Scheme (EPC, mandated by IPR)

The EPC is developing the pan-European Verification of Payee scheme:
- Must be operational by October 2025 for eurozone PSPs
- Distributed model: each PSP responds about its own accounts
- Central directory for inter-PSP routing (under development)
- ISO 20022 messaging (under development)

**Our alignment:** We implement the same pattern (requester + responder). When the EU VoP scheme finalizes its technical specification, our architecture maps directly to it.

---

## 2. "Exotic" Features Assessment

### Feature: Anti-Enumeration Detection (VOP-E02)

**Is this unusual?** Most portfolio projects implementing name checking don't consider enumeration attacks.
**Is it needed?** YES — absolutely critical. Without it, a malicious PSP (or compromised PSP credentials) could probe millions of IBANs with common names to build a name↔IBAN directory for phishing or social engineering.
**SurePay has this?** Yes — they rate-limit and monitor for suspicious query patterns.
**UK CoP has this?** Yes — same approach.
**Verdict:** **Keep. This is a production security requirement, not exotic.**

### Feature: Fraud Score Integration (VOP-I01-I03)

**Is this unusual?** Integrating VoP results into fraud scoring is the recommended approach but not universally implemented.
**EU IPR requires it?** Not explicitly. The IPR requires VoP and warns the payer, but doesn't mandate fraud scoring integration.
**Is it valuable?** Extremely. The NO_MATCH override is one of the strongest single indicators of APP fraud. Not using this signal would be negligent.
**Verdict:** **Keep. This is security best practice and a differentiator.**

### Feature: Diacritics Normalization for Nordic Names (VOP-N02)

**Is this unusual?** For a generic VoP, no. But our specific handling of Nordic characters (ø, å, æ, ä, ö, ü) goes beyond what a standard Jaro-Winkler implementation handles.
**Is it needed?** YES — for Norway. "Bjørn" and "Bjorn" are the same name. Without normalization, this would be a CLOSE_MATCH instead of MATCH, causing unnecessary friction.
**SurePay handles this for Dutch?** Yes — they handle diacritics common in Dutch names.
**Verdict:** **Keep. Essential for Norwegian market. This is a test case differentiator in interviews.**

---

## 3. Features Others Have That We Don't

| Feature | Who Has It | Priority | Recommendation |
|---------|-----------|----------|----------------|
| **Business registry integration** | SurePay (KvK), UK CoP (Companies House) | Medium | We use abbreviation matching (AS↔Aksjeselskap) as a simpler substitute. Full registry integration would need Brønnøysundregistrene (Norwegian business registry) API. Document as future extension |
| **"Name Switched" response** | UK CoP | Low | For accounts that have been transferred to a new holder. Edge case |
| **Cross-border VoP** | EU scheme (in development) | Low | Will need inter-PSP routing via EU directory. Not available yet |
| **Confidence score in response** | Some implementations | Medium | Instead of binary MATCH/NO_MATCH, return a confidence percentage. We compute this internally (Jaro-Winkler score) — could expose it to the requester. Low effort |
| **Batch verification** | Some implementations | Low | Verify multiple IBANs in one request. Corporate feature |
| **Account ownership verification** | UK extended CoP | Low | Prove that a specific person owns a specific account (stronger than name matching). Different use case (KYC) |

**Top recommendation:** Expose the confidence score (similarity score) in the response alongside the MATCH/CLOSE_MATCH/NO_MATCH result. We already compute it — just add it to the response. This gives the calling service (and the payer UI) more nuance.

---

## 4. Summary

| Category | Assessment |
|----------|-----------|
| **EU regulatory compliance** | Strong. Implements VoP as mandated by IPR. Both requester and responder roles |
| **Reference implementation alignment** | Matches SurePay (NL) and UK CoP patterns. Same three-tier response model |
| **Target company alignment** | All target companies must implement VoP. Our service mirrors what they'll need to build |
| **Exotic features** | None exotic. Anti-enumeration is production security. Fraud integration is best practice. Nordic diacritics is market-appropriate |
| **Key gap** | Business registry integration (Brønnøysundregistrene). Acceptable to substitute with abbreviation matching for portfolio scope |
