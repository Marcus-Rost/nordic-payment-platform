-- JRPay Identity Service — initial schema.
--
-- Tables:
--   identity_users           ─ customers + operators (audience-scoped)
--   identity_sca_factors     ─ enrolled TOTP / BankID / biometric factors
--   identity_oauth_clients   ─ pre-registered GUI / TPP clients
--   identity_oauth_authorizations
--                            ─ Spring Authorization Server's authorization log
--   identity_sca_sessions    ─ pending SCA challenges + dl_hash binding
--   identity_step_up_tokens  ─ short-lived step-up JWT tracking (lookup by hash)
--   identity_password_reset_tokens
--                            ─ single-use reset URLs (hashed) + rate-limit log
--   identity_sessions        ─ active OAuth2 sessions per device
--   identity_privacy_acceptance
--                            ─ per-user notice version acknowledgements
--
-- Conventions:
--   - Every PK is a UUID stored as `uuid` (PG native).
--   - Timestamps are `TIMESTAMPTZ` storing UTC.
--   - Sensitive token values are stored as SHA-256 hex hashes (`*_hash`),
--     never as plain text. The full token only exists in transit.
--   - Indices are added on every column referenced by a query.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE identity_users (
    user_id          UUID         PRIMARY KEY,
    audience         VARCHAR(16)  NOT NULL CHECK (audience IN ('customer', 'admin')),
    username         VARCHAR(255) NOT NULL,
    email            VARCHAR(254),
    password_hash    VARCHAR(120) NOT NULL,
    status           VARCHAR(32)  NOT NULL
                     CHECK (status IN ('PENDING_FIRST_LOGIN', 'ACTIVE', 'SUSPENDED', 'LOCKED', 'DELETED')),
    role             VARCHAR(32),
    failed_attempts  INT          NOT NULL DEFAULT 0,
    locked_until     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_login_at    TIMESTAMPTZ,

    CONSTRAINT uq_identity_users_audience_username UNIQUE (audience, username)
);
CREATE INDEX idx_identity_users_email ON identity_users (email);
CREATE INDEX idx_identity_users_status ON identity_users (status);

CREATE TABLE identity_sca_factors (
    factor_id    UUID         PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES identity_users(user_id) ON DELETE CASCADE,
    factor_type  VARCHAR(32)  NOT NULL
                 CHECK (factor_type IN ('TOTP', 'BANKID_SIMULATED', 'BANKID_REAL', 'BIOMETRIC')),
    -- For TOTP: encrypted/encoded shared secret. For BankID: subject id. For biometric: device key id.
    secret       TEXT         NOT NULL,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ
);
CREATE INDEX idx_identity_sca_factors_user ON identity_sca_factors (user_id);

CREATE TABLE identity_oauth_clients (
    client_id            VARCHAR(64)  PRIMARY KEY,
    client_secret_hash   VARCHAR(120),
    client_name          VARCHAR(255) NOT NULL,
    audience             VARCHAR(16)  NOT NULL CHECK (audience IN ('customer', 'admin', 'service')),
    grant_types          TEXT         NOT NULL,
    redirect_uris        TEXT         NOT NULL,
    scopes               TEXT         NOT NULL,
    require_pkce         BOOLEAN      NOT NULL DEFAULT TRUE,
    require_consent      BOOLEAN      NOT NULL DEFAULT FALSE,
    access_token_ttl_sec INT          NOT NULL DEFAULT 900,
    refresh_token_ttl_sec INT         NOT NULL DEFAULT 1209600,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE identity_oauth_authorizations (
    id                              VARCHAR(36)  PRIMARY KEY,
    registered_client_id            VARCHAR(64)  NOT NULL,
    principal_name                  VARCHAR(255) NOT NULL,
    authorization_grant_type        VARCHAR(64)  NOT NULL,
    authorized_scopes               TEXT,
    attributes                      TEXT,
    state                           VARCHAR(500),
    authorization_code_value        TEXT,
    authorization_code_issued_at    TIMESTAMPTZ,
    authorization_code_expires_at   TIMESTAMPTZ,
    authorization_code_metadata     TEXT,
    access_token_value              TEXT,
    access_token_issued_at          TIMESTAMPTZ,
    access_token_expires_at         TIMESTAMPTZ,
    access_token_metadata           TEXT,
    access_token_type               VARCHAR(64),
    access_token_scopes             TEXT,
    oidc_id_token_value             TEXT,
    oidc_id_token_issued_at         TIMESTAMPTZ,
    oidc_id_token_expires_at        TIMESTAMPTZ,
    oidc_id_token_metadata          TEXT,
    refresh_token_value             TEXT,
    refresh_token_issued_at         TIMESTAMPTZ,
    refresh_token_expires_at        TIMESTAMPTZ,
    refresh_token_metadata          TEXT,
    user_code_value                 TEXT,
    user_code_issued_at             TIMESTAMPTZ,
    user_code_expires_at            TIMESTAMPTZ,
    user_code_metadata              TEXT,
    device_code_value               TEXT,
    device_code_issued_at           TIMESTAMPTZ,
    device_code_expires_at          TIMESTAMPTZ,
    device_code_metadata            TEXT
);
CREATE INDEX idx_identity_oauth_authz_principal ON identity_oauth_authorizations (principal_name);
CREATE INDEX idx_identity_oauth_authz_client ON identity_oauth_authorizations (registered_client_id);

CREATE TABLE identity_sca_sessions (
    sca_session_id   UUID         PRIMARY KEY,
    user_id          UUID         NOT NULL REFERENCES identity_users(user_id) ON DELETE CASCADE,
    dl_class         VARCHAR(32)  NOT NULL,
    dl_hash          VARCHAR(64)     NOT NULL,
    canonical_input  TEXT         NOT NULL,
    factor_type      VARCHAR(32)  NOT NULL,
    state            VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                     CHECK (state IN ('PENDING', 'VERIFIED', 'CONSUMED', 'FAILED', 'EXPIRED')),
    attempts         INT          NOT NULL DEFAULT 0,
    exempt           BOOLEAN      NOT NULL DEFAULT FALSE,
    issued_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at       TIMESTAMPTZ  NOT NULL,
    verified_at      TIMESTAMPTZ
);
CREATE INDEX idx_identity_sca_sessions_user ON identity_sca_sessions (user_id);
CREATE INDEX idx_identity_sca_sessions_state ON identity_sca_sessions (state);

CREATE TABLE identity_step_up_tokens (
    token_hash       VARCHAR(64)     PRIMARY KEY,  -- SHA-256 hex of the bearer string
    user_id          UUID         NOT NULL REFERENCES identity_users(user_id) ON DELETE CASCADE,
    sca_session_id   UUID         NOT NULL REFERENCES identity_sca_sessions(sca_session_id) ON DELETE CASCADE,
    dl_hash          VARCHAR(64)     NOT NULL,
    issued_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at       TIMESTAMPTZ  NOT NULL,
    consumed_at      TIMESTAMPTZ
);
CREATE INDEX idx_identity_step_up_tokens_user ON identity_step_up_tokens (user_id);

CREATE TABLE identity_password_reset_tokens (
    token_hash       VARCHAR(64)     PRIMARY KEY,  -- SHA-256 hex of the URL token
    user_id          UUID         NOT NULL REFERENCES identity_users(user_id) ON DELETE CASCADE,
    audience         VARCHAR(16)  NOT NULL,
    issued_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at       TIMESTAMPTZ  NOT NULL,
    consumed_at      TIMESTAMPTZ
);
CREATE INDEX idx_identity_password_reset_tokens_user ON identity_password_reset_tokens (user_id);

CREATE TABLE identity_password_reset_requests (
    request_id    UUID         PRIMARY KEY,
    identifier_hash VARCHAR(64)   NOT NULL,    -- SHA-256 of the requested identifier (rate-limit key)
    audience      VARCHAR(16)  NOT NULL,
    issued_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved      BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_identity_password_reset_requests_lookup
    ON identity_password_reset_requests (identifier_hash, issued_at DESC);

CREATE TABLE identity_sessions (
    session_id       UUID         PRIMARY KEY,
    user_id          UUID         NOT NULL REFERENCES identity_users(user_id) ON DELETE CASCADE,
    client_id        VARCHAR(64)  NOT NULL REFERENCES identity_oauth_clients(client_id),
    device_name      VARCHAR(140) NOT NULL,
    user_agent       VARCHAR(512),
    ip_country       VARCHAR(2),
    audience         VARCHAR(16)  NOT NULL,
    acr_level        VARCHAR(32),
    last_seen_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    revoked_at       TIMESTAMPTZ,
    revoked_reason   VARCHAR(64)
);
CREATE INDEX idx_identity_sessions_user ON identity_sessions (user_id);
CREATE INDEX idx_identity_sessions_active ON identity_sessions (user_id) WHERE revoked_at IS NULL;

CREATE TABLE identity_privacy_acceptance (
    user_id          UUID         NOT NULL REFERENCES identity_users(user_id) ON DELETE CASCADE,
    notice_version   VARCHAR(32)  NOT NULL,
    accepted_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, notice_version)
);

-- Stream-token store backing UC-G52/G53 SSE auth (single-use, 5-min TTL).
CREATE TABLE identity_stream_tokens (
    token_hash       VARCHAR(64)     PRIMARY KEY,
    user_id          UUID         NOT NULL REFERENCES identity_users(user_id) ON DELETE CASCADE,
    audience         VARCHAR(16)  NOT NULL,
    purpose          VARCHAR(32)  NOT NULL,
    expires_at       TIMESTAMPTZ  NOT NULL,
    consumed_at      TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
