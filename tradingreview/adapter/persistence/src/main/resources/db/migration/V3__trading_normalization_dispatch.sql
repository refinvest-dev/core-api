ALTER TABLE trading_import_sessions DROP CONSTRAINT trading_import_sessions_status_check;
ALTER TABLE trading_import_sessions ADD CONSTRAINT trading_import_sessions_status_check
    CHECK (status IN ('RECEIVED','VALIDATING','ACCEPTED','REJECTED','PROCESSING_FAILED'));
ALTER TABLE trading_import_sessions ADD COLUMN validation_started_at TIMESTAMPTZ;
ALTER TABLE trading_import_sessions ADD COLUMN active_normalization_run_id UUID;
ALTER TABLE trading_import_sessions ADD COLUMN failure_code VARCHAR(80);
ALTER TABLE trading_import_sessions ADD COLUMN failure_retryable BOOLEAN;
ALTER TABLE trading_import_sessions ADD COLUMN failure_user_action VARCHAR(30);
ALTER TABLE trading_import_sessions ADD COLUMN failure_occurred_at TIMESTAMPTZ;

CREATE TABLE trading_normalization_runs (
    id UUID PRIMARY KEY,
    retry_of_run_id UUID REFERENCES trading_normalization_runs(id),
    import_session_id BIGINT NOT NULL REFERENCES trading_import_sessions(id),
    trading_book_id BIGINT NOT NULL REFERENCES trading_books(id),
    owner_member_id BIGINT NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    logical_key CHAR(64) NOT NULL,
    input_json JSONB NOT NULL,
    target_version_set_json JSONB NOT NULL,
    instrument_terms_json JSONB NOT NULL,
    data_policy_version VARCHAR(100) NOT NULL,
    retry_policy_version VARCHAR(100) NOT NULL,
    retry_policy_json JSONB NOT NULL,
    deletion_generation BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL CHECK (status IN ('DISPATCH_PENDING','SUBMITTED','RUNNING','RESULT_READY','PUBLISHING','PUBLISHED','RETRY_WAIT','TERMINAL_FAILURE','OBSOLETE')),
    current_attempt INTEGER NOT NULL CHECK (current_attempt >= 1),
    attempt_token UUID NOT NULL,
    compute_job_id VARCHAR(160),
    last_failure_code VARCHAR(80),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX trading_normalization_active_session_uq ON trading_normalization_runs(import_session_id)
    WHERE status IN ('DISPATCH_PENDING','SUBMITTED','RUNNING','RESULT_READY','PUBLISHING','RETRY_WAIT');
CREATE INDEX trading_normalization_logical_idx ON trading_normalization_runs(import_session_id, input_fingerprint, logical_key);

CREATE TABLE trading_normalization_attempts (
    run_id UUID NOT NULL REFERENCES trading_normalization_runs(id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number >= 1),
    attempt_token UUID NOT NULL UNIQUE,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    result_category VARCHAR(30),
    safe_failure_code VARCHAR(80),
    compute_job_id VARCHAR(160),
    retry_at TIMESTAMPTZ,
    PRIMARY KEY (run_id, attempt_number)
);
CREATE TABLE trading_normalization_dispatches (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES trading_normalization_runs(id),
    retry_of_dispatch_id UUID REFERENCES trading_normalization_dispatches(id),
    compute_key CHAR(64) NOT NULL UNIQUE,
    status VARCHAR(30) NOT NULL CHECK (status IN ('PENDING','SUBMITTED','RESULT_READY','TERMINAL_FAILURE','OBSOLETE')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_owner UUID,
    lease_expires_at TIMESTAMPTZ,
    last_failure_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX trading_normalization_active_dispatch_uq ON trading_normalization_dispatches(run_id)
    WHERE status IN ('PENDING','SUBMITTED');
CREATE INDEX trading_normalization_dispatch_due_idx ON trading_normalization_dispatches(status, next_attempt_at, lease_expires_at);
CREATE TABLE trading_normalization_audit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES trading_normalization_runs(id),
    event VARCHAR(40) NOT NULL,
    safe_failure_code VARCHAR(80),
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE trading_artifact_read_grants (
    token_hash CHAR(64) PRIMARY KEY,
    artifact_id BIGINT NOT NULL REFERENCES trading_source_artifacts(id),
    logical_job_id UUID NOT NULL,
    session_id BIGINT NOT NULL REFERENCES trading_import_sessions(id),
    artifact_role VARCHAR(30) NOT NULL,
    raw_sha256 CHAR(64) NOT NULL,
    attempt_token UUID NOT NULL,
    deletion_generation BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX trading_artifact_read_grant_expiry_idx ON trading_artifact_read_grants(expires_at);
