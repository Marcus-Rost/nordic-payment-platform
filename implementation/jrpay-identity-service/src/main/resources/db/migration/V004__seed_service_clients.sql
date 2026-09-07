-- Service-to-service OAuth2 clients used by the resource servers (A1 today,
-- A4/A5 in 2J/2K/2L) to validate step-up tokens against the identity
-- service. client_credentials grant only; minimum scope set.
--
-- Phase 2I introduces:
--   - jrpay-a1-service (account ledger admin endpoints)
--
-- The {@code sca:validate} scope is admitted only on these service clients.
-- Customer/admin browser clients receive their own scopes via V002.

INSERT INTO identity_oauth_clients (
    client_id, client_secret_hash, client_name, audience,
    grant_types, redirect_uris, scopes, require_pkce, require_consent,
    access_token_ttl_sec, refresh_token_ttl_sec
) VALUES
    (
        'jrpay-a1-service',
        -- bcrypt('a1-service-secret') strength 10
        '$2b$10$mfvaaI8s2A9vfsHgFIT9Ce2KNS0v//qL9IS3654YR5uNmrRyZO.Ni',
        'JRPay A1 Service', 'service',
        'client_credentials',
        'urn:none', -- redirect_uris is NOT NULL; client_credentials never redirects
        'sca:validate',
        -- refresh_token_ttl_sec must be > 0 even for client_credentials
        -- (Spring Authorization Server's TokenSettings rejects Duration.ZERO);
        -- the value is unused since client_credentials issues no refresh token.
        FALSE, FALSE, 900, 900
    );
