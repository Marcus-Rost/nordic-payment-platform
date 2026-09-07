# Certification Readiness Review — Findings

*Date: 2026-04-12*

---

## Summary

17 checks. 10 PASS, 5 PARTIAL, 2 FAIL.

### FAIL

| ID | Finding | Fix |
|----|---------|-----|
| **CR8.1** | SCT Inst max transaction amount (EUR 100,000) not documented or enforced in A2 | Add amount limit to A2 design + INTERFACE_CONTRACTS |
| **CR8.2** | DORA resilience testing plan — chaos engineering not referenced in master architecture | Add reference to chaos engineering checklist (already exists at reviews/crash_resilience/) |

### PARTIAL

| ID | Finding | Fix |
|----|---------|-----|
| CR8.3 | ISO 20022 rejection reason codes not mapped (internal reasons → ExternalStatusReason1Code) | Document mapping in A2 |
| CR8.4 | VoP vocabulary uses NOT_FOUND/ERROR instead of EPC's NOT_POSSIBLE | Add NOT_POSSIBLE mapping note in A6 + INTERFACE_CONTRACTS |
| CR8.5 | ISO 27001 Annex A — controls exist but no traceability table | Document as future work |
| CR8.6 | DORA health check/shutdown not architecturally detailed | Referenced to Starter — acceptable |
