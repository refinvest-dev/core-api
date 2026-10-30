package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.valueobject.*
import java.sql.ResultSet
import java.util.UUID

internal object TradingImportRows {
    fun session(rs: ResultSet) = TradingImportSessionRecord(rs.getLong("id"),
        rs.getLong("trading_book_id"), rs.getLong("owner_member_id"),
        rs.getString("source_timezone"), rs.getString("review_timezone"),
        rs.getString("declared_position_mode"), rs.getString("data_policy_version"),
        rs.getString("retention_policy"), rs.getString("masking_policy_version"),
        rs.getString("evidence_retention"), rs.getString("deletion_policy_version"), rs.getString("version_set_json"),
        rs.getString("status"), rs.getLong("deletion_generation"),
        rs.getBoolean("deletion_pending"), rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"),
        rs.getTimestamp("validation_started_at")?.toInstant(), rs.getString("active_normalization_run_id"),
        rs.getString("failure_code")?.let { code -> TradingImportFailureRecord(code,
            rs.getBoolean("failure_retryable"), rs.getString("failure_user_action"),
            rs.getTimestamp("failure_occurred_at").toInstant()) },
        rs.getObject("created_ledger_revision_id", UUID::class.java))

    fun artifact(rs: ResultSet) = TradingSourceArtifactRecord(rs.getLong("id"),
        rs.getLong("import_session_id"), rs.getString("artifact_role"),
        rs.getString("filename"), rs.getString("content_type"), rs.getLong("byte_size"),
        rs.getString("content_sha256"), rs.getString("dialect_candidate"), rs.getString("object_key"),
        rs.getBytes("wrapped_dek"), rs.getString("key_version"),
        rs.getString("retention_status"),
        rs.getTimestamp("uploaded_at").toInstant(), rs.getTimestamp("raw_expires_at").toInstant(),
        rs.getTimestamp("deleted_at")?.toInstant(), rs.getString("deletion_failure"), rs.getLong("version"))
}
