# UC-04: Batch Clearing Cycle (End-of-Day Settlement)

*Sub-systems involved: A2 (Clearing Engine), A5 (Fraud & Sanctions), A6 (VoP), A1 (Account Ledger)*
*Mirrors: Mastercard Oslo NICS (Norwegian Interbank Clearing System) batch clearing*
*Note: For instant payment processing (EPC 2025: 5s execution / 7s hard limit), see UC-08*

---

## Primary Actor
**System (scheduled trigger)** — the clearing engine runs batch clearing on a configurable schedule

## Preconditions
- Payment instructions have accumulated in the clearing queue during the clearing window
- All participating bank accounts exist in A1

## Trigger
Scheduled clearing cycle fires (e.g., end-of-day at 16:00 UTC, or every 15 minutes for near-real-time)

---

## Main Success Scenario

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | Scheduler | Triggers batch clearing cycle | A2 creates a new ClearingCycle record (cycle_id, started_at, status=PROCESSING) |
| 2 | A2 | Collects all uncleared payment instructions from queue | Retrieves N payment instructions accumulated since last cycle |
| 3 | A2 | Validates each instruction against ISO 20022 schema | Invalid instructions marked as REJECTED (reason: SCHEMA_VALIDATION_FAILED) |
| 3b | A2 | **Read VoP result** from each instruction (set by A3/A4 before publishing — per SEC-12) | VoP was performed upstream. A2 reads the `vopResult` field. NO_MATCH results forwarded to A5 as fraud signal but not blocking in batch path |
| 4 | A2 | Sends all valid instructions to Fraud & Sanctions Engine (batch) | Publishes each to `payment.fraud-check-request`, waits for responses (configurable timeout: 30 sec) |
| 5a | A5 | **Sanctions screening** for each transaction | Screens originator + beneficiary against EU/UN sanctions lists. Example: 149 CLEAR, 1 POTENTIAL_HIT |
| 5b | A5 | **Fraud scoring** for sanctions-clear transactions | Applies velocity rules + anomaly detection + ML scoring. VoP NO_MATCH override adds +0.3 to score. Example: 146 PASS, 2 MANUAL_REVIEW, 1 AUTO_BLOCK |
| 6 | A2 | Separates: cleared proceed to netting, held/blocked do not | 146 proceed. 1 sanctions HELD, 2 fraud HELD (manual review), 1 fraud REJECTED |
| 7 | A2 | **Multilateral netting calculation**: groups transactions by debtor bank and creditor bank, calculates net position per bank pair | Example: Bank A owes Bank B net NOK 1.2M, Bank B owes Bank C net NOK 800K, etc. |
| 8 | A2 | Generates settlement instructions (one net transfer per bank pair) | Produces ISO 20022 pacs.008 (credit transfer) messages |
| 9 | A2 | Publishes settlement events to `payment.settlement-events` | One event per original transaction: debit source account, credit destination account |
| 10 | A1 | Processes settlement events | Records immutable balance change events for each account. Updates projections |
| 11 | A2 | Generates settlement report (ISO 20022 camt.053 account statement) | Persists report, available via REST API |
| 12 | A2 | Publishes status updates for all processed payments | `payment.status-updates`: each payment → SETTLED or REJECTED |
| 13 | A2 | Closes clearing cycle | ClearingCycle status=COMPLETED, completed_at, statistics (total, settled, rejected, net positions) |

## Postconditions
- All payment instructions in the batch have a final status (SETTLED or REJECTED)
- Account balances in A1 reflect all settled payments
- Settlement report available via A2 REST API
- Clearing cycle statistics logged and exposed via Prometheus metrics

---

## Alternative Flows

### AF-01: Fraud Engine Timeout
| Step | Condition | System Response |
|------|-----------|----------------|
| 4a | Fraud Detection does not respond within 10 seconds for a transaction | A2 scores as UNKNOWN (not blocked). Transaction proceeds to clearing but flagged for post-settlement review. Metric `fraud.timeout.count` incremented |

### AF-02: No Instructions to Clear
| Step | Condition | System Response |
|------|-----------|----------------|
| 2a | No pending instructions in queue | A2 creates cycle with status=EMPTY, logs "No instructions to clear", completes immediately |

### AF-03: Partial Settlement Failure
| Step | Condition | System Response |
|------|-----------|----------------|
| 10a | Account Ledger rejects a settlement event (e.g., account FROZEN) | A1 publishes failure event. A2 marks that specific payment as FAILED (reason: ACCOUNT_FROZEN). Other payments in the cycle are unaffected |

### AF-04: Sanctions Hit in Batch
| Step | Condition | System Response |
|------|-----------|----------------|
| 5a-alt | Sanctions screening returns POTENTIAL_HIT for a transaction | Transaction immediately HELD (removed from batch netting). Compliance alert created in A5 case management. All other transactions in the batch continue normally |
| — | Compliance analyst confirms match | Transaction BLOCKED. Report to FIU. Merchant notified |
| — | Compliance analyst determines false positive | Transaction RELEASED into the next clearing cycle |

### AF-05: VoP No Match in Batch
| Step | Condition | System Response |
|------|-----------|----------------|
| 3b-alt | Instruction's `vopResult` field is NO_MATCH (VoP was performed by A3/A4 before publishing — per SEC-12) | Transaction is NOT automatically blocked in batch (unlike instant path). NO_MATCH result forwarded to A5 fraud scoring as +0.3 risk signal. If combined fraud score > threshold → HELD for review. Otherwise proceeds to netting with a flag |

### AF-06: Instant Payment Instruction Received
| Step | Condition | System Response |
|------|-----------|----------------|
| — | Payment instruction arrives with `serviceLevel: INSTANT` | A2 routes to the **instant processing path** (separate Kafka topic, separate consumer group). See **UC-08: Instant Payment** for the full instant payment flow with EPC 2025 SLA (5s/7s/9s) |

---

## Netting Example

**Input: 6 payment instructions in one clearing cycle**

| From (Debtor Bank) | To (Creditor Bank) | Amount (NOK) |
|--------------------|--------------------|-------------|
| Bank A | Bank B | 500,000 |
| Bank A | Bank C | 300,000 |
| Bank B | Bank A | 200,000 |
| Bank B | Bank C | 150,000 |
| Bank C | Bank A | 400,000 |
| Bank C | Bank B | 100,000 |

**Netting result (net positions):**

| Bank | Gross Debits | Gross Credits | Net Position |
|------|-------------|--------------|-------------|
| Bank A | 800,000 | 600,000 | -200,000 (net debtor) |
| Bank B | 350,000 | 600,000 | +250,000 (net creditor) |
| Bank C | 500,000 | 450,000 | -50,000 (net debtor) |

**Settlement: 2 net transfers instead of 6 gross transfers:**
1. Bank A → Bank B: NOK 200,000 (but adjusted per triangular netting)
2. Net positions settle to zero across all participants

This netting reduces 6 gross transfers to fewer net transfers, minimizing interbank settlement volume — exactly what NICS does for Norwegian interbank payments.

## Data Requirements

**ClearingCycle entity:**
```
cycle_id:               UUID
started_at:             Instant (UTC)
completed_at:           Instant (nullable)
status:                 Enum (PROCESSING, COMPLETED, FAILED, EMPTY)
total_instructions:     Integer
settled_count:          Integer
rejected_count:         Integer
failed_count:           Integer
total_gross_amount:     BigDecimal
total_net_amount:       BigDecimal
settlement_report_id:   UUID (reference to generated ISO 20022 camt.053)
```

## Security Considerations
- Clearing cycle trigger must be authenticated (only the scheduler or admin, not external API callers)
- Settlement files include digital signature (JWS) for integrity verification
- Netting calculation must be deterministic and reproducible: same input always produces same output (for audit)
- All rejected payments must include reason code in audit trail
