# UC-03: PSD2 Payment Initiation (PISP Flow)

*Sub-systems involved: A4 (PSD2 API), A6 (VoP), A2 (Clearing), A5 (Fraud & Sanctions), A1 (Account Ledger)*
*Mirrors: DNB/SpareBank 1 PSD2 Payment Initiation Service Provider (PISP) APIs*
*Updated: includes VoP check, SCA exemptions, instant payment option, sanctions screening*

---

## Primary Actor
**Third-Party Provider (TPP)** — a fintech app initiating a payment on behalf of an account holder

## Preconditions
- TPP has an active consent with `payments:initiate` scope (obtained via UC-02 flow)
- Account holder's account exists in A1 with sufficient balance
- TPP has a valid access token

## Trigger
TPP submits a payment initiation request on behalf of the account holder

---

## Main Success Scenario (SCA Required)

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | TPP | POST `/api/v1/payments` with `{debtor_account, creditor_account, creditor_name, amount: 5000.00, currency: NOK, reference, service_level: INSTANT}` and Bearer token | A4 validates token (scope: `payments:initiate`), validates consent is active and covers debtor_account |
| 2 | A4 | **VoP check** via A6 | Queries A6: does creditor_name match creditor_account? → **MATCH**. Result logged |
| 3 | A4 | **SCA exemption check** | Evaluates: amount NOK 5,000 > EUR 30 equivalent → not low-value exempt. Not a trusted beneficiary. Not recurring. TRA: fraud rate 0.008% < 0.01% threshold BUT amount > EUR 500 → TRA not applicable. **SCA REQUIRED** |
| 4 | A4 | Returns 201 with payment_id + status `REQUIRES_SCA` + SCA redirect URL | Dynamic linking: SCA will show amount + creditor |
| 5 | Account Holder | Redirected to SCA page. Sees: **"Confirm payment of NOK 5,000.00 to Ola Nordmann. Enter TOTP code."** | |
| 6 | Account Holder | Enters TOTP code | A4 validates. SCA complete. Dynamic linking verified |
| 7 | A4 | Publishes PaymentInstruction to Kafka `payment.instant-instructions` | Includes: payment_id, debtor, creditor, amount, currency, TPP identity, consent reference, VoP result, SCA method |
| 8 | — | A2 (instant path): reserves balance → A5 sanctions screening (CLEAR) → A5 fraud scoring (0.15, PASS) → gross settlement → A1 ledger update | See UC-08 for full instant payment flow |
| 9 | — | Status update: SETTLED | `payment.status-updates` → A4 receives SETTLED |
| 10 | TPP | GET `/api/v1/payments/{id}` | Returns: `{status: SETTLED, settledAt: "...", serviceLevel: INSTANT, scaMethod: "TOTP", scaExemption: null}` |

## Postconditions
- Payment settled in ~3-4 seconds (instant path)
- Balances updated in A1
- Full audit trail: consent used, VoP checked, SCA performed, fraud + sanctions scored, settlement executed

---

## Alternative Flows — SCA Exemptions

### AF-01: Low-Value Exemption (No SCA)
| Step | Condition | System Response |
|------|-----------|----------------|
| 3a | Amount = EUR 20 (< EUR 30 threshold). Cumulative since last SCA: 2 exempt payments, EUR 45 (< 5 count limit, < EUR 100 amount limit) | **SCA EXEMPT (LOW_VALUE)**. Skip steps 4-6. Payment proceeds directly |
| 3a-1 | A4 updates counters: exempt_count=3, exempt_amount=EUR 65 | Logged: `{scaExemption: "LOW_VALUE", exemptCount: 3, exemptAmount: 65.00}` |
| — | **Next payment**: EUR 40 (cumulative would be EUR 105 > EUR 100 limit) | SCA REQUIRED despite low value. After SCA: counters reset to 0 |

### AF-02: Trusted Beneficiary Exemption (No SCA)
| Step | Condition | System Response |
|------|-----------|----------------|
| 3b | Creditor IBAN is in account holder's trusted beneficiary list (previously added with SCA) | **SCA EXEMPT (TRUSTED_BENEFICIARY)**. Skip steps 4-6. Payment proceeds directly |
| — | Logged: `{scaExemption: "TRUSTED_BENEFICIARY", trustedSince: "2026-01-15"}` | |

### AF-03: Recurring Payment Exemption (No SCA)
| Step | Condition | System Response |
|------|-----------|----------------|
| 3c | Same amount (NOK 1,299.00) + same creditor as a previous SCA-authenticated payment | **SCA EXEMPT (RECURRING)**. First payment required SCA; subsequent identical payments skip SCA |
| — | If amount changes (NOK 1,399.00 next month) | SCA REQUIRED again (different amount breaks the recurring exemption) |

### AF-04: Transaction Risk Analysis (TRA) Exemption (No SCA)
| Step | Condition | System Response |
|------|-----------|----------------|
| 3d | Amount = EUR 200. Our fraud rate for credit transfers = 0.005% (below EBA threshold of 0.06% for EUR 250 bracket) | **SCA EXEMPT (TRA)**. Skip steps 4-6 |
| — | Logged: `{scaExemption: "TRA", fraudRate: 0.005, ebaThreshold: 0.06, maxExemptAmount: 250}` | |

**TRA thresholds (EBA RTS Article 18):**
| Max exempt amount | Required PSP fraud rate |
|---|---|
| EUR 500 | < 0.01% |
| EUR 250 | < 0.06% |
| EUR 100 | < 0.13% |

---

## Alternative Flows — Errors and Edge Cases

### AF-05: VoP No Match
| Step | Condition | System Response |
|------|-----------|----------------|
| 2a | VoP returns **NO_MATCH** | A4 returns 200 with `{paymentId, status: "REQUIRES_CONFIRMATION", vopResult: "NO_MATCH", warning: "Beneficiary name does not match account holder"}` |
| 2b | TPP (or account holder) confirms override | Payment proceeds. VoP override logged. Override flag → A5 (+0.3 risk) |
| 2c | TPP (or account holder) cancels | Payment abandoned. No charge |

### AF-06: SCA Not Completed (Timeout)
| Step | Condition | System Response |
|------|-----------|----------------|
| 6a | Account holder does not complete SCA within 5 minutes | A4 transitions payment to CANCELLED (reason: SCA_TIMEOUT) |

### AF-07: Consent Does Not Cover Debtor Account
| Step | Condition | System Response |
|------|-----------|----------------|
| 1a | debtor_account not covered by active consent | A4 returns 403: `{error: "consent_scope_mismatch"}` |

### AF-08: Sanctions Hit
| Step | Condition | System Response |
|------|-----------|----------------|
| 8a | A5 sanctions screening returns POTENTIAL_HIT | Payment HELD. Compliance alert created. TPP queries status → `{status: "HELD", reason: "COMPLIANCE_REVIEW"}` |

### AF-09: Fraud Detected
| Step | Condition | System Response |
|------|-----------|----------------|
| 8b | A5 fraud score 0.92 → AUTO_BLOCK | Payment REJECTED. `{status: "REJECTED", reason: "FRAUD_SUSPECTED"}` |

### AF-10: Daily Limit Exceeded
| Step | Condition | System Response |
|------|-----------|----------------|
| 1b | Cumulative payments today exceed account daily limit | A4 returns 422: `{error: "daily_limit_exceeded"}` |

### AF-11: Instant Payment Timeout
| Step | Condition | System Response |
|------|-----------|----------------|
| 8c | Instant path exceeds 7-second hard limit (EPC 2025) | Payment REJECTED with TIMEOUT. Balance reservation released. See UC-08 AF-06 |

---

## Security Considerations
- **Per-payment SCA**: Required for each payment unless an exemption applies (PSD2 Article 97)
- **SCA exemption decisions are logged** with: exemption type, rationale, cumulative counters (for low-value), fraud rate (for TRA). This is required for regulatory audit
- **Dynamic linking**: SCA challenge cryptographically bound to specific payment amount and creditor (PSD2 RTS Article 5)
- **VoP check before SCA**: The VoP result is available before the user authenticates, allowing the warning to be shown during the consent/confirmation step
- **Non-repudiation**: PaymentInstruction on Kafka includes TPP identity, consent reference, SCA timestamp, SCA exemption type, VoP result — creating a complete authorization chain
- **Even with SCA exemption**: Payment still goes through fraud scoring (A5) and sanctions screening (A5). SCA exemption does NOT exempt from fraud/sanctions checks
