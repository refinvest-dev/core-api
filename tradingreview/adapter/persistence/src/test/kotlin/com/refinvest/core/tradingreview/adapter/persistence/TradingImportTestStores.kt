package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.importsession.*
import java.sql.Timestamp
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** Fixture composition only. Production writes artifacts exclusively through the Session Store. */
internal class TradingImportTestStores(
    private val jdbc: JdbcTemplate,
    private val aggregate: JdbcTradingImportSessionPersistenceAdapter = JdbcTradingImportSessionPersistenceAdapter(jdbc),
) : TradingImportSessionStore by aggregate, TradingImportSessionReader by aggregate, TradingSourceArtifactReader by aggregate {
    override fun deleted(id: TradingImportSessionId, owner: MemberId) = aggregate.deleted(id, owner)

    override fun insert(session: TradingImportSession) {
        if (jdbc.execute(ConnectionCallback { !it.autoCommit }) == true) aggregate.insert(session)
        else TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbc.dataSource))).executeWithoutResult { aggregate.insert(session) }
    }

    override fun update(session: TradingImportSession): Boolean =
        if (jdbc.execute(ConnectionCallback { !it.autoCommit }) == true) aggregate.update(session)
        else TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbc.dataSource))).execute { aggregate.update(session) }!!

    fun seedValidation(id: TradingImportSessionId, runId: String, now: java.time.Instant): Boolean = jdbc.update(
        """UPDATE trading_import_sessions SET status='VALIDATING',active_normalization_run_id=?::uuid,
            validation_started_at=?,updated_at=?,version=version+1 WHERE id=?""",
        runId, Timestamp.from(now), Timestamp.from(now), id.value) == 1

    fun seedArtifact(artifact: TradingSourceArtifact) {
        val record = TradingImportSessionPersistenceMapper.toRecord(artifact)
        jdbc.update("""INSERT INTO trading_source_artifacts (id, import_session_id, artifact_role, filename,
            content_type, byte_size, content_sha256, dialect_candidate, object_key, wrapped_dek, key_version,
            upload_status, retention_status, uploaded_at, raw_expires_at, deleted_at, deletion_failure, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UPLOADED', ?, ?, ?, ?, ?, ?)""",
            record.id, record.sessionId, record.role, record.filename, record.contentType, record.byteSize,
            record.sha256, record.dialectCandidate, record.objectKey, record.wrappedDek, record.keyVersion,
            record.retentionStatus, Timestamp.from(record.uploadedAt), Timestamp.from(record.rawExpiresAt),
            record.deletedAt?.let(Timestamp::from), record.deletionFailure, record.version)
    }
}
