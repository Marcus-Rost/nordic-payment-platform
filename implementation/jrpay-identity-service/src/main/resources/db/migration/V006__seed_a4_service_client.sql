-- Phase 2K — A4 (jrpay-psd2-api) needs to validate step-up tokens for the
-- customer accounts / payments / schedules surfaces. Same pattern as A1/A3.

INSERT INTO identity_oauth_clients (
    client_id, client_secret_hash, client_name, audience,
    grant_types, redirect_uris, scopes, require_pkce, require_consent,
    access_token_ttl_sec, refresh_token_ttl_sec
) VALUES
    (
        'jrpay-a4-service',
        -- bcrypt('a4-service-secret') strength 10
        '$2b$10$yB.ldXRH1St0wQfiFPJ.se.obD2mUaS/R.Zybk9n3CkNAjmYk679u',
        'JRPay A4 Service', 'service',
        'client_credentials',
        'urn:none',
        'sca:validate',
        FALSE, FALSE, 900, 900
    );
