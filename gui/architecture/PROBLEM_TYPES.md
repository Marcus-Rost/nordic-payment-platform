# RFC 9457 Problem Types — Canonical Registry

*Companion to `gui/architecture/ARCHITECTURE_DESIGN.md` §8.*
*Authority: this document. Every API error that is not an OAuth2 error (RFC 6749) uses a `type` URI listed here.*
*Phase 1 finding reference: FI.5.*
*Version: 1.0.*
*Date: 2026-04-24.*

---

## Convention

- `type` URI format: `urn:problem:<hyphen-separated-lowercase>`.
- `title` is short human-readable English (one-line).
- Every Problem response body is RFC 9457-compliant: `type`, `title`, `status`, `detail`, `instance`, plus extension fields.
- HTTP status codes: 4xx for client-correctable, 5xx for server-side; the mapping is pinned below.
- A Spectral lint rule checks every error response in every OpenAPI spec file against this registry. Unknown types fail the build.

## Customer-visible mapping discipline

User-facing copy for each type is in `gui/i18n/errors.json` keyed by `<type-slug>`. Copy does not contain PII; copy does not reveal sanctions classification (see FG.4 / UC-G20 anti-tipping-off).

---

## Registry

### OAuth2 (RFC 6749) — NOT in this registry

The `/oauth2/*` endpoints use RFC 6749's error format (`{error, error_description, error_uri}`) — not RFC 9457. Values: `invalid_request`, `invalid_client`, `invalid_grant`, `unauthorized_client`, `unsupported_grant_type`, `invalid_scope`, `access_denied`, `invalid_redirect_uri`, `user_locked`, `user_suspended`, `server_error`, `temporarily_unavailable`. These are RFC-fixed and not listed below.

### Authentication and SCA

| type | HTTP | Title | Emitted from |
|------|------|-------|--------------|
| `urn:problem:sca-required` | 401 | SCA required for this operation | A4, A5, A1, identity |
| `urn:problem:sca-timeout` | 401 | SCA session expired | identity |
| `urn:problem:sca-locked` | 423 | Too many failed SCA attempts | identity |
| `urn:problem:sca-factor-not-enrolled` | 409 | Required SCA factor not enrolled | identity |
| `urn:problem:state-mismatch` | 400 | OAuth2 state parameter invalid | client (not server-emitted) |
| `urn:problem:dynamic-linking-mismatch` | 403 | SCA proof does not bind to operation | A4, A5, A1 |
| `urn:problem:step-up-required` | 401 | This action requires recent SCA | A1, A2, A5 admin endpoints |

### Payment business rules

| type | HTTP | Title |
|------|------|-------|
| `urn:problem:insufficient-funds` | 402 | Account balance too low |
| `urn:problem:account-frozen` | 403 | Account is frozen |
| `urn:problem:account-closed` | 403 | Account is closed |
| `urn:problem:duplicate-suspected` | 409 | Payment may be a duplicate |
| `urn:problem:pending-execution` | 409 | Operation cannot complete while another is pending |
| `urn:problem:instant-not-supported-for-currency` | 422 | Currency not supported on instant rail |
| `urn:problem:too-late-to-cancel` | 409 | Cancellation window closed |
| `urn:problem:vop-timeout` | 504 | Verification of Payee service unavailable |
| `urn:problem:currency-unsupported` | 422 | Currency not supported |

### Customer-visible rejection (FG.4 collapsed set)

These are returned on `GET /api/v1/customer/payments/{id}` to represent a payment's final state; internal reason codes collapse to this narrow enum per `CustomerVisibleRejectionReason` in `SHARED_ENUMS.md`.

| type | HTTP | Title |
|------|------|-------|
| `urn:problem:generic-decline` | — | Payment declined |
| `urn:problem:generic-regulatory` | — | Payment blocked by regulatory requirement |

(These appear inside the payment-state response body, not as HTTP errors. They share the `type` URI convention but are presentation-layer tags, not HTTP failures.)

### Mandate, consent, R2P

| type | HTTP | Title |
|------|------|-------|
| `urn:problem:merchant-not-in-registry` | 404 | Merchant not enrolled for this mandate type |
| `urn:problem:duplicate-iban` | 409 | Beneficiary with this IBAN already exists |
| `urn:problem:duplicate-consent` | 409 | Consent with these parameters already exists |
| `urn:problem:consent-revoked` | 410 | Consent no longer active |
| `urn:problem:r2p-already-acted` | 409 | R2P already accepted/declined/deferred |
| `urn:problem:r2p-cancelled` | 410 | R2P cancelled by merchant |
| `urn:problem:r2p-expired` | 410 | R2P past due date |
| `urn:problem:amendment-not-allowed` | 409 | R2P does not permit amendment |

### Admin and operator endpoints

| type | HTTP | Title |
|------|------|-------|
| `urn:problem:case-already-decided` | 409 | Case already decided by another operator |
| `urn:problem:hit-already-decided` | 409 | Sanctions hit already decided |
| `urn:problem:account-balance-nonzero` | 422 | Cannot close account with non-zero balance |
| `urn:problem:consents-active` | 409 | Active consents prevent close |
| `urn:problem:insufficient-funds-for-return` | 422 | Return would cause negative balance on credit account |
| `urn:problem:already-returned` | 409 | Payment already returned |
| `urn:problem:two-supervisor-required` | 202 | Action requires a second SUPERVISOR approval |
| `urn:problem:cannot-delete-self` | 409 | Operator cannot delete themselves |

### Session and identity

| type | HTTP | Title |
|------|------|-------|
| `urn:problem:session-expired` | 401 | Session idle timeout |
| `urn:problem:session-hard-cap` | 401 | Maximum session duration reached |
| `urn:problem:session-revoked` | 401 | Session revoked by another device login |
| `urn:problem:password-requirements-unmet` | 422 | Password does not meet policy |
| `urn:problem:breached-password` | 422 | Password appears on known-breach list |
| `urn:problem:email-in-use` | 409 | Email already registered |
| `urn:problem:phone-in-use` | 409 | Phone already registered |

### GDPR

| type | HTTP | Title |
|------|------|-------|
| `urn:problem:erasure-precondition-balance` | 422 | Erasure blocked — non-zero balance |
| `urn:problem:erasure-precondition-investigation` | 422 | Erasure blocked — active investigation |
| `urn:problem:erasure-precondition-consents` | 422 | Erasure blocked — active consents |
| `urn:problem:rate-limit-gdpr-request` | 429 | Too many GDPR requests in window |
| `urn:problem:export-not-ready` | 425 | Export still in progress |
| `urn:problem:signed-url-expired` | 410 | Download link expired |

### Input and contract

| type | HTTP | Title |
|------|------|-------|
| `urn:problem:invalid-input` | 400 | Request body fails validation |
| `urn:problem:unknown-field` | 400 | Unknown field in canonical input |
| `urn:problem:invalid-class` | 400 | Invalid dynamic-linking class |
| `urn:problem:invalid-amount` | 400 | Amount format invalid |
| `urn:problem:invalid-currency` | 400 | Currency not ISO 4217 |
| `urn:problem:invalid-iban` | 400 | IBAN failed mod-97 |
| `urn:problem:idempotency-key-missing` | 400 | Idempotency-Key header required |
| `urn:problem:idempotency-key-conflict` | 409 | Idempotency-Key replayed with different body |
| `urn:problem:payload-too-large` | 413 | Request body exceeds limit |
| `urn:problem:unsupported-media-type` | 415 | Content-Type not supported |

### Resource limits

| type | HTTP | Title |
|------|------|-------|
| `urn:problem:rate-limit` | 429 | Rate limit exceeded |
| `urn:problem:concurrent-limit` | 429 | Concurrent operations limit reached |
| `urn:problem:too-many-sessions` | 429 | Too many active sessions |

### System and infrastructure

| type | HTTP | Title |
|------|------|-------|
| `urn:problem:temporarily-unavailable` | 503 | Service temporarily unavailable |
| `urn:problem:dependency-timeout` | 504 | Downstream service timeout |
| `urn:problem:unexpected-error` | 500 | Unexpected error — support reference in `instance` |

---

## Extension fields

Problem responses carry a consistent set of extension fields where relevant:

- `correlationId` (UUIDv4) — the request's trace id; operator-surface references
- `retryAfterSec` (integer) — for 429 / 503
- `supportReference` (string) — for user-facing errors needing support contact
- `details` (object) — additional fields specific to the Problem type; schema pinned per type in OpenAPI spec files

No Problem response includes customer PII (names, balances, IBANs) beyond what the caller already supplied in the request.

## Unknown types

If a client receives a `type` not in this registry, the GUI renders the generic `urn:problem:unexpected-error` copy with the `correlationId` for support. The unknown type is logged to observability for follow-up.

## Registry maintenance

- Every new Problem type is added here *before* any spec file uses it.
- The Spectral lint rule `jrpay-problem-types-registered` validates every OpenAPI `response.content.application/problem+json.examples.type` against this document.
- Retiring a type: mark deprecated here for two minor releases before removal; clients see both during transition.
