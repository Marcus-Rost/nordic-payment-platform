# UC-G80 — Browse Notification Inbox

*Owner: Frontend / Notifications*
*Status: DRAFT*
*Implementation status: Admin Web n/a | Client Web ☐ | Client iOS ☐ | Client Android ☐*
*Last updated: 2026-04-24*

---

## 1. Purpose

The customer opens the in-app inbox and browses all notifications they have received — payment-status updates, fraud-hold, sanctions-block, R2P arrivals, consent expiry, GDPR status, account-freeze, profile changes, system-maintenance. Filter by category, search by counterparty/text, mark read/unread, delete, open to deep-linked target. Read-state synchronises across devices via SSE (FI.6 / SSE_CONVENTIONS.md). This is the canonical UC for CLI-F80 (notification inbox) — previously implicit across UC-G19, UC-G20, UC-G21.

Phase 1 finding reference: FC.3, FC.5, FI.1.

## 2. Actors

- **Primary:** Bank customer.
- **Secondary:** Notifications service (REST + SSE).

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | n/a | Admin users use a different surface (dashboard widgets per UC-G42) |
| Client Web | yes | Full inbox with filters, bulk-select |
| Client iOS | yes | Tab / drawer in the navigation; supports pull-to-refresh |
| Client Android | yes | Same |

## 4. Preconditions

- Customer is authenticated.
- Customer has the implicit `notifications:read` right (granted at login for account holders).

## 5. Postconditions

- Viewed list reflects the current server state.
- Marking read/deleted synchronises to all other devices via SSE within seconds.
- Audit events `notification.read`, `notification.deleted` with hashed user id (low-signal audit).
- Badge count on app icon / tab icon updates.

## 6. Main flow (happy path)

### 6.1 Open inbox

1. Customer navigates to **Varsler / Notifications**.
2. GUI fetches `GET /api/v1/customer/notifications?page=0&size=25&filter=all`. Response per row:
   ```json
   {
     "id": "...",
     "category": "PAYMENT_STATUS|FRAUD_HOLD|SANCTIONS_BLOCK|R2P_INCOMING|CONSENT_EVENTS|...",
     "titleKey": "...",
     "bodySummaryKey": "...",
     "params": {...},
     "createdAt": "...",
     "readAt": "..." | null,
     "deepLink": "jrpay://payment/{id}" | null,
     "actionHint": "view|act-now|none",
     "priority": "info|warn|critical"
   }
   ```
3. GUI renders a list. Unread rows have a visual indicator (dot + bold title + colour-independent icon).
4. Sort default: newest first. Alternative sort: priority (critical → warn → info).
5. Unread counter in the header plus badge on the inbox icon (mobile).

### 6.2 Filters

- Category multi-select chip row: All / Payments / Fraud / Sanctions-block / R2P / Consents / GDPR / Account / System / Profile / Marketing.
- Read-state: All / Unread only / Read only.
- Date range: today / 7 days / 30 days / custom.
- Free-text search: counterparty substring, amount, reference substring (client-side filter of the fetched set; server-side full-text search if list > 100 entries).

### 6.3 Per-row actions (tap target + kebab menu)

1. **Tap the row** → navigate to deep-linked target (if `deepLink` present). Row auto-marks-read via `PATCH /api/v1/customer/notifications/{id}` with `{readAt: now}`.
2. **Mark read/unread** (kebab menu, bulk) → `PATCH` with `{readAt: now | null}`.
3. **Delete** (kebab menu, bulk) → `PATCH` with `{deletedAt: now}` (soft delete). Deleted items hidden by default; retrievable via filter.
4. **Copy reference** (kebab menu) → copies `supportReference` (if present) to clipboard; toast confirms.
5. **Open in full** (mobile) → opens a full-screen reader view rendering the notification's full body (vs. the list's short summary).

### 6.4 Bulk selection

1. Long-press a row (mobile) / checkbox (web) enters selection mode.
2. Footer bar shows: N selected + actions (Mark read, Delete, Cancel).
3. Apply to all selected with a single PATCH per row (no batch endpoint in MVP — server-side batching could be added if volumes grow).

### 6.5 SSE sync

1. On inbox mount, GUI opens SSE to `GET /api/v1/customer/notifications/stream` (stream-token per SSE_CONVENTIONS.md).
2. Events received:
   - `notification.new` → prepend to list (animated) + update unread counter + ring a tone (respects mute pref).
   - `notification.read-state-changed` → update the row's `readAt` (from another device).
   - `notification.deleted` → remove the row.
3. On disconnect, GUI reconciles via `GET /api/v1/customer/notifications?modifiedSince=<ts>` on reconnect.

### 6.6 Pagination

1. "Load more" button (web) / infinite scroll (mobile) fetches `page=n+1`.
2. When the customer has no more items, footer shows "Ingen flere varsler" / "No more notifications".

## 7. Alternative flows

### AF-01 — Empty inbox (new customer)

1. Empty state: illustration + "Dine varsler vises her" / "Your notifications will show here."

### AF-02 — Deep link target no longer exists (race)

1. Row's target 404s on navigation. UI shows "Denne hendelsen er ikke lenger tilgjengelig." and offers to mark the row read + return to inbox.

### AF-03 — Privacy-sensitive notifications (sanctions-block, fraud-hold)

1. Row rendering does NOT differ from a generic REJECTED payment at the inbox level (FG.4 anti-tipping-off). Icon and title are identical to other REJECTED-like events.

### AF-04 — Notifications list is very long (> 10k)

1. Server-side paging caps at 10k results; UI shows "Bruk filter for å se eldre varsler" / "Use filters to see older notifications".

### AF-05 — Customer is offline (mobile, UC-G28)

1. Inbox renders from cache with "Offline — last updated <time>" banner.
2. Actions (mark read/delete) are blocked with tooltip "Trenger nett".

### AF-06 — Deleted item restoration

1. Filter "Deleted (last 30 days)" shows soft-deleted items with a "Restore" action. Beyond 30 days they're purged permanently.

### AF-07 — Locked notifications (SECURITY_EVENTS)

1. Per UC-G21, security-events notifications are non-deletable. Delete action greys out with a tooltip "Sikkerhetsvarsler kan ikke slettes — bevar for sikkerhet."

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| List fetch 5xx | response | retryable; show cached if mobile |
| SSE drop | client | reconnect per SSE conventions |
| PATCH conflict (deleted elsewhere while editing) | 404 | refresh list silently |
| Stream-token expired | 401 on connect | re-request token + reconnect |

## 9. Related requirements

- `gui_requirements.md` — `CLI-F80`, `CLI-F81` (preferences; UC-G21 covers)
- `GUI-RES-05` (SSE resilience)
- `GUI-I18N-02..04` (all copy via catalogue)
- `GUI-A11Y-01..07` (full keyboard, screen-reader friendly)
- FC.3, FC.5, FI.1 (Phase 1 fixes)

## 10. API operations involved

- `GET   /api/v1/customer/notifications`
- `PATCH /api/v1/customer/notifications/{id}` — read-state / soft delete
- `GET   /api/v1/customer/notifications/stream` (SSE)
- `POST  /api/v1/customer/notifications/stream-token`

All in `customer-notifications.yaml`.

## 11. Tests realising this use case

| Layer | Client Web | iOS | Android |
|-------|-----------|-----|---------|
| Unit | `.../inbox/__tests__/row.test.tsx`, `filters.test.ts`, `bulk-select.test.ts` | `.../Tests/InboxRowTests.swift` | `.../inbox/InboxRowTest.kt` |
| Component | `.../inbox/__tests__/inbox-screen.test.tsx` | `.../Tests/InboxViewTests.swift` | `.../InboxScreenTest.kt` |
| Contract | `.../inbox.pact.test.ts` | `.../InboxPactTests.swift` | `.../InboxPactTest.kt` |
| E2E | `client-web/e2e/inbox-browse.spec.ts`, `inbox-sse-read-sync.spec.ts` | `.../UITests/InboxUITests.swift` | `.../androidTest/.../InboxE2ETest.kt` |
| Cross-platform consistency | `cross-platform-tests/scenarios/inbox_mark_read_sync.json`, `inbox_delete_bulk.json`, `inbox_filter_by_category.json` | (same) | (same) |

## 12. UX notes

- Icon per category is enumerated (see FA.3 icon set): payment (arrow-right), fraud-hold (clock), sanctions-block (neutral flag — same as fraud for tipping-off discipline), R2P (envelope), consent (key), GDPR (shield), account (lock), system (megaphone), profile (person).
- Unread dot is colour-independent (position: left of title; screen reader announces "unread").
- "Mark all read" affordance is available from the filter bar (with confirmation).
- Row tap area ≥ 44 pt / 48 dp with internal chevron indicating drill-down.
- Screen-reader friendly: each row's `aria-label` combines title + "read" / "unread" + relative timestamp + category.
- Deep links to UC-G19/G20 (notifications) land directly on this screen with the specific row in focus.

## 13. Open questions

- Push-to-refresh on mobile vs. auto-refresh on focus: both; document both in the component.
- Should "marketing" category be present in MVP? Already listed off-by-default; no UI gap if no marketing events ever arrive.
