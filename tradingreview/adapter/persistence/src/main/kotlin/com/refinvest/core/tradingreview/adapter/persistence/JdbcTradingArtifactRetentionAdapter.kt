package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.importsession.TradingArtifactRetention
import java.sql.Timestamp
import java.time.Instant
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcTradingArtifactRetentionAdapter(private val jdbc: JdbcTemplate) : TradingArtifactRetention {
    override fun markDeleted(artifact: TradingSourceArtifact, now: Instant): Boolean = jdbc.update("""UPDATE trading_source_artifacts
        SET retention_status='DELETED', object_key=NULL, wrapped_dek=NULL, key_version=NULL, filename=NULL,
        deleted_at=?, deletion_failure=NULL, deletion_claim_until=NULL, version=version+1 WHERE id=? AND version=? AND retention_status='DELETION_PENDING'""",
        Timestamp.from(now), artifact.id.value, artifact.version) == 1

    override fun markDeletionFailed(artifact: TradingSourceArtifact, code: String, now: Instant) {
        jdbc.update("""UPDATE trading_source_artifacts SET retention_status=CASE WHEN deletion_attempt_count >= 4
            THEN 'DELETION_FAILED' ELSE 'DELETION_PENDING' END, deletion_attempt_count=deletion_attempt_count+1,
            deletion_failure=?, deletion_claim_until=NULL, version=version+1 WHERE id=? AND version=? AND retention_status='DELETION_PENDING'""", code, artifact.id.value, artifact.version)
    }

    override fun claimDue(now: Instant, limit: Int): List<TradingSourceArtifact> = jdbc.query("""WITH due AS (
        SELECT id FROM trading_source_artifacts WHERE
        ((retention_status IN ('ACTIVE','RETENTION_SCHEDULED') AND raw_expires_at <= CURRENT_TIMESTAMP)
        OR retention_status='DELETION_PENDING')
        AND (deletion_claim_until IS NULL OR deletion_claim_until <= CURRENT_TIMESTAMP)
        ORDER BY raw_expires_at, id LIMIT ? FOR UPDATE SKIP LOCKED)
        UPDATE trading_source_artifacts a SET retention_status='DELETION_PENDING',
        deletion_claim_until=CURRENT_TIMESTAMP + INTERVAL '5 minutes', version=version+1
        FROM due WHERE a.id=due.id RETURNING a.*""", { rs, _ -> TradingImportSessionPersistenceMapper.toDomain(TradingImportRows.artifact(rs)) }, limit)

}
