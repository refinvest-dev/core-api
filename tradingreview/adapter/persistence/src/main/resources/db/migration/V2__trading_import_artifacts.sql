ALTER TABLE trading_deletion_requests DROP CONSTRAINT trading_deletion_requests_target_type_check;
ALTER TABLE trading_deletion_requests ADD CONSTRAINT trading_deletion_requests_target_type_check
    CHECK (target_type IN ('TRADING_ACCOUNT', 'TRADING_BOOK', 'TRADING_IMPORT_SESSION', 'TRADING_SOURCE_ARTIFACT'));
ALTER TABLE trading_deletion_requests ADD COLUMN import_session_count BIGINT NOT NULL DEFAULT 0;
ALTER TABLE trading_deletion_requests ADD COLUMN artifact_count BIGINT NOT NULL DEFAULT 0;

CREATE TABLE trading_import_sessions (
    id BIGINT PRIMARY KEY,
    trading_book_id BIGINT NOT NULL,
    owner_member_id BIGINT NOT NULL,
    source_timezone VARCHAR(100) NOT NULL,
    review_timezone VARCHAR(100) NOT NULL,
    declared_position_mode VARCHAR(20) NOT NULL CHECK (declared_position_mode = 'ONE_WAY'),
    data_policy_version VARCHAR(100) NOT NULL,
    retention_policy VARCHAR(100) NOT NULL,
    masking_policy_version VARCHAR(100) NOT NULL,
    evidence_retention VARCHAR(30) NOT NULL CHECK (evidence_retention = 'BOOK_LIFECYCLE'),
    deletion_policy_version VARCHAR(100) NOT NULL,
    version_set_json TEXT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('RECEIVED','VALIDATING','ACCEPTED','REJECTED')),
    deletion_generation BIGINT NOT NULL DEFAULT 0 CHECK (deletion_generation >= 0),
    deletion_pending BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT trading_import_book_owner_fk FOREIGN KEY (trading_book_id, owner_member_id)
        REFERENCES trading_books(id, owner_member_id),
    UNIQUE (id, owner_member_id)
);
CREATE INDEX trading_import_book_created_idx ON trading_import_sessions(trading_book_id, created_at DESC, id DESC);
CREATE INDEX trading_import_owner_idx ON trading_import_sessions(owner_member_id, id);
CREATE INDEX trading_import_status_idx ON trading_import_sessions(status, deletion_pending);

CREATE TABLE trading_source_artifacts (
    id BIGINT PRIMARY KEY,
    import_session_id BIGINT NOT NULL REFERENCES trading_import_sessions(id),
    artifact_role VARCHAR(30) NOT NULL CHECK (artifact_role IN ('TRADE_HISTORY','POSITION_HISTORY')),
    filename VARCHAR(255),
    content_type VARCHAR(50) NOT NULL CHECK (content_type IN ('text/csv','application/vnd.ms-excel')),
    byte_size BIGINT NOT NULL CHECK (byte_size BETWEEN 1 AND 52428800),
    content_sha256 CHAR(64) NOT NULL,
    dialect_candidate VARCHAR(80) NOT NULL,
    object_key VARCHAR(160) UNIQUE,
    wrapped_dek BYTEA,
    key_version VARCHAR(80),
    upload_status VARCHAR(20) NOT NULL CHECK (upload_status = 'UPLOADED'),
    retention_status VARCHAR(30) NOT NULL CHECK (retention_status IN
        ('ACTIVE','RETENTION_SCHEDULED','DELETION_PENDING','DELETED','DELETION_FAILED')),
    uploaded_at TIMESTAMPTZ NOT NULL,
    raw_expires_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    deletion_failure VARCHAR(80),
    deletion_attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (deletion_attempt_count >= 0),
    deletion_claim_until TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT trading_artifact_key_consistency CHECK (
        (retention_status = 'DELETED' AND object_key IS NULL AND wrapped_dek IS NULL)
        OR (retention_status <> 'DELETED' AND object_key IS NOT NULL AND wrapped_dek IS NOT NULL)),
    UNIQUE (import_session_id, artifact_role)
);
CREATE INDEX trading_artifact_retention_due_idx ON trading_source_artifacts(raw_expires_at, id)
    WHERE retention_status IN ('ACTIVE','RETENTION_SCHEDULED');
CREATE INDEX trading_artifact_deletion_pending_idx ON trading_source_artifacts(retention_status, id)
    WHERE retention_status IN ('DELETION_PENDING','DELETION_FAILED');
CREATE INDEX trading_artifact_session_idx ON trading_source_artifacts(import_session_id, id);
