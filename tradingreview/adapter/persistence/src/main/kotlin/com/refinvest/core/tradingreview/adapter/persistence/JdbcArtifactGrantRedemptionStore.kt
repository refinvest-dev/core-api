package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant.ArtifactGrantConsumption
import com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant.ArtifactGrantReadSource
import com.refinvest.core.tradingreview.port.outbound.persistence.artifactgrant.ArtifactGrantRedemptionStore
import java.sql.Timestamp
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcArtifactGrantRedemptionStore(private val jdbc: JdbcTemplate) : ArtifactGrantRedemptionStore {
    override fun consume(request: ArtifactGrantConsumption): ArtifactGrantReadSource? {
        val now = Timestamp.from(request.observedAt)
        return jdbc.query("""UPDATE trading_artifact_read_grants g SET consumed_at=?
            FROM trading_source_artifacts a, trading_import_sessions s,
                 trading_books b, trading_accounts c,
                 trading_normalization_dispatches d, trading_normalization_runs r
            WHERE g.token_hash=? AND g.consumed_at IS NULL AND g.expires_at>? AND
            a.id=g.artifact_id AND s.id=g.session_id AND a.import_session_id=s.id AND
            b.id=s.trading_book_id AND b.owner_member_id=s.owner_member_id AND
            c.id=b.account_id AND c.owner_member_id=b.owner_member_id AND
            b.status='ACTIVE' AND c.status='ACTIVE' AND
            d.id=g.logical_job_id AND r.id=d.run_id AND r.import_session_id=s.id AND
            r.trading_book_id=b.id AND r.owner_member_id=s.owner_member_id AND
            r.attempt_token=g.attempt_token AND r.deletion_generation=g.deletion_generation AND
            s.active_normalization_run_id=r.id AND s.deletion_generation=g.deletion_generation AND
            s.status='VALIDATING' AND NOT s.deletion_pending AND
            a.retention_status='ACTIVE' AND a.raw_expires_at>? AND
            a.content_sha256=g.raw_sha256 AND a.artifact_role=g.artifact_role
            RETURNING a.id,a.object_key,a.wrapped_dek,a.key_version,a.byte_size,a.content_type""",
            { rs, _ -> ArtifactGrantReadSource(TradingSourceArtifactId(rs.getLong("id")), rs.getString("object_key"),
                rs.getBytes("wrapped_dek"), rs.getString("key_version"), rs.getLong("byte_size"),
                rs.getString("content_type")) }, now, request.tokenHash, now, now).firstOrNull()
    }
}
