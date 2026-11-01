-- Historical counters are incomplete. Preserve them and never reconstruct request-time counts from live data.
ALTER TABLE trading_deletion_requests
    ADD COLUMN ledger_revision_count BIGINT NOT NULL DEFAULT 0 CHECK (ledger_revision_count >= 0),
    ADD COLUMN analysis_run_count BIGINT NOT NULL DEFAULT 0 CHECK (analysis_run_count >= 0),
    ADD COLUMN reprocessing_run_count BIGINT NOT NULL DEFAULT 0 CHECK (reprocessing_run_count >= 0),
    ADD COLUMN count_snapshot_status VARCHAR(30) NOT NULL DEFAULT 'LEGACY_PARTIAL'
        CHECK (count_snapshot_status IN ('REQUEST_TIME_SNAPSHOT', 'LEGACY_PARTIAL'));
