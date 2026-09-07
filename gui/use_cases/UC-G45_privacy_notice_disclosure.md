# UC-G45 — Privacy Notice Disclosure

*Owner: Frontend / Privacy / Legal*
*Status: DRAFT*
*Implementation status: Admin Web ☐ | Client Web ☐ | Client iOS ☐ | Client Android ☐*
*Last updated: 2026-04-24*

---

## 1. Purpose

On first app open (or first login to the identity service), and whenever the privacy notice materially changes, the customer sees the privacy notice, scrolls to the bottom, and explicitly accepts it. The accepted version is recorded in the consent log (UC-G26) and persisted server-side. The customer can re-read the notice from Settings → Privacy anytime. This realises GDPR Articles 13 and 14 and satisfies the regulatory obligation that the customer is informed about the processing at the point of collection.

Phase 1 finding reference: FG.2 (Art. 13/14 privacy notice had no UC).

## 2. Actors

- **Primary:** Bank customer (new or existing).
- **Secondary:** Identity service (stores accepted version per user); notifications service (optional: send material-change notice by email).

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes (operator-side equivalent: staff privacy + acceptable-use) |
| Client Web | yes |
| Client iOS | yes |
| Client Android | yes |

## 4. Preconditions

- Customer has completed login (UC-G01) successfully.
- Privacy notice version is available from the server (`GET /api/v1/public/privacy-notice/latest`).

## 5. Postconditions

- `users.accepted_privacy_notice_version = <version>` in identity DB.
- Audit events `privacy.notice.displayed`, `privacy.notice.accepted` with the accepted version.
- Consent log (UC-G26) shows the acceptance event.
- Customer can proceed to the authenticated app.

## 6. Main flow (happy path)

### 6.1 Post-login check

1. On successful login (UC-G01 step 8 or similar), GUI calls `GET /api/v1/customer/privacy-notice/status`. Response:
   ```json
   {
     "currentVersion": "v1",
     "userAcceptedVersion": "v0" | null,
     "materialChangeSinceAccepted": true|false
   }
   ```
2. If `userAcceptedVersion == currentVersion`, GUI proceeds to the main app. No interstitial shown.
3. Otherwise (first-login, changed notice), GUI shows the privacy-notice interstitial (step 6.2).

### 6.2 Interstitial

1. Full-screen scrollable view titled "Personvernerklæring" / "Privacy Notice".
2. Header row: version badge ("v2"), last-updated date, "What changed" summary if `materialChangeSinceAccepted` (linked diff or summary bullets).
3. Body: rendered from `gui/i18n/privacy-notice/{version}.{locale}.md` — a signed, versioned document containing:
   - Controller identity (legal name, reg no).
   - DPO contact (email, phone).
   - Purposes (payment processing, fraud prevention, AML).
   - Legal bases per purpose (GDPR Art. 6(1)(a, b, c, f)).
   - Categories of personal data collected.
   - Recipients (processors: notifications adapters; counterparties).
   - Cross-border transfers (APNs/FCM/SMTP/SMS — cross-reference FG.8).
   - Retention periods (5-year AML; per-purpose schedules).
   - Rights summary with links to in-app self-service (UC-G24 export, UC-G25 erasure, UC-G22 rectification, UC-G18 consent revocation, UC-G26 consent log).
   - Right to lodge complaint with Datatilsynet.
   - Complete date of last update.
4. Footer: sticky action bar with two buttons — **Les mer om behandling / Read processing details** (expandable link to a longer text) and **Jeg har lest og godtar / I have read and accept**. The accept button is disabled until the user has scrolled to the bottom of the main body (detected programmatically and announced to screen readers). A small helper line: "Bla til bunnen for å fortsette" / "Scroll to the bottom to continue".
5. On accept, GUI calls `POST /api/v1/customer/privacy-notice/accept` with `{version: "<current>"}`.
6. On success, interstitial dismisses and main app loads.

### 6.3 Re-read from Settings

1. From Settings → Privacy, customer taps "Personvernerklæring".
2. GUI renders the current version (same body as 6.2) with a button "Last ned som PDF / Download as PDF" (UC-G04 pattern — signed URL to the canonical PDF rendering).
3. No re-acceptance is required since the user already accepted this version.

### 6.4 Material-change prompt (between releases)

1. When a new version is deployed with `materialChange: true`, the notifications service emits an inbox + email notification to all affected customers.
2. Next login triggers 6.2 with the "What changed" summary visible.

## 7. Alternative flows

### AF-01 — Customer declines (closes the interstitial without accepting)

1. There is no "Decline" button; the only exit path is to accept or to log out. Declining is represented by closing the app.
2. On next login, the interstitial reappears. The customer cannot access the main app without accepting.
3. This is consistent with the regulatory model: the customer's choice is to use the service under the notice or not use the service.

### AF-02 — Customer wants to delete their account without accepting a new notice

1. The Settings menu remains accessible from the interstitial with a single "Logg ut eller slett konto" / "Log out or delete account" link.
2. Clicking brings up a slim modal with two actions: Log out (UC-G53), Delete account (UC-G25). Both flows work without requiring notice acceptance.
3. Rationale: the customer must be able to exit the service without being forced to accept new terms — GDPR Art. 7(3) and consumer-protection principles.

### AF-03 — Server is unreachable (pre-login check fails)

1. If the pre-login privacy-status call fails, GUI allows login to proceed but flags "Vi kunne ikke verifisere samtykkestatus — sjekk igjen senere" / "Could not verify consent status — please check again later". A dashboard badge reminds the user. Next successful status-check triggers the interstitial.

### AF-04 — Scroll detection fails (accessibility / custom scroll container)

1. Fallback: after 10 seconds of viewing, the Accept button becomes enabled regardless (10-second minimum reading time).
2. Motivation: some assistive technologies don't trigger the expected scroll events; we should not lock the user out.

### AF-05 — Version rollback (we ship v3 then realise bug, revert to v2)

1. `users.accepted_privacy_notice_version` is preserved. The customer who accepted v3 is not asked to re-accept v2 (which they already accepted earlier).

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Privacy notice file missing for locale | GET returns 404 | Fall back to `en-US`; log; do not block flow |
| Accept POST 5xx | Response | Retry with idempotency; on persistent fail, user stuck — escalate banner + support contact |
| Version string malformed | Parse error | Fall back to "latest known"; log |

## 9. Related requirements

- `gui_requirements.md` — new `GUI-PRV-07` (added with this review).
- GDPR Art. 13, Art. 14, Art. 12 (clear language).
- Norwegian personopplysningsloven.
- FG.2 (Phase 1 fix).

## 10. API operations involved

- `GET  /api/v1/public/privacy-notice/latest` — returns `{version, publishedAt, materialChange, locales: [...], urls: {md: "...", pdf: "..."}}` (unauthenticated)
- `GET  /api/v1/customer/privacy-notice/status` — per-user acceptance status
- `POST /api/v1/customer/privacy-notice/accept` — record acceptance

All in `customer-privacy.yaml` (new spec; per FI.1 fix).

## 11. Tests realising this use case

| Layer | Admin Web | Client Web | iOS | Android |
|-------|-----------|-----------|-----|---------|
| Unit | `.../privacy/__tests__/notice-renderer.test.tsx`, `scroll-detector.test.ts` | (same) | `.../Tests/PrivacyNoticeTests.swift` | `.../privacy/PrivacyNoticeTest.kt` |
| Component | `.../privacy/__tests__/notice-interstitial.test.tsx` | (same) | `.../Tests/PrivacyNoticeViewTests.swift` | `.../PrivacyNoticeScreenTest.kt` |
| Contract | `.../privacy.pact.test.ts` | (same) | `.../PrivacyPactTests.swift` | `.../PrivacyPactTest.kt` |
| E2E | `admin-web/e2e/privacy-notice.spec.ts` | `client-web/e2e/privacy-notice.spec.ts` | `.../UITests/PrivacyNoticeUITests.swift` | `.../androidTest/.../PrivacyNoticeE2ETest.kt` |
| Cross-platform consistency | n/a | `cross-platform-tests/scenarios/privacy_notice_first_accept.json`, `privacy_notice_material_change_reaccept.json`, `privacy_notice_logout_without_accept.json` | (same) | (same) |

## 12. UX notes

- The scroll-to-bottom requirement uses a generous threshold (95% of body height) so small keyboard scroll increments don't trap users. Screen readers reach the bottom via standard navigation; the accept button becomes focus-reachable when scroll OR 10-s minimum (AF-04) is satisfied, whichever first.
- "What changed" summary uses a diff-style summary: a short bullet list of material changes, not a full redline. Full redline available via link.
- The button label uses the verb "godtar" / "accept" (not "agree") — accept is the regulatory term.
- Font size respects dynamic type (see FA.6). Long content is scrollable; the sticky footer does not overlap the body.
- Privacy notice markdown file is shipped in the app bundle and fetched from server on launch; if the server version is newer, the fetched one wins. This gives offline robustness (UC-G28).

## 13. Open questions

- Should the DPO contact in the notice be a form rather than an email? Defer — email is industry-standard; a form is nice-to-have.
- Should we require re-acceptance annually even without material change? Defer — not required by GDPR; some banks do.
- How do we handle notice updates for minors or under-guardianship accounts? Covered by FG.6 (adult-only MVP scope) — out of MVP.
