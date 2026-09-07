# UC-07: Account Lifecycle Management

*Sub-systems involved: A1 (Account Ledger)*
*Mirrors: Core banking account management at DNB, SpareBank 1, Tietoevry*

---

## Scenario 7A: Account Creation

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | Admin / System | POST `/api/v1/accounts` with `{holder_id, currency: NOK, account_type: CURRENT}` | A1 generates IBAN-format account number, creates account with status ACTIVE, balance 0.00. Publishes AccountCreated event |

## Scenario 7B: Account Freeze (Fraud Response)

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | Fraud system (A5) or Admin | PUT `/api/v1/accounts/{id}/freeze` with `{reason: FRAUD_INVESTIGATION}` | A1 sets account status FROZEN. All pending debits rejected. Credits still accepted (to not lose incoming payments) |
| 2 | Admin | PUT `/api/v1/accounts/{id}/unfreeze` | A1 sets account status ACTIVE. Normal operations resume |

## Scenario 7C: Multi-currency Account Query

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | TPP or Consumer | GET `/api/v1/accounts/{id}/balances` | Returns: `[{currency: NOK, available: 45230.50, booked: 45230.50}, {currency: EUR, available: 1200.00, booked: 1250.00}]`. Available vs. booked reflects pending transactions |

## Scenario 7D: Account Statement Generation

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | Consumer | GET `/api/v1/accounts/{id}/statement?from=2026-01-01&to=2026-03-31&format=json` | Returns paginated transaction list: date, amount, currency, creditor/debtor, reference, running balance |
| 2 | Consumer | GET `/api/v1/accounts/{id}/statement?from=2026-01-01&to=2026-03-31&format=camt053` | Returns ISO 20022 camt.053 XML account statement |

---

## Account States

```
ACTIVE ──── freeze ────→ FROZEN ──── unfreeze ────→ ACTIVE
   │                                                    
   └─── close ────→ CLOSED (terminal, balance must be 0)
```

## Security Considerations
- Account creation: internal/admin only (not consumer-facing)
- Account freeze: requires `accounts:admin` scope or system-to-system auth from A5
- Balance queries: consumer must own the account (OAuth2 token subject = holder_id) OR TPP with valid PSD2 consent
- Account statements contain PII: encrypted in transit (TLS) and at rest
