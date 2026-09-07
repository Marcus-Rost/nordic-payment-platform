# Requirements Review: Nordic Payment Processing Infrastructure

*Reviewer: Claude (Opus 4.6)*
*Date: 2026-04-11*
*Scope: All requirements and use cases across 6 sub-systems (A1-A6) + cross-cutting starter*
*Special focus: Computational stability — crash recovery, interrupted transactions, hardware failure*

---

## Executive Summary

The requirements documentation is **exceptionally thorough** for functional behavior, security, and regulatory compliance. The sub-system decomposition is clean, the Kafka topic topology is well-defined, and the cross-reference system (NAVIGATION.md, SUBSYSTEM_MAP.md) is exemplary.

However, there is a **systematic gap in computational stability requirements**. The documents describe what the system should do under normal conditions and several failure scenarios (fraud timeout, sanctions timeout, SLA breach, insufficient funds), but they are largely silent on what happens when the *system itself* fails mid-operation — crashes, hardware faults, network partitions, partial failures during multi-step transactions. For a payment processing system, this is the single most dangerous category of requirements omissions: silent data loss or inconsistency in financial records.

This review identifies **23 findings**, classified by severity:

| Severity | Count | Meaning |
|----------|-------|---------|
| **CRITICAL** | 7 | Could cause financial loss, data corruption, or regulatory violation if not addressed |
| **HIGH** | 8 | Could cause degraded operation, stuck transactions, or audit trail gaps |
| **MEDIUM** | 5 | Should be addressed for production-readiness but won't cause immediate harm |
| **LOW** | 3 | Improvements for completeness |

---

## PART 1: COMPUTATIONAL STABILITY FINDINGS

These findings concern system crashes, interrupted transactions, hardware failures, and the absence of recovery mechanisms in the requirements.

---

### CRITICAL-01: Orphaned Balance Reservations After A2 Crash

**Location**: LED-F10 through LED-F14 (A1 requirements), CLR-I06 through CLR-I09 (A2 requirements)

**Problem**: During an instant payment, A2 requests a balance reservation from A1 (step 5 in UC-08). If A2 crashes after A1 creates the reservation but before A2 sends either a confirm or release command, the reservation becomes orphaned. The payer's funds are locked with no service to unlock them.

LED-F14 specifies a reservation timeout auto-release but classifies it as **"Should"** (optional). For a payment system, this must be **"Must"**. Without it, any crash in A2 during the reserve-to-confirm window permanently locks customer funds.

Furthermore, the timeout mechanism itself has unspecified behavior:
- How is the timeout tracked? An in-memory timer in A1 would be lost if A1 also crashes. A database-persisted scheduled job would survive, but this is not specified.
- What happens to the payment in A2's state if A2 restarts and finds a reservation it made before the crash — does it attempt to complete or abort?

**Recommendation**:
1. Elevate LED-F14 from "Should" to **"Must"**.
2. Add requirement: "Reservation timeouts must be persisted (database or durable scheduler), surviving A1 restarts."
3. Add requirement: "On A2 restart, scan for in-flight instant payments with active reservations. For each, either complete the payment pipeline or issue an explicit release."
4. Add requirement: "Reservations released by timeout must produce a `BALANCE_RELEASED` event with reason `TIMEOUT` and publish a `REJECTED` status update."

---

### CRITICAL-02: Double-Entry Atomicity Across Two Kafka Events

**Location**: LED-F01, LED-F02, A1 use case Scenario 1A (ledger_operations.md lines 6-16)

**Problem**: LED-F02 mandates that every transaction creates exactly two entries (debit + credit). Scenario 1A shows these as two separate events published to `ledger.events`:
- Event #1: DEBIT on account A
- Event #2: CREDIT on account B

If A1 crashes after publishing Event #1 but before publishing Event #2, the double-entry invariant is violated: money has been debited from one account but never credited to another. Since Kafka events are immutable (LED-S05), Event #1 cannot be rolled back.

The idempotency mechanism (LED-F08) handles the case of duplicate events — but it does not handle the case of a *missing paired event*.

**Recommendation**:
1. Add requirement: "The debit and credit events for a single transaction must be published atomically. Either both are published or neither is. This can be achieved via Kafka transactions (exactly-once producer semantics) with both events in a single atomic batch."
2. Alternatively, add requirement: "A single compound event containing both debit and credit entries must be used, with the projection consumer extracting and applying both sides."
3. Add requirement: "A startup consistency checker must detect unpaired debit/credit events and raise an alert."

---

### CRITICAL-03: Dual-Write Problem — PostgreSQL + Kafka Consistency

**Location**: All sub-systems that write to both PostgreSQL and Kafka (A1, A2, A3, A4, A5)

**Problem**: Multiple sub-systems must maintain state in both PostgreSQL and Kafka simultaneously. Classic examples:
- A3 transitions a payment request to ACCEPTED in PostgreSQL and publishes a PaymentInstruction to Kafka.
- A2 records a clearing cycle as COMPLETED in PostgreSQL and publishes settlement events to Kafka.
- A5 writes a FraudAlert to PostgreSQL and publishes to `payment.fraud-check-response`.

If a service crashes between the two writes, or if one write succeeds and the other fails, the systems become inconsistent. This is the well-known "dual-write problem" in distributed systems, and the requirements do not acknowledge or address it anywhere.

**Recommendation**:
1. Add a cross-cutting requirement: "For any operation that must update both PostgreSQL and Kafka, the system must use the Transactional Outbox Pattern: write the outgoing event to an outbox table within the same database transaction, then publish to Kafka from the outbox table using a separate polling or CDC (Change Data Capture) process."
2. Alternatively: "Use Kafka as the source of truth and derive PostgreSQL state from Kafka consumers (already done for A1 projections — generalize this pattern)."
3. Document which sub-systems use which pattern and ensure consistency.

---

### CRITICAL-04: Batch Clearing Cycle Crash Recovery

**Location**: CLR-B01 through CLR-B10, UC-04 (batch_clearing_cycle.md)

**Problem**: A batch clearing cycle is a long-running multi-step process: collect instructions, validate, screen, net, settle, report. If A2 crashes mid-cycle:

1. **After netting, during settlement event publishing**: Some payments are settled (events published to `payment.settlement-events`), some are not. The netting calculation assumed all 145 payments would settle. Partial settlement invalidates the net positions. PSPs could end up with incorrect balances.

2. **After settlement events, before cycle close**: A2 hasn't recorded the cycle as COMPLETED. On restart, does it re-run the cycle? If so, it would re-settle already-settled payments (violating idempotency unless the ledger handles it). If not, the cycle is abandoned and some payments are settled without cycle metadata.

The ClearingCycle entity has statuses PROCESSING, COMPLETED, FAILED, EMPTY — but no PARTIALLY_SETTLED status and no recovery procedure.

Spring Batch (used for batch processing) has built-in restartability, but this capability is not mentioned in the requirements and the integration between Spring Batch job recovery and Kafka exactly-once publishing is non-trivial.

**Recommendation**:
1. Add requirement: "Batch clearing must be restartable. If A2 crashes mid-cycle, restarting A2 must resume the cycle from the last successfully committed step, not re-process from the beginning."
2. Add requirement: "Each settlement event publication within a batch must be tracked in the clearing cycle state (e.g., a per-payment status within the cycle). On restart, only unpublished settlements are retried."
3. Add requirement: "Spring Batch job state (ExecutionContext) must be persisted to the database, enabling automatic restart from the last committed chunk."
4. Add a PARTIALLY_SETTLED status for cycles interrupted mid-settlement.

---

### CRITICAL-05: Exactly-Once Semantics Across Service Boundaries

**Location**: A2-NF04 ("Zero payment loss. Exactly-once semantics"), LED-F08, CLR-B12

**Problem**: A2-NF04 claims "exactly-once semantics" but this is stated as a single-line non-functional requirement without specifying how it is achieved across the multi-service pipeline:

```
A3/A4 → Kafka → A2 → Kafka → A5 → Kafka → A2 → Kafka → A1
```

Kafka provides exactly-once *within* a Kafka Streams application (using transactional producers and EOS). But the pipeline involves multiple independent services communicating via Kafka topics. At each service boundary, there is a consume-process-produce cycle that can fail:

- A2 consumes from `payment.instant-instructions`, processes, produces to `payment.instant-fraud-check-request`. If A2 crashes after producing but before committing the consumer offset, it will re-consume the instruction on restart and produce a duplicate fraud check request.
- A5 consumes the request, scores it, produces to `payment.instant-fraud-check-response`. Same crash-window issue.
- Each of these services would need to either (a) use Kafka transactions linking consume and produce, or (b) rely on downstream idempotency.

The requirements specify idempotency at the ledger level (LED-F08) and clearing level (CLR-B12), but not at the fraud engine level or the inter-service message level.

**Recommendation**:
1. Add cross-cutting requirement: "Each service in the payment pipeline must implement idempotent message processing. Every Kafka message must carry a unique `messageId`. Services must deduplicate by `messageId` before processing."
2. Add requirement: "For the instant payment path, A2 should use Kafka exactly-once semantics (transactional producer with `read_committed` isolation) to atomically consume from instruction topics and produce to fraud-check-request topics."
3. Add requirement: "A5 must deduplicate fraud-check requests by `transactionId` to handle duplicate deliveries."

---

### CRITICAL-06: Sanctions List Ingestion Atomicity

**Location**: SAN-F07, SAN-F08 (A5 requirements), Section 7.5 of master requirements

**Problem**: Sanctions list updates must be ingested within 1 hour (SAN-F07). The screening pipeline uses three in-memory structures: Bloom filter, Aho-Corasick automaton, and fuzzy match index. If A5 crashes during list ingestion:

1. **Partial load**: The Bloom filter is rebuilt with the new list, but the Aho-Corasick automaton still references the old list. A name present in the new list but not the old one would pass the Bloom filter (maybe-match) but then fail to match in Aho-Corasick (which still has old data). Result: **a sanctioned entity passes screening**.

2. **Inconsistent state on restart**: If the list file was partially processed and the checkpoint was not persisted, A5 might re-ingest from the beginning (causing a period of incomplete screening) or skip the update (running on stale data).

Since sanctions screening is a legal obligation that cannot be skipped (SAN-F12), an inconsistent screening state is equivalent to a screening failure.

**Recommendation**:
1. Add requirement: "Sanctions list ingestion must be atomic. The new Bloom filter, Aho-Corasick automaton, and fuzzy match index must be built in a staging area. Only after all three are fully rebuilt, swap the active reference atomically (copy-on-write / double-buffering)."
2. Add requirement: "Sanctions list version must be tracked. Every screening decision must record which list version was used (already partially covered by SEC-05 / A5-S06, but needs explicit version tracking)."
3. Add requirement: "On A5 startup, verify that in-memory screening data is consistent and current. If the last successful list load timestamp is older than the configured freshness threshold, trigger immediate re-ingestion before accepting screening requests."

---

### CRITICAL-07: No Disaster Recovery or Data Durability Requirements

**Location**: Absent from all documents

**Problem**: The system specifies 99.99% availability for critical services (A5, A6, instant payment path), which translates to approximately 52 minutes of downtime per year. However, there are no requirements addressing:

1. **Kafka durability**: Kafka topic replication factor, min ISR (in-sync replicas), acknowledgment mode (acks=all). The event log in `ledger.events` is the source of truth for all financial data (LED-S05). If a Kafka broker fails and events are lost, the entire system's financial integrity is compromised.

2. **PostgreSQL backups and failover**: No requirements for database replication, automated failover, point-in-time recovery, or backup frequency.

3. **Multi-node/multi-AZ deployment**: 99.99% availability cannot be achieved with a single instance. No requirements for deploying services across availability zones or data centers.

4. **Kafka topic retention policies**: `ledger.events` must be retained indefinitely (it's the immutable event log). Other topics may have different retention needs. This is not specified.

5. **RTO/RPO targets**: No Recovery Time Objective or Recovery Point Objective is defined.

**Recommendation**:
1. Add cross-cutting requirement: "Kafka topics that serve as sources of truth (ledger.events) must be configured with replication factor >= 3, min.insync.replicas >= 2, acks=all."
2. Add requirement: "ledger.events topic retention must be set to unlimited (retention.ms=-1). Other topics must have retention policies defined per-topic."
3. Add requirement: "PostgreSQL databases must be configured with streaming replication and automated failover."
4. Add requirement: "Define RTO and RPO targets. Suggested: RTO < 5 minutes, RPO = 0 (no data loss) for critical path services."
5. Add requirement: "Critical services (A2 instant path, A5, A6) must be deployable across multiple availability zones."

---

### HIGH-01: Graceful Shutdown During In-Flight Instant Payments

**Location**: STARTER-F40 through F43 (platform starter), UC-08

**Problem**: The platform starter defines graceful shutdown: "stop accepting new requests, drain in-flight requests, close Kafka consumers (commit offsets), close database connections." But this does not account for in-flight instant payments that span multiple services and involve uncommitted state:

- A2 has an in-flight payment with an active balance reservation in A1.
- A2 begins graceful shutdown. It stops consuming new instructions.
- But the current payment is between "reserve confirmed" and "fraud check response received."
- If A2 shuts down before completing the payment, the reservation is orphaned (see CRITICAL-01).

Standard Spring Boot graceful shutdown drains HTTP requests, but Kafka Streams and async multi-service workflows require explicit handling.

**Recommendation**:
1. Add requirement to A2: "During graceful shutdown, A2 must complete or abort all in-flight instant payments before stopping. For payments awaiting external responses (A5), apply the same timeout logic as normal operation. For payments that cannot complete within the shutdown grace period, explicitly release balance reservations."
2. Add requirement: "The shutdown grace period for A2 must be at least 10 seconds (matching the instant payment SLA) to allow in-flight payments to complete."

---

### HIGH-02: Kafka Streams State Store Recovery

**Location**: A5 requirements (Section 6), FRD-F02, FRD-F03, FRD-F10

**Problem**: A5 uses Kafka Streams with RocksDB state stores for:
- Velocity windows (tumbling 60-min and 24-hour windows)
- Account profiles (rolling 90-day statistics)
- Transaction counters

When a Kafka Streams instance crashes and restarts, it must rebuild its state stores from Kafka changelog topics. During this recovery period:
- Velocity rules cannot fire accurately (window state is incomplete)
- Anomaly detection has no profile data (everything looks anomalous)
- The service may produce inaccurate fraud scores

The requirements do not specify:
- Whether state store recovery is acceptable (and what accuracy guarantees exist during recovery)
- Whether there should be a "warm-up" period during which the service does not accept scoring requests
- How long state store recovery takes for a typical state size

**Recommendation**:
1. Add requirement: "A5 must implement standby replicas for Kafka Streams state stores (`num.standby.replicas >= 1`) to enable fast failover without full state rebuild."
2. Add requirement: "During state store recovery, A5 must either (a) reject scoring requests with a TEMPORARILY_UNAVAILABLE status, or (b) use degraded scoring with only the ML model and sanctions screening (which use separate, non-state-store data)."
3. Add non-functional requirement: "State store recovery time for A5 after a crash must be < 60 seconds (with standby replicas)."

---

### HIGH-03: VRP Cumulative Counter Crash Consistency

**Location**: VRP-F05, VRP-F08, UC-10 (variable_recurring_payments.md)

**Problem**: VRP consent enforcement relies on cumulative counters (current_period_amount, current_period_count) that are updated after each payment. UC-10 Scenario 10B shows:
- Step 2: A4 validates payment against consent bounds (check cumulative counters)
- Step 11: A4 updates cumulative counters

If A4 crashes between step 2 (validation passes, payment proceeds to execution) and step 11 (counter update), the counters are stale. The next VRP payment will be validated against incorrect counters, potentially allowing the TPP to exceed consent bounds.

This is a financial safety control that fails open on crash.

**Recommendation**:
1. Add requirement: "VRP cumulative counters must be updated atomically with the payment initiation record, in the same database transaction. The counter update must happen BEFORE the payment instruction is published to Kafka, not after."
2. Add requirement: "On A4 startup, reconcile VRP cumulative counters against actual payments executed under each consent since the last period reset."

---

### HIGH-04: SCA Exemption Counter Consistency

**Location**: EXE-F01, EXE-F07 (A4 requirements)

**Problem**: Similar to HIGH-03. The low-value SCA exemption tracks `exempt_count_since_sca` and `exempt_amount_since_sca`. These must be updated when a payment is exempted from SCA. If the counter update fails but the payment proceeds, the next payment might incorrectly receive another exemption beyond the legal limits (5 payments or EUR 100).

PSD2 violation: allowing more exempt payments than legally permitted.

**Recommendation**:
1. Add requirement: "SCA exemption counters must be updated in the same database transaction as the payment consent/authorization record."
2. Add requirement: "Counter increments must use SELECT FOR UPDATE to prevent concurrent exempt payments from exceeding cumulative limits."

---

### HIGH-05: Webhook Delivery Crash Recovery

**Location**: RTP-W01 through RTP-W08 (A3 requirements)

**Problem**: A3 sends webhooks to merchants on payment status changes. The WebhookDelivery table tracks delivery status and retry count. But if A3 crashes:
1. **After status change, before webhook delivery record creation**: The status changed but no webhook is scheduled. The merchant is never notified.
2. **After creating delivery record, before first delivery attempt**: The record exists with status PENDING. On restart, is there a process that scans for PENDING deliveries and retries them?
3. **During retry backoff**: The next_retry_at is set but A3 is down. After restart, does it check for overdue retries?

The requirements specify the retry mechanism but not the crash recovery for the retry mechanism itself.

**Recommendation**:
1. Add requirement: "Webhook delivery records must be created in the same database transaction as the payment request status change."
2. Add requirement: "On startup, A3 must scan for WebhookDelivery records with status PENDING or with next_retry_at in the past, and resume delivery attempts."
3. Add requirement: "A scheduled job must periodically scan for stuck deliveries (status PENDING for > configurable threshold) and either retry or escalate."

---

### HIGH-06: Network Partition Handling

**Location**: Absent from all documents

**Problem**: The system is a distributed system of 6 services communicating via Kafka and REST. No requirements address network partitions:
- What if A2 can reach Kafka but not A1's REST API during an instant payment?
- What if the Kafka cluster itself experiences a partition (split-brain)?
- What if A6's external PSP network calls time out due to network issues?

VoP (A6) has a "proceed with flag" degradation (VOP-R06), and fraud (A5) has a timeout auto-pass mechanism. But there's no general policy for how services should behave when they can't reach their dependencies.

**Recommendation**:
1. Add cross-cutting requirement: "Each service must define explicit fallback behavior for each of its dependencies: (a) which dependencies are hard blockers (sanctions = hard blocker), (b) which allow degraded operation (fraud scoring = degraded OK), (c) maximum wait time before applying fallback."
2. Add requirement: "Circuit breaker pattern must be implemented for all synchronous REST calls between services (A2→A1 for balance reservation, A6→A1 for name lookup, A4→A6 for VoP check)."
3. Add requirement: "All inter-service REST calls must have explicit connect and read timeouts configured."

---

### HIGH-07: Projection Rebuild Consistency During Live Traffic

**Location**: LED-F09, LED-F40 through F42, Scenario 1E (ledger_operations.md)

**Problem**: LED-F09 requires the ability to rebuild all projections from the event log. Scenario 1E describes: "stop projection consumer, truncate balance table, reset offset to 0, replay all events." During this rebuild:
- A1's balance queries (LED-F30 through F35) return stale/empty data.
- Any balance reservation requests from A2 will fail or use stale data.
- Other services querying A1 will receive incorrect balances.
- Instant payments are still arriving 24/7/365.

There is no requirement for how to handle live traffic during a projection rebuild.

**Recommendation**:
1. Add requirement: "Projection rebuild must be performed against a secondary database replica (blue-green). Traffic switches to the new projection only after rebuild is complete and verified."
2. Add requirement: "The readiness probe (`/actuator/health/readiness`) must report DOWN during projection rebuild, causing the load balancer to route traffic to other A1 instances."
3. Add requirement: "Projection rebuild must never be triggered on all A1 instances simultaneously."

---

### HIGH-08: Dead Letter Queue for Failed Message Processing

**Location**: Absent from all Kafka-related requirements

**Problem**: The requirements specify that services consume messages from Kafka and process them. But they don't specify what happens when processing fails — not due to business logic (those cases are handled), but due to deserialization errors, schema incompatibilities, unexpected data formats, or programming errors.

If a Kafka consumer encounters a message it cannot process, it has three options: (a) skip and lose the message, (b) retry indefinitely (blocking the partition), (c) send to a dead letter queue for investigation. None of these options is specified.

For a payment system, losing a message (option a) is unacceptable. Blocking the partition (option b) stops all subsequent payments. Dead letter queues (option c) are the standard approach but must be monitored.

**Recommendation**:
1. Add cross-cutting requirement: "Each Kafka consumer must implement a Dead Letter Queue (DLQ). Messages that fail processing after N configurable retries must be sent to a `{topic}.dlq` topic."
2. Add requirement: "DLQ topics must be monitored. Any message in a DLQ triggers an operational alert."
3. Add requirement: "DLQ messages must include the original message, the error details, and the processing timestamp."

---

## PART 2: GENERAL REQUIREMENTS REVIEW FINDINGS

Beyond computational stability, these findings address completeness, consistency, and correctness of the requirements.

---

### MEDIUM-01: Missing Data Retention and GDPR Requirements

**Location**: Absent from all documents

**Problem**: The system stores extensive PII: account holder names, IBANs, transaction histories, fraud scores, sanctions screening results, OAuth2 consent records. There are no requirements for:
- Data retention periods (how long to keep transaction history, audit logs, fraud alerts)
- Right to erasure (GDPR Article 17) — how does "right to be forgotten" interact with the immutable event log?
- Data minimization — are all stored fields necessary?
- Legal hold — how to preserve data for ongoing investigations while deleting for GDPR compliance?

The tension between event sourcing immutability (LED-S05: "events cannot be modified or deleted") and GDPR's right to erasure is a known challenge. The requirements should acknowledge this and specify an approach (e.g., crypto-shredding: encrypt PII in events with a per-individual key; "delete" by destroying the key).

**Recommendation**:
1. Add cross-cutting GDPR requirements specifying retention periods per data category.
2. Add requirement addressing the immutability-vs-erasure tension, likely via crypto-shredding.
3. Define data retention for Kafka topics and PostgreSQL tables.

---

### MEDIUM-02: Inconsistent VoP Timeout Behavior

**Location**: VOP-R06 ("proceed with payment but flag for review"), SEC-12 (master requirements: "every credit transfer must pass VoP check"), A6-F10

**Problem**: There is a contradiction:
- SEC-12 states VoP is mandatory for every credit transfer.
- VOP-R06 and A6-F10 say if VoP times out, "proceed with payment but flag for review."
- These two positions are inconsistent: if VoP is mandatory, a timeout should block the payment (like sanctions). If VoP can be skipped on timeout, it's not truly mandatory.

The regulation (EU IPR Art. 5c) actually requires the VoP check to be performed, but allows proceeding if the response is unavailable — so VOP-R06 is closer to the regulatory intent. But SEC-12's wording ("must pass VoP check") is misleading.

**Recommendation**:
1. Reword SEC-12 to: "Every credit transfer must trigger a VoP check before execution. If the VoP service is unavailable or times out, the payment may proceed with the result recorded as TIMEOUT and flagged for review."
2. Explicitly distinguish VoP from sanctions: "VoP is a mandatory check with soft degradation (proceed on timeout). Sanctions is a mandatory check with hard degradation (reject on timeout)."

---

### MEDIUM-03: No Multi-Datacenter Deployment Model

**Location**: CLR-I03 (24/7/365), CLR-NF04 (99.99%), VOP-NF03 (99.99%), NFR-09 (99.99%)

**Problem**: Multiple services require 99.99% availability, which is 52 minutes of downtime per year. This is unachievable with a single-instance deployment. The requirements specify `docker-compose up` for local development (OPS-04), but never address production-grade deployment topology.

While this may be intentional (it's a portfolio project), the 99.99% SLA is stated as a hard requirement. Without redundancy requirements, this SLA is a promise that cannot be kept.

**Recommendation**: Either (a) add requirements for multi-instance deployment with load balancing and failover, or (b) explicitly note that 99.99% availability is a target for production deployment and the docker-compose environment is a development/demo configuration.

---

### MEDIUM-04: Undefined Behavior for Late-Arriving Fraud Responses

**Location**: UC-08 AF-05, Scenario 1C (clearing use cases)

**Problem**: When A5 times out during instant payment processing, A2 auto-passes. But A5 may eventually respond — possibly after the payment is already settled. Scenario 1C notes: "A5 eventually responds (late). Result logged but payment already settled."

What if A5's late response is AUTO_BLOCK or SANCTIONS_HIT? The payment is already irrevocable (A2-F23). The requirements don't specify:
- Is the late response ignored entirely?
- Does it create a retrospective alert for investigation?
- Could it trigger a payment return (pacs.004)?
- How is this recorded in the audit trail?

**Recommendation**:
1. Add requirement: "If A5 returns a score of MANUAL_REVIEW or AUTO_BLOCK for a payment that has already been settled (due to A5 timeout → auto-pass), create a retrospective fraud alert with flag SETTLED_BEFORE_SCORE."
2. Add requirement: "If A5 returns SANCTIONS_HIT for an already-settled payment, create a high-priority compliance alert and initiate the payment return process (pacs.004)."

---

### MEDIUM-05: Kafka Schema Evolution Failure Handling

**Location**: LED-F07 ("forward/backward compatible evolution"), DATA-06

**Problem**: The requirements mandate Avro serialization with Schema Registry for schema evolution. But they don't specify what happens when schema evolution goes wrong:
- A producer publishes an event with schema v3, but a consumer only understands v1 and v2.
- A schema registry becomes unavailable.
- An incompatible schema change is accidentally registered.

These are common operational failures that can halt the entire pipeline.

**Recommendation**:
1. Add requirement: "Schema compatibility checks must be enforced at the Schema Registry level (BACKWARD or FULL compatibility mode)."
2. Add requirement: "Consumers must handle unknown fields gracefully (Avro's forward compatibility) without failing."
3. Add requirement: "Schema Registry availability must be monitored. If unavailable, producers must cache the last known schema and continue operating."

---

### LOW-01: Reconciliation Detects But Does Not Correct

**Location**: LED-F37 through F39, Scenario 1G (ledger_operations.md)

**Problem**: The reconciliation system detects discrepancies between the ledger (A1) and settlement reports (A2). Discrepancies trigger alerts. But there is no automated or semi-automated correction mechanism. In a real payment system, discrepancy resolution is a formal process with:
- Classification (timing difference, amount mismatch, missing entry)
- Automated resolution for known patterns (e.g., timing differences resolve when the next cycle completes)
- Manual resolution workflow for genuine mismatches
- Adjustment entries in the ledger to correct discrepancies

**Recommendation**: For a portfolio project, detection is sufficient. But add a note in the requirements: "Discrepancy resolution is manual in v1. Future versions should support automated resolution for common discrepancy types (timing differences) and manual resolution workflows for genuine mismatches."

---

### LOW-02: No Circuit Breaker for Webhook Delivery

**Location**: RTP-W04 (retry with exponential backoff, max 5 retries)

**Problem**: If a merchant's webhook endpoint is permanently down, A3 will attempt 5 retries per status change. If a merchant has 1,000 pending payment requests changing status, that's 5,000 failed HTTP calls. There's no circuit breaker to detect that a merchant's endpoint is consistently failing and stop wasting resources.

**Recommendation**: Add requirement: "If a merchant's webhook endpoint fails for N consecutive delivery attempts across different requests (configurable, default: 10), mark the merchant's webhook as SUSPENDED. Resume only on manual reactivation or after a cool-down period."

---

### LOW-03: Snapshot Corruption Detection

**Location**: LED-F40 through F42 (A1 snapshot mechanism)

**Problem**: The snapshot mechanism stores periodic balance snapshots to PostgreSQL to speed up projection rebuilds. If a snapshot is corrupted (partial write due to crash, disk error), a projection rebuild starting from that snapshot would produce incorrect balances. The requirements don't specify integrity verification for snapshots.

**Recommendation**: Add requirement: "Each snapshot must include a checksum (e.g., SHA-256 of the serialized balance state). Before using a snapshot for rebuild, verify its checksum. If verification fails, fall back to the previous valid snapshot or rebuild from the beginning."

---

## PART 3: POSITIVE OBSERVATIONS

The review identified several areas of exceptional strength that should be preserved.

1. **Event sourcing as foundation** (A1): The choice of event sourcing for the account ledger is architecturally sound for a payment system. Immutable events provide an inherent audit trail, and the ability to rebuild projections from the event log is a powerful recovery mechanism.

2. **Hard timeout for instant payments** (CLR-I10): The 10-second hard boundary with mandatory REJECT eliminates limbo states. This is exactly right — a payment should never be in an undefined state.

3. **Sanctions vs. fraud asymmetry**: The different failure modes (sanctions: reject on unavailability; fraud: auto-pass on timeout) reflect real regulatory requirements and demonstrate domain understanding.

4. **Semantic duplicate detection** (CLR-B13): Warn-but-don't-block is the correct design choice. Many legitimate payments are superficially similar to duplicates.

5. **Balance reservation model** (LED-F10 through F13): The three-state reservation model (reserve → confirm/release) is the standard pattern for instant payments. The concept is correct; the implementation needs the crash-recovery guarantees identified in this review.

6. **Reconciliation framework** (LED-F36 through F39): External references on events enabling automated reconciliation shows operational maturity. This is what production payment systems do.

7. **Comprehensive Kafka topic topology** (master requirements Section 9.1): Clean separation of concerns with dedicated topics for batch vs. instant, clear producer/consumer mapping.

8. **Anti-enumeration protection** (VOP-E01 through E04): Rate limiting and repeated-query detection on VoP responder API shows security awareness.

---

## PART 4: SUMMARY OF RECOMMENDATIONS

### Priority 1 — Must address before implementation begins:

| ID | Summary | Affected sub-systems |
|----|---------|---------------------|
| CRITICAL-01 | Elevate reservation timeout to "Must"; specify persistent timeout and crash recovery | A1, A2 |
| CRITICAL-02 | Ensure double-entry events are published atomically | A1 |
| CRITICAL-03 | Address dual-write problem with Transactional Outbox or single-source pattern | All |
| CRITICAL-04 | Specify batch clearing crash recovery and restartability | A2 |
| CRITICAL-05 | Define exactly-once semantics implementation across service boundaries | A2, A5, A1 |
| CRITICAL-06 | Atomic sanctions list ingestion with double-buffering | A5 |
| CRITICAL-07 | Add disaster recovery, Kafka durability, and data persistence requirements | All |

### Priority 2 — Should address during implementation:

| ID | Summary | Affected sub-systems |
|----|---------|---------------------|
| HIGH-01 | Graceful shutdown must complete or abort in-flight instant payments | A2, Starter |
| HIGH-02 | Kafka Streams state store recovery with standby replicas | A5 |
| HIGH-03 | VRP counter crash consistency | A4 |
| HIGH-04 | SCA exemption counter crash consistency | A4 |
| HIGH-05 | Webhook delivery crash recovery | A3 |
| HIGH-06 | Network partition handling and circuit breakers | All |
| HIGH-07 | Projection rebuild during live traffic | A1 |
| HIGH-08 | Dead letter queues for failed Kafka processing | All |

### Priority 3 — Address for completeness:

| ID | Summary | Affected sub-systems |
|----|---------|---------------------|
| MEDIUM-01 | GDPR data retention and right-to-erasure vs. immutability | All |
| MEDIUM-02 | Clarify VoP timeout behavior (soft vs. hard mandatory) | A6, master |
| MEDIUM-03 | Multi-datacenter deployment model for 99.99% SLA | All |
| MEDIUM-04 | Late-arriving fraud scores for already-settled payments | A2, A5 |
| MEDIUM-05 | Kafka schema evolution failure handling | All |

---

*End of review.*
