-- Pre-ADR-065 runs remain null and cannot be submitted or retried as V1 by guessing current state.
ALTER TABLE trading_normalization_runs
    ADD COLUMN input_identity_json JSONB
    CONSTRAINT trading_normalization_input_identity_object_check
        CHECK (input_identity_json IS NULL OR jsonb_typeof(input_identity_json) = 'object');
