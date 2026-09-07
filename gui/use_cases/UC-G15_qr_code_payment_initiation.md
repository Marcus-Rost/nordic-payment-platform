# UC-G15 — QR-Code Payment Initiation

*Owner: Frontend / Payments*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

The customer initiates a payment by scanning an EPC069-12 QR code (SEPA QR payload) with their phone camera, or on web by uploading a QR image or pasting the raw payload. The QR payload pre-fills the payment form (creditor name, IBAN, amount, currency, reference). The customer reviews and submits following UC-G05's remaining flow (VoP, SCA, status).

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A4 (payment endpoint), A6 (VoP), subsequent services of UC-G05

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | |
| Client Web | yes | Upload or paste only — browsers lack camera-to-QR in a portable way; file input / paste area |
| Client iOS | yes | Native camera via `AVCaptureMetadataOutput`; QR scanner screen with flashlight toggle |
| Client Android | yes | CameraX + ML Kit Barcode Scanning; same UX |

## 4. Preconditions

- Customer is authenticated with `payments:initiate`.
- Mobile: camera permission granted. If not granted, GUI prompts for permission with a rationale ("Vi bruker kameraet kun når du skanner et betalingssymbol.") on first use.

## 5. Postconditions

- A prefilled payment form is shown; the customer has reviewed and can edit any field before SCA.
- From here, UC-G05 (instant) or UC-G06 (batch) applies.

## 6. Main flow (happy path)

1. Customer taps **Skann / Scan** on the payment tab (mobile) or **Opplastingskode / Upload QR** (web).
2. Mobile: camera viewfinder opens with a scanning frame. Customer points at the QR code. On detection, the payload is extracted.
3. Web: customer uploads an image or pastes text in the dialog.
4. GUI parses the EPC069-12 payload:
   ```
   BCD
   002
   1
   SCT
   <BIC>
   <Beneficiary name>
   <IBAN>
   <amount e.g. EUR12.34>
   <reference type>
   <reference>
   <information>
   ```
5. GUI validates each field locally:
   - IBAN mod-97
   - Amount > 0 and currency supported
   - Reference per `reference type` (KID mod-10/11 if type indicates)
6. GUI opens the payment form (UC-G05 step 2) with all fields pre-filled. A blue banner at the top announces "Utfyllt fra QR / Pre-filled from QR".
7. Flow continues with UC-G05 from step 3 (VoP auto-runs on the pre-filled IBAN+name).

## 7. Alternative flows

### AF-01 — Non-EPC QR (unrelated QR code)

1. Parser fails (missing `BCD` header or bad line count).
2. GUI shows "Denne QR-koden er ikke en betaling." / "This QR code is not a payment." with a Retry action.

### AF-02 — EPC QR with missing amount

1. EPC QR specifies amount is optional. If absent, GUI pre-fills everything else and leaves amount blank for the customer to enter.

### AF-03 — EPC QR with unsupported BIC

1. If the BIC resolves to a non-SEPA country or a country outside the supported set (see A2-F02), GUI refuses and shows copy explaining which countries are supported.

### AF-04 — Camera permission denied

1. GUI shows a permission-denied screen with a button to open system settings and a "Last opp bilde / Upload image" fallback (iOS PhotoKit / Android `Intent.ACTION_GET_CONTENT`).

### AF-05 — Low-light / blurry / unreadable QR

1. Viewfinder displays "Holder kameraet 15–20 cm fra QR-koden." / "Hold the camera 15–20 cm from the QR code."
2. After 10 s without detection, GUI offers the manual "Tast IBAN / Enter IBAN" fallback.

### AF-06 — Multiple QR codes in frame

1. GUI picks the largest / centred one and ignores the rest.

### AF-07 — Tampered QR (payload signed but signature invalid — future spec)

1. EPC069-12 v3 signature check fails. GUI refuses with "Betalingskode er ikke gyldig." / "This payment code is invalid." *Requires signature-support flag; not in MVP.*

### AF-08 — Customer scans a QR while offline (mobile)

1. QR decode works offline.
2. Form opens but VoP is disabled ("Nettverksforbindelse mangler for verifisering"); customer can review the pre-fill but cannot continue until online.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Malformed payload | Parser | AF-01 copy |
| Amount > account balance | Pre-validated from pre-fill | Soft warning on form; submission still goes through UC-G05 balance check |
| IBAN in payload not mod-97 valid | Parser / form validation | Refuse and AF-01 copy |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F54`, `CLI-F20..F28`, `GUI-A11Y-04` (all focusable with the manual-entry fallback)
- `system_requirements.md` — A3-F09 (QR support)

## 10. API operations involved

- None unique to this UC — reuse of UC-G05 endpoints.

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `.../qr/__tests__/epc-parser.test.ts` | `.../Tests/EpcParserTests.swift` | `.../qr/EpcParserTest.kt` |
| Component | `.../qr/__tests__/upload.test.tsx` | `.../Tests/QrScannerViewTests.swift` | `.../QrScannerScreenTest.kt` (Compose + fake camera) |
| Contract | n/a (no new backend surface) | | |
| E2E | `client-web/e2e/qr-upload.spec.ts` | `.../UITests/QrScanUITests.swift` (fixture image) | `.../androidTest/.../QrScanE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/qr_valid_instant.json`, `qr_missing_amount.json` | (same) | (same) |

## 12. UX notes

- Mobile scanner has a flashlight toggle (if hardware supports it), a 44-pt/48-dp tap target in the top-right.
- A faint grid overlay helps alignment; the detected payload briefly pulses before the transition.
- Accessibility: VoiceOver / TalkBack announces "QR detected: <amount> to <creditor>" when the pre-fill is applied; the scanner is fully usable with screen readers in fallback manual mode.

## 13. Open questions

- Whether to support GiroCode (German variant) in addition to EPC069-12. *Defer — GiroCode is a superset of EPC; future work.*
