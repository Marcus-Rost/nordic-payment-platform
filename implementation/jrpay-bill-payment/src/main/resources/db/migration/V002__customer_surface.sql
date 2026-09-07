-- Phase 2J — A3 customer surface: R2P management, AvtaleGiro mandates,
-- trusted beneficiaries. Backs `customer-r2p.yaml`, `customer-mandates.yaml`,
-- `customer-beneficiaries.yaml`.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-----------------------------------------------------------------------------
-- payment_requests: extend with customer scoping, merchant metadata, and the
-- amendment-flow / linked-payment columns the GUI surface needs.
-----------------------------------------------------------------------------
ALTER TABLE payment_requests
    ADD COLUMN customer_user_id        UUID,
    ADD COLUMN merchant_name           VARCHAR(140),
    ADD COLUMN merchant_verified       BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN merchant_logo_url       VARCHAR(500),
    ADD COLUMN amendment_allowed       BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN amendment_proposed_at   TIMESTAMPTZ,
    ADD COLUMN amendment_proposed_amount NUMERIC(18,2),
    ADD COLUMN amendment_proposed_due_date DATE,
    ADD COLUMN amendment_reason        VARCHAR(280),
    ADD COLUMN amendment_rejected      BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN replaced_by_request_id  VARCHAR(36),
    ADD COLUMN replaces_request_id     VARCHAR(36),
    ADD COLUMN scheduled_payment_id    VARCHAR(36),
    ADD COLUMN payment_id              VARCHAR(36),
    ADD COLUMN decline_note            VARCHAR(280),
    ADD COLUMN requested_execution_mode VARCHAR(16),
    ADD COLUMN invoice_pdf_url         VARCHAR(500),
    ADD COLUMN invoice_pdf_expires_at  TIMESTAMPTZ;

-- Existing rows: backfill merchant_name from creditor_name (best-effort).
UPDATE payment_requests SET merchant_name = creditor_name WHERE merchant_name IS NULL;
ALTER TABLE payment_requests ALTER COLUMN merchant_name SET NOT NULL;

-- Status check needs to admit the new GUI-visible states.
ALTER TABLE payment_requests DROP CONSTRAINT IF EXISTS payment_requests_status_check;
ALTER TABLE payment_requests ADD CONSTRAINT payment_requests_status_check
    CHECK (status IN (
        'PENDING','ACCEPTED','DECLINED','DEFERRED','DEFER_FAILED','AMENDED',
        'AMENDMENT_REQUESTED','REPLACED','CANCELLED_BY_MERCHANT',
        'PAYMENT_INITIATED','SETTLED','REJECTED','EXPIRED',
        'PAYMENT_HELD','RETURN_IN_PROGRESS','PAYMENT_RETURNED'
    ));

CREATE INDEX idx_pr_customer_status   ON payment_requests (customer_user_id, status);
CREATE INDEX idx_pr_customer_due      ON payment_requests (customer_user_id, due_date);
CREATE INDEX idx_pr_merchant_trgm     ON payment_requests USING gin (merchant_name gin_trgm_ops);

-----------------------------------------------------------------------------
-- r2p_history: ordered transition trail per request (UC-G14 §6 history).
-----------------------------------------------------------------------------
CREATE TABLE r2p_history (
    history_id    BIGSERIAL    PRIMARY KEY,
    request_id    VARCHAR(36)  NOT NULL REFERENCES payment_requests(request_id) ON DELETE CASCADE,
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    status        VARCHAR(32)  NOT NULL,
    actor         VARCHAR(16)  NOT NULL CHECK (actor IN ('CUSTOMER','MERCHANT','SYSTEM')),
    note          VARCHAR(280)
);
CREATE INDEX idx_r2p_history_request ON r2p_history (request_id, occurred_at DESC);

-----------------------------------------------------------------------------
-- mandates: replace with the richer GUI-aligned schema.
-- The pre-existing rows (none in production yet — service is fresh) are
-- migrated by recreating the table; column compat is preserved where used
-- by the existing R2P mandate-auto-accept logic.
-----------------------------------------------------------------------------
ALTER TABLE mandates
    ADD COLUMN customer_user_id   UUID,
    ADD COLUMN debtor_account_id  VARCHAR(34),
    ADD COLUMN max_amount         NUMERIC(18,2),
    ADD COLUMN frequency          VARCHAR(16),
    ADD COLUMN start_date         DATE,
    ADD COLUMN end_date           DATE,
    ADD COLUMN notify_before_debit BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN updated_at         TIMESTAMPTZ,
    ADD COLUMN revoked_at         TIMESTAMPTZ,
    ADD COLUMN paused_at          TIMESTAMPTZ,
    ADD COLUMN resume_at          TIMESTAMPTZ,
    ADD COLUMN sca_session_id     UUID,
    ADD COLUMN dl_hash            VARCHAR(64);

UPDATE mandates SET max_amount = max_single_amount WHERE max_amount IS NULL;
UPDATE mandates SET updated_at = created_at WHERE updated_at IS NULL;
ALTER TABLE mandates ALTER COLUMN updated_at SET NOT NULL;

-- Status check + length: extend column from VARCHAR(10) to VARCHAR(16) so
-- 'PENDING_SCA' (11 chars) fits, and admit the GUI-aligned status values.
ALTER TABLE mandates ALTER COLUMN status TYPE VARCHAR(16);
ALTER TABLE mandates DROP CONSTRAINT IF EXISTS mandates_status_check;
ALTER TABLE mandates ADD CONSTRAINT mandates_status_check
    CHECK (status IN ('PENDING_SCA','ACTIVE','PAUSED','REVOKED','EXPIRED'));

CREATE INDEX idx_mandates_customer  ON mandates (customer_user_id, status);
CREATE INDEX idx_mandates_merchant  ON mandates (merchant_id);

-----------------------------------------------------------------------------
-- mandate_executions: per-debit history + linkage to the ledger.
-----------------------------------------------------------------------------
CREATE TABLE mandate_executions (
    execution_id     UUID            PRIMARY KEY,
    mandate_id       VARCHAR(36)     NOT NULL REFERENCES mandates(mandate_id) ON DELETE CASCADE,
    amount           NUMERIC(18,2)   NOT NULL,
    currency         VARCHAR(3)      NOT NULL,
    status           VARCHAR(32)     NOT NULL,
    attempted_at     TIMESTAMPTZ     NOT NULL,
    settled_at       TIMESTAMPTZ,
    transaction_id   VARCHAR(36),
    rejection_reason VARCHAR(40),
    invoice_reference VARCHAR(140),
    CONSTRAINT chk_mandate_exec_amount_positive CHECK (amount > 0)
);
CREATE INDEX idx_mandate_exec_mandate_attempted ON mandate_executions (mandate_id, attempted_at DESC);

-----------------------------------------------------------------------------
-- merchants: cached AvtaleGiro registry projection. Refreshed nightly from
-- Bits in production; seeded with demo entries for development.
-----------------------------------------------------------------------------
CREATE TABLE merchants (
    merchant_id           VARCHAR(64)  PRIMARY KEY,
    avtale_id             VARCHAR(7)   NOT NULL UNIQUE,
    display_name          VARCHAR(140) NOT NULL,
    trading_name          VARCHAR(140),
    iban                  VARCHAR(34)  NOT NULL,
    bic                   VARCHAR(11),
    logo_url              VARCHAR(500),
    accepted_frequencies  TEXT         NOT NULL,            -- comma-separated MandateFrequency
    registry_updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_avtale_id_seven_digits CHECK (avtale_id ~ '^[0-9]{7}$')
);
CREATE INDEX idx_merchants_display_trgm ON merchants USING gin (display_name gin_trgm_ops);

-- Seed a small demo set (development only — production registers via Bits).
INSERT INTO merchants (merchant_id, avtale_id, display_name, trading_name, iban, bic,
                        accepted_frequencies, registry_updated_at)
VALUES
    ('m-bits-0001234', '0001234', 'Hafslund Strøm AS', 'Hafslund Strøm',
     'NO9386011117947', 'DNBANOKKXXX', 'MONTHLY,ON_INVOICE', NOW()),
    ('m-bits-0005678', '0005678', 'Telenor Norge AS', 'Telenor',
     'NO0820000000123', 'DNBANOKKXXX', 'MONTHLY', NOW()),
    ('m-bits-0009999', '0009999', 'Oslo Kommune Vann og Avløp', 'Oslo Vann',
     'NO9300000000010', 'DNBANOKKXXX', 'QUARTERLY,ON_INVOICE', NOW());

-----------------------------------------------------------------------------
-- beneficiaries: trusted-payee address book per customer.
-----------------------------------------------------------------------------
CREATE TABLE beneficiaries (
    beneficiary_id       VARCHAR(64)  PRIMARY KEY,
    customer_user_id     UUID         NOT NULL,
    name                 VARCHAR(140) NOT NULL,
    iban                 VARCHAR(34)  NOT NULL,
    bic                  VARCHAR(11),
    bank_name            VARCHAR(140),
    currency             VARCHAR(3)   NOT NULL,
    notes                VARCHAR(140),
    status               VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING_SCA','ACTIVE')),
    vop_result_at_creation       VARCHAR(16),
    vop_payer_override_at_creation BOOLEAN,
    sca_session_id       UUID,
    dl_hash              VARCHAR(64),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    activated_at         TIMESTAMPTZ,
    last_paid_at         TIMESTAMPTZ,
    total_ytd            NUMERIC(18,2) NOT NULL DEFAULT 0,
    payments_count_ytd   INTEGER       NOT NULL DEFAULT 0,
    CONSTRAINT uq_customer_iban UNIQUE (customer_user_id, iban)
);
CREATE INDEX idx_beneficiaries_customer        ON beneficiaries (customer_user_id, status);
CREATE INDEX idx_beneficiaries_name_trgm       ON beneficiaries USING gin (name gin_trgm_ops);
CREATE INDEX idx_beneficiaries_notes_trgm      ON beneficiaries USING gin (coalesce(notes,'') gin_trgm_ops);

-----------------------------------------------------------------------------
-- customer_idempotency: per-customer Idempotency-Key dedup for mutating ops.
-----------------------------------------------------------------------------
CREATE TABLE customer_idempotency (
    customer_user_id  UUID         NOT NULL,
    idempotency_key   VARCHAR(64)  NOT NULL,
    operation         VARCHAR(40)  NOT NULL,
    request_hash      VARCHAR(64)  NOT NULL,
    response_status   INT          NOT NULL,
    response_body     TEXT         NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (customer_user_id, idempotency_key)
);
CREATE INDEX idx_customer_idempotency_created ON customer_idempotency (created_at);
