# Server-Sent Events (SSE) — Conventions

*Companion to `gui/architecture/ARCHITECTURE_DESIGN.md` §5.5, §10.1.*
*Authority: this document; every SSE endpoint across the GUI tier conforms to these conventions.*
*Phase 1 finding reference: FI.6.*
*Version: 1.0.*
*Date: 2026-04-24.*

---

## Endpoints (canonical inventory)

| Endpoint | Audience | Purpose |
|----------|----------|---------|
| `GET /api/v1/customer/payments/stream` | customer | Payment-status updates for active payments of the authenticated customer |
| `GET /api/v1/customer/notifications/stream` | customer | Inbox updates and read-state sync across devices |
| `GET /api/v1/customer/session/stream` | customer | Session-revocation events (FS.5 / UC-G53) |
| `GET /api/v1/admin/fraud/cases/stream` | admin | Live updates to case queue / SLA counters |
| `GET /api/v1/admin/sanctions/hits/stream` | admin | Live updates to hits queue |
| `GET /api/v1/admin/payments/investigations/stream` | admin | Live updates on stuck-payment queue |
| `GET /api/v1/admin/reconciliation/runs/{runId}/stream` | admin | Run progress during on-demand reconciliation |
| `GET /api/v1/public/status/stream` | unauthenticated | Status-banner updates (UC-G54) |

Polling endpoints (UC-G42 dashboard 30-s auto-refresh) are NOT SSE; they are standard REST with client-side polling. When a dashboard wants true live updates, it migrates to SSE following these conventions.

## Authentication

### Authenticated streams (all except `/public/status/stream`)

**Stream tokens are required.** `EventSource` cannot send `Authorization:` headers, so:

1. Client calls `POST /api/v1/.../stream-token` with a standard bearer token. Request body specifies the stream endpoint (audience-bound) and optional scope (e.g., a specific paymentId).
2. Server returns `{ "streamToken": "<opaque>", "expiresIn": 300 }`. Token is:
   - ≥ 128 bits of entropy (from `SecureRandom`).
   - Single-use (consumed on stream connect; any reconnect requires a new token).
   - 5-minute TTL.
   - Scope-bound to `{user, audience, specific-resource-if-any}`.
   - Stored server-side in a short-lived Redis-cached table keyed by `hash(streamToken)`.
3. Client opens `GET {endpoint}?token={streamToken}`. Server:
   - Looks up the hashed token, validates scope, deletes the entry (one-shot).
   - Sends the usual SSE response.
4. Server logs only `hash(streamToken)`, never the token value (log hygiene; FS.15).

**Token never appears in browser history beyond the EventSource request**; browsers typically do not log URL query strings to history for subresource requests the way they do for top-level navigations, but the 5-minute TTL + single-use is our real defence.

### Public streams (`/public/status/stream`)

No authentication. Rate-limited by IP (per FS.3: 3 concurrent connections per IP for this endpoint). Messages contain only non-sensitive banner payloads (no customer-specific content ever).

## Message format

Standard SSE framing (RFC 8895 EventSource spec):

```
event: <event-name>
id: <monotonic-integer-or-ulid>
retry: 15000
data: {"…json…"}

```

(Blank line terminates each event. Heartbeat = empty `:` comment line.)

### Event names

Lowercase, dot-separated, mirror the underlying Kafka topic or domain event where there is one:

| Endpoint | Event names |
|----------|-------------|
| payments/stream | `payment.status-updated`, `payment.held`, `payment.settled`, `payment.rejected`, `payment.returned` |
| notifications/stream | `notification.new`, `notification.read-state-changed`, `notification.deleted` |
| session/stream | `session.revoked`, `session.warning` (T-60s idle) |
| admin/fraud/.../stream | `case.created`, `case.assigned`, `case.decided`, `sla.breached` |
| admin/sanctions/.../stream | `hit.created`, `hit.decided` |
| admin/payments/investigations/stream | `investigation.pacs028-response`, `investigation.status-changed` |
| admin/reconciliation/runs/{id}/stream | `run.progress`, `run.completed`, `run.failed` |
| public/status/stream | `banner.published`, `banner.updated`, `banner.resolved` |

### Data payload

JSON; each event's payload is a JSON object whose schema lives in the relevant OpenAPI spec file under `components.schemas.Sse<Event>`. The schema includes:
- `eventId` (ULID; opaque to the client but server can use for de-dup)
- `timestamp` (ISO 8601 UTC)
- content fields specific to the event

No PII beyond what the customer already sees in the authenticated UI. For admin streams, PII is masked identically to the UI's masking rules (UC-G35 §12).

## Heartbeat

- **Interval:** 15 seconds (server sends `: heartbeat\n\n`).
- **Purpose:** keeps intermediate proxies from closing idle connections; gives the client a "connection healthy" signal.
- **Client timeout:** if no event or heartbeat for > 30 seconds, client assumes connection lost and reconnects.

## Reconnection (GUI-RES-05)

Client-side algorithm (shared across web + mobile):

1. On `error` / `close`, begin exponential backoff: 1 s, 2 s, 4 s, 8 s, 15 s cap.
2. Acquire a new stream token (tokens are single-use).
3. Reopen the stream; include `Last-Event-ID: <lastSeenId>` header if supported by the platform (web EventSource sends automatically; mobile implementations must set manually).
4. On reconnect, GUI performs **reconciliation**: fetches the relevant REST resource once (e.g., `GET /payments/{id}` for payments, `GET /notifications?since=<lastTs>` for notifications) to catch anything missed while disconnected.
5. If connection still failing after 5 backoff cycles (1+2+4+8+15 = 30 s total), surface "Connection lost" banner; resume attempts every 30 s while UI is foregrounded.

Server-side: supports `Last-Event-ID` header by replaying events from the stored event log (Redis stream or Kafka consumer from the last event ID). Replay window: 5 minutes. Beyond that, the client must reconcile via REST without replay guarantees.

## Server-side close semantics

- Idle client (no HTTP activity + no heartbeat-ack) closed after 10 minutes.
- Server restart: TERM handler finishes in-flight frames, emits `event: stream.closing\ndata: {}\n\n`, allows client to reconnect elsewhere.
- Per-user concurrent limit: 3 streams per customer per audience (admin audience different quota). Exceeded → `429 urn:problem:concurrent-limit` on stream-token request (not on the stream itself; the token is the throttle point).
- Graceful close on logout: server emits `event: session.revoked` then closes.

## Observability

- Every stream establishes a trace span propagated via `traceparent` header on the initial request.
- Events carry `X-Span-Id` extension header on the SSE frame so cross-event traces can be reconstructed.
- Metrics: `sse.streams.active{audience}`, `sse.events.sent{event}`, `sse.reconnects{audience}`, `sse.missed-heartbeats{audience}`.

## Security notes

- No PII in URL (token is opaque; not logged).
- TLS 1.3 mandatory.
- CSP `connect-src` on web GUIs must include the backend origin (pinned in CSP spec; see FS.2).
- CORS preflight requires the backend to send `Access-Control-Allow-Credentials: false` (we don't need cookies for the stream; token is the auth); `Access-Control-Allow-Headers: Last-Event-ID, X-Request-ID`.
- Mobile cert pinning (GUI-SEC-05) applies — same host as REST.

## Testing

Per `gui_requirements.md` §2.8 the test pyramid covers SSE at layers 2 (mock EventSource) and 4 (real SSE against live docker-compose). Cross-platform consistency layer verifies the same event payloads reach web/iOS/Android listeners and produce equivalent UI state.

Specific scenarios each UC-adjacent SSE endpoint must cover:
- happy path (events flow)
- drop-and-reconnect (exponential backoff observed)
- replay via `Last-Event-ID` on reconnect
- reconciliation fetch on reconnect
- 5-backoff-cycle failure (UI banner)
- server-side graceful close
