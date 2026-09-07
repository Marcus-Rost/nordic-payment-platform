# UC-G20 — Receive a Sanctions-Block Notification

*Owner: Frontend / Notifications / Compliance*
*Status: DRAFT*
*Implementation status: Web ☐ | iOS ☐ | Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

A payment the customer attempted has been blocked because the beneficiary matched a sanctions-list entry. Per EU IPR Art. 5d and Norwegian sanctions regulations, the customer must be informed that the payment did not go through but must NOT be told the reason in accusatory terms (regulatory restriction — anti-tipping-off). The notification directs the customer to contact support with a reference. The customer cannot retry this specific payment without support intervention.

## 2. Actors

- **Primary:** Bank customer
- **Secondary:** A5 (sanctions screening), notification service

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | no | Admin handles the hit in UC-G34 |
| Client Web | yes | Inbox + email (push is avoided to reduce latency — customer needs to deliberately open) |
| Client iOS | yes | Inbox + email; push delivered with generic copy (no reason) |
| Client Android | yes | Same as iOS |

## 4. Preconditions

- Customer initiated a payment via UC-G05/G06/G11.
- A5 returned `BLOCK` with `sanctionsResult=HIT`.
- A2 transitioned the payment to `REJECTED reason=SANCTIONS_HIT`.

## 5. Postconditions

- Customer has received the notification.
- A `sanctions.customer-notified` audit event is recorded (for compliance evidence).

## 6. Main flow (happy path)

1. A5 publishes `payment.blocked` with the block reason class (internally `SANCTIONS_HIT`; customer-visible copy is deliberately generic).
2. Notification service composes:
   - **Title:** "Betalingen kunne ikke gjennomføres" / "Your payment could not be completed"
   - **Body:** Canonical copy `CLI-F84`: "Din betaling til Ola Nordmann kunne ikke behandles på grunn av regulatoriske krav. Kontakt kundestøtte for mer informasjon. Referanse: <caseRef>." / "Your payment to Ola Nordmann could not be processed due to regulatory requirements. Please contact customer support for more information. Reference: <caseRef>."
   - Copy explicitly avoids the word "sanksjon" / "sanctions" in the customer-facing string (anti-tipping-off — see Norwegian Finanstilsynet guidance and EU Regulation 2580/2001).
   - **Deep link:** to the payment detail page showing REJECTED status and the same copy.
3. Delivery:
   - Inbox: always.
   - Email: if enabled.
   - SMS: if enabled.
   - Push: if enabled; push body is even more generic ("En betaling krever din oppmerksomhet"), and the full context only appears after the customer opens the deep link authenticated.
4. Payment detail renders:
   - REJECTED badge
   - Generic copy (same as notification body)
   - **Art. 22 disclosure** (Phase 1 FG.1 / GUI-PRV-06), written to preserve anti-tipping-off: "Våre regulatoriske systemer kan holde enkelte transaksjoner automatisk. En saksbehandler vurderer hvert slikt tilfelle. Hvis du ønsker å diskutere denne avgjørelsen eller be om en grundigere manuell gjennomgang, kontakt support med referanse `<caseRef>`." / "Our regulatory systems place a hold on some transactions automatically. A case worker reviews each such case. If you would like to discuss this decision or request deeper human review, contact support with reference `<caseRef>`."
   - Copy deliberately does not mention "sanctions" or any reason class.
   - "Contact support" button that opens a support-ticket form pre-filled with the reference
   - No "Retry" button — regulation does not allow the customer to simply retry a sanctioned payment.

## 7. Alternative flows

### AF-01 — False positive (operator rules FALSE_POSITIVE in UC-G34)

1. A5 publishes `sanctions.decision false-positive`; A2 re-routes the original payment (or a resubmission).
2. Notification service sends "Betalingen er nå gjennomført" / "Your payment has now been processed" as a follow-up; customer sees the payment transition to SETTLED in the detail view.

### AF-02 — Operator confirms sanctions (CONFIRMED_SANCTION)

1. The original REJECTED state is final; no retry path.
2. Account may be subject to further action (freeze) — customer is informed via a separate notification if so.

### AF-03 — Customer tries to resubmit the same payment (doesn't know the reason)

1. The payment form runs VoP + sanctions-pre-check server-side on re-submit with the same details; A5 blocks again before any money moves.
2. Notification service de-duplicates (does not send a second notification for the same beneficiary within 1 hour).

### AF-04 — Push not allowed and email disabled

1. Inbox only. A support nudge is placed on the customer's dashboard at next login ("Du har 1 ulest melding — kontakt support hvis du lurer på noe.") until the inbox item is marked read.

### AF-05 — Payment was part of a larger batch

1. Only the one instruction is blocked; others in the batch proceed. The notification and detail refer only to this instruction.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Notification channel failure | Adapter | Inbox guaranteed; other channels retried then dropped |
| Copy template missing i18n key | Notification service | Service logs ERROR; falls back to English catalogue |
| Deep link lands on wrong payment (race with cleanup) | Detail fetch 404 | Show "Denne betalingen finnes ikke lenger — kontakt support med referanse <ref>." |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F80`, `CLI-F84`, `GUI-RES-04`, `GUI-SEC-09`-style structural enforcement of copy
- `system_requirements.md` — A5-F20..F28 (sanctions), EU IPR Art. 5d, EU 2580/2001

## 10. API operations involved

- Same inbox APIs as UC-G19.
- `GET /api/v1/customer/payments/{paymentId}` — detail view

## 11. Tests realising this use case

| Layer | Web | iOS | Android |
|-------|-----|-----|---------|
| Unit | `notifications/src/test/.../SanctionsBlockComposerTest.java` | `.../Tests/SanctionsBlockRowTests.swift` | `.../inbox/SanctionsBlockRowTest.kt` |
| Component | shares `.../inbox/__tests__/notification-detail.test.tsx` | (same) | (same) |
| Contract | shares `.../notifications.pact` | (same) | (same) |
| E2E | `client-web/e2e/sanctions-block-notification.spec.ts` | `.../UITests/SanctionsBlockUITests.swift` | `.../androidTest/.../SanctionsBlockE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/sanctions_block_notification.json`, `sanctions_block_false_positive_followup.json` | (same) | (same) |

## 12. UX notes

- The anti-tipping-off copy is locked in the i18n catalogue and gated by a dedicated lint rule: any commit that changes `sanctions.customer.*` strings requires a compliance reviewer on the PR.
- The "Contact support" button opens the support form with the reference ID already filled; the customer does not need to memorise it.
- No visual signal is used in the inbox list that differentiates sanctions-block from other REJECTED reasons — same neutral icon — precisely to avoid outing a sanctioned party via UI side-channels.

## 13. Open questions

- Whether to allow the customer to start a support chat from the notification (mobile only — web links to email support). *Defer to support-channel integration.*
