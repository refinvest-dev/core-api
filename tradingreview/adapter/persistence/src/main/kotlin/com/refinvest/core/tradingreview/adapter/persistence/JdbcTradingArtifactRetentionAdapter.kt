package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.RawArtifactPurgeOutcome
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.RawArtifactRetentionClaim
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.RawArtifactRetentionCompletion
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingArtifactRetention

import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcTradingArtifactRetentionAdapter(private val jdbc: JdbcTemplate) : TradingArtifactRetention {
    override fun countOverdue(now: Instant): Long = jdbc.queryForObject("""SELECT count(*) FROM trading_source_artifacts
        WHERE retention_status <> 'DELETED' AND raw_expires_at <= ?""", Long::class.java, Timestamp.from(now)) ?: 0L

    override fun finish(completion: RawArtifactRetentionCompletion): Boolean {
        val claim = completion.claim
        val absent = completion.outcome == RawArtifactPurgeOutcome.ABSENT
        val failed = completion.outcome in setOf(RawArtifactPurgeOutcome.RETRY, RawArtifactPurgeOutcome.EXHAUSTED)
        val status = when (completion.outcome) {
            RawArtifactPurgeOutcome.ABSENT -> "DELETED"
            RawArtifactPurgeOutcome.EXHAUSTED -> "DELETION_FAILED"
            else -> "DELETION_PENDING"
        }
        // The materialized lock is acquired before clock_timestamp() is evaluated, including after a lock wait.
        return jdbc.update("""WITH locked AS MATERIALIZED (
            SELECT id,version,retention_status,deletion_claim_until FROM trading_source_artifacts WHERE id=? FOR UPDATE)
            UPDATE trading_source_artifacts a SET retention_status=?,
            object_key=CASE WHEN ? THEN NULL ELSE a.object_key END,
            wrapped_dek=CASE WHEN ? THEN NULL ELSE a.wrapped_dek END,
            key_version=CASE WHEN ? THEN NULL ELSE a.key_version END,
            filename=CASE WHEN ? THEN NULL ELSE a.filename END,
            deleted_at=CASE WHEN ? THEN ? ELSE a.deleted_at END,
            deletion_failure=CASE WHEN ? THEN NULL WHEN ? THEN 'ARTIFACT_DELETION_FAILED' ELSE a.deletion_failure END,
            deletion_attempt_count=a.deletion_attempt_count+?, deletion_claim_until=NULL,
            deletion_next_attempt_at=?, version=a.version+1
            FROM locked l WHERE a.id=l.id AND a.version=l.version AND l.version=?
            AND l.retention_status='DELETION_PENDING' AND l.deletion_claim_until=?
            AND l.deletion_claim_until > GREATEST(?::timestamptz,clock_timestamp())""",
            claim.artifact.id.value, status, absent, absent, absent, absent, absent, Timestamp.from(completion.observedAt),
            absent, failed, if (failed) 1 else 0, completion.nextAttemptAt?.let(Timestamp::from),
            claim.artifact.version, Timestamp.from(claim.leaseUntil), Timestamp.from(completion.observedAt)) == 1
    }

    override fun claimDue(now: Instant, limit: Int, lease: Duration): List<RawArtifactRetentionClaim> {
        require(limit in 1..100 && !lease.isNegative && !lease.isZero && lease <= Duration.ofMinutes(5))
        return jdbc.query("""WITH due AS (
        SELECT id FROM trading_source_artifacts WHERE
        ((retention_status IN ('ACTIVE','RETENTION_SCHEDULED') AND raw_expires_at <= ?)
        OR retention_status='DELETION_PENDING')
        AND (deletion_claim_until IS NULL OR deletion_claim_until <= clock_timestamp())
        AND (deletion_next_attempt_at IS NULL OR deletion_next_attempt_at <= ?)
        ORDER BY raw_expires_at, id LIMIT ? FOR UPDATE SKIP LOCKED)
        UPDATE trading_source_artifacts a SET retention_status='DELETION_PENDING',
        deletion_claim_until=clock_timestamp() + (? * INTERVAL '1 millisecond'), deletion_next_attempt_at=NULL,
        version=version+1 FROM due WHERE a.id=due.id RETURNING a.*""", { rs, _ ->
            RawArtifactRetentionClaim(TradingImportSessionPersistenceMapper.toDomain(TradingImportRows.artifact(rs)),
                rs.getTimestamp("deletion_claim_until").toInstant(), rs.getInt("deletion_attempt_count"))
        }, Timestamp.from(now), Timestamp.from(now), limit, lease.toMillis())
    }
}
