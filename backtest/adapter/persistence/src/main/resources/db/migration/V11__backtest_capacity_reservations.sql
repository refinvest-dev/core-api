-- Deploy with backtest admission/dispatch/polling stopped and all old Core instances removed.
-- The legacy monthly active_count column is retained for schema compatibility, but is no longer authoritative.
CREATE TABLE IF NOT EXISTS backtest_quotas (
    member_id BIGINT NOT NULL,
    quota_month DATE NOT NULL,
    used_count INTEGER NOT NULL CHECK (used_count >= 0),
    active_count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (member_id, quota_month)
);
CREATE TABLE backtest_capacities (member_id BIGINT PRIMARY KEY);
CREATE TABLE backtest_capacity_reservations (
    backtest_run_id BIGINT PRIMARY KEY,
    member_id BIGINT NOT NULL REFERENCES backtest_capacities(member_id),
    quota_month DATE NOT NULL
);
CREATE INDEX backtest_capacity_member_idx ON backtest_capacity_reservations(member_id);

-- Fresh schemas do not yet have Hibernate-owned Backtest/Strategy tables.
-- Existing schemas derive capacity from accepted active Runs, never from a reset monthly counter.
DO $$
BEGIN
    IF to_regclass('backtest_runs') IS NULL THEN
        RETURN;
    END IF;
    LOCK TABLE backtest_runs IN SHARE ROW EXCLUSIVE MODE;
    IF to_regclass('strategies') IS NULL THEN
        IF EXISTS (SELECT 1 FROM backtest_runs WHERE status IN ('PENDING','RUNNING')) THEN
            RAISE EXCEPTION 'Cannot resolve active backtest owners without strategies';
        END IF;
        RETURN;
    END IF;
    LOCK TABLE strategies IN SHARE MODE;
    IF EXISTS (SELECT 1 FROM backtest_runs r LEFT JOIN strategies s ON s.id=r.strategy_id
        WHERE r.status IN ('PENDING','RUNNING') AND (s.id IS NULL OR s.member_id IS NULL)) THEN
        RAISE EXCEPTION 'Cannot resolve an active backtest owner';
    END IF;
    INSERT INTO backtest_capacities(member_id)
        SELECT DISTINCT s.member_id FROM backtest_runs r JOIN strategies s ON s.id=r.strategy_id
        WHERE r.status IN ('PENDING','RUNNING');
    INSERT INTO backtest_capacity_reservations(backtest_run_id,member_id,quota_month)
        SELECT r.id,s.member_id,date_trunc('month',r.created_at AT TIME ZONE 'UTC')::date
        FROM backtest_runs r JOIN strategies s ON s.id=r.strategy_id
        WHERE r.status IN ('PENDING','RUNNING');
END;
$$;
