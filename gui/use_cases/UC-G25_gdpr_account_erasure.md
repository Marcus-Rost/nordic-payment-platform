# UC-G25 — GDPR Account Erasure (Customer-Initiated)

*Owner: Frontend / Identity / Compliance*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

A bank customer exercises the right to erasure under GDPR Article 17 from any of the three client variants. The flow leads through prerequisite checks (zero balance, mandate revocation acknowledgement, consent acknowledgement), informed-consent disclosure (the customer is told what is and is not erased — see §6.3), Strong Customer Authentication, and triggers the backend's existing crypto-shredding cascade across all six services and the identity service.

The mechanism (per-account AES-256-GCM keys, key deletion makes PII irrecoverable while preserving event integrity) already exists in the backend per `HIGHLIGHTS.md` "GDPR Crypto-Shredding". This use case adds the customer-facing trigger (per `D5`).

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A4 customer GDPR endpoints, A1 (zero-balance check, ledger crypto-shred), A2 (clearing residue cleanup), A3 (mandate revocation, R2P closure), A5 (case PII shred), A6 (cache eviction), identity service (account suspension), notifications service, audit-query service

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | Admin GDPR view is UC-G41 |
| Client Web | yes | |
| Client iOS | yes | |
| Client Android | yes | |

## 4. Preconditions

- Customer is authenticated with `acr=loa3` issued in last 5 minutes for this resource (re-SCA may be triggered if older).
- Customer holds the `gdpr:erase` scope.
- All accounts owned by the customer have `availableBalance == bookedBalance == 0` (server-checked at `POST /api/v1/customer/gdpr/erase`).

## 5. Postconditions (happy path)

- A `gdpr_erasure_request` row exists with status `IN_PROGRESS`, then `COMPLETED` upon cascade success.
- All per-account AES-256-GCM keys for the customer's accounts are deleted; PII columns in projection tables are zeroed; audit log entries about the customer have their PII columns zeroed; A6 cache evicted.
- All mandates are `REVOKED`; all consents are `REVOKED`; all PSD2 refresh tokens for the customer are revoked.
- The customer's identity record is `CLOSED`. The customer cannot log in again with these credentials.
- A `gdpr.erasure.completed` notification is published; an email is sent to the customer's last-known email (with a note: "this is the final email we will send you; your data has been erased except for the legally required residue").
- An `audit:gdpr.erasure.completed` event is published, signed by the operator's instance key (system-as-actor for self-service; `actor=system:gdpr-cascade`).
- The audit event itself **does not** contain the customer's PII (it carries only the request id and the customer id reference, which now has no PII associated).

## 6. Main flow

### 6.1 Entry point

1. Customer navigates to **Profile → Privacy** in the GUI (`CLI-F90` adjacent).
2. The Privacy screen lists their GDPR rights. Two prominent cards:
   - **Download my data** (UC-G24)
   - **Close account and erase my data** (this UC)

### 6.2 Prerequisite check

3. Customer taps **Close account and erase my data**.
4. GUI calls `GET /api/v1/customer/gdpr/erase/preflight`. Server returns:
   ```json
   {
     "eligible": true | false,
     "blockers": [
       {"type": "non-zero-balance", "accountId": "NO9386011117947", "balance": "1234.56", "currency": "NOK"},
       {"type": "open-r2p-pending", "requestId": "..."},
       ...
     ],
     "willRevoke": {
       "mandates": [...],
       "consents": [...],
       "trustedBeneficiaries": [...]
     },
     "willRetain": {
       "transactionEventsForYears": 5,
       "auditEventsForYears": 5,
       "explanation": "..."
     }
   }
   ```
5. If `eligible == false`, GUI shows the **Cannot proceed yet** screen with the list of blockers and inline calls-to-action ("Empty NO**...**947 to proceed", deep-linked to UC-G05 prefilled to transfer the residual amount; "Cancel pending request to <merchant> to proceed", deep-linked to UC-G12). Customer fixes blockers and re-tries.

### 6.3 Informed-consent disclosure (GUI-PRV-08, Phase 1 FG.3)

6. If `eligible == true`, GUI shows the **Informed-consent screen**, which presents the canonical i18n text in plain language. The text is versioned and its acceptance is a distinct entry in the consent log (UC-G26):
   - **What we will erase** — your name, address, fødselsnummer, email, phone, account-holder names on your accounts, your name on transaction projections, any personal notes you've stored on beneficiaries.
   - **What we will keep, and why** — the encrypted transaction events for 5 years (regulatory obligation per Hvitvaskingsloven), the encrypted audit log entries for 5 years (PCI / DORA / AML obligation). These records are stored as cipher text without the keys; **they cannot be linked back to you** and **we cannot read them**. After 5 years, they are deleted automatically.
   - **What is outside our reach** — transactions you have sent leave a record on the receiving bank and any payment-scheme intermediaries. Those records are not in our systems and we cannot erase them. The same applies to merchants you have paid: their invoicing and accounting records follow their own retention policies.
   - **In-flight payments** — any payment that is currently pending, awaiting fraud review, or waiting to clear will be dealt with before erasure completes. Payments that can be safely cancelled will be cancelled; payments already past the point of no return (e.g., already settled in a foreign bank) will complete and settle normally. You will receive a final email itemising what was cancelled and what completed.
   - **What this means for you** — your access will be permanently removed. You cannot log in again. Your mandates, consents, and trusted beneficiaries are revoked. We will send one final email confirming completion.
   - **What you should do first** — download your data (link to UC-G24), in case you want a personal copy. The export is unaffected by erasure if downloaded before submission.
   - **This service is free of charge** (per GDPR Art. 12(5); FG.11).
   - A required checkbox: "**I understand and want to proceed.**" Continue button is disabled until checked.

### 6.4 Re-SCA

7. Customer taps **Continue**. GUI checks the access token's `acr` claim and `auth_time`. If `acr != loa3` or `auth_time` is older than 5 minutes for this resource, GUI launches a fresh SCA round (UC-G51) with dynamic linking bound to the operation `gdpr.erase` and a stable identifier `customer:<sub>`.

### 6.5 Submit

8. With fresh SCA proof, GUI calls `POST /api/v1/customer/gdpr/erase` with `Idempotency-Key`.
9. Server creates the `gdpr_erasure_request` (status `PENDING`) and returns `202 Accepted` with a request id and a "track this request" URL.
10. GUI navigates to a **Tracking screen** showing the live state of the cascade:
    - Each step (A1, A2, A3, A4, A5, A6, identity) is a row with a status badge: `WAITING`, `RUNNING`, `DONE`, `FAILED`.
    - Updates arrive via SSE on `GET /api/v1/customer/gdpr/erase/{requestId}/stream`.

### 6.6 Cascade execution (server-side, summarised)

11. Server begins the cascade (existing crypto-shredding orchestration extended to be triggered by this endpoint):
    1. Identity service: suspend account login, revoke all refresh tokens, mark identity record `CLOSING`.
    2. A4: revoke all PSD2 consents and TPP refresh tokens; publish events.
    3. A3: revoke all mandates; close all open R2P (any non-terminal R2P transitions to `CLOSED_GDPR`).
    4. A6: evict cache for all of the customer's IBANs.
    5. A2: cancel any latent reservations or in-flight investigation queue items for this customer (server-checked; cascade fails fast if any active payment is in flight, and reports `customer-has-in-flight-payment` blocker — customer must wait).
    6. A1: zero-out PII fields in projection tables; emit a final compound event `AccountClosedForGdpr`; delete the per-account encryption keys (KeyProvider; in portfolio default the env-var key is overwritten with zeros and re-keyed; in production the KMS key version is destroyed).
    7. A5: zero-out customer references in `fraud_cases` (case ids retained; PII removed).
    8. Audit-query: zero PII columns in audit projection rows about this customer (the source-of-truth Kafka events also have their PII fields rendered unreadable by the same key destruction; all consumers of `audit.events` see cipher-text from now on for these entries).
    9. Identity: mark identity record `CLOSED`; delete identity-service PII (name, email, phone, fødselsnummer); retain only the user id and timestamps.
    10. Notifications: dispatch the final email; mark the user's notification preferences as `DELETED`.
12. Server marks the request `COMPLETED`. Final SSE event is sent.

### 6.7 Completion

13. Tracking screen shows all rows green; final state "Erasure complete".
14. Server returns a one-time download URL for a **Completion certificate** PDF (signed if KMS available, plain otherwise per ARCHITECTURE_DECISIONS G.7b style; the completion certificate is *not* PII — it contains the request id, the cascade timestamp, and a cryptographic attestation that the shredding was performed).
15. After 60 seconds (configurable) or upon customer interaction, GUI logs out and clears all local state.

## 7. Alternative flows

### AF-01 — Customer changes their mind on the informed-consent screen

1. Customer taps **Cancel**.
2. No request is created. GUI returns to the Privacy screen.

### AF-02 — Customer changes their mind during the cascade

1. The cascade is **not interruptible** once started (consistent state requires the cascade to complete or fail-and-rollback). GUI shows the tracking screen with **Cancellation not available** explanation.
2. Operator (UC-G41) may abort a stuck cascade in extreme cases; that triggers compensating actions.

### AF-03 — Cascade step fails

1. The failing service publishes a step-failed event to the SSE.
2. Tracking screen marks the row red with the reason.
3. Server attempts retries (idempotent steps; max 3 with backoff). If still failing, request status moves to `FAILED`. Customer is informed: "Erasure could not complete. Our compliance team has been notified and will contact you within 1 business day."
4. Operator-side workflow takes over (UC-G41).

### AF-04 — Customer attempts to log in mid-cascade

1. Identity service has already suspended login at step 1; login attempts return `403 user_locked` with a specific reason `gdpr_erasure_in_progress`.
2. GUI displays the canonical message (no retry).

### AF-05 — Customer has in-flight payments at submission

1. The `POST /erase` returns 409 with `Problem.type=customer-has-in-flight-payment` and a list of payment ids.
2. GUI shows: "You have N payments in progress. Erasure can only proceed once all are settled or returned. We'll notify you when ready." and offers a **Notify me** toggle (default on; sends an in-app notification when no in-flight payments remain).

### AF-06 — Erasure was previously requested and completed

1. The `POST /erase` returns 410 Gone (or login simply fails because the identity record is closed).
2. GUI shows the canonical message "This account has been closed under GDPR Article 17."

### AF-07 — Erasure conflicts with an open AML investigation

1. The compliance system has flagged this customer for an active investigation (this is a regulatory hold; erasure may be lawfully refused per GDPR Art. 17(3)(b)/(e) — establishment, exercise or defence of legal claims; compliance with Union law).
2. Server returns 422 with `Problem.type=erasure-blocked-aml`.
3. GUI shows a clear, neutral message: "Your erasure request cannot be processed at this time due to an ongoing legal review. We will contact you when this is resolved. Reference: <id>."
4. The request is logged for the admin GDPR queue (UC-G41).

### AF-08 — Last-known email is invalid

1. Notifications service falls back to in-app inbox only. The completion-certificate URL is shown on the tracking screen instead of being emailed.

### AF-09 — Customer is offline mid-cascade (mobile)

1. Cascade continues server-side regardless of customer connectivity.
2. On reconnect, mobile app fetches `GET /api/v1/customer/gdpr/erase/{requestId}` once and reconnects SSE.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Customer is logged out by the cascade before completion screen renders | 401 on subsequent calls | Show a neutral completion message and the certificate-download link from a temporary token issued by the cascade endpoint at start (TTL = 24 h) |
| SSE disconnects during cascade | EventSource error | Reconnect; GET status once on reconnect |
| Idempotency-Key collision (very rare) | 409 idempotency-replay | Show the previously created request's tracking screen |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F93`, `GUI-PRV-03`, `GUI-PRV-05`, `GUI-SEC-08` (re-SCA dynamic linking)
- `system_requirements.md` — GDPR section, crypto-shredding architecture
- `gui/architecture/ARCHITECTURE_DESIGN.md` — §12 (Privacy Architecture)
- `gui/PROJECT_DECISIONS.md` — D5

## 10. API operations involved

- `GET  /api/v1/customer/gdpr/erase/preflight` (`customer-gdpr.yaml`)
- `POST /api/v1/customer/gdpr/erase` (`customer-gdpr.yaml`)
- `GET  /api/v1/customer/gdpr/erase/{requestId}` (`customer-gdpr.yaml`)
- `GET  /api/v1/customer/gdpr/erase/{requestId}/stream` (SSE) (`customer-gdpr.yaml`)
- `GET  /api/v1/customer/gdpr/erase/{requestId}/certificate` (`customer-gdpr.yaml`)
- `POST /sca/start`, `POST /sca/{sessionId}/verify` (`identity.yaml`)

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `client-web/src/features/gdpr/__tests__/preflight.test.ts` | `client-ios/.../GdprPreflightTests.swift` | `client-android/.../gdpr/PreflightTest.kt` |
| Component | `client-web/.../erasure-disclosure.test.tsx`, `tracking-screen.test.tsx` | `client-ios/.../GdprViewTests.swift` | `client-android/.../gdpr/ErasureScreenTest.kt` |
| Contract (Pact) | `client-web/.../gdpr.pact.test.ts` | `client-ios/.../GdprPactTests.swift` | `client-android/.../gdpr/GdprPactTest.kt` |
| E2E | `client-web/e2e/gdpr-erasure.spec.ts` (full cascade against docker-compose) | `client-ios/.../UITests/GdprErasureUITests.swift` | `client-android/.../androidTest/.../GdprErasureE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/gdpr_erasure.json` | (same) | (same) |

## 12. UX notes

- The informed-consent text is the *only* place the customer learns the legal nuance; it must be drafted carefully and reviewed by a privacy specialist (Phase-1 review).
- The required-checkbox pattern follows the GDPR consent best practice (no pre-ticked, no implicit consent).
- The tracking screen is reassuring but not chirpy — this is a serious operation. Animation kept subdued; success state uses a quiet checkmark, not a celebration.
- The "Download my data first" reminder appears prominently on the informed-consent screen as a yellow tip; many users will only realise on submitting that this is irreversible.
- Mobile: the screen sets `FLAG_SECURE` (Android) / `isCaptured` obscuration (iOS) on the SCA step.
- The completion certificate is downloadable only once during the session; if missed, can be re-requested via support (out of scope for self-service post-erasure since the customer no longer has an account).

## 13. Open questions

- Whether to require a 24-h cooling-off period between the request and the cascade (some banks do, to prevent regret-driven submissions). *Defer to Phase-1; favour offering an opt-in cooling-off rather than mandating it.*
- Whether to record a (cipher-text only) note about the customer's reason for erasure for service-improvement analysis. *Defer; lean against — adds privacy surface for marginal benefit.*
- Whether the completion certificate should be optionally signed by the customer's chosen public key (uploaded at submission) for personal verification. *Defer; over-scope for MVP.*
