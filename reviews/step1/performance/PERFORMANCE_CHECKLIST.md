# Performance Under Load Review Checklist

*Purpose: Identify architectural decisions that could become performance bottlenecks under load*
*Date: 2026-04-12*
*Based on: Java/Spring Boot performance patterns, Kafka tuning, PostgreSQL performance, payment system benchmarks*

---

## Category PF1: Latency Budget Under Load

### PF1.1 — Time Budget Composition Under Contention
Verify: the 5-second EPC SLA time budget was verified for happy path. Under load, contention adds latency. Check: database lock wait times, Kafka produce latency with `acks=all`, connection pool wait times. Do the per-service latency targets still hold at 10x load?

### PF1.2 — Kafka Produce Latency with acks=all
Verify: `acks=all` adds ~5-15ms per produce. With multiple Kafka produces per payment (reservation, fraud check, settlement, status update), the cumulative Kafka overhead could be 20-60ms. Is this within the time budget?

### PF1.3 — Database Query Latency Under Concurrent Load
Verify: A1 balance queries target <100ms. Under concurrent reservations for different accounts (parallel Kafka consumers), does PostgreSQL connection pool contention add latency?

## Category PF2: Lock Contention

### PF2.1 — A4 SCA Exemption Counters (SELECT FOR UPDATE)
Verify: A4 uses pessimistic locking on `sca_exemption_counters`. Under high concurrent SCA requests for the same customer, this serializes all requests for that customer. Is this acceptable? What's the expected contention level?

### PF2.2 — A1 Reservation Scanner vs Consumer Race
Verify: A1's `@Scheduled` scanner uses `SELECT FOR UPDATE SKIP LOCKED`. Under high reservation volume, does the scanner contend with consumer-driven updates?

### PF2.3 — A3 State Machine CAS (Compare-and-Swap)
Verify: A3 uses optimistic concurrency for state transitions. Under concurrent status updates for the same R2P request, CAS retries add latency. Is the retry strategy bounded?

## Category PF3: Connection and Thread Pool Sizing

### PF3.1 — Connection Pool Formula
Verify: are connection pools sized based on core count formula (cores × 2 + effective_spindle_count), or are they guessed? Document the formula and sizing rationale.

### PF3.2 — Thread Pool vs Connection Pool Ratio
Verify: if Tomcat has 200 threads but HikariCP has 10 connections, 190 threads may block waiting for connections under load. Is the ratio balanced?

### PF3.3 — Separate Pools for Batch/Instant (A2)
Verify: A2's batch and instant instances have separate connection pools. A2 instant: min=10, max=20. A2 batch: min=5, max=30. Are these appropriate for their workloads?

## Category PF4: Kafka Consumer Performance

### PF4.1 — max.poll.records vs Processing Time
Verify: if A2 or A5 processes each record in 50ms and max.poll.records=500, a single poll takes 25 seconds — exceeding max.poll.interval.ms (5 min default but still wastes resources). Is max.poll.records tuned per consumer?

### PF4.2 — Consumer Lag Under Burst
Verify: if a burst of 10K instant payment instructions arrives, how long before consumer lag is absorbed? Is there monitoring for lag growing rather than shrinking?

### PF4.3 — Exactly-Once Transaction Overhead
Verify: exactly_once_v2 adds ~20-30% throughput overhead. At expected volumes, is this acceptable? At 10x volumes?

## Category PF5: Serialization and Network

### PF5.1 — Avro Serialization Overhead
Verify: Avro serialization/deserialization for every Kafka message. At high throughput (>5000 msg/s), is serialization CPU a bottleneck? Is Schema Registry a latency-adding hop on every serialize?

### PF5.2 — Settlement Report Size
Verify: camt.053 XML reports for batch clearing cycles. With 10K instructions per cycle, how large is the XML? At 100K instructions? Is streaming generation used or does the full XML live in memory?

### PF5.3 — REST Response Size for Batch Status
Verify: A2's investigation queue or A3's bulk operations — can responses grow large? Pagination documented?

## Category PF6: GC and Memory

### PF6.1 — GC Strategy for Latency-Sensitive Services
Verify: A2 instant and A5 instant have sub-second latency requirements. Is ZGC or Shenandoah recommended? Is G1 with appropriate pause target documented?

### PF6.2 — BigDecimal Allocation Pressure
Verify: BigDecimal is immutable — every arithmetic operation creates a new object. In the netting algorithm processing 10K payments, this generates significant garbage. Is this a concern?

### PF6.3 — Container Memory Budgeting
Verify: is JVM heap sized at ~75% of container memory limit, leaving room for thread stacks, metaspace, native memory, OS buffers?

## Category PF7: Caching and Hot Path

### PF7.1 — A6 Cache Miss Storm
Verify: if A6's cache is cold (restart, eviction) and high query volume arrives, all queries hit A1 simultaneously. Pre-warming mitigates but what about eviction-driven miss storms during normal operation?

### PF7.2 — Schema Registry as Latency Hop
Verify: Schema Registry lookup on every Avro serialize/deserialize adds latency. Is schema caching configured on the client side?

### PF7.3 — A4 Consent Check Database Round-Trip
Verify: A4 checks consent from database on EVERY request (no cache — by design for immediate revocation). At high QPS, this adds a database round-trip to every API call. Is this sustainable?
