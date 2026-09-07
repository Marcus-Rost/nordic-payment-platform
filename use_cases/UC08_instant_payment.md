# UC-08: Instant Payment (SCT Inst — EPC 2025: 5s Execution / 7s Hard Limit / 9s Confirmation)

*Sub-systems involved: A2 (Clearing, instant path), A5 (Fraud + Sanctions), A6 (VoP), A1 (Account Ledger)*
*Regulatory basis: EU Instant Payment Regulation 2024/886*
*Mirrors: SEPA Instant Credit Transfer (SCT Inst) via TIPS/RT1*

---

## Primary Actor
**Payer** (directly or via TPP through A4)

## Preconditions
- Payer has an active account in A1 with sufficient available balance
- Beneficiary IBAN is valid
- Amount <= EUR 100,000 (configurable)
- System is available (24/7/365 — no maintenance windows)

## Trigger
Payer initiates a credit transfer with `service_level: INSTANT`

---

## Main Success Scenario (Time Budget: 5 seconds execution, 7 seconds hard limit, 9 seconds confirmation)

| Step | Time Budget | Actor/Service | Action |
|------|------------|---------------|--------|
| 1 | 0ms | Payer / TPP | Submits instant payment: debtor IBAN, creditor IBAN, creditor name, amount (NOK 5,000.00), reference |
| 2 | 0-300ms | **A6 (VoP)** | Verify creditor name against IBAN at beneficiary PSP → **MATCH** |
| 3 | 300-400ms | **A4/A3** | Validate instruction, check SCA (or exemption), publish to `payment.instant-instructions` |
| 4 | 400-500ms | **A2 (instant)** | Consume instruction, validate ISO 20022 pacs.008 structure |
| 5 | 500-600ms | **A1** | Reserve funds: available balance -= 5,000. Hold created. Status: RESERVED |
| 6 | 600-1100ms | **A5** | **Sanctions screening** (<50ms): screen originator + beneficiary → CLEAR. **Fraud scoring** (<100ms): velocity + anomaly + ML → score 0.05, PASS |
| 7 | 1100-1200ms | **A2** | Evaluate: sanctions CLEAR, fraud PASS, liquidity OK → execute gross settlement |
| 8 | 1200-1500ms | **A1** | Confirm hold → final debit on payer account. Credit beneficiary account. Events published: immutable, irrevocable |
| 9 | 1500-2000ms | **A2** | Generate pacs.002 (positive confirmation). Publish status SETTLED to `payment.status-updates` |
| 10 | 2000ms | **A4/A3** | Receive confirmation. Notify payer: "Payment of NOK 5,000 to Ola Nordmann completed" |

**Total elapsed: ~2 seconds** (well within 5-second execution target)

---

## Time Budget Allocation

```
EPC 2025 SCT Inst Rulebook v1.1 (effective October 5, 2025):
  Execution target:    5,000ms  (settlement completed)
  Hard limit:          7,000ms  (must reject if not completed)
  Confirmation:        9,000ms  (pacs.002 delivered to originator)

Execution budget (5,000ms target):
├── VoP check (A6):                           300ms   (includes network to external PSP)
├── SCA / validation / publish:               100ms
├── Clearing engine intake + validation:      100ms
├── Balance reservation (A1):                 100ms
├── Sanctions screening (A5):                  50ms
├── Fraud scoring (A5):                       100ms   (including DJL model inference)
├── Settlement execution + ledger update:     300ms
├── Subtotal processing:                    1,050ms
├── Network/infrastructure buffer:          3,950ms
                                           ────────
                                           5,000ms   (execution target)

Confirmation budget (additional):
├── pacs.002 generation + delivery:           200ms
├── Buffer to hard limit:                   1,800ms
                                           ────────
                                           7,000ms   (hard limit — REJECT if exceeded)

├── Additional buffer to confirmation:      2,000ms
                                           ────────
                                           9,000ms   (confirmation deadline)
```

Our processing subtotal (~1,050ms) gives us ~4 seconds of buffer within the 5-second execution target. Even under degraded conditions, the system stays well within the EPC 2025 SLA.

---

## Alternative Flows

### AF-01: VoP No Match — Payer Overrides
| Step | Time | Action |
|------|------|--------|
| 2a | 0-300ms | A6 returns NO_MATCH. Payer warned |
| 2b | — | Payer acknowledges warning and confirms (UI interaction, not counted in SLA since user is in the loop) |
| 2c | 300ms+ | Payment proceeds. VoP override flag sent to A5 → fraud score increased |

### AF-02: Sanctions Hit
| Step | Time | Action |
|------|------|--------|
| 6a | 600-650ms | A5 sanctions screening returns HIT |
| 6b | — | Payment immediately **HELD**. Does NOT continue to fraud scoring. Alert created |
| 6c | — | Compliance analyst reviews (async — outside SLA window). Confirm → BLOCK + freeze. Dismiss → release to fraud scoring |

### AF-03: Fraud Auto-Block
| Step | Time | Action |
|------|------|--------|
| 6c | 650-750ms | A5 fraud score = 0.92 (AUTO_BLOCK threshold) |
| 7a | 750-800ms | A2: REJECT payment. Reason: FRAUD_SUSPECTED |
| 8a | 800-900ms | A1: release balance reservation |
| 9a | 900-1000ms | pacs.002 negative confirmation (RJCT with reason code) |

### AF-04: Insufficient Balance
| Step | Time | Action |
|------|------|--------|
| 5a | 500-550ms | A1: available balance < 5,000 → reservation FAILED |
| 5b | 550-600ms | A2: REJECT. Reason: INSUFFICIENT_FUNDS |
| 5c | 600-700ms | pacs.002 negative confirmation |

### AF-05: Fraud Engine Timeout

**Case A — Partial response (sanctions CLEAR, fraud UNKNOWN/TIMEOUT):**

| Step | Time | Action |
|------|------|--------|
| 6d | 600-1100ms | A5 returns sanctions=CLEAR but fraud score times out (no fraud result within 500ms) |
| 6e | 1100ms | A2: AUTO_PASS fraud with score=UNKNOWN. Flag for post-settlement review. Payment proceeds to settlement |
| — | — | Alert created for retrospective fraud analyst review |

**Case B — Complete timeout (no response at all from A5):**

| Step | Time | Action |
|------|------|--------|
| 6d | 600-1100ms | A5 does not respond at all within 500ms (neither sanctions nor fraud result) |
| 6e | 1100ms | A2: REJECT. Reason: SCREENING_TIMEOUT. Sanctions status unknown — cannot skip sanctions |
| — | — | Alert created for operations team to investigate A5 availability |

**Note:** The distinction is critical: sanctions screening can **never** be skipped. Only the fraud component can be auto-passed when sanctions have already cleared.

### AF-06: Overall Timeout (7-second hard limit exceeded)
| Step | Time | Action |
|------|------|--------|
| — | 7,000ms | A2 hard-limit watchdog fires (EPC 2025: 7-second hard limit) |
| — | — | REJECT payment with reason TIMEOUT. Release any balance reservation. No limbo state |
| — | — | pacs.002 negative confirmation (must be delivered within 9s). Metric `instant.timeout.count` incremented |

### AF-07: Amount Exceeds Limit
| Step | Time | Action |
|------|------|--------|
| 4a | 400ms | A2: amount > EUR 100,000 limit → REJECT immediately. Reason: AMOUNT_EXCEEDED |

### AF-08: Beneficiary Account Closed
| Step | Time | Action |
|------|------|--------|
| 8a | 1200ms | A1: beneficiary account status = CLOSED (terminal). Cannot credit — account no longer active |
| 8b | — | A2: REJECT. Reason: BENEFICIARY_ACCOUNT_UNAVAILABLE. Release payer reservation |

*Note: FROZEN accounts still ACCEPT credits (LED-F22 — don't lose incoming payments). Only CLOSED accounts reject credits.*

---

## Differences from Batch Path (UC-04)

| Aspect | Batch (UC-04) | Instant (UC-08) |
|--------|---------------|-----------------|
| Timing | Scheduled (every 15 min / end-of-day) | On-demand, per-transaction |
| Settlement | **Multilateral netting** (reduces volume) | **Gross** (1:1, each payment settled individually) |
| Kafka topic | `payment.instructions` | `payment.instant-instructions` (dedicated) |
| Fraud timeout | Can wait indefinitely in batch queue | 500ms budget, then auto-pass |
| Sanctions timeout | Can wait | **REJECT** (cannot skip) |
| Balance | Debit at settlement time | **Reserve first**, then confirm/release |
| Processing | Spring Batch (chunk-oriented) | Kafka Streams (event-at-a-time) |
| Availability | Business hours acceptable | 24/7/365 mandatory |
| Finality | After batch cycle completes | **Immediate and irrevocable** |
| Liquidity | Post-netting (lower requirement) | Pre-funded (gross amount per payment) |

---

## Monitoring & SLA Compliance

| Metric | Target | Alert Threshold |
|--------|--------|----------------|
| `instant.latency.p50` | < 2s | — |
| `instant.latency.p95` | < 4s | Alert if p95 > 4s (approaching 5s target) |
| `instant.latency.p99` | < 5s | Alert if p99 > 5s (EPC execution target) |
| `instant.timeout.count` | 0 | Alert if > 0 in 5 minutes |
| `instant.sanctions.timeout.count` | 0 | Alert if > 0 (payment rejected) |
| `instant.fraud.timeout.count` | < 0.1% | Alert if > 1% (auto-pass rate too high) |
| `instant.success.rate` | > 99.5% | Alert if < 99% |
| `instant.availability` | 99.99% | Alert on any downtime |

All metrics exported via Micrometer → Prometheus → Grafana dashboard.

---

## Data: ISO 20022 pacs.002 Confirmation

```xml
<!-- Positive confirmation (ACSC = AcceptedSettlementCompleted) -->
<FIToFIPmtStsRpt>
  <GrpHdr>
    <MsgId>CONF-20260411-001</MsgId>
    <CreDtTm>2026-04-11T14:23:45.123Z</CreDtTm>
  </GrpHdr>
  <TxInfAndSts>
    <OrgnlEndToEndId>E2E-20260411-42</OrgnlEndToEndId>
    <TxSts>ACSC</TxSts>
    <AccptncDtTm>2026-04-11T14:23:44.891Z</AccptncDtTm>
    <InstgAgt><FinInstnId><BIC>DNBANOKK</BIC></FinInstnId></InstgAgt>
  </TxInfAndSts>
</FIToFIPmtStsRpt>

<!-- Negative confirmation (RJCT = Rejected) -->
<FIToFIPmtStsRpt>
  <TxInfAndSts>
    <OrgnlEndToEndId>E2E-20260411-43</OrgnlEndToEndId>
    <TxSts>RJCT</TxSts>
    <StsRsnInf>
      <Rsn><Cd>AM04</Cd></Rsn>  <!-- AM04 = Insufficient Funds -->
    </StsRsnInf>
  </TxInfAndSts>
</FIToFIPmtStsRpt>
```
