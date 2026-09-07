# UC-09: Verification of Payee (Pre-Payment Name Check)

*Sub-systems involved: A6 (VoP Service), A1 (Account Ledger), A5 (Fraud — consumes VoP result)*
*Regulatory basis: EU Instant Payment Regulation 2024/886, Art. 5c*
*Mandatory from October 2025 for all euro credit transfers*

---

## Scenario 9A: Requester Role — Check Before Sending

### Primary Actor
**Payer** (via A3, A4, or directly)

### Trigger
Payer enters beneficiary name and IBAN before initiating a payment

### Main Success Scenario

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | Payer | Enters: beneficiary name "Ola Nordmann", IBAN "NO93 8601 1117 947" | A3/A4 captures input |
| 2 | A3/A4 | Calls A6 VoP requester: `POST /internal/vop/verify` | A6 routes query to beneficiary PSP (simulated as internal lookup against A1 in our system) |
| 3 | A6 | Looks up IBAN in A1, retrieves account holder name "Ola Nordmann" | Normalizes both names, computes Jaro-Winkler similarity = 1.0 |
| 4 | A6 | Returns `{result: "MATCH", response_time_ms: 45}` | Result logged in audit trail |
| 5 | A3/A4 | Displays to payer: "Name verified" (green check) | Payment proceeds normally |

### Alternative: Close Match

| Step | Action | System Response |
|------|--------|----------------|
| 3a | Account holder name is "Ola Nordmann AS" (company). Payer entered "Ola Nordmann" | Jaro-Winkler = 0.92 → CLOSE_MATCH |
| 4a | Returns `{result: "CLOSE_MATCH", actual_name: "Ola Nordmann AS"}` | |
| 5a | Displays: "The account is registered to **Ola Nordmann AS**. Is this correct?" | Payer confirms → proceeds. Payer cancels → payment abandoned |

### Alternative: No Match

| Step | Action | System Response |
|------|--------|----------------|
| 3b | Account holder is "Kari Hansen". Payer entered "Ola Nordmann" | Jaro-Winkler = 0.21 → NO_MATCH |
| 4b | Returns `{result: "NO_MATCH"}`. Actual name NOT returned (privacy) | |
| 5b | Displays warning: "The name you entered does NOT match the account holder. Proceeding may result in payment to the wrong person." | |
| 5c | Payer can: **Cancel** (recommended) or **Override** (proceed anyway with explicit acknowledgment) | |
| 5d | If override: logged in audit trail. VoP override flag sent to A5 fraud scoring as HIGH_RISK input | |

### Alternative: IBAN Not Found

| Step | Action | System Response |
|------|--------|----------------|
| 3c | IBAN does not exist at the queried PSP | Returns NOT_FOUND |
| 4c | Displays: "This account could not be verified. Please check the IBAN." | Payment blocked unless payer explicitly overrides |

---

## Scenario 9B: Responder Role — Answer Queries About Our Accounts

### Primary Actor
**External PSP** querying whether a name matches one of our accounts

### Trigger
Another PSP's user wants to send a payment to an account held in our system

### Main Success Scenario

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | External PSP | `POST /api/v1/vop/check` with `{iban: "NO93 8601 1117 947", name: "Ola Nordmann"}` (authenticated via API key or mTLS) | A6 receives query |
| 2 | A6 | Validates authentication. Rate limit check (prevent enumeration) | OK |
| 3 | A6 | Queries A1 for account holder name by IBAN | Found: "Ola Nordmann" |
| 4 | A6 | Normalizes and compares: Jaro-Winkler = 1.0 → MATCH | |
| 5 | A6 | Returns `{result: "MATCH"}` | Logged in audit trail: querying PSP identity, IBAN queried, result, timestamp |

### Responder Privacy Rules

| Result | What We Return | What We Do NOT Return |
|--------|---------------|----------------------|
| MATCH | `{result: "MATCH"}` | — |
| CLOSE_MATCH | `{result: "CLOSE_MATCH", actual_name: "Ola Nordmann AS"}` | (actual name revealed to help correction) |
| NO_MATCH | `{result: "NO_MATCH"}` | Actual name NOT revealed (prevent name harvesting) |
| NOT_FOUND | `{result: "NOT_FOUND"}` | — |

### Security: Anti-Enumeration Measures
- Rate limit: max 100 queries per minute per querying PSP
- If same IBAN queried > 5 times with different names in 1 hour → flag as potential enumeration attack → temporarily block querying PSP for that IBAN
- All queries logged with querying PSP identity for audit

---

## Scenario 9C: VoP Integration with Fraud Detection (A5)

The VoP result is forwarded to A5 as an input to the fraud score. This creates a feedback loop:

| VoP Result | Payer Action | Fraud Signal Weight |
|-----------|-------------|-------------------|
| MATCH | Proceeds | +0.0 (neutral) |
| CLOSE_MATCH | Corrects name | +0.0 (neutral — legitimate typo) |
| CLOSE_MATCH | Overrides without correcting | +0.1 (mild risk) |
| NO_MATCH | Cancels | N/A (no payment) |
| NO_MATCH | **Overrides** | **+0.3** (significant risk — classic APP fraud indicator) |
| NOT_FOUND | **Overrides** | **+0.4** (high risk — IBAN doesn't exist) |

These weights are added to the base fraud score. A NO_MATCH override alone could push a borderline transaction (score 0.45) above the manual review threshold (0.50).

---

## Data Requirements

**VoP Query Log (Audit):**
```
query_id:           UUID
direction:          Enum (OUTBOUND_REQUEST, INBOUND_RESPONSE)
querying_psp_id:    String (for inbound) or "SELF" (for outbound)
iban:               String
stated_name:        String
actual_name:        String (nullable — only stored for MATCH/CLOSE_MATCH)
result:             Enum (MATCH, CLOSE_MATCH, NO_MATCH, NOT_FOUND, TIMEOUT, ERROR)
similarity_score:   Double
response_time_ms:   Long
payer_override:     Boolean (did payer proceed despite mismatch?)
timestamp:          Instant (UTC)
```
