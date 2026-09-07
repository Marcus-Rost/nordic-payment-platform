# Interoperability Review — Findings

*Date: 2026-04-12*

---

## Summary

14 checks. 7 PASS, 4 PARTIAL, 3 FAIL.

### FAIL

| ID | Finding | Fix |
|----|---------|-----|
| **IR9.1** | Supported currency set {NOK, EUR, SEK, DKK, USD} not enumerated in master architecture or INTERFACE_CONTRACTS | Add to PROJECT_GUIDELINES or INTERFACE_CONTRACTS |
| **IR9.2** | Per-currency minor units hardcoded to DECIMAL(18,2) — no ISO 4217 minor unit logic | Already flagged in Financial Integrity review (FI1.5). Same fix: derive from Currency enum. |
| **IR9.3** | EPC character set (EPC217-08) not enforced on VoP input | Add EPC character set validation to A6 input validation layer |

### PARTIAL

| ID | Finding | Fix |
|----|---------|-----|
| IR9.4 | IBAN validation not shared — each service implements independently | Consolidate into shared utility (already noted in prior review) |
| IR9.5 | UTF-8 not mandated system-wide — only explicit in JAXB | Add to PROJECT_GUIDELINES |
| IR9.6 | ISO 8601 + UTC not explicitly mandated | Add to PROJECT_GUIDELINES |
| IR9.7 | PostgreSQL uses TIMESTAMP (timezone-naive) not TIMESTAMPTZ | Document: use TIMESTAMP WITH TIME ZONE |

### Positive

- ISO 20022 message compliance: all 6 message types documented with version numbers
- JAXB from XSD with secure XML processing
- Shared `payment-iso20022` library
- IBAN validation with mod-97 checksum (in A6)
- BerlinGroup NextGenPSD2 compliance in A4
