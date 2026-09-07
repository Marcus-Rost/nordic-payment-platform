# Signed-URL Conventions (File Download)

*Companion to `gui/architecture/ARCHITECTURE_DESIGN.md` §8.*
*Authority: this document.*
*Phase 1 finding reference: FI.7.*
*Version: 1.0.*
*Date: 2026-04-24.*

---

## Purpose

Downloadable artefacts (PDF statements, GDPR export ZIPs, audit bundles, receipts) are produced server-side and delivered via time-limited, single-use signed URLs. This document canonicalises the signing algorithm, URL structure, TTL per use, and audit discipline across UC-G04 (statements), UC-G24 (GDPR export), UC-G03 (transaction receipts), UC-G40 (audit bundles).

## URL structure

```
https://api.{env}.jrpay.example/api/v1/<surface>/download/{resourceId}?sig={sig}&exp={exp}&nonce={nonce}&cid={cid}
```

- `resourceId` — the resource identifier. Opaque to the client.
- `sig` — HMAC-SHA256 over the canonical input (below), encoded base64url (no padding).
- `exp` — Unix seconds expiration.
- `nonce` — ULID, single-use.
- `cid` — correlation id for audit linkage (optional, may be a URL-safe client-session id).

## Signing algorithm

```
sig = base64url(HMAC_SHA256(signingKey, canonical))
```

Where `canonical = "\n".join([method, path, sorted_query_minus_sig, exp, nonce, subject])`:

- `method` = `GET` (always).
- `path` = URL path segment (without query).
- `sorted_query_minus_sig` = query string with the `sig` parameter omitted, the remaining params sorted lexicographically, joined with `&`. Percent-encoded per RFC 3986.
- `exp` = the numeric expiration.
- `nonce` = the nonce.
- `subject` = the user id or operator id that the URL is bound to.

Binding the signing string to `subject` prevents URL forwarding: if user A's signed URL lands in user B's inbox, B's bearer token has a different `sub` and the server rejects (see validation).

## Signing key

- Stored in KMS / HSM in production (KeyProvider abstraction per backend existing pattern).
- In portfolio default: per-service symmetric key in Kubernetes Secret, rotated monthly (automated); prior key honoured for the remaining TTL window.
- Key identifier (`kid`) embedded in the nonce format: `<kidByte>-<ulidBytes>` so servers can pick the right key to verify during rotation.

## TTL policy (per use)

| Use | TTL | Rationale |
|-----|-----|-----------|
| Statement PDF download (UC-G04 async) | 10 min | Short — the customer is online when requested |
| Receipt PDF (UC-G03) | 5 min | Very short; receipts are tiny and fetched immediately |
| GDPR export ZIP (UC-G24) | 24 h | Customer may download from email link later |
| Consent-log CSV (UC-G26) | 10 min | Same as statements; user is online |
| Audit bundle (UC-G40) | 1 h | Operator-facing; operators are interactive |
| Admin user-activity CSV (UC-G43) | 1 h | Same |

Each UC cites the TTL in its §10 API operations section. No TTL exceeds 24 h.

## Single-use enforcement

- On issue: server records `hash(nonce)` → `{resourceId, subject, exp}` in a Redis key with TTL = exp.
- On access: server fetches the Redis entry, validates, then DELETEs. Second access returns `410 urn:problem:signed-url-expired`.
- Re-request: customer clicks "Request new link" in the UI; server issues a fresh URL (no throttle beyond standard rate-limit) pointing to the same underlying artefact.

## Server-side validation on access

```
1. Parse exp, nonce, sig from query string.
2. Reject if exp < now.
3. Recompute sig from the canonical string. Reject on mismatch.
4. Fetch Redis entry by hash(nonce). Reject if missing.
5. Verify subject matches the calling token's sub.
6. DELETE Redis entry.
7. Serve the artefact with Content-Disposition: attachment; Cache-Control: private, no-store.
8. Emit audit event download.signed-url.consumed.
```

## Audit events

- `download.signed-url.issued` — on URL issue. Fields: `resourceId, subject, exp, nonceHash, issuedBy, cid`.
- `download.signed-url.consumed` — on successful download. Fields: `resourceId, subject, nonceHash, consumedAt`.
- `download.signed-url.expired` — on 410 return. Fields: `resourceId, subject, nonceHash, reason`.

No audit event stores the URL in full; only `hash(nonce)` (FS.15 log-hygiene alignment).

## Privacy

- Signed URLs never include PII in path or query.
- Server does not log the full URL; only the hash of the nonce.
- Content-Disposition header forces `attachment` (prevents browser from rendering, which in turn prevents PII copy-paste from rendered view without explicit save).

## Rate limits

Per FS.3:
- Issue: 30 / hour / subject.
- Download (on valid URL): unlimited within the single-use-single-access window.

## Security posture

- HMAC-SHA256 with ≥ 256-bit key is sufficient for the TTL-bounded threat model (offline bruteforce within 24 h is infeasible).
- We do not use asymmetric signing (JWT-ES256) because the consumer is our own backend; symmetric HMAC is simpler and faster.
- In the case of key compromise: rotate immediately; emit a key-rotation event; all URLs issued under the compromised key become invalid; clients re-request (which produces a new URL under the new key). Maximum customer-visible disruption: 24 h (the longest TTL).

## Cross-references

- UC-G04 §6 step 6 — 10-min TTL for statements.
- UC-G24 §6 step 7 — 24-h TTL for GDPR export.
- UC-G40 §6.4 — 1-h TTL for audit bundle.
- `PROBLEM_TYPES.md` — `urn:problem:signed-url-expired`.
