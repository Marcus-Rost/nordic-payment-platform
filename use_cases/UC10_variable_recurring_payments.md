# UC-10: Variable Recurring Payments (VRP)

*Sub-systems involved: A4 (PSD2 API — consent management), A2 (Clearing), A1 (Account Ledger)*
*No regulatory mandate yet — this is a DIFFERENTIATING feature*
*Inspired by: UK Open Banking VRP (CMA mandate for sweeping, 2022)*

---

## What Makes VRP Different

| Aspect | Traditional Direct Debit (SDD) | AvtaleGiro (UC-06) | **VRP** |
|--------|-------------------------------|---------------------|---------|
| Direction | Pull (creditor debits) | Push (consumer approves invoice) | Push (TPP initiates on behalf of payer) |
| Auth model | One-time mandate | One-time mandate per merchant | **Parameterized consent** with bounds |
| Per-payment SCA | No | No | **No** (SCA only at consent creation) |
| Speed | Batch (D+1/D+2) | Batch or instant | **Instant** (SCT Inst) |
| Control granularity | Limited (amount/frequency) | Max amount per invoice | **Detailed**: max per payment, max per period, max frequency, date range, specific payee |
| Refund rights | 8 weeks (Core) | None specified | None (consent-limited, not guaranteed) |
| Smart use cases | No | No | **Yes**: sweeping, variable billing, metered services |

---

## Primary Actor
**Consumer** (payer) grants a VRP consent to a TPP via A4

## Preconditions
- TPP is registered with A4 (PSD2 API Gateway)
- Consumer has an active account in A1

## Trigger
TPP requests VRP consent from consumer (e.g., utility company wants to auto-debit variable monthly amounts)

---

## Scenario 10A: VRP Consent Creation

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | TPP | `GET /api/v1/authorize?scope=vrp:initiate&vrp_params=...` | A4 presents consent page to consumer |
| 2 | Consumer | Reviews VRP consent parameters displayed by A4 | "**EnergiNord AS** requests permission to make payments from your account with these limits: max NOK 3,000 per payment, max NOK 10,000 per month, max 4 payments per month. Valid for 12 months." |
| 3 | Consumer | Completes **SCA** (PIN + TOTP) | A4 verifies both factors |
| 4 | Consumer | Clicks "Approve" | A4 creates VRP consent record. Returns authorization code to TPP |
| 5 | TPP | Exchanges code for access token (with `vrp:initiate` scope) | Token linked to VRP consent and its parameter bounds |

**VRP Consent Parameters:**
```json
{
  "consent_id": "vrp-uuid-001",
  "tpp_client_id": "energinord-tpp",
  "payer_account": "NO93 8601 1117 947",
  "payee_account": "NO12 3456 7890 123",
  "payee_name": "EnergiNord AS",
  "max_amount_per_payment": { "amount": 3000.00, "currency": "NOK" },
  "max_cumulative_per_period": { "amount": 10000.00, "currency": "NOK", "period": "MONTHLY" },
  "max_frequency_per_period": { "count": 4, "period": "MONTHLY" },
  "valid_from": "2026-04-11",
  "valid_until": "2027-04-11",
  "status": "ACTIVE"
}
```

---

## Scenario 10B: VRP Payment Execution (No SCA Required)

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | TPP | `POST /api/v1/vrp/payments` with `{consent_id, amount: 1,847.50, currency: NOK, reference: "April electricity"}` and Bearer token | A4 receives payment request |
| 2 | A4 | **Parameter validation** against consent bounds | Check 1: amount 1,847.50 <= max 3,000 ✓. Check 2: cumulative this month (1,200 + 1,847.50 = 3,047.50) <= 10,000 ✓. Check 3: payments this month (1 + 1 = 2) <= 4 ✓. Check 4: consent not expired ✓. Check 5: payee matches consent ✓ |
| 3 | A4 | **No SCA required** (consent already SCA'd at creation. Parameters within bounds) | Proceed to payment |
| 4 | A4 | Publishes payment instruction to `payment.instant-instructions` with `service_level: INSTANT` | VRP payments default to instant |
| 5-10 | — | Standard instant payment flow (UC-08 steps 4-10) | VoP check, sanctions, fraud, settlement |
| 11 | A4 | Updates cumulative counters: total this month = 3,047.50, count = 2 | |
| 12 | TPP | Receives confirmation: SETTLED in ~3 seconds | |

**No consumer interaction required.** The TPP initiated and the payment executed automatically because it was within the pre-authorized bounds.

---

## Scenario 10C: VRP Payment Exceeds Bounds — Rejected

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | TPP | `POST /api/v1/vrp/payments` with `{amount: 4,500.00}` | A4 receives |
| 2 | A4 | Parameter check: 4,500 > max 3,000 per payment | **REJECTED**: `{error: "vrp_parameter_exceeded", parameter: "max_amount_per_payment", limit: 3000, requested: 4500}` |
| 3 | TPP | Must either: reduce amount, or request new consent from consumer with higher limits | |

---

## Scenario 10D: VRP Consent Revocation

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | Consumer | Via consent dashboard: `DELETE /api/v1/consents/{vrp-uuid-001}` | A4 sets consent REVOKED. All associated tokens invalidated |
| 2 | TPP | Next payment attempt | A4 returns 403: `{error: "consent_revoked"}` |
| 3 | TPP | Must request new consent from consumer | |

---

## Scenario 10E: "Sweeping" — Automated Savings Transfer

VRP enables "sweeping" — automatically moving excess funds from current account to savings:

| Step | Actor | Action |
|------|-------|--------|
| 1 | Consumer | Grants VRP consent to their own savings app: max NOK 5,000 per transfer, max 2 per month, payee = own savings account |
| 2 | Savings app (TPP) | Weekly: checks current account balance via AISP. If > NOK 15,000, sweeps NOK 2,000 to savings via VRP |
| 3 | — | Payment executes instantly, no SCA needed, within consent bounds |

This is the original UK CMA-mandated "sweeping VRP" use case — moving money between a customer's own accounts at different banks.

---

## Security Considerations

- **SCA at consent creation only**: PSD2 requires SCA when the payer authorizes a series of payments. The consent creation IS that authorization. Individual payments within bounds don't need SCA.
- **Bank-side enforcement**: The bank (A4) validates EVERY payment against consent parameters. The TPP cannot exceed bounds — the bank rejects.
- **Consent immutability**: Parameters cannot be changed after creation. To change bounds, consumer must create a new consent (with SCA).
- **Audit trail**: Every VRP payment logged with consent reference, parameter check result, and cumulative counters at time of execution.
- **Payee restriction**: VRP consent is locked to a specific payee account. TPP cannot redirect payments to a different beneficiary.
- **Fraud integration**: VRP payments still go through A5 fraud scoring. A sudden burst of max-amount payments near consent expiry could trigger anomaly detection.
