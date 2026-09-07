# Extension: Real-time Sanctions Screening

*Addresses GAP 3 from gap_analysis.md*
*Regulatory basis: EU AML Package (2024), Funds Transfer Regulation, EU Instant Payment Regulation*
*Mandatory for all payment processing — cannot be skipped or deferred*

---

## Why It Matters

Unlike fraud detection (which can auto-pass on timeout), sanctions screening is a **legal obligation**. Processing a payment involving a sanctioned entity is a criminal offense. The challenge: do it in <50ms without delaying instant payments.

---

## Requirements

| ID | Requirement | Priority |
|----|------------|----------|
| SAN-F01 | Screen originator AND beneficiary name + IBAN + country against EU Consolidated Sanctions List | Must |
| SAN-F02 | Screen against UN Security Council sanctions list | Must |
| SAN-F03 | Screen against configurable national lists (Norwegian, Dutch, etc.) | Should |
| SAN-F04 | Fuzzy name matching: handle transliterations (Cyrillic→Latin, Arabic→Latin), aliases, partial names, name order variations | Must |
| SAN-F05 | Screening latency: **< 50ms** per transaction for the fast-path check | Must |
| SAN-F06 | List updates ingested within **1 hour** of publication | Must |
| SAN-F07 | Potential match (hit) → payment HELD, alert created for compliance analyst | Must |
| SAN-F08 | Confirmed match → payment BLOCKED, funds frozen, report to FIU (Financial Intelligence Unit) | Must |
| SAN-F09 | False positive → payment released, analyst decision logged | Must |
| SAN-F10 | Retrospective screening: when lists update, re-screen all existing accounts and pending payments | Should |

## Architecture

```
                                  +-------------------+
                                  | Sanctions List    |
                                  | Ingestion Service |
                                  | (async update)    |
                                  +--------+----------+
                                           |
                                    update list
                                           |
                                           v
Payment ──→ [Sanctions Screener] ──→ CLEAR / HIT / ERROR
              │
              ├── In-memory index (Aho-Corasick + fuzzy)
              ├── Bloom filter (fast negative check)
              └── Fuzzy matcher (Jaro-Winkler, phonetic)
```

### Fast-path (sub-50ms):
1. **Bloom filter** — quick negative check. If name is definitely NOT in any list → CLEAR (< 1ms)
2. If bloom filter says "maybe" → **Aho-Corasick multi-pattern match** against exact names → HIT or CLEAR (< 5ms)
3. If near-miss → **fuzzy match** (Jaro-Winkler + Soundex/Metaphone) → HIT or CLEAR (< 30ms)

### Slow-path (analyst review, async):
4. HIT → payment HELD → compliance alert created
5. Analyst reviews → CONFIRM (block + report) or DISMISS (release payment)

---

## Technologies

| Component | Technology | Rationale |
|-----------|-----------|-----------|
| Bloom filter | **Google Guava BloomFilter** or custom | O(1) negative check, sub-millisecond |
| Multi-pattern matching | **Aho-Corasick** (e.g., `org.ahocorasick` library) | Efficient simultaneous search against all sanctioned names |
| Fuzzy matching | **Apache Commons Text** (Jaro-Winkler, Soundex, Metaphone) | Handle transliterations and spelling variations |
| In-memory store | **Caffeine cache** or dedicated ConcurrentHashMap | Entire sanctions list in memory (~15,000 entries for EU list) |
| List ingestion | **Scheduled Spring task** polling EU/UN list endpoints | XML/JSON parsing, normalize to internal format |
| Alert management | Integrated with A5 Fraud Detection case management API | Reuse existing alert review workflow |
