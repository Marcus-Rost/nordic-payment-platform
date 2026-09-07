# Project #9: Event-Sourced Account Ledger — Gap Analysis

*Comparison against event sourcing frameworks, banking ledger products, and target company patterns*

---

## 1. Comparison with Target Company Patterns

### Tietoevry Banking (Primary Reference)

Tietoevry explicitly documents event sourcing and CQRS in transaction-heavy banking domains. Their approach:
- Java/Kotlin with Spring Boot/WebFlux
- Kafka for event streaming
- PostgreSQL for read models
- Domain-Driven Design as organizational principle
- Contract-first API design (Pact testing)

**Our alignment:** Direct match. Same technologies, same patterns.

### DNB

DNB uses event-driven architecture with Kafka as backbone, CQRS in some services, DDD. Their migration from monolith to microservices uses the Strangler Fig pattern — event sourcing naturally supports this (new events alongside legacy).

**Our alignment:** Strong. Our event sourcing + CQRS matches their architecture direction.

### SpareBank 1

SpareBank 1 migrated from Oracle to PostgreSQL (using ora2pg). Their 2024 JavaZone talk on "Using Fakes for DDD" demonstrates domain-driven testing patterns. Event sourcing enables this approach: fakes implement the event interface without needing a real database.

**Our alignment:** Good. ArchUnit tests enforcing event sourcing patterns align with their disciplined testing culture.

---

## 2. Comparison with Event Sourcing Frameworks

### Axon Framework (Most Popular Java ES Framework)

| Feature | Axon | Our System | Gap? |
|---------|------|-----------|------|
| Event store | Dedicated AxonDB or JPA-based | Kafka (append-only log) | Different choice, both valid |
| Event bus | Axon Server or Spring AMQP | Kafka | Our choice (Kafka) aligns with target companies |
| Saga management | Built-in saga orchestration | Not built-in — manual saga pattern | **Gap** but acceptable for scope |
| Snapshotting | Built-in aggregate snapshotting | Custom (LED-F40-F42) | Same concept, manual implementation |
| Command handling | `@CommandHandler` annotations | Custom command service | Simpler |
| Query handling | `@QueryHandler` with subscription queries | REST API + PostgreSQL projection | Simpler but standard |
| Dead letter queue | Built-in DLQ for failed events | Kafka DLQ (standard pattern) | Same pattern |
| Multi-tenancy | Supported | Not in scope | Different requirement |

**Key decision: Why NOT Axon?**
- Axon adds a framework dependency that obscures the event sourcing patterns. For a portfolio project, implementing ES from scratch (with Kafka + PostgreSQL) demonstrates deeper understanding than using a framework that hides the complexity
- Target companies (DNB, Tietoevry, SpareBank 1) use Kafka-native patterns, not Axon
- A portfolio reviewer sees more skill in raw Kafka + event replay than in `@EventHandler` annotations

**Verdict:** Not using Axon is a deliberate and justified choice.

### EventStoreDB

A dedicated event store database (originally from Greg Young's work). Supports event streams, projections, subscriptions.

**Why we don't use it:** Our target companies use Kafka, not EventStoreDB. Using Kafka for the event log is the pragmatic choice that aligns with the Norwegian tech ecosystem.

---

## 3. Comparison with Banking Ledger Products

### Thought Machine Vault (UK Fintech — Next-Gen Core Banking)

| Feature | Vault | Our System | Gap? |
|---------|-------|-----------|------|
| Event sourcing | Yes (all changes as events) | Yes | Same |
| Multi-currency | Yes (100+ currencies) | Yes (5 currencies: NOK, EUR, SEK, DKK, USD) | Smaller scope but same pattern |
| Balance holds | Yes ("posting instructions" with phases: pending→committed) | Yes (reserve→confirm/release) | Same pattern |
| Smart contracts | Yes (Python-based "smart contracts" define account behavior) | No | **Gap** — but this is Vault's unique value prop, not standard |
| Time travel | Yes (query any historical point-in-time balance) | We have event replay capability | **Partial** — we can rebuild but no point-in-time query API |
| Interest calculation | Yes | No | Out of scope (current accounts, not savings) |

**Key insight:** Thought Machine's "pending→committed" posting model is exactly our "reserve→confirm/release" pattern for instant payments. This validates our approach.

### Mambu (Cloud Banking Platform)

| Feature | Mambu | Our System | Gap? |
|---------|-------|-----------|------|
| Ledger | Double-entry, event-driven | Double-entry, event-sourced | Same |
| Multi-currency | Yes | Yes | Same |
| API-first | Yes (REST) | Yes (REST + OpenAPI) | Same |
| Composable | Microservices, API-based | Microservice within System A | Same |

---

## 4. "Exotic" Features Assessment

### Feature: Kafka as Event Store (Not a Dedicated Event Store)

**Is this unusual?** In the event sourcing community, using Kafka as the event store is debated. Purists prefer EventStoreDB or dedicated stores. In the banking industry, Kafka is standard.
**Justification:** DNB, SpareBank 1, NAV, Tietoevry all use Kafka. Using Kafka means the same infrastructure handles both the event store and the inter-service event bus — no additional technology to operate.
**Limitation we should document:** Kafka's default retention is time-based (7 days). For event sourcing, we need **infinite retention** (`retention.ms=-1`) on the `ledger.events` topic. This must be explicitly configured.
**Verdict:** **Keep. Aligned with target companies. Document the infinite retention requirement.**

### Feature: PostgreSQL SELECT FOR UPDATE SKIP LOCKED for Reservations

**Is this unusual?** In the context of balance reservation concurrency, this is a professional pattern. Most portfolio projects use optimistic locking or don't handle concurrency at all.
**Justification:** SKIP LOCKED prevents deadlocks and ensures one winner when multiple instant payments compete for the same account's balance. Standard PostgreSQL pattern for job queues and reservation systems.
**Verdict:** **Keep. Demonstrates production-grade concurrency handling.**

### Feature: JSR 354 / Moneta for Money Handling

**Is this unusual?** Most Java projects use `BigDecimal` directly. JSR 354 is the Java Money API standard but has low adoption.
**Justification:** Using the standard API demonstrates awareness that money has both amount and currency as a single concept. Prevents the common bug of adding EUR to NOK.
**Alternative:** We could use `BigDecimal` + separate currency field (simpler, more common). Both approaches are valid.
**Verdict:** **Keep JSR 354 as recommended. Note that BigDecimal + ISO 4217 string is an acceptable alternative.**

### Feature: ArchUnit for Architectural Fitness Functions

**Is this unusual?** Yes — very few portfolio projects use ArchUnit.
**Justification:** ArchUnit rules can enforce event sourcing patterns: "no service may directly write to the projection database — all writes must go through events." This is a powerful demonstration of architectural discipline.
**Verdict:** **Keep. Strong senior-level signal.**

---

## 5. Features Others Have That We Don't

| Feature | Who Has It | Priority | Recommendation |
|---------|-----------|----------|----------------|
| **Point-in-time balance query** | Thought Machine Vault | Medium | Query "what was the balance at timestamp X?" by replaying events. Useful for regulatory reporting. Medium effort — add as extension |
| **Saga orchestration** | Axon Framework | Low | For multi-step distributed transactions. We handle this manually in A2 (clearing engine). Not needed in the ledger itself |
| **Interest calculation** | Most core banking systems | Low | Out of scope — we model current/payment accounts, not savings accounts |
| **Multi-tenancy** | BaaS platforms (Mambu, Vault) | Low | Would require per-tenant data isolation. Out of scope for portfolio |
| **Idempotent consumer with exactly-once** | Kafka Streams EOS | Medium | We handle idempotency via idempotencyKey check. Kafka Streams exactly-once is an alternative. Both valid |
| **Event store compaction** | Some implementations | Low | Compact old events into snapshots to save space. We use snapshots (LED-F40) which achieves the same goal |

**Top recommendation:** Point-in-time balance query. It would allow the system to answer "what was account A's balance at 2026-03-31 23:59:59?" without maintaining separate period-end snapshots. Implement by filtering events up to the target timestamp during replay.

---

## 6. Summary

| Category | Assessment |
|----------|-----------|
| **Architecture** | Strong. Event sourcing + CQRS over Kafka + PostgreSQL is the professional approach for banking. Matches DNB, Tietoevry, SpareBank 1 patterns |
| **Target company alignment** | Excellent. Kafka-native event sourcing, DDD, contract-first API — all match target company stacks |
| **Framework comparison** | Not using Axon is justified — shows deeper understanding. Using Kafka aligns with target companies |
| **Banking product comparison** | Realistic subset of Thought Machine Vault / Mambu patterns. Balance reservation matches Vault's pending→committed model |
| **Exotic features** | All justified. Kafka as event store (target company alignment), SKIP LOCKED (production concurrency), JSR 354 (correctness), ArchUnit (discipline) |
| **Key gap** | Point-in-time balance query. Valuable for regulatory reporting. Medium effort to add |
