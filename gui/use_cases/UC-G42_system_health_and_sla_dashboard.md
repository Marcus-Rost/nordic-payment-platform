# UC-G42 — System Health and SLA Dashboard

*Owner: Frontend / Admin / Operations*
*Status: DRAFT*
*Implementation status: Admin Web ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

OPERATIONS operators see a live dashboard of platform health: per-service status, error rate, p50/p95/p99 latency, uptime; queue-depth per Kafka topic; SLA tracking against the key contractual latencies (instant-payment p95 ≤ 5s, fraud-alert resolution ≤ 1h, sanctions-alert ≤ 15m); DLQ viewer with per-topic dead-letter depth and sample messages (PII-redacted). SUPERVISORs can post/schedule maintenance/incident banners that appear on customer GUIs.

## 2. Actors

- **Primary:** OPERATIONS, SUPERVISOR, ADMIN
- **Secondary:** Prometheus (metrics source), Kafka admin APIs (for queue depth), A2/A5 (SLA counters)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes | |

## 4. Preconditions

- Operator has `system:read` scope; SUPERVISOR for status-banner management.

## 5. Postconditions

- Observed state rendered; no persistent side-effects other than audit `admin.dashboard.viewed` (low-priority audit category).
- If banner posted: status-banner service has a new entry.

## 6. Main flow (happy path)

### 6.1 Service health

1. Operator navigates to **Operations** → **Systemhelse / System health**.
2. GUI fetches `GET /api/v1/admin/system/health` (a thin proxy that aggregates from Prometheus).
3. Grid of service tiles (A1..A7, notifications, audit-query): name, state (UP / DEGRADED / DOWN), 1-minute error rate, p95 latency.
4. Click a tile → drill-through to the service's full metrics page embedded from Grafana (iframe with strict `frame-ancestors`).

### 6.2 Queue depth

1. Tile group "Købredde / Queue depth" shows key topics: `payment.instant-instructions`, `payment.status-updates`, `fraud.decision-request`, `audit.events`, etc.
2. Visual: number + 24h sparkline. Alert badge if over threshold (topic-specific thresholds loaded from config).

### 6.3 SLA dashboard (ADM-F82)

1. Tile "SLA-er": three key SLAs:
   - Instant payment p95 latency (target ≤ 5s; EU IPR Art. 5b).
   - Fraud-alert resolution age (target ≤ 1h).
   - Sanctions-alert resolution age (target ≤ 15min).
2. Each shows current value, target, and a sparkline of last 24h.
3. Drill-through to the offending items (e.g., click the sanctions SLA tile → list of currently overdue sanctions hits, deep-link to UC-G34).

### 6.4 DLQ viewer (ADM-F83)

1. Tab "DLQ": per-topic dead-letter depth.
2. Expanding a topic shows a sample of the latest dead-lettered messages (with PII redacted by a backend pass — the GUI never holds raw payload).
3. Actions (SUPERVISOR + step-up SCA): retry single message, retry all, purge selected.

### 6.5 Status banner management (ADM-F84)

1. Separate tab "Statusbanner".
2. Current banner (if any) shown with severity (INFO / WARNING / CRITICAL), scheduled time, duration, audiences (CUSTOMER / ADMIN / BOTH).
3. SUPERVISOR can create/edit/delete banners; each change requires step-up SCA; audited.

## 7. Alternative flows

### AF-01 — Prometheus unreachable

1. Tiles show "metrikker utilgjengelig"; last-known state cached for 5 min.

### AF-02 — Kafka admin unreachable

1. Queue-depth tiles show stale data with timestamp.

### AF-03 — Grafana iframe blocked (CSP)

1. Fallback is a link "Åpne i Grafana" that opens in a new tab.

### AF-04 — DLQ retry fails

1. Each message has a failure counter; after 3 retries, the option to purge becomes available.

### AF-05 — Banner conflict (two SUPERVISORs editing simultaneously)

1. Optimistic concurrency control with a version token; second save gets 409 and must refresh.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Health-aggregator 5xx | Response | Show stale values with timestamp |
| Banner POST 5xx | Response | Retryable |
| SCA fails | UC-G51 | No state change |

## 9. Related requirements

- `gui_requirements.md` — `ADM-F80..F84`, `GUI-OPS-02..03`
- `system_requirements.md` — OPS-01..OPS-05, DORA Art. 11, 12
- UC-G54 (customer-side banner display)

## 10. API operations involved

- `GET  /api/v1/admin/system/health`
- `GET  /api/v1/admin/system/queues`
- `GET  /api/v1/admin/system/sla`
- `GET  /api/v1/admin/system/dlq` + `POST /retry`, `DELETE /purge`
- `GET/POST/PATCH/DELETE /api/v1/admin/status` (banner)

## 11. Tests realising this use case

| Layer | Admin Web |
|-------|-----------|
| Unit | `admin-web/.../system/__tests__/tiles.test.tsx`, `banner-form.test.tsx` |
| Component | `admin-web/.../system/__tests__/dashboard.test.tsx` |
| Contract | `admin-web/.../admin-system.pact.test.ts` |
| E2E | `admin-web/e2e/system-health.spec.ts`, `system-dlq-retry.spec.ts`, `status-banner-publish.spec.ts`, `status-banner-customer-visible.spec.ts` (verifies banner reaches UC-G54 on client web) |

## 12. UX notes

- The grid uses a consistent traffic-light convention (`GUI-A11Y-03`): green = up, amber = degraded, red = down, grey = unknown.
- Auto-refresh interval is 30s; paused when the tab is backgrounded (reduces noise + API load).
- Banner editor previews the customer-GUI rendering so SUPERVISOR sees what the customer will see.

## 13. Open questions

- Whether to expose memory / CPU metrics at this level or defer to Grafana. *Decision: the dashboard shows rate + latency only; resource metrics live in Grafana drill-through.*
