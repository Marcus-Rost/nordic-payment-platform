-- JRPay Audit Query Service — projection schema for `audit.events`.
--
-- Tables:
--   audit_events           ─ projected events with chain-hash; indexed for search
--   audit_checkpoints      ─ rolled-up hashes every N events for fast verify
--   audit_export_jobs      ─ async CSV / signed-JSON exports (UC-G40 §6.4)
--   audit_export_artefacts ─ raw bytes of small exports (large ones go to blob)
--   processed_kafka_events ─ idempotency by (topic, partition, offset)
--
-- Conventions match the other services:
--   - UUID PKs (PG native).
--   - `TIMESTAMPTZ` storing UTC.
--   - SHA-256 chain hashes stored as 64-char lowercase hex.
--   - PII fields are NOT separately broken out — payload_json holds the
--     pre-masked event as it arrived on the Kafka topic; fully un-masked
--     reads belong to UC-G36 reveal events only and never live here.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE audit_events (
    event_id           VARCHAR(26)   PRIMARY KEY,         -- ULID, mirrors the Kafka key
    chain_offset       BIGSERIAL     NOT NULL UNIQUE,     -- monotonic insertion ordinal
    occurred_at        TIMESTAMPTZ   NOT NULL,
    type               VARCHAR(120)  NOT NULL,
    actor              VARCHAR(160)  NOT NULL,
    actor_kind         VARCHAR(16)   NOT NULL,            -- operator | customer | system
    outcome            VARCHAR(16)   NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE', 'REJECTED')),
    severity           VARCHAR(16)   NOT NULL CHECK (severity IN ('INFO', 'WARN', 'ERROR', 'CRITICAL')),
    summary            TEXT,
    resource_type      VARCHAR(32),
    resource_id        VARCHAR(140),
    correlation_id     UUID,
    customer_user_id   UUID,
    operator_user_id   UUID,
    consent_id         UUID,
    payment_id         UUID,
    case_id            UUID,
    payload_json       JSONB         NOT NULL DEFAULT '{}'::jsonb,
    prior_hash         VARCHAR(64),                          -- null only for chain_offset=1
    chain_hash         VARCHAR(64)      NOT NULL,
    kafka_topic        VARCHAR(120)  NOT NULL,
    kafka_partition    INT           NOT NULL,
    kafka_offset       BIGINT        NOT NULL,
    inserted_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    UNIQUE (kafka_topic, kafka_partition, kafka_offset)
);

-- Search hot paths.
CREATE INDEX idx_audit_events_occurred_at ON audit_events (occurred_at DESC);
CREATE INDEX idx_audit_events_type        ON audit_events (type);
CREATE INDEX idx_audit_events_actor       ON audit_events (actor);
CREATE INDEX idx_audit_events_resource    ON audit_events (resource_type, resource_id);
CREATE INDEX idx_audit_events_severity    ON audit_events (severity);
CREATE INDEX idx_audit_events_outcome     ON audit_events (outcome);
CREATE INDEX idx_audit_events_customer    ON audit_events (customer_user_id) WHERE customer_user_id IS NOT NULL;
CREATE INDEX idx_audit_events_operator    ON audit_events (operator_user_id) WHERE operator_user_id IS NOT NULL;
CREATE INDEX idx_audit_events_consent     ON audit_events (consent_id) WHERE consent_id IS NOT NULL;

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Free-text search across summary + actor + resource_id (UC-G40 §6.1 `q=`).
CREATE INDEX idx_audit_events_summary_trgm
    ON audit_events USING gin ((coalesce(summary, '') || ' ' || actor || ' ' || coalesce(resource_id, '')) gin_trgm_ops);

CREATE TABLE audit_checkpoints (
    checkpoint_id  UUID         PRIMARY KEY,
    from_offset    BIGINT       NOT NULL,
    to_offset      BIGINT       NOT NULL,
    event_count    INT          NOT NULL,
    chain_hash     VARCHAR(64)     NOT NULL,                  -- copy of audit_events.chain_hash at to_offset
    verified_by    VARCHAR(64)  NOT NULL DEFAULT 'system:chain-verifier',
    verified_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (from_offset, to_offset)
);
CREATE INDEX idx_audit_checkpoints_to_offset ON audit_checkpoints (to_offset DESC);

CREATE TABLE audit_export_jobs (
    request_id        UUID         PRIMARY KEY,
    requested_by      VARCHAR(160) NOT NULL,
    audience          VARCHAR(16)  NOT NULL CHECK (audience IN ('admin', 'customer')),
    format            VARCHAR(16)  NOT NULL CHECK (format IN ('CSV', 'JSON_SIGNED')),
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                       CHECK (status IN ('PENDING', 'IN_PROGRESS', 'READY', 'FAILED', 'EXPIRED', 'CANCELLED')),
    filter_json       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    requested_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ready_at          TIMESTAMPTZ,
    download_expires_at TIMESTAMPTZ,
    artefact_size_bytes BIGINT,
    progress          INT          NOT NULL DEFAULT 0
                       CHECK (progress BETWEEN 0 AND 100),
    signed            BOOLEAN      NOT NULL DEFAULT FALSE,
    unsigned_reason   VARCHAR(64),
    failure_reason    TEXT,
    customer_user_id  UUID,                                -- when audience='customer', limits the export to this user
    nonce             VARCHAR(64),                         -- single-use download token (hex of bytes)
    nonce_consumed_at TIMESTAMPTZ
);
CREATE INDEX idx_audit_export_jobs_requestor ON audit_export_jobs (requested_by, requested_at DESC);

CREATE TABLE audit_export_artefacts (
    request_id  UUID         PRIMARY KEY REFERENCES audit_export_jobs(request_id) ON DELETE CASCADE,
    content_type VARCHAR(64) NOT NULL,
    bytes        BYTEA       NOT NULL
);

CREATE TABLE processed_kafka_events (
    topic     VARCHAR(120) NOT NULL,
    partition INT          NOT NULL,
    "offset"  BIGINT       NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (topic, partition, "offset")
);
