-- ADR-070: V2 input supplements are immutable children of the published Revision.
-- A V1 Revision has no rows. A V2 Revision with no ReviewUnits also has no rows,
-- so callers must gate on the Revision's pinned result_schema_version.
CREATE TABLE trading_ledger_analytics_supplement (
    revision_id UUID NOT NULL REFERENCES trading_ledger_revisions(id) ON DELETE CASCADE,
    review_unit_id TEXT NOT NULL,
    engine_input_json JSONB NOT NULL,
    PRIMARY KEY (revision_id, review_unit_id),
    FOREIGN KEY (revision_id, review_unit_id)
        REFERENCES trading_ledger_review_units(revision_id, review_unit_id)
);
CREATE TRIGGER trading_ledger_analytics_supplement_no_update
    BEFORE UPDATE ON trading_ledger_analytics_supplement
    FOR EACH ROW EXECUTE FUNCTION trading_reject_published_update();
