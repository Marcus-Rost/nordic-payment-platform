# Project #12: Bill Payment & Request-to-Pay — Gap Analysis

*Comparison against eFaktura, AvtaleGiro, SEPA Request to Pay (SRTP), Vipps bill payment*

---

## 1. Comparison with Norwegian Systems

### Mastercard Oslo eFaktura (Primary Reference)

| Feature | eFaktura (Real) | Our System | Gap? |
|---------|----------------|-----------|------|
| Electronic invoice delivery | Yes | Yes (RTP-F01) | No |
| Consumer approve/reject | Yes | Yes + decline with reason + defer + amend (richer) | **We have MORE** — SRTP evolution |
| Payment initiation on approve | Yes | Yes (instant or batch choice) | **We have MORE** — consumer chooses instant/batch |
| Merchant webhook notification | Yes | Yes (RTP-W01-W08) | No |
| Recurring (AvtaleGiro) | Yes — separate system | Yes — integrated as mandates (RTP-M01-M07) | **Integrated** vs. separate system |

**We exceed eFaktura** by adding: decline with reason codes, defer (pay later), amendment flow, instant payment choice, and integrated mandates. This is the SRTP evolution of eFaktura.

### Mastercard Oslo AvtaleGiro

| Feature | AvtaleGiro (Real) | Our Mandates | Gap? |
|---------|-------------------|-------------|------|
| Consumer authorization | Yes (mandate) | Yes (RTP-M01) | No |
| Max amount limit | Yes | Yes (max_single_amount) | No |
| Auto-debit by merchant | Yes (pull payment) | We do auto-accept + push payment (different model) | **Different model** — our mandates auto-accept requests rather than auto-debit. Same outcome, different mechanism |
| Revocation | Yes | Yes (RTP-M06) | No |
| Refund rights | Norwegian-specific (limited) | Not modeled | **Gap** — but refund is a SEPA Direct Debit concern |

**Key architectural difference:** AvtaleGiro is a pull payment (merchant debits). Our mandates auto-accept push requests (consumer's bank pushes). The outcome is the same (merchant gets paid automatically), but our model is safer (the consumer's bank always validates against mandate parameters).

---

## 2. Comparison with SEPA Request to Pay (SRTP)

| Feature | SRTP (EPC014-20) | Our System | Gap? |
|---------|-----------------|-----------|------|
| Creditor sends request | Yes (pain.013) | Yes (RTP-F01) | No |
| Debtor accepts/declines | Yes | Yes + defer + amend | **We have MORE** |
| Accept triggers SCT/SCT Inst | Yes | Yes (consumer chooses instant/batch) | No |
| Recurring R2P | In development | Yes (mandates with auto-accept) | **We're ahead** |
| QR code initiation | Discussed in EPC | Yes (EPC069-12, RTP-Q01-Q05) | **We're ahead** |
| Due date concept | Yes | Yes (due_date, auto-expiry) | No |
| Partial payment support | Yes (SRTP) | Via amendment flow (propose different amount) | **Partial** — we support it through amendment, not as a built-in "pay partial" option |
| Multi-currency | Via underlying SCT | Via A1 multi-currency ledger | No |
| Interoperability (cross-PSP) | Yes (the whole point of SRTP) | Single-system scope | Expected for portfolio |

**Assessment:** Our R2P model is a realistic implementation of SRTP with additional features (defer, amend, QR) that go beyond the current EPC specification. This positions us ahead of early SRTP adopters.

---

## 3. Comparison with Vipps MobilePay

| Feature | Vipps MobilePay | Our System | Gap? |
|---------|----------------|-----------|------|
| Bill payment (Regninger) | Yes — in-app bill display + payment | Yes — via API (no mobile UI, but same backend pattern) | Same pattern, different UI |
| Request money (P2P) | Yes — one user requests from another | Not modeled | **Gap** — we focus on merchant-to-consumer, not P2P |
| Recurring payments | Yes — subscription management | Yes — mandates (AvtaleGiro) + VRP in A4 | Same pattern |
| QR payments | Yes — in-store and online | Yes — EPC QR standard | Same pattern |
| Webhooks | Yes — event-based merchant notification | Yes — HMAC-signed webhooks | Same pattern |
| Checkout widget | Yes — embeddable payment widget | Not modeled | **Gap** — UI component. Out of scope for backend API project |
| P2P instant | Yes (Vipps-to-Vipps instant) | Our instant payment is bank-to-bank via A2 | Same outcome, different scope |

**Key difference:** Vipps is a consumer-facing mobile app with a backend. We are a backend API platform. Our APIs could power a Vipps-like mobile app, but we don't build the UI.

---

## 4. "Exotic" Features Assessment

### Feature: Decline with Structured Reason Codes (RTP-F04)

**Is this unusual?** For eFaktura, yes — traditional eFaktura is binary (approve/reject). For SRTP, no — the SRTP specification includes decline with reason.
**Justification:** We're building toward SRTP, not just eFaktura. Reason codes help merchants understand why a payment was declined (already paid vs. amount incorrect vs. not recognized). This reduces customer service costs.
**Verdict:** **Keep. SRTP-aligned, adds real value.**

### Feature: Defer (Pay Later) (RTP-F05)

**Is this unusual?** For payment systems, yes. For SRTP, it's part of the specification but rarely implemented.
**Justification:** "Pay on a specific date" is a real consumer need. Many people receive bills before payday and want to defer payment. SRTP supports this. Implementing it shows SRTP depth beyond the basic accept/decline.
**Verdict:** **Keep. Differentiating within the SRTP model.**

### Feature: Amendment Flow (RTP-F06)

**Is this unusual?** Yes — this is one of the most advanced SRTP features. Most implementations don't support it.
**Justification:** Enables dispute resolution within the payment flow. Consumer says "the amount is wrong" → proposes different amount → merchant can accept or insist. This avoids the pattern where the consumer declines and the merchant has to send a new request.
**Risk:** Adds complexity (merchant must implement amendment-response endpoint).
**Verdict:** **Keep as Should priority.** Impressive if implemented, acceptable if deferred.

### Feature: EPC QR Code Generation (RTP-Q01-Q05)

**Is this unusual?** For a payment API, yes. Most APIs don't generate QR codes.
**Justification:** The EPC QR standard (EPC069-12) exists but is underutilized. Wero/EPI are driving QR adoption. Implementing it is forward-looking and provides a bridge between online (API) and offline (printed QR on invoice).
**Verdict:** **Keep as Should. Low effort (ZXing library), high demo value.**

### Feature: Webhook HMAC-SHA256 Signing (RTP-W03)

**Is this unusual?** No — this is standard practice. Vipps MobilePay, Stripe, Mastercard all sign webhook payloads.
**Verdict:** **Keep. Standard practice, not exotic.**

---

## 5. Features Others Have That We Don't

| Feature | Who Has It | Priority | Recommendation |
|---------|-----------|----------|----------------|
| **P2P Request-to-Pay** | Vipps (request money), Swish | Low | Consumer-to-consumer payment requests. Different use case (social, not billing) |
| **Installment plan** | Klarna, Vipps Senere (BNPL) | Low | "Pay in 3" / "Pay in 30 days". Complex credit product, out of scope |
| **Invoice factoring integration** | Some billing platforms | Low | Merchant sells receivable to factoring company. Financial service, not payment infrastructure |
| **Multi-language notification** | Vipps (NO/EN/DK/FI) | Low | Webhook payloads are structured data — language is a UI concern |
| **Bulk invoice submission** | Many billing platforms | Medium | Merchant uploads CSV/XML with 1000 invoices at once. Useful for utility companies. Could add as batch API |
| **Payment reminder** | Some billing platforms | Medium | Auto-send reminder N days before due date. Simple scheduler extension |
| **Partial payment acceptance** | SRTP specification | Medium | Consumer pays 500 of 1000, with remaining 500 still pending. Our amendment flow approximates this but isn't the same. Could add as extension |
| **Direct debit initiation (SEPA SDD)** | Standard EU payment type | Medium-High | Our mandates auto-accept push payments. Real SDD is a pull payment with different refund rights. See master system gap analysis |

**Top recommendation:** Bulk invoice submission (batch API). Many Mastercard Oslo / eFaktura merchants submit thousands of invoices monthly. A batch endpoint (`POST /api/v1/requests/batch` with NDJSON or CSV body) would be realistic and not difficult to implement.

---

## 6. Summary

| Category | Assessment |
|----------|-----------|
| **eFaktura alignment** | We exceed eFaktura with SRTP features (decline reasons, defer, amend, QR). This is the right direction |
| **SRTP alignment** | Strong. We implement core SRTP flows + ahead on QR and recurring. Amendment is the most advanced feature |
| **Target company alignment** | Excellent. Mirrors Mastercard eFaktura/AvtaleGiro. API surface matches what DNB/SpareBank1 mobile apps consume. QR matches Vipps pattern |
| **Exotic features** | Amendment is the most unusual — justified as SRTP-compliant and impressive. Defer and QR are forward-looking but well-motivated |
| **Key gap** | Bulk invoice submission. Realistic for utility/telecom billing use cases. Medium effort |
