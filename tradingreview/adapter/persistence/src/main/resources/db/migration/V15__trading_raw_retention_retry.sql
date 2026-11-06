-- Keep retry scheduling separate from the lease of an active purge attempt.
-- Existing descriptors, deadlines, attempt counts and terminal failures remain unchanged.
ALTER TABLE trading_source_artifacts ADD COLUMN deletion_next_attempt_at TIMESTAMPTZ;

CREATE INDEX trading_raw_retention_due_idx
    ON trading_source_artifacts (raw_expires_at, id)
    WHERE retention_status IN ('ACTIVE', 'RETENTION_SCHEDULED', 'DELETION_PENDING');
