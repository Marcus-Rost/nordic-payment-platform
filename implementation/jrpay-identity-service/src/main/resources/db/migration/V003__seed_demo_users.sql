-- Demo users for the docker / demo profiles.
--
-- Passwords are bcrypt-encoded ('demo-password-1!' for both). Real
-- deployments seed via UC-G43 user-admin instead.
--
-- Customer:        ola.nordmann@example.com / demo-password-1!
-- Operator (admin): admin@jrpay.example     / demo-password-1!
--
-- TOTP factors are seeded with a deterministic Base32 secret so the
-- accompanying smoke tests can compute valid 6-digit codes.

INSERT INTO identity_users (
    user_id, audience, username, email, password_hash, status, role
) VALUES
    (
        '550e8400-e29b-41d4-a716-446655440001',
        'customer',
        'ola.nordmann@example.com',
        'ola.nordmann@example.com',
        -- bcrypt('demo-password-1!')
        '$2a$10$qV3.HCZ/7l72KSlPjLktKuy9G3mNmoF/iXFkU8YWX1zh3lF5bKsny',
        'ACTIVE', NULL
    ),
    (
        '550e8400-e29b-41d4-a716-446655440002',
        'admin',
        'admin@jrpay.example',
        'admin@jrpay.example',
        '$2a$10$qV3.HCZ/7l72KSlPjLktKuy9G3mNmoF/iXFkU8YWX1zh3lF5bKsny',
        'ACTIVE', 'ADMIN'
    );

INSERT INTO identity_sca_factors (factor_id, user_id, factor_type, secret, is_active)
VALUES
    (
        '550e8400-e29b-41d4-a716-446655440101',
        '550e8400-e29b-41d4-a716-446655440001',
        'TOTP',
        -- Base32-encoded "JRPAYDEMO"
        'JFCFAUKBKJEU2TQ=',
        TRUE
    ),
    (
        '550e8400-e29b-41d4-a716-446655440102',
        '550e8400-e29b-41d4-a716-446655440002',
        'TOTP',
        'JFCFAUKBKJEU2TQ=',
        TRUE
    );
