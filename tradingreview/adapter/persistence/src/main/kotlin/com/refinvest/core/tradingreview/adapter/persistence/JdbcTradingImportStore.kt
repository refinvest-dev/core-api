package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.port.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Repository
class JdbcTradingImportStore(private val jdbc: JdbcTemplate) : TradingImportSessionStore, TradingSourceArtifactStore {
    override fun insert(session: TradingImportSession) {
        jdbc.update("""INSERT INTO trading_import_sessions (id, trading_book_id, owner_member_id,
            source_timezone, review_timezone, declared_position_mode, data_policy_version, retention_policy,
            masking_policy_version, evidence_retention, deletion_policy_version, version_set_json, status,
            deletion_generation, deletion_pending, created_at, updated_at, version, validation_started_at,
            active_normalization_run_id, failure_code, failure_retryable, failure_user_action, failure_occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::uuid, ?, ?, ?, ?)""",
            session.id.value, session.bookId.value, session.owner.value, session.sourceTimezone,
            session.reviewTimezone, session.declaredPositionMode.name, session.dataPolicyVersion,
            session.retentionPolicy, session.maskingPolicyVersion, session.evidenceRetention,
            session.deletionPolicyVersion, session.versionSetJson, session.status.name,
            session.deletionGeneration, session.deletionPending, Timestamp.from(session.createdAt),
            Timestamp.from(session.updatedAt), session.version, session.validationStartedAt?.let(Timestamp::from),
            session.activeNormalizationRunId, session.failure?.code, session.failure?.retryable,
            session.failure?.userAction, session.failure?.occurredAt?.let(Timestamp::from))
    }

    override fun find(id: TradingImportSessionId, owner: MemberId, forUpdate: Boolean): TradingImportSession? =
        jdbc.query("SELECT * FROM trading_import_sessions WHERE id=? AND owner_member_id=?" +
            if (forUpdate) " FOR UPDATE" else "", { rs, _ -> session(rs) }, id.value, owner.value).firstOrNull()

    override fun list(bookId: TradingBookId, owner: MemberId, page: Int, size: Int): TradingPage<TradingImportSession> {
        val total = jdbc.queryForObject("""SELECT count(*) FROM trading_import_sessions
            WHERE trading_book_id=? AND owner_member_id=? AND deletion_pending=false""",
            Long::class.java, bookId.value, owner.value) ?: 0L
        val items = jdbc.query("""SELECT * FROM trading_import_sessions WHERE trading_book_id=?
            AND owner_member_id=? AND deletion_pending=false ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?""",
            { rs, _ -> session(rs) }, bookId.value, owner.value, size, page.toLong() * size)
        return TradingPage(items, page, size, total)
    }

    override fun update(session: TradingImportSession): Boolean = jdbc.update("""UPDATE trading_import_sessions
        SET status=?, deletion_generation=?, deletion_pending=?, updated_at=?,
        validation_started_at=?, active_normalization_run_id=?::uuid, failure_code=?,
        failure_retryable=?, failure_user_action=?, failure_occurred_at=?, version=version+1
        WHERE id=? AND owner_member_id=? AND version=?""", session.status.name, session.deletionGeneration,
        session.deletionPending, Timestamp.from(session.updatedAt),
        session.validationStartedAt?.let(Timestamp::from), session.activeNormalizationRunId,
        session.failure?.code, session.failure?.retryable, session.failure?.userAction,
        session.failure?.occurredAt?.let(Timestamp::from), session.id.value,
        session.owner.value, session.version) == 1

    override fun deleted(id: TradingImportSessionId, owner: MemberId): Boolean = jdbc.queryForObject(
        """SELECT EXISTS(SELECT 1 FROM trading_deleted_resources WHERE target_type='TRADING_IMPORT_SESSION'
            AND target_id=? AND owner_member_id=?)""", Boolean::class.java, id.value, owner.value) == true

    override fun insert(artifact: TradingSourceArtifact) {
        jdbc.update("""INSERT INTO trading_source_artifacts (id, import_session_id, artifact_role, filename,
            content_type, byte_size, content_sha256, dialect_candidate, object_key, wrapped_dek, key_version,
            upload_status, retention_status, uploaded_at, raw_expires_at, deleted_at, deletion_failure, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UPLOADED', ?, ?, ?, ?, ?, ?)""",
            artifact.id.value, artifact.sessionId.value, artifact.role.name, artifact.filename, artifact.contentType,
            artifact.byteSize, artifact.sha256, artifact.dialectCandidate, artifact.objectKey, artifact.wrappedDek,
            artifact.keyVersion, artifact.retentionStatus.name, Timestamp.from(artifact.uploadedAt),
            Timestamp.from(artifact.rawExpiresAt), artifact.deletedAt?.let(Timestamp::from),
            artifact.deletionFailure, artifact.version)
    }

    override fun find(id: TradingSourceArtifactId, owner: MemberId, forUpdate: Boolean): TradingSourceArtifact? =
        jdbc.query("""SELECT a.* FROM trading_source_artifacts a JOIN trading_import_sessions s
            ON s.id=a.import_session_id WHERE a.id=? AND s.owner_member_id=?""" +
            if (forUpdate) " FOR UPDATE OF a" else "", { rs, _ -> artifact(rs) }, id.value, owner.value).firstOrNull()

    override fun findByRole(sessionId: TradingImportSessionId, role: TradingArtifactRole): TradingSourceArtifact? =
        jdbc.query("SELECT * FROM trading_source_artifacts WHERE import_session_id=? AND artifact_role=?",
            { rs, _ -> artifact(rs) }, sessionId.value, role.name).firstOrNull()

    override fun list(sessionId: TradingImportSessionId): List<TradingSourceArtifact> =
        jdbc.query("SELECT * FROM trading_source_artifacts WHERE import_session_id=? ORDER BY artifact_role",
            { rs, _ -> artifact(rs) }, sessionId.value)

    override fun markDeletionPending(sessionId: TradingImportSessionId, now: Instant) {
        jdbc.update("""UPDATE trading_source_artifacts SET retention_status='DELETION_PENDING', version=version+1
            WHERE import_session_id=? AND retention_status IN ('ACTIVE','RETENTION_SCHEDULED')""", sessionId.value)
    }

    override fun markDeletionPending(id: TradingSourceArtifactId, now: Instant): Boolean = jdbc.update("""
        UPDATE trading_source_artifacts SET retention_status='DELETION_PENDING', version=version+1
        WHERE id=? AND retention_status IN ('ACTIVE','RETENTION_SCHEDULED')""", id.value) == 1

    override fun markDeleted(id: TradingSourceArtifactId, now: Instant): Boolean = jdbc.update("""UPDATE trading_source_artifacts
        SET retention_status='DELETED', object_key=NULL, wrapped_dek=NULL, key_version=NULL, filename=NULL,
        deleted_at=?, deletion_failure=NULL, deletion_claim_until=NULL, version=version+1 WHERE id=? AND retention_status='DELETION_PENDING'""",
        Timestamp.from(now), id.value) == 1

    override fun markDeletionFailed(id: TradingSourceArtifactId, code: String, now: Instant) {
        jdbc.update("""UPDATE trading_source_artifacts SET retention_status=CASE WHEN deletion_attempt_count >= 4
            THEN 'DELETION_FAILED' ELSE 'DELETION_PENDING' END, deletion_attempt_count=deletion_attempt_count+1,
            deletion_failure=?, deletion_claim_until=NULL, version=version+1 WHERE id=? AND retention_status='DELETION_PENDING'""", code, id.value)
    }

    override fun claimDue(now: Instant, limit: Int): List<TradingSourceArtifact> = jdbc.query("""WITH due AS (
        SELECT id FROM trading_source_artifacts WHERE
        ((retention_status IN ('ACTIVE','RETENTION_SCHEDULED') AND raw_expires_at <= CURRENT_TIMESTAMP)
        OR retention_status='DELETION_PENDING')
        AND (deletion_claim_until IS NULL OR deletion_claim_until <= CURRENT_TIMESTAMP)
        ORDER BY raw_expires_at, id LIMIT ? FOR UPDATE SKIP LOCKED)
        UPDATE trading_source_artifacts a SET retention_status='DELETION_PENDING',
        deletion_claim_until=CURRENT_TIMESTAMP + INTERVAL '5 minutes', version=version+1
        FROM due WHERE a.id=due.id RETURNING a.*""", { rs, _ -> artifact(rs) }, limit)

    override fun referencesObjectKey(key: String): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM trading_source_artifacts WHERE object_key=?)", Boolean::class.java, key) == true

    private fun session(rs: ResultSet) = TradingImportSession(TradingImportSessionId(rs.getLong("id")),
        TradingBookId(rs.getLong("trading_book_id")), MemberId(rs.getLong("owner_member_id")),
        rs.getString("source_timezone"), rs.getString("review_timezone"),
        TradingPositionMode.valueOf(rs.getString("declared_position_mode")), rs.getString("data_policy_version"),
        rs.getString("retention_policy"), rs.getString("masking_policy_version"),
        rs.getString("evidence_retention"), rs.getString("deletion_policy_version"), rs.getString("version_set_json"),
        TradingImportStatus.valueOf(rs.getString("status")), rs.getLong("deletion_generation"),
        rs.getBoolean("deletion_pending"), rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"),
        rs.getTimestamp("validation_started_at")?.toInstant(), rs.getString("active_normalization_run_id"),
        rs.getString("failure_code")?.let { code -> TradingImportFailure(code,
            rs.getBoolean("failure_retryable"), rs.getString("failure_user_action"),
            rs.getTimestamp("failure_occurred_at").toInstant()) })

    private fun artifact(rs: ResultSet) = TradingSourceArtifact(TradingSourceArtifactId(rs.getLong("id")),
        TradingImportSessionId(rs.getLong("import_session_id")), TradingArtifactRole.valueOf(rs.getString("artifact_role")),
        rs.getString("filename"), rs.getString("content_type"), rs.getLong("byte_size"),
        rs.getString("content_sha256"), rs.getString("dialect_candidate"), rs.getString("object_key"),
        rs.getBytes("wrapped_dek"), rs.getString("key_version"),
        TradingArtifactRetentionStatus.valueOf(rs.getString("retention_status")),
        rs.getTimestamp("uploaded_at").toInstant(), rs.getTimestamp("raw_expires_at").toInstant(),
        rs.getTimestamp("deleted_at")?.toInstant(), rs.getString("deletion_failure"), rs.getLong("version"))
}
