# Nordic Payment Processing Platform

A security-focused reference implementation for Nordic and European payments, covering initiation, Verification of Payee, fraud and sanctions controls, clearing, and event-sourced accounting.

> Independent portfolio project—not a bank, payment institution, or production payment service.

## Source code and tests

- [Backend implementation](implementation/) — 478 Java and 34 Kotlin files
- [Account-ledger source](implementation/jrpay-account-ledger/src/main/java/)
- [Fraud-detection source](implementation/jrpay-fraud-detection/src/main/java/)
- [Web clients](implementation/admin-web/src/) and [mobile clients](implementation/android-app/app/src/)
- [Cross-service smoke tests](implementation/jrpay-smoke-tests/src/test/java/)
- Unit and integration tests are colocated under each module's `src/test/` directory

## The problem

Moving money safely is a distributed-systems problem: every retry, crash, timeout, duplicate, late response, and reconciliation mismatch must have a defined outcome. The project translates those concerns—and PSD2, instant-payment, privacy, and operational-resilience requirements—into service boundaries and tests.

```text
Initiation → Verification of Payee → fraud/sanctions → clearing → ledger
                                  ↘ audit, status, notification, recovery ↗
```

## System areas

1. Event-sourced account ledger
2. Batch and instant clearing
3. Bill payment and Request-to-Pay
4. PSD2/Open Banking gateway
5. Fraud and sanctions screening
6. Verification of Payee

Supporting modules cover identity and SCA, notifications, audit querying, shared domain types, security defaults, API specifications, and Android/iOS/web clients.

## Implementation status

| Capability | Status |
|---|---|
| Six core service modules | ✅ Implemented reference services |
| Identity, notification, and audit modules | ✅ Implemented |
| Account-ledger and VoP runtime demo | ✅ Documented and smoke-tested |
| Unit, service-use-case, system-use-case, and smoke layers | ✅ Included |
| Mobile and web clients | 🟡 Reference implementations |
| Production clearing rails, HSM, sanctions feeds, and bank connectivity | 📐 Designed / simulated |

## Quick start

Requirements: JDK 21, Gradle 8.12+, Docker Compose, and approximately 4 GB free memory.

```bash
cd implementation
gradle build
cd docker && docker compose up -d
```

The fastest infrastructure-backed demonstration starts the account-ledger and Verification-of-Payee services and then runs `implementation/scripts/run-demo.sh`. See [BUILD_GUIDE.md](BUILD_GUIDE.md) for exact commands.

## Engineering highlights

- Event sourcing and rebuildable projections for ledger truth
- Compound debit/credit events and decimal money types
- Three-layer idempotency and explicit balance reservations
- Separate instant and batch clearing paths
- Structural Nordic name matching and anti-enumeration controls
- Tamper-evident auditing, reconciliation, and crypto-shredding design
- Explicit crash-point analysis and failure-injection plans

## Repository map

- `implementation/` — services, applications, tests, API specifications, and local infrastructure
- `architecture/` — system design, decisions, and interface contracts
- `subsystems/` — requirements and designs by bounded context
- `use_cases/` — cross-service payment journeys
- `requirements/` — system requirements and regulatory mapping
- [Roadmap](ROADMAP.md) · [Implementation boundary](docs/IMPLEMENTATION_STATUS.md) · [Navigation](NAVIGATION.md)

## Safety

All accounts, identities, balances, and transactions used in examples are synthetic. Real credentials, personal data, and production financial integrations do not belong in this repository.
