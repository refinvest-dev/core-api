package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.importsession.*
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.sql.ResultSet

internal object NormalizationRunRows {
    fun record(rs: ResultSet) = NormalizationRunRecord(
        id = rs.getString("id"),
        retryOfRunId = rs.getString("retry_of_run_id"),
        importSessionId = rs.getLong("import_session_id"),
        tradingBookId = rs.getLong("trading_book_id"),
        ownerMemberId = rs.getLong("owner_member_id"),
        inputFingerprint = rs.getString("input_fingerprint"),
        logicalKey = rs.getString("logical_key"),
        inputJson = rs.getString("input_json"),
        inputIdentityJson = rs.getString("input_identity_json"),
        targetVersionSetJson = rs.getString("target_version_set_json"),
        instrumentTermsJson = rs.getString("instrument_terms_json"),
        dataPolicyVersion = rs.getString("data_policy_version"),
        retryPolicyVersion = rs.getString("retry_policy_version"),
        retryPolicyJson = rs.getString("retry_policy_json"),
        resultDeliveryPolicyJson = rs.getString("result_delivery_policy_json"),
        deletionGeneration = rs.getLong("deletion_generation"),
        status = rs.getString("status"),
        currentAttempt = rs.getInt("current_attempt"),
        attemptToken = rs.getString("attempt_token"),
        computeJobId = rs.getString("compute_job_id"),
        lastFailureCode = rs.getString("last_failure_code"),
        nextAttemptAt = rs.getTimestamp("next_attempt_at").toInstant(),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        startedAt = rs.getTimestamp("started_at")?.toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
        completedAt = rs.getTimestamp("completed_at")?.toInstant(),
        version = rs.getLong("version"),
        resultSchemaVersion = rs.getString("result_schema_version"),
        resultHashSpecificationVersion = rs.getString("result_hash_specification_version"),
    )
}
