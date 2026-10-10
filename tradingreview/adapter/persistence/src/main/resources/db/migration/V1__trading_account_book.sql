-- Existing installations predate Flyway. The member definition matches the existing JPA mapping on a fresh database.
CREATE TABLE IF NOT EXISTS members (
    id BIGINT PRIMARY KEY,
    role VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE trading_accounts (
    id BIGINT PRIMARY KEY,
    owner_member_id BIGINT NOT NULL REFERENCES members(id),
    venue VARCHAR(20) NOT NULL CHECK (venue = 'BINANCE'),
    display_name VARCHAR(100) NOT NULL CHECK (length(display_name) > 0),
    status VARCHAR(30) NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETION_PENDING', 'DELETION_FAILED')),
    deletion_generation BIGINT NOT NULL DEFAULT 0 CHECK (deletion_generation >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE (id, owner_member_id)
);
CREATE INDEX trading_accounts_owner_created_idx ON trading_accounts(owner_member_id, created_at DESC, id DESC);
CREATE INDEX trading_accounts_status_idx ON trading_accounts(status);

CREATE TABLE trading_books (
    id BIGINT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    owner_member_id BIGINT NOT NULL,
    venue VARCHAR(20) NOT NULL CHECK (venue = 'BINANCE'),
    display_name VARCHAR(100) NOT NULL CHECK (length(display_name) > 0),
    product_family VARCHAR(30) NOT NULL CHECK (product_family = 'LINEAR_PERPETUAL'),
    position_mode VARCHAR(20) NOT NULL CHECK (position_mode = 'ONE_WAY'),
    settlement_asset VARCHAR(10) NOT NULL CHECK (settlement_asset = 'USDT'),
    review_timezone VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETION_PENDING', 'DELETION_FAILED')),
    deletion_generation BIGINT NOT NULL DEFAULT 0 CHECK (deletion_generation >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT trading_books_account_owner_fk FOREIGN KEY (account_id, owner_member_id)
        REFERENCES trading_accounts(id, owner_member_id),
    UNIQUE (id, owner_member_id)
);
CREATE INDEX trading_books_account_created_idx ON trading_books(account_id, created_at DESC, id DESC);
CREATE INDEX trading_books_owner_idx ON trading_books(owner_member_id, id);
CREATE INDEX trading_books_status_idx ON trading_books(status);

CREATE TABLE trading_idempotency (
    owner_member_id BIGINT NOT NULL REFERENCES members(id),
    operation_id VARCHAR(80) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    payload_fingerprint CHAR(64) NOT NULL,
    resource_id BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_member_id, operation_id, request_key)
);

CREATE TABLE trading_deletion_requests (
    id BIGINT PRIMARY KEY,
    owner_member_id BIGINT NOT NULL REFERENCES members(id),
    target_type VARCHAR(30) NOT NULL CHECK (target_type IN ('TRADING_ACCOUNT', 'TRADING_BOOK')),
    target_id BIGINT NOT NULL,
    requested_scope VARCHAR(30) NOT NULL,
    state VARCHAR(30) NOT NULL CHECK (state IN ('REQUESTED', 'CANCELLING_JOBS', 'PURGING_PRIMARY', 'PURGING_OBJECTS', 'COMPLETED', 'FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    failure_code VARCHAR(80),
    deletion_generation BIGINT NOT NULL CHECK (deletion_generation > 0),
    account_count BIGINT NOT NULL DEFAULT 0,
    book_count BIGINT NOT NULL DEFAULT 0,
    revoked_at TIMESTAMPTZ NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    backup_purge_due_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    UNIQUE (owner_member_id, target_type, target_id)
);
CREATE INDEX trading_deletion_requests_owner_idx ON trading_deletion_requests(owner_member_id, requested_at DESC);
CREATE INDEX trading_deletion_requests_state_idx ON trading_deletion_requests(state);

CREATE TABLE trading_deleted_resources (
    owner_member_id BIGINT NOT NULL REFERENCES members(id),
    target_type VARCHAR(30) NOT NULL,
    target_id BIGINT NOT NULL,
    deletion_request_id BIGINT NOT NULL REFERENCES trading_deletion_requests(id),
    PRIMARY KEY (owner_member_id, target_type, target_id)
);
