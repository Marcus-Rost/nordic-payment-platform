# Project #11: Payment Clearing & Settlement Engine — Requirements

*System A role: A2 — Dual-mode clearing: batch (netting) + instant (gross, EPC 2025 SLA: 5s execution / 7s hard limit / 9s confirmation)*
*Target companies: Mastercard Oslo (NICS/AvtaleGiro/P27), DNB, Tietoevry Banking*
*Regulatory basis: EU Instant Payment Regulation 2024/886, SEPA schemes (SCT, SCT Inst)*
*Standards: ISO 20022 (pacs.008, pacs.002, pacs.004, camt.053)*

---

## 1. Overview

The central orchestrator of the payment platform. It receives payment instructions from A3 (Bill Payment/R2P) and A4 (PSD2 API), routes them through fraud/sanctions screening (A5) and VoP verification (A6), executes clearing (batch netting or instant gross settlement), and publishes settlement events to A1 (Account Ledger).

This is the most **operationally complex** sub-system: it must handle two fundamentally different processing models (batch and instant) in the same service, manage liquidity, enforce ISO 20022 compliance, and maintain 24/7 availability for the instant path.

### 1.1 Target Company Alignment

| Company | What they build | Alignment |
|---------|----------------|-----------|
| **Mastercard Oslo** | NICS (Norwegian Interbank Clearing System), AvtaleGiro, eFaktura clearing, P27 multi-currency platform. 100+ Java engineers. Spring Boot, Linux, jUnit/Fitnesse | **This is their core product.** Our engine is a simplified NICS. ISO 20022, batch netting, instant path — all directly relevant |
| **DNB** | Kafka-based payment processing, GCP infrastructure, real-time event processing | Our Kafka Streams instant path + Kafka-based batch processing matches their architecture |
| **Tietoevry Banking** | Core banking platform modernization from COBOL to Java microservices. Spring Batch for batch processing. Event-driven with Kafka | Our dual-mode (Spring Batch for batch, Kafka Streams for instant) mirrors their modernization approach |

---

## 2. Functional Requirements — Batch Clearing Path

| ID | Requirement | Priority |
|----|------------|----------|
| CLR-B01 | Consume payment instructions from Kafka topic `payment.instructions` | Must |
| CLR-B02 | Validate each instruction against ISO 20022 pacs.008 schema (JAXB XSD validation) | Must |
| CLR-B03 | Collect instructions over configurable window (e.g., 15 min or end-of-day) | Must |
| CLR-B04 | **Multilateral netting**: calculate net debit/credit position per participating PSP per clearing cycle | Must |
| CLR-B05 | Route each transaction through A5 (fraud + sanctions) before settlement. Wait for response | Must |
| CLR-B06 | Reject transactions flagged by A5 (FRAUD or SANCTIONS) | Must |
| CLR-B07 | Produce settlement events to Kafka `payment.settlement-events` for settled transactions | Must |
| CLR-B08 | Generate settlement report in ISO 20022 camt.053 format | Must |
| CLR-B09 | Publish payment status updates to `payment.status-updates`. Statuses: PROCESSING, HELD (sanctions/fraud review), SETTLED, REJECTED (with reason code: FRAUD_SUSPECTED, SANCTIONS_HOLD, INSUFFICIENT_FUNDS, SCHEMA_VALIDATION_FAILED, TIMEOUT, AMOUNT_EXCEEDED), CANCELLED | Must |
| CLR-B10 | Clearing cycle history: cycle_id, start/end time, transaction count, net positions, report reference | Must |
| CLR-B11 | REST API: GET clearing cycles (paginated), GET cycle detail, GET settlement report | Must |
| CLR-B12 | Idempotent processing: same instruction consumed twice → not cleared twice | Must |
| CLR-B13 | **Semantic duplicate detection**: detect probably-duplicate payments even with different idempotency keys. Match criteria: same debtor + same creditor + same amount + same currency within configurable window (default: 10 min). On match: flag as PROBABLE_DUPLICATE, include `duplicateOf` reference to the earlier payment. Do NOT auto-block — return warning to originator, let payer confirm or cancel | Must |
| CLR-B14 | **Reconciliation-ready output**: every settlement event includes unique `settlementReference` (UUID) and `clearingCycleId`. These are stored by A1 as `externalReference` on each ledger event. Settlement reports (camt.053) are machine-parseable and contain the same references, enabling automated reconciliation | Must |
| CLR-B15 | **Reconciliation report endpoint**: GET `/api/v1/clearing-cycles/{id}/reconciliation-status` — shows per-cycle reconciliation status: how many payments matched, how many discrepancies, how many unmatched | Should |

### 2.1 Netting Algorithm

For N participating PSPs, multilateral netting reduces N×(N-1) gross obligations to N net positions:

```
Input:  Gross payment instructions between PSPs
        PSP_A → PSP_B: 500,000
        PSP_A → PSP_C: 300,000
        PSP_B → PSP_A: 200,000
        PSP_B → PSP_C: 150,000
        PSP_C → PSP_A: 400,000
        PSP_C → PSP_B: 100,000

Process: Calculate net position per PSP
        PSP_A: debits=800,000 credits=600,000 → net=-200,000 (net debtor)
        PSP_B: debits=350,000 credits=600,000 → net=+250,000 (net creditor)
        PSP_C: debits=500,000 credits=450,000 → net=-50,000 (net debtor)
        
Verify:  Sum of net positions = -200,000 + 250,000 + (-50,000) = 0 ✓

Output: Settlement instructions: net transfers to close positions
```

---

## 3. Functional Requirements — Instant Payment Path

| ID | Requirement | Priority |
|----|------------|----------|
| CLR-I01 | Consume from dedicated topic `payment.instant-instructions` (separate from batch) | Must |
| CLR-I02 | End-to-end processing: **execution < 5 seconds** (target), **hard limit < 7 seconds**, **confirmation < 9 seconds** (EPC 2025 SCT Inst rulebook v1.1) | Must |
| CLR-I03 | Available **24/7/365** | Must |
| CLR-I04 | Maximum amount: EUR 100,000 (configurable) | Must |
| CLR-I05 | **Gross settlement** per transaction (no netting) — each payment settled individually | Must |
| CLR-I06 | Request balance reservation from A1 before fraud/sanctions check | Must |
| CLR-I07 | Route through A5 with 500ms timeout. If fraud timeout: auto-pass with score=UNKNOWN. If sanctions timeout: **REJECT** (cannot skip) | Must |
| CLR-I08 | On success: confirm reservation in A1, publish settlement event, return pacs.002 positive confirmation | Must |
| CLR-I09 | On failure/timeout: release reservation in A1, return pacs.002 negative confirmation (RJCT + reason code) | Must |
| CLR-I10 | If 7-second hard limit exceeded: REJECT with TIMEOUT reason. No limbo state. pacs.002 negative confirmation must still be delivered within 9 seconds | Must |
| CLR-I11 | Support pacs.028 (PaymentStatusRequest) for in-flight status queries | Should |
| CLR-I12 | Support pacs.004 (PaymentReturn) for post-settlement return requests | Should |
| CLR-I13 | **Settlement finality event**: publish a distinct `SETTLEMENT_FINAL` event (separate from SETTLED status) indicating the settlement is legally irrevocable. For instant payments, this is immediate. For batch, this is after the clearing cycle closes | Should |

---

## 4. Functional Requirements — Liquidity Management

| ID | Requirement | Priority |
|----|------------|----------|
| CLR-L01 | Each participating PSP has a pre-funded settlement account with tracked balance | Must |
| CLR-L02 | Before instant settlement: verify PSP liquidity >= payment amount | Must |
| CLR-L03 | After instant settlement: deduct from originator PSP's liquidity, credit beneficiary PSP's liquidity | Must |
| CLR-L04 | Batch netting: verify each net debtor PSP has sufficient liquidity for their net position | Must |
| CLR-L05 | Threshold alerts: configurable low-liquidity warning (e.g., < 20% of initial funding) | Should |
| CLR-L06 | REST API: GET liquidity positions per PSP | Should |

---

## 5. Non-functional Requirements

| ID | Requirement | Target |
|----|------------|--------|
| CLR-NF01 | Batch: 10,000 instructions cleared in one cycle | < 30 seconds |
| CLR-NF02 | **Instant: end-to-end latency** | **Execution < 5s**, hard limit **< 7s**, confirmation **< 9s** (EPC 2025) |
| CLR-NF03 | Instant: A5 fraud+sanctions budget | < 500ms |
| CLR-NF04 | Instant: availability | 99.99% (24/7/365) |
| CLR-NF05 | Instant: throughput | >= 500 TPS |
| CLR-NF06 | Zero payment loss | Every instruction either SETTLED or explicitly REJECTED |

### 5.1 Resilience and Crash Recovery

*Propagated from master system requirements (section 2.7)*

| ID | Requirement | Priority |
|----|------------|----------|
| CLR-RES01 | **Batch clearing restartability**: Spring Batch clearing jobs must be restartable. Each payment's status is tracked individually within a cycle. If A2 crashes mid-batch, restart resumes from the last uncommitted chunk, not from the beginning. Spring Batch `ExecutionContext` must be persisted to PostgreSQL (`BATCH_JOB_EXECUTION`, `BATCH_STEP_EXECUTION` tables) | Must |
| CLR-RES02 | **Dead Letter Queue**: every Kafka consumer (batch instruction consumer, instant instruction consumer, fraud-response consumer, status-update consumer) must route unprocessable messages to `{topic}.dlq` after 3 retries. DLQ messages include original payload + error details. Monitor DLQ depth via Prometheus | Must |
| CLR-RES03 | **Graceful shutdown with in-flight instant payments**: on SIGTERM, complete or abort all in-flight instant payments — confirm or release balance reservations in A1 before closing Kafka consumers. Do not leave reservations orphaned. Use `spring.lifecycle.timeout-per-shutdown-phase` configuration | Must |
| CLR-RES04 | **Late-arriving fraud responses**: if A5 responds with SANCTIONS_HIT after A2 has already settled the payment (race condition on timeout auto-pass), create a retrospective alert (`SETTLED_BEFORE_SCREENING`) and initiate payment return procedure (pacs.004 PaymentReturn to A1). Log at WARN level | Must |
| CLR-RES05 | **Idempotent Kafka consumption**: use Kafka exactly-once semantics (`processing.guarantee=exactly_once_v2`) for the instant path Kafka Streams topology. For batch path: deduplicate by `messageId` or `transactionId` before processing | Must |
| CLR-RES06 | **XML parser hardening**: all ISO 20022 XML parsing (pacs.008, pacs.002, pacs.004, camt.053) must disable DTD processing, external entities (XXE), and XInclude. Configure JAXB with `XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES=false` and `XMLInputFactory.SUPPORT_DTD=false` | Must |

---

## 6. Technology Stack

| Component | Technology | Company Alignment |
|-----------|-----------|-------------------|
| Framework | **Spring Boot 3.x** | Universal |
| Batch processing | **Spring Batch** | Mastercard uses batch processing. Tietoevry uses Spring Batch for core banking migration |
| Instant processing | **Kafka Streams** | Low-latency stateful processing. DNB/SpareBank1/NAV pattern |
| ISO 20022 | **JAXB** with XSD-generated classes (pacs.008, pacs.002, pacs.004, camt.053) | Type-safe message handling |
| Scheduling | **Spring Scheduler** (for batch cycle triggers) | Standard |
| Database | **PostgreSQL** (clearing cycles, settlement records, liquidity) | Universal |
| Timeout | **CompletableFuture.orTimeout()** (Java 9+) | For 7-second hard limit enforcement (EPC 2025) |
| Testing | **Testcontainers** (Kafka, PostgreSQL), JUnit 5, **Spring Batch Test** | Tietoevry uses Testcontainers |
| Observability | **Micrometer + Prometheus** | Per-stage latency tracking, SLA compliance metrics |

---

## 7. ISO 20022 Message Types

| Message | Direction | Purpose |
|---------|-----------|---------|
| **pain.001** | Inbound (from A3/A4) | Customer Credit Transfer Initiation — the original payment instruction |
| **pacs.008** | Internal + outbound | FI-to-FI Credit Transfer — the interbank instruction used in clearing |
| **pacs.002** | Outbound (to A3/A4) | Payment Status Report — confirmation (ACSC) or rejection (RJCT) with reason code |
| **pacs.004** | Inbound (return request) | Payment Return — request to return a settled payment |
| **pacs.028** | Inbound (status query) | Payment Status Request — query status of in-flight payment |
| **camt.053** | Outbound (report) | Bank-to-Customer Statement — settlement report per clearing cycle |

---

## 8. Security Requirements

| ID | Requirement |
|----|------------|
| CLR-S01 | Payment instructions must include HMAC or digital signature for non-repudiation |
| CLR-S02 | Settlement reports (camt.053) must be digitally signed |
| CLR-S03 | Only authorized services may submit instructions (service-to-service mTLS or JWT) |
| CLR-S04 | All clearing decisions logged to immutable audit trail |
| CLR-S05 | Sanctions screening cannot be bypassed — hardcoded as mandatory |
| CLR-S06 | Instant path: 7-second hard limit is a hard boundary — no exceptions (EPC 2025 SCT Inst rulebook v1.1) |
| CLR-S07 | Netting algorithm must be deterministic and reproducible (same input → same output) |

---

## 9. Acceptance Criteria

| ID | Criterion |
|----|----------|
| AC-01 | Batch: 6 payment instructions between 3 PSPs → correct netting, net positions sum to 0 |
| AC-02 | Batch: instruction with invalid ISO 20022 schema → REJECTED with reason SCHEMA_VALIDATION_FAILED |
| AC-03 | Batch: instruction flagged by A5 → REJECTED with reason FRAUD_SUSPECTED or SANCTIONS_HOLD |
| AC-04 | Instant: payment submitted → settled in < 5 seconds (show elapsed time vs. EPC 2025 execution target) |
| AC-05 | Instant: sanctions timeout → payment REJECTED (not auto-passed) |
| AC-06 | Instant: fraud timeout → payment auto-passed with UNKNOWN score, flagged for review |
| AC-07 | Instant: 7-second hard limit exceeded → REJECT with TIMEOUT, reservation released |
| AC-08 | Instant: concurrent payments from same account → no double-spend (reservation prevents it) |
| AC-09 | Liquidity check: PSP with insufficient liquidity → payment REJECTED |
| AC-10 | Settlement report: valid ISO 20022 camt.053 XML generated and downloadable |
