# Extension: Instant Payment Processing (SCT Inst)

*Addresses GAP 1 from gap_analysis.md*
*Regulatory basis: EU Instant Payment Regulation 2024/886*
*Mandatory for eurozone PSPs: receive by Jan 2025, send by Oct 2025*

---

## Why This Is the #1 Gap

The EU has made instant payments **legally mandatory**. A payment processing portfolio that only has batch clearing is like a web application without HTTPS — it signals you haven't kept up with the regulatory landscape. Every interviewer at Mastercard, DNB, SpareBank 1, or Tietoevry will notice this absence.

Additionally, this is technically the most interesting extension: it forces a fundamentally different processing model (real-time vs. batch), introduces hard latency constraints, and requires rethinking how fraud detection, sanctions screening, and settlement interact.

---

## Requirements

### Functional Requirements

| ID | Requirement | Priority |
|----|------------|----------|
| IP-F01 | Process credit transfer from submission to beneficiary credit: **execution < 5 seconds**, hard limit **< 7 seconds**, confirmation **< 9 seconds** (EPC 2025 SCT Inst rulebook v1.1) | Must |
| IP-F02 | Available **24/7/365** — no maintenance windows, no batch cut-off times | Must |
| IP-F03 | Maximum transaction amount: **EUR 100,000** (configurable) | Must |
| IP-F04 | Settlement is **immediate, irrevocable, and final** — no netting, no deferred settlement | Must |
| IP-F05 | Use ISO 20022: **pacs.008** for instruction, **pacs.002** for confirmation/rejection | Must |
| IP-F06 | Return **positive confirmation** to originating PSP within 9-second confirmation window (EPC 2025) | Must |
| IP-F07 | If processing cannot complete within **7-second hard limit**, **reject** with timeout reason code (do not leave payment in unknown state) | Must |
| IP-F08 | Support **pacs.028** (PaymentStatusRequest) for status inquiry on submitted payments | Should |
| IP-F09 | Support **pacs.004** (PaymentReturn) for post-settlement return requests | Should |
| IP-F10 | Price parity: instant payments must not cost more than batch payments | Must (regulatory) |

### Non-functional Requirements

| ID | Requirement | Target |
|----|------------|--------|
| IP-NF01 | End-to-end latency | Execution < 5s, hard limit < 7s, confirmation < 9s (EPC 2025) |
| IP-NF02 | Fraud screening latency | < 100ms (to fit within SLA budget) |
| IP-NF03 | Sanctions screening latency | < 50ms |
| IP-NF04 | Ledger update latency | < 200ms |
| IP-NF05 | Availability | 99.99% (24/7/365) |
| IP-NF06 | Throughput | >= 500 TPS (transactions per second) |

### Security Requirements

| ID | Requirement |
|----|------------|
| IP-S01 | **Verification of Payee (VoP)** must be executed before payment submission (see VoP extension) |
| IP-S02 | **Sanctions screening** must execute within the 5-second execution window without blocking (see sanctions extension) |
| IP-S03 | **Fraud scoring** must execute within the 5-second execution window (configurable timeout with auto-pass fallback) |
| IP-S04 | All instant payment processing decisions logged to immutable audit trail |
| IP-S05 | Payment confirmation (pacs.002) must include digital signature for non-repudiation |

---

## Processing Flow

### Happy Path (Target: < 5 seconds execution, < 9 seconds confirmation)

```
EPC 2025 SCT Inst Rulebook v1.1 timing:
  Execution target:    5,000ms
  Hard limit:          7,000ms
  Confirmation:        9,000ms

Execution budget (5,000ms target):
├── Originator PSP processing:        0.8s
│   ├── Validation:                   0.2s
│   ├── VoP check:                    0.3s
│   └── Sanctions screen:             0.2s + 0.1s buffer
├── CSM (Clearing) processing:        1.5s
│   ├── Receive & validate:           0.2s
│   ├── Fraud scoring:                0.5s
│   ├── Settlement (gross):           0.3s
│   └── Buffer:                       0.5s
├── Beneficiary PSP processing:       0.7s
│   ├── Receive & validate:           0.2s
│   ├── Credit account:               0.2s
│   └── Buffer:                       0.3s
└── Network/infrastructure buffer:    2.0s
                                     ─────
                                     5.0s  (execution target)

Confirmation budget (additional):
├── pacs.002 generation + delivery:   0.2s
├── Buffer:                          3.8s
                                     ─────
                                     9.0s  (confirmation deadline)
```

### Step-by-step:

```
1. Payer → A4/A3: Submit instant payment request
   └── A4 validates: SCA completed, consent valid (PSD2) or A3 validates: API key, invoice approved
   
2. Pre-payment checks (parallel):
   ├── VoP: Query beneficiary PSP — "Does IBAN belong to stated name?" → MATCH
   ├── Sanctions: Screen originator + beneficiary against EU sanctions list → CLEAR  
   └── Balance: Check sufficient funds in A1 → OK

3. If all checks pass → Debit payer account in A1 (immediate, tentative hold)

4. Publish to Kafka: payment.instant-instructions (high-priority topic, separate from batch)

5. A2 Clearing Engine (instant path):
   ├── Validate ISO 20022 pacs.008 structure
   ├── Send to A5 Fraud Detection (with 500ms timeout)
   │   └── A5 scores: velocity + anomaly + ML → score 0.12 → PASS
   ├── Execute gross settlement (no netting — immediate 1:1 settlement)
   └── Publish settlement event to A1

6. A1 Account Ledger: 
   ├── Convert tentative hold to confirmed debit on payer account
   └── Credit beneficiary account (immediate, final)

7. A2 → pacs.002 positive confirmation back to originator

8. Originator → Payer: "Payment of NOK 5,000 to Ola Nordmann completed"

Total elapsed: ~3-4 seconds (well within 5-second execution target)
```

### Timeout/Failure Path:

```
If any step exceeds its time budget:
├── VoP timeout (>500ms): Proceed without VoP (log for audit, flag for review)
├── Sanctions timeout (>200ms): REJECT payment (cannot skip sanctions)  
├── Fraud scoring timeout (>500ms): AUTO_PASS with score=UNKNOWN (flag for post-settlement review)
├── Ledger debit failure: REJECT (insufficient funds or account frozen)
├── Settlement failure: REJECT, reverse tentative hold on payer account
└── Total >7 seconds (hard limit): REJECT with reason TIMEOUT, reverse all tentative operations
```

---

## Architecture Differences: Instant vs. Batch

| Aspect | Batch Clearing (existing) | Instant Payment (new) |
|--------|--------------------------|----------------------|
| Timing | Scheduled cycles (e.g., every 15 min or end-of-day) | On-demand, per-transaction |
| Settlement | **Net** (multilateral netting reduces volume) | **Gross** (each payment settled individually) |
| Kafka topics | `payment.instructions` (standard priority) | `payment.instant-instructions` (dedicated, high-priority) |
| Fraud check | Can wait for batch response | Must respond in <500ms or auto-pass |
| Availability | Business hours acceptable | 24/7/365 mandatory |
| Rollback | Can reject before settlement in next batch | Must complete or reject within 7 seconds (hard limit) — no limbo |
| Spring Batch | Yes (batch job framework) | No — real-time event processing |
| Kafka Streams | Optional | Essential (low-latency stateful processing) |
| Liquidity | Post-netting (lower requirements) | Pre-funded (gross amount per transaction) |

---

## Kafka Topic Design for Instant Payments

```
payment.instant-instructions          # New: dedicated topic for instant payments
  ├── Partition key: originator_account_id  
  ├── Retention: 7 days
  ├── Replication: 3
  └── Config: acks=all, min.insync.replicas=2

payment.instant-fraud-check-request   # New: dedicated fraud check for instant
  └── Max latency expectation: 500ms response

payment.instant-fraud-check-response  # New: fraud check results
  └── Consumed by: A2 instant path

payment.instant-settlement-events     # New: instant settlement confirmations
  └── Consumed by: A1 (immediate ledger update)

payment.instant-confirmation          # New: pacs.002 confirmations
  └── Consumed by: originator service
```

Separating instant payment topics from batch ensures that high-volume batch processing doesn't cause head-of-line blocking for latency-sensitive instant payments.

---

## Impact on Existing Sub-systems

| Sub-system | Changes Required |
|------------|-----------------|
| **A1 (Ledger)** | Add tentative hold/confirm/reverse pattern. Must process instant settlement events with <200ms latency. Add balance reservation for pending instant payments |
| **A2 (Clearing)** | Add instant processing path alongside batch. No netting for instant. Gross settlement per transaction. Timeout management with reject-on-timeout |
| **A3 (Bill Payment)** | Add `payment_method: INSTANT` option on invoice approval. Trigger instant path instead of batch |
| **A4 (PSD2 API)** | Payment initiation must support `instructed_execution_date: INSTANT` or `service_level: SEPA_INSTANT`. SCA must complete before submission |
| **A5 (Fraud)** | Must guarantee <500ms response for instant path. Add configurable timeout behavior (auto-pass vs. auto-block). Separate Kafka consumer group for instant topics |

---

## Implementation Technologies

| Component | Technology | Why |
|-----------|-----------|-----|
| Instant processing pipeline | **Kafka Streams** (not Spring Batch) | Low-latency stateful stream processing |
| Timeout management | **CompletableFuture** with `orTimeout()` | Java 9+ timeout-aware async |
| Balance reservation | **PostgreSQL SELECT FOR UPDATE with SKIP LOCKED** | Concurrent reservation without blocking |
| High-availability | **Multiple Kafka consumer instances** with consumer group | No single point of failure |
| Monitoring | **Micrometer timer metrics** for each processing stage | Track SLA compliance per-stage and end-to-end |
| Alerting | **Prometheus alert rules** for p99 > 8 seconds | Early warning before SLA breach |
