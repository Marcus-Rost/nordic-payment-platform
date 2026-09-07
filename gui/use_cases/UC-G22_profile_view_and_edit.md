# UC-G22 — Profile View and Edit

*Owner: Frontend / Profile*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

The customer views their profile (name, date of birth, fødselsnummer masked, address, contact methods) and edits the mutable subset: address, email, and phone. Immutable fields (name, DOB, fødselsnummer) come from upstream CDD and can only be changed through a bank-supervised process (out of scope). Edits to contact methods follow a confirmation loop (email link / SMS OTP) to prove control.

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A4 / identity service (profile store), notification service (sending verification emails/SMS)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | Admin uses UC-G35 for holder info |
| Client Web | yes | |
| Client iOS | yes | |
| Client Android | yes | |

## 4. Preconditions

- Customer is authenticated with `profile:read` + `profile:write`.

## 5. Postconditions

- Updated field(s) persisted after the verification loop completes.
- Audit events `profile.updated.address` / `profile.updated.email` / `profile.updated.phone` with the field, the old-value hash (for audit integrity without leaking PII), and the new masked value.

## 6. Main flow (happy path)

1. Customer navigates to **Innstillinger / Settings** → **Profil / Profile**.
2. GUI fetches `GET /api/v1/customer/profile`. Response:
   ```json
   {
     "name": "Ola Nordmann",
     "dob": "1990-05-12",
     "fodselsnummer": "•••••• ••234",
     "address": { "street":"...","postCode":"...","city":"...","country":"NO" },
     "email": { "address":"o****@example.com", "verified": true },
     "phone": { "number":"+47 •••• ••34", "verified": true },
     "language": "nb-NO"
   }
   ```
3. GUI renders a card per field group; mutable fields have a pencil icon.

### 6.1 Edit address

1. Customer taps edit on address → dialog with four fields (street, postal code, city, country — limited to a jurisdiction set: NO + EU).
2. Inline validation: postal code format per country, city length, street max 140 chars.
3. Customer saves; GUI calls `PATCH /api/v1/customer/profile` with `{address: {...}}`.
4. SCA prompt runs (UC-G51) dynamic-linking bound to `{field:"address", hash(newAddress)}`.
5. On success, profile updated; confirmation toast.

### 6.2 Edit email

1. Customer taps edit → dialog with new email input + current-password confirmation (extra step because email change is a phishing vector).
2. GUI calls `POST /api/v1/customer/profile/email` with `{newEmail}`; backend validates password (via identity service), records a pending-change row, sends a verification link to the *new* email.
3. Customer clicks the link → GUI / deep link opens a confirmation page that completes the change via `POST /api/v1/customer/profile/email/verify?token=<>`.
4. Backend updates and emits a **warning email to the old address** ("Din e-post ble endret. Hvis dette ikke var deg, ring oss umiddelbart.").
5. A SCA prompt on the device that initiated the change finalises the update.

### 6.3 Edit phone

1. Similar to email: new phone + password → SMS OTP to the new phone → enter OTP → SCA.
2. Warning SMS sent to old phone.

## 7. Alternative flows

### AF-01 — Email / phone already in use on another account

1. Backend 409 `conflict`. GUI copy: "Denne e-posten er allerede knyttet til en annen konto. Kontakt support."

### AF-02 — Verification link expires (24h)

1. GUI shows expired-link page; customer can request a new link.

### AF-03 — Customer attempts to edit fødselsnummer or DOB

1. UI has no edit affordance; tooltip explains the process requires in-person verification ("Kontakt support for å oppdatere dette feltet").

### AF-04 — Customer moves abroad (changes country to outside EU/EEA)

1. Dialog warns about consequences (CDD re-verification may be required; some services may be limited). Save still proceeds.

### AF-05 — SCA fails on address save

1. UC-G51 failure paths; profile unchanged.

### AF-06 — Customer wants to disable SMS entirely

1. Customer can set phone.verified to false via removal (`DELETE /api/v1/customer/profile/phone`) — which disables SMS channel in notification prefs automatically (UC-G21 cascade).

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| 5xx on PATCH | Response | Retryable with idempotency key |
| Verification email fails to send | Adapter error | Toast "Kunne ikke sende bekreftelse — prøv igjen" |
| Customer clicks verification link after a concurrent change | Token invalidated | Error page with support ref |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F90`, `GUI-SEC-08`, `GUI-PRV-05` (customer sees own PII safely)
- `system_requirements.md` — A4-F30 (profile), GDPR Art. 16 (rectification)

## 10. API operations involved

- `GET   /api/v1/customer/profile` (`customer-profile.yaml`)
- `PATCH /api/v1/customer/profile` — address + language
- `POST  /api/v1/customer/profile/email` — initiate change
- `POST  /api/v1/customer/profile/email/verify`
- `POST  /api/v1/customer/profile/phone`
- `POST  /api/v1/customer/profile/phone/verify`
- `DELETE /api/v1/customer/profile/phone`

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `.../profile/__tests__/address-form.test.tsx`, `email-change.test.tsx`, `phone-otp.test.tsx` | `.../Tests/ProfileEditTests.swift` | `.../profile/ProfileEditTest.kt` |
| Component | `.../profile/__tests__/profile-screen.test.tsx` | `.../Tests/ProfileViewTests.swift` | `.../ProfileScreenTest.kt` |
| Contract | `.../profile.pact.test.ts` | `.../ProfilePactTests.swift` | `.../ProfilePactTest.kt` |
| E2E | `client-web/e2e/profile-edit.spec.ts` | `.../UITests/ProfileEditUITests.swift` | `.../androidTest/.../ProfileEditE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/profile_edit_address.json`, `profile_change_email.json`, `profile_change_phone.json` | (same) | (same) |

## 12. UX notes

- Fødselsnummer mask shows the last three digits only; full reveal is not provided anywhere in the customer UI (customer can verify via GDPR export UC-G24 if they truly need to see it).
- The old-address warning emails use a consistent format across the bank's emails, with a clear "Dette var ikke meg" link that triggers an account-takeover alert to the fraud team.
- Country picker defaults to NO; ordering favours EU countries first then the rest alphabetically.
- **Screen-capture protection (Phase 1 FM.1 / GUI-SEC-05a):** profile displays name, address, masked fødselsnummer, email, phone; mobile sets `FLAG_SECURE` + iOS obscuring overlay for the profile screen and all edit dialogs.

## 13. Open questions

- Whether to support multiple addresses (billing vs. mailing). *Defer — single address is the Norwegian banking convention.*
