# JRPay GUI Extension

*Multi-session initiative to add an Admin Web Console (case-workers + surveillance) and three feature-parity Client GUIs (Web, native iOS, native Android) on top of the JRPay payment-processing backend, fully wired to the real backend services with no mocks.*

*Initiative started: 2026-04-23.*

---

## Read this in order

1. [`PROJECT_DECISIONS.md`](PROJECT_DECISIONS.md) — the seven locked decisions (D1–D7). Read first.
2. [`requirements/gui_requirements.md`](requirements/gui_requirements.md) — master GUI requirements, including the cross-cutting security / accessibility / i18n / privacy / observability / resilience / test requirements that apply to all four GUIs.
3. [`architecture/ARCHITECTURE_DESIGN.md`](architecture/ARCHITECTURE_DESIGN.md) — the architectural design (principles, decomposition, auth, frontend, backend additions, contracts, parity, security, privacy, observability, testing, deployment).
4. [`architecture/ARCHITECTURE_DECISIONS.md`](architecture/ARCHITECTURE_DECISIONS.md) — options analysis for the open architectural questions (decisions G.1–G.20).
5. [`use_cases/INDEX.md`](use_cases/INDEX.md) — the 50+ use cases that realise the requirements.

## Use cases (Phase 0 + Phase 1 Bucket A fixes — 51 drafted)

Phase 0 produced 48 UCs; Phase 1 added 3 more (UC-G44, UC-G45, UC-G80) to close gaps identified in the architecture review. The four most detailed references — set during the kick-off session and preserved as the quality bar — are UC-G01, UC-G05, UC-G25, UC-G31.

Coverage by band:

- **Client narratives (29):** UC-G01..UC-G29 — account views, payment variants, mandates, VRP, R2P, beneficiaries, consents, notifications, profile, GDPR, mobile-specific (biometric, offline, push deep link).
- **Admin narratives (14):** UC-G30..UC-G43 — operator login + step-up SCA, fraud triage + decision flows, sanctions review, account admin, PII reveal, stuck payments + pacs.028, returns + pacs.004, reconciliation, audit + hash-chain verify, GDPR queue, system health + SLA, user/role admin.
- **Overflow (2, added in Phase 1):** UC-G44 password reset, UC-G45 privacy notice disclosure.
- **Cross-cutting (5):** UC-G50..UC-G54 — OAuth2+PKCE canonical, dynamic-linking SCA canonical, session-expiry warning, multi-device logout/revocation, status-banner display.
- **Shared platform surfaces (1, added in Phase 1):** UC-G80 browse notification inbox.

## Phase 1 — Architecture review complete

6 dedicated reviews produced 83 findings (0 CRITICAL, 15 HIGH, 42 MEDIUM, 26 LOW). Bucket A — all HIGH + pre-Phase-2 MEDIUMs — has been fixed. Tracking:

- [`reviews/REVIEW_PLAN.md`](reviews/REVIEW_PLAN.md) — the review discipline
- [`reviews/PHASE_1_SUMMARY.md`](reviews/PHASE_1_SUMMARY.md) — consolidated findings and recommended fix order
- [`reviews/FIXES_APPLIED.md`](reviews/FIXES_APPLIED.md) — finding-by-finding fix log
- Per-review `CHECKLIST.md`, `FINDINGS.md`, `SUMMARY.md` under `reviews/consistency/`, `reviews/security/`, `reviews/mobile_threat_model/`, `reviews/gdpr_pii/`, `reviews/accessibility/`, `reviews/interface_contracts/`.

Architecture companion documents introduced during Bucket A fixes:
- [`architecture/DYNAMIC_LINKING.md`](architecture/DYNAMIC_LINKING.md) — canonical SCA hash spec
- [`architecture/PROBLEM_TYPES.md`](architecture/PROBLEM_TYPES.md) — RFC 9457 registry
- [`architecture/SSE_CONVENTIONS.md`](architecture/SSE_CONVENTIONS.md)
- [`architecture/SIGNED_URL_CONVENTIONS.md`](architecture/SIGNED_URL_CONVENTIONS.md)
- [`architecture/SHARED_ENUMS.md`](architecture/SHARED_ENUMS.md)
- [`architecture/COMPONENTS_COUNTDOWN.md`](architecture/COMPONENTS_COUNTDOWN.md)

## How this folder relates to the rest of the project

- `requirements/system_requirements.md` (project root) is the *backend* requirements; `gui/requirements/gui_requirements.md` extends it with the GUI tier.
- `architecture/ARCHITECTURE_DESIGN.md` (project root) describes the existing backend; `gui/architecture/ARCHITECTURE_DESIGN.md` extends it.
- `architecture/INTERFACE_CONTRACTS.md` (project root) holds the existing canonical Kafka/REST contracts; the GUI tier adds new REST/SSE contracts under `implementation/api-specs/` (OpenAPI 3.1) and a small set of new Kafka topics described in `gui/architecture/ARCHITECTURE_DESIGN.md` §7.
- `use_cases/UC01..UC10*.md` (project root) are the 10 master backend use cases; `gui/use_cases/UC-G*.md` are the user-facing flows that consume them.

## Phase plan

The work is broken into 9 sequential phases (tracked in the task list of the AI session):

| Phase | Goal |
|-------|------|
| 0 | Discovery, locked decisions, requirements, architecture, representative use cases (in progress) |
| 1 | Architecture review (consistency, security/OWASP ASVS L2 + MASVS L1, accessibility WCAG 2.1 AA, GDPR/PII, mobile threat model, interface-contract bilateral verification) |
| 2 | Backend gap closure: identity service (A7), audit-query, notifications, A4 customer endpoints + SSE, A3 R2P/mandate endpoints, A5 case-management API + projection, A1 admin endpoints |
| 3 | Admin Web (G1) — exercises admin endpoints + auth model end-to-end |
| 4 | Client Web (G2) — reference implementation of the client narratives |
| 5 | Client iOS (G3) — SwiftUI; consumes shared OpenAPI client and i18n catalogue |
| 6 | Client Android (G4) — Jetpack Compose; consumes shared OpenAPI client and i18n catalogue |
| 7 | End-to-end verification: Playwright/XCUITest/Espresso vs live `docker-compose`; cross-platform consistency layer 5; WCAG audit; ASVS+MASVS pen-test pass |
| 8 | Production readiness: K8s manifests, OPERATIONS.md, mobile signing pipeline, traceability sign-off |

## Quality bar

Per the user-given rule (`memory/feedback_quality_over_speed.md`): quality is the only thing that matters. No phase is complete until its artefacts are reviewed using the same discipline that the existing backend artefacts were reviewed with (architecture consistency, security, crash resilience, financial integrity where relevant, regulatory, scalability, certification, interoperability — adapted for the GUI tier).
