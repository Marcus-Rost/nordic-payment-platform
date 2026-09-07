# Extension: Verification of Payee (VoP) / Confirmation of Payee

*Addresses GAP 2 from gap_analysis.md*
*Regulatory basis: EU Instant Payment Regulation 2024/886, Art. 5c*
*Mandatory from October 2025 for euro credit transfers*

---

## What It Does

Before a payment is executed, the payer's PSP checks with the beneficiary's PSP whether the name the payer entered matches the actual account holder name on the destination IBAN. This prevents:
- Authorized push payment (APP) fraud ("I'm your supplier, please pay this new IBAN")
- Misdirected payments (typo in IBAN sends money to wrong person)
- Social engineering attacks

The Netherlands (SurePay, since 2017) and UK (CoP, since 2020) have proven this reduces fraud by 30-40%.

---

## Two Roles Our System Must Implement

### Role 1: VoP Requester (Outbound)
When OUR user sends a payment, we check the beneficiary's name before executing.

### Role 2: VoP Responder (Inbound)
When another PSP's user sends a payment to OUR account holder, they query us to verify the name.

Both roles are mandatory.

---

## Requirements

| ID | Requirement | Priority |
|----|------------|----------|
| VOP-F01 | Before any credit transfer (instant or batch), query the beneficiary PSP with (IBAN, stated name) | Must |
| VOP-F02 | Accept incoming VoP queries for accounts in our ledger (A1), respond with match result | Must |
| VOP-F03 | Match results: **MATCH** (exact or normalized match), **CLOSE_MATCH** (similar but not exact — return actual name), **NO_MATCH**, **NOT_FOUND** (IBAN unknown) | Must |
| VOP-F04 | On CLOSE_MATCH or NO_MATCH: display warning to payer with actual name (if CLOSE_MATCH). Payer can override and proceed, or cancel | Must |
| VOP-F05 | If payer overrides a NO_MATCH warning, log the override explicitly in audit trail | Must |
| VOP-F06 | VoP check latency: < 500ms (to fit within instant payment time budget) | Must |
| VOP-F07 | Name matching must handle: case insensitivity, diacritics (ø, å, ä, ü), common abbreviations (AS↔Aksjeselskap), name order (first-last vs. last-first), prefixes (van, de, von) | Must |
| VOP-F08 | Feed VoP result into fraud scoring (A5): NO_MATCH with payer override is a risk signal | Should |
| VOP-F09 | VoP must be offered free of charge (regulatory requirement) | Must |
| VOP-F10 | Service must be available when payment services are available (24/7 for instant payments) | Must |

## Name Matching Algorithm

```
Input: stated_name = "Ola Nordman"
       actual_name = "Ola Nordmann"

Step 1: Normalize both names
  - Lowercase: "ola nordman", "ola nordmann"
  - Remove diacritics: (none needed here)
  - Expand abbreviations: (none needed here)
  - Trim whitespace, collapse multiple spaces

Step 2: Exact match check
  - "ola nordman" != "ola nordmann" → not exact

Step 3: Fuzzy match (Jaro-Winkler similarity)
  - Score: 0.97 (very high similarity)
  - Threshold: >= 0.92 → CLOSE_MATCH

Step 4: Return result
  - Result: CLOSE_MATCH
  - Actual name returned: "Ola Nordmann"
  - Payer sees: "The name you entered doesn't exactly match. 
                 Did you mean 'Ola Nordmann'?"
```

### Match Thresholds (configurable)

| Jaro-Winkler Score | Result |
|-------------------|--------|
| >= 0.95 | MATCH |
| >= 0.85 and < 0.95 | CLOSE_MATCH (return actual name) |
| < 0.85 | NO_MATCH |

---

## API Design

### VoP Requester API (Internal — called by A2/A3/A4 before payment)

```
POST /internal/vop/verify
{
  "beneficiary_iban": "NO93 8601 1117 947",
  "stated_name": "Ola Nordmann",
  "request_id": "uuid"
}

Response:
{
  "request_id": "uuid",
  "result": "CLOSE_MATCH",
  "actual_name": "Ola Nordmann AS",
  "confidence": 0.91,
  "response_time_ms": 120
}
```

### VoP Responder API (External — queried by other PSPs)

```
POST /api/v1/vop/check
{
  "iban": "NO93 8601 1117 947",
  "name": "Ola Nordmann"
}

Response:
{
  "result": "MATCH",
  "response_time_ms": 45
}
```

Note: The responder API does NOT return the actual name on NO_MATCH (privacy — you don't reveal account holder names to unmatched queries). It only returns the actual name on CLOSE_MATCH (to help the payer correct a near-miss).

---

## Integration with Fraud Detection (A5)

VoP result becomes an input feature to the fraud scoring model:

| VoP Result | Payer Action | Fraud Risk Signal |
|-----------|-------------|-------------------|
| MATCH | Proceeds | Neutral (no additional risk) |
| CLOSE_MATCH | Corrects name, proceeds | Low (legitimate typo) |
| CLOSE_MATCH | Ignores, overrides | Medium (why ignore the correction?) |
| NO_MATCH | Cancels payment | N/A (payment not executed) |
| NO_MATCH | Overrides warning, proceeds | **High** (paying someone who isn't who they said — classic APP fraud pattern) |
| NOT_FOUND | Overrides | **Very high** (IBAN doesn't exist at beneficiary PSP) |

---

## Technologies

| Component | Technology |
|-----------|-----------|
| Name normalization | Custom Java utility using `java.text.Normalizer` (NFD decomposition for diacritics) |
| Fuzzy matching | **Apache Commons Text** `JaroWinklerSimilarity` or custom implementation |
| Caching | Account holder names cached in-memory (Caffeine cache) for responder role — invalidate on name change |
| API | REST endpoint (Spring Boot) — internal for requester, external for responder |
| Latency target | < 500ms total (including network to beneficiary PSP) |
