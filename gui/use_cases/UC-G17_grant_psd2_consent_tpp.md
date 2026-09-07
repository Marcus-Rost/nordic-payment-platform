# UC-G17 — Grant a PSD2 Consent (TPP-Initiated Redirect)

*Owner: Frontend / PSD2 / Identity*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

A third-party provider (TPP — AIS, PIS, or both) redirects the customer to the bank's identity service to authorise access to their accounts (AIS) or to initiate a payment (PIS). The customer authenticates with the bank, reviews the TPP's requested scopes and the accounts covered, and decides approve / deny. Approval requires SCA (per PSD2 RTS Art. 97/98). The resulting consent is bound to the TPP's eIDAS certificate identity and has a 180-day validity (AIS) per PSD2 RTS Art. 10 §2 (re-auth cadence).

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** TPP (as the redirect originator), identity service (consent issuance + SCA), A4 (PSD2 consent record)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | |
| Client Web | yes | Browser redirect |
| Client iOS | yes | `ASWebAuthenticationSession` — the system browser with a shared keychain so the bank's existing session is available |
| Client Android | yes | Custom Tab — same sharing |

## 4. Preconditions

- Customer is redirected to the bank's identity service with a valid OAuth2 authorization request (`client_id` of the TPP, `scope=accounts:read transactions:read payments:initiate`, `state`, `code_challenge` PKCE, `redirect_uri` matching the registered TPP URI, and optional BerlinGroup-specific params for consent envelope).
- TPP is registered and has a valid eIDAS certificate (QWAC).
- Customer has at least one account the TPP is requesting access to.

## 5. Postconditions

- If approved: A consent record in A4 is ACTIVE with `tppId`, `customerId`, `scopes`, `accountIds`, `validFrom`, `validUntil`, `psd2Role` (AISP/PISP/CBPII). An OAuth2 authorization code is issued and the customer is redirected back to the TPP's `redirect_uri`.
- If denied: The redirect back to TPP carries `error=access_denied`. No consent is created.
- Audit event `consent.psd2.granted` (or `.denied`) with scopes and TPP identity.

## 6. Main flow (happy path)

1. TPP initiates `GET /oauth2/authorize?...` in the system browser.
2. Identity service checks that the customer has an active session (via its own cookie — note this is separate from a client-GUI session). If not, it runs UC-G01 login inline.
3. Identity service renders the **consent screen**:
   - TPP verified display name + logo (from eIDAS QWAC's `organizationName`); **"Unverified"** red banner if the cert validation failed, with a prominent refusal option.
   - Requested role: AISP, PISP, or CBPII (human-readable: "see accounts", "initiate payments", "confirm funds").
   - Requested scopes, human-readable:
     - `accounts:read` → "Se kontonumrene og hvilke kontoer du har"
     - `transactions:read` → "Se siste 90 dagers transaksjoner"
     - `payments:initiate` → "Initiere betalinger fra valgte kontoer"
   - Account picker: customer selects the accounts to include (multi-select, defaults to all accounts the customer owns).
   - Validity duration (read-only for AIS — 180 days fixed by regulation; PIS — single payment; CBPII — 180 days).
   - Two buttons: **Godkjenn / Approve** (primary) and **Avslå / Deny** (secondary).
4. Customer approves.
5. Identity service triggers SCA (UC-G51). For PIS, the dynamic-linking is bound to `{amount, creditorIban, creditorName}` from the OAuth request's payment envelope; for AIS, the dynamic-linking is bound to `{consentId, tppId}` (no payment amount, but still proof of intent).
6. On SCA success, identity service:
   - Creates a `PsdConsent` row in A4 with the selected accounts and validity.
   - Issues an OAuth2 authorization code.
   - Redirects the browser to `redirect_uri?code=<code>&state=<state>`.
7. Customer's browser continues back to the TPP; the bank-side flow ends here.

## 7. Alternative flows

### AF-01 — Customer denies

1. Customer taps **Avslå**.
2. Identity service redirects with `error=access_denied&error_description=user-denied&state=<state>`.

### AF-02 — Customer deselects all accounts then taps Approve

1. Inline validation prevents submission.

### AF-03 — Customer has no accounts (new customer)

1. Consent screen shows "Du har ingen kontoer å gi tilgang til." with a **Avslå** button only. Approve is disabled.

### AF-04 — TPP cert revoked or invalid

1. Identity service redirects immediately with `error=invalid_client` before the consent screen ever renders. Customer lands on an error page at the bank with a brief explanation and a support reference.

### AF-05 — Customer is already on the bank's app (mobile) and the TPP uses app-to-app redirect

1. TPP opens the bank's app via universal link.
2. The bank's app handles the OAuth2 flow natively (not Custom Tab), preserving session state. On approval, the bank redirects back to the TPP's registered URI scheme.

### AF-06 — Concurrent SCA on the same account by another flow (rare)

1. Identity service's SCA orchestrator queues; customer sees a brief "Vent litt — en annen handling fullføres". No double-SCA.

### AF-07 — Customer wants to change accounts after approval

1. Consent parameters immutable post-creation. Customer uses UC-G18 to revoke and re-grant with a different account set (via the TPP's flow).

### AF-08 — TPP requests `payments:initiate` for an amount above customer's available balance (PIS)

1. Identity service still issues the consent; A4 rejects the actual payment at submission with `insufficient-funds`. The consent/payment decoupling means the customer isn't blocked at this screen.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| SCA failure | UC-G51 | Returns to consent screen; customer can retry |
| Customer closes the browser mid-flow | No detectable event server-side | TPP eventually sees the OAuth state expire (5 min); customer sees nothing; neutral outcome |
| Identity service backend 5xx | Response | Error page with support ref; redirect back to TPP not performed; TPP times out |
| `redirect_uri` does not match registered URIs | OAuth2 parameter validation | Identity service refuses with `invalid_redirect_uri`; renders a clear error page; no redirect to attacker-controlled URI |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F70`, `CLI-F72`, `GUI-SEC-01..02`, `GUI-SEC-08`
- `system_requirements.md` — A4-F10..F17 (PSD2 consents)
- `PROJECT_DECISIONS.md` — D2 (dedicated identity service)

## 10. API operations involved

- `GET  /oauth2/authorize` (`identity.yaml`) — entry point
- `POST /oauth2/token` (`identity.yaml`) — TPP redeems code; customer not involved
- `GET  /api/v1/customer/accounts` (identity service proxies this to render the account picker; uses the customer's session)
- SCA endpoints via identity service
- A4 internal `createPsdConsent`

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `identity-service/src/test/.../ConsentRenderingTest.java`, `client-web/.../consent/__tests__/consent-screen.test.tsx` | `.../Tests/ConsentScreenTests.swift` | `.../consent/ConsentScreenTest.kt` |
| Component | `.../consent/__tests__/tpp-consent.test.tsx` | `.../Tests/TppConsentViewTests.swift` | `.../TppConsentScreenTest.kt` |
| Contract | Pact consumer: a fake TPP calls `/oauth2/authorize` | (same) | (same) |
| E2E | `client-web/e2e/tpp-consent-grant.spec.ts` (full redirect round trip with a fake TPP) | `.../UITests/TppConsentUITests.swift` | `.../androidTest/.../TppConsentE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/tpp_ais_consent_grant.json`, `tpp_pis_consent_grant.json`, `tpp_consent_deny.json` | (same) | (same) |

## 12. UX notes

- TPP identity rendering must be unmistakable — Norwegian banking precedent (DNB, SpareBank 1) places verified TPP name, cert subject, and a locked-padlock iconography prominently. We match that convention.
- Scope descriptions use plain Norwegian language, not technical OAuth scope strings.
- Consent screen uses extra contrast and larger touch targets than most bank UIs — PSD2 consent is a high-stakes action and the UI should reflect it.

## 13. Open questions

- Whether to show the TPP's last 10 consent grants (for transparency / familiarity). *Defer — privacy of other customers' decisions complicates this; skip in MVP.*
- Embedded vs. redirect flow for PSD2: regulation allows both; we implement redirect only (clearer security model, matches AppAuth recommendations). *Locked; confirm in Phase-1 review.*
