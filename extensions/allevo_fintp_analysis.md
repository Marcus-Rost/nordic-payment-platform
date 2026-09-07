# Allevo FinTP Analysis — Lessons for Our System

*Allevo (Bucharest, Romania) — FinTP open-source payment processing platform*
*Source: allevo.ro, github.com/fintp, fintp.org*
*License: GPL v3*

---

## What Allevo FinTP Is

FinTP is an open-source payment processing application built by Allevo in Bucharest, Romania. Originally a proprietary product (qPayIntegrator, since 2001), Allevo open-sourced it under GPL v3. It is used in production by Romanian banks including:
- **Raiffeisen Bank Romania** — SEPA compliance
- **EximBank Romania** — SEPA compliance
- **Garanti BBVA Romania** — FinTP-Instant for real-time payments

FinTP processes financial transactions, handles ISO 20022 messaging, and provides SEPA + TARGET2 compliance.

## Key Features

| Feature | Description | Relevance to Our System |
|---------|-------------|------------------------|
| **ISO 20022 CBPR+ certified** | Passed self-attesting for Cross-border Payments and Reporting Plus readiness (Dec 2020) | Validates our ISO 20022 approach. We focus on pacs.008/002/004, camt.053 — same standards |
| **SEPA + TARGET2 compliance** | Full compliance with European payment clearing standards | We target SEPA SCT/SCT Inst. Same regulatory framework |
| **FinTP-Instant** | Real-time payment processing module | Validates our dual-mode (batch + instant) architecture in A2 |
| **Transaction filtering** | Compliance screening for sanctions/watchlists | Same as our A5 sanctions screening |
| **Duplicate detection** | Detects duplicate payment instructions beyond simple dedup | **KEY LEARNING**: We adopted semantic duplicate detection (same debtor+creditor+amount+currency within time window) based on this |
| **Accounting reconciliation** | Compares internal records against clearing house settlement reports | **KEY LEARNING**: We added reconciliation requirements (A1-F16 through F19, LED-F36 through F39) based on this |
| **Liquidity reporting** | Tracks and reports on settlement account liquidity | Validates our liquidity management in A2 (CLR-L01 through L06) |
| **Treasury operations** | End-to-end remittance management | Out of our scope (we focus on payment processing, not treasury) |
| **RESTful API** | Third-party integration API | Same approach — our sub-systems expose REST APIs |

## Technology Stack

- GitHub: [github.com/fintp](https://github.com/fintp) — 14 repositories
- Build: GNU Autotools (Unix), Visual Studio (Windows)
- Additional technologies: Apache, Apache Maven
- Architecture: Message-based flow automation between internal systems and external market infrastructures

## What We Adopted from FinTP

### 1. Reconciliation (adopted in A1 requirements)
- A1-F15: External reference IDs on ledger events (links to clearing cycle + settlement reference)
- A1-F16: Reconciliation status per transaction (UNRECONCILED → RECONCILED → DISCREPANCY)
- A1-F17: Reconciliation API (accepts settlement report, compares against internal records)
- LED-F36 through F39 in sub-project #9 requirements
- Use case: Scenario 1G (Daily Reconciliation) in ledger use cases

### 2. Semantic Duplicate Detection (adopted in A2 requirements)
- A2-F12: Detect probable duplicates (same debtor+creditor+amount+currency within configurable window)
- A5-F11: Duplicate override as fraud risk signal (+0.2)
- CLR-B13 through B15 in sub-project #11 requirements
- VEL-10 rule in sub-project #1 (fraud detection)
- Use case: Scenario 1F (Semantic Duplicate Detection) in clearing use cases
- Alternative flow AF-11 in master system UC-01

## What We Chose NOT to Adopt

| FinTP Feature | Why We Skipped It |
|--------------|-------------------|
| Treasury operations management | Out of scope — we focus on payment processing, not corporate treasury |
| SWIFT/TARGET2 direct connectivity | We model a single clearing engine, not actual SWIFT network integration |
| Competitive reporting | Business intelligence feature, not core payment processing |
| Paper-based remittance handling | Legacy feature, not relevant for modern API-first architecture |
| GPL v3 licensing model | Our project is a portfolio demonstration, not a distributed product |

## Potential Future Reference

FinTP's GitHub repos could serve as **implementation reference** when we start coding:
- ISO 20022 message parsing patterns
- Duplicate detection algorithm details
- Reconciliation matching logic
- Flow automation between internal components

However, FinTP is GPL v3, so any code we reference should be for learning only — we should write our own implementations to avoid license contamination.
