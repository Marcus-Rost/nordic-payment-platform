-- Account projection table (derived from events, rebuildable)
CREATE TABLE accounts (
    account_id      VARCHAR(34)     PRIMARY KEY,
    holder_name     VARCHAR(140)    NOT NULL,
    currency        VARCHAR(3)      NOT NULL,
    booked_balance  NUMERIC(18,2)   NOT NULL DEFAULT 0,
    reserved_amount NUMERIC(18,2)   NOT NULL DEFAULT 0,
    status          VARCHAR(10)     NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,

    -- Defense-in-depth: balance can never go negative
    CONSTRAINT chk_booked_balance_non_negative CHECK (booked_balance >= 0),
    CONSTRAINT chk_reserved_non_negative CHECK (reserved_amount >= 0),
    CONSTRAINT chk_available_non_negative CHECK (booked_balance - reserved_amount >= 0)
);

-- Reservation table
CREATE TABLE reservations (
    reservation_id  VARCHAR(64)     PRIMARY KEY,
    account_id      VARCHAR(34)     NOT NULL REFERENCES accounts(account_id),
    transaction_id  VARCHAR(36)     NOT NULL,
    amount          NUMERIC(18,2)   NOT NULL,
    currency        VARCHAR(3)      NOT NULL,
    status          VARCHAR(10)     NOT NULL DEFAULT 'RESERVED'
                    CHECK (status IN ('RESERVED', 'CONFIRMED', 'RELEASED')),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at     TIMESTAMP WITH TIME ZONE,

    CONSTRAINT chk_reservation_amount_positive CHECK (amount > 0)
);

CREATE INDEX idx_reservations_account_status ON reservations (account_id, status);
CREATE INDEX idx_reservations_status_created ON reservations (status, created_at);

-- Processed event IDs for idempotency (consumer dedup)
CREATE TABLE processed_event_ids (
    event_id        VARCHAR(36)     PRIMARY KEY,
    processed_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
