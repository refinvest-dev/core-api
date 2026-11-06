ALTER TABLE trading_import_sessions
    ADD COLUMN accepted_at TIMESTAMPTZ,
    ADD COLUMN rejected_at TIMESTAMPTZ,
    ADD COLUMN raw_expired_at TIMESTAMPTZ;

-- Recover only timestamps with existing transition evidence. Never extend historical raw deadlines.
UPDATE trading_import_sessions s SET accepted_at=r.published_at
    FROM trading_ledger_revisions r WHERE s.status='ACCEPTED' AND s.created_ledger_revision_id=r.id
    AND r.import_session_id=s.id AND r.published_at BETWEEN s.created_at AND s.updated_at;
UPDATE trading_import_sessions SET rejected_at=failure_occurred_at
    WHERE status='REJECTED' AND failure_occurred_at BETWEEN created_at AND updated_at;

CREATE INDEX trading_import_raw_expiry_idx ON trading_import_sessions(id)
    WHERE status IN ('RECEIVED','VALIDATING','PROCESSING_FAILED') AND raw_expired_at IS NULL AND NOT deletion_pending;
