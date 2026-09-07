# Crash Resilience Review Checklist — Nordic Payment Processing Infrastructure

*Purpose: Verify that no data is lost or corrupted if the system crashes at any point during a payment transaction*
*Date: 2026-04-12*
*Scope: Master project + 7 subprojects, architecture + requirements*
*Frameworks: DORA (EU Digital Operational Resilience Act), Nygard "Release It!" stability patterns, Kleppmann "Designing Data-Intensive Applications", Chaos Engineering principles, Jepsen empirical verification*

---

## Guiding Principle

In a payment system, a crash at any point must result in one of two outcomes:
1. **The transaction completes correctly** (as if the crash never happened), or
2. **The transaction is cleanly rolled back** (as if it was never started)

A crash must **never** result in:
- Money debited but not credited (or vice versa)
- A payment in a permanent limbo state with no path to resolution
- Duplicate processing that charges someone twice
- Lost audit records for transactions that did execute
- Inconsistency between what the ledger says and what actually happened

---

## Category R1: Crash Points in the Instant Payment Flow

*For each step in the instant payment lifecycle, verify: what happens if the system crashes at exactly this point?*

### R1.1 — Crash During Phase 1: VoP Check + Instruction Submission

**Crash point:** A3/A4 called A6 for VoP, received the result, and is about to publish the payment instruction to Kafka.

| Crash Scenario | What Happens | What to Verify |
|---|---|---|
| A3/A4 crashes after VoP call but before outbox write | Transaction never happened — payer can retry | Is A6 naturally idempotent? (VoP is a pure query — yes) |
| A3/A4 crashes after outbox write but before Kafka publish | Outbox row exists in DB. On restart, outbox relay re-publishes. | Does A3/A4 implement outbox relay on startup? Does A2 deduplicate if the message arrives twice? |
| A6 crashes during VoP check | A3/A4 gets connection error. Circuit breaker may open. Payment proceeds with VOP_TIMEOUT. | Does A3/A4 handle A6 connection failures gracefully? Is the VOP_TIMEOUT flag correctly set? |

**Where to check:** A3 ARCHITECTURE_DESIGN (outbox, recovery), A4 ARCHITECTURE_DESIGN (outbox, recovery), A6 ARCHITECTURE_DESIGN (crash recovery), INTERFACE_CONTRACTS (VoP timeout contract)

### R1.2 — Crash During Phase 2: A2 Receives Instruction

**Crash point:** A2 consumed the payment instruction from Kafka but crashes during processing.

| Crash Scenario | What Happens | What to Verify |
|---|---|---|
| A2 crashes after consuming but before committing Kafka offset | On restart, Kafka replays the message from last committed offset. A2 reprocesses. | Is A2's instruction processing idempotent? Does dedup prevent double-processing? |
| A2 crashes after writing to state store but before publishing reservation request | Kafka Streams `exactly_once_v2`: state store write and offset commit are atomic. If state store was written, offset was committed. If not, both are rolled back. | Is `exactly_once_v2` configured? Is the state store changelog topic used for recovery? |
| A2 crashes after publishing reservation request but before receiving response | On restart, A2 rebuilds state store from changelog. Payment is in AWAITING_RESERVATION. A1 may have already processed the reservation. | Does A2's state store recovery detect in-flight reservations? Does the timeout punctuator handle stale AWAITING_RESERVATION states? |

**Where to check:** A2 ARCHITECTURE_DESIGN (Kafka Streams topology, exactly_once_v2, state store recovery, crash recovery section)

### R1.3 — Crash During Phase 2: A1 Balance Reservation

**Crash point:** A1 received the reservation request and is processing it.

| Crash Scenario | What Happens | What to Verify |
|---|---|---|
| A1 crashes after consuming reservation request but before publishing event | Kafka-first: event not published = reservation never happened. On restart, A1 re-consumes from last committed offset and reprocesses. | Is A1's reservation processing idempotent? What if A2 retries the same reservation request? |
| A1 crashes after publishing reservation event but before publishing response | The reservation IS applied (event in ledger.events). But A2 never gets the response. | Does A2's timeout detect this? Does A1's 30-second auto-release safety net apply? Does re-processing the same reservation request on restart produce a duplicate? |
| A1 crashes after publishing response | Normal — A2 receives the response. A1's projection may not have been updated yet. | On restart, does A1 rebuild the projection from events? Is the projection eventually consistent? |

**Where to check:** A1 ARCHITECTURE_DESIGN (Kafka-first model, event publishing, reservation timeout, projection rebuild), INTERFACE_CONTRACTS (reservation contract)

### R1.4 — Crash During Phase 2: A5 Fraud/Sanctions Screening

**Crash point:** A5 received the fraud check request and is scoring.

| Crash Scenario | What Happens | What to Verify |
|---|---|---|
| A5 crashes during scoring | Kafka Streams `exactly_once_v2`: scoring output and offset commit are atomic. On restart, A5 re-consumes and re-scores. | Is scoring deterministic? Will re-scoring produce the same result? |
| A5 crashes after publishing response but before offset commit | `exactly_once_v2` makes this atomic — if response was published, offset was committed. | Verify exactly_once_v2 configuration. |
| A5 is slow (not crashed) — A2's timeout fires | A2 rejects the payment (sanctions unknown). A5 later publishes a response. | Does A2 handle late responses correctly (section 4.6)? If it's a SANCTIONS_HIT, does it trigger a pacs.004 return? |

**Where to check:** A5 ARCHITECTURE_DESIGN (Kafka Streams, exactly_once_v2, late response handling), A2 ARCHITECTURE_DESIGN (timeout punctuator, late fraud response)

### R1.5 — Crash During Phase 2: A2 Settlement Decision

**Crash point:** A2 received fraud response, decided to settle, and is executing settlement.

| Crash Scenario | What Happens | What to Verify |
|---|---|---|
| A2 crashes after deciding to settle but before publishing settlement event | State store has payment in SETTLING state. On restart, database recovery scan finds SETTLING records. | Does the recovery scan retry settlement for SETTLING records? Or does it timeout and reject? |
| A2 crashes after publishing settlement event but before confirming reservation | A1 receives settlement event, applies it. But the reservation is never explicitly confirmed. | Does A1's reservation handler accept settlement events for reserved balances? Or does it require explicit confirmation first? Is the 30-second auto-release a risk here (releasing a reservation that was already settled)? |
| A2 crashes after publishing settlement but before publishing status update to A3/A4 | A1 has settled. A3/A4 don't know. Payment is settled but unreported. | Does A2's recovery scan detect settled-but-unreported payments and re-publish status updates? |

**Where to check:** A2 ARCHITECTURE_DESIGN (settlement flow, crash recovery, database recovery scan), A1 ARCHITECTURE_DESIGN (settlement event handling, reservation confirmation)

### R1.6 — Crash During Phase 3: A1 Applies Settlement

**Crash point:** A1 received the settlement event and is applying it to the ledger.

| Crash Scenario | What Happens | What to Verify |
|---|---|---|
| A1 crashes during compound event processing | Kafka-first: if event not published to ledger.events, settlement never happened. On restart, re-consume and reprocess. | Is compound event publishing atomic? Is it idempotent? |
| A1 crashes after publishing compound event but before updating projection | Event is in ledger.events (source of truth). Projection is stale. On restart, projection consumer catches up. | Is the projection rebuild correct? Does it handle partial application (some events applied, some not)? |

**Where to check:** A1 ARCHITECTURE_DESIGN (compound events, projection rebuild, Kafka-first consistency)

---

## Category R2: Crash Points in the Batch Payment Flow

### R2.1 — Crash During Spring Batch Processing

| Crash Scenario | What Happens | What to Verify |
|---|---|---|
| A2 batch instance crashes mid-chunk | Spring Batch ExecutionContext tracks progress. On restart, batch resumes from the last committed chunk. | Is Spring Batch configured for restartability? Are chunk boundaries aligned with Kafka offset commits? |
| A2 batch crashes during netting calculation | Netting has not yet written results. On restart, netting recalculates from scratch. | Is netting deterministic? Same input → same output? |
| A2 batch crashes after netting but before settlement events | Netting results in database (clearing_records updated). Settlement events not yet published. | Does recovery scan detect clearing_records in NETTING_COMPLETE but not SETTLED? |
| A2 batch crashes during multi-PSP settlement | Some PSPs settled, others not. | Is per-PSP settlement atomic? Can partial settlement be detected and completed? |

**Where to check:** A2 ARCHITECTURE_DESIGN (Spring Batch configuration, ExecutionContext, chunk processing, netting, batch crash recovery)

---

## Category R3: Dual-Write Consistency

*Verify that the chosen dual-write strategy (Kafka-first or Transactional Outbox) correctly handles crashes at the boundary between database and Kafka.*

### R3.1 — Kafka-First Services (A1)

| Crash Point | What Happens | What to Verify |
|---|---|---|
| After Kafka publish, before projection update | Event is the source of truth. Projection catches up on restart. | Is projection rebuild from events correct and complete? |
| Kafka publish fails (broker down) | Event not published. Operation not applied. Retry or reject. | Does A1 retry Kafka publishes? Is there a circuit breaker on Kafka? What does the caller see? |

### R3.2 — Transactional Outbox Services (A3, A4)

| Crash Point | What Happens | What to Verify |
|---|---|---|
| After DB transaction (outbox row written), before Kafka publish | Outbox row exists. Relay picks it up on restart. | Is the outbox relay idempotent? Does it run on startup? |
| After in-process Kafka publish, before marking outbox row as published | Kafka message published. Outbox row still marked unpublished. Relay re-publishes → duplicate. | Does the Kafka consumer (A2) deduplicate? Is this documented? |
| Database transaction fails, Kafka publish already happened (impossible with outbox) | Cannot happen — outbox ensures DB write happens first, Kafka publish second. | Verify the outbox pattern is correctly implemented (single DB transaction for business data + outbox row). |

### R3.3 — Kafka Streams Services (A2 instant, A5)

| Crash Point | What Happens | What to Verify |
|---|---|---|
| During Kafka Streams processing | `exactly_once_v2` ensures atomic: consume → process → produce → commit offset. Crash = rollback to last committed state. | Is `processing.guarantee=exactly_once_v2` configured? |
| State store corruption | Kafka Streams rebuilds state store from changelog topic. | Is the changelog topic retained long enough? Is the state store recovery tested? |

---

## Category R4: Reservation and Timeout Safety

*Verify that balance reservations are always resolved (confirmed or released) and never left dangling.*

### R4.1 — Reservation Lifecycle Completeness

| Scenario | Expected Outcome | What to Verify |
|---|---|---|
| Payment settles normally | Reservation confirmed, balance transferred | Does settlement event trigger reservation confirmation in A1? |
| Payment rejected (fraud, sanctions, insufficient balance) | Reservation released | Does A2 explicitly publish release command on rejection? |
| A2 crashes with in-flight reservation | 30-second auto-release in A1 | Does A1's periodic scan correctly identify and release stale reservations? Is the scan interval (5 seconds) frequent enough? |
| A2 times out waiting for A1 reservation response | A2 rejects payment, publishes release command | Is the release idempotent? What if A1 never received the original reservation? |
| Double reservation for same transaction (replay) | Second reservation deduplicated | Does A1 deduplicate by reservationId? |

**Where to check:** A1 ARCHITECTURE_DESIGN (reservation timeout, auto-release scan), A2 ARCHITECTURE_DESIGN (reservation management, release on rejection), INTERFACE_CONTRACTS (reservation contract)

### R4.2 — Race Between Timeout and Completion

| Scenario | Expected Outcome | What to Verify |
|---|---|---|
| A1 auto-releases reservation at 30s; A2 publishes settlement at 29.5s | Settlement should succeed (reservation was still active when settlement was published) | Is there a race between A1's release scan and incoming settlement events? Does Kafka partition-based serialization prevent this race? |
| A2 publishes release command; A1 receives settlement event first | Settlement should NOT proceed (A2 decided to reject) | Does A1 check whether the payment has been rejected before settling? Or does it rely on A2 to never publish settlement + release for the same transaction? |

---

## Category R5: Audit Trail Durability

*Verify that audit records are never lost, even on crash.*

### R5.1 — Audit Dual-Write Survival

| Crash Point | Local Log | Kafka audit.events | What to Verify |
|---|---|---|---|
| Service crashes after local log write, before Kafka publish | Preserved (filesystem) | Lost for this event | Is the local log the compliance-primary record? Does the hash chain handle a gap in Kafka? |
| Service crashes before any audit write | Both lost | Both lost | Was the payment operation applied? If yes, the audit gap must be detectable. |
| Kafka broker unavailable | Local log captures event | Event buffered, published when broker returns | Is the buffer bounded? What happens if the buffer fills? |

**Where to check:** Platform Starter ARCHITECTURE_DESIGN (audit dual-write, hash chain crash recovery, GENESIS markers)

### R5.2 — Hash Chain Recovery

| Scenario | What to Verify |
|---|---|
| Service restarts normally | Does it recover the hash chain from Kafka (last N events for this instanceId)? |
| Service restarts but Kafka is unavailable | Does it start from GENESIS marker? Is the GENESIS gap detectable by verification tools? |
| Multiple service instances restart simultaneously | Each maintains its own chain (per-instanceId). Do they interfere? |

---

## Category R6: Data Consistency Across Services

*Verify that after any crash, all services converge to a consistent state.*

### R6.1 — Ledger vs. Clearing Record Consistency

| Scenario | What to Verify |
|---|---|
| A2 says payment SETTLED, A1 ledger has no entry | Is this possible? (A2 publishes settlement event; A1 hasn't consumed it yet.) Is this eventually consistent? How long before inconsistency resolves? |
| A1 ledger has settlement entry, A2 still says SETTLING | A2 crashed after publishing settlement event. On restart, does A2's recovery scan update SETTLING → SETTLED? |
| A1 ledger has double entry (same transaction twice) | A1's idempotency (compound event dedup) should prevent this. Verify. |

### R6.2 — R2P Status vs. Payment Status Consistency

| Scenario | What to Verify |
|---|---|
| A2 publishes SETTLED on status-updates; A3 crashes before processing | On restart, A3 re-consumes from Kafka. Does it correctly update the R2P request to SETTLED? Is the state transition idempotent? |
| Payment is RETURNED (late sanctions hit); A3 never gets the RETURNED status | Does A3 consume RETURN_INITIATED and RETURNED from `payment.status-updates`? (Fixed in architecture review — verify) |

### R6.3 — Reconciliation as Safety Net

| What to Verify |
|---|
| Does A1's daily reconciliation compare ledger events against A2's settlement reports? |
| Can reconciliation detect a payment that A2 says SETTLED but A1 never received? |
| Can reconciliation detect a payment that A1 settled but A2 has no record of? |
| Are reconciliation discrepancies alerted automatically? |

**Where to check:** A1 ARCHITECTURE_DESIGN (reconciliation), A2 ARCHITECTURE_DESIGN (settlement reports), master requirements (reconciliation requirements)

---

## Category R7: Infrastructure Failure Scenarios

*Verify behavior when infrastructure components fail, not just application services.*

### R7.1 — Kafka Broker Failure

| Scenario | What to Verify |
|---|---|
| Kafka broker temporarily unavailable (1 minute) | Do producers buffer and retry? Do consumers pause and resume? Is `acks=all` with `min.insync.replicas=2` preventing data loss? |
| Kafka topic data loss (broker disk failure) | `replication.factor=3` prevents this for single-broker failure. Is this configured? |
| Kafka partition reassignment during rolling upgrade | Cooperative Sticky Assignor minimizes reassignment. Is this configured (master H.1a)? |

### R7.2 — PostgreSQL Failure

| Scenario | What to Verify |
|---|---|
| PostgreSQL temporarily unavailable | Do services retry database operations? Circuit breaker? What state are in-flight operations in? |
| PostgreSQL data corruption | Point-in-time recovery from WAL backups? Is this an infrastructure requirement? |
| Connection pool exhaustion | Are connection pools sized appropriately? Separate pools for batch/instant in A2? |

### R7.3 — Service Mesh / Network Failure

| Scenario | What to Verify |
|---|---|
| Network partition between A2 and A1 | A2's circuit breaker opens. Reservations eventually time out (30s). Payments rejected. | 
| Network partition between A2 and A5 | A2's punctuator fires. Payments rejected (sanctions unknown). |
| DNS failure | Services cannot resolve each other. All external calls fail. Circuit breakers open across the system. |

---

## Category R8: Graceful Shutdown

*Verify that planned shutdowns (rolling deployment) don't cause data loss.*

### R8.1 — In-Flight Request Handling

| Service | What to Verify |
|---|---|
| A2 (instant) | Does it complete or abort in-flight instant payments before shutting down? (master RES-10) |
| A2 (batch) | Does Spring Batch complete the current chunk before shutdown? |
| A1 | Does it commit pending projection updates before shutdown? |
| A5 | Does it complete in-flight scoring? If not, does it publish `decision: TIMEOUT`? |
| A6 | Does it drain HTTP connections before stopping? |
| A3, A4 | Do they drain HTTP connections and commit Kafka offsets? |

### R8.2 — Kafka Consumer Rebalancing

| What to Verify |
|---|
| Is Cooperative Sticky Assignor configured to minimize partition reassignment? |
| During rolling update, do consumers gracefully leave the group (sending LeaveGroup) rather than timing out? |
| Is there a processing pause during rebalancing? How long? Does it affect the instant payment SLA? |

---

## Review Execution Approach

1. **R1 first** — trace the instant payment flow crash point by crash point (highest risk)
2. **R3 second** — verify dual-write consistency at each service boundary
3. **R4 third** — reservation safety (money at risk)
4. **R2, R5-R8** — remaining categories

For each crash point:
- Read the relevant service's ARCHITECTURE_DESIGN
- Identify the recovery mechanism documented
- Verify it handles the specific crash point correctly
- Flag any crash point with no documented recovery or with ambiguous behavior

---

## Category R9: DORA Compliance — Operational Resilience for Financial Entities

*EU Digital Operational Resilience Act (Regulation 2022/2554), mandatory from January 2025. Directly applicable to payment processing systems operated by or for EU financial entities.*

### R9.1 — ICT Risk Management (DORA Articles 5-16)

**What:** DORA requires financial entities to have a comprehensive ICT risk management framework that identifies, protects against, detects, responds to, and recovers from ICT-related incidents.

| Check | What to Verify | Where |
|-------|---------------|-------|
| R9.1.1 | Are all ICT assets and dependencies identified and documented? | Master SUBSYSTEM_MAP.md, INTERFACE_CONTRACTS.md, deployment sections |
| R9.1.2 | Are protection measures proportionate to the risk? (Critical functions get stronger protection) | Payment clearing (A2) and balance management (A1) are critical functions — do they have stronger resilience than supporting functions? |
| R9.1.3 | Are detection mechanisms in place for anomalous activities and ICT incidents? | Alerting sections across all services, Prometheus/Grafana configuration |
| R9.1.4 | Is there a documented response and recovery plan for ICT incidents? | Investigation queues (A2), fraud case management (A5), account freeze capability |
| R9.1.5 | Are backup and restoration procedures documented and tested? | Database backup, Kafka topic retention, event replay capability |

### R9.2 — ICT Incident Classification and Reporting (DORA Articles 17-23)

**What:** DORA requires classification of ICT-related incidents and reporting of major incidents to competent authorities within specified timeframes.

| Check | What to Verify | Where |
|-------|---------------|-------|
| R9.2.1 | Are incident severity levels defined? (Major vs. non-major) | Alerting thresholds across services — which alerts constitute "major incidents"? |
| R9.2.2 | Is there a clear escalation path from automated alert to human response? | Alerting sections, investigation queue escalation thresholds |
| R9.2.3 | Can the system produce the data required for incident reports? (Timeline, root cause, impact assessment, remediation) | Audit trail completeness (hash-chained audit.events), distributed tracing (Micrometer), SLA metrics |

### R9.3 — Digital Operational Resilience Testing (DORA Articles 24-27)

**What:** DORA mandates regular testing of ICT systems, including threat-led penetration testing for significant financial entities.

| Check | What to Verify | Where |
|-------|---------------|-------|
| R9.3.1 | Are resilience tests defined that cover crash, failover, and recovery scenarios? | Testing sections across all services, end-to-end demo script |
| R9.3.2 | Do tests include scenarios where multiple components fail simultaneously? | Current testing covers single-service crashes — verify multi-service failure scenarios |
| R9.3.3 | Is there a plan for threat-led penetration testing (TLPT)? | Master architecture security testing section |
| R9.3.4 | Are test results reviewed and used to improve the system? | Feedback loop from test findings to architecture changes |

### R9.4 — Third-Party ICT Risk (DORA Articles 28-44)

**What:** DORA requires oversight of third-party ICT service providers (cloud, infrastructure).

| Check | What to Verify | Where |
|-------|---------------|-------|
| R9.4.1 | Are critical third-party dependencies identified? (Kafka, PostgreSQL, Kubernetes, cloud provider) | SUBSYSTEM_MAP.md, deployment sections |
| R9.4.2 | Are exit strategies documented for critical dependencies? (What if the Kafka provider fails?) | Not currently documented — verify |
| R9.4.3 | Are concentration risks identified? (All services depending on the same Kafka cluster) | Single Kafka cluster serves all services — documented as a risk? |

---

## Category R10: Chaos Engineering Test Plan

*Based on the Principles of Chaos Engineering (principlesofchaos.org). Purpose: define specific failure injection experiments to verify that the system behaves correctly under real failure conditions, not just on paper.*

### R10.1 — Steady State Definition

Before injecting failures, define what "normal" looks like so we can verify the system returns to it:

| Metric | Steady State Value | Tolerance |
|--------|-------------------|-----------|
| Instant payment success rate | >99.9% | Any drop below 99% is a failure |
| Instant payment p95 latency | <5 seconds | Any breach of 7-second hard limit is critical |
| Account balance consistency | Sum(debits) == Sum(credits) at all times | Zero tolerance — any imbalance is a critical failure |
| Audit trail completeness | Every settled/rejected payment has an audit record | Zero tolerance |
| Reservation leak rate | No reservations older than 30 seconds (auto-release) | Zero tolerance |

### R10.2 — Failure Injection Experiments

Each experiment: inject a specific failure, observe system behavior, verify steady state is maintained or restored.

#### Experiment 1: Kill A2 Instant Instance Mid-Payment

**Inject:** `kill -9` the A2 instant JVM while payments are in-flight (some in AWAITING_RESERVATION, some in AWAITING_FRAUD, some in SETTLING).

**Verify:**
- All in-flight payments eventually reach a terminal state (SETTLED or REJECTED)
- No payments are stuck permanently in AWAITING_* states
- Reservations for rejected payments are released (either by A2 on restart or by A1's 30-second auto-release)
- No duplicate payments (Kafka Streams exactly_once_v2 prevents reprocessing)
- After recovery, new payments process normally
- The investigation queue captures any payment that took >30 seconds to resolve

**Expected recovery time:** <60 seconds (Kubernetes restarts pod, Kafka Streams rebuilds state store from changelog)

#### Experiment 2: Kill A1 While A2 Has In-Flight Reservations

**Inject:** `kill -9` A1 while A2 has active AWAITING_RESERVATION payments.

**Verify:**
- A2's reservation timeout fires (200ms). A2 rejects the payment.
- A2 publishes release commands (which A1 will process after restart)
- A1's projections are consistent after restart (events replayed from Kafka)
- No reservations are "orphaned" (reserved but never released or confirmed)
- A6's circuit breaker opens (A1 is down). VoP queries serve from cache or return ERROR.

#### Experiment 3: Kill A5 During Sanctions Screening

**Inject:** `kill -9` A5 instant instance while fraud-check-requests are queued.

**Verify:**
- A2's timeout punctuator fires for all AWAITING_FRAUD payments
- All payments are REJECTED (sanctions unknown → cannot skip)
- No payment settles without sanctions screening
- After A5 restart, new payments process normally
- Late A5 responses (if any) are handled correctly by A2 (section 4.6)

#### Experiment 4: Kafka Broker Temporary Unavailability

**Inject:** Stop one Kafka broker for 60 seconds (with replication.factor=3, min.insync.replicas=2).

**Verify:**
- Producers retry and eventually succeed (acks=all with 2 ISR available)
- No messages are lost
- Consumers pause and resume transparently
- Instant payment latency may increase but no payments fail solely due to the broker outage
- Audit events are buffered (local log captures them) and published when broker returns

#### Experiment 5: PostgreSQL Temporary Unavailability

**Inject:** Block PostgreSQL connections for 30 seconds for A2's database.

**Verify:**
- A2 instant path (Kafka Streams) continues processing — state store is in RocksDB, not PostgreSQL
- A2 batch path pauses (Spring Batch uses PostgreSQL for job state)
- Clearing record writes fail — do they retry or go to DLQ?
- After PostgreSQL returns, does A2 recover without manual intervention?

#### Experiment 6: Network Partition Between A2 and A1

**Inject:** Block all network traffic between A2 and A1 pods for 60 seconds.

**Verify:**
- A2 cannot reach A1 for balance reservations (Kafka messages not delivered)
- A2's timeout handles this — payments rejected
- A6's circuit breaker opens for A1 REST calls — VoP serves from cache
- After partition heals, all services converge to consistent state
- Reconciliation detects no discrepancies

#### Experiment 7: Rolling Deployment Under Load

**Inject:** Perform a rolling deployment of A2 instant while processing continuous instant payment traffic.

**Verify:**
- No payments are lost during the deployment
- Kafka consumer rebalancing (Cooperative Sticky Assignor) minimizes partition reassignment
- Graceful shutdown completes or aborts in-flight payments before pod termination
- New instance's readiness probe gates traffic until state store is rebuilt
- End-to-end payment success rate stays >99%

#### Experiment 8: Concurrent Crash of A2 + A5

**Inject:** Kill both A2 instant and A5 instant simultaneously.

**Verify:**
- All in-flight payments are in an indeterminate state (no service is processing them)
- After both restart: Kafka Streams replays from last committed offsets
- Payments that were AWAITING_FRAUD get re-requested to A5 (or timeout-rejected)
- No payments are duplicated, lost, or settled without screening
- This is the worst-case scenario — verify the system recovers without manual intervention

### R10.3 — Chaos Test Infrastructure

| Component | Purpose |
|-----------|---------|
| **Chaos Mesh** or **Litmus** (Kubernetes-native) | Inject pod kills, network partitions, I/O delays |
| **Toxiproxy** | Inject network failures between specific services (e.g., A2→A1 latency, A2→Kafka packet loss) |
| **Custom test harness** | Continuous payment submission + verification. Submits payments at a steady rate, verifies each reaches a terminal state, checks balances, counts discrepancies. |
| **Reconciliation verifier** | After each experiment: compare A1 ledger totals against A2 settlement records. Any discrepancy = test failure. |

### R10.4 — When to Run Chaos Tests

| Phase | Scope | Frequency |
|-------|-------|-----------|
| Development | Single-service experiments (Testcontainers + kill) | On every PR that modifies state management |
| Integration | Multi-service experiments (docker-compose + kill) | Weekly, or before each milestone |
| Pre-production | Full chaos suite including network partitions | Before each release |
| Production (DORA requirement) | Controlled experiments on live traffic | Quarterly (significant financial entities) |

---

## Category R11: Infrastructure Guarantees Verification

*Our crash resilience depends on guarantees from Kafka, PostgreSQL, and the JVM. These guarantees must be verified, not assumed. Jepsen-style empirical thinking.*

### R11.1 — Kafka Exactly-Once Semantics (exactly_once_v2)

**Claim we depend on:** Kafka Streams with `processing.guarantee=exactly_once_v2` ensures that consume → process → produce → commit-offset is atomic. A crash at any point results in rollback to the last consistent state.

**What Jepsen found:** Kyle Kingsbury's Jepsen testing of Kafka (2018-2020) confirmed that Kafka's exactly-once semantics work correctly for the transactional producer/consumer pattern. However:
- It requires `min.insync.replicas >= 2` and `acks=all` — without these, data loss is possible
- It requires the transaction coordinator to be available — if the coordinator broker fails, transactions block until it recovers
- `unclean.leader.election.enable` must be `false` — otherwise, a broker that was behind can become leader and lose acknowledged writes

| What to Verify | Where |
|---------------|-------|
| `processing.guarantee=exactly_once_v2` configured for A2 and A5 | Service configurations |
| `min.insync.replicas=2` on all payment topics | Kafka topic configuration |
| `acks=all` on all producers | Producer configurations |
| `unclean.leader.election.enable=false` | Kafka broker configuration |
| `replication.factor=3` on all payment topics | Kafka topic configuration |

### R11.2 — Kafka Message Durability

**Claim we depend on:** Once a producer receives an ack (`acks=all`), the message is durable — it will not be lost even if brokers crash.

**Conditions for this to hold:**
- `replication.factor >= 3` (survive 1 broker failure)
- `min.insync.replicas = 2` (require 2 replicas to ack)
- Broker disk is not corrupted (fsync configured correctly or using SSDs with power-loss protection)

| What to Verify | Where |
|---------------|-------|
| `replication.factor=3` for all topics | Topic configuration in INTERFACE_CONTRACTS |
| `min.insync.replicas=2` for payment topics | Topic configuration |
| `log.flush.interval.messages` or reliance on OS page cache fsync | Broker configuration (infrastructure) |
| `ledger.events` topic: `retention.ms=-1` (infinite retention, no data loss by expiry) | Topic configuration |

### R11.3 — PostgreSQL Crash Recovery

**Claim we depend on:** PostgreSQL's WAL (Write-Ahead Logging) ensures committed transactions survive a crash. After crash, PostgreSQL replays the WAL to restore to the last committed state.

**Conditions for this to hold:**
- WAL archiving or streaming replication configured (for point-in-time recovery)
- `fsync=on` (default, but sometimes disabled for "performance" — catastrophic if the server crashes)
- `synchronous_commit=on` (default — must not be set to `off` for payment data)

| What to Verify | Where |
|---------------|-------|
| `fsync=on` in PostgreSQL configuration | Infrastructure requirement |
| `synchronous_commit=on` for payment databases | Infrastructure requirement |
| WAL archiving or streaming replication configured | Infrastructure requirement |
| Connection pooling does not bypass transaction boundaries | Application datasource configuration |

### R11.4 — JVM Crash vs. Graceful Shutdown

**Important distinction:** A JVM crash (`kill -9`, OOM, segfault) is different from a graceful shutdown (`SIGTERM`). Our architecture must handle both:

| Scenario | What Happens | What to Verify |
|----------|-------------|----------------|
| `SIGTERM` (graceful) | Shutdown hooks run: drain connections, commit Kafka offsets, complete in-flight operations | Does each service register shutdown hooks via the Platform Starter? |
| `kill -9` (crash) | No hooks run. Process dies immediately. | Kafka offsets not committed → replay on restart. Database transactions rolled back by PostgreSQL. State store rebuilt from changelog. |
| OOM (OutOfMemoryError) | JVM may or may not terminate cleanly depending on the OOM circumstances | Is `-XX:+ExitOnOutOfMemoryError` configured? (Ensures JVM exits rather than continuing in a corrupt state) |
| Stack overflow | Thread dies, potentially leaving resources in inconsistent state | Does the error handler catch StackOverflowError and trigger shutdown? |

### R11.5 — Clock Assumptions

**Claim we depend on:** Several mechanisms depend on wall-clock time: reservation auto-release (30 seconds), SCA timeout (5 minutes), timeout punctuator (7 seconds), rate limiting windows (1 minute), anti-enumeration windows (1 hour).

| What to Verify | Where |
|---------------|-------|
| Are all services synchronized via NTP? | Infrastructure requirement |
| What happens if the system clock jumps forward? (Reservations released early, timeouts fire prematurely) | Timeout implementations |
| What happens if the system clock jumps backward? (Reservations not released, timeouts delayed) | Timeout implementations |
| Are durations measured with monotonic clocks (System.nanoTime) or wall clocks (System.currentTimeMillis)? | Application code — monotonic clocks are correct for durations |
| Kafka Streams punctuators: wall-clock or stream-time? | A2 and A5 punctuator configuration — wall-clock is correct for real-time SLAs |

---

## Category R12: Consistency Verification Patterns

*Beyond crash recovery, verify that the system has mechanisms to detect and alert on inconsistencies, even if they should never happen.*

### R12.1 — Reconciliation

| Check | What to Verify | Where |
|-------|---------------|-------|
| R12.1.1 | Does A1's daily reconciliation compare ledger events against A2's settlement reports? | A1 ARCHITECTURE_DESIGN (reconciliation section) |
| R12.1.2 | Can reconciliation detect: payment A2 says SETTLED but A1 never received? | Reconciliation query logic |
| R12.1.3 | Can reconciliation detect: payment A1 settled but A2 has no record of? | Reconciliation query logic |
| R12.1.4 | Are discrepancies alerted automatically? | Alerting configuration |
| R12.1.5 | Is reconciliation frequency sufficient? (Daily for batch, continuous for instant?) | Requirements vs. design |

### R12.2 — Accounting Invariants

**These invariants must hold at all times. If any is violated, it indicates a bug or data corruption:**

| Invariant | What It Means | How to Verify |
|-----------|--------------|---------------|
| Sum of all debits == Sum of all credits | Money is conserved — no money created or destroyed | A1 can compute this from `ledger.events` at any time |
| Every debit has a corresponding credit (compound event guarantee) | No half-applied transactions | A1's compound event model makes this structural |
| Available balance >= 0 for all accounts | No account is overdrawn | A1's domain type `MonetaryAmount` enforces non-negative; PostgreSQL CHECK constraint |
| Active reservations + available balance == total balance | Reserved funds are accounted for | A1 can verify this from projection state |
| Every SETTLED payment has a corresponding ledger entry | No "phantom" settlements | Reconciliation (R12.1.2) catches this |
| Every ledger entry has a corresponding settlement or lifecycle event | No "phantom" ledger entries | Reconciliation (R12.1.3) catches this |

### R12.3 — Monotonicity Checks

| Check | What It Means | What to Verify |
|-------|--------------|---------------|
| Event sequence numbers are strictly increasing per partition | No event reordering or gaps | A1's projection consumer should verify sequence continuity |
| Audit hash chain is unbroken per instance | No audit event tampering or loss | Platform Starter's verification tool |
| Payment state machine transitions are forward-only (no backward transitions) | A SETTLED payment cannot go back to AWAITING_FRAUD | A2's state machine enforcement |
| Kafka consumer offsets are monotonically increasing | No offset regression (which would cause reprocessing) | Kafka consumer monitoring |

### R12.4 — Continuous Consistency Monitoring

**Beyond periodic reconciliation, the system should continuously monitor for consistency violations:**

| Monitor | What It Detects | Implementation |
|---------|----------------|----------------|
| Balance conservation check | Sum(debits) != Sum(credits) at any point | Periodic (every 5 minutes) SQL query against A1 projection |
| Reservation age check | Reservations older than 30 seconds still active | A1's periodic scan (every 5 seconds) — already implemented |
| Stale in-flight payments | Payments in AWAITING_* states beyond expected timeout | A2's investigation queue — already implemented |
| Orphaned outbox entries | Outbox rows not published to Kafka within expected relay interval | Periodic scan of outbox table (age > 1 minute) |
| Consumer lag anomaly | Kafka consumer lag growing instead of shrinking | Prometheus metric `kafka.consumer.lag` with trend-based alerting |
| Hash chain gap | Missing sequence numbers in audit hash chain | Periodic verification (hourly) via Platform Starter tool |

---

## Category R13: Data Recovery and Rebuild Capability

*Verify that the system can recover from data loss scenarios — not just crashes but also corruption, accidental deletion, and backup restoration.*

### R13.1 — Event Store as Source of Truth

| Scenario | Recovery Procedure | What to Verify |
|----------|-------------------|----------------|
| A1 PostgreSQL projection corrupted | Rebuild projection by replaying all events from `ledger.events` Kafka topic | Is projection rebuild tested? How long does it take for N events? Is the system available during rebuild? |
| A1 PostgreSQL projection accidentally deleted | Same as above — full replay from Kafka | Does the rebuild handle crypto-shredded events (PII fields → `[REDACTED]`)? |
| Kafka `ledger.events` topic data loss | **Unrecoverable** — this is the source of truth. Backup via Kafka MirrorMaker or topic-to-S3 archival. | Is Kafka replication sufficient (factor=3)? Is there an additional backup mechanism? |

### R13.2 — Service State Rebuild

| Service | State to Rebuild | Rebuild Mechanism | What to Verify |
|---------|-----------------|-------------------|----------------|
| A1 | PostgreSQL projections | Replay `ledger.events` from offset 0 | Tested? Duration? Available during rebuild? |
| A2 | Kafka Streams state store | Rebuilt from changelog topic automatically | Is changelog retention sufficient? |
| A2 | `clearing_records` PostgreSQL | Replay from Kafka topics + reconcile with A1 | Is this possible? Documented? |
| A5 | Kafka Streams state store | Rebuilt from changelog topic | Same as A2 |
| A5 | Sanctions index | Rebuilt from cached file on disk or re-downloaded | Startup procedure documented |
| A6 | Caffeine cache | Pre-warm from A1 on startup | Already documented in A6 design |

### R13.3 — Backup Requirements

| Data | Backup Frequency | Retention | Recovery Point Objective (RPO) | What to Verify |
|------|-----------------|-----------|-------------------------------|----------------|
| Kafka topics (payment.*) | Continuous replication (factor=3) | Per topic retention config | 0 (no data loss with acks=all) | Replication factor and ISR config |
| Kafka topics (ledger.events) | Continuous replication + periodic archival to cold storage | Infinite | 0 | Archival mechanism documented? |
| PostgreSQL databases (all services) | Daily full + continuous WAL archiving | 30 days | <1 minute (WAL granularity) | WAL archiving configured? |
| Kafka consumer offsets | Managed by Kafka (__consumer_offsets topic) | Infinite | N/A (replay from last offset) | Offset retention config |

---

## Review Execution Approach

Recommended order (by risk and dependency):

1. **R1 + R2** — Trace crash points step by step (highest risk, most specific)
2. **R3** — Dual-write consistency at each boundary
3. **R4** — Reservation safety (money at risk)
4. **R11** — Infrastructure guarantees (foundation for everything above)
5. **R5 + R6** — Audit and cross-service consistency
6. **R12** — Consistency verification patterns
7. **R7 + R8** — Infrastructure failure and graceful shutdown
8. **R13** — Data recovery capability
9. **R9** — DORA compliance mapping
10. **R10** — Chaos engineering test plan (verification, not review)

For each crash point:
- Read the relevant service's ARCHITECTURE_DESIGN
- Identify the recovery mechanism documented
- Verify it handles the specific crash point correctly
- Flag any crash point with no documented recovery or with ambiguous behavior
