-- Pre-register the four GUI clients + a placeholder TPP for tests.
--
-- redirect_uris is a comma-separated list — the SAS adapter explodes on
-- comma. Scopes are space-separated to match RFC 6749.
--
-- Web clients receive refresh_tokens via Set-Cookie; mobile clients
-- receive them in the JSON body. The distinction is enforced at the
-- token endpoint based on the client_id's audience.

INSERT INTO identity_oauth_clients (
    client_id, client_secret_hash, client_name, audience,
    grant_types, redirect_uris, scopes, require_pkce, require_consent,
    access_token_ttl_sec, refresh_token_ttl_sec
) VALUES
    (
        'jrpay-client-web', NULL, 'JRPay Customer Web', 'customer',
        'authorization_code,refresh_token',
        'http://localhost:5173/oauth/callback,https://client.jrpay.example/oauth/callback',
        'openid profile accounts:read transactions:read payments:initiate payments:read payments:cancel mandates:manage consents:manage beneficiaries:manage notifications:manage profile:read profile:write gdpr:export gdpr:erase',
        TRUE, FALSE, 900, 1209600
    ),
    (
        'jrpay-client-ios', NULL, 'JRPay Customer iOS', 'customer',
        'authorization_code,refresh_token',
        'jrpay://oauth/callback',
        'openid profile accounts:read transactions:read payments:initiate payments:read payments:cancel mandates:manage consents:manage beneficiaries:manage notifications:manage profile:read profile:write gdpr:export gdpr:erase',
        TRUE, FALSE, 900, 1209600
    ),
    (
        'jrpay-client-android', NULL, 'JRPay Customer Android', 'customer',
        'authorization_code,refresh_token',
        'jrpay://oauth/callback',
        'openid profile accounts:read transactions:read payments:initiate payments:read payments:cancel mandates:manage consents:manage beneficiaries:manage notifications:manage profile:read profile:write gdpr:export gdpr:erase',
        TRUE, FALSE, 900, 1209600
    ),
    (
        'jrpay-admin-web', NULL, 'JRPay Admin Web', 'admin',
        'authorization_code,refresh_token',
        'http://localhost:5174/oauth/callback,https://admin.jrpay.example/oauth/callback',
        'openid profile fraud:read fraud:manage sanctions:read sanctions:manage accounts:admin:read accounts:admin:write audit:read audit:verify reconciliation:read reconciliation:run payments:investigate gdpr:execute users:read users:manage system:read',
        TRUE, FALSE, 900, 28800
    ),
    (
        'tpp-fiken-test', '$2a$10$ABCDEFabcdefABCDEFabceiE7K6dWqUtvGZBSU7BzfjgT2.bU6Zs1G',
            -- bcrypt of 'fiken-test-secret' — placeholder; rotated in real envs.
        'Fiken AS (Test TPP)', 'service',
        'authorization_code,refresh_token,client_credentials',
        'https://tpp.fiken.example/callback',
        'openid accounts:read transactions:read payments:initiate',
        TRUE, TRUE, 900, 15552000
    );
