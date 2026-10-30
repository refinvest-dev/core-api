package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.valueobject.NormalizationRunId
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationResultVersionPin
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationDispatchStatus
import com.refinvest.core.tradingreview.domain.importsession.NormalizationDispatch
import com.refinvest.core.tradingreview.domain.importsession.NormalizationRun
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.port.normalization.*
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcTradingNormalizationRuntimeAdapter(private val jdbc: JdbcTemplate) : TradingNormalizationRuntime {
    override fun findRun(id: NormalizationRunId): NormalizationRun? = jdbc.query(
        "SELECT * FROM trading_normalization_runs WHERE id=?::uuid", { rs, _ -> NormalizationRunPersistenceMapper.toDomain(NormalizationRunRows.record(rs)) }, id.value).firstOrNull()

    override fun findActive(sessionId: TradingImportSessionId): NormalizationRun? = jdbc.query("""
        SELECT * FROM trading_normalization_runs WHERE import_session_id=?
        AND status IN ('DISPATCH_PENDING','SUBMITTED','RUNNING','RESULT_READY','PUBLISHING','RETRY_WAIT')
        ORDER BY created_at DESC LIMIT 1""", { rs, _ -> NormalizationRunPersistenceMapper.toDomain(NormalizationRunRows.record(rs)) }, sessionId.value).firstOrNull()

    override fun claimNext(now: Instant, lease: Duration, workerId: String): NormalizationDispatch? {
        // Defense at claim, in addition to the permit held across claim/send/receipt.
        return org.springframework.transaction.support.TransactionTemplate(
            org.springframework.jdbc.datasource.DataSourceTransactionManager(requireNotNull(jdbc.dataSource))).execute {
            if (jdbc.queryForObject("SELECT paused FROM trading_normalization_transition WHERE singleton FOR SHARE", Boolean::class.java) != false)
                null
            else claim("PENDING", now, lease, workerId)
        }
    }

    override fun holdUnknownCreate(dispatchId: String, leaseOwner: String, now: Instant): Boolean =
        jdbc.update("""UPDATE trading_normalization_dispatches SET lease_owner=NULL,
            lease_expires_at=NULL,last_failure_code='CREATE_OUTCOME_UNKNOWN',updated_at=?
            WHERE id=?::uuid AND lease_owner=?::uuid AND status='PENDING'""",
            Timestamp.from(now), dispatchId, leaseOwner) == 1

    override fun claimSubmitted(now: Instant, lease: Duration, workerId: String): NormalizationDispatch? =
        claim("SUBMITTED", now, lease, workerId)

    private fun claim(status: String, now: Instant, lease: Duration, workerId: String): NormalizationDispatch? =
        jdbc.query("""WITH due AS (SELECT id FROM trading_normalization_dispatches
            WHERE status=? AND (status<>'PENDING' OR create_started_at IS NULL) AND next_attempt_at<=? AND
              (lease_expires_at IS NULL OR lease_expires_at<=?)
            ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED)
            UPDATE trading_normalization_dispatches d SET lease_owner=?::uuid,
            lease_expires_at=?,attempt_count=attempt_count+1,updated_at=?,
            create_started_at=CASE WHEN d.status='PENDING' THEN clock_timestamp() ELSE d.create_started_at END
            FROM due WHERE d.id=due.id RETURNING d.*""", { rs, _ -> dispatch(rs) },
            status, Timestamp.from(now), Timestamp.from(now), workerId,
            Timestamp.from(now.plus(lease)), Timestamp.from(now)).firstOrNull()

    override fun schedulePoll(dispatchId: String, leaseOwner: String, next: Instant, now: Instant): Boolean =
        jdbc.update("""UPDATE trading_normalization_dispatches SET next_attempt_at=?,poll_failure_count=0,
            lease_owner=NULL,lease_expires_at=NULL,updated_at=? WHERE id=?::uuid AND
            lease_owner=?::uuid AND lease_expires_at>? AND status='SUBMITTED'""",
            Timestamp.from(next), Timestamp.from(now), dispatchId, leaseOwner, Timestamp.from(now)) == 1

    override fun schedulePollFailure(dispatchId: String, leaseOwner: String, code: String,
                                     next: Instant, now: Instant): Boolean = jdbc.update("""
        UPDATE trading_normalization_dispatches SET next_attempt_at=?,
        poll_failure_count=poll_failure_count+1,last_failure_code=?,
        lease_owner=NULL,lease_expires_at=NULL,updated_at=? WHERE id=?::uuid
        AND lease_owner=?::uuid AND lease_expires_at>? AND status='SUBMITTED'""",
        Timestamp.from(next), code, Timestamp.from(now), dispatchId, leaseOwner,
        Timestamp.from(now)) == 1

    override fun markResultReady(dispatchId: String, leaseOwner: String, now: Instant): Boolean {
        val changed = jdbc.update("""UPDATE trading_normalization_dispatches SET status='RESULT_READY',
            lease_owner=NULL,lease_expires_at=NULL,updated_at=? WHERE id=?::uuid AND
            lease_owner=?::uuid AND lease_expires_at>? AND status='SUBMITTED'""",
            Timestamp.from(now), dispatchId, leaseOwner, Timestamp.from(now))
        if (changed != 1) return false
        jdbc.update("""UPDATE trading_normalization_runs SET status='RESULT_READY',updated_at=?,
            version=version+1 WHERE id=(SELECT run_id FROM trading_normalization_dispatches WHERE id=?::uuid)""",
            Timestamp.from(now), dispatchId)
        return true
    }

    override fun advanceAttempt(change: NormalizationGrantAttemptAdvance): Boolean {
        val runId = change.runId.value
        val dispatchId = change.lease.dispatchId
        val leaseOwner = change.lease.owner
        val now = change.lease.observedAt
        val expectedToken = change.expectedToken
        val nextToken = change.nextToken
        require(expectedToken != nextToken) { "Grant retry requires a fresh token" }
        val changed = jdbc.update("""UPDATE trading_normalization_runs r SET current_attempt=current_attempt+1,
            attempt_token=?::uuid,updated_at=?,version=version+1 WHERE r.id=?::uuid AND
            r.attempt_token=?::uuid AND EXISTS (SELECT 1 FROM trading_normalization_dispatches d
            WHERE d.id=?::uuid AND d.run_id=r.id AND d.status='SUBMITTED' AND
            d.lease_owner=?::uuid AND d.lease_expires_at>?)""",
            nextToken, Timestamp.from(now), runId, expectedToken, dispatchId, leaseOwner, Timestamp.from(now))
        if (changed != 1) return false
        jdbc.update("""UPDATE trading_normalization_attempts SET completed_at=?,result_category='RETRY_WAIT'
            WHERE run_id=?::uuid AND attempt_token=?::uuid AND completed_at IS NULL""",
            Timestamp.from(now), runId, expectedToken)
        jdbc.update("""INSERT INTO trading_normalization_attempts
            (run_id,attempt_number,attempt_token,started_at,compute_job_id)
            SELECT id,current_attempt,attempt_token,?,compute_job_id FROM trading_normalization_runs WHERE id=?::uuid""",
            Timestamp.from(now), runId)
        audit(runId, "GRANT_RETRY_ATTEMPT", null, now)
        return true
    }

    override fun restartAfterJobFailure(change: NormalizationJobRetry): Boolean {
        val runId = change.runId.value
        val dispatchId = change.lease.dispatchId
        val leaseOwner = change.lease.owner
        val now = change.lease.observedAt
        val nextDispatch = change.nextDispatch
        val nextToken = change.nextToken
        val failureCode = change.failureCode
        require(nextDispatch.runId == runId) { "Foreign retry dispatch" }
        val pinned = findRun(NormalizationRunId(runId))?.resultVersionPin ?: return false
        require(nextDispatch.resultVersionPin == pinned)
        val changed = jdbc.update("""UPDATE trading_normalization_dispatches SET status='TERMINAL_FAILURE',
            last_failure_code=?,lease_owner=NULL,lease_expires_at=NULL,updated_at=? WHERE id=?::uuid
            AND lease_owner=?::uuid AND lease_expires_at>? AND status='SUBMITTED'""",
            failureCode, Timestamp.from(now), dispatchId, leaseOwner, Timestamp.from(now))
        if (changed != 1) return false
        jdbc.update("""UPDATE trading_normalization_attempts SET completed_at=?,result_category='RETRY_WAIT',
            safe_failure_code=? WHERE run_id=?::uuid AND completed_at IS NULL""",
            Timestamp.from(now), failureCode, runId)
        jdbc.update("""UPDATE trading_normalization_runs SET status='DISPATCH_PENDING',
            current_attempt=current_attempt+1,attempt_token=?::uuid,compute_job_id=NULL,
            last_failure_code=?,next_attempt_at=?,updated_at=?,version=version+1 WHERE id=?::uuid""",
            nextToken, failureCode, Timestamp.from(nextDispatch.nextAttemptAt), Timestamp.from(now), runId)
        jdbc.update("""INSERT INTO trading_normalization_attempts(run_id,attempt_number,attempt_token,started_at)
            SELECT id,current_attempt,attempt_token,? FROM trading_normalization_runs WHERE id=?::uuid""",
            Timestamp.from(now), runId)
        jdbc.update("""INSERT INTO trading_normalization_dispatches
            (id,run_id,retry_of_dispatch_id,compute_key,status,attempt_count,next_attempt_at,created_at,updated_at,
            result_schema_version,result_hash_specification_version)
            VALUES (?::uuid,?::uuid,?::uuid,?,'PENDING',0,?,?,?,?,?)""",
            nextDispatch.id, runId, dispatchId, nextDispatch.computeKey,
            Timestamp.from(nextDispatch.nextAttemptAt), Timestamp.from(now), Timestamp.from(now),
            pinned.schemaVersion, pinned.hashSpecificationVersion)
        audit(runId, "COMPUTE_JOB_RETRY", failureCode, now)
        return true
    }

    override fun markSubmitted(change: NormalizationSubmissionReceipt): Boolean {
        val runId = change.runId.value
        val dispatchId = change.lease.dispatchId
        val leaseOwner = change.lease.owner
        val now = change.lease.observedAt
        val generation = change.generation
        val attemptToken = change.attemptToken
        val jobId = change.jobId
        val count = jdbc.update("""UPDATE trading_normalization_dispatches d SET status='SUBMITTED',
            lease_owner=NULL,lease_expires_at=NULL,updated_at=? WHERE d.id=?::uuid AND
            d.lease_owner=?::uuid AND d.lease_expires_at>? AND d.status='PENDING' AND EXISTS (
              SELECT 1 FROM trading_normalization_runs r JOIN trading_import_sessions s
              ON s.id=r.import_session_id WHERE r.id=d.run_id AND r.id=?::uuid AND
              r.attempt_token=?::uuid AND r.deletion_generation=? AND
              s.active_normalization_run_id=r.id AND s.deletion_generation=? AND
              s.status='VALIDATING' AND NOT s.deletion_pending)""",
            Timestamp.from(now), dispatchId, leaseOwner, Timestamp.from(now),
            runId, attemptToken, generation, generation)
        if (count != 1) return false
        jdbc.update("""UPDATE trading_normalization_runs SET status='SUBMITTED',compute_job_id=?,
            started_at=COALESCE(started_at,?),updated_at=?,version=version+1 WHERE id=?::uuid""",
            jobId, Timestamp.from(now), Timestamp.from(now), runId)
        jdbc.update("""UPDATE trading_normalization_attempts SET compute_job_id=?
            WHERE run_id=?::uuid AND attempt_token=?::uuid""", jobId, runId, attemptToken)
        audit(runId, "COMPUTE_SUBMITTED", null, now)
        return true
    }

    override fun scheduleRetry(dispatchId: String, leaseOwner: String, code: String,
                               next: Instant, now: Instant): Boolean {
        val count = jdbc.update("""UPDATE trading_normalization_dispatches SET
            lease_owner=NULL,lease_expires_at=NULL,create_started_at=NULL,next_attempt_at=?,last_failure_code=?,updated_at=?
            WHERE id=?::uuid AND lease_owner=?::uuid AND lease_expires_at>? AND status='PENDING'""",
            Timestamp.from(next), code, Timestamp.from(now), dispatchId, leaseOwner, Timestamp.from(now))
        if (count == 1) jdbc.update("""UPDATE trading_normalization_runs SET status='RETRY_WAIT',
            last_failure_code=?,next_attempt_at=?,updated_at=?,version=version+1
            WHERE id=(SELECT run_id FROM trading_normalization_dispatches WHERE id=?::uuid)""",
            code, Timestamp.from(next), Timestamp.from(now), dispatchId)
        return count == 1
    }

    override fun markTerminal(dispatchId: String, leaseOwner: String, code: String, now: Instant): Boolean =
        finish(dispatchId, leaseOwner, "TERMINAL_FAILURE", code, now)

    override fun markObsolete(dispatchId: String, leaseOwner: String, now: Instant): Boolean =
        finish(dispatchId, leaseOwner, "OBSOLETE", null, now)

    private fun finish(dispatchId: String, leaseOwner: String, state: String, code: String?, now: Instant): Boolean {
        val count = jdbc.update("""UPDATE trading_normalization_dispatches SET status=?,last_failure_code=?,
            lease_owner=NULL,lease_expires_at=NULL,updated_at=? WHERE id=?::uuid AND lease_owner=?::uuid
            AND lease_expires_at>? AND status IN ('PENDING','SUBMITTED','RESULT_READY')""",
            state, code, Timestamp.from(now), dispatchId, leaseOwner, Timestamp.from(now))
        if (count != 1) return false
        jdbc.update("""UPDATE trading_normalization_runs SET status=?,last_failure_code=?,
            completed_at=?,updated_at=?,version=version+1 WHERE id=(SELECT run_id FROM
            trading_normalization_dispatches WHERE id=?::uuid)""",
            state, code, Timestamp.from(now), Timestamp.from(now), dispatchId)
        jdbc.update("""UPDATE trading_normalization_attempts SET completed_at=?,
            result_category=?,safe_failure_code=? WHERE run_id=(SELECT run_id FROM
            trading_normalization_dispatches WHERE id=?::uuid) AND completed_at IS NULL""",
            Timestamp.from(now), state, code, dispatchId)
        return true
    }

    override fun audit(runId: String, event: String, code: String?, now: Instant) {
        jdbc.update("""INSERT INTO trading_normalization_audit(run_id,event,safe_failure_code,occurred_at)
            VALUES (?::uuid,?,?,?)""", runId, event, code, Timestamp.from(now))
    }

    private fun dispatch(rs: ResultSet) = NormalizationDispatch(rs.getString("id"), rs.getString("run_id"),
        rs.getString("compute_key"), NormalizationDispatchStatus.valueOf(rs.getString("status")),
        rs.getInt("attempt_count"), rs.getTimestamp("next_attempt_at").toInstant(),
        rs.getString("lease_owner"), rs.getTimestamp("lease_expires_at")?.toInstant(),
        rs.getString("last_failure_code"), rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(), rs.getInt("poll_failure_count"),
        NormalizationResultVersionPin(rs.getString("result_schema_version"),
            rs.getString("result_hash_specification_version")))
}
