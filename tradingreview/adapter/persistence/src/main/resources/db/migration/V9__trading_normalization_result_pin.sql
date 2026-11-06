-- Existing Core/Compute production normalization was V1-only. Published and staged
-- provenance must agree before the remaining V1-only runs are backfilled.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM trading_ledger_revisions
        WHERE result_schema_version <> 'TRADING_NORMALIZATION_RESULT_V1'
           OR result_hash_specification_version <> 'TRADING_NORMALIZATION_RESULT_HASH_V1')
       OR EXISTS (SELECT 1 FROM trading_normalization_result_staging
        WHERE result_schema_version <> 'TRADING_NORMALIZATION_RESULT_V1'
           OR result_hash_specification_version <> 'TRADING_NORMALIZATION_RESULT_HASH_V1')
       OR EXISTS (SELECT 1 FROM trading_normalization_runs r
        WHERE r.status = 'PUBLISHED' AND NOT EXISTS
            (SELECT 1 FROM trading_ledger_revisions v WHERE v.run_id = r.id)) THEN
        RAISE EXCEPTION 'Normalization V1 provenance is not established';
    END IF;
END;
$$;

ALTER TABLE trading_normalization_runs
    ADD COLUMN result_schema_version VARCHAR(100),
    ADD COLUMN result_hash_specification_version VARCHAR(100);
ALTER TABLE trading_normalization_dispatches
    ADD COLUMN result_schema_version VARCHAR(100),
    ADD COLUMN result_hash_specification_version VARCHAR(100);

UPDATE trading_normalization_runs SET
    result_schema_version = 'TRADING_NORMALIZATION_RESULT_V1',
    result_hash_specification_version = 'TRADING_NORMALIZATION_RESULT_HASH_V1',
    logical_key = encode(sha256(convert_to(
        '{"deletionGeneration":' || deletion_generation::text ||
        ',"inputSetHash":"' || input_fingerprint ||
        '","ownerScope":"' || owner_member_id::text ||
        '","resultHashSpecificationVersion":"TRADING_NORMALIZATION_RESULT_HASH_V1"' ||
        ',"resultSchemaVersion":"TRADING_NORMALIZATION_RESULT_V1"}', 'UTF8')), 'hex');
UPDATE trading_normalization_dispatches d SET
    result_schema_version = r.result_schema_version,
    result_hash_specification_version = r.result_hash_specification_version
    FROM trading_normalization_runs r WHERE r.id = d.run_id;

ALTER TABLE trading_normalization_runs
    ALTER COLUMN result_schema_version SET NOT NULL,
    ALTER COLUMN result_hash_specification_version SET NOT NULL,
    ADD CONSTRAINT trading_normalization_run_result_pair_check CHECK
        ((result_schema_version = 'TRADING_NORMALIZATION_RESULT_V1' AND
          result_hash_specification_version = 'TRADING_NORMALIZATION_RESULT_HASH_V1') OR
         (result_schema_version = 'TRADING_NORMALIZATION_RESULT_V2' AND
          result_hash_specification_version = 'TRADING_NORMALIZATION_RESULT_HASH_V2')),
    ADD CONSTRAINT trading_normalization_run_pinned_identity_uq
        UNIQUE (id, result_schema_version, result_hash_specification_version);
ALTER TABLE trading_normalization_dispatches
    ALTER COLUMN result_schema_version SET NOT NULL,
    ALTER COLUMN result_hash_specification_version SET NOT NULL,
    ADD CONSTRAINT trading_normalization_dispatch_pinned_run_fk
        FOREIGN KEY (run_id, result_schema_version, result_hash_specification_version)
        REFERENCES trading_normalization_runs(id, result_schema_version,
            result_hash_specification_version);

CREATE FUNCTION trading_normalization_reject_pin_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (OLD.result_schema_version, OLD.result_hash_specification_version) IS DISTINCT FROM
       (NEW.result_schema_version, NEW.result_hash_specification_version) THEN
        RAISE EXCEPTION 'Normalization result pin is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trading_normalization_run_pin_no_update
    BEFORE UPDATE ON trading_normalization_runs FOR EACH ROW
    EXECUTE FUNCTION trading_normalization_reject_pin_change();
CREATE TRIGGER trading_normalization_dispatch_pin_no_update
    BEFORE UPDATE ON trading_normalization_dispatches FOR EACH ROW
    EXECUTE FUNCTION trading_normalization_reject_pin_change();

-- Publication uniqueness includes the wire pair without rewriting any Revision.
DO $$
DECLARE previous_constraint name;
BEGIN
    SELECT conname INTO previous_constraint FROM pg_constraint
    WHERE conrelid = 'trading_ledger_revisions'::regclass AND contype = 'u'
      AND pg_get_constraintdef(oid) =
          'UNIQUE (trading_book_id, input_set_hash, target_version_set_json)';
    IF previous_constraint IS NULL THEN
        RAISE EXCEPTION 'Previous normalization publication constraint not found';
    END IF;
    EXECUTE format('ALTER TABLE trading_ledger_revisions DROP CONSTRAINT %I', previous_constraint);
END;
$$;
ALTER TABLE trading_ledger_revisions ADD CONSTRAINT trading_ledger_revision_result_pair_uq
    UNIQUE (trading_book_id, input_set_hash, target_version_set_json,
        result_schema_version, result_hash_specification_version);
