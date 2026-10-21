-- RFC 8785 orders object identities by UTF-16 code units, not by the database locale.
-- Existing in-flight stages are repaired in bounded batches by the result store.
ALTER TABLE trading_normalization_staged_items ADD COLUMN item_sort_key TEXT;
CREATE INDEX trading_normalization_staged_order_idx
    ON trading_normalization_staged_items(run_id, section_type, item_sort_key COLLATE "C");
