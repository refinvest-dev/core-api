ALTER TABLE trading_deletion_requests ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0);

CREATE TABLE trading_deletion_checkpoints (
    request_id BIGINT NOT NULL REFERENCES trading_deletion_requests(id),
    component VARCHAR(30) NOT NULL CHECK (component IN
        ('RAW_OBJECTS','RAW_KEYS','TEMPORARY_COPIES','PRIMARY_DATA','DERIVED_RESULTS','COMPUTE_RUNTIME')),
    verified_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (request_id, component)
);

-- Opaque locators survive primary purge and never contain a key or source payload.
CREATE TABLE trading_deletion_object_tasks (
    request_id BIGINT NOT NULL REFERENCES trading_deletion_requests(id),
    artifact_id BIGINT NOT NULL,
    object_key TEXT,
    verified_at TIMESTAMPTZ,
    PRIMARY KEY (request_id, artifact_id)
);

-- Historical COMPLETED is preserved, but is never used to manufacture absence evidence.

CREATE TABLE trading_deletion_worksets (
    request_id BIGINT PRIMARY KEY REFERENCES trading_deletion_requests(id),
    captured_at TIMESTAMPTZ NOT NULL
);

-- Payload-free external cleanup identities must survive primary-row removal.
CREATE TABLE trading_deletion_session_tasks (
    request_id BIGINT NOT NULL REFERENCES trading_deletion_requests(id),
    session_id BIGINT NOT NULL,
    book_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    PRIMARY KEY (request_id, session_id)
);
CREATE INDEX trading_deletion_session_scope_idx ON trading_deletion_session_tasks(account_id,book_id);

CREATE TABLE trading_deletion_runtime_tasks (
    request_id BIGINT NOT NULL REFERENCES trading_deletion_requests(id),
    run_id UUID NOT NULL,
    attempt_token UUID NOT NULL,
    compute_job_id VARCHAR(160),
    deletion_generation BIGINT NOT NULL,
    current_deletion_generation BIGINT NOT NULL,
    PRIMARY KEY (request_id, run_id, attempt_token)
);

CREATE TABLE trading_deletion_dispatch_tasks (
    request_id BIGINT NOT NULL REFERENCES trading_deletion_requests(id),
    dispatch_id UUID NOT NULL,
    run_id UUID NOT NULL,
    compute_key CHAR(64) NOT NULL,
    PRIMARY KEY (request_id, dispatch_id)
);

CREATE TABLE trading_deletion_revision_tasks (
    request_id BIGINT NOT NULL REFERENCES trading_deletion_requests(id),
    revision_id UUID NOT NULL,
    PRIMARY KEY (request_id, revision_id)
);
