# UC-01: OAuth2 Consent Flow with SCA (Account Access)

*The core PSD2 flow: TPP requests consent, account holder authenticates and approves*

---

## Primary Actor
**Third-Party Provider (TPP)** — a fintech app requesting access to a consumer's bank data

## Preconditions
- TPP is registered (client_id, client_secret, redirect URIs, client certificate)
- Account holder has active accounts in A1

## Trigger
TPP initiates an OAuth2 authorization request

---

## Main Success Scenario

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | TPP | `GET /api/v1/authorize?response_type=code&client_id={tpp_id}&scope=accounts:read+transactions:read&redirect_uri={callback}&state={random}&code_challenge={S256hash}&code_challenge_method=S256` | A4 validates: TPP registered, redirect_uri matches, PKCE present. Stores auth request. Redirects to consent page |
| 2 | Account Holder | Enters username/password on consent page | A4 validates knowledge factor (first SCA factor) |
| 3 | Account Holder | Enters TOTP code from authenticator app | A4 validates possession factor (second SCA factor). **SCA complete** |
| 4 | Account Holder | Reviews: "**FinApp AS** requests access to your accounts and transactions for 90 days." Selects accounts to share. Clicks **Approve** | A4 creates consent record. Generates authorization code (single-use, 5-min expiry) |
| 5 | A4 | Redirects: `{callback}?code={auth_code}&state={random}` | TPP receives authorization code |
| 6 | TPP | `POST /api/v1/token` — `grant_type=authorization_code`, `code`, `client_id`, `client_secret`, `code_verifier` (PKCE) | A4 validates: code not expired, not reused, PKCE verifier matches challenge, client credentials valid |
| 7 | A4 | Issues tokens | Access token (JWT RS256, 15-min expiry) + refresh token (opaque, hashed in DB). Returns `{access_token, token_type: "Bearer", expires_in: 900, refresh_token, scope}` |
| 8 | TPP | `GET /api/v1/accounts` with `Authorization: Bearer {access_token}` | A4: validate JWT (signature, expiry, scope). Check consent is ACTIVE and covers `accounts:read`. Query A1 for account list. Return accounts for this holder |
| 9 | TPP | `GET /api/v1/accounts/{id}/transactions?dateFrom=2026-01-01` | A4: validate token + scope `transactions:read`. Query A1. Return paginated transactions. JWE-encrypt IBANs if TPP has registered public key |

---

## Alternative Flows

### AF-01: Account Holder Denies Consent
| Step | Action | Response |
|------|--------|---------|
| 4a | Account holder clicks **Deny** | Redirect to `{callback}?error=access_denied&state={random}`. No consent created |

### AF-02: SCA Failure
| Step | Action | Response |
|------|--------|---------|
| 3a | Wrong TOTP code (attempt 1 of 3) | "Incorrect code. 2 attempts remaining." |
| 3b | Wrong TOTP code (attempt 3 of 3) | SCA locked for 5 minutes. Redirect: `{callback}?error=sca_failed` |

### AF-03: Token Refresh
| Step | Action | Response |
|------|--------|---------|
| — | Access token expired after 15 min. TPP calls `POST /api/v1/token` with `grant_type=refresh_token` | A4 validates: refresh token hash matches DB, not used before, consent still active. Issues NEW access token + NEW refresh token. Old refresh token marked as `used=true` |

### AF-04: Refresh Token Reuse (Theft Detection)
| Step | Action | Response |
|------|--------|---------|
| — | Attacker replays an already-used refresh token | A4: token found in DB but `used=true`. **THEFT DETECTED.** Revoke entire token family (all refresh tokens with same `family_id`). Return `error=invalid_grant`. TPP and account holder must re-authenticate |

### AF-05: Consent Expired
| Step | Action | Response |
|------|--------|---------|
| — | 90 days pass since consent creation | Consent status → EXPIRED. Next API call returns 403: `{error: "consent_expired", message: "Please re-authenticate"}` |

### AF-06: Account Holder Revokes Consent
| Step | Action | Response |
|------|--------|---------|
| — | Account holder: `DELETE /api/v1/consents/{id}` | Consent → REVOKED. All refresh tokens invalidated. Next API call by TPP returns 403 |

### AF-07: PKCE Missing
| Step | Action | Response |
|------|--------|---------|
| 1a | TPP omits `code_challenge` parameter | A4 returns 400: `{error: "invalid_request", description: "PKCE is required"}` |

---

## Security Deep Dive: Why Each Mechanism Matters

| Mechanism | Attack it Prevents |
|-----------|-------------------|
| **PKCE** | Authorization code interception — even if attacker captures the code, they can't exchange it without the verifier |
| **State parameter** | CSRF — prevents attacker from initiating consent flow on behalf of victim |
| **Refresh token rotation** | Token theft — even if attacker captures a refresh token, the legitimate client's next refresh invalidates it (or detects reuse) |
| **Family-based revocation** | Sophisticated token theft — if the attacker uses the stolen token first, the legitimate client's attempt detects reuse and revokes everything |
| **mTLS** | TPP impersonation — only the registered TPP with the correct client certificate can make API calls |
| **JWE** | Response interception — even if HTTPS is terminated at a proxy, payload-level encryption protects account numbers |
| **Dynamic linking** | SCA phishing — the TOTP challenge shows the specific amount and creditor, so the user sees exactly what they're authorizing |
| **Consent scoping** | Over-authorization — TPP only gets access to specifically granted scopes and accounts |
