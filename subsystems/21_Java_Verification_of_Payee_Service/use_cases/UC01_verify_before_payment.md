# UC-01: Verify Beneficiary Name Before Payment

*Both requester and responder flows*

---

## Scenario 1A: Requester — Exact Match (Happy Path)

| Step | Actor | Action | Response |
|------|-------|--------|---------|
| 1 | Payer (via A3/A4) | Enters: name "Ola Nordmann", IBAN "NO93 8601 1117 947" | |
| 2 | A3/A4 | Calls A6: `POST /internal/vop/verify` | |
| 3 | A6 | Cache lookup for IBAN → found: "Ola Nordmann" | |
| 4 | A6 | Normalize both: "ola nordmann" vs "ola nordmann" → Jaro-Winkler = 1.0 | |
| 5 | A6 | Returns `{result: MATCH}` in 12ms | |
| 6 | A3/A4 | Shows payer: "Name verified" (green indicator) | Payment proceeds |

## Scenario 1B: Requester — Close Match (Typo or Missing Suffix)

| Step | Actor | Action | Response |
|------|-------|--------|---------|
| 1 | Payer | Enters: "Ola Nordman" (missing final 'n'), IBAN for "Ola Nordmann AS" | |
| 2 | A6 | Normalize: "ola nordman" vs "ola nordmann as". Jaro-Winkler = 0.89 → CLOSE_MATCH | |
| 3 | A6 | Returns `{result: CLOSE_MATCH, actual_name: "Ola Nordmann AS"}` | |
| 4 | A3/A4 | Shows: **"The account is registered to 'Ola Nordmann AS'. Is this the intended recipient?"** | |
| 5a | Payer | Confirms "Yes" → corrects name | Payment proceeds. VoP result: CLOSE_MATCH (corrected). No fraud signal |
| 5b | Payer | Clicks "No, cancel" | Payment abandoned. No fraud signal |

## Scenario 1C: Requester — No Match (Potential Fraud)

| Step | Actor | Action | Response |
|------|-------|--------|---------|
| 1 | Payer | Enters: "Ola Nordmann", IBAN belongs to "Kari Hansen" (fraudster redirected payment to wrong account) | |
| 2 | A6 | Jaro-Winkler("ola nordmann", "kari hansen") = 0.32 → NO_MATCH | |
| 3 | A6 | Returns `{result: NO_MATCH}` (NO actual name revealed — privacy protection) | |
| 4 | A3/A4 | Shows warning: **"The name you entered does NOT match the account holder for this IBAN. Proceeding may result in payment to the wrong person. Are you sure?"** | |
| 5a | Payer | Cancels payment (recommended) | No payment. No fraud signal |
| 5b | Payer | **Overrides warning** (clicks "Proceed anyway") | Payment continues. Override logged in audit. VoP override flag → A5 fraud scoring: +0.3 risk increase |

## Scenario 1D: Requester — Nordic Diacritics

| Input | Account Holder | Normalized | Score | Result |
|-------|---------------|------------|-------|--------|
| "Bjørn Nilsen" | "Bjorn Nilsen" | "bjorn nilsen" / "bjorn nilsen" | 1.0 | MATCH |
| "Ase Johansen" | "Åse Johansen" | "ase johansen" / "ase johansen" | 1.0 | MATCH |
| "Gronn Energi AS" | "Grønn Energi Aksjeselskap" | "gronn energi as" / "gronn energi aksjeselskap" | 0.87 | CLOSE_MATCH |
| "Müller GmbH" | "Mueller GmbH" | "muller gmbh" / "mueller gmbh" | 0.94 | CLOSE_MATCH |

## Scenario 1E: Responder — External PSP Queries Us

| Step | Actor | Action | Response |
|------|-------|--------|---------|
| 1 | External PSP | `POST /api/v1/vop/check` with API key + `{iban: "NO93...", name: "Ola Nordmann"}` | |
| 2 | A6 | Validate: API key valid, rate limit OK (42 queries this minute < 100 max) | |
| 3 | A6 | Cache lookup → "Ola Nordmann" → Jaro-Winkler = 1.0 | |
| 4 | A6 | Returns `{result: "MATCH"}` | Logged: PSP ID, IBAN queried, result, timestamp |

### Responder — Anti-Enumeration Triggered

| Step | Action | Response |
|------|--------|---------|
| 1 | Same PSP queries same IBAN with 6 different names in 45 minutes | |
| 2 | A6 detects pattern: > 5 distinct names for same IBAN from same PSP in 1 hour | |
| 3 | Returns 429: `{error: "rate_limited", message: "Too many verification attempts for this IBAN"}` | |
| 4 | Logged as potential enumeration attempt. Security alert created | |

---

## Security Considerations
- **Privacy is paramount:** On NO_MATCH, the actual name is NEVER revealed. This prevents an attacker from probing IBANs to discover who holds each account
- **Anti-enumeration is critical:** Without rate limiting + pattern detection, an attacker could systematically probe thousands of IBANs with common names to build a name→IBAN directory
- **VoP override as fraud signal:** The decision to proceed despite a NO_MATCH warning is one of the strongest indicators of APP (Authorized Push Payment) fraud. This signal flows to A5 and can tip a borderline transaction over the manual review threshold
