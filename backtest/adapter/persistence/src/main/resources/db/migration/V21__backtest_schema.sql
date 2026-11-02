-- V11 owns quotas, capacities and reservations. Do not reset or derive them again.
CREATE TABLE IF NOT EXISTS backtest_runs (
    id BIGINT PRIMARY KEY,
    strategy_id BIGINT NOT NULL,
    strategy_version_id BIGINT NOT NULL,
    requested_period_start DATE NOT NULL,
    requested_period_end DATE NOT NULL,
    commission NUMERIC(19,8) NOT NULL,
    slippage NUMERIC(19,8) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')),
    actual_period_start DATE,
    actual_period_end DATE,
    dataset_snapshot_id VARCHAR(255),
    engine_version VARCHAR(255),
    failure_reason VARCHAR(255),
    error_code VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL
);
-- @Lob String uses PostgreSQL large-object OIDs; keep existing storage semantics.
CREATE TABLE IF NOT EXISTS backtest_results (
    backtest_run_id BIGINT PRIMARY KEY,
    result_payload OID NOT NULL
);
CREATE TABLE IF NOT EXISTS backtest_compute_dispatches (
    backtest_run_id BIGINT PRIMARY KEY,
    idempotency_key UUID NOT NULL UNIQUE,
    compute_run_id VARCHAR(255) UNIQUE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','SUBMITTED','REJECTED','TERMINAL')),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    dispatch_attempt_count INTEGER NOT NULL DEFAULT 0,
    poll_retry_pending BOOLEAN NOT NULL DEFAULT FALSE
);
