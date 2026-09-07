# Crash Resilience Review — Consolidated Findings

*Date: 2026-04-12*
*Scope: Master project + 7 subprojects*
*Phases: 3 review phases covering R1-R13 (excluding R10 chaos test plan and R12 consistency patterns — these are test plans, not architecture checks)*

---

## Overall Assessment

The system's crash resilience is **strong**. Event sourcing (A1), exactly-once semantics (A2, A5), and transactional outbox (A3, A4) provide robust crash recovery for every step in the payment flow. Out of 17 crash points in the instant payment flow, **16 are fully SAFE** with documented, mechanically sound recovery.

The findings fall into three categories:
1. **One genuine race condition** that needs explicit documentation (CONFIRM-after-RELEASE)
2. **One systemic issue** — all timeouts use wall clocks, vulnerable to clock jumps
3. **Infrastructure gaps** — missing Kafka/JVM configurations and no backup beyond Kafka replication

---

## Findings by Severity

### HIGH (3)

| ID | Finding | Category | Risk |
|----|---------|----------|------|
| **CR2.1** | All timeouts use wall clocks (`Instant.now()`, SQL `now()`). Clock jump can prematurely reject payments or delay timeout detection. `System.nanoTime()` or Kafka `STREAM_TIME` not considered. | R11.5 | NTP correction during payment processing could violate 7-second EPC SLA |
| **CR2.2** | `unclean.leader.election.enable=false` not documented for Kafka brokers. Without it, `exactly_once_v2` and `acks=all` guarantees can be violated after unclean leader election. | R11.1 | Kafka data loss after broker failure + unclean election |
| **CR3.1** | `ledger.events` Kafka topic has no backup beyond Kafka's built-in replication (factor=3). Total loss of all 3 replicas = unrecoverable data loss for the entire system. | R13.2 | Catastrophic, unrecoverable data loss |

### MEDIUM (5)

| ID | Finding | Category |
|----|---------|----------|
| **CR1.1** | Reservation CONFIRM-after-RELEASE idempotency not explicitly documented. If A2 crashes after settlement but before CONFIRM, auto-release fires, then recovery sends CONFIRM. | R1.5b |
| **CR2.3** | A1 reservation timeout scanner vs Kafka consumer race on same reservation row — concurrency control not documented | R4.2 |
| **CR2.4** | `-XX:+ExitOnOutOfMemoryError` not documented as mandatory JVM flag | R11.4 |
| **CR2.5** | `acks=all` not auto-configured by Platform Starter — developer could create producer with `acks=1` | R11.2 |
| **CR3.2** | Reconciliation discrepancy alerting/escalation not documented — reconciliation publishes to topic but who consumes and what happens next is unspecified | R6.3 |

### LOW (4)

| ID | Finding | Category |
|----|---------|----------|
| **CR2.6** | `fsync=on` not documented as PostgreSQL infrastructure requirement | R11.3 |
| **CR3.3** | DORA: no incident classification taxonomy (P1/P2/P3/P4), no escalation matrix, no documented backup/restoration procedures | R9 |
| **CR3.4** | Full A1 projection rebuild from scratch not explicitly listed as a tested scenario | R13.1 |
| **CR3.5** | Kafka consumer rebalance latency impact on instant SLA not quantified | R8.2 |

---

## Statistics

| Severity | Count |
|----------|-------|
| High | 3 |
| Medium | 5 |
| Low | 4 |
| **Total** | **12** |

---

## What Works Well

1. **16 of 17 instant payment crash points are SAFE** — documented, mechanically sound recovery at every step
2. **Event sourcing (A1) is inherently crash-resilient** — events are the source of truth, projections are rebuildable, crashes either replay or catch up
3. **exactly_once_v2 (A2, A5) provides strong atomicity** — state store + output + offset commit are atomic, preventing all partial-state scenarios
4. **Transactional Outbox (A3, A4) eliminates the DB-Kafka gap** — outbox relay on startup + scheduled fallback + downstream dedup
5. **Three-layer reservation safety net** — A2 explicit release (7s), A1 auto-release (30s), A1 startup scan
6. **Audit dual-write with hash chain recovery** — local log (sync, compliance-primary) + Kafka (async) + GENESIS markers for restart detection
7. **A2 explicitly acknowledges and mitigates its PostgreSQL-Kafka gap** — 5-minute reconciliation for clearing_records

---

## Recommended Fix Order

### Immediate (before implementation)

1. **CR2.1 [HIGH]** — Switch A2's `TimeoutPunctuator` to use `System.nanoTime()` for elapsed time calculation (or `PunctuationType.STREAM_TIME`). Document clock skew tolerance. Add NTP synchronization as infrastructure requirement.

2. **CR2.2 [HIGH]** — Add `unclean.leader.election.enable=false` to the master Kafka configuration section and INTERFACE_CONTRACTS.md.

3. **CR1.1 [MEDIUM]** — Document in A1 that CONFIRM on an already-RELEASED reservation is a no-op. Add integration test.

### Before production

4. **CR3.1 [HIGH]** — Implement periodic export of `ledger.events` to write-once storage (S3 with object lock, or similar). This is the single unrecoverable data loss scenario.

5. **CR2.3 [MEDIUM]** — Document A1's concurrency control between timeout scanner thread and reservation handler consumer thread.

6. **CR2.4 [MEDIUM]** — Add `-XX:+ExitOnOutOfMemoryError` to Platform Starter or deployment template.

7. **CR2.5 [MEDIUM]** — Auto-configure `acks=all` in Platform Starter's Kafka module.

### Documentation improvements

8. **CR3.2 [MEDIUM]** — Define who consumes `payment.reconciliation-results` and the escalation path for discrepancies.
9. **CR2.6, CR3.3-CR3.5 [LOW]** — Infrastructure docs, DORA compliance, rebuild testing, rebalance impact.
