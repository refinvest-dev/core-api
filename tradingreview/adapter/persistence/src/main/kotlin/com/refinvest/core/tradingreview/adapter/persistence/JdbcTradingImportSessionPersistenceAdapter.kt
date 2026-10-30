package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.*
import com.refinvest.core.tradingreview.port.importsession.*
import java.sql.Timestamp
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class JdbcTradingImportSessionPersistenceAdapter(private val jdbc: JdbcTemplate) :
    TradingImportSessionStore, TradingImportSessionReader, TradingSourceArtifactReader {

    @Transactional
    override fun insert(session: TradingImportSession) {
        requireTransaction()
        val record = TradingImportSessionPersistenceMapper.toRecord(session)
        require(record.version == 0L && session.status == TradingImportStatus.RECEIVED &&
            record.deletionGeneration == 0L && !record.deletionPending && session.artifacts.isEmpty() &&
            record.activeNormalizationRunId == null && session.createdLedgerRevisionId == null)
        val accountId = jdbc.query("SELECT account_id FROM trading_books WHERE id=? AND owner_member_id=?",
            { rs, _ -> rs.getLong(1) }, record.bookId, record.owner).firstOrNull()
            ?: throw IllegalArgumentException("Session parent is missing")
        // Match the Account -> Book -> Session order used by admission, upload and deletion.
        val account = jdbc.query("SELECT status FROM trading_accounts WHERE id=? AND owner_member_id=? FOR UPDATE",
            { rs, _ -> rs.getString(1) }, accountId, record.owner).firstOrNull()
        require(account == "ACTIVE") { "Session account is not active" }
        val parent = jdbc.query("""SELECT status FROM trading_books
            WHERE id=? AND owner_member_id=? AND position_mode=? FOR UPDATE""",
            { rs, _ -> rs.getString(1) }, record.bookId, record.owner, record.declaredPositionMode).firstOrNull()
        require(parent == "ACTIVE") { "Session requires an active parent with matching owner and mode" }
        jdbc.update("""INSERT INTO trading_import_sessions (id, trading_book_id, owner_member_id,
            source_timezone, review_timezone, declared_position_mode, data_policy_version, retention_policy,
            masking_policy_version, evidence_retention, deletion_policy_version, version_set_json, status,
            deletion_generation, deletion_pending, created_at, updated_at, version, validation_started_at,
            active_normalization_run_id, failure_code, failure_retryable, failure_user_action, failure_occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::uuid, ?, ?, ?, ?)""",
            record.id, record.bookId, record.owner, record.sourceTimezone,
            record.reviewTimezone, record.declaredPositionMode, record.dataPolicyVersion,
            record.retentionPolicy, record.maskingPolicyVersion, record.evidenceRetention,
            record.deletionPolicyVersion, record.versionSetJson, record.status,
            record.deletionGeneration, record.deletionPending, Timestamp.from(record.createdAt),
            Timestamp.from(record.updatedAt), record.version, record.validationStartedAt?.let(Timestamp::from),
            record.activeNormalizationRunId, record.failure?.code, record.failure?.retryable,
            record.failure?.userAction, record.failure?.occurredAt?.let(Timestamp::from))
    }


    override fun find(id: TradingImportSessionId, owner: MemberId): TradingImportSession? = find(id, owner, false)

    override fun find(id: TradingImportSessionId, owner: MemberId, forUpdate: Boolean): TradingImportSession? {
        if (forUpdate) requireTransaction()
        val record = jdbc.query("SELECT * FROM trading_import_sessions WHERE id=? AND owner_member_id=?" +
            if (forUpdate) " FOR UPDATE" else "", { rs, _ -> TradingImportRows.session(rs) }, id.value, owner.value).firstOrNull()
            ?: return null
        return TradingImportSessionPersistenceMapper.toDomain(record.copy(artifacts = artifactRecords(id)))
    }

    override fun list(bookId: TradingBookId, owner: MemberId, page: Int, size: Int): TradingPage<TradingImportSession> {
        val total = jdbc.queryForObject("""SELECT count(*) FROM trading_import_sessions
            WHERE trading_book_id=? AND owner_member_id=? AND deletion_pending=false""",
            Long::class.java, bookId.value, owner.value) ?: 0L
        val items = jdbc.query("""SELECT * FROM trading_import_sessions WHERE trading_book_id=?
            AND owner_member_id=? AND deletion_pending=false ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?""",
            { rs, _ -> TradingImportSessionPersistenceMapper.toDomain(TradingImportRows.session(rs).let { it.copy(artifacts = artifactRecords(TradingImportSessionId(it.id))) }) }, bookId.value, owner.value, size, page.toLong() * size)
        return TradingPage(items, page, size, total)
    }


    @Transactional
    override fun update(session: TradingImportSession): Boolean {
        requireTransaction()
        val current = find(session.id, session.owner, true) ?: return false
        val before = TradingImportSessionPersistenceMapper.toRecord(current)
        val after = TradingImportSessionPersistenceMapper.toRecord(session)
        if (before.version != after.version || before.deletionPending || after.updatedAt < before.updatedAt) return false
        if (before.copy(status = after.status, deletionGeneration = after.deletionGeneration,
                deletionPending = after.deletionPending, updatedAt = after.updatedAt,
                validationStartedAt = after.validationStartedAt, activeNormalizationRunId = after.activeNormalizationRunId,
                failure = after.failure, artifacts = emptyList()) != after.copy(artifacts = emptyList())) return false
        val generationAdvanced = before.deletionGeneration < Long.MAX_VALUE && after.deletionGeneration == before.deletionGeneration + 1
        val stateAllowed = when {
            after.deletionPending -> generationAdvanced && after.status == before.status &&
                after.activeNormalizationRunId == before.activeNormalizationRunId &&
                after.validationStartedAt == before.validationStartedAt && after.failure == before.failure
            generationAdvanced -> after.status == (if (before.status == "VALIDATING") "PROCESSING_FAILED" else before.status) &&
                after.activeNormalizationRunId == before.activeNormalizationRunId && after.validationStartedAt == before.validationStartedAt
            after.deletionGeneration != before.deletionGeneration -> false
            before.status == after.status -> after.activeNormalizationRunId == before.activeNormalizationRunId &&
                after.validationStartedAt == before.validationStartedAt && after.failure == before.failure
            after.status == "VALIDATING" -> before.status in setOf("RECEIVED", "PROCESSING_FAILED")
            before.status == "VALIDATING" -> after.status in setOf("REJECTED", "PROCESSING_FAILED") &&
                before.activeNormalizationRunId == after.activeNormalizationRunId && before.validationStartedAt == after.validationStartedAt
            else -> false
        }
        if (!stateAllowed) return false
        val storedArtifacts = artifactRecords(session.id, true)
        if (storedArtifacts.size > after.artifacts.size) return false
        for (stored in storedArtifacts) {
            val next = after.artifacts.singleOrNull { it.id == stored.id } ?: return false
            if (!sameArtifact(stored, next)) {
                if (!generationAdvanced || stored.retentionStatus !in setOf("ACTIVE", "RETENTION_SCHEDULED") ||
                    next.retentionStatus != "DELETION_PENDING" || !sameArtifact(stored.copy(retentionStatus = next.retentionStatus), next)) return false
            }
        }
        val added = after.artifacts.filter { next -> storedArtifacts.none { it.id == next.id } }
        if (added.isNotEmpty() && (before.status != "RECEIVED" || after.status != "RECEIVED" || generationAdvanced ||
                added.any { it.version != 0L || it.retentionStatus != "ACTIVE" || it.sessionId != after.id })) return false
        val pendingTransitions = storedArtifacts.count { stored ->
            stored.retentionStatus != "DELETION_PENDING" && after.artifacts.single { it.id == stored.id }.retentionStatus == "DELETION_PENDING"
        }
        if (generationAdvanced && !after.deletionPending && pendingTransitions != 1) return false
        if (after.deletionPending && after.artifacts.any { it.retentionStatus in setOf("ACTIVE", "RETENTION_SCHEDULED") }) return false
        val changed = jdbc.update("""UPDATE trading_import_sessions SET status=?, deletion_generation=?, deletion_pending=?,
            updated_at=?, validation_started_at=?, active_normalization_run_id=?::uuid, failure_code=?,
            failure_retryable=?, failure_user_action=?, failure_occurred_at=?, version=version+1
            WHERE id=? AND owner_member_id=? AND version=? AND EXISTS (
                SELECT 1 FROM trading_books b JOIN trading_accounts a ON a.id=b.account_id
                WHERE b.id=trading_import_sessions.trading_book_id AND b.owner_member_id=trading_import_sessions.owner_member_id
                AND b.status='ACTIVE' AND a.status='ACTIVE')""", after.status, after.deletionGeneration,
            after.deletionPending, Timestamp.from(after.updatedAt), after.validationStartedAt?.let(Timestamp::from),
            after.activeNormalizationRunId, after.failure?.code, after.failure?.retryable, after.failure?.userAction,
            after.failure?.occurredAt?.let(Timestamp::from), after.id, after.owner, after.version)
        if (changed != 1) return false
        added.forEach(::insertArtifact)
        storedArtifacts.forEach { stored ->
            val next = after.artifacts.single { it.id == stored.id }
            if (next.retentionStatus != stored.retentionStatus) check(jdbc.update("""UPDATE trading_source_artifacts
                SET retention_status=?, version=version+1 WHERE id=? AND version=?""",
                next.retentionStatus, next.id, next.version) == 1)
        }
        return true
    }

    private fun sameArtifact(left: TradingSourceArtifactRecord, right: TradingSourceArtifactRecord) =
        left.copy(wrappedDek = null) == right.copy(wrappedDek = null) &&
            java.util.Arrays.equals(left.wrappedDek, right.wrappedDek)

    private fun requireTransaction() = check(jdbc.execute(ConnectionCallback { !it.autoCommit }) == true) {
        "Command write or lock requires an enclosing transaction"
    }

    private fun artifactRecords(id: TradingImportSessionId, forUpdate: Boolean = false) = jdbc.query(
        "SELECT * FROM trading_source_artifacts WHERE import_session_id=? ORDER BY artifact_role" +
            if (forUpdate) " FOR UPDATE" else "", { rs, _ -> TradingImportRows.artifact(rs) }, id.value)

    private fun insertArtifact(artifact: TradingSourceArtifactRecord) {
        jdbc.update("""INSERT INTO trading_source_artifacts (id, import_session_id, artifact_role, filename,
            content_type, byte_size, content_sha256, dialect_candidate, object_key, wrapped_dek, key_version,
            upload_status, retention_status, uploaded_at, raw_expires_at, deleted_at, deletion_failure, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UPLOADED', ?, ?, ?, ?, ?, ?)""",
            artifact.id, artifact.sessionId, artifact.role, artifact.filename, artifact.contentType,
            artifact.byteSize, artifact.sha256, artifact.dialectCandidate, artifact.objectKey, artifact.wrappedDek,
            artifact.keyVersion, artifact.retentionStatus, Timestamp.from(artifact.uploadedAt),
            Timestamp.from(artifact.rawExpiresAt), artifact.deletedAt?.let(Timestamp::from),
            artifact.deletionFailure, artifact.version)
    }

    override fun deleted(id: TradingImportSessionId, owner: MemberId): Boolean = jdbc.queryForObject(
        """SELECT EXISTS(SELECT 1 FROM trading_deleted_resources WHERE target_type='TRADING_IMPORT_SESSION'
            AND target_id=? AND owner_member_id=?)""", Boolean::class.java, id.value, owner.value) == true

    override fun find(id: TradingSourceArtifactId, owner: MemberId): TradingSourceArtifact? =
        jdbc.query("""SELECT a.* FROM trading_source_artifacts a JOIN trading_import_sessions s
            ON s.id=a.import_session_id WHERE a.id=? AND s.owner_member_id=?""", { rs, _ -> TradingImportSessionPersistenceMapper.toDomain(TradingImportRows.artifact(rs)) }, id.value, owner.value).firstOrNull()

    override fun findByRole(sessionId: TradingImportSessionId, role: TradingArtifactRole): TradingSourceArtifact? =
        jdbc.query("SELECT * FROM trading_source_artifacts WHERE import_session_id=? AND artifact_role=?",
            { rs, _ -> TradingImportSessionPersistenceMapper.toDomain(TradingImportRows.artifact(rs)) }, sessionId.value, role.name).firstOrNull()

    override fun list(sessionId: TradingImportSessionId): List<TradingSourceArtifact> =
        jdbc.query("SELECT * FROM trading_source_artifacts WHERE import_session_id=? ORDER BY artifact_role",
            { rs, _ -> TradingImportSessionPersistenceMapper.toDomain(TradingImportRows.artifact(rs)) }, sessionId.value)

    override fun referencesObjectKey(key: String): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM trading_source_artifacts WHERE object_key=?)", Boolean::class.java, key) == true

}
