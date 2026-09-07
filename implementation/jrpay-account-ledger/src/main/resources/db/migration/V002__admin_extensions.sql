-- Phase 2I — A1 admin controller schema extensions.
-- Adds PII + admin-metadata columns to accounts, a transactions projection
-- (for UC-G35 §6.2 transaction list), the PII-reveal log + pending-approvals
-- tables (UC-G36 §6 + Phase 1 FS.10), and an admin-idempotency registry
-- (jrpay-idempotency-key-required Spectral rule).

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-----------------------------------------------------------------------------
-- accounts: PII fields + freeze metadata + customer binding
-----------------------------------------------------------------------------
ALTER TABLE accounts
    ADD COLUMN customer_id           VARCHAR(36),
    ADD COLUMN account_type          VARCHAR(16) NOT NULL DEFAULT 'CHECKING'
                  CHECK (account_type IN ('CHECKING','SAVINGS','CREDIT')),
    ADD COLUMN iban                  VARCHAR(34),
    ADD COLUMN bic                   VARCHAR(11),
    ADD COLUMN holder_address        VARCHAR(500),
    ADD COLUMN fodselsnummer         VARCHAR(11),
    ADD COLUMN opened_at             TIMESTAMPTZ,
    ADD COLUMN closed_at             TIMESTAMPTZ,
    ADD COLUMN freeze_reason         VARCHAR(40),
    ADD COLUMN freeze_reference      VARCHAR(140),
    ADD COLUMN freeze_applied_at     TIMESTAMPTZ,
    ADD COLUMN freeze_applied_by     VARCHAR(160),
    ADD COLUMN freeze_notes_summary  VARCHAR(140);

UPDATE accounts SET opened_at = created_at WHERE opened_at IS NULL;
ALTER TABLE accounts ALTER COLUMN opened_at SET NOT NULL;

CREATE INDEX idx_accounts_customer       ON accounts (customer_id)       WHERE customer_id IS NOT NULL;
CREATE UNIQUE INDEX idx_accounts_iban    ON accounts (iban)              WHERE iban IS NOT NULL;
CREATE INDEX idx_accounts_fodselsnummer  ON accounts (fodselsnummer)     WHERE fodselsnummer IS NOT NULL;
CREATE INDEX idx_accounts_holder_trgm    ON accounts USING gin (holder_name gin_trgm_ops);
CREATE INDEX idx_accounts_status         ON accounts (status);

-----------------------------------------------------------------------------
-- account_transactions: read-side projection of settled / rejected legs
-- The existing settlement / fraud / clearing events feed this table; the
-- admin transaction list (UC-G35 §6.2) reads it directly.
-----------------------------------------------------------------------------
CREATE TABLE account_transactions (
    transaction_id      VARCHAR(36)     NOT NULL,
    account_id          VARCHAR(34)     NOT NULL REFERENCES accounts(account_id),
    direction           VARCHAR(6)      NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount              NUMERIC(18,2)   NOT NULL,
    currency            VARCHAR(3)      NOT NULL,
    status              VARCHAR(16)     NOT NULL,
    counterparty_name   VARCHAR(140),
    counterparty_iban   VARCHAR(34),
    reference           VARCHAR(140),
    rejection_reason    VARCHAR(40),
    posted_at           TIMESTAMPTZ     NOT NULL,
    PRIMARY KEY (account_id, transaction_id),
    CONSTRAINT chk_acc_tx_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_acc_tx_account_posted ON account_transactions (account_id, posted_at DESC);
CREATE INDEX idx_acc_tx_status         ON account_transactions (status);

-----------------------------------------------------------------------------
-- pii_reveal_log: forensic record per UC-G36 / GUI-SEC-15
-----------------------------------------------------------------------------
CREATE TABLE pii_reveal_log (
    reveal_id                UUID            PRIMARY KEY,
    account_id               VARCHAR(34)     NOT NULL REFERENCES accounts(account_id),
    operator_id              UUID            NOT NULL,
    customer_id              UUID,
    field                    VARCHAR(20)     NOT NULL CHECK (field IN ('holderName','address','fodselsnummer')),
    revealed_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expires_at               TIMESTAMPTZ     NOT NULL,
    extension_count          INT             NOT NULL DEFAULT 0
                             CHECK (extension_count BETWEEN 0 AND 3),
    justification_notes_hash VARCHAR(64)     NOT NULL,
    notes_summary            VARCHAR(140)    NOT NULL,
    audit_event_id           VARCHAR(26)     NOT NULL,
    sca_session_id           UUID,
    second_approval_ref      VARCHAR(64)
);

CREATE INDEX idx_pii_reveal_operator_window  ON pii_reveal_log (operator_id, account_id, revealed_at DESC);
CREATE INDEX idx_pii_reveal_account_recent   ON pii_reveal_log (account_id, revealed_at DESC);

-----------------------------------------------------------------------------
-- pii_pending_approvals: rate-limit-triggered SUPERVISOR approval queue
-----------------------------------------------------------------------------
CREATE TABLE pii_pending_approvals (
    approval_ref             VARCHAR(64)     PRIMARY KEY,
    account_id               VARCHAR(34)     NOT NULL REFERENCES accounts(account_id),
    operator_id              UUID            NOT NULL,
    field                    VARCHAR(20)     NOT NULL CHECK (field IN ('holderName','address','fodselsnummer')),
    justification_notes_hash VARCHAR(64)     NOT NULL,
    notes_summary            VARCHAR(140)    NOT NULL,
    requested_at             TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    expires_at               TIMESTAMPTZ     NOT NULL,
    approved_at              TIMESTAMPTZ,
    approved_by              UUID,
    consumed_at              TIMESTAMPTZ
);

CREATE INDEX idx_pending_approvals_operator ON pii_pending_approvals (operator_id, requested_at DESC);
CREATE INDEX idx_pending_approvals_account  ON pii_pending_approvals (account_id, requested_at DESC);

-----------------------------------------------------------------------------
-- admin_idempotency: per-operator Idempotency-Key dedup for mutating endpoints
-----------------------------------------------------------------------------
CREATE TABLE admin_idempotency (
    operator_id      UUID            NOT NULL,
    idempotency_key  VARCHAR(64)     NOT NULL,
    operation        VARCHAR(40)     NOT NULL,
    request_hash     VARCHAR(64)     NOT NULL,
    response_status  INT             NOT NULL,
    response_body    TEXT            NOT NULL,
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    PRIMARY KEY (operator_id, idempotency_key)
);
CREATE INDEX idx_admin_idempotency_created ON admin_idempotency (created_at);
