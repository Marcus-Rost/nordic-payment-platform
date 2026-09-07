-- Phase 2J — A3 (jrpay-bill-payment) needs to validate step-up tokens for
-- the customer R2P / mandate / beneficiary surfaces. Same shape as the
-- jrpay-a1-service client seeded in V004.

INSERT INTO identity_oauth_clients (
    client_id, client_secret_hash, client_name, audience,
    grant_types, redirect_uris, scopes, require_pkce, require_consent,
    access_token_ttl_sec, refresh_token_ttl_sec
) VALUES
    (
        'jrpay-a3-service',
        -- bcrypt('a3-service-secret') strength 10
        '$2b$10$2uSjWoItyFTBqBFq8t63wO9OjsbGUdIIVuWdVdSaOiVQ6wRyZEDXK',
        'JRPay A3 Service', 'service',
        'client_credentials',
        'urn:none',
        'sca:validate',
        FALSE, FALSE, 900, 900
    );
