# UC-02: PSD2 Open Banking Account Access (AISP Flow)

*Sub-systems involved: A4 (PSD2 API Gateway), A1 (Account Ledger)*
*Mirrors: DNB developer.dnb.no, SpareBank 1 developer.sparebank1.no (BerlinGroup NextGenPSD2)*

---

## Primary Actor
**Third-Party Provider (TPP)** — a fintech app that wants to read a consumer's bank account data

## Preconditions
- TPP has registered with the PSD2 API Gateway (received client_id, client_secret)
- Account holder has an active account in the Account Ledger (A1)
- Account holder has not previously granted consent to this TPP (or consent has expired)

## Trigger
TPP initiates an OAuth2 authorization request to access a consumer's account data

---

## Main Success Scenario

| Step | Actor | Action | System Response |
|------|-------|--------|----------------|
| 1 | TPP | GET `/api/v1/authorize?response_type=code&client_id={tpp_id}&scope=accounts:read+transactions:read&redirect_uri={tpp_callback}&state={random}` | A4 validates TPP registration, stores authorization request, redirects account holder to consent page |
| 2 | Account Holder | Enters credentials on consent page | A4 authenticates account holder (knowledge factor: PIN/password) |
| 3 | Account Holder | Enters TOTP code (sent via simulated SMS or authenticator) | A4 validates possession factor → SCA complete |
| 4 | Account Holder | Reviews consent: "App X requests access to your accounts and transaction history for 90 days" → clicks Approve | A4 creates consent record (TPP, scopes, account holder, expiry=now+90d), generates authorization code |
| 5 | A4 | Redirects to `{tpp_callback}?code={auth_code}&state={random}` | TPP receives authorization code |
| 6 | TPP | POST `/api/v1/token` with `grant_type=authorization_code`, `code={auth_code}`, `client_id`, `client_secret` | A4 validates code, issues JWT access token (expiry: 15min) + refresh token (single-use, rotating). Returns token response |
| 7 | TPP | GET `/api/v1/accounts` with `Authorization: Bearer {access_token}` | A4 validates JWT (signature, expiry, scopes), extracts account holder identity, queries A1 Account Ledger for account list |
| 8 | A4 | Queries A1 Account Ledger | A1 returns accounts with balances for the account holder |
| 9 | A4 | Returns accounts to TPP | Response includes account list with JWE-encrypted account numbers. Response headers include security headers |
| 10 | TPP | GET `/api/v1/accounts/{id}/transactions?from=2026-01-01&to=2026-04-10` | A4 validates token + scope `transactions:read`, queries A1 for transaction history, returns paginated results |

## Postconditions
- Active consent record in A4 database (TPP, scopes, account holder, expiry)
- TPP has valid access token (15 min) and refresh token
- Audit trail: consent grant, all API calls with timestamps and TPP identity
- Account holder can view and revoke this consent at any time

---

## Alternative Flows

### AF-01: Account Holder Denies Consent
| Step | Condition | System Response |
|------|-----------|----------------|
| 4a | Account holder clicks "Deny" on consent page | A4 redirects to TPP callback with `error=access_denied`. No consent created |

### AF-02: SCA Failure (Wrong TOTP)
| Step | Condition | System Response |
|------|-----------|----------------|
| 3a | Account holder enters wrong TOTP code | A4 returns error, allows 2 more attempts |
| 3b | 3 failed TOTP attempts | A4 locks SCA for 5 minutes, redirects with `error=sca_failed` |

### AF-03: Access Token Expired
| Step | Condition | System Response |
|------|-----------|----------------|
| 7a | TPP uses expired access token | A4 returns 401 Unauthorized with `error=invalid_token` |
| 7b | TPP uses refresh token to get new access token | POST `/api/v1/token` with `grant_type=refresh_token`. A4 issues new access token + new refresh token (old refresh token invalidated) |

### AF-04: Consent Expired (90 days)
| Step | Condition | System Response |
|------|-----------|----------------|
| 7c | TPP uses valid token but consent has expired (90 days since last SCA per PSD2 RTS Art. 10) | A4 returns 403 Forbidden with `error=consent_expired`. TPP must re-initiate consent flow (new SCA required) |

*Note: PSD3/PSR proposal may eliminate the 90-day mandatory re-authentication for AIS consents. If adopted (expected 2027-2028), consent validity would extend to the full agreed period without forced re-auth. Our system should make the 90-day re-auth interval configurable (`payment.psd2.consent.reauth-days=90`) to support this future change.*

### AF-05: Account Holder Revokes Consent
| Step | Condition | System Response |
|------|-----------|----------------|
| — | Account holder visits consent dashboard, revokes TPP access | A4 invalidates consent, all associated refresh tokens. Next API call by TPP returns 403 |

### AF-06: TPP Rate Limited
| Step | Condition | System Response |
|------|-----------|----------------|
| 7d | TPP exceeds rate limit (e.g., > 100 requests/minute) | A4 returns 429 Too Many Requests with `Retry-After` header |

### AF-07: Invalid TPP Client Certificate
| Step | Condition | System Response |
|------|-----------|----------------|
| 1a | TPP client certificate validation fails (mTLS) | A4 returns 401 with `error=invalid_client_certificate`. Connection refused |

---

## Data Requirements

**Consent entity:**
```
consent_id:         UUID (primary key)
tpp_client_id:      String (registered TPP)
account_holder_id:  String (authenticated user)
granted_scopes:     Set<String> (accounts:read, transactions:read, payments:initiate)
created_at:         Instant (UTC)
expires_at:         Instant (UTC, max 90 days from creation per PSD2)
revoked_at:         Instant (nullable, UTC)
status:             Enum (ACTIVE, EXPIRED, REVOKED)
sca_method:         String (TOTP, SMS)
sca_completed_at:   Instant (UTC)
```

**Token metadata:**
```
access_token:       JWT (signed RS256, 15min expiry)
  - sub:            account_holder_id
  - client_id:      tpp_client_id
  - scope:          granted scopes
  - consent_id:     reference to consent
  - exp:            expiry timestamp
  - iat:            issued at

refresh_token:      Opaque string (stored hashed in DB)
  - single_use:     true (rotating refresh tokens)
  - family_id:      UUID (detect refresh token reuse → revoke family)
  - expires_at:     consent expiry
```

## Security Considerations
- **RS256 JWT signing**: asymmetric keys; public key published at `/.well-known/jwks.json` for TPP verification
- **Refresh token rotation**: each use invalidates the old token and issues a new one. Reuse of an old refresh token triggers revocation of the entire token family (detects token theft)
- **JWE encryption**: account numbers and balances in API responses encrypted with the TPP's registered public key
- **PKCE** (Proof Key for Code Exchange): required on authorization request to prevent code interception
- **mTLS**: TPP identified by client certificate (simulating eIDAS QWAC certificates for PSD2)
- **Audit**: every token issuance, API call, consent change logged with TPP identity, IP, timestamp
