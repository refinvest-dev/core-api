package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.port.outbound.artifactgrant.ArtifactGrantBinding
import com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant.ArtifactGrantCandidate
import com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant.ArtifactGrantIssueRecord
import com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant.ArtifactGrantIssueStore
import java.sql.Timestamp
import java.time.Instant
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcArtifactGrantIssueStore(private val jdbc: JdbcTemplate) : ArtifactGrantIssueStore {
    override fun loadEligible(binding: ArtifactGrantBinding, observedAt: Instant): ArtifactGrantCandidate? =
        jdbc.query("""SELECT a.object_key, a.byte_size, a.content_sha256, a.content_type,
            a.artifact_role FROM trading_source_artifacts a JOIN trading_import_sessions s
            ON s.id=a.import_session_id
            JOIN trading_books b ON b.id=s.trading_book_id AND b.owner_member_id=s.owner_member_id
            JOIN trading_accounts c ON c.id=b.account_id AND c.owner_member_id=b.owner_member_id
            JOIN trading_normalization_runs r ON r.id=s.active_normalization_run_id
            JOIN trading_normalization_dispatches d ON d.run_id=r.id
            WHERE a.id=? AND a.import_session_id=? AND d.id=?::uuid
            AND b.status='ACTIVE' AND c.status='ACTIVE'
            AND r.trading_book_id=b.id AND r.owner_member_id=s.owner_member_id
            AND s.status='VALIDATING' AND NOT s.deletion_pending AND
            s.deletion_generation=? AND r.deletion_generation=? AND r.attempt_token=?::uuid AND
            a.retention_status='ACTIVE' AND a.raw_expires_at>? AND a.deleted_at IS NULL""",
            { rs, _ -> ArtifactGrantCandidate(rs.getString("object_key"), rs.getLong("byte_size"),
                rs.getString("content_sha256"), rs.getString("content_type"), rs.getString("artifact_role")) },
            binding.artifact.artifactId.value, binding.sessionId.value, binding.logicalJobId,
            binding.deletionGeneration, binding.deletionGeneration, binding.attemptToken,
            Timestamp.from(observedAt)).firstOrNull()

    override fun recordIfEligible(record: ArtifactGrantIssueRecord): Boolean {
        val binding = record.binding
        val artifact = binding.artifact
        return jdbc.update("""INSERT INTO trading_artifact_read_grants
            (token_hash,artifact_id,logical_job_id,session_id,artifact_role,raw_sha256,
             attempt_token,deletion_generation,expires_at,created_at)
            SELECT ?,a.id,d.id,s.id,a.artifact_role,a.content_sha256,r.attempt_token,s.deletion_generation,?,?
            FROM trading_source_artifacts a JOIN trading_import_sessions s
            ON s.id=a.import_session_id
            JOIN trading_books b ON b.id=s.trading_book_id AND b.owner_member_id=s.owner_member_id
            JOIN trading_accounts c ON c.id=b.account_id AND c.owner_member_id=b.owner_member_id
            JOIN trading_normalization_runs r ON r.id=s.active_normalization_run_id
            JOIN trading_normalization_dispatches d ON d.run_id=r.id
            WHERE a.id=? AND a.import_session_id=? AND d.id=?::uuid
            AND b.status='ACTIVE' AND c.status='ACTIVE'
            AND r.trading_book_id=b.id AND r.owner_member_id=s.owner_member_id
            AND s.status='VALIDATING' AND NOT s.deletion_pending AND
            s.deletion_generation=? AND r.deletion_generation=? AND r.attempt_token=?::uuid AND
            a.retention_status='ACTIVE' AND a.raw_expires_at>? AND a.deleted_at IS NULL
            AND a.object_key=? AND a.byte_size=? AND a.content_sha256=? AND a.content_type=? AND a.artifact_role=?""",
            record.tokenHash, Timestamp.from(record.expiresAt), Timestamp.from(record.createdAt),
            artifact.artifactId.value, binding.sessionId.value, binding.logicalJobId,
            binding.deletionGeneration, binding.deletionGeneration, binding.attemptToken,
            Timestamp.from(record.createdAt), record.objectKey, artifact.contentLength,
            artifact.sha256, artifact.mediaType, artifact.role.name) == 1
    }
}
