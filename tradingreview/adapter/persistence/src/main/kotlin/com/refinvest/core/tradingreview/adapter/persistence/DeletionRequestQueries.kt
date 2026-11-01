package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.DeletionRequest
import org.springframework.jdbc.core.JdbcTemplate

/** One statement observes the root version and its checkpoints from the same DB snapshot. */
internal class DeletionRequestQueries(private val jdbc: JdbcTemplate) {
    fun find(condition: String, vararg args: Any): DeletionRequest? {
        val rows = jdbc.query("""SELECT d.*, c.component, c.verified_at
            FROM trading_deletion_requests d LEFT JOIN trading_deletion_checkpoints c ON c.request_id=d.id
            WHERE $condition""", { r, _ ->
            Triple(DeletionRequestRows.read(r), r.getString("component"), r.getTimestamp("verified_at")?.toInstant())
        }, *args)
        val record = rows.firstOrNull()?.first ?: return null
        check(rows.all { it.first == record })
        return DeletionRequestPersistenceMapper.toDomain(record.copy(verifiedAbsent = rows.mapNotNull { (_, component, at) ->
            component?.let { it to requireNotNull(at) }
        }.toMap()))
    }
}
