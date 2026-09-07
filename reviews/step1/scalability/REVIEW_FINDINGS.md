# Scalability + Performance Review — Findings

*Date: 2026-04-12*

---

## Summary

35 checks performed. 13 PASS, 19 CONCERN, 2 BLOCKER, 1 NOT DOCUMENTED.

---

## BLOCKER (1 root cause, 2 manifestations)

### SP1 — Unbounded Kafka Storage Growth [BLOCKER]

`ledger.events` (infinite retention) and `audit.events` (5-year retention) grow without bound. At 1000 TPS: ~31TB/year for ledger.events alone (×3 for replication = ~93TB/year raw disk). No tiered storage, archival, or lifecycle management documented.

**Action:** Document a tiered storage strategy as an architectural requirement: Kafka KIP-405 tiered storage (offload cold segments to S3), OR periodic export to write-once storage with offset-based truncation after verified backup. This is not just a backup concern — it's a storage scalability blocker.

---

## KEY CONCERNS (top 5 by impact)

### SP2 — PostgreSQL Tables Grow Without Partitioning [HIGH CONCERN]

`clearing_records` (86M rows/day), `transaction_history` (172M rows/day), `vop_query_log` (173M rows/day) have no partitioning strategy. At production volumes, tables reach billions of rows within months. Index maintenance, VACUUM, and query performance degrade rapidly.

**Action:** Document PostgreSQL range partitioning by `created_at` for all high-write tables. Add retention/archival policy (partition drop after retention period).

### SP3 — A1 Reservation Throughput Limit at Scale [CONCERN]

A1's per-partition sequential processing limits reservation throughput to ~150-190 concurrent reservations/second (12 partitions × ~13-16 ops/sec/partition at ~60-80ms each). Adequate for 500 TPS but not for significant growth.

**Action:** Document as a scaling limit. Partition scaling path needed (increase partitions + instances).

### SP4 — No Partition Scaling Procedure Documented [CONCERN]

Partitions called "configurable" but no procedure for expanding partitions on stateful Kafka Streams consumers (state store rebalancing, changelog topic impact).

**Action:** Document partition expansion procedure.

### SP5 — No GC Recommendation for Latency-Sensitive Services [CONCERN]

A2 instant and A5 instant have sub-second latency requirements but no GC algorithm recommendation. G1GC (default) may cause occasional >100ms pauses. ZGC or Shenandoah more appropriate.

**Action:** Add JVM GC guidance to deployment documentation.

### SP6 — Schema Registry and PostgreSQL as Undocumented SPOFs [CONCERN]

Neither Schema Registry clustering nor PostgreSQL replication topology is documented. Both are single points of failure.

**Action:** Document HA deployment topology as production requirement.

---

## Additional Concerns (lower priority)

| ID | Finding | Action |
|----|---------|--------|
| SP7 | Kafka consumer tuning (max.poll.records, etc.) not documented per service | Document per-service tuning |
| SP8 | Container memory budgeting (-Xmx vs container limit) only documented for A6 | Add to all services |
| SP9 | A1 as shared REST bottleneck (VoP + AISP + analyst queries) — no read replica | Document read replica as production option |
| SP10 | Connection pool sizing not formula-based for A1, A5 | Add formula/rationale |
| SP11 | Hot partition risk for accountId-keyed topics — monitoring only | Acceptable for current scale |
| SP12 | Cache miss storm after A6 restart — 8-minute warm-up quantified but not mitigated beyond warming | Acceptable with documented degradation |
| SP13 | PgBouncer not documented for multi-instance deployments | Document as production requirement |
| SP14 | Autovacuum tuning not documented for high-write tables | Document as operational requirement |

---

## Positive Findings

- Netting algorithm is O(N + P²) with P typically small — not a bottleneck
- Separate JVM instances for batch/instant prevent resource contention
- Separate connection pools prevent pool exhaustion across workloads
- A6 cache sizing and warming strategy is well-designed
- exactly_once_v2 overhead acceptable within time budget margins
- Avro serialization CPU is negligible at expected volumes
- BigDecimal allocation pressure handled well by modern GC
- A4 consent DB check sustainable (indexed PK lookup <1ms)
