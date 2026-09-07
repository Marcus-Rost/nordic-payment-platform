# Project #21: Verification of Payee (VoP) Service — Requirements

*System A role: A6 — Pre-payment name-IBAN verification*
*Target companies: All PSD2 implementers — Mastercard Oslo, DNB, SpareBank 1, Tietoevry, Kantega (BankID), Vipps*
*Regulatory basis: EU Instant Payment Regulation 2024/886, Article 5c*
*Mandatory from October 2025 for all euro credit transfers (instant and non-instant)*
*Reference implementations: SurePay (Netherlands, 2017), UK CoP (Pay.UK, 2020)*

---

## 1. Overview

A lightweight, high-performance service that verifies whether the beneficiary name the payer entered matches the actual account holder name at the destination IBAN — before the payment is executed. Implements two roles:

- **Requester**: Before our users send money out, we check with the beneficiary's bank
- **Responder**: When other banks' users send money to our accounts, they check with us

This is the **single most impactful fraud prevention measure** mandated by the EU IPR. The Netherlands (SurePay, since 2017) proved 30-40% reduction in authorized push payment (APP) fraud.

### 1.1 Target Company Alignment

| Company | Relevance |
|---------|-----------|
| **Mastercard Oslo** | Operates NICS clearing — VoP must be integrated into the clearing pipeline. Every payment flowing through NICS needs VoP |
| **DNB** | Norway's largest bank — must implement VoP for all outgoing and incoming credit transfers |
| **SpareBank 1** | 14 banks + 1 million customers — VoP must work across the alliance |
| **Tietoevry** | Provides BaaS to 100+ banks — VoP is a platform feature they offer to all clients |
| **Kantega** | Built the BankID platform (OpenID Connect). VoP uses similar identity verification patterns |
| **Vipps** | P2P payments need name verification. Vipps already does phone→account mapping; VoP adds name→IBAN mapping |

---

## 2. Functional Requirements

### 2.1 Requester Role (Outbound — Check Before Sending)

| ID | Requirement | Priority |
|----|------------|----------|
| VOP-R01 | Before any credit transfer, query beneficiary PSP with (IBAN, stated_name) | Must |
| VOP-R02 | Return match result to calling service (A2, A3, or A4): MATCH, CLOSE_MATCH, NO_MATCH, NOT_FOUND, TIMEOUT, ERROR | Must |
| VOP-R03 | On CLOSE_MATCH: include actual_name in response (so UI can ask "Did you mean...?") | Must |
| VOP-R04 | On NO_MATCH: do NOT include actual_name (privacy — we can't reveal the real holder to a mismatched query) | Must |
| VOP-R05 | Latency: < 500ms total (including network to external PSP) | Must |
| VOP-R06 | On TIMEOUT or ERROR: proceed with payment but flag for review (VoP is important but not a hard blocker like sanctions) | Should |
| VOP-R07 | Forward VoP result to A5 (Fraud Engine) as fraud scoring input | Must |

### 2.2 Responder Role (Inbound — Answer Queries About Our Accounts)

| ID | Requirement | Priority |
|----|------------|----------|
| VOP-P01 | External API: `POST /api/v1/vop/check` accepting `{iban, name}`. Returns `{result, actual_name (if CLOSE_MATCH)}` | Must |
| VOP-P02 | Authentication: mTLS (client certificate) or API key for querying PSPs | Must |
| VOP-P03 | Lookup IBAN in A1 Account Ledger → retrieve account holder name | Must |
| VOP-P04 | Apply fuzzy name matching algorithm → return result | Must |
| VOP-P05 | Responder latency: < 100ms (p95) | Must |
| VOP-P06 | Available 24/7 when instant payments are active | Must |
| VOP-P07 | Free of charge (regulatory requirement) | Must |

### 2.3 Name Matching

| ID | Requirement | Priority |
|----|------------|----------|
| VOP-N01 | Case-insensitive comparison | Must |
| VOP-N02 | Diacritics normalization: ø→o, å→a, ä→a, ü→u, é→e, etc. (Unicode NFD decomposition + strip combining marks) | Must |
| VOP-N03 | Common abbreviations: AS↔Aksjeselskap, ASA↔Allmennaksjeselskap, Ltd↔Limited, GmbH↔Gesellschaft mit beschränkter Haftung | Must |
| VOP-N04 | Name order: "Nordmann, Ola" matches "Ola Nordmann" | Must |
| VOP-N05 | Prefixes: "van der Berg" matches "Berg, van der" | Must |
| VOP-N06 | Whitespace normalization: collapse multiple spaces, trim | Must |
| VOP-N07 | Configurable thresholds: MATCH >= 0.95, CLOSE_MATCH >= 0.85, NO_MATCH < 0.85 (Jaro-Winkler) | Must |
| VOP-N08 | Handle legal entity suffixes separately: compare "Nordmann" (base name) independently from "AS" (suffix) | Should |

### 2.4 Anti-Enumeration Protection

| ID | Requirement | Priority |
|----|------------|----------|
| VOP-E01 | Rate limit per querying PSP: max 100 queries/minute (configurable) | Must |
| VOP-E02 | Detect repeated queries for same IBAN with different names (> 5 in 1 hour) → flag as potential enumeration → temporarily block that IBAN for that PSP | Should |
| VOP-E03 | All queries logged with querying PSP identity for audit | Must |
| VOP-E04 | On NO_MATCH: never reveal actual name (prevents name harvesting) | Must |

### 2.5 Fraud Integration

| ID | Requirement | Priority |
|----|------------|----------|
| VOP-I01 | VoP result included in fraud-check-request message to A5 | Must |
| VOP-I02 | Fraud score adjustment: NO_MATCH + payer override → +0.3. NOT_FOUND + payer override → +0.4 | Must |
| VOP-I03 | CLOSE_MATCH + payer ignores correction → +0.1 (mild risk) | Should |

---

## 3. Non-functional Requirements

| ID | Requirement | Target |
|----|------------|--------|
| VOP-NF01 | Responder query latency | < 100ms (p95) |
| VOP-NF02 | Requester end-to-end | < 500ms (p95, including network) |
| VOP-NF03 | Availability | 99.99% (24/7 when instant payments active) |
| VOP-NF04 | Throughput | >= 2,000 queries/second (every payment triggers a query) |
| VOP-NF05 | Name cache hit rate | > 95% (most lookups served from cache) |

### 3.1 Resilience

*Propagated from master system requirements*

| ID | Requirement | Priority |
|----|------------|----------|
| VOP-RES01 | **Graceful degradation on A1 unavailability**: if A1 Account Ledger REST API is unavailable, serve queries from the name cache (Caffeine). If cache miss and A1 is down, return `ERROR` result — calling service will proceed with VOP_TIMEOUT flag per SEC-12 | Must |
| VOP-RES02 | **Mandate VoP cache**: support a 90-day TTL cache entry for recurring mandate payments. A3 may request a cached check for a specific mandate+beneficiary. Re-check required if: (a) 90 days since last check, (b) A1 notifies of account holder name change, (c) mandate modified. This reduces load for predictable recurring payments | Should |

---

## 4. Technology Stack

| Component | Technology | Rationale |
|-----------|-----------|-----------|
| Framework | **Spring Boot 3.x** | Universal |
| Name normalization | **java.text.Normalizer** (NFD) + custom strip combining marks | Standard Java Unicode handling |
| Fuzzy matching | **Apache Commons Text** `JaroWinklerSimilarity` | Industry standard. Configurable thresholds |
| Phonetic matching | **Apache Commons Codec** `Soundex` / `Metaphone` | Supplement Jaro-Winkler for transliteration cases |
| Name cache | **Caffeine** (in-memory, TTL-based, max 100K entries) | Sub-ms lookups. Invalidated on name change events from A1 |
| Rate limiting | **Bucket4j** | Per-PSP rate limiting with configurable limits |
| Auth (responder) | **Spring Security** with mTLS or API key | Only registered PSPs can query |
| API | **REST + OpenAPI 3.0** | Internal (requester) + external (responder) |
| Data source | A1 Account Ledger via REST or Kafka event | Account holder names |
| Audit | Kafka `audit.events` | Every query logged |
| Monitoring | **Micrometer + Prometheus** | Query volume, match rates, latency |
| Testing | **JUnit 5**, Nordic name test fixtures | Edge cases: Bjørn/Bjorn, Ø/O, compound names, AS/ASA |

---

## 5. Data Model

### VoP Query Log (PostgreSQL — for audit and analytics)
```
query_id:           UUID (PK)
direction:          Enum (OUTBOUND_REQUEST, INBOUND_QUERY)
querying_psp_id:    String
iban:               String
stated_name:        String
actual_name:        String (nullable — only for MATCH/CLOSE_MATCH)
result:             Enum (MATCH, CLOSE_MATCH, NO_MATCH, NOT_FOUND, TIMEOUT, ERROR)
similarity_score:   Double (Jaro-Winkler)
response_time_ms:   Long
payer_override:     Boolean (nullable — only for outbound)
timestamp:          Instant (UTC)
```

### Name Cache Entry (Caffeine — in-memory)
```
Key:    IBAN (String)
Value:  {holderName: String, normalizedName: String, lastUpdated: Instant}
TTL:    1 hour (or invalidated by A1 name-change event)
```

---

## 6. API Design

### Internal Requester API
```
POST /internal/vop/verify
Authorization: (service-to-service)
{
  "beneficiary_iban": "NO93 8601 1117 947",
  "stated_name": "Ola Nordmann",
  "request_id": "uuid"
}

→ 200 OK
{
  "request_id": "uuid",
  "result": "CLOSE_MATCH",
  "actual_name": "Ola Nordmann AS",
  "similarity_score": 0.91,
  "response_time_ms": 45
}
```

### External Responder API
```
POST /api/v1/vop/check
X-API-Key: {psp-api-key}  (or mTLS client cert)
{
  "iban": "NO93 8601 1117 947",
  "name": "Ola Nordmann"
}

→ 200 OK
{
  "result": "MATCH"
}

(Note: on NO_MATCH, actual_name is NOT returned)
```

---

## 7. Security Requirements

| ID | Requirement |
|----|------------|
| VOP-S01 | Responder API: only authenticated PSPs (mTLS or API key) |
| VOP-S02 | Anti-enumeration: rate limiting + repeated query detection |
| VOP-S03 | Privacy: never reveal actual name on NO_MATCH or NOT_FOUND |
| VOP-S04 | Audit: every query logged with PSP identity, IBAN, result, timestamp |
| VOP-S05 | Name cache: invalidated immediately on account holder name change in A1 |
| VOP-S06 | IBAN validation: reject malformed IBANs before lookup (prevent injection) |

---

## 8. Acceptance Criteria

| ID | Criterion |
|----|----------|
| AC-01 | "Ola Nordmann" vs. "Ola Nordmann" → MATCH (exact) |
| AC-02 | "Ola Nordmann" vs. "Ola Nordmann AS" → CLOSE_MATCH (actual name returned) |
| AC-03 | "Ola Nordmann" vs. "Kari Hansen" → NO_MATCH (actual name NOT returned) |
| AC-04 | "Bjørn Nilsen" vs. "Bjorn Nilsen" → MATCH (diacritics normalized) |
| AC-05 | "Nordmann, Ola" vs. "Ola Nordmann" → MATCH (name order) |
| AC-06 | Nonexistent IBAN → NOT_FOUND |
| AC-07 | 6th query for same IBAN with different names in 1 hour → blocked (anti-enumeration) |
| AC-08 | Responder latency < 100ms in load test (1,000 concurrent queries) |
| AC-09 | NO_MATCH + payer override → fraud score increased by 0.3 in A5 |
