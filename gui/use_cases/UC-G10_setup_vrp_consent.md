# UC-G10 — Set Up a VRP (Variable Recurring Payment) Consent

*Owner: Frontend / Payments / PSD2*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

The customer grants a Variable Recurring Payment (VRP) consent to a TPP (third-party provider) or to a specific merchant. VRP allows multiple future payments within pre-agreed bounds (per-payment max, cumulative cap over a period, frequency cap, payee). Unlike AvtaleGiro, VRP can be used cross-border and is the preferred model under Open Banking. The consent parameters are immutable after creation; changes require revoking and re-granting.

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A4 (PSD2 consent management), A3 (VRP execution), identity service (SCA), the TPP (arriving via OAuth redirect for TPP-initiated case)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | |
| Client Web | yes | |
| Client iOS | yes | |
| Client Android | yes | |

## 4. Preconditions

- Customer is authenticated with `consents:manage` + `payments:initiate`.

## 5. Postconditions

- A VRP consent exists in A4 with the immutable parameters and `status=ACTIVE`.
- A dynamic-linking proof is bound to the consent envelope (not to any specific payment; each drawdown re-verifies against the consent).
- Audit event `consent.vrp.created`.
- Inbox notification.

## 6. Main flow (happy path)

### 6.1 Customer-initiated (self-service without a TPP)

1. Customer navigates to **Tillatelser / Consents** → **Opprett VRP / Create VRP**.
2. Form fields (all required):
   - Payee: creditor name + IBAN (or a saved beneficiary)
   - Per-payment max (amount + currency)
   - Cumulative cap (amount) per "period" (select: daily / weekly / monthly / quarterly)
   - Max frequency (number of payments per period)
   - Validity: start date, end date (max: today + 24 months)
   - Purpose: free-text, max 140 chars (included in each drawdown's payment reference)
3. GUI runs VoP on the payee.
4. Customer reviews a summary page with explicit bounds visualised ("Maks 500 kr per betaling × maks 4 per måned = maks 2.000 kr per måned").
5. `POST /api/v1/customer/consents/vrp` with the payload (+ idempotency key).
6. A4 responds with `PENDING_SCA`; SCA (UC-G51) runs with dynamic linking to `{payee, perPaymentMax, cumulativeCap, frequencyMax, periodType, validUntil}`.
7. On success, consent is ACTIVE; confirmation screen shows the full parameter set and a "Gå til tillatelser / Go to consents" link (UC-G18).

### 6.2 TPP-initiated (redirect flow)

1. TPP app redirects customer to `GET /oauth2/authorize?scope=vrp-consent&client_id=<tpp>&...` with pre-populated VRP envelope in request parameters.
2. Customer authenticates (UC-G01) if not already.
3. A4's consent screen renders the proposed envelope (same summary as 6.1 step 4) — not editable; customer can only approve or deny.
4. If approved, SCA runs (same as 6.1 step 6).
5. On success, OAuth redirect back to the TPP with the consent ID in the token's `consent_id` claim.

## 7. Alternative flows

### AF-01 — Customer reduces bounds on a TPP-proposed envelope

1. In TPP-initiated flow, the "approve or deny" screen does not allow edits — this is by BerlinGroup spec.
2. If the customer wants a tighter bound, they deny and ask the TPP to propose a narrower envelope.

### AF-02 — VoP CLOSE_MATCH / NO_MATCH on payee

1. Same as UC-G05 VoP handling. NO_MATCH override is allowed only in the customer-initiated flow; TPP-initiated flow requires a clean MATCH or CLOSE_MATCH.

### AF-03 — Cumulative cap less than per-payment max (invalid envelope)

1. Inline validation; submission blocked.

### AF-04 — Validity end beyond 24 months

1. Per PSD2 RTS Art. 10 (re-auth after 180 days for AIS; for VRP, the bank policy is max 24 months). Inline validation.

### AF-05 — SCA timeout / failure

1. Same as UC-G51 failure paths; consent in `PENDING_SCA` is garbage-collected after 10 minutes.

### AF-06 — TPP is suspended (e.g., regulator revoked licence)

1. `GET /oauth2/authorize` rejected by identity service (list of suspended TPPs). Customer sees "Denne leverandøren er ikke godkjent til å be om tillatelse nå."

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 5xx on POST | Response | Retryable with same idempotency key |
| Duplicate consent for same TPP+payee envelope | A4 409 `duplicate-consent` | GUI offers to open existing instead (UC-G18) |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F43`, `CLI-F44`, `GUI-SEC-08`
- `system_requirements.md` — A4-F40..F47 (VRP)

## 10. API operations involved

- `POST /api/v1/customer/consents/vrp` (`customer-consents.yaml`)
- `GET  /api/v1/customer/consents/vrp` / `/{id}` (used by UC-G18)
- `GET  /oauth2/authorize` (TPP-initiated; see `identity.yaml`)

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `.../vrp/__tests__/envelope-validator.test.ts`, `summary-copy.test.tsx` | `.../Tests/VrpEnvelopeTests.swift` | `.../vrp/VrpEnvelopeTest.kt` |
| Component | `.../vrp/__tests__/vrp-create.test.tsx`, `vrp-tpp-consent.test.tsx` | `.../Tests/VrpCreateViewTests.swift` | `.../VrpCreateScreenTest.kt` |
| Contract | `.../vrp.pact.test.ts` | `.../VrpPactTests.swift` | `.../VrpPactTest.kt` |
| E2E | `client-web/e2e/vrp-create.spec.ts`, `vrp-tpp.spec.ts` | `.../UITests/VrpCreateUITests.swift` | `.../androidTest/.../VrpCreateE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/vrp_monthly_500nok.json`, `vrp_tpp_redirect_approve.json` | (same) | (same) |

## 12. UX notes

- The bounds summary is rendered as a sentence (natural-language) *and* as a structured table. Users retain the bounds better when they see both.
- The immutability is stressed in copy: "Når du har gitt samtykket kan parametrene ikke endres. For å justere beløpet må du revokere og opprette en ny."
- The TPP-initiated consent screen shows the TPP's verified display name and logo from the eIDAS certificate (or a clear "uverifisert TPP" notice if unverified).

## 13. Open questions

- Whether to support mid-flight consent bumping (increase cap) by issuing a separate "amendment" consent that extends the first. *Defer to Phase-1 review; not in MVP.*
