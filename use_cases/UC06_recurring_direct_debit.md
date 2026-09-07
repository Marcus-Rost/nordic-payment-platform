# UC-06: Recurring Direct Debit (AvtaleGiro Pattern)

*Sub-systems involved: A3 (Bill Payment), A2 (Clearing), A1 (Account Ledger)*
*Mirrors: Mastercard Oslo AvtaleGiro — Norway's direct debit system*

---

## Primary Actor
**Consumer** (debtor) pre-authorizes a merchant to debit their account on a recurring schedule

## Preconditions
- Consumer has an active account in A1
- Merchant is registered with A3

## Trigger
Consumer creates a recurring authorization (mandate) for a merchant

---

## Main Success Scenario

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | Consumer | POST `/api/v1/mandates` with `{merchant_id, debtor_account, max_amount, frequency: MONTHLY}` | A3 creates mandate (status: ACTIVE). Consumer can set max single amount (e.g., NOK 5,000) |
| 2 | Merchant | POST `/api/v1/invoices` with `{debtor_account, amount: 1,299.00, mandate_id}` and `auto_approve: true` | A3 validates: mandate exists, is ACTIVE, amount <= max_amount. Auto-approves invoice (no consumer interaction needed) |
| 3 | — | Same flow as UC-01 steps 3-11 | Payment clears, settles, merchant notified via webhook |
| 4 | — | Next month: merchant submits another invoice against same mandate | Same flow. Consumer is never asked to approve (auto-approved via mandate) |

## Alternative Flows

### AF-01: Amount Exceeds Mandate Limit
| Step | Condition | System Response |
|------|-----------|----------------|
| 2a | Invoice amount NOK 6,500 > mandate max NOK 5,000 | A3 rejects auto-approval. Invoice created with status PENDING (consumer must manually approve) |

### AF-02: Consumer Revokes Mandate
| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| — | Consumer | DELETE `/api/v1/mandates/{id}` | A3 sets mandate status=REVOKED. Future invoices from this merchant cannot be auto-approved |

### AF-03: Consumer Modifies Mandate
| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| — | Consumer | PATCH `/api/v1/mandates/{id}` with `{max_amount: 3000}` | A3 updates mandate limit. Future invoices above NOK 3,000 require manual approval |

---

## Data Requirements

**Mandate entity:**
```
mandate_id:         UUID
consumer_id:        String (account holder)
debtor_account:     String (IBAN)
merchant_id:        String
max_single_amount:  BigDecimal (nullable = unlimited)
currency:           String (ISO 4217)
frequency:          Enum (WEEKLY, MONTHLY, QUARTERLY, ANNUAL, ON_DEMAND)
status:             Enum (ACTIVE, SUSPENDED, REVOKED)
created_at:         Instant (UTC)
revoked_at:         Instant (nullable)
```

## Security Considerations
- Mandate creation requires consumer OAuth2 token with `mandates:create` scope
- Mandate modification/revocation requires consumer authentication
- Merchant cannot modify mandates — only the consumer who created them
- All auto-approved debits logged with mandate reference in audit trail
- Consumer receives notification (webhook or via consumer API) when an invoice is auto-approved

---

## Comparison: AvtaleGiro (Our Model) vs. SEPA Direct Debit (SDD)

Our system implements the Norwegian AvtaleGiro pattern (push-based mandates). The pan-European SEPA Direct Debit is a different mechanism. Key differences:

| Aspect | Our Mandates (AvtaleGiro) | SEPA Direct Debit (SDD) |
|--------|--------------------------|------------------------|
| **Direction** | Push: consumer's bank auto-accepts a payment request and initiates credit transfer | Pull: creditor's bank debits the consumer's account directly |
| **Who initiates** | Consumer's bank pushes (after auto-accepting merchant's request) | Creditor pulls (sends collection file to their bank, which debits consumer's bank) |
| **Refund rights** | None specified (consumer consented via mandate) | **SDD Core: 8 weeks unconditional** refund (consumer can reverse ANY debit within 8 weeks, no questions). 13 months if no valid mandate. **SDD B2B: no refund** |
| **R-transactions** | Not modeled | Reject (before settlement), Return (after), Refund (consumer-initiated), Reversal (creditor-initiated), Revocation |
| **Pre-notification** | Not required (consumer sees request in pending list) | **14 days** before collection (creditor must notify debtor) |
| **Sequence types** | Not modeled | FRST (first), RCUR (recurring), FNAL (final), OOFF (one-off) |
| **ISO 20022** | Not modeled (our mandates are REST API-based) | pain.008 (CustomerDirectDebitInitiation), pacs.003 (FIToFICustomerDirectDebit) |
| **Safety model** | Safer: consumer's bank always validates against mandate. Merchant cannot debit without bank approval | Riskier: creditor can debit without real-time consumer approval (hence the refund rights) |

### Why we chose the AvtaleGiro model (not SDD):

1. **Safer by design**: Our push-based model means the consumer's bank always validates the mandate parameters before releasing funds. In SDD, the creditor initiates the debit and the consumer relies on refund rights for protection.
2. **Norwegian market alignment**: AvtaleGiro is the Norwegian standard. Mastercard Oslo operates it. For a portfolio targeting Norwegian employers, this is the right choice.
3. **Works with instant payments**: Our mandates trigger instant credit transfers on auto-accept. SDD is batch-only (D+1/D+2 settlement).
4. **Simpler implementation**: No R-transaction handling, no refund rights logic, no pre-notification scheduling.

### Future extension: SEPA Direct Debit support

If the system needs to support SEPA Direct Debit in the future (pan-European reach), it would require:
- New sub-system or module for SDD collection processing
- Mandate management with signed mandates (paper/electronic)
- Refund handling: 8-week unconditional refund for SDD Core, 13-month unauthorized refund
- R-transaction processing (Reject, Return, Refund, Reversal, Revocation)
- ISO 20022 pain.008 message handling
- Pre-notification scheduling (14 days before collection)
- SDD B2B variant (no refund rights, for corporate payments)

This is documented as a potential UC-11 in the gap analysis.
