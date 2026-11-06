ALTER TABLE trading_deletion_requests
    ADD COLUMN execution_lease_token UUID,
    ADD COLUMN execution_lease_expires_at TIMESTAMPTZ,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT '-infinity',
    ADD COLUMN execution_policy_version TEXT NOT NULL DEFAULT 'DELETION_EXECUTION_V1'
        CHECK (execution_policy_version = 'DELETION_EXECUTION_V1'),
    ADD COLUMN legacy_coverage_unknown BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN remediation_of BIGINT REFERENCES trading_deletion_requests(id);

UPDATE trading_deletion_requests d SET legacy_coverage_unknown=TRUE
WHERE state='PURGING_OBJECTS' AND NOT EXISTS
    (SELECT 1 FROM trading_deletion_worksets w WHERE w.request_id=d.id);

-- Keep the original idempotent request. Remediation is a new audited attempt.
DO $$
DECLARE previous_constraint TEXT;
BEGIN
    SELECT conname INTO previous_constraint FROM pg_constraint
    WHERE conrelid='trading_deletion_requests'::regclass AND contype='u'
        AND pg_get_constraintdef(oid)='UNIQUE (owner_member_id, target_type, target_id)';
    IF previous_constraint IS NULL THEN RAISE EXCEPTION 'Deletion scope uniqueness missing'; END IF;
    EXECUTE format('ALTER TABLE trading_deletion_requests DROP CONSTRAINT %I', previous_constraint);
END $$;
CREATE UNIQUE INDEX trading_deletion_original_scope_uq
    ON trading_deletion_requests(owner_member_id,target_type,target_id) WHERE remediation_of IS NULL;
CREATE UNIQUE INDEX trading_deletion_remediation_uq ON trading_deletion_requests(remediation_of)
    WHERE remediation_of IS NOT NULL;
CREATE INDEX trading_deletion_due_idx ON trading_deletion_requests(next_attempt_at,requested_at)
    WHERE state NOT IN ('COMPLETED','FAILED');

ALTER TABLE trading_deletion_worksets ADD COLUMN legacy_copies_verified_at TIMESTAMPTZ;
ALTER TABLE trading_deletion_runtime_tasks ADD COLUMN session_id BIGINT, ADD COLUMN verified_at TIMESTAMPTZ;
UPDATE trading_deletion_runtime_tasks t SET session_id=r.import_session_id
    FROM trading_normalization_runs r WHERE r.id=t.run_id;
ALTER TABLE trading_deletion_object_tasks ADD COLUMN session_id BIGINT, ADD COLUMN artifact_role TEXT;
UPDATE trading_deletion_object_tasks t SET session_id=a.import_session_id,artifact_role=a.artifact_role
    FROM trading_source_artifacts a WHERE a.id=t.artifact_id;
ALTER TABLE trading_deletion_revision_tasks ADD COLUMN verified_at TIMESTAMPTZ;

CREATE TABLE trading_deletion_copy_tasks (
    request_id BIGINT NOT NULL REFERENCES trading_deletion_requests(id),
    scope_type TEXT NOT NULL CHECK (scope_type IN ('SESSION','RAW_SLOT','ARTIFACT','RUN')),
    scope_id TEXT NOT NULL,
    artifact_role TEXT NOT NULL DEFAULT '',
    verified_at TIMESTAMPTZ,
    PRIMARY KEY(request_id,scope_type,scope_id,artifact_role)
);
