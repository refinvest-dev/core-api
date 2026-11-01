package com.refinvest.core.tradingreview.adapter.persistence

import java.sql.ResultSet

internal object DeletionRequestRows {
    fun read(r: ResultSet) = DeletionRequestRecord(r.getLong("id"), r.getLong("owner_member_id"),
        r.getString("target_type"), r.getLong("target_id"), r.getString("requested_scope"), r.getString("state"),
        r.getLong("deletion_generation"), r.getLong("account_count"), r.getLong("book_count"),
        r.getLong("import_session_count"), r.getLong("artifact_count"), r.getTimestamp("revoked_at").toInstant(),
        r.getTimestamp("requested_at").toInstant(), r.getTimestamp("updated_at").toInstant(),
        r.getTimestamp("backup_purge_due_at").toInstant(), r.getTimestamp("completed_at")?.toInstant(),
        r.getString("failure_code"), r.getInt("attempt_count"), r.getLong("version"))
}
