# UC-01: Batch Clearing Cycle and Instant Payment Processing

---

## Scenario 1A: Batch Clearing Cycle (Happy Path)

| Step | Actor | Action | Detail |
|------|-------|--------|--------|
| 1 | Scheduler | Fires batch trigger (every 15 min or end-of-day) | A2 creates ClearingCycle record: `{cycleId, startedAt, status: PROCESSING}` |
| 2 | A2 | Collects all uncleared instructions from `payment.instructions` | 150 payment instructions accumulated since last cycle |
| 3 | A2 | Validates each against ISO 20022 pacs.008 XSD | 148 valid, 2 rejected (malformed XML) |
| 4 | A2 | Sends 148 instructions to A5 (fraud + sanctions) via Kafka | Batch screening — waits for all responses (configurable timeout: 30 sec) |
| 5 | A5 | Scores all 148 | 145 PASS, 2 MANUAL_REVIEW (held), 1 SANCTIONS_HOLD (held) |
| 6 | A2 | 145 proceed to netting. 3 held/blocked | |
| 7 | A2 | **Netting calculation**: groups by PSP pair, calculates net positions | 3 PSPs participating. Net positions sum to 0 ✓ |
| 8 | A2 | Verifies liquidity: each net debtor PSP has sufficient funds | All OK |
| 9 | A2 | Publishes 145 settlement events to `payment.settlement-events` | A1 processes: debit/credit each account |
| 10 | A2 | Generates camt.053 settlement report | Stored in DB, available via REST API |
| 11 | A2 | Publishes 145 SETTLED + 2 HELD + 1 BLOCKED to `payment.status-updates` | A3/A4 receive status updates, notify merchants/TPPs |
| 12 | A2 | Closes cycle | `{status: COMPLETED, settled: 145, rejected: 2, held: 3, grossAmount: 12.5M NOK, netAmount: 3.2M NOK}` |

---

## Scenario 1B: Instant Payment (Happy Path — EPC 2025: < 5s execution)

| Step | Time | Actor | Action |
|------|------|-------|--------|
| 1 | T+0ms | A3/A4 | Publishes to `payment.instant-instructions`: `{amount: 5000, currency: NOK, serviceLevel: INSTANT}` |
| 2 | T+50ms | A2 | Consumes instruction. Validates pacs.008 schema → OK |
| 3 | T+100ms | A2 → A1 | Request balance reservation: 5,000 NOK on debtor account |
| 4 | T+150ms | A1 → A2 | Reservation confirmed. Available balance reduced |
| 5 | T+200ms | A2 → A5 | Send to fraud+sanctions check via `payment.instant-fraud-check-request`. Start 500ms timeout |
| 6 | T+250ms | A5 | Sanctions screening: CLEAR (25ms). Fraud scoring: 0.05, PASS (80ms) |
| 7 | T+350ms | A5 → A2 | Response: `{sanctions: CLEAR, fraudScore: 0.05, decision: PASS}` |
| 8 | T+400ms | A2 | Check liquidity: originator PSP has sufficient funds → OK |
| 9 | T+450ms | A2 | Execute gross settlement: publish to `payment.instant-settlement-events` |
| 10 | T+500ms | A1 | Confirm reservation → final debit. Credit beneficiary account |
| 11 | T+600ms | A2 | Generate pacs.002 positive confirmation (ACSC) |
| 12 | T+650ms | A2 | Publish SETTLED to `payment.status-updates` |
| **Total** | **650ms** | | **Well within 5-second execution target (EPC 2025)** |

---

## Scenario 1C: Instant Payment — Fraud Timeout (Auto-Pass)

| Step | Time | Action |
|------|------|--------|
| 1-4 | T+0-200ms | Same as 1B (instruction consumed, reservation made) |
| 5 | T+200ms | Send to A5. Start 500ms timer |
| 6 | T+700ms | **Timer fires. A5 has not responded.** |
| 7 | T+700ms | A2 applies AUTO_PASS with fraudScore=UNKNOWN. Creates post-settlement review alert |
| 8-12 | T+700-1000ms | Settlement proceeds. pacs.002 confirmation sent |
| — | Later | A5 eventually responds (late). Result logged but payment already settled. Review alert flagged |

---

## Scenario 1D: Instant Payment — Sanctions Timeout (REJECT)

| Step | Time | Action |
|------|------|--------|
| 1-4 | T+0-200ms | Same as 1B |
| 5 | T+200ms | Send to A5. Start 500ms timer. **Sanctions screening component is down** |
| 6 | T+700ms | Timer fires. Sanctions result: UNAVAILABLE |
| 7 | T+700ms | **REJECT.** Cannot skip sanctions. Reason: SANCTIONS_UNAVAILABLE |
| 8 | T+750ms | Release balance reservation in A1. Funds restored |
| 9 | T+800ms | pacs.002 negative confirmation: `{status: RJCT, reason: AM06}` (technical error — try again later) |

**Key difference:** Fraud timeout → auto-pass (payment proceeds, reviewed later). Sanctions timeout → **REJECT** (legal obligation cannot be skipped).

---

## Scenario 1E: Instant Payment — 7-Second Hard Limit Breach (EPC 2025)

| Step | Time | Action |
|------|------|--------|
| 1-4 | T+0-200ms | Normal processing |
| 5 | T+200ms | Send to A5. A5 is extremely slow due to load |
| 6 | T+700ms | Fraud timeout. Auto-pass applied |
| 7 | T+750ms | Liquidity check. PSP liquidity service is slow (database contention) |
| 8 | T+9500ms | Liquidity confirmed (very late) |
| 9 | **T+7,000ms** | **Hard-limit watchdog fires.** 7 seconds exceeded (EPC 2025) |
| 10 | T+7,000ms | **REJECT with TIMEOUT.** All in-flight operations cancelled |
| 11 | T+7,050ms | Release balance reservation. pacs.002: `{status: RJCT, reason: TIMEOUT}` (must be delivered within 9s) |
| 12 | — | Metric `instant.timeout.count` +1. Grafana alert fires |

**No limbo state.** After 7 seconds (EPC 2025 hard limit), the answer is always REJECT. The payer can retry.

---

## Scenario 1H: Instant Payment — Amount Exceeds Limit

| Step | Time | Action |
|------|------|--------|
| 1 | T+0ms | A3/A4 publishes instant instruction: amount = EUR 150,000 |
| 2 | T+50ms | A2 consumes. Validates: amount EUR 150,000 > configured maximum EUR 100,000 (CLR-I04) |
| 3 | T+55ms | **REJECT immediately.** Reason: AMOUNT_EXCEEDED. No balance reservation attempted. No fraud check |
| 4 | T+60ms | pacs.002 negative confirmation: `{status: RJCT, reason: AM02}` (Amount exceeds agreed limit) |
| 5 | T+65ms | Status published to `payment.status-updates`: REJECTED with reason AMOUNT_EXCEEDED |

---

## Scenario 1I: Instant Payment — Beneficiary Account Closed

| Step | Time | Action |
|------|------|--------|
| 1-7 | T+0-350ms | Normal flow: reservation OK, fraud+sanctions PASS |
| 8 | T+400ms | A2 sends settlement event to A1: credit beneficiary account |
| 9 | T+450ms | A1 rejects: beneficiary account status = **CLOSED** (terminal). Credits not accepted on closed accounts |
| 10 | T+460ms | A2: REJECT. Reason: BENEFICIARY_ACCOUNT_UNAVAILABLE |
| 11 | T+470ms | Release payer balance reservation. pacs.002 negative confirmation |

*Note: FROZEN accounts still accept credits (LED-F22 — don't lose incoming payments). Only CLOSED accounts reject all operations.*

---

## Data: ISO 20022 pacs.008 (Simplified)

```xml
<FIToFICstmrCdtTrf>
  <GrpHdr>
    <MsgId>MSG-20260411-001</MsgId>
    <CreDtTm>2026-04-11T14:23:44.000Z</CreDtTm>
    <NbOfTxs>1</NbOfTxs>
    <SttlmInf><SttlmMtd>CLRG</SttlmMtd></SttlmInf>
  </GrpHdr>
  <CdtTrfTxInf>
    <PmtId>
      <EndToEndId>E2E-20260411-42</EndToEndId>
    </PmtId>
    <IntrBkSttlmAmt Ccy="NOK">5000.00</IntrBkSttlmAmt>
    <Dbtr><Nm>Kari Hansen</Nm></Dbtr>
    <DbtrAcct><Id><IBAN>NO9386011117947</IBAN></Id></DbtrAcct>
    <Cdtr><Nm>Ola Nordmann AS</Nm></Cdtr>
    <CdtrAcct><Id><IBAN>NO1234567890123</IBAN></Id></CdtrAcct>
    <RmtInf><Ustrd>Invoice 2026-042</Ustrd></RmtInf>
  </CdtTrfTxInf>
</FIToFICstmrCdtTrf>
```

---

## Scenario 1F: Semantic Duplicate Detection

*Inspired by Allevo FinTP's duplicate detection. Catches duplicates that idempotency keys miss.*

### What it detects
Same debtor + same creditor + same amount + same currency within a configurable time window (default: 10 minutes). Different from idempotency (which catches exact duplicate API calls with the same key).

### Example: User submits same payment from two channels

| Step | Time | Action | Result |
|------|------|--------|--------|
| 1 | T+0 | Consumer pays NOK 5,000 to Ola Nordmann AS via mobile app (idempotency key: "mobile-abc-123") | Payment P-001 accepted, enters clearing |
| 2 | T+3min | Consumer forgets and pays NOK 5,000 to Ola Nordmann AS via web (idempotency key: "web-xyz-789") | Different idempotency key → NOT caught by idempotency check |
| 3 | T+3min | **Semantic duplicate check**: same debtor + creditor + amount + currency, within 10-min window. Match against P-001 | **PROBABLE_DUPLICATE** detected |
| 4 | T+3min | A2 returns to originator: `{paymentId: "P-002", status: "REQUIRES_CONFIRMATION", duplicateWarning: {duplicateOf: "P-001", submittedAt: "T+0", amount: 5000, creditor: "Ola Nordmann AS"}}` | Warning, not rejection |
| 5a | Consumer | "Oops, I already paid this" → cancels P-002 | P-002 cancelled. Duplicate avoided |
| 5b | Consumer | "No, this is a different invoice" → confirms override | P-002 proceeds. Override logged. Duplicate flag → A5 fraud scoring (+0.2 risk) |

### What is NOT a semantic duplicate

| Scenario | Duplicate? | Why |
|----------|-----------|-----|
| Same amount to same creditor, 2 hours apart | No | Outside 10-min window |
| Same amount to same creditor, but different debtor | No | Different payer |
| Same debtor + creditor, different amount | No | Different amount |
| Monthly rent: same amount, same creditor, same debtor, 30 days apart | No | Outside window. Legitimate recurring payment |

### Design decision: Warn, don't block

Many legitimate payments look like duplicates (monthly rent, subscription renewal, split bills). Blocking would cause false rejections. The warning gives users a chance to catch mistakes while not interfering with intentional payments.

---

## Scenario 1G: Reconciliation-Ready Settlement Output

*Every settlement event carries references enabling automated reconciliation by A1.*

| Field | Value | Purpose |
|-------|-------|---------|
| `settlementReference` | UUID (unique per settled payment) | Links A1 ledger event to A2 settlement record |
| `clearingCycleId` | UUID (unique per clearing cycle) | Groups all payments in the same batch |
| `originalEndToEndId` | String (from pacs.008) | Trace back to original payment instruction |
| `settlementTimestamp` | Instant (UTC) | When settlement was finalized |

These fields appear in:
1. The Kafka settlement event (consumed by A1)
2. The camt.053 settlement report (used for reconciliation)
3. The A1 ledger event (stored as `externalReference`)

When A1 runs reconciliation (Scenario 1G in ledger use cases), it matches ledger events against camt.053 entries using `settlementReference`. A match = RECONCILED. A mismatch = DISCREPANCY → alert.

---

## Security Considerations
- ISO 20022 XML messages are validated against XSD schema — prevents malformed/injected data
- Netting algorithm is deterministic: given the same inputs, always produces the same outputs (auditable)
- Settlement reports are digitally signed — integrity verification
- Instant path timeout is a hard boundary — prevents resource exhaustion attacks
- Separate Kafka topics for batch and instant prevent head-of-line blocking
- **Semantic duplicate detection** prevents accidental double payments without blocking legitimate recurring transfers
- **Settlement references enable reconciliation** — every payment is traceable from instruction → clearing → settlement → ledger → reconciliation
