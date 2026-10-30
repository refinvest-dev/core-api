package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.*
import java.sql.ResultSet
import java.sql.Timestamp
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcTradingDeletionStoreAdapter(private val jdbc: JdbcTemplate) : TradingDeletionStore {
    override fun insert(deletion: TradingDeletion) {
        jdbc.update("""INSERT INTO trading_deletion_requests (id, owner_member_id, target_type, target_id,
            requested_scope, state, deletion_generation, account_count, book_count, revoked_at, requested_at,
            updated_at, backup_purge_due_at, import_session_count, artifact_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", deletion.id, deletion.owner.value, deletion.targetType,
            deletion.targetId, deletion.requestedScope, deletion.state, deletion.deletionGeneration,
            deletion.accountCount, deletion.bookCount, Timestamp.from(deletion.revokedAt),
            Timestamp.from(deletion.requestedAt), Timestamp.from(deletion.updatedAt), Timestamp.from(deletion.backupPurgeDueAt),
            deletion.importSessionCount, deletion.artifactCount)
    }

    override fun find(id: Long, owner: MemberId): TradingDeletion? = jdbc.query(
        "SELECT * FROM trading_deletion_requests WHERE id=? AND owner_member_id=?",
        { rs, _ -> deletion(rs) }, id, owner.value).firstOrNull()

    override fun findByTarget(owner: MemberId, targetType: String, targetId: Long): TradingDeletion? = jdbc.query(
        "SELECT * FROM trading_deletion_requests WHERE owner_member_id=? AND target_type=? AND target_id=?",
        { rs, _ -> deletion(rs) }, owner.value, targetType, targetId).firstOrNull()

    private fun deletion(rs: ResultSet) = TradingDeletion(rs.getLong("id"), MemberId(rs.getLong("owner_member_id")),
        rs.getString("target_type"), rs.getLong("target_id"), rs.getString("requested_scope"), rs.getString("state"),
        rs.getLong("deletion_generation"),
        rs.getLong("account_count"), rs.getLong("book_count"), rs.getTimestamp("revoked_at").toInstant(),
        rs.getTimestamp("requested_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
        rs.getTimestamp("backup_purge_due_at").toInstant(),
        rs.getTimestamp("completed_at")?.toInstant(), rs.getString("failure_code"),
        rs.getLong("import_session_count"), rs.getLong("artifact_count"))
}
