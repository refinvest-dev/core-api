package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.port.TradingNormalizationStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Repository
class JdbcTradingNormalizationStore(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) : TradingNormalizationStore {
    override fun insert(run: NormalizationRun, dispatch: NormalizationDispatch) {
        val input = run.input
        val identityJson = run.inputIdentity?.let { identity ->
            verifyIdentity(input, identity, run.inputFingerprint)
            String(NormalizationInputCanonicalizer.canonicalize(identity), Charsets.UTF_8)
        }
        val descriptors = input.artifacts.map { mapOf("artifactId" to it.artifactId.value,
            "role" to it.role.name, "sha256" to it.sha256, "contentLength" to it.contentLength,
            "mediaType" to it.mediaType, "dialect" to it.dialect) }
        val inputJson = mapper.writeValueAsString(mapOf("artifacts" to descriptors, "venue" to input.venue,
            "productFamily" to input.productFamily, "positionMode" to input.positionMode,
            "settlementAsset" to input.settlementAsset, "sourceTimezone" to input.sourceTimezone,
            "reviewTimezone" to input.reviewTimezone))
        jdbc.update("""INSERT INTO trading_normalization_runs
            (id, retry_of_run_id, import_session_id, trading_book_id, owner_member_id, input_fingerprint, logical_key,
            input_json, input_identity_json, target_version_set_json, instrument_terms_json, data_policy_version,
            retry_policy_version, retry_policy_json,
            deletion_generation, status, current_attempt, attempt_token, compute_job_id,
            last_failure_code, next_attempt_at, created_at, started_at, updated_at, completed_at, version)
            VALUES (?::uuid,?::uuid,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?::jsonb,?,?,?,?::uuid,?,?,?,?,?,?,?,?)""",
            run.id, run.retryOfRunId, input.importSessionId.value, input.tradingBookId.value, input.ownerId,
            run.inputFingerprint, run.logicalKey, inputJson, identityJson, input.versionSetJson,
            input.instrumentTermsJson, input.dataPolicyVersion, input.retryPolicyVersion,
            input.retryPolicyJson, input.deletionGeneration,
            run.status.name, run.currentAttempt, run.attemptToken, run.computeJobId, run.lastFailureCode,
            Timestamp.from(run.nextAttemptAt), Timestamp.from(run.createdAt), run.startedAt?.let(Timestamp::from),
            Timestamp.from(run.updatedAt), run.completedAt?.let(Timestamp::from), run.version)
        jdbc.update("""INSERT INTO trading_normalization_attempts
            (run_id,attempt_number,attempt_token,started_at) VALUES (?::uuid,?,?::uuid,?)""",
            run.id, run.currentAttempt, run.attemptToken, Timestamp.from(run.createdAt))
        jdbc.update("""INSERT INTO trading_normalization_dispatches
            (id,run_id,compute_key,status,attempt_count,next_attempt_at,lease_owner,lease_expires_at,
            last_failure_code,created_at,updated_at)
            VALUES (?::uuid,?::uuid,?,?,?,?,?,?,?,?,?)""",
            dispatch.id, dispatch.runId, dispatch.computeKey, dispatch.status.name,
            dispatch.attemptCount, Timestamp.from(dispatch.nextAttemptAt), null,
            null, dispatch.lastFailureCode, Timestamp.from(dispatch.createdAt), Timestamp.from(dispatch.updatedAt))
    }

    override fun findRun(id: String): NormalizationRun? = jdbc.query(
        "SELECT * FROM trading_normalization_runs WHERE id=?::uuid", { rs, _ -> run(rs) }, id).firstOrNull()

    override fun findActive(sessionId: TradingImportSessionId): NormalizationRun? = jdbc.query("""
        SELECT * FROM trading_normalization_runs WHERE import_session_id=?
        AND status IN ('DISPATCH_PENDING','SUBMITTED','RUNNING','RESULT_READY','PUBLISHING','RETRY_WAIT')
        ORDER BY created_at DESC LIMIT 1""", { rs, _ -> run(rs) }, sessionId.value).firstOrNull()

    override fun claimNext(now: Instant, lease: Duration, workerId: String): NormalizationDispatch? =
        claim("PENDING", now, lease, workerId)

    override fun claimSubmitted(now: Instant, lease: Duration, workerId: String): NormalizationDispatch? =
        claim("SUBMITTED", now, lease, workerId)

    private fun claim(status: String, now: Instant, lease: Duration, workerId: String): NormalizationDispatch? =
        jdbc.query("""WITH due AS (SELECT id FROM trading_normalization_dispatches
            WHERE status=? AND next_attempt_at<=? AND
              (lease_expires_at IS NULL OR lease_expires_at<=?)
            ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED)
            UPDATE trading_normalization_dispatches d SET lease_owner=?::uuid,
            lease_expires_at=?,attempt_count=attempt_count+1,updated_at=?
            FROM due WHERE d.id=due.id RETURNING d.*""", { rs, _ -> dispatch(rs) },
            status, Timestamp.from(now), Timestamp.from(now), workerId,
            Timestamp.from(now.plus(lease)), Timestamp.from(now)).firstOrNull()

    override fun schedulePoll(dispatchId: String, leaseOwner: String, next: Instant, now: Instant): Boolean =
        jdbc.update("""UPDATE trading_normalization_dispatches SET next_attempt_at=?,
            lease_owner=NULL,lease_expires_at=NULL,updated_at=? WHERE id=?::uuid AND
            lease_owner=?::uuid AND lease_expires_at>? AND status='SUBMITTED'""",
            Timestamp.from(next), Timestamp.from(now), dispatchId, leaseOwner, Timestamp.from(now)) == 1

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

    override fun advanceAttempt(runId: String, dispatchId: String, leaseOwner: String,
                                expectedToken: String, nextToken: String, now: Instant): Boolean {
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
            (run_id,attempt_number,attempt_token,started_at)
            SELECT id,current_attempt,attempt_token,? FROM trading_normalization_runs WHERE id=?::uuid""",
            Timestamp.from(now), runId)
        audit(runId, "GRANT_RETRY_ATTEMPT", null, now)
        return true
    }

    override fun restartAfterJobFailure(runId: String, dispatchId: String, leaseOwner: String,
                                        nextDispatch: NormalizationDispatch, nextToken: String,
                                        failureCode: String, now: Instant): Boolean {
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
            (id,run_id,retry_of_dispatch_id,compute_key,status,attempt_count,next_attempt_at,created_at,updated_at)
            VALUES (?::uuid,?::uuid,?::uuid,?,'PENDING',0,?,?,?)""",
            nextDispatch.id, runId, dispatchId, nextDispatch.computeKey,
            Timestamp.from(nextDispatch.nextAttemptAt), Timestamp.from(now), Timestamp.from(now))
        audit(runId, "COMPUTE_JOB_RETRY", failureCode, now)
        return true
    }

    override fun markSubmitted(dispatchId: String, leaseOwner: String, runId: String,
                               generation: Long, attemptToken: String, jobId: String, now: Instant): Boolean {
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
            lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=?,last_failure_code=?,updated_at=?
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
            AND lease_expires_at>? AND status IN ('PENDING','SUBMITTED')""",
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

    private fun run(rs: ResultSet): NormalizationRun {
        val data = mapper.readTree(rs.getString("input_json"))
        val artifactArray = data.path("artifacts")
        val inputs = (0 until artifactArray.size()).map { index ->
            val a = artifactArray.path(index)
            NormalizationArtifactInput(
            TradingSourceArtifactId(a.path("artifactId").asLong()),
            TradingArtifactRole.valueOf(a.path("role").asText()), a.path("sha256").asText(),
            a.path("contentLength").asLong(), a.path("mediaType").asText(), a.path("dialect").asText()) }
        val input = NormalizationInput(TradingBookId(rs.getLong("trading_book_id")),
            TradingImportSessionId(rs.getLong("import_session_id")), rs.getLong("owner_member_id"),
            inputs, data.path("venue").asText(), data.path("productFamily").asText(),
            data.path("positionMode").asText(), data.path("settlementAsset").asText(),
            data.path("sourceTimezone").asText(), data.path("reviewTimezone").asText(),
            rs.getString("target_version_set_json"), rs.getString("instrument_terms_json"),
            rs.getString("data_policy_version"),
            rs.getString("retry_policy_version"), rs.getString("retry_policy_json"),
            rs.getLong("deletion_generation"))
        val identity = rs.getString("input_identity_json")?.let {
            NormalizationInputIdentity.fromWireValue(mapper.readValue(it, Map::class.java))
        }
        if (identity != null) verifyIdentity(input, identity, rs.getString("input_fingerprint"))
        return NormalizationRun(rs.getString("id"), input, rs.getString("input_fingerprint"),
            rs.getString("logical_key"), NormalizationRunStatus.valueOf(rs.getString("status")),
            rs.getInt("current_attempt"), rs.getString("attempt_token"),
            rs.getString("compute_job_id"), rs.getString("last_failure_code"),
            rs.getTimestamp("next_attempt_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("started_at")?.toInstant(), rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("completed_at")?.toInstant(), rs.getLong("version"),
            rs.getString("retry_of_run_id"), identity)
    }

    private fun verifyIdentity(input: NormalizationInput, identity: NormalizationInputIdentity,
                               inputFingerprint: String) {
        require(NormalizationInputCanonicalizer.hash(identity) == inputFingerprint) {
            "Persisted normalization identity/hash mismatch"
        }
        val pinnedVersions = mapper.readValue(input.versionSetJson, Map::class.java)
        val expected = NormalizationInputIdentity.fromPinnedInput(input, pinnedVersions)
        require(NormalizationInputCanonicalizer.canonicalize(identity).contentEquals(
            NormalizationInputCanonicalizer.canonicalize(expected))) {
            "Persisted normalization identity/input mismatch"
        }
    }

    private fun dispatch(rs: ResultSet) = NormalizationDispatch(rs.getString("id"), rs.getString("run_id"),
        rs.getString("compute_key"), NormalizationDispatchStatus.valueOf(rs.getString("status")),
        rs.getInt("attempt_count"), rs.getTimestamp("next_attempt_at").toInstant(),
        rs.getString("lease_owner"), rs.getTimestamp("lease_expires_at")?.toInstant(),
        rs.getString("last_failure_code"), rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant())
}
