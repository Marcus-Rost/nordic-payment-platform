CREATE TABLE consents (
    consent_id         VARCHAR(36)   PRIMARY KEY,
    tpp_id             VARCHAR(50)   NOT NULL,
    account_holder_id  VARCHAR(50)   NOT NULL,
    granted_scopes     VARCHAR(255)  NOT NULL,
    granted_accounts   VARCHAR(500),
    status             VARCHAR(10)   NOT NULL CHECK (status IN ('ACTIVE','REVOKED','EXPIRED')),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at         TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_consents_holder ON consents (account_holder_id, status);

CREATE TABLE sca_sessions (
    session_id         VARCHAR(36)   PRIMARY KEY,
    account_holder_id  VARCHAR(50)   NOT NULL,
    linked_amount      NUMERIC(18,2),
    linked_creditor    VARCHAR(140),
    status             VARCHAR(15)   NOT NULL CHECK (status IN ('PENDING','COMPLETED','LOCKED','CANCELLED','EXPIRED')),
    failed_attempts    INTEGER       NOT NULL DEFAULT 0,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_until       TIMESTAMP WITH TIME ZONE,
    completed_at       TIMESTAMP WITH TIME ZONE
);
