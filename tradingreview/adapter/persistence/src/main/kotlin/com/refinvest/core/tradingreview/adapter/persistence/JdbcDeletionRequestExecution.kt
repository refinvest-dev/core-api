package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.port.deletion.DeletionRequestExecution
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Timestamp

@Repository
class JdbcDeletionRequestExecution(private val jdbc: JdbcTemplate) : DeletionRequestExecution {
    override fun lockNext(): DeletionRequest? {
        requireTransaction()
        val id = jdbc.query("""SELECT id FROM trading_deletion_requests
            WHERE state NOT IN ('COMPLETED','FAILED') ORDER BY attempt_count, updated_at, requested_at, id
            LIMIT 1 FOR UPDATE SKIP LOCKED""", { r, _ -> r.getLong(1) }).firstOrNull() ?: return null
        return DeletionRequestQueries(jdbc).find("d.id=?", id)
    }

    override fun commit(expected: DeletionRequest, updated: DeletionRequest): Boolean {
        requireTransaction()
        require(!expected.state.terminal && updated.version > expected.version)
        require(updated.state != com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestState.COMPLETED ||
            updated.verifiedAbsent.keys.containsAll(updated.checklist.requiredComponents))
        require(expected.id == updated.id && expected.owner == updated.owner && expected.scope == updated.scope &&
            expected.deletionGeneration == updated.deletionGeneration && expected.requestedAt == updated.requestedAt &&
            expected.revokedAt == updated.revokedAt && expected.affectedResources == updated.affectedResources)
        require(expected.verifiedAbsent.all { (component, at) -> updated.verifiedAbsent[component] == at })
        val r = DeletionRequestPersistenceMapper.toRecord(updated)
        val saved = jdbc.update("""UPDATE trading_deletion_requests SET state=?, attempt_count=?, failure_code=?,
            updated_at=?, completed_at=?, backup_purge_due_at=?, version=?
            WHERE id=? AND owner_member_id=? AND target_type=? AND target_id=? AND deletion_generation=?
            AND state=? AND version=?""", r.state, r.attemptCount, r.failureCode, Timestamp.from(r.updatedAt),
            r.completedAt?.let(Timestamp::from), Timestamp.from(r.backupPurgeDueAt), r.version, r.id, r.ownerId,
            r.targetType, r.targetId, r.deletionGeneration, expected.state.name, expected.version) == 1
        if (!saved) return false
        for ((component, at) in updated.verifiedAbsent - expected.verifiedAbsent.keys) {
            jdbc.update("INSERT INTO trading_deletion_checkpoints(request_id,component,verified_at) VALUES (?,?,?)",
                r.id, component.name, Timestamp.from(at))
        }
        return true
    }

    private fun requireTransaction() = check(TransactionSynchronizationManager.isActualTransactionActive()) {
        "Deletion execution requires an active transaction"
    }
}
