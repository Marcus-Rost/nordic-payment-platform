# Test Framework

JRPay uses a four-layer test strategy. Each layer tests a different dimension
that the layers below it cannot reach.

```
Layer 4 │ Smoke Tests              real HTTP, real auth, real database
────────┤
Layer 3 │ Master Use Case Tests    system-level scenarios across services
────────┤
Layer 2 │ Subproject Use Case Tests end-to-end scenarios within one service
────────┤
Layer 1 │ Unit Tests               individual classes and algorithms
```

---

## Layer 1 — Unit Tests

**What they test:** A single class or algorithm in isolation. No Spring context,
no database, no network. Pure logic verification.

**Why they exist:** Fast feedback on correctness of individual building blocks —
domain types, state machines, matching algorithms, scoring pipelines.

| Subproject | Test Class | Tests | Focus |
|---|---|---:|---|
| domain-model | `MonetaryAmountTest` | 14 | Value object: creation, rounding, arithmetic, currency validation |
| security-starter | `DenyAllSecurityTest` | 5 | Fortress principle: deny-all default, permit-all health, OWASP headers |
| A1 Account Ledger | `AccountCommandHandlerTest` | 18 | Commands: create, reserve, release, confirm, double-entry, freeze, concurrency |
| A6 VoP Service | `NameNormalizerTest` | 9 | EPC288-23: diacritics (ø→o, æ→ae), case, whitespace, idempotency |
| A6 VoP Service | `NameMatcherTest` | 16 | Jaro-Winkler: exact, permutations, typos, legal suffixes, symmetry |
| A6 VoP Service | `NameMatcherPropertyTest` | 4 | Property-based (jqwik, 500 tries): symmetry, score bounds, identity |
| A5 Fraud Detection | `ScoringPipelineTest` | 9 | Chain of responsibility: clean, sanctions hit, VoP risk, velocity |
| A5 Fraud Detection | `SanctionsIndexTest` | 13 | Bloom filter + fuzzy: exact, case, diacritics, threshold 0.85 |
| A2 Clearing Engine | `InstantPaymentProcessorTest` | 18 | State machine: 5-step flow, timeouts, terminal states, hard limit |
| A3 Bill Payment | `StateMachineTest` | 14 | R2P lifecycle: 12 states, 14 triggers, terminal enforcement |
| A4 PSD2 API | `ScaExemptionEngineTest` | 10 | SCA: low-value, trusted beneficiary, recurring, TRA with EBA thresholds |

**Total: 11 classes, 130 tests**

---

## Layer 2 — Subproject Use Case Tests

**What they test:** End-to-end scenarios within a single service, derived from
that service's own use case document. Each service has use cases defined in its
architecture folder — these tests walk through the main flow and alternative
flows described there.

**Why they exist:** Unit tests verify pieces; use case tests verify that the
pieces compose correctly into the workflows the service is designed to support.
They catch integration bugs between classes within a service that unit tests
miss because they test components in isolation.

| Subproject | Tests | Scenarios |
|---|---:|---|
| A1 Account Ledger | 13 | Credit transfer, instant payment with reservation, release, concurrent reservations, freeze during operation, conservation invariant, crash recovery |
| A6 VoP Service | 12 | Exact match, close match, no match, Nordic diacritics, responder query, IBAN not found, security enforcement |
| A5 Fraud Detection | 12 | Low-risk auto-pass, high-risk escalation, sanctions hit, VoP risk signals (+0.0 to +0.4), velocity rules, score bound invariants |
| A2 Clearing Engine | 11 | Instant happy path, fraud timeout, sanctions timeout, hard limit €100k, semantic duplicate detection, amount exceeded |
| A3 Bill Payment | 14 | Submit→accept→settle, decline, defer, amendment, mandate auto-accept (AvtaleGiro), payment status updates, expiry, idempotency |
| A4 PSD2 API | 11 | Consent scope/expiry/revocation, SCA session, 3-attempt lockout, 5-min timeout, dynamic linking immutability |

**Total: 6 classes, 73 tests**

---

## Layer 3 — Master Use Case Tests

**What they test:** System-level scenarios from the master project's use case
documents. These simulate multi-service flows — a payment that originates in A3,
gets cleared by A2, screened by A5, and settled by A1. Since the services don't
share a process in these tests, each test class simulates the cross-service
interaction by wiring the relevant domain logic together.

**Why they exist:** Subproject use case tests verify each service in isolation.
Master use case tests verify that the services compose into the system-level
workflows defined in the master project requirements. They test assumptions
services make about each other — shared enums, contract compliance, state
machine compatibility.

| Master Use Case | Located In | Tests | Flow |
|---|---|---:|---|
| UC-01: Invoice Payment | A3 Bill Payment | 7 | Merchant→consumer→VoP→clearing→fraud→settlement, late return, mandate auto-accept |
| UC-03: PSD2 Payment | A4 PSD2 API | 9 | TPP consent + SCA + VoP, low-value exemption, trusted beneficiary, consent revocation |
| UC-05: Fraud & Sanctions | A5 Fraud Detection | 13 | Normal auto-pass, sanctions hit overrides all, VoP risk weights, velocity rules |
| UC-08: Instant Payment | A2 Clearing Engine | 12 | RECEIVED→SETTLED 5-step flow, 6 alternative flows (AF-01 through AF-06), terminal state invariants |
| UC-09: Verification of Payee | A6 VoP Service | 10 | Requester + responder, privacy rules, joint accounts, interface contract compliance |

**Total: 5 classes, 51 tests**

---

## Layer 4 — Smoke Tests

**What they test:** Real HTTP calls against running services, with real
authentication, real database, and real cross-service REST integration.

**Why they exist:** Layers 1–3 run entirely in-process. They cannot catch:
- Security misconfiguration (endpoints accidentally open)
- Serialization issues (JSON field names, date formats)
- Cross-service HTTP integration (A6 calling A1 on cache miss)
- Database schema mismatches (H2 in tests vs. PostgreSQL in production)
- Spring auto-configuration failures (missing beans, profile issues)

The live demo revealed exactly these gaps — the VoP service wasn't calling A1 on
cache miss, security blocked demo API calls, and there was no transfer endpoint.
All unit tests passed. Smoke tests exist to cover this structural blind spot.

**Prerequisites:** A1 (port 8091) and A6 (port 8096) running with the `docker`
profile. Tests use `Assumptions.assumeTrue` to skip gracefully when services are
unavailable.

| Master Use Case | Tests | Verification |
|---|---:|---|
| UC-07: Account Management | 7 | Create account, retrieve, balance query (booked/reserved/available), holder name endpoint, duplicate handling, 401 on unauthenticated, actuator public |
| UC-09: Verification of Payee | 9 | Exact/close/no match via /verify, external /check with privacy, A6→A1 cache-miss integration, Nordic diacritics (ø, æ), 401 on unauthenticated |
| UC-01: Invoice Payment | 8 | Pre-payment VoP check, transfer settlement, debtor decreased + creditor increased, conservation of money, duplicate idempotency, insufficient funds, self-transfer |

**Total: 3 classes, 24 tests**

---

## Summary

| Layer | Classes | Tests | Infrastructure | Speed |
|---|---:|---:|---|---|
| 1. Unit | 11 | 130 | None | < 10 sec |
| 2. Subproject Use Case | 6 | 73 | Spring context (some) | < 30 sec |
| 3. Master Use Case | 5 | 51 | None | < 10 sec |
| 4. Smoke | 3 | 24 | Running services + DB | ~ 15 sec |
| **Total** | **25** | **278** | | |

Each layer catches a class of defect that the layers below it structurally
cannot. Removing any layer leaves a blind spot.

## Running

```bash
# Layers 1–3: all in-process tests
gradle test

# Layer 4: requires services running
cd docker && docker compose up -d
gradle :jrpay-account-ledger:bootRun &
gradle :jrpay-vop-service:bootRun &
gradle :jrpay-smoke-tests:test
```
