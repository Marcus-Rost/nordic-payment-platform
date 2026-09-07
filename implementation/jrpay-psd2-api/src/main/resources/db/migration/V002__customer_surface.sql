-- Phase 2K — A4 customer surface: accounts read-projection, transactions
-- read-projection, customer payments (draft → SCA → submit → settled),
-- payment schedules (UC-G07), statement export jobs (UC-G04).

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-----------------------------------------------------------------------------
-- customer_accounts: read-side projection of A1's account state. Updated by
-- the ledger.events Kafka consumer (V002 listener). Customer-scoped reads
-- query this table; A1 remains the system-of-record.
-----------------------------------------------------------------------------
CREATE TABLE customer_accounts (
    account_id           VARCHAR(34)     PRIMARY KEY,
    customer_user_id     UUID            NOT NULL,
    iban                 VARCHAR(34),
    bic                  VARCHAR(11),
    account_type         VARCHAR(16)     NOT NULL DEFAULT 'CHECKING'
                         CHECK (account_type IN ('CHECKING','SAVINGS','CREDIT')),
    currency             VARCHAR(3)      NOT NULL,
    booked_balance       NUMERIC(18,2)   NOT NULL DEFAULT 0,
    reserved_amount      NUMERIC(18,2)   NOT NULL DEFAULT 0,
    status               VARCHAR(10)     NOT NULL DEFAULT 'ACTIVE'
                         CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    nickname             VARCHAR(140),
    is_primary           BOOLEAN         NOT NULL DEFAULT FALSE,
    opened_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    closed_at            TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_cust_acc_balance_non_negative CHECK (booked_balance >= 0),
    CONSTRAINT chk_cust_acc_reserved_non_negative CHECK (reserved_amount >= 0)
);
CREATE INDEX idx_cust_accounts_customer ON customer_accounts (customer_user_id, status);
CREATE UNIQUE INDEX idx_cust_accounts_iban ON customer_accounts (iban) WHERE iban IS NOT NULL;

-----------------------------------------------------------------------------
-- customer_transactions: read-side per-account leg projection. Mirrors A1's
-- account_transactions but scoped to a customer's accounts via the
-- customer_accounts join.
-----------------------------------------------------------------------------
CREATE TABLE customer_transactions (
    transaction_id       VARCHAR(36)     NOT NULL,
    account_id           VARCHAR(34)     NOT NULL REFERENCES customer_accounts(account_id),
    customer_user_id     UUID            NOT NULL,
    direction            VARCHAR(6)      NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount               NUMERIC(18,2)   NOT NULL,
    currency             VARCHAR(3)      NOT NULL,
    status               VARCHAR(16)     NOT NULL,
    counterparty_name    VARCHAR(140),
    counterparty_iban    VARCHAR(34),
    reference            VARCHAR(140),
    rejection_reason     VARCHAR(40),
    description          VARCHAR(280),
    posted_at            TIMESTAMPTZ     NOT NULL,
    PRIMARY KEY (account_id, transaction_id),
    CONSTRAINT chk_cust_tx_amount_positive CHECK (amount > 0)
);
CREATE INDEX idx_cust_tx_customer_posted ON customer_transactions (customer_user_id, posted_at DESC);
CREATE INDEX idx_cust_tx_account_posted  ON customer_transactions (account_id, posted_at DESC);
CREATE INDEX idx_cust_tx_counterparty_trgm ON customer_transactions USING gin (counterparty_name gin_trgm_ops);

-----------------------------------------------------------------------------
-- customer_payments: A4-side payment records. Lifecycle:
--   PENDING_SCA → PENDING_EXECUTION → AWAITING_CLEARING → SETTLING → SETTLED
-- with REJECTED / RETURNED / CANCELLED branches. Also covers HELD (fraud).
-----------------------------------------------------------------------------
CREATE TABLE customer_payments (
    payment_id           VARCHAR(36)     PRIMARY KEY,
    customer_user_id     UUID            NOT NULL,
    debtor_account_id    VARCHAR(34)     NOT NULL,
    creditor_iban        VARCHAR(34)     NOT NULL,
    creditor_name        VARCHAR(140)    NOT NULL,
    amount               NUMERIC(18,2)   NOT NULL,
    currency             VARCHAR(3)      NOT NULL,
    reference            VARCHAR(140),
    description          VARCHAR(280),
    execution_mode       VARCHAR(16)     NOT NULL,
    status               VARCHAR(32)     NOT NULL,
    sca_session_id       UUID,
    dl_hash              VARCHAR(64),
    step_up_token_hash   VARCHAR(64),
    vop_result           VARCHAR(16),
    vop_payer_override   BOOLEAN,
    rejection_reason     VARCHAR(40),
    transaction_id       VARCHAR(36),
    settled_at           TIMESTAMPTZ,
    cancelled_at         TIMESTAMPTZ,
    last_status_refreshed_at TIMESTAMPTZ,
    idempotency_key      VARCHAR(64)     UNIQUE,
    request_hash         VARCHAR(64),
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_payments_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_payments_execution_mode CHECK (execution_mode IN ('INSTANT','STANDARD','BOOK_TRANSFER'))
);
CREATE INDEX idx_payments_customer ON customer_payments (customer_user_id, created_at DESC);
CREATE INDEX idx_payments_status   ON customer_payments (status);

-----------------------------------------------------------------------------
-- payment_schedules: UC-G07 future-dated payments.
-----------------------------------------------------------------------------
CREATE TABLE payment_schedules (
    schedule_id          VARCHAR(36)     PRIMARY KEY,
    customer_user_id     UUID            NOT NULL,
    debtor_account_id    VARCHAR(34)     NOT NULL,
    creditor_iban        VARCHAR(34)     NOT NULL,
    creditor_name        VARCHAR(140)    NOT NULL,
    amount               NUMERIC(18,2)   NOT NULL,
    currency             VARCHAR(3)      NOT NULL,
    reference            VARCHAR(140),
    execute_at           TIMESTAMPTZ     NOT NULL,
    execution_mode       VARCHAR(16)     NOT NULL,
    status               VARCHAR(32)     NOT NULL
                         CHECK (status IN ('PENDING_SCA','SCHEDULED','EXECUTED','CANCELLED','EXECUTION_FAILED')),
    sca_session_id       UUID,
    dl_hash              VARCHAR(64),
    executed_payment_id  VARCHAR(36),
    cancelled_at         TIMESTAMPTZ,
    executed_at          TIMESTAMPTZ,
    failure_reason       VARCHAR(40),
    note                 VARCHAR(280),
    idempotency_key      VARCHAR(64)     UNIQUE,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_schedules_amount_positive CHECK (amount > 0)
);
CREATE INDEX idx_schedules_customer_status ON payment_schedules (customer_user_id, status);
CREATE INDEX idx_schedules_due ON payment_schedules (execute_at) WHERE status = 'SCHEDULED';

-----------------------------------------------------------------------------
-- statement_jobs: async export of UC-G04 statements.
-----------------------------------------------------------------------------
CREATE TABLE statement_jobs (
    request_id           UUID            PRIMARY KEY,
    customer_user_id     UUID            NOT NULL,
    account_id           VARCHAR(34)     NOT NULL,
    from_date            DATE            NOT NULL,
    to_date              DATE            NOT NULL,
    format               VARCHAR(8)      NOT NULL CHECK (format IN ('PDF','CSV')),
    status               VARCHAR(16)     NOT NULL CHECK (status IN ('PENDING','READY','FAILED','EXPIRED')),
    download_url         VARCHAR(500),
    download_expires_at  TIMESTAMPTZ,
    failure_reason       VARCHAR(255),
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    completed_at         TIMESTAMPTZ
);
CREATE INDEX idx_statement_jobs_customer ON statement_jobs (customer_user_id, created_at DESC);

-----------------------------------------------------------------------------
-- stream_tokens: A4-issued single-use SSE auth tokens (5-min TTL).
-- Stored as SHA-256(token) so the wire token never persists.
-----------------------------------------------------------------------------
CREATE TABLE stream_tokens (
    token_hash           VARCHAR(64)     PRIMARY KEY,
    customer_user_id     UUID            NOT NULL,
    payment_id           VARCHAR(36),
    issued_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expires_at           TIMESTAMPTZ     NOT NULL,
    consumed_at          TIMESTAMPTZ
);
CREATE INDEX idx_stream_tokens_customer ON stream_tokens (customer_user_id, issued_at DESC);

-----------------------------------------------------------------------------
-- processed_kafka_events: idempotent ledger.events / payment.status-updates
-- consumption. Composite PK so independent topics share the table.
-----------------------------------------------------------------------------
CREATE TABLE processed_kafka_events (
    topic                VARCHAR(140)    NOT NULL,
    partition            INTEGER         NOT NULL,
    kafka_offset         BIGINT          NOT NULL,
    processed_at         TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    PRIMARY KEY (topic, partition, kafka_offset)
);

-----------------------------------------------------------------------------
-- customer_idempotency: per-customer Idempotency-Key dedup for mutating ops.
-----------------------------------------------------------------------------
CREATE TABLE customer_idempotency (
    customer_user_id     UUID            NOT NULL,
    idempotency_key      VARCHAR(64)     NOT NULL,
    operation            VARCHAR(40)     NOT NULL,
    request_hash         VARCHAR(64)     NOT NULL,
    response_status      INT             NOT NULL,
    response_body        TEXT            NOT NULL,
    created_at           TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    PRIMARY KEY (customer_user_id, idempotency_key)
);
CREATE INDEX idx_cust_idem_created ON customer_idempotency (created_at);
