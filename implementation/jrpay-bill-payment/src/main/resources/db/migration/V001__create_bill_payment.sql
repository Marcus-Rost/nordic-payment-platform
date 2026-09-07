CREATE TABLE payment_requests (
    request_id       VARCHAR(36)   PRIMARY KEY,
    merchant_id      VARCHAR(50)   NOT NULL,
    creditor_iban    VARCHAR(34)   NOT NULL,
    creditor_name    VARCHAR(140)  NOT NULL,
    debtor_iban      VARCHAR(34),
    amount           NUMERIC(18,2) NOT NULL,
    currency         VARCHAR(3)    NOT NULL,
    reference        VARCHAR(35),
    description      VARCHAR(140),
    due_date         DATE,
    status           VARCHAR(20)   NOT NULL CHECK (status IN (
                        'PENDING','ACCEPTED','DECLINED','DEFERRED','AMENDED',
                        'PAYMENT_INITIATED','SETTLED','REJECTED','EXPIRED',
                        'PAYMENT_HELD','RETURN_IN_PROGRESS','PAYMENT_RETURNED')),
    transaction_id   VARCHAR(36),
    mandate_id       VARCHAR(36),
    decline_reason   VARCHAR(50),
    decline_message  VARCHAR(255),
    idempotency_key  VARCHAR(64)   UNIQUE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_pr_merchant_status ON payment_requests (merchant_id, status);
CREATE INDEX idx_pr_debtor_status ON payment_requests (debtor_iban, status);

CREATE TABLE mandates (
    mandate_id         VARCHAR(36)   PRIMARY KEY,
    merchant_id        VARCHAR(50)   NOT NULL,
    debtor_iban        VARCHAR(34)   NOT NULL,
    max_single_amount  NUMERIC(18,2) NOT NULL,
    currency           VARCHAR(3)    NOT NULL,
    status             VARCHAR(10)   NOT NULL CHECK (status IN ('ACTIVE','REVOKED')),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL
);
