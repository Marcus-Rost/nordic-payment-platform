# Scalability Review Checklist

*Purpose: Identify architectural decisions that could become obstacles to scaling*
*Date: 2026-04-12*
*Based on: Kafka scalability patterns, PostgreSQL scaling, microservice scaling best practices, payment system benchmarks*

---

## Category SC1: Kafka Scalability

### SC1.1 — Partition Count Headroom
Verify: are payment topics configured with enough partitions to scale consumers? Current: 12 partitions per topic. Can this handle 10x growth? Can partitions be increased without downtime?

### SC1.2 — Partition Key Distribution
Verify: are partition keys well-distributed? `transactionId` (UUID) → uniform. `accountId` → potentially skewed if some accounts have much higher volume. Check for hot partition risk.

### SC1.3 — Kafka Streams State Store Scaling
Verify: A2 and A5 use RocksDB state stores. As payment volume grows, state store size grows. Are standby replicas configured to reduce failover time? Is state store restoration time acceptable?

### SC1.4 — Event Store Growth (ledger.events)
Verify: `ledger.events` has infinite retention. At 1000 TPS × 1KB = 86GB/day = 31TB/year. Is there a tiered storage or archival strategy? Is snapshot frequency sufficient to keep replay times manageable?

### SC1.5 — Consumer Group Scaling
Verify: can consumer count scale independently? With 12 partitions, max 12 consumers per group. Is this sufficient? Is Cooperative Sticky Assignor configured to minimize rebalance disruption?

## Category SC2: PostgreSQL Scalability

### SC2.1 — Connection Pool Sizing
Verify: connection pool sizes documented per service. A2 instant: min=10, max=20. Are these sufficient under peak load? Are they calculated based on core count, not guessed?

### SC2.2 — Table Growth and Partitioning Strategy
Verify: high-volume tables (clearing_records, transaction_history, vop_query_log) have a growth management strategy. Partitioning by date? Archival of old data? VACUUM tuning for large tables?

### SC2.3 — Transaction ID Wraparound Risk
Verify: is autovacuum configured aggressively enough for high-write tables? Is `age(datfrozenxid)` monitoring documented?

### SC2.4 — Read Replica Strategy
Verify: for read-heavy query patterns (reporting, analytics, AISP balance lookups), is a read replica strategy documented? Is the replication lag tolerance defined?

### SC2.5 — PgBouncer for Multi-Instance Deployments
Verify: when scaling to many service instances, is PgBouncer documented as the connection multiplexer? Without it, N instances × M connections per instance can exhaust PostgreSQL.

## Category SC3: Service Scalability

### SC3.1 — Stateless Services Scale Horizontally
Verify: A3, A4, A6 can add instances by deploying more pods. What state is lost? (A6: cache, anti-enumeration — documented Redis upgrade)

### SC3.2 — Stateful Services (A2, A5) Scaling
Verify: A2 and A5 use Kafka Streams with state stores. Scaling = adding partitions + instances. Is the repartitioning documented? Is standby replica configured?

### SC3.3 — Single Points of Failure
Verify: is there any component that cannot be replicated? Kafka (multi-broker), PostgreSQL (primary + replica), services (multi-instance). What about Schema Registry? Prometheus?

### SC3.4 — Cache Sizing at Scale (A6)
Verify: A6's 100K cache entries — sufficient at scale? If the bank has 1M+ accounts, hit rate drops below 95% target. Is the Redis L2 upgrade path realistic?

## Category SC4: Data Growth Management

### SC4.1 — Audit Trail Growth
Verify: `audit.events` Kafka topic with infinite retention. How large does it get? Is there a lifecycle management strategy?

### SC4.2 — Query Log Growth (A6)
Verify: `vop_query_log` PostgreSQL table grows with every VoP query. At 2000 QPS, that's 173M rows/day. Is partitioning or archival documented?

### SC4.3 — Settlement Report Storage
Verify: camt.053 XML reports generated per clearing cycle. At scale, storage management needed.

## Category SC5: Architectural Scaling Limits

### SC5.1 — Synchronous Call Chains
Verify: are there synchronous call chains that become bottlenecks at scale? A3→A6→A1 is synchronous REST. Under load, A1 becomes the bottleneck for all VoP queries. Circuit breaker + cache mitigate this, but is it sufficient at 10x current scale?

### SC5.2 — Single Kafka Cluster
Verify: all services share one Kafka cluster. At what point does this become a bottleneck? Is multi-cluster (MirrorMaker) documented as a scaling path?

### SC5.3 — Netting Algorithm Complexity
Verify: A2's netting algorithm with N participants — O(N²) bilateral matrix? At what N does it become a bottleneck? Is this acceptable for expected participant counts?
