# Crash Resilience Review Phase 1 — Payment Flow Crash Points (R1+R2)

*Date: 2026-04-12*

---

## Summary

17 crash points analyzed across the instant payment flow (R1.1 through R1.6).

- **SAFE:** 16 of 17 crash points have documented, mechanically sound recovery
- **RISK:** 1 crash point (R1.5b) has a documentation gap regarding CONFIRM-after-RELEASE idempotency

---

## Critical Finding

### CR1.1 — Reservation CONFIRM-After-RELEASE Idempotency Not Documented [MEDIUM-HIGH]

**Crash point R1.5b:** A2 publishes settlement event to A1 but crashes before sending reservation CONFIRM.

**Sequence:**
1. A2 publishes settlement event → A1 applies (debits/credits booked balance)
2. A2 crashes
3. A1's 30-second auto-release fires → releases the reservation (adds amount back to available balance)
4. A2 recovers → sends CONFIRM for the already-released reservation

**Analysis:** The final balances are mathematically correct (booked=5000, available=5000 — consistent). But the architecture documents explicitly guarantee RELEASE idempotency (releasing an already-confirmed reservation is a no-op) without explicitly guaranteeing the converse (confirming an already-released reservation is a no-op).

**Risk:** If CONFIRM on an already-RELEASED reservation subtracts from available_balance again, it would create an incorrect available balance. The math works only if CONFIRM-after-RELEASE is a no-op.

**Action:**
1. Document that CONFIRM on an already-RELEASED reservation is a no-op in A1's ARCHITECTURE_DESIGN
2. Add an integration test for this specific scenario
3. Consider whether A2's recovery for SETTLING records should skip CONFIRM when auto-release has likely occurred

---

## All Crash Points

| ID | Crash Point | Verdict | Recovery Mechanism |
|----|------------|---------|-------------------|
| R1.1a | A3 after VoP, before outbox write | **SAFE** | Transaction uncommitted, payer retries |
| R1.1b | A3 after outbox write, before Kafka | **SAFE** | Outbox relay on restart + A2 dedup |
| R1.1c | A6 crashes during VoP | **SAFE** | Stateless; A3 handles timeout/error |
| R1.2a | A2 consumes, before offset commit | **SAFE** | exactly_once_v2 atomic replay |
| R1.2b | A2 writes state store, before reservation | **SAFE** | exactly_once_v2 atomicity |
| R1.2c | A2 after reservation request, before response | **SAFE** | TimeoutPunctuator + DB recovery scan |
| R1.3a | A1 consumes reservation, before event | **SAFE** | Kafka-first re-consume |
| R1.3b | A1 after event, before response to A2 | **SAFE** | A2 timeout rejects; A1 restart re-publishes; 30s auto-release backstop |
| R1.3c | A1 after response published | **SAFE** | Projection catches up |
| R1.4a | A5 crashes during scoring | **SAFE** | exactly_once_v2 replay; deterministic scoring |
| R1.4b | A5 publishes response, before offset commit | **SAFE** | exactly_once_v2 produce+commit atomic |
| R1.4c | A5 slow, A2 timeout fires | **SAFE** | A2 rejects+releases; late response handled |
| R1.5a | A2 after SETTLING, before settlement event | **SAFE** | DB scan detects SETTLING; reconciliation finds no event; rejects |
| **R1.5b** | **A2 after settlement event, before CONFIRM** | **RISK** | **Settlement applied correctly, but CONFIRM-after-RELEASE idempotency not documented** |
| R1.5c | A2 after settlement, before status to A3 | **SAFE** | Recovery scan re-publishes status |
| R1.6a | A1 during compound event processing | **SAFE** | Kafka-first: re-consume if unpublished |
| R1.6b | A1 after event, before projection update | **SAFE** | Event is source of truth; projection catches up |

---

## Positive Findings

1. **Event sourcing (A1) provides robust crash recovery** — the event store is the source of truth, projections are derived and rebuildable. Every crash point in A1 results in either "event not published → re-consume" or "event published → projection catches up."

2. **exactly_once_v2 (A2, A5) prevents all duplicate processing** — state store writes, output publishes, and offset commits are atomic. No crash can cause partial state.

3. **Transactional Outbox (A3, A4) handles the DB-Kafka gap** — outbox relay on startup + scheduled fallback + downstream dedup = reliable delivery.

4. **30-second reservation auto-release is a robust safety net** — even if A2 crashes and never sends RELEASE, money is never locked permanently.

5. **TimeoutPunctuator (A2) guarantees terminal state** — every in-flight payment reaches SETTLED or REJECTED within 7 seconds (or recovery timeout).

6. **Investigation queues capture anomalies** — payments that take unusual recovery paths are surfaced to operators.
