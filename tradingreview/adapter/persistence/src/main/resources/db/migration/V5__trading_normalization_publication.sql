-- ADR-066 result transport/staging is private. Only a committed ledger revision is queryable.
ALTER TABLE trading_normalization_dispatches DROP CONSTRAINT trading_normalization_dispatches_status_check;
ALTER TABLE trading_normalization_dispatches ADD CONSTRAINT trading_normalization_dispatches_status_check
    CHECK (status IN ('PENDING','SUBMITTED','RESULT_READY','PUBLISHED','TERMINAL_FAILURE','OBSOLETE'));
ALTER TABLE trading_normalization_dispatches ADD COLUMN result_attempt_count INTEGER NOT NULL DEFAULT 0
    CHECK (result_attempt_count >= 0);
ALTER TABLE trading_normalization_dispatches ADD COLUMN poll_failure_count INTEGER NOT NULL DEFAULT 0
    CHECK (poll_failure_count >= 0);

CREATE TABLE trading_normalization_result_staging (
    run_id UUID PRIMARY KEY REFERENCES trading_normalization_runs(id),
    payload_id VARCHAR(160) NOT NULL,
    compute_job_id VARCHAR(160) NOT NULL,
    attempt_token UUID NOT NULL,
    deletion_generation BIGINT NOT NULL,
    transport_sha256 CHAR(64) NOT NULL,
    byte_size BIGINT NOT NULL CHECK (byte_size > 0),
    payload_expires_at TIMESTAMPTZ NOT NULL,
    result_hash CHAR(64) NOT NULL,
    result_schema_version VARCHAR(100) NOT NULL,
    result_hash_specification_version VARCHAR(100) NOT NULL,
    state VARCHAR(20) NOT NULL CHECK (state IN ('RECEIVING','VALIDATED','OBSOLETE','FAILED')),
    counts_json JSONB NOT NULL,
    section_digests_json JSONB NOT NULL,
    manifest_hash CHAR(64) NOT NULL,
    base_json JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    validated_at TIMESTAMPTZ,
    UNIQUE (run_id, result_hash)
);
CREATE INDEX trading_normalization_staging_cleanup_idx ON trading_normalization_result_staging(state, updated_at);

CREATE TABLE trading_normalization_staged_items (
    run_id UUID NOT NULL REFERENCES trading_normalization_result_staging(run_id) ON DELETE CASCADE,
    section_type VARCHAR(40) NOT NULL,
    item_identity TEXT NOT NULL,
    item_sha256 CHAR(64) NOT NULL,
    item_json JSONB NOT NULL,
    PRIMARY KEY (run_id, section_type, item_identity)
);
CREATE INDEX trading_normalization_staged_section_idx ON trading_normalization_staged_items(run_id, section_type);

CREATE TABLE trading_ledger_revisions (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL UNIQUE REFERENCES trading_normalization_runs(id),
    trading_book_id BIGINT NOT NULL REFERENCES trading_books(id),
    import_session_id BIGINT NOT NULL REFERENCES trading_import_sessions(id),
    revision_number BIGINT NOT NULL CHECK (revision_number >= 1),
    input_set_hash CHAR(64) NOT NULL,
    input_hash_specification_version VARCHAR(100) NOT NULL,
    input_identity_json JSONB NOT NULL,
    target_version_set_json JSONB NOT NULL,
    result_schema_version VARCHAR(100) NOT NULL,
    result_hash_specification_version VARCHAR(100) NOT NULL,
    result_hash CHAR(64) NOT NULL,
    ledger_content_hash CHAR(64) NOT NULL,
    reconciliation_manifest_hash CHAR(64) NOT NULL,
    reconciliation_manifest_json JSONB NOT NULL,
    coverage_json JSONB NOT NULL,
    capability_json JSONB NOT NULL,
    quality_report_json JSONB NOT NULL,
    counts_json JSONB NOT NULL,
    section_digests_json JSONB NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    UNIQUE (trading_book_id, revision_number),
    UNIQUE (trading_book_id, input_set_hash, target_version_set_json),
    UNIQUE (id, trading_book_id)
);
CREATE INDEX trading_ledger_revision_session_idx ON trading_ledger_revisions(import_session_id);

CREATE TABLE trading_source_evidence_snapshots (
    trading_book_id BIGINT NOT NULL REFERENCES trading_books(id),
    import_session_id BIGINT NOT NULL REFERENCES trading_import_sessions(id),
    snapshot_id TEXT NOT NULL,
    source_artifact_id BIGINT NOT NULL REFERENCES trading_source_artifacts(id),
    source_row_number BIGINT NOT NULL CHECK (source_row_number >= 1),
    evidence_schema_version VARCHAR(100) NOT NULL,
    masking_policy_version VARCHAR(100) NOT NULL,
    evidence_json JSONB NOT NULL,
    PRIMARY KEY (trading_book_id, snapshot_id),
    UNIQUE (source_artifact_id, source_row_number, evidence_schema_version)
);
CREATE TABLE trading_records (
    trading_book_id BIGINT NOT NULL REFERENCES trading_books(id),
    record_id TEXT NOT NULL,
    source_evidence_snapshot_id TEXT NOT NULL,
    record_hash CHAR(64) NOT NULL,
    record_json JSONB NOT NULL,
    PRIMARY KEY (trading_book_id, record_id),
    UNIQUE (trading_book_id, source_evidence_snapshot_id, record_hash),
    FOREIGN KEY (trading_book_id, source_evidence_snapshot_id)
        REFERENCES trading_source_evidence_snapshots(trading_book_id, snapshot_id)
);
CREATE TABLE trading_ledger_revision_records (
    revision_id UUID NOT NULL,
    trading_book_id BIGINT NOT NULL,
    record_id TEXT NOT NULL,
    semantic_order_key TEXT NOT NULL,
    PRIMARY KEY (revision_id, record_id),
    FOREIGN KEY (revision_id, trading_book_id) REFERENCES trading_ledger_revisions(id, trading_book_id),
    FOREIGN KEY (trading_book_id, record_id) REFERENCES trading_records(trading_book_id, record_id)
);
CREATE TABLE trading_ledger_review_units (
    revision_id UUID NOT NULL REFERENCES trading_ledger_revisions(id) ON DELETE CASCADE,
    review_unit_id TEXT NOT NULL,
    unit_json JSONB NOT NULL,
    PRIMARY KEY (revision_id, review_unit_id)
);
CREATE TABLE trading_ledger_allocations (
    revision_id UUID NOT NULL,
    allocation_id TEXT NOT NULL,
    review_unit_id TEXT NOT NULL,
    record_id TEXT NOT NULL,
    allocation_json JSONB NOT NULL,
    PRIMARY KEY (revision_id, allocation_id),
    FOREIGN KEY (revision_id, review_unit_id)
        REFERENCES trading_ledger_review_units(revision_id, review_unit_id),
    FOREIGN KEY (revision_id, record_id) REFERENCES trading_ledger_revision_records(revision_id, record_id)
);

CREATE FUNCTION trading_reject_published_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Published trading ledger data is immutable';
END;
$$;
CREATE TRIGGER trading_ledger_revision_no_update BEFORE UPDATE ON trading_ledger_revisions
    FOR EACH ROW EXECUTE FUNCTION trading_reject_published_update();
CREATE TRIGGER trading_ledger_revision_records_no_update BEFORE UPDATE ON trading_ledger_revision_records
    FOR EACH ROW EXECUTE FUNCTION trading_reject_published_update();
CREATE TRIGGER trading_ledger_review_units_no_update BEFORE UPDATE ON trading_ledger_review_units
    FOR EACH ROW EXECUTE FUNCTION trading_reject_published_update();
CREATE TRIGGER trading_ledger_allocations_no_update BEFORE UPDATE ON trading_ledger_allocations
    FOR EACH ROW EXECUTE FUNCTION trading_reject_published_update();
CREATE TRIGGER trading_records_no_update BEFORE UPDATE ON trading_records
    FOR EACH ROW EXECUTE FUNCTION trading_reject_published_update();
CREATE TRIGGER trading_source_evidence_no_update BEFORE UPDATE ON trading_source_evidence_snapshots
    FOR EACH ROW EXECUTE FUNCTION trading_reject_published_update();

ALTER TABLE trading_books ADD COLUMN latest_ledger_revision_id UUID REFERENCES trading_ledger_revisions(id);
ALTER TABLE trading_import_sessions ADD COLUMN created_ledger_revision_id UUID REFERENCES trading_ledger_revisions(id);

CREATE TABLE trading_normalization_ack_dispatches (
    id UUID PRIMARY KEY,
    -- Opaque acknowledgement survives primary session/run purge after a deletion tombstone.
    run_id UUID NOT NULL,
    core_import_session_id BIGINT NOT NULL,
    compute_job_id VARCHAR(160) NOT NULL,
    payload_id VARCHAR(160) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    result_hash CHAR(64) NOT NULL,
    attempt_token UUID NOT NULL,
    deletion_generation BIGINT NOT NULL,
    current_deletion_generation BIGINT,
    disposition VARCHAR(20) NOT NULL CHECK (disposition IN ('PERSISTED','DISCARDED_STALE')),
    idempotency_key CHAR(64) NOT NULL UNIQUE,
    retry_policy_json JSONB NOT NULL,
    payload_expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','ACKNOWLEDGED','TERMINAL_FAILURE')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_owner UUID,
    lease_expires_at TIMESTAMPTZ,
    last_failure_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (run_id, payload_id, disposition)
);
CREATE INDEX trading_normalization_ack_due_idx ON trading_normalization_ack_dispatches
    (status, next_attempt_at, lease_expires_at);
