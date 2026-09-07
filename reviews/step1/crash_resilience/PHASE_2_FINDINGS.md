# Crash Resilience Review Phase 2 — Dual-Write, Reservations, Infrastructure (R3+R4+R11)

*Date: 2026-04-12*

---

## Summary

11 checks performed. 4 PASS, 5 PARTIAL, 1 FAIL, 1 PASS (acknowledged gap with mitigation).

---

## Critical Finding

### CR2.1 — All Timeouts Use Wall Clocks — Clock Jump Risk [HIGH]

**R11.5:** Every timeout in the system — A2's 7-second hard-limit punctuator, A1's 30-second reservation scanner, A4's 5-minute SCA timeout — uses wall-clock time (`Instant.now()`, SQL `now()`). No monotonic clock (`System.nanoTime()`) is used anywhere.

**Risk:** If NTP corrects the system clock forward by 2 seconds during instant payment processing, the punctuator sees elapsed time as 2 seconds more than actual — could prematurely reject a payment that was within the 7-second SLA. Conversely, a backward jump delays timeout detection.

**Action:** 
- Switch A2's `TimeoutPunctuator` to `PunctuationType.STREAM_TIME` (monotonic within Kafka partition) or use `System.nanoTime()` for elapsed time measurement
- Document clock skew tolerance for all time-critical operations
- Add NTP synchronization as an explicit infrastructure requirement

### CR2.2 — `unclean.leader.election.enable=false` Not Documented [HIGH]

**R11.1:** This Kafka broker configuration is a prerequisite for `exactly_once_v2` and `acks=all` durability guarantees. Without it, a lagging broker can become leader after an outage and lose acknowledged writes. Not documented anywhere.

**Action:** Add to master architecture Kafka configuration section and INTERFACE_CONTRACTS.

---

## Medium Findings

### CR2.3 — Reservation Scanner vs Consumer Race Not Documented [MEDIUM]

**R4.2:** A1's `@Scheduled` timeout scanner and the Kafka consumer reservation handler both modify the `reservations` table. If the scanner releases a reservation at the same instant the consumer processes a CONFIRM, the concurrency control mechanism (row-level lock, CAS, version check) is not specified.

**Action:** Document the concurrency control in A1's design.

### CR2.4 — `-XX:+ExitOnOutOfMemoryError` Not Documented [MEDIUM]

**R11.4:** Without this JVM flag, an OOM'd JVM can enter a partial-zombie state where liveness probe passes but the application is non-functional.

**Action:** Add to Platform Starter or deployment documentation as mandatory JVM flag.

### CR2.5 — `acks=all` Not Enforced Structurally [MEDIUM]

**R11.2:** `acks=all` is documented as a requirement but not auto-configured by the Platform Starter. A developer could create a producer with `acks=1`.

**Action:** Auto-configure `acks=all` in the Starter's Kafka module.

### CR2.6 — `fsync=on` Not Documented [LOW]

**R11.3:** PostgreSQL's default, but should be explicitly documented as an infrastructure requirement for a financial system.

**Action:** Add to infrastructure requirements.

---

## Positive Findings

- **Dual-write strategies are correctly implemented** — A1 Kafka-first, A3 Transactional Outbox, A2 instant exactly_once_v2, A2 batch outbox — all verified
- **A2 explicitly acknowledges its PostgreSQL-Kafka gap** and mitigates with 5-minute reconciliation
- **Reservation resolution has three independent safety nets** — A2 explicit release, A1 30-second auto-release, A1 startup scan
- **Deterministic reservation IDs prevent double-reservation**
- **A2 state machine structurally prevents RELEASE+SETTLE for same transaction**
