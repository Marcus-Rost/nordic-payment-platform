# Payment Processing System — Navigation & Cross-References

*Use this file to understand how the master system documents relate to sub-project folders.*
*When working in another terminal/session, read this file first for orientation.*

---

## Architecture: 6 Sub-systems

```
A1 Account Ledger          →  ../09_Event_Sourced_Account_Ledger/
A2 Clearing Engine         →  ../11_Payment_Clearing_Settlement_Engine/
A3 Bill Payment / R2P      →  ../12_Bill_Payment_Request_to_Pay_Service/
A4 PSD2 API Gateway        →  ../02_Open_Banking_PSD2_API/
A5 Fraud & Sanctions       →  ../01_Real_time_Transaction_Fraud_Detection_Engine/
A6 Verification of Payee   →  ../21_Verification_of_Payee_Service/
Cross-cutting Starter      →  ../04_Secure_Spring_Boot_Platform_Starter/
```

---

## Cross-Reference: Master Use Cases → Sub-project Documents

Each master use case involves multiple sub-systems. This table shows which sub-project documents contain the detailed requirements for each participant.

| Master Use Case | Sub-systems | Sub-project Requirements | Sub-project Use Cases |
|----------------|------------|--------------------------|----------------------|
| **UC-01** Invoice Payment | A3, A6, A2, A5, A1 | [A3 reqs](../12_Bill_Payment_Request_to_Pay_Service/requirements/requirements.md), [A6 reqs](../21_Verification_of_Payee_Service/requirements/requirements.md), [A2 reqs](../11_Payment_Clearing_Settlement_Engine/requirements/requirements.md), [A5 reqs](../01_Real_time_Transaction_Fraud_Detection_Engine/requirements/requirements.md), [A1 reqs](../09_Event_Sourced_Account_Ledger/requirements/requirements.md) | [A3 UC01](../12_Bill_Payment_Request_to_Pay_Service/use_cases/UC01_request_to_pay_lifecycle.md), [A5 UC01](../01_Real_time_Transaction_Fraud_Detection_Engine/use_cases/UC01_transaction_scoring.md), [A1 UC01](../09_Event_Sourced_Account_Ledger/use_cases/UC01_ledger_operations.md) |
| **UC-02** PSD2 Account Access | A4, A1 | [A4 reqs](../02_Open_Banking_PSD2_API/requirements/requirements.md), [A1 reqs](../09_Event_Sourced_Account_Ledger/requirements/requirements.md) | [A4 UC01](../02_Open_Banking_PSD2_API/use_cases/UC01_consent_flow.md) |
| **UC-03** PSD2 Payment Init | A4, A6, A2, A5, A1 | [A4 reqs](../02_Open_Banking_PSD2_API/requirements/requirements.md), [A6 reqs](../21_Verification_of_Payee_Service/requirements/requirements.md), [A2 reqs](../11_Payment_Clearing_Settlement_Engine/requirements/requirements.md), [A5 reqs](../01_Real_time_Transaction_Fraud_Detection_Engine/requirements/requirements.md) | [A4 UC02](../02_Open_Banking_PSD2_API/use_cases/UC02_payment_initiation_and_sca.md) |
| **UC-04** Batch Clearing | A2, A6, A5, A1 | [A2 reqs](../11_Payment_Clearing_Settlement_Engine/requirements/requirements.md) | [A2 UC01](../11_Payment_Clearing_Settlement_Engine/use_cases/UC01_batch_and_instant_clearing.md) |
| **UC-05** Fraud Detection | A5, A6 input | [A5 reqs](../01_Real_time_Transaction_Fraud_Detection_Engine/requirements/requirements.md) | [A5 UC01](../01_Real_time_Transaction_Fraud_Detection_Engine/use_cases/UC01_transaction_scoring.md), [A5 UC02](../01_Real_time_Transaction_Fraud_Detection_Engine/use_cases/UC02_case_management.md) |
| **UC-06** Recurring Debit | A3, A2, A1 | [A3 reqs](../12_Bill_Payment_Request_to_Pay_Service/requirements/requirements.md) | [A3 UC01](../12_Bill_Payment_Request_to_Pay_Service/use_cases/UC01_request_to_pay_lifecycle.md) (mandate scenarios) |
| **UC-07** Account Mgmt | A1 | [A1 reqs](../09_Event_Sourced_Account_Ledger/requirements/requirements.md) | [A1 UC01](../09_Event_Sourced_Account_Ledger/use_cases/UC01_ledger_operations.md) (account lifecycle scenarios) |
| **UC-08** Instant Payment | A2, A6, A5, A1 | [A2 reqs](../11_Payment_Clearing_Settlement_Engine/requirements/requirements.md) | [A2 UC01](../11_Payment_Clearing_Settlement_Engine/use_cases/UC01_batch_and_instant_clearing.md) (instant scenarios) |
| **UC-09** VoP | A6, A1, A5 | [A6 reqs](../21_Verification_of_Payee_Service/requirements/requirements.md) | [A6 UC01](../21_Verification_of_Payee_Service/use_cases/UC01_verify_before_payment.md) |
| **UC-10** VRP | A4, A2, A1 | [A4 reqs](../02_Open_Banking_PSD2_API/requirements/requirements.md) | [A4 UC02](../02_Open_Banking_PSD2_API/use_cases/UC02_payment_initiation_and_sca.md) (VRP scenarios) |

---

## Cross-Reference: Requirement IDs → Sub-project Location

When you see a requirement ID in the master system_requirements.md, here's where to find the detailed version:

| Req ID prefix | Sub-system | Detailed requirements at |
|--------------|------------|------------------------|
| A1-F*, A1-NF*, A1-S* | A1 Account Ledger | [../09_Event_Sourced_Account_Ledger/requirements/requirements.md](../09_Event_Sourced_Account_Ledger/requirements/requirements.md) (LED-F* IDs) |
| A2-F*, A2-NF*, A2-S*, CLR-* | A2 Clearing Engine | [../11_Payment_Clearing_Settlement_Engine/requirements/requirements.md](../11_Payment_Clearing_Settlement_Engine/requirements/requirements.md) (CLR-B*, CLR-I*, CLR-L* IDs) |
| A3-F*, A3-NF*, A3-S*, RTP-* | A3 Bill Payment / R2P | [../12_Bill_Payment_Request_to_Pay_Service/requirements/requirements.md](../12_Bill_Payment_Request_to_Pay_Service/requirements/requirements.md) (RTP-F*, RTP-M*, RTP-Q*, RTP-W* IDs) |
| A4-F*, AUTH-*, SCA-*, EXE-*, VRP-*, AISP-*, PISP-*, TPP-* | A4 PSD2 API Gateway | [../02_Open_Banking_PSD2_API/requirements/requirements.md](../02_Open_Banking_PSD2_API/requirements/requirements.md) |
| A5-F*, SAN-*, FRD-*, ML-*, CASE-* | A5 Fraud & Sanctions | [../01_Real_time_Transaction_Fraud_Detection_Engine/requirements/requirements.md](../01_Real_time_Transaction_Fraud_Detection_Engine/requirements/requirements.md) |
| A6-F*, VOP-* | A6 Verification of Payee | [../21_Verification_of_Payee_Service/requirements/requirements.md](../21_Verification_of_Payee_Service/requirements/requirements.md) |
| SEC-*, OPS-*, DATA-* | Cross-cutting | Master [system_requirements.md](requirements/system_requirements.md) only |
| STARTER-* | Platform Starter | [../04_Secure_Spring_Boot_Platform_Starter/requirements/requirements.md](../04_Secure_Spring_Boot_Platform_Starter/requirements/requirements.md) |

---

## Cross-Reference: Kafka Topics → Producing/Consuming Sub-systems

| Topic | Producer | Consumer | Master req | Sub-project detail |
|-------|----------|----------|-----------|-------------------|
| `payment.instructions` | A3, A4 | A2 (batch) | Section 9.1 | [A2 CLR-B01](../11_Payment_Clearing_Settlement_Engine/requirements/requirements.md) |
| `payment.instant-instructions` | A3, A4 | A2 (instant) | Section 9.1 | [A2 CLR-I01](../11_Payment_Clearing_Settlement_Engine/requirements/requirements.md) |
| `payment.fraud-check-request` | A2 | A5 | Section 9.1 | [A5 FRD-F01](../01_Real_time_Transaction_Fraud_Detection_Engine/requirements/requirements.md) |
| `payment.instant-fraud-check-request` | A2 | A5 | Section 9.1 | [A5 FRD-F01](../01_Real_time_Transaction_Fraud_Detection_Engine/requirements/requirements.md) |
| `payment.fraud-check-response` | A5 | A2 | Section 9.1 | [A5 FRD-F06](../01_Real_time_Transaction_Fraud_Detection_Engine/requirements/requirements.md) |
| `payment.instant-fraud-check-response` | A5 | A2 | Section 9.1 | [A5 FRD-F06](../01_Real_time_Transaction_Fraud_Detection_Engine/requirements/requirements.md) |
| `payment.settlement-events` | A2 | A1 | Section 9.1 | [A1 LED-F01](../09_Event_Sourced_Account_Ledger/requirements/requirements.md) |
| `payment.instant-settlement-events` | A2 | A1 | Section 9.1 | [A1 LED-F01](../09_Event_Sourced_Account_Ledger/requirements/requirements.md) |
| `payment.status-updates` | A2 | A3, A4 | Section 9.1 | [A3 RTP-F08](../12_Bill_Payment_Request_to_Pay_Service/requirements/requirements.md) |
| `payment.balance-reservation` | A2 | A1 | Section 9.1 | [A1 LED-F10](../09_Event_Sourced_Account_Ledger/requirements/requirements.md) |
| `payment.balance-reservation-response` | A1 | A2 | Section 9.1 | Reservation results (confirmed/rejected/released) |
| `payment.reconciliation-results` | A1 | A2, monitoring | Section 9.1 | [A1 LED-F39](../09_Event_Sourced_Account_Ledger/requirements/requirements.md) |
| `ledger.events` | A1 | A1 (projections) | — | [A1 LED-F03](../09_Event_Sourced_Account_Ledger/requirements/requirements.md) |
| `sanctions.list-updates` | External | A5 | — | [A5 SAN-F24](../01_Real_time_Transaction_Fraud_Detection_Engine/requirements/requirements.md) |
| `account.freeze-commands` | A5 | A1 | — | [A5 CASE-F04](../01_Real_time_Transaction_Fraud_Detection_Engine/requirements/requirements.md) |
| `audit.events` | All | Audit consumer | Section 9.1 | Cross-cutting (Starter [STARTER-F13](../04_Secure_Spring_Boot_Platform_Starter/requirements/requirements.md)) |
| `audit.checkpoints` | A1 | Audit verification | — | Daily signed audit hash chain checkpoints (5-year retention) |
| `payment.investigation` | A2 | Operator dashboard | — | Stuck payment alerts for operator review |
| `payment.return-requests` | A2 | A2 (internal) | — | pacs.004 return request processing |
| `payment.status-request` | A2 | A2 (internal) | — | pacs.028 status query processing (enhancement) |

---

## Cross-Reference: Extension Documents → What They Cover

| Extension | Key findings | Requirements impacted |
|-----------|-------------|----------------------|
| [gap_analysis.md](extensions/gap_analysis.md) | 3 critical gaps (instant, VoP, sanctions), 8 exotic features (all justified) | Led to: UC-08, A6 sub-system, sanctions in A5, SCA exemptions in A4, VRP, R2P evolution |
| [instant_payments.md](extensions/instant_payments.md) | EPC 2025 SLA (5s/7s/9s) time budget, batch vs. instant differences, Kafka topic separation | Led to: A2-F20 through F30 (instant path requirements) |
| [verification_of_payee.md](extensions/verification_of_payee.md) | VoP algorithm, API design, fraud integration, anti-enumeration | Led to: A6 sub-system, VOP-* requirements, Project #21 |
| [sanctions_screening.md](extensions/sanctions_screening.md) | Bloom filter + Aho-Corasick + fuzzy match architecture | Led to: A5-F20 through F29 (sanctions requirements) |
| [further_extensions_overview.md](extensions/further_extensions_overview.md) | SCA exemptions, SDD, liquidity, VRP, QR, proxy, CBDC | Led to: EXE-F01 through F08, CLR-L01 through L06, VRP-F01 through F08, RTP-Q01 through Q05 |
| [allevo_fintp_analysis.md](extensions/allevo_fintp_analysis.md) | Reconciliation and semantic duplicate detection from Allevo FinTP (Bucharest) | Led to: A1-F15 through F17 (reconciliation), A2-F12 (semantic dedup), LED-F36 through F39, CLR-B13 through B15, VEL-10 |

---

## Quick Start for a New Session

If you're Claude starting a new session and need to work on this project:

1. **Read this file first** (you're reading it now)
2. **Read [../TOC.md](../TOC.md)** for the full workspace index
3. **For system-level context**: read [requirements/system_requirements.md](requirements/system_requirements.md) (the master requirements)
4. **For interface contracts**: read [architecture/INTERFACE_CONTRACTS.md](architecture/INTERFACE_CONTRACTS.md) (canonical Kafka, REST, schema, and enum definitions between services)
5. **For a specific sub-system**: find it in the table above and read its requirements + use cases in the linked sub-project folder
5. **For technology decisions**: read [../oslo/PROJECT_SUGGESTIONS.md](../oslo/PROJECT_SUGGESTIONS.md) for tech stack rationale and target company alignment
6. **For gap analysis context**: read [extensions/gap_analysis.md](extensions/gap_analysis.md) for what was compared and decided

### Key architectural decisions to know:
- **Event sourcing + CQRS** for the account ledger (A1) — Kafka as event store, PostgreSQL for projections
- **Dual-mode clearing** (A2) — Spring Batch for batch netting, Kafka Streams for instant (EPC 2025 SLA (5s/7s/9s))
- **Three-layer fraud detection** (A5) — sanctions screening (Bloom filter + Aho-Corasick + fuzzy) → velocity rules → ML scoring (DJL)
- **OAuth2 with SCA exemption engine** (A4) — Spring Authorization Server, 4 exemption types, VRP
- **VoP before every payment** (A6) — Jaro-Winkler fuzzy matching, anti-enumeration, fraud integration
- **Reconciliation** (A1↔A2) — external references on events, daily reconciliation against settlement reports
- **Semantic duplicate detection** (A2) — warns but doesn't block (same debtor+creditor+amount within 10 min)
