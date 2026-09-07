CREATE TABLE clearing_records (
    transaction_id      VARCHAR(36)     PRIMARY KEY,
    debtor_iban         VARCHAR(34)     NOT NULL,
    creditor_iban       VARCHAR(34)     NOT NULL,
    creditor_name       VARCHAR(140),
    amount              NUMERIC(18,2)   NOT NULL,
    currency            VARCHAR(3)      NOT NULL,
    path_type           VARCHAR(10)     NOT NULL CHECK (path_type IN ('INSTANT', 'BATCH')),
    status              VARCHAR(25)     NOT NULL CHECK (status IN (
                            'RECEIVED', 'AWAITING_RESERVATION', 'AWAITING_FRAUD',
                            'SETTLING', 'SETTLED', 'REJECTED')),
    rejection_reason    VARCHAR(50),
    reservation_id      VARCHAR(64),
    vop_result          VARCHAR(20),
    vop_payer_override  BOOLEAN,
    fraud_score         DOUBLE PRECISION,
    sanctions_result    VARCHAR(20),
    duplicate_override  BOOLEAN DEFAULT FALSE,
    received_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    settled_at          TIMESTAMP WITH TIME ZONE,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_clearing_status ON clearing_records (status);
CREATE INDEX idx_clearing_status_updated ON clearing_records (status, updated_at);
