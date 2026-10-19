-- Existing runs and stages predate ADR-067. Null marks legacy state and must not be inferred from current configuration.
ALTER TABLE trading_normalization_runs ADD COLUMN result_delivery_policy_json JSONB
    CHECK (result_delivery_policy_json IS NULL OR jsonb_typeof(result_delivery_policy_json) = 'object');
ALTER TABLE trading_normalization_result_staging ADD COLUMN result_delivery_policy_version VARCHAR(100);
ALTER TABLE trading_normalization_result_staging ADD COLUMN content_encoding VARCHAR(30);
