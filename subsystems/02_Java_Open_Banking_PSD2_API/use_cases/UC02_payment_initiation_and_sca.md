# UC-02: Payment Initiation with SCA and Exemptions

*PSD2 PISP flow: TPP initiates a payment with dynamic linking SCA*

---

## Scenario 2A: Standard Payment Initiation (SCA Required)

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | TPP | `POST /api/v1/payments` with Bearer token + `{debtorIban, creditorIban, creditorName, amount: 5000.00, currency: NOK, serviceLevel: INSTANT}` | A4 validates: token scope `payments:initiate`, consent covers debtor account |
| 2 | A4 | Triggers VoP check via A6 | `creditorIban` + `creditorName` → A6 → **MATCH** |
| 3 | A4 | Determines SCA requirement: amount NOK 5,000 > EUR 30 equivalent, not trusted beneficiary, not recurring → **SCA REQUIRED** | Returns 201 with `{paymentId, status: "REQUIRES_SCA", scaRedirectUrl: "/sca/payments/{paymentId}"}` |
| 4 | Account Holder | Redirected to SCA page. Sees: **"Confirm payment of NOK 5,000.00 to Ola Nordmann. Enter TOTP code."** | Dynamic linking: amount + creditor shown in challenge |
| 5 | Account Holder | Enters TOTP code | A4 validates. SCA complete. Payment authorized |
| 6 | A4 | Publishes to Kafka `payment.instant-instructions` | Payment enters instant processing pipeline |
| 7 | TPP | Polls `GET /api/v1/payments/{id}` | Status progresses: PROCESSING → SETTLED (in ~3-4 seconds for instant) |

---

## Scenario 2B: Low-Value Exemption (No SCA)

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | TPP | `POST /api/v1/payments` with `{amount: 25.00, currency: EUR}` | A4 validates token + consent |
| 2 | A4 | SCA Exemption Engine evaluates: amount EUR 25 < EUR 30 threshold. Checks cumulative: 2 exempt payments since last SCA (< 5 max), EUR 55 cumulative (< EUR 100 max) → **EXEMPT (LOW_VALUE)** | |
| 3 | A4 | No SCA required. Payment proceeds directly | Returns 201: `{paymentId, status: "PROCESSING", scaExemption: "LOW_VALUE"}`. Published to Kafka |
| 4 | A4 | Updates counters: exempt_count = 3, exempt_amount = EUR 80 | |

### Next payment: EUR 25 again (cumulative would hit EUR 105 > EUR 100 limit)
| Step | Action | Response |
|------|--------|---------|
| 5 | TPP submits another EUR 25 payment | A4 checks: cumulative would be EUR 105 > EUR 100 limit → **SCA REQUIRED** despite low value |
| 6 | After successful SCA | Counters reset: exempt_count = 0, exempt_amount = EUR 0 |

---

## Scenario 2C: Trusted Beneficiary Exemption

| Step | Action | Response |
|------|--------|---------|
| 1 | Account holder previously: `POST /api/v1/trusted-beneficiaries` with `{iban: "NO12345678901", name: "Strøm AS"}` (SCA required to add) | Creditor added to trusted list |
| 2 | TPP initiates payment to NO12345678901 for NOK 8,000 | A4: creditor is in trusted beneficiaries → **EXEMPT (TRUSTED_BENEFICIARY)**. No SCA. Payment proceeds |

---

## Scenario 2D: Transaction Risk Analysis (TRA) Exemption

| Step | Action | Response |
|------|--------|---------|
| 1 | TPP initiates payment of EUR 200 | A4 evaluates: amount EUR 200. Our fraud rate for credit transfers (from A5 metrics) = 0.005% (below EBA threshold of 0.01% for EUR 250 bracket) → **EXEMPT (TRA)** |
| 2 | No SCA required | Payment proceeds. Logged with exemption type TRA and fraud rate used |

**TRA thresholds (EBA RTS):**
| Max exempt amount | Required fraud rate |
|---|---|
| EUR 500 | < 0.01% |
| EUR 250 | < 0.06% |
| EUR 100 | < 0.13% |

---

## Scenario 2E: VRP Payment (Pre-authorized, No SCA)

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | TPP | `POST /api/v1/vrp/payments` with `{consentId, amount: 1847.50, currency: NOK, reference: "April electricity"}` | A4 validates Bearer token |
| 2 | A4 | VRP parameter check: amount 1,847.50 <= max 3,000 ✓. Cumulative this month 1,200 + 1,847.50 = 3,047.50 <= 10,000 ✓. Count 1 + 1 = 2 <= 4 ✓. Payee matches ✓. Not expired ✓ | All bounds satisfied |
| 3 | A4 | **No SCA required** — consent was SCA'd at creation | Payment published to instant path |
| 4 | A4 | Updates VRP counters: period_amount = 3,047.50, period_count = 2 | |
| 5 | TPP | Receives confirmation: SETTLED in ~3 seconds | |

### VRP: Parameter Exceeded
| Step | Action | Response |
|------|--------|---------|
| 1 | TPP submits `{amount: 4500.00}` against VRP consent with max 3,000 per payment | A4 returns 422: `{error: "vrp_parameter_exceeded", parameter: "max_amount_per_payment", limit: 3000, requested: 4500}` |

---

## Scenario 2F: VoP No Match — Payer Override or Cancel

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | TPP | `POST /api/v1/payments` with creditorName "Ola Nordmann", creditorIban belongs to "Kari Hansen" | A4 validates token + consent |
| 2 | A4 | VoP check via A6 | A6 returns **NO_MATCH** (Jaro-Winkler = 0.32) |
| 3 | A4 | Returns 201 with `{paymentId, status: "REQUIRES_CONFIRMATION", vopResult: "NO_MATCH", warning: "Beneficiary name does not match the account holder for this IBAN"}` | Payment NOT yet submitted to clearing |
| 4a | TPP/Payer | `PUT /api/v1/payments/{id}/confirm` with `{override: true}` | A4 logs VoP override. Payment proceeds to SCA (if required) or directly to clearing. VoP override flag (+0.3 risk) forwarded to A5 |
| 4b | TPP/Payer | `PUT /api/v1/payments/{id}/cancel` | Payment → CANCELLED. No charge. No fraud signal |

*Note: VoP check happens before SCA. If VoP returns NO_MATCH and payer overrides, the SCA dynamic linking page shows an additional warning.*

---

## Scenario 2G: VRP Consent Creation (Full Flow)

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | TPP | `GET /api/v1/authorize?scope=vrp:initiate&vrp_max_amount=3000&vrp_max_cumulative=10000&vrp_period=MONTHLY&vrp_max_frequency=4&vrp_payee_iban=NO12...&vrp_payee_name=EnergiNord+AS&vrp_valid_months=12` | A4 stores VRP consent request, redirects to consent page |
| 2 | Account Holder | Reviews consent parameters: "EnergiNord AS requests permission to make payments from your account with limits: max NOK 3,000 per payment, max NOK 10,000 per month, max 4 payments per month. Valid for 12 months." | |
| 3 | Account Holder | Enters PIN + TOTP code | **SCA complete** |
| 4 | Account Holder | Clicks **Approve** | A4 creates VRP consent record with all parameter bounds. Generates authorization code |
| 5 | A4 | Redirects to TPP callback with code | TPP exchanges for access token with `vrp:initiate` scope |
| 6 | TPP | Token received. Can now initiate VRP payments within bounds | |

**Postconditions**: VRP consent ACTIVE, parameters immutable, SCA timestamp recorded. Account holder can view and revoke via consent dashboard.

---

## Scenario 2H: VRP Consent Revocation

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | Account Holder | Via consent dashboard: `DELETE /api/v1/consents/{vrp-consent-id}` | A4 sets consent status = REVOKED. All associated refresh tokens invalidated |
| 2 | TPP | Next VRP payment attempt: `POST /api/v1/vrp/payments` | A4 returns 403: `{error: "consent_revoked", message: "VRP consent has been revoked by the account holder"}` |
| 3 | TPP | Must request new VRP consent from account holder (new SCA required) | |

---

## Scenario 2I: SCA Timeout

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | TPP | Initiates payment → A4 returns REQUIRES_SCA with redirect | |
| 2 | Account Holder | Redirected to SCA page but does not complete within 5 minutes | |
| 3 | A4 | SCA timeout fires (5 minutes, configurable via PSD2-F21) | Payment → CANCELLED with reason `SCA_TIMEOUT` |
| 4 | TPP | Polls `GET /api/v1/payments/{id}` | Returns `{status: "CANCELLED", reason: "SCA_TIMEOUT"}` |

---

## Security Considerations
- SCA exemption decisions are **logged to audit trail** with: exemption type, rationale, cumulative counters at time of decision, fraud rate (for TRA)
- Dynamic linking is cryptographically bound: the TOTP seed incorporates the payment amount and creditor IBAN hash (simplified simulation — in production this would be scheme-specific)
- Even with exemptions, every payment still goes through fraud scoring (A5) and sanctions screening (A5) — SCA exemption does NOT exempt from fraud/sanctions checks
- VRP consent parameters are enforced bank-side — the TPP cannot bypass them
- VoP NO_MATCH override is explicitly recorded and forwarded as a high-risk signal to A5
