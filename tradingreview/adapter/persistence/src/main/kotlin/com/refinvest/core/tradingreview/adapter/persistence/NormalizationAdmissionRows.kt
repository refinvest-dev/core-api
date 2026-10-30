package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.importsession.*
import com.refinvest.core.tradingreview.domain.valueobject.*
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp

internal class NormalizationAdmissionRows(private val jdbc: JdbcTemplate) {
    fun insert(run: NormalizationRun, dispatch: NormalizationDispatch) {
        require(run.resultVersionPin == dispatch.resultVersionPin)
        require(dispatch.runId == run.id.value)
        val record = NormalizationRunPersistenceMapper.toRecord(run)
        jdbc.update("""INSERT INTO trading_normalization_runs
            (id, retry_of_run_id, import_session_id, trading_book_id, owner_member_id, input_fingerprint, logical_key,
            input_json, input_identity_json, target_version_set_json, instrument_terms_json, data_policy_version,
            retry_policy_version, retry_policy_json, result_delivery_policy_json,
            deletion_generation, status, current_attempt, attempt_token, compute_job_id,
            last_failure_code, next_attempt_at, created_at, started_at, updated_at, completed_at, version,
            result_schema_version,result_hash_specification_version)
            VALUES (?::uuid,?::uuid,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?::jsonb,?::jsonb,?,?,?,?::uuid,?,?,?,?,?,?,?,?,?,?)""",
            record.id, record.retryOfRunId, record.importSessionId, record.tradingBookId, record.ownerMemberId,
            record.inputFingerprint, record.logicalKey, record.inputJson, record.inputIdentityJson, record.targetVersionSetJson,
            record.instrumentTermsJson, record.dataPolicyVersion, record.retryPolicyVersion,
            record.retryPolicyJson,
            record.resultDeliveryPolicyJson, record.deletionGeneration,
            record.status, record.currentAttempt, record.attemptToken, record.computeJobId, record.lastFailureCode,
            Timestamp.from(record.nextAttemptAt), Timestamp.from(record.createdAt), record.startedAt?.let(Timestamp::from),
            Timestamp.from(record.updatedAt), record.completedAt?.let(Timestamp::from), record.version,
            record.resultSchemaVersion, record.resultHashSpecificationVersion)
        jdbc.update("""INSERT INTO trading_normalization_attempts
            (run_id,attempt_number,attempt_token,started_at) VALUES (?::uuid,?,?::uuid,?)""",
            record.id, record.currentAttempt, record.attemptToken, Timestamp.from(record.createdAt))
        jdbc.update("""INSERT INTO trading_normalization_dispatches
            (id,run_id,compute_key,status,attempt_count,next_attempt_at,lease_owner,lease_expires_at,
            last_failure_code,created_at,updated_at,result_schema_version,result_hash_specification_version)
            VALUES (?::uuid,?::uuid,?,?,?,?,?,?,?,?,?,?,?)""",
            dispatch.id, dispatch.runId, dispatch.computeKey, dispatch.status.name,
            dispatch.attemptCount, Timestamp.from(dispatch.nextAttemptAt), null,
            null, dispatch.lastFailureCode, Timestamp.from(dispatch.createdAt), Timestamp.from(dispatch.updatedAt),
            dispatch.resultVersionPin.schemaVersion, dispatch.resultVersionPin.hashSpecificationVersion)
    }

}
