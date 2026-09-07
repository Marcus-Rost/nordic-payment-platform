# JRPay GUI Extension — Traceability Matrix

*Maps every requirement in `gui_requirements.md` to its realising use case(s), OpenAPI operation(s), and tests at each test-pyramid layer.*
*This file is a living artefact. CI gate: every requirement must have at least one mapping; every use case must back at least one requirement.*

*Last updated: 2026-04-23 (skeleton; populated incrementally during Phase 0 continuation and Phase 2+).*

---

## Format

CSV-style table per section (one row per requirement). Missing values are `—`; populated as the corresponding artefact lands.

Columns:
- `req` — requirement id
- `use_cases` — semicolon-separated UC-G ids
- `api_ops` — semicolon-separated OpenAPI operations (`<METHOD> <path>`)
- `tests.unit` / `tests.component` / `tests.contract` / `tests.e2e` / `tests.cross` — paths to realising tests per platform (semicolon-separated where multiple platforms apply)

---

## Cross-cutting (Section 2 of `gui_requirements.md`)

| req | use_cases | api_ops | tests |
|-----|-----------|---------|-------|
| GUI-SEC-01 | UC-G01;UC-G30 | POST /oauth2/authorize; POST /oauth2/token | — |
| GUI-SEC-02 | (all) | (all) | — |
| GUI-SEC-03 | (all web) | n/a | — |
| GUI-SEC-04 | (all web) | n/a | — |
| GUI-SEC-05 | (all mobile) | n/a | — |
| GUI-SEC-06 | UC-G01;UC-G27 | n/a | — |
| GUI-SEC-07 | UC-G27 | n/a | — |
| GUI-SEC-08 | UC-G05;UC-G51 | POST /sca/start; POST /sca/{sid}/verify | — |
| GUI-SEC-09 | UC-G05 | POST /api/v1/customer/payments/vop | — |
| GUI-SEC-10 | UC-G01;UC-G52 | n/a | — |
| GUI-SEC-11 | UC-G53 | POST /oauth2/revoke | — |
| GUI-SEC-12 | UC-G27 | n/a | — |
| GUI-SEC-13 | UC-G01 | POST /sca/{sid}/verify | — |
| GUI-SEC-14 | UC-G31..G43 | (admin endpoints) | — |
| GUI-SEC-15 | UC-G36;UC-G31 | POST /api/v1/admin/accounts/{id}/reveal-pii | — |
| GUI-SEC-16 | (all) | n/a | — |
| GUI-SEC-17 | UC-G53 | POST /oauth2/revoke | — |
| GUI-SEC-18 | (CI gate) | n/a | — |
| GUI-A11Y-01..07 | (all client) | n/a | — |
| GUI-I18N-01..05 | (all) | n/a | — |
| GUI-OPS-01..07 | (all) | n/a | — |
| GUI-PRV-01..05 | UC-G24;UC-G25;UC-G41 | (gdpr endpoints) | — |
| GUI-RES-01..05 | UC-G05;UC-G11..G14 | (writes) | — |

## Admin Web (Section 3)

| req | use_cases | api_ops | tests |
|-----|-----------|---------|-------|
| ADM-F01..F03 | UC-G30 | POST /oauth2/* | — |
| ADM-F10..F15 | UC-G31;UC-G32;UC-G33 | GET/POST /api/v1/admin/fraud/cases* | — |
| ADM-F20..F23 | UC-G34 | GET/POST /api/v1/admin/sanctions/* | — |
| ADM-F30..F36 | UC-G35;UC-G36 | GET/POST /api/v1/admin/accounts/* | — |
| ADM-F40..F43 | UC-G37;UC-G38 | (admin-payments) | — |
| ADM-F50..F53 | UC-G39 | (admin-reconciliation) | — |
| ADM-F60..F62 | UC-G40 | (admin-audit) | — |
| ADM-F70..F73 | UC-G41 | (admin-gdpr) | — |
| ADM-F80..F84 | UC-G42 | (system endpoints) | — |
| ADM-F90..F93 | UC-G43 | (admin-users) | — |
| ADM-NF01..NF04 | (all admin) | n/a | — |

## Client GUIs (Section 4)

| req | use_cases | api_ops | tests |
|-----|-----------|---------|-------|
| CLI-F01..F05 | UC-G01;UC-G27 | (oauth2 + identity) | — |
| CLI-F10..F14 | UC-G02;UC-G03;UC-G04 | (customer-accounts) | — |
| CLI-F20..F29 | UC-G05;UC-G06 | (customer-payments) | — |
| CLI-F40..F44 | UC-G07;UC-G08;UC-G09;UC-G10 | (customer-mandates) | — |
| CLI-F50..F54 | UC-G11..G15 | (customer-r2p) | — |
| CLI-F60..F62 | UC-G16 | (customer-beneficiaries) | — |
| CLI-F70..F73 | UC-G17;UC-G18 | (customer-consents) | — |
| CLI-F80..F84 | UC-G19;UC-G20;UC-G21;UC-G29 | (customer-notifications) | — |
| CLI-F90..F94 | UC-G22;UC-G23;UC-G24;UC-G25;UC-G26 | (profile + gdpr) | — |
| CLI-F100..F104 | (all client) | n/a | layer 5 cross-platform tests |
| CLI-NF01..NF07 | (all client) | n/a | (load + size budgets in CI) |

## Identity Service (Section 5)

| req | use_cases | api_ops | tests |
|-----|-----------|---------|-------|
| IDS-F01..F13 | UC-G01;UC-G30;UC-G50;UC-G51 | (identity.yaml) | — |

---

## How to update this file

When you add a use case, update the matching rows here. When you add or change an OpenAPI operation, search for `(<service>)` placeholders in the `api_ops` column and replace with the concrete operation. When you add a test, add its path to the appropriate test cell. CI verifies that every populated requirement has at least one test path that matches an existing file.
