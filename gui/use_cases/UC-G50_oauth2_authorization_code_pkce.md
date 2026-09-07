# UC-G50 — OAuth2 Authorization Code Flow with PKCE

*Owner: Frontend / Identity*
*Status: DRAFT*
*Implementation status: Admin Web ☐ | Client Web ☐ | Client iOS ☐ | Client Android ☐*
*Last updated: 2026-04-23*

---

## 1. Purpose

Canonical specification of the OAuth2 Authorization Code + PKCE flow used by all four GUIs and by TPPs. This is the shared plumbing that UC-G01 and UC-G30 invoke; UC-G17 and UC-G72 layer on top. Writing it once here avoids divergent implementations.

## 2. Actors

- **Primary:** Any GUI / TPP acting as OAuth2 client
- **Secondary:** Identity service (Authorization Server), resource servers (A1..A6)

## 3. Audience and platforms

| Platform | In scope | Notes |
|----------|----------|-------|
| Admin Web | yes | |
| Client Web | yes | |
| Client iOS | yes | AppAuth-iOS in `ASWebAuthenticationSession` |
| Client Android | yes | AppAuth-Android in Custom Tab |

## 4. Preconditions

- Identity service has issued a `client_id` for the consuming app (pre-registered; customer GUIs are fixed public clients; TPPs are eIDAS-verified public clients with redirect-URI allowlists).
- User-agent supports redirect-based flows.

## 5. Postconditions

- Client holds a valid access token + refresh token bound appropriately to the platform (cookie on web, secure storage on mobile).
- All OAuth2 security invariants hold: PKCE verifier secret, state non-reuse, nonce verification (for OIDC), exact redirect-URI match.

## 6. Main flow (happy path)

1. Client initiates:
   1. Generates cryptographically random `code_verifier` (43–128 chars, RFC 7636 §4.1).
   2. Computes `code_challenge = BASE64URL(SHA-256(code_verifier))`.
   3. Generates `state` (CSRF, ≥ 128 bit entropy) and `nonce` (replay, ≥ 128 bit) — both stored in client-side session (web: `sessionStorage`; mobile: secure storage pending redirect).
   4. Redirects (web) or opens system browser (mobile) to `/oauth2/authorize` with:
      ```
      response_type=code
      client_id=<client>
      redirect_uri=<exact-match>
      scope=<space-separated>
      state=<state>
      nonce=<nonce>
      code_challenge=<challenge>
      code_challenge_method=S256
      acr_values=<loa3|loa3-admin>
      (optional) prompt=login|consent|select_account
      ```
2. Identity service:
   1. Validates `client_id` and `redirect_uri` exact match against registration.
   2. Requires an authenticated session for the user audience (customer vs. admin). If none, shows login form + runs SCA.
   3. If the client's scope set requires consent (e.g., TPP — UC-G17), shows the consent screen.
   4. Issues an authorization code (single-use, 10-min TTL) bound to `{client_id, redirect_uri, code_challenge, scope, user}`.
   5. 302-redirects user-agent to `redirect_uri?code=<code>&state=<state>`.
3. Client:
   1. Verifies `state` matches stored state (rejects otherwise with severe-error log).
   2. Exchanges code: `POST /oauth2/token`
      ```
      grant_type=authorization_code
      code=<code>
      client_id=<client>
      redirect_uri=<exact-match>
      code_verifier=<code_verifier>
      ```
   3. Identity service verifies: code valid + not used + binding matches + `code_verifier` hashes to `code_challenge`.
   4. Returns:
      ```json
      {
        "access_token": "<jwt-rs256>",
        "token_type": "Bearer",
        "expires_in": 900,
        "refresh_token": "<opaque>",
        "id_token": "<jwt>", // if openid scope requested
        "scope": "<granted-scopes>"
      }
      ```
   5. Refresh token delivery:
      - **Web:** as a `Set-Cookie` with `HttpOnly; Secure; SameSite=Strict; Path=/oauth2/` — the token never lives in JS.
      - **Mobile:** in the JSON body; client writes to secure storage immediately.
   6. Client verifies `id_token.nonce` matches stored nonce (OIDC).
   7. Discards `state`, `nonce`, `code_verifier` from memory.
4. Subsequent API calls: `Authorization: Bearer <access_token>`.
5. On 401 with `Token expired`, client runs the refresh-token flow (UC-G01 AF-01 / UC-G52).

## 7. Alternative flows

### AF-01 — `state` mismatch on callback

1. Severe — possible CSRF. Client discards draft state, shows `auth.state-mismatch`, logs to observability, does NOT retry.

### AF-02 — `nonce` mismatch in id_token

1. Severe — possible token confusion. Same handling as AF-01.

### AF-03 — `redirect_uri` not exactly matching registration

1. Identity service refuses the authorize request with a parameter error; renders an error page at the bank (so no redirect ever leaves the bank domain for an attacker-controlled URI).

### AF-04 — PKCE verifier missing or wrong on token exchange

1. Token endpoint returns 400 `invalid_grant`. Client treats as unrecoverable; restarts flow.

### AF-05 — Code reuse

1. Identity service marks the code invalid on first successful use; subsequent uses return `invalid_grant` and also invalidate the issued access token + refresh token (defensive — attacker may have obtained the first use).

### AF-06 — Multiple concurrent OAuth flows from the same client

1. Each has its own `state` / `nonce` / `code_verifier` — independent.

### AF-07 — Customer cancels on the consent screen

1. Identity service redirects with `error=access_denied`. Client surfaces the cancellation cleanly (no error toast; UI just returns to pre-login state).

### AF-08 — Account uses `prompt=login` to force re-auth

1. Identity service ignores existing session and shows login form.

## 8. Failure modes

| Failure | Detection | GUI behaviour |
|---------|-----------|---------------|
| Network failure during token exchange | `fetch`/`URLSession` error | Retryable within 30s with preserved `code_verifier`; past 30s, restart (code expired) |
| Clock skew causing `iat`/`exp` rejection | Token parse error | Show clock-skew guidance |
| JWK rotation mid-session (old kid invalid) | Access token verification fails on resource server | Client refetches JWKS and retries once |
| Identity service 5xx | Response | Maintenance banner + retry |

## 9. Related requirements

- `gui_requirements.md` — `GUI-SEC-01`, `GUI-SEC-02`, `GUI-SEC-04`, OAuth 2.1 BCP
- `system_requirements.md` — IDS-F01..F13
- RFC 7636 (PKCE), RFC 6749 (OAuth2), RFC 7009 (revocation), OpenID Connect Core 1.0

## 10. API operations involved

- `GET  /oauth2/authorize`
- `POST /oauth2/token`
- `POST /oauth2/revoke` (UC-G53)
- `POST /oauth2/introspect` (resource-server-side; not GUI-side)
- `GET  /.well-known/openid-configuration`
- `GET  /.well-known/jwks.json`

## 11. Tests realising this use case

| Layer | Admin Web | Client Web | iOS | Android |
|-------|-----------|-----------|-----|---------|
| Unit | `admin-web/.../auth/__tests__/oauth-state.test.ts`, `pkce.test.ts` | `client-web/.../auth/__tests__/oauth-state.test.ts` | `.../Tests/OAuthFlowTests.swift` | `.../auth/OAuthFlowTest.kt` |
| Component | `admin-web/.../auth/__tests__/callback.test.tsx` | `client-web/.../auth/__tests__/callback.test.tsx` | `.../Tests/OAuthCallbackViewTests.swift` | `.../OAuthCallbackScreenTest.kt` |
| Contract (Pact) | `admin-web/.../oauth.pact.test.ts` | `client-web/.../oauth.pact.test.ts` | `.../OAuthPactTests.swift` | `.../OAuthPactTest.kt` |
| E2E | `admin-web/e2e/oauth-happy.spec.ts` | `client-web/e2e/oauth-happy.spec.ts`, `oauth-state-mismatch.spec.ts`, `oauth-code-reuse.spec.ts` | `.../UITests/OAuthUITests.swift` | `.../androidTest/.../OAuthE2ETest.kt` |
| Cross-platform consistency | n/a (pure protocol) | n/a | n/a | n/a |

## 12. UX notes

- The system browser (mobile) must show the identity-service origin clearly; we never embed the OAuth flow in a WebView.
- State-mismatch errors are a rare but severe event; UI copy is calm but unambiguous; log to observability with a dedicated event type for forensic review.
- On mobile, the redirect back to the app uses a Universal Link / App Link (custom URI schemes are avoided — they're hijackable).

## 13. Open questions

- Whether to add DPoP (RFC 9449) for refresh-token binding. *Deferred from MVP per IDS-F03; phase-2 enhancement.*
- Whether to support OAuth2 Pushed Authorization Requests (RFC 9126) for TPPs. *Defer; BerlinGroup does not yet require it.*
