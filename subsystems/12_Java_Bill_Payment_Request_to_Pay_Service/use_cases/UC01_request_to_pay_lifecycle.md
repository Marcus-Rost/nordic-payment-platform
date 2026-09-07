# UC-01: Request-to-Pay Full Lifecycle

*Covers: create, accept, decline, defer, amend, mandate auto-accept, QR initiation*

---

## Scenario 1A: Happy Path — Submit, Accept (Instant), Settle

| Step | Actor | Action | Response |
|------|-------|--------|---------|
| 1 | Merchant | POST `/api/v1/requests` with API key + `{creditorAccount, debtorAccount, amount: 1250.00, currency: NOK, dueDate: 2026-05-01, reference: "INV-2026-042", description: "April electricity"}` | 201: `{requestId, status: PENDING}` |
| 2 | Consumer | GET `/api/v1/requests?status=PENDING` with Bearer token | Returns list including the new request |
| 3 | Consumer | PUT `/api/v1/requests/{id}/accept` with `{paymentMethod: INSTANT}` | Request → ACCEPTED → PAYMENT_INITIATED |
| 4 | — | A6 (VoP): verify creditor name → MATCH | |
| 5 | — | Published to Kafka `payment.instant-instructions` | |
| 6 | — | A2 → A5 → A1: cleared + settled in ~3 seconds | |
| 7 | — | A3 receives SETTLED from `payment.status-updates` | Request → SETTLED |
| 8 | — | Webhook to merchant | `{requestId, status: SETTLED, settledAt: "2026-04-11T14:24:00Z"}` signed with HMAC-SHA256 |

## Scenario 1B: Decline with Reason

| Step | Actor | Action | Response |
|------|-------|--------|---------|
| 1 | Merchant | Creates request for NOK 1,250 | PENDING |
| 2 | Consumer | PUT `/api/v1/requests/{id}/decline` with `{reason: ALREADY_PAID, message: "Paid via bank transfer on April 5"}` | Request → DECLINED |
| 3 | — | Webhook to merchant | `{requestId, status: DECLINED, declineReason: ALREADY_PAID, declineMessage: "Paid via bank transfer on April 5"}` |

No payment initiated. Request is terminal.

## Scenario 1C: Defer (Pay Later)

| Step | Actor | Action | Response |
|------|-------|--------|---------|
| 1 | Consumer | PUT `/api/v1/requests/{id}/defer` with `{payDate: 2026-05-15}` | Request → DEFERRED |
| 2 | — | Webhook to merchant | `{requestId, status: DEFERRED, deferredUntil: "2026-05-15"}` |
| 3 | — | On May 15: scheduler reactivates request → status back to PENDING | |
| 4 | Consumer | Sees request again in pending list → accepts | Normal payment flow |

## Scenario 1D: Amendment (Consumer Proposes Different Terms)

| Step | Actor | Action | Response |
|------|-------|--------|---------|
| 1 | Consumer | PUT `/api/v1/requests/{id}/amend` with `{proposedAmount: 800.00, message: "Partial payment for disputed portion"}` | Request → AMENDED |
| 2 | — | Webhook to merchant | `{requestId, status: AMENDED, proposedAmount: 800.00, message: "..."}` |
| 3 | Merchant | PUT `/api/v1/requests/{id}/amendment-response` with `{accepted: true}` | Request → PENDING (with updated amount 800.00). Consumer can now accept |
| 3a | Merchant | PUT `/api/v1/requests/{id}/amendment-response` with `{accepted: false, message: "Full amount required"}` | Request → PENDING (original amount remains). Consumer must accept or decline at original terms |

## Scenario 1E: Recurring Mandate Auto-Accept (AvtaleGiro)

| Step | Actor | Action | Response |
|------|-------|--------|---------|
| 1 | Consumer | POST `/api/v1/mandates` with Bearer token + `{merchantId: "energinord", debtorAccount: "NO93...", maxSingleAmount: 5000, frequency: MONTHLY}` | Mandate created: ACTIVE |
| 2 | Merchant (next month) | POST `/api/v1/requests` with `{amount: 1847.50, mandateId: "mandate-uuid"}` and `autoAccept: true` | A3 checks: mandate ACTIVE, amount 1,847.50 <= max 5,000, same merchant → **AUTO-ACCEPTED** |
| 3 | — | Payment initiated automatically (no consumer interaction) | |
| 4 | — | Settled via clearing engine | Request → SETTLED |
| 5 | — | Notification to consumer (not approval, just informational) | "EnergiNord AS charged NOK 1,847.50 from your account (mandate #mandate-uuid)" |

### Mandate amount exceeded:
| Step | Action | Response |
|------|--------|---------|
| 2a | Merchant submits with amount 6,500 (> mandate max 5,000) | A3: auto-accept DENIED. Request created as PENDING (consumer must manually approve) |

### Mandate revoked:
| Step | Actor | Action | Response |
|------|-------|--------|---------|
| 1 | Consumer | DELETE `/api/v1/mandates/{id}` | Mandate → REVOKED |
| 2 | Merchant | Next month: submits request with mandateId | A3: mandate REVOKED → auto-accept DENIED. Request goes to PENDING |
| 3 | Consumer | Does not approve (doesn't want to pay this merchant anymore) | Request → EXPIRED after due date |

## Scenario 1F: QR Code Payment

| Step | Actor | Action | Response |
|------|-------|--------|---------|
| 1 | Merchant | POST `/api/v1/requests` → gets requestId | Request created |
| 2 | Merchant | POST `/api/v1/requests/{id}/qr` | Returns QR code as PNG (Base64) containing EPC069-12 formatted data |
| 3 | Consumer | Scans QR with banking app camera | App parses EPC069-12 data: IBAN, name, amount, reference |
| 4 | Consumer | App shows: "Pay NOK 1,250.00 to Strøm AS? [Approve] [Cancel]" | |
| 5 | Consumer | Taps Approve | App calls PUT `/api/v1/requests/{id}/accept` via backend → instant payment |

**EPC QR data (text format):**
```
BCD
002
1
SCT
DNBANOKK
Strøm AS
NO1234567890123
NOK1250.00

RF18539007547034
April electricity
```

---

## Security Considerations
- **Webhook HMAC verification:** Merchants MUST verify `X-Webhook-Signature` before processing. Without verification, an attacker could forge webhook calls to trick the merchant into believing a payment was settled
- **Mandate security:** Only the consumer can create, modify, or revoke mandates. The merchant can only submit requests against a mandate — they cannot alter the consumer's authorization
- **QR code safety:** Dynamic QR codes include the full payment details. Static QR codes only include IBAN + name (consumer enters amount). Dynamic QR should have a short TTL (e.g., 15 minutes) to prevent replay
- **Webhook payload PII:** Webhook payloads sent to merchant endpoints should NOT contain full debtor account numbers (only last 4 digits). Merchants identify their customers by reference number, not IBAN
- **Idempotency key:** Prevents the same invoice from being submitted twice. Critical for reliability — a network timeout followed by retry should not create a duplicate charge
