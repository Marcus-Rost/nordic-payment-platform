-- JRPay Notifications Service — initial schema.
--
-- Tables:
--   notifications              ─ inbox rows + read/delete state
--   notification_preferences   ─ per-user channel × category matrix (JSONB)
--   push_devices               ─ APNs / FCM tokens
--   stream_tokens              ─ single-use SSE tokens (5-min TTL)
--   processed_kafka_events     ─ idempotency table for Kafka projections
--                                (topic + partition + offset is the key)
--   pending_email_verifications  ─ rate-limit log for resend-verify endpoints
--
-- Conventions match the identity-service module:
--   - UUID PKs (PG native).
--   - `TIMESTAMPTZ` storing UTC.
--   - Hashed token values; never plaintext.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE notifications (
    notification_id   VARCHAR(26)   PRIMARY KEY,             -- ULID for stable monotonic ordering
    user_id           UUID          NOT NULL,
    audience          VARCHAR(16)   NOT NULL CHECK (audience IN ('customer', 'admin')),
    category          VARCHAR(40)   NOT NULL,
    priority          VARCHAR(8)    NOT NULL DEFAULT 'info'
                       CHECK (priority IN ('info', 'warn', 'critical')),
    title_key         VARCHAR(120)  NOT NULL,
    body_summary_key  VARCHAR(120)  NOT NULL,
    params_json       JSONB         NOT NULL DEFAULT '{}'::jsonb,
    deep_link         VARCHAR(512),
    action_hint       VARCHAR(16)   NOT NULL DEFAULT 'VIEW'
                       CHECK (action_hint IN ('VIEW', 'ACT_NOW', 'NONE')),
    locked            BOOLEAN       NOT NULL DEFAULT FALSE,
    support_reference VARCHAR(64),
    related_payment_id      UUID,
    related_r2p_request_id  UUID,
    related_consent_id      UUID,
    related_mandate_id      UUID,
    related_gdpr_request_id UUID,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    read_at           TIMESTAMPTZ,
    deleted_at        TIMESTAMPTZ
);

-- Inbox query: latest first, filter by user + read-state + category.
CREATE INDEX idx_notifications_user_created
    ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_user_unread
    ON notifications (user_id) WHERE read_at IS NULL AND deleted_at IS NULL;
CREATE INDEX idx_notifications_user_undeleted_recent
    ON notifications (user_id, created_at DESC) WHERE deleted_at IS NULL;

CREATE TABLE notification_preferences (
    user_id         UUID         PRIMARY KEY,
    audience        VARCHAR(16)  NOT NULL CHECK (audience IN ('customer', 'admin')),
    version         INT          NOT NULL DEFAULT 0,
    channels_json   JSONB        NOT NULL,
    categories_json JSONB        NOT NULL,
    email_address   VARCHAR(254),
    email_verified  BOOLEAN      NOT NULL DEFAULT FALSE,
    phone_number    VARCHAR(32),
    phone_verified  BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE push_devices (
    device_id     UUID         PRIMARY KEY,
    user_id       UUID         NOT NULL,
    platform      VARCHAR(8)   NOT NULL CHECK (platform IN ('APNS', 'FCM')),
    -- SHA-256 of the raw APNs/FCM token; store hashed only (FS.15).
    token_hash    VARCHAR(64)  NOT NULL,
    name          VARCHAR(140) NOT NULL,
    model         VARCHAR(140),
    os_version    VARCHAR(64),
    app_version   VARCHAR(64),
    bundle_id     VARCHAR(140),
    registered_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_seen_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    revoked_at    TIMESTAMPTZ,
    UNIQUE (user_id, token_hash)
);
CREATE INDEX idx_push_devices_user ON push_devices (user_id) WHERE revoked_at IS NULL;

CREATE TABLE stream_tokens (
    token_hash  VARCHAR(64)  PRIMARY KEY,
    user_id     UUID         NOT NULL,
    audience    VARCHAR(16)  NOT NULL,
    purpose     VARCHAR(32)  NOT NULL,
    issued_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    consumed_at TIMESTAMPTZ
);
CREATE INDEX idx_stream_tokens_user ON stream_tokens (user_id);

CREATE TABLE processed_kafka_events (
    topic     VARCHAR(120) NOT NULL,
    partition INT          NOT NULL,
    "offset"  BIGINT       NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (topic, partition, "offset")
);

CREATE TABLE pending_email_verifications (
    request_id    UUID         PRIMARY KEY,
    user_id       UUID         NOT NULL,
    address_hash  VARCHAR(64)  NOT NULL,
    issued_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    consumed      BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_pending_email_verifications_user_recent
    ON pending_email_verifications (user_id, issued_at DESC);

CREATE TABLE pending_phone_verifications (
    request_id    UUID         PRIMARY KEY,
    user_id       UUID         NOT NULL,
    phone_hash    VARCHAR(64)  NOT NULL,
    issued_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    consumed      BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_pending_phone_verifications_user_recent
    ON pending_phone_verifications (user_id, issued_at DESC);
