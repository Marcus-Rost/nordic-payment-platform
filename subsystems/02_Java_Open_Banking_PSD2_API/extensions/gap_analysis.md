# Project #2: PSD2 Open Banking API — Gap Analysis

*Comparison against BerlinGroup NextGenPSD2, UK Open Banking, target company APIs, and PSD3 proposals*

---

## 1. Comparison with Target Company APIs

### DNB (developer.dnb.no)

DNB's Open Banking portal exposes PSD2-compliant APIs:
- Account Information (AISP): accounts, balances, transactions
- Payment Initiation (PISP): domestic and SEPA credit transfers
- OAuth2-based consent with SCA

**Alignment:** Our API structure mirrors DNB's. Our BerlinGroup-style endpoints match their API surface.

### SpareBank 1 (developer.sparebank1.no)

SpareBank 1 implements BerlinGroup NextGenPSD2 standard:
- AISP and PISP APIs
- OAuth2 consent with sandbox for testing
- Kotlin/Java backend

**Alignment:** Direct match. Same standard, same tech stack. Our use of Kotlin-compatible code aligns.

### Mastercard — Different Approach

Mastercard famously uses **OAuth 1.0a with body hash** (not OAuth 2.0). They published a blog post "Why Mastercard Doesn't Use OAuth 2.0" explaining their rationale: body integrity (OAuth 1.0a signs the body, OAuth 2.0 doesn't), simplicity of implementation for API clients, and backward compatibility.

**Our approach:** We use standard OAuth 2.0 (PSD2 requirement). But we add **JWE payload encryption** (inspired by Mastercard's `client-encryption-java`) as an optional defense-in-depth layer. This shows awareness of Mastercard's security philosophy without deviating from PSD2 standards.

### Tietoevry Banking-as-a-Service

Tietoevry builds BaaS APIs with Pact contract testing. Their approach:
- API-first design with OpenAPI specs
- Contract testing between consumer (TPP) and provider (bank)
- Event-driven architecture with Kafka

**Alignment:** Our Pact contract testing mirrors their practice. Our Kafka-based payment initiation matches their event-driven approach.

---

## 2. Comparison with API Standards

### BerlinGroup NextGenPSD2 (Our primary standard)

| BerlinGroup Feature | Our Status | Gap? |
|--------------------|-----------|------|
| AIS: Account list | **Have** (AISP-F01) | No |
| AIS: Balances | **Have** (AISP-F02) | No |
| AIS: Transactions | **Have** (AISP-F03) | No |
| PIS: Single credit transfer | **Have** (PISP-F01) | No |
| PIS: Periodic (standing order) | **Gap** | Not in our scope. Standing orders are a low-priority extension |
| PIS: Bulk payment | **Gap** | Not in our scope. Corporate feature |
| Consent management | **Have** (AUTH-F01 through F15) | No |
| SCA redirect | **Have** (SCA-F01 through F05) | No |
| SCA decoupled | **Gap** | Decoupled SCA (push notification to banking app instead of redirect) is an alternative approach. Not critical for portfolio |
| PIIS (Card funds confirmation) | **Gap** | PSD2 includes PIIS for card-based instruments. Out of scope for credit transfer system |
| Multi-currency accounts | **Have** (via A1) | No |
| Signing baskets | **Gap** | Group multiple payments for single SCA. Low priority |
| Standing order info | **Gap** | AISP extension. Low priority |

**Summary:** We cover the core BerlinGroup flows (AIS, PIS, consent). Gaps are advanced/corporate features (bulk, standing orders, signing baskets) that are not critical for a portfolio project.

### UK Open Banking (For Comparison)

| UK Feature | BerlinGroup equivalent | Our Status |
|-----------|----------------------|-----------|
| Account and Transaction API | AIS API | **Have** |
| Payment Initiation API | PIS API | **Have** |
| Confirmation of Funds API | PIIS | Gap (out of scope) |
| **Variable Recurring Payments** | No equivalent | **Have** (VRP-F01-F08) — differentiator |
| Event Notifications | No standard equivalent | **Partial** (we have webhooks for payment status) |
| Dynamic Client Registration | Manual registration in BerlinGroup | Gap (low priority) |

**Key insight:** Our VRP implementation (Should priority) puts us ahead of the EU standard. VRP exists in UK but not yet in the EU BerlinGroup specification. This is a genuine differentiator.

---

## 3. PSD3/PSR (Payment Services Regulation) Awareness

The European Commission proposed PSD3 and the Payment Services Regulation (PSR) in June 2023, expected to be adopted 2025-2026. Key changes:

| PSD3/PSR Change | Impact on Our System | Action |
|----------------|---------------------|--------|
| Eliminate screen scraping fallback | No impact (we're API-only) | None |
| Enhanced API performance requirements | Ensure our <300ms latency target | Already covered |
| Premium API concept | Banks can offer enhanced APIs beyond PSD2 minimum | Our VRP is a "premium API" |
| IBAN/name verification mandatory | Already implemented (A6 VoP) | Covered |
| Fraud data sharing between PSPs | We share VoP result with fraud engine — partial | Could expand |
| Open Finance (beyond payment accounts) | Savings, investments, pensions accessible via API | Future extension |
| Dashboard for consent management | We have consent list/revoke (AUTH-F11, F12) | Covered |

**Recommendation:** Document PSD3 awareness in architecture decisions but don't implement PSD3-specific features — the regulation isn't finalized yet.

---

## 4. "Exotic" Features Assessment

### Feature: JWE Payload Encryption (Optional)

**Status:** Optional per-TPP feature. When a TPP registers a public key, sensitive fields in API responses (IBANs, balances) are JWE-encrypted.
**Is this standard?** No. PSD2 does not require field-level encryption. Most implementations rely on TLS only.
**Justification:** Mastercard's `client-encryption-java` library implements exactly this pattern. Including it shows understanding of defense-in-depth: even if TLS is terminated at a proxy or load balancer, payload encryption protects sensitive data. Also protects against log leakage (encrypted fields in logs are useless to attackers).
**Verdict:** **Keep as optional.** Document the rationale clearly. Don't force it on all TPPs.

### Feature: VRP (Variable Recurring Payments)

**Status:** Should-priority feature. No EU mandate.
**Is this standard?** UK-only (CMA mandate for sweeping). Not in BerlinGroup or EU PSD2/PSD3.
**Justification:** VRP is the most innovative payment authorization pattern in Europe. Building it shows forward-thinking. It's also highly practical: replaces direct debit for subscription billing with better UX (instant, transparent, revocable).
**Verdict:** **Keep.** This is deliberately differentiating. Document it as "ahead of EU regulation, aligned with UK Open Banking trajectory."

### Feature: NAV mock-oauth2-server for Testing

**Status:** Used in integration tests instead of rolling our own mock.
**Is this standard?** Using an external open-source mock OAuth2 server is standard practice. Using NAV's specifically is a deliberate Norwegian ecosystem signal.
**Justification:** It's a well-maintained, production-quality library from the largest Norwegian public sector IT organization. Using it says "I know the Norwegian tech ecosystem."
**Verdict:** **Keep.** Strategic choice, not exotic.

### Feature: Refresh Token Family Revocation

**Status:** AUTH-F08. On reuse detection, revoke all tokens in the family.
**Is this standard?** Yes — recommended by OAuth 2.0 Security Best Current Practice (RFC draft) and Auth0/Okta documentation. Not exotic at all.
**Verdict:** **Keep.** This is security best practice, not exotic.

### Feature: SCA Exemption Engine (4 exemption types)

**Status:** EXE-F01 through F08.
**Is this standard?** Implementing SCA exemptions is standard for any PSD2-compliant PSP. Not implementing them means every payment requires SCA, which kills conversion rates. Most banks implement at least low-value and recurring exemptions.
**Is our implementation exotic?** Having 4-5 exemption types (low-value, TRA, trusted beneficiary, recurring, MIT) is comprehensive but not unusual for a production system.
**Verdict:** **Keep.** Standard practice, well-implemented.

---

## 5. Features Others Have That We Don't

| Feature | Who Has It | Priority | Recommendation |
|---------|-----------|----------|----------------|
| **Decoupled SCA** | Most production banks (push notification to app) | Medium | Document as future extension. Our redirect SCA is simpler but less UX-friendly |
| **Signing baskets** | Some BerlinGroup implementations | Low | Group multiple payments for single SCA. Corporate feature |
| **Bulk payment initiation** | BerlinGroup spec, corporate banking | Low | Out of scope for our consumer-focused API |
| **Standing order management** | BerlinGroup spec | Low | AISP extension |
| **Dynamic Client Registration** (DCR) | UK Open Banking | Low | Auto-register TPPs via API instead of manual. Nice but not critical |
| **Financial data beyond payments** | PSD3 Open Finance proposal | Very Low | Savings, investments, pensions. Future evolution |
| **Consent extension/renewal** | Some implementations | Medium | Extend consent beyond 90 days without full re-authentication. Nice UX |
| **Rich payment status webhooks** | Some modern APIs | Medium | Push payment status changes to TPP instead of polling. We have Kafka internally but no webhook to TPP for payment status |

**Top recommendation:** Add payment status webhooks to TPPs (already have the internal Kafka events, just need an outbound webhook dispatcher). Medium effort, high value.

---

## 6. Summary

| Category | Assessment |
|----------|-----------|
| **PSD2 compliance** | Strong. Core AISP/PISP/consent/SCA all covered. Gaps are advanced features (bulk, standing orders, signing baskets) |
| **Target company alignment** | Excellent. BerlinGroup matches DNB/SpareBank1. JWE matches Mastercard. Pact matches Tietoevry. mock-oauth2-server matches NAV ecosystem |
| **Innovation** | VRP is genuinely differentiating (no EU equivalent). SCA exemption engine is comprehensive |
| **Exotic features** | All justified. JWE is optional Mastercard-inspired defense-in-depth. VRP is deliberate innovation. mock-oauth2-server is ecosystem signal |
| **Key gap** | Payment status webhooks to TPPs. Internal events exist (Kafka) but no outbound push notification |
