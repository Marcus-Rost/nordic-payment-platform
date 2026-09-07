-- Phase 2L — A5 case-management projection. Backs the admin-fraud and
-- admin-sanctions surfaces (UC-G31..G34).

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-----------------------------------------------------------------------------
-- fraud_cases: one row per fraud case. Created when A5's scoring pipeline
-- emits a HOLD decision via payment.fraud-check-response.
-----------------------------------------------------------------------------
CREATE TABLE fraud_cases (
    case_id             UUID            PRIMARY KEY,
    payment_id          UUID            NOT NULL,
    customer_user_id    UUID,
    debtor_account_id   VARCHAR(34)     NOT NULL,
    holder_name         VARCHAR(140),
    creditor_iban       VARCHAR(34)     NOT NULL,
    creditor_name       VARCHAR(140),
    amount              NUMERIC(18,2)   NOT NULL,
    currency            VARCHAR(3)      NOT NULL,
    reference           VARCHAR(140),
    execution_mode      VARCHAR(16),
    score               NUMERIC(5,4)    NOT NULL,
    score_band          VARCHAR(8)      NOT NULL CHECK (score_band IN ('LOW','MEDIUM','HIGH')),
    threshold           NUMERIC(5,4)    NOT NULL,
    triggered_rules     TEXT,                                  -- comma-separated rule ids
    score_breakdown     TEXT,                                  -- JSON blob for the breakdown
    status              VARCHAR(24)     NOT NULL CHECK (status IN ('OPEN','ASSIGNED','INVESTIGATING','CONFIRMED_FRAUD','DISMISSED')),
    assignee            UUID,
    initiated_at        TIMESTAMPTZ     NOT NULL,
    sla_deadline        TIMESTAMPTZ     NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    decided_at          TIMESTAMPTZ,
    decided_by          UUID,
    decision_notes_summary VARCHAR(140),
    decision_notes_hash VARCHAR(64),
    sca_session_id      UUID,
    CONSTRAINT chk_fraud_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_fraud_score_range CHECK (score >= 0 AND score <= 1)
);
CREATE INDEX idx_fraud_status_score ON fraud_cases (status, score DESC);
CREATE INDEX idx_fraud_assignee     ON fraud_cases (assignee) WHERE assignee IS NOT NULL;
CREATE INDEX idx_fraud_account      ON fraud_cases (debtor_account_id);
CREATE INDEX idx_fraud_payment      ON fraud_cases (payment_id);
CREATE INDEX idx_fraud_sla          ON fraud_cases (sla_deadline) WHERE status IN ('OPEN','ASSIGNED','INVESTIGATING');

-----------------------------------------------------------------------------
-- fraud_timeline: append-only state-transition history.
-----------------------------------------------------------------------------
CREATE TABLE fraud_timeline (
    timeline_id         BIGSERIAL       PRIMARY KEY,
    case_id             UUID            NOT NULL REFERENCES fraud_cases(case_id) ON DELETE CASCADE,
    occurred_at         TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    type                VARCHAR(32)     NOT NULL CHECK (type IN (
                            'CASE_CREATED','CASE_ASSIGNED','CASE_VIEWED','CASE_DECIDED',
                            'PII_REVEALED','SLA_BREACHED','PAYMENT_HELD','PAYMENT_RELEASED',
                            'PAYMENT_REJECTED')),
    actor               VARCHAR(160)    NOT NULL,
    note                VARCHAR(280)
);
CREATE INDEX idx_fraud_timeline_case ON fraud_timeline (case_id, occurred_at ASC);

-----------------------------------------------------------------------------
-- fraud_held_payments: payments currently HELD attached to a case.
-----------------------------------------------------------------------------
CREATE TABLE fraud_held_payments (
    case_id             UUID            NOT NULL REFERENCES fraud_cases(case_id) ON DELETE CASCADE,
    payment_id          UUID            NOT NULL,
    amount              NUMERIC(18,2)   NOT NULL,
    currency            VARCHAR(3)      NOT NULL,
    status              VARCHAR(32)     NOT NULL,
    held_at             TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    PRIMARY KEY (case_id, payment_id)
);

-----------------------------------------------------------------------------
-- sanctions_hits: one row per sanctions screening hit.
-----------------------------------------------------------------------------
CREATE TABLE sanctions_hits (
    hit_id              UUID            PRIMARY KEY,
    payment_id          UUID            NOT NULL,
    customer_user_id    UUID,
    debtor_account_id   VARCHAR(34)     NOT NULL,
    matched_entity_name VARCHAR(280)    NOT NULL,
    matched_list_id     VARCHAR(64)     NOT NULL,
    match_score         NUMERIC(5,4)    NOT NULL,
    creditor_iban       VARCHAR(34)     NOT NULL,
    creditor_name       VARCHAR(140)    NOT NULL,
    amount              NUMERIC(18,2)   NOT NULL,
    currency            VARCHAR(3)      NOT NULL,
    reference           VARCHAR(140),
    matched_entity_payload TEXT,                              -- JSON blob describing the matched record
    status              VARCHAR(28)     NOT NULL CHECK (status IN ('OPEN','CONFIRMED_SANCTION','FALSE_POSITIVE','RELEASE_FOR_FRAUD_REVIEW')),
    assignee            UUID,
    sla_deadline        TIMESTAMPTZ     NOT NULL,
    initiated_at        TIMESTAMPTZ     NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    decided_at          TIMESTAMPTZ,
    decided_by          UUID,
    decision_notes_summary VARCHAR(140),
    decision_notes_hash VARCHAR(64),
    sca_session_id      UUID,
    CONSTRAINT chk_sanc_amount_positive CHECK (amount > 0)
);
CREATE INDEX idx_sanc_status     ON sanctions_hits (status);
CREATE INDEX idx_sanc_assignee   ON sanctions_hits (assignee) WHERE assignee IS NOT NULL;
CREATE INDEX idx_sanc_list       ON sanctions_hits (matched_list_id);
CREATE INDEX idx_sanc_entity_trgm ON sanctions_hits USING gin (matched_entity_name gin_trgm_ops);
CREATE INDEX idx_sanc_payment    ON sanctions_hits (payment_id);

-----------------------------------------------------------------------------
-- sanctions_lists: cached list metadata. Refresh runs update last_refresh_at.
-----------------------------------------------------------------------------
CREATE TABLE sanctions_lists (
    list_id             VARCHAR(64)     PRIMARY KEY,
    name                VARCHAR(255)    NOT NULL,
    source              VARCHAR(140)    NOT NULL,
    version             VARCHAR(64),
    entry_count         INTEGER         NOT NULL DEFAULT 0,
    last_refresh_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    stale_after_hours   INTEGER         NOT NULL DEFAULT 24
);

-- Seed a small demo registry — production refreshes via the upstream provider.
INSERT INTO sanctions_lists (list_id, name, source, version, entry_count, last_refresh_at, stale_after_hours) VALUES
    ('EU-CONS', 'EU Consolidated', 'eeas.europa.eu', '2026.04.10', 12345, NOW() - INTERVAL '6 hours', 24),
    ('OFAC-SDN', 'OFAC SDN List', 'treasury.gov', '2026.04.21', 8765, NOW() - INTERVAL '4 hours', 24),
    ('UN-CONS', 'UN Consolidated', 'un.org', '2026.04.05', 5210, NOW() - INTERVAL '12 hours', 24);

-----------------------------------------------------------------------------
-- sanctions_list_refresh_runs: async job tracking for /lists/refresh.
-----------------------------------------------------------------------------
CREATE TABLE sanctions_list_refresh_runs (
    run_id              UUID            PRIMARY KEY,
    requested_by        UUID            NOT NULL,
    list_ids            TEXT,                                  -- comma-separated; NULL = all
    status              VARCHAR(16)     NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    requested_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    failure_reason      VARCHAR(255)
);

-----------------------------------------------------------------------------
-- sanctions_fp_cache: false-positive cache (UC-G34 §6.4 — 90-day TTL).
-----------------------------------------------------------------------------
CREATE TABLE sanctions_fp_cache (
    entry_id            UUID            PRIMARY KEY,
    matched_entity_name VARCHAR(280)    NOT NULL,
    matched_list_id     VARCHAR(64)     NOT NULL,
    creditor_iban       VARCHAR(34)     NOT NULL,
    notes_summary       VARCHAR(140),
    added_by            UUID            NOT NULL,
    source_hit_id       UUID,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMPTZ     NOT NULL,
    removed_at          TIMESTAMPTZ
);
CREATE INDEX idx_fp_cache_active ON sanctions_fp_cache (creditor_iban, matched_list_id) WHERE removed_at IS NULL;
CREATE INDEX idx_fp_cache_entity_trgm ON sanctions_fp_cache USING gin (matched_entity_name gin_trgm_ops);

-----------------------------------------------------------------------------
-- stream_tokens: SSE auth tokens (single-use, 5-min TTL). Keyed by SHA-256
-- so the wire token never persists.
-----------------------------------------------------------------------------
CREATE TABLE stream_tokens (
    token_hash          VARCHAR(64)     PRIMARY KEY,
    operator_user_id    UUID            NOT NULL,
    scope               VARCHAR(16)     NOT NULL CHECK (scope IN ('FRAUD','SANCTIONS')),
    resource_id         VARCHAR(64),                          -- caseId or hitId when scoped
    issued_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expires_at          TIMESTAMPTZ     NOT NULL,
    consumed_at         TIMESTAMPTZ
);
CREATE INDEX idx_stream_tokens_operator ON stream_tokens (operator_user_id, issued_at DESC);

-----------------------------------------------------------------------------
-- processed_kafka_events: idempotent multi-topic consumer dedup.
-----------------------------------------------------------------------------
CREATE TABLE processed_kafka_events (
    topic               VARCHAR(140)    NOT NULL,
    partition           INTEGER         NOT NULL,
    kafka_offset        BIGINT          NOT NULL,
    processed_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    PRIMARY KEY (topic, partition, kafka_offset)
);

-----------------------------------------------------------------------------
-- admin_idempotency: per-operator Idempotency-Key dedup.
-----------------------------------------------------------------------------
CREATE TABLE admin_idempotency (
    operator_user_id    UUID            NOT NULL,
    idempotency_key     VARCHAR(64)     NOT NULL,
    operation           VARCHAR(40)     NOT NULL,
    request_hash        VARCHAR(64)     NOT NULL,
    response_status     INT             NOT NULL,
    response_body       TEXT            NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    PRIMARY KEY (operator_user_id, idempotency_key)
);
CREATE INDEX idx_admin_idem_created ON admin_idempotency (created_at);

-----------------------------------------------------------------------------
-- bulk_decide_runs: rate-limit ledger for bulk-decide (1/60s per operator).
-----------------------------------------------------------------------------
CREATE TABLE bulk_decide_runs (
    operator_user_id    UUID            NOT NULL,
    requested_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    case_count          INTEGER         NOT NULL,
    decision            VARCHAR(20)     NOT NULL,
    PRIMARY KEY (operator_user_id, requested_at)
);
