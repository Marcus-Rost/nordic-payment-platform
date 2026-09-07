-- Phase 2L — A5 (jrpay-fraud-detection) needs to validate step-up tokens
-- for the admin-fraud / admin-sanctions decide flows. Same pattern as V004/V005/V006.

INSERT INTO identity_oauth_clients (
    client_id, client_secret_hash, client_name, audience,
    grant_types, redirect_uris, scopes, require_pkce, require_consent,
    access_token_ttl_sec, refresh_token_ttl_sec
) VALUES
    (
        'jrpay-a5-service',
        -- bcrypt('a5-service-secret') strength 10
        '$2b$10$R7YvtjNBtcEMWBwihFegAuqoBaPCp3bT9zXk57g8NQRlvqkiJXWQG',
        'JRPay A5 Service', 'service',
        'client_credentials',
        'urn:none',
        'sca:validate',
        FALSE, FALSE, 900, 900
    );
