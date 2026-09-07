# UC-G38 — Payment Return (pacs.004)

*Owner: Frontend / Admin / Operations*
*Status: DRAFT*
*Implementation status: Admin Web ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

An OPERATIONS operator initiates a pacs.004 payment return — undoing a settled credit transfer by sending the funds back to the original debtor. Returns are used for cases like wrong-account crediting, customer-requested refund of a fraudulent incoming, or account-closure rejections. The return itself becomes a new payment; the original remains SETTLED but is linked to the return.

## 2. Actors

- **Primary:** OPERATIONS
- **Secondary:** A2 (clearing), A1 (reservations + debit on the original creditor account)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes | |

## 4. Preconditions

- Operator has `payments:investigate` scope.
- The payment to be returned is SETTLED (pending-state payments use cancel or pacs.028 flows — UC-G37).

## 5. Postconditions

- A new pacs.004 return payment exists, linked to the original via `originalPaymentId`.
- Original payment has a `returned=true` flag, with the return-payment ID.
- Audit `payment.return.initiated`.

## 6. Main flow (happy path)

1. From UC-G37 detail or UC-G35 transaction history, operator taps **pacs.004-retur**.
2. Modal:
   - Original payment summary (read-only).
   - Return reason dropdown: `ACCOUNT_CLOSED`, `CUSTOMER_REQUEST`, `FRAUD_REFUND`, `OTHER`.
   - Amount (defaults to original amount; can be less for partial returns — backend enforces ≤ original).
   - Notes ≥ 50 chars.
   - Confirm checkbox: "Jeg har kontrollert at midler finnes på kreditkontoen og at retur er riktig."
3. Step-up SCA with dynamic-linking `{originalPaymentId, action:"RETURN", amount}`.
4. Backend `POST /api/v1/admin/payments/{paymentId}/return` — A2 constructs pacs.004, A1 reserves amount on the original creditor account, dispatches to correspondent.
5. Return appears as a new payment in the system, linked bidirectionally.
6. UI shows confirmation with the return payment's ID and a link to monitor it (SSE stream as with any outbound instant payment).

## 7. Alternative flows

### AF-01 — Creditor account has insufficient balance for return

1. A1 reservation fails; backend 409 `insufficient-funds-for-return`.
2. UI explains and suggests contacting the customer first.

### AF-02 — Original was already returned

1. Button disabled (the original has `returned=true`); tooltip shows the link to the return.

### AF-03 — Partial return

1. Operator enters a lower amount; notes must justify the partial nature.

### AF-04 — Correspondent rejects the return

1. Return payment transitions to `REJECTED`; original payment's `returned` flag is cleared; audit logs the failure; operator is notified.

### AF-05 — Debtor-side (where we received and credited in error)

1. If we were the creditor in the original and must return, same flow — creditor account is internal; debit lands via A1; outbound to the original debtor's bank.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| A1 reservation fail | 409 | AF-01 copy |
| A2 dispatch 5xx | Response | Retryable |
| SCA fails | UC-G51 | No state change |
| Race with concurrent return | 409 | Refresh; one return wins |

## 9. Related requirements

- `gui_requirements.md` — `ADM-F42`
- `system_requirements.md` — A2-F30 (return), UC-08

## 10. API operations involved

- `POST /api/v1/admin/payments/{paymentId}/return` (`admin-payments.yaml`)

## 11. Tests realising this use case

| Layer | Admin Web |
|-------|-----------|
| Unit | `admin-web/.../payments/__tests__/return-modal.test.tsx` |
| Component | shares admin payment-detail component tests |
| Contract | shares `admin-payments.pact` |
| E2E | `admin-web/e2e/payment-return.spec.ts`, `payment-return-insufficient-funds.spec.ts`, `payment-return-partial.spec.ts` |

## 12. UX notes

- Destructive-style confirm button; default focus on Cancel.
- Linked payment rendering in UC-G35 shows "Returned on <date> by <operator>" inline, with a click-through to the return.

## 13. Open questions

- Whether to support chained returns (return of a return). *Decision: disallowed — chained corrections are a manual process by design.*
