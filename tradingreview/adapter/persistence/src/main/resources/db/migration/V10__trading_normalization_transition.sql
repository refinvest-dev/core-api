-- Internal DBA control. There is deliberately no public management endpoint.
CREATE TABLE trading_normalization_transition (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    epoch BIGINT NOT NULL DEFAULT 0 CHECK (epoch >= 0),
    verified_evidence VARCHAR(240),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO trading_normalization_transition(singleton) VALUES (TRUE);
CREATE TABLE trading_normalization_transition_audit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    epoch BIGINT NOT NULL,
    action VARCHAR(30) NOT NULL,
    actor TEXT NOT NULL,
    evidence_ref VARCHAR(240) NOT NULL,
    dispatch_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
ALTER TABLE trading_normalization_dispatches ADD COLUMN create_started_at TIMESTAMPTZ;
-- An old pending attempt may already have reached Compute. Require reconciliation.
UPDATE trading_normalization_dispatches SET create_started_at=updated_at
    WHERE status='PENDING' AND attempt_count>0;

CREATE FUNCTION trading_normalization_transition_control(
    command TEXT, expected_epoch BIGINT, evidence TEXT) RETURNS BIGINT
LANGUAGE plpgsql AS $$
DECLARE state trading_normalization_transition%ROWTYPE;
BEGIN
    IF evidence IS NULL OR evidence !~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,239}$' THEN
        RAISE EXCEPTION 'Safe evidence reference required';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtext(current_schema()), 28028);
    SELECT * INTO STRICT state FROM trading_normalization_transition WHERE singleton FOR UPDATE;
    IF state.epoch<>expected_epoch THEN RAISE EXCEPTION 'Transition epoch conflict'; END IF;
    IF command='PAUSE' THEN
        UPDATE trading_normalization_transition SET paused=TRUE,epoch=epoch+1,
            verified_evidence=NULL,updated_at=clock_timestamp() WHERE singleton;
    ELSIF command='VERIFY' AND state.paused THEN
        IF EXISTS (SELECT 1 FROM trading_normalization_dispatches
            WHERE status='PENDING' AND create_started_at IS NOT NULL) THEN
            RAISE EXCEPTION 'Unresolved create outcome';
        END IF;
        UPDATE trading_normalization_transition SET verified_evidence=evidence,
            updated_at=clock_timestamp() WHERE singleton;
    ELSIF command='RESUME' AND state.paused AND state.verified_evidence=evidence THEN
        IF EXISTS (SELECT 1 FROM trading_normalization_dispatches
            WHERE status='PENDING' AND create_started_at IS NOT NULL) THEN
            RAISE EXCEPTION 'Unresolved create outcome';
        END IF;
        UPDATE trading_normalization_transition SET paused=FALSE,updated_at=clock_timestamp() WHERE singleton;
    ELSE RAISE EXCEPTION 'Transition not verified or invalid command';
    END IF;
    INSERT INTO trading_normalization_transition_audit(epoch,action,actor,evidence_ref)
        SELECT epoch,command,session_user,evidence FROM trading_normalization_transition WHERE singleton;
    RETURN (SELECT epoch FROM trading_normalization_transition WHERE singleton);
END;
$$;
REVOKE ALL ON FUNCTION trading_normalization_transition_control(TEXT,BIGINT,TEXT) FROM PUBLIC;

-- Only after an operator has compared real Compute status and its idempotency record.
-- Null Job ID is allowed solely for proven non-acceptance, preserving the original key.
CREATE FUNCTION trading_normalization_reconcile_create(
    dispatch UUID, job TEXT, expected_epoch BIGINT, evidence TEXT) RETURNS VOID
LANGUAGE plpgsql AS $$
DECLARE item trading_normalization_dispatches%ROWTYPE;
DECLARE parent trading_normalization_runs%ROWTYPE;
BEGIN
    IF evidence IS NULL OR evidence !~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{0,239}$'
        OR (job IS NOT NULL AND (length(job)<1 OR length(job)>160)) THEN
        RAISE EXCEPTION 'Safe reconciliation evidence required';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtext(current_schema()), 28028);
    IF NOT EXISTS (SELECT 1 FROM trading_normalization_transition
        WHERE singleton AND paused AND epoch=expected_epoch) THEN
        RAISE EXCEPTION 'Reconciliation requires paused epoch';
    END IF;
    SELECT * INTO STRICT item FROM trading_normalization_dispatches WHERE id=dispatch FOR UPDATE;
    SELECT * INTO STRICT parent FROM trading_normalization_runs WHERE id=item.run_id FOR UPDATE;
    IF item.status<>'PENDING' OR item.create_started_at IS NULL OR
        parent.status NOT IN ('DISPATCH_PENDING','RETRY_WAIT') OR
        NOT EXISTS (SELECT 1 FROM trading_import_sessions WHERE id=parent.import_session_id
            AND NOT deletion_pending AND status='VALIDATING' AND active_normalization_run_id=parent.id
            AND deletion_generation=parent.deletion_generation) THEN
        RAISE EXCEPTION 'Create identity no longer current';
    END IF;
    UPDATE trading_normalization_dispatches SET create_started_at=NULL,
        status=CASE WHEN job IS NULL THEN 'PENDING' ELSE 'SUBMITTED' END,
        lease_owner=NULL,lease_expires_at=NULL,last_failure_code=NULL,
        next_attempt_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=dispatch;
    IF job IS NOT NULL THEN
        UPDATE trading_normalization_runs SET status='SUBMITTED',compute_job_id=job,
            started_at=COALESCE(started_at,clock_timestamp()),updated_at=clock_timestamp(),version=version+1
            WHERE id=parent.id;
        UPDATE trading_normalization_attempts SET compute_job_id=job
            WHERE run_id=parent.id AND attempt_token=parent.attempt_token;
    END IF;
    UPDATE trading_normalization_transition SET verified_evidence=NULL WHERE singleton;
    INSERT INTO trading_normalization_transition_audit(epoch,action,actor,evidence_ref,dispatch_id)
        VALUES (expected_epoch,'RECONCILE_CREATE',session_user,evidence,dispatch);
END;
$$;
REVOKE ALL ON FUNCTION trading_normalization_reconcile_create(UUID,TEXT,BIGINT,TEXT) FROM PUBLIC;
REVOKE ALL ON trading_normalization_transition,trading_normalization_transition_audit FROM PUBLIC;
