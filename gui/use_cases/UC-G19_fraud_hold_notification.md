# UC-G19 — Receive a Fraud-Hold Notification

*Owner: Frontend / Notifications / Fraud*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

One of the customer's payments is in `HELD` state because A5 fraud scoring returned `REVIEW`. The customer is informed via inbox (always), email/SMS/push (per preferences), and an in-app badge. The customer can view the held payment's detail, understand the SLA (case must be decided within 1 hour), but cannot lift the hold themselves. Once the case is decided (UC-G31..G33 on the operator side), a follow-up notification delivers the outcome (SETTLED or REJECTED).

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A5 (fraud scoring), notifications service (per gui_requirements §6.6), push adapters (APNs/FCM)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | Admin equivalent is UC-G31 |
| Client Web | yes | Inbox + email |
| Client iOS | yes | Inbox + push + email/SMS per preferences |
| Client Android | yes | Inbox + push + email/SMS per preferences |

## 4. Preconditions

- Customer initiated a payment via UC-G05 / UC-G06 / UC-G11 / UC-G13.
- A5 returned `REVIEW` for the instruction; A2 transitioned the payment to `HELD`.
- Notification service received `payment.status-updates` with `status=HELD` and a fraud-case reference.

## 5. Postconditions

- Customer has received the notification in their preferred channels.
- Inbox row exists; email/SMS/push delivered (or delivery attempt logged if customer had those channels off).
- When the case is decided, customer receives the follow-up notification.

## 6. Main flow (happy path)

1. A5 publishes `fraud.decision-request` (existing contract) and attaches a `caseId` to the payment's status event.
2. Notification service subscribes to `payment.status-updates`; on seeing `HELD`, it composes the notification:
   - **Title:** "Betalingen din er til vurdering" / "Your payment is under review"
   - **Body:** "Din betaling på NOK 1.234,56 til Ola Nordmann er midlertidig holdt for en rutinekontroll. Vi sier fra så snart vi har en avgjørelse — normalt innen 1 time." / "Your payment of NOK 1,234.56 to Ola Nordmann is temporarily on hold for a routine check. We'll notify you as soon as we have a decision — usually within 1 hour."
   - **Deep link:** to the payment detail (UC-G03) with the HELD banner visible.
3. Delivery:
   - Inbox: always. New row at top, unread badge.
   - Email: if enabled in preferences (`CLI-F81`).
   - SMS: if enabled and customer has verified phone.
   - Push: if enabled and customer has registered a device (CLI-F82).
4. Customer opens the notification → deep link lands on payment detail.
5. Payment detail shows:
   - HELD badge with SLA countdown ("Vurderes — forventet svar innen 47 minutter")
   - Canonical fraud-hold copy (`CLI-F83`)
   - Transaction summary
   - **Art. 22 disclosure** (Phase 1 FG.1 / GUI-PRV-06). A distinct paragraph in the canonical i18n copy: "Denne avgjørelsen tas delvis av et automatisk system. En saksbehandler gjennomgår alle hold. Hvis du ønsker en grundigere manuell gjennomgang eller vil bestride resultatet, kontakt support med referanse `<caseRef>`." / "This decision is made in part by an automated system. A case worker reviews every hold. If you would like a deeper human review or wish to contest the outcome, contact support with reference `<caseRef>`."
   - A "Kontakt support / Contact support" button that deep-links into the support form with the reference pre-filled.
   - No buttons that would let the customer themselves lift the hold.
6. On case decision:
   - **DISMISSED** → A5 publishes `fraud.decision-response dismissed`; A2 transitions to next step (settlement). Notification service composes "Betalingen din går videre" / "Your payment is proceeding" with the new ETA. On eventual `SETTLED`, a standard completion notification replaces.
   - **CONFIRMED_FRAUD** → A5 publishes `fraud.decision-response confirmed-fraud`; A2 transitions to `REJECTED reason=FRAUD_CONFIRMED`. Notification service composes "Betalingen kunne ikke gjennomføres" / "Your payment could not be completed" with a support reference; the customer's account freeze (if any) is covered by UC-G20-style sanctions-block copy adapted for fraud.

## 7. Alternative flows

### AF-01 — Customer opts out of all channels except inbox

1. Inbox delivery only; email/SMS/push skipped; adapter logs `suppressed-by-pref`.

### AF-02 — Customer has no verified phone

1. SMS skipped; notification service records `sms-skipped-no-phone`.

### AF-03 — Case SLA breached (> 1 h without decision)

1. Notification service sends a follow-up at T+60 min: "Behandlingen tar lengre tid enn forventet — vi kommer tilbake."
2. At T+2h, escalates to on-call (internal, no customer notification).

### AF-04 — Customer has the app open on the payment detail when the case is decided

1. SSE stream updates the payment detail in real-time; the notification arrives in parallel but visually de-duplicated (inbox row marked read immediately).

### AF-05 — Push token invalid (device was uninstalled)

1. APNs/FCM returns invalid-token; notification service marks the device expired and retries email. Inbox always delivered.

### AF-06 — Customer revokes notification preferences mid-case

1. Ongoing case notifications stop on subsequent channel changes, except inbox which remains.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Email adapter 5xx | Adapter | Retry 3 times with backoff; then drop (other channels still deliver) |
| SMS adapter rate-limit | Adapter | Queue + backoff |
| Push adapter offline | Adapter | Retry queue; on prolonged outage, customer still gets inbox + email |
| Kafka lag on `payment.status-updates` | Monitoring | Operations alert; customer notification delayed but eventually delivered |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F80`, `CLI-F81`, `CLI-F82`, `CLI-F83`, `GUI-RES-04`
- `system_requirements.md` — A5-F*, A2-F* (HELD path)
- `PROJECT_DECISIONS.md` — D7 (notification adapters)

## 10. API operations involved

- `GET  /api/v1/customer/notifications` — inbox
- `PATCH /api/v1/customer/notifications/{id}` — mark read
- `GET  /api/v1/customer/notifications/stream` — SSE for inbox updates
- `POST /api/v1/customer/notifications/devices` — register push token (see CLI-F82)

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `notifications/src/test/.../FraudHoldComposerTest.java`, `.../inbox/__tests__/fraud-hold-row.test.tsx` | `.../Tests/FraudHoldRowTests.swift` | `.../inbox/FraudHoldRowTest.kt` |
| Component | `.../inbox/__tests__/notification-detail.test.tsx` | `.../Tests/NotificationDetailViewTests.swift` | `.../NotificationDetailScreenTest.kt` |
| Contract | `.../notifications.pact.test.ts` | `.../NotificationsPactTests.swift` | `.../NotificationsPactTest.kt` |
| E2E | `client-web/e2e/fraud-hold-notification.spec.ts` | `.../UITests/FraudHoldUITests.swift` | `.../androidTest/.../FraudHoldE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/fraud_hold_notification_dismissed.json`, `fraud_hold_notification_confirmed.json` | (same) | (same) |

## 12. UX notes

- The fraud-hold copy avoids the word "mistanke / suspicion" — banking regulators require neutral language; sounding accusatory confuses customers and triggers unnecessary support contacts.
- Inbox icon: a neutral clock, not a warning triangle; the hold is routine for the system even if unusual for the customer.
- SLA countdown on the detail is read-only; it does not animate pulsingly (non-distracting, `GUI-A11Y-*`).

## 13. Open questions

- Whether to proactively email the customer a "case closed" summary even when they've opted out of email for transactional notifications. *Decision: yes for terminal outcomes — this is more like a final receipt than an ongoing ping.*
