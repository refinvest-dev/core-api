package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionRemediation
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.TradingDeletionRemediation
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp

@Repository
class JdbcTradingDeletionRemediationAdapter(private val jdbc: JdbcTemplate) : TradingDeletionRemediation {
    override fun remediate(command: DeletionRemediation): DeletionRequest {
        val failedId = command.failedId
        val newId = command.newId
        val now = command.observedAt
        requireTransaction()
        check(exists("SELECT 1 FROM trading_deletion_requests WHERE id=? FOR UPDATE", failedId.value))
        val failed = requireNotNull(DeletionRequestQueries(jdbc).find("d.id=?", failedId.value))
        require(failed.state == DeletionRequestState.FAILED) { "Only failed deletion can be remediated" }
        DeletionRequestQueries(jdbc).find("d.remediation_of=?", failedId.value)?.let { return it }
        val root = DeletionRequest.remediation(newId, failed, now)
        val r = DeletionRequestPersistenceMapper.toRecord(root)
        jdbc.update("""INSERT INTO trading_deletion_requests(id,owner_member_id,target_type,target_id,
            requested_scope,state,deletion_generation,account_count,book_count,import_session_count,artifact_count,
            revoked_at,requested_at,updated_at,backup_purge_due_at,remediation_of,
            ledger_revision_count,analysis_run_count,reprocessing_run_count,count_snapshot_status,legacy_coverage_unknown)
            SELECT ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,ledger_revision_count,analysis_run_count,
            reprocessing_run_count,count_snapshot_status,legacy_coverage_unknown
            FROM trading_deletion_requests WHERE id=?""", r.id, r.ownerId, r.targetType, r.targetId,
            r.requestedScope, r.state, r.deletionGeneration, r.accountCount, r.bookCount, r.importSessionCount,
            r.artifactCount, Timestamp.from(r.revokedAt), Timestamp.from(r.requestedAt), Timestamp.from(r.updatedAt),
            Timestamp.from(r.backupPurgeDueAt), failedId.value, failedId.value)
        val manifests = mapOf(
            "worksets" to "captured_at,legacy_copies_verified_at",
            "object_tasks" to "artifact_id,object_key,verified_at,session_id,artifact_role",
            "session_tasks" to "session_id,book_id,account_id",
            "runtime_tasks" to "run_id,attempt_token,compute_job_id,deletion_generation,current_deletion_generation,session_id,verified_at",
            "dispatch_tasks" to "dispatch_id,run_id,compute_key",
            "revision_tasks" to "revision_id,verified_at",
            "copy_tasks" to "scope_type,scope_id,artifact_role,verified_at")
        for ((table, columns) in manifests) jdbc.update("""INSERT INTO trading_deletion_$table(request_id,$columns)
            SELECT ?,$columns FROM trading_deletion_$table WHERE request_id=?""", newId.value, failedId.value)
        // Inherit item evidence and fences, while retaining the original failed aggregate unchanged.
        return root
    }

    private fun exists(query: String, vararg args: Any): Boolean =
        jdbc.queryForObject("SELECT EXISTS($query)", Boolean::class.java, *args) == true

    private fun requireTransaction() = check(TransactionSynchronizationManager.isActualTransactionActive()) {
        "Deletion work requires an active transaction"
    }

}
