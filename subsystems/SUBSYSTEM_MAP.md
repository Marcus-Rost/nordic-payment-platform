# Sub-system Map — Quick Reference

*One-page view of all sub-systems and their locations*

---

## Sub-system → Folder Mapping

| ID | Sub-system Name | Folder | Role in System A |
|----|----------------|--------|-----------------|
| **A1** | Event-Sourced Account Ledger | `../09_Event_Sourced_Account_Ledger/` | Single source of truth for balances. Event sourcing + CQRS. Balance holds for instant payments. Reconciliation |
| **A2** | Payment Clearing & Settlement Engine | `../11_Payment_Clearing_Settlement_Engine/` | Orchestrator. Batch clearing (netting, Spring Batch) + instant (EPC 2025 SLA (5s/7s/9s), Kafka Streams). Liquidity. Semantic duplicate detection. ISO 20022 |
| **A3** | Bill Payment & Request-to-Pay Service | `../12_Bill_Payment_Request_to_Pay_Service/` | eFaktura/SRTP. Accept/decline/defer/amend. Recurring mandates (AvtaleGiro). QR codes. HMAC-signed webhooks |
| **A4** | PSD2 Open Banking API Gateway | `../02_Open_Banking_PSD2_API/` | OAuth2 consent + SCA with exemption engine. AISP/PISP. VRP. BerlinGroup NextGenPSD2 |
| **A5** | Fraud Detection & Sanctions Engine | `../01_Real_time_Transaction_Fraud_Detection_Engine/` | Real-time scoring: sanctions (Bloom+Aho-Corasick+fuzzy) → velocity rules → anomaly detection → ML (DJL). Case management |
| **A6** | Verification of Payee Service | `../21_Verification_of_Payee_Service/` | Pre-payment name-IBAN check. Requester + responder roles. Fuzzy matching. Anti-enumeration. Feeds into A5 |
| **--** | Secure Spring Boot Platform Starter | `../04_Secure_Spring_Boot_Platform_Starter/` | Cross-cutting: security headers, @Audited, Prometheus metrics, health checks, graceful shutdown. Used by all |

---

## Data Flow Between Sub-systems

```
Payer → A3 (Request-to-Pay) or A4 (PSD2 API)
           │
           ├──→ A6 (VoP: verify beneficiary name)
           │
           ├──→ A2 (Clearing Engine)
           │       │
           │       ├──→ A1 (reserve balance)
           │       │
           │       ├──→ A5 (sanctions + fraud scoring)
           │       │       │
           │       │       └──→ A6 result fed into fraud score
           │       │
           │       ├──→ A1 (confirm/release balance, credit beneficiary)
           │       │
           │       └──→ Status update → A3/A4 → webhook/response to payer
           │
           └──→ A1 (reconciliation against A2 settlement reports)
```

---

## Technology Stack Per Sub-system

| Sub-system | Primary Tech | Key Libraries/Frameworks |
|------------|-------------|-------------------------|
| A1 | Java 17+, Spring Boot, Kafka, PostgreSQL | Avro + Schema Registry, JSR 354/Moneta, JAXB (camt.053) |
| A2 | Java 17+, Spring Boot, Spring Batch, Kafka Streams, PostgreSQL | JAXB (ISO 20022), CompletableFuture.orTimeout() |
| A3 | Java 17+, Spring Boot, PostgreSQL, Kafka | ZXing (QR), WireMock (testing), HMAC-SHA256 |
| A4 | Java 17+, Spring Boot, Spring Authorization Server, PostgreSQL | mock-oauth2-server (NAV), Pact, Nimbus JOSE (JWE), Bucket4j |
| A5 | Java 17+, Spring Boot, Kafka Streams, PostgreSQL | DJL (ML), Guava BloomFilter, Aho-Corasick, Apache Commons Text |
| A6 | Java 17+, Spring Boot, Caffeine cache | Apache Commons Text (JaroWinklerSimilarity), Bucket4j |
| Starter | Java 17+, Spring Boot auto-config, Gradle plugin | Micrometer, logstash-logback-encoder, CycloneDX |

---

## Port Assignments (for docker-compose)

Suggested ports for local development:

| Sub-system | App Port | Management Port | Database |
|-----------|----------|----------------|----------|
| A1 Account Ledger | 8091 | 9091 | PostgreSQL :5441 |
| A2 Clearing Engine | 8092 | 9082 | PostgreSQL :5442 |
| A3 R2P Service | 8093 | 9093 | PostgreSQL :5443 |
| A4 PSD2 API Gateway | 8094 | 9094 | PostgreSQL :5444 |
| A5 Fraud & Sanctions | 8095 | 9095 | PostgreSQL :5445 |
| A6 VoP Service | 8096 | 9096 | (uses A1's data via REST) |
| Kafka | — | — | :9092 (broker), :8081 (Schema Registry) |
| Grafana | — | — | :3000 |
| Prometheus | — | — | :9090 |
