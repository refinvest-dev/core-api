package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.deletion.DeletionRequestStore
import java.sql.Timestamp
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcDeletionRequestPersistenceAdapter(private val jdbc: JdbcTemplate) : DeletionRequestStore {
    override fun insert(request: DeletionRequest) {
        require(request.state == DeletionRequestState.REQUESTED && request.attemptCount == 0 && request.failureCode == null &&
            request.version == 0L && request.verifiedAbsent.isEmpty())
        val r = DeletionRequestPersistenceMapper.toRecord(request)
        jdbc.update("""INSERT INTO trading_deletion_requests (id, owner_member_id, target_type, target_id,
            requested_scope, state, deletion_generation, account_count, book_count, revoked_at, requested_at,
            updated_at, backup_purge_due_at, import_session_count, artifact_count, attempt_count, completed_at, failure_code)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", r.id, r.ownerId, r.targetType,
            r.targetId, r.requestedScope, r.state, r.deletionGeneration, r.accountCount, r.bookCount,
            Timestamp.from(r.revokedAt), Timestamp.from(r.requestedAt), Timestamp.from(r.updatedAt),
            Timestamp.from(r.backupPurgeDueAt), r.importSessionCount, r.artifactCount, r.attemptCount,
            r.completedAt?.let(Timestamp::from), r.failureCode)
    }
    override fun find(id: DeletionRequestId, owner: MemberId): DeletionRequest? = DeletionRequestQueries(jdbc)
        .find("d.id=? AND d.owner_member_id=?", id.value, owner.value)
    override fun findByTarget(owner: MemberId, scope: DeletionScope): DeletionRequest? = DeletionRequestQueries(jdbc)
        .find("d.owner_member_id=? AND d.target_type=? AND d.target_id=?",
            owner.value, scope.targetType.name, scope.target.value)
}
