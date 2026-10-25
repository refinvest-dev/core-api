package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.port.AnalyticsRevisionSource
import com.refinvest.core.tradingreview.port.TradingAnalyticsRevisionReader
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementCreator
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Repository

@Repository
class JdbcTradingAnalyticsRevisionReader(private val jdbc: JdbcTemplate) : TradingAnalyticsRevisionReader {
    override fun find(revisionId: String, ownerId: Long): AnalyticsRevisionSource? = jdbc.query("""
        SELECT r.id::text,r.result_schema_version,r.ledger_content_hash,
            r.reconciliation_manifest_hash,
            r.target_version_set_json->'normalizer'->>'canonicalSchemaVersion',
            r.target_version_set_json->'reconstruction'->>'reviewUnitSchemaVersion',
            n.instrument_terms_json::text,r.reconciliation_manifest_json::text,
            r.capability_json::text,(r.counts_json->>'canonicalRecordCount')::bigint,
            (r.counts_json->>'reviewUnitCount')::bigint
        FROM trading_ledger_revisions r JOIN trading_normalization_runs n ON n.id=r.run_id
        JOIN trading_import_sessions s ON s.id=r.import_session_id
        JOIN trading_books b ON b.id=r.trading_book_id
        JOIN trading_accounts a ON a.id=b.account_id
        WHERE r.id=?::uuid AND b.owner_member_id=? AND b.status='ACTIVE'
            AND a.status='ACTIVE' AND s.status='ACCEPTED' AND NOT s.deletion_pending
        FOR SHARE OF r,s,b,a""", { rs, _ -> AnalyticsRevisionSource(
            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
            rs.getString(9), rs.getLong(10), rs.getLong(11)) }, revisionId, ownerId).firstOrNull()

    override fun visitRecords(revisionId: String, accept: (String) -> Unit) = visit("""
        SELECT r.record_json::text FROM trading_ledger_revision_records m
        JOIN trading_records r ON r.trading_book_id=m.trading_book_id AND r.record_id=m.record_id
        WHERE m.revision_id=?::uuid ORDER BY m.semantic_order_key COLLATE "C" """, revisionId, accept)

    override fun visitEvidenceReferences(revisionId: String, accept: (String) -> Unit) = visit("""
        SELECT jsonb_build_object('snapshotId',e.snapshot_id,'recordId',m.record_id,
            'coreArtifactId',e.source_artifact_id::text,
            'sourceRowNumber',e.source_row_number,
            'sourceRowHash',e.evidence_json->>'sourceRowHash')::text
        FROM trading_ledger_revision_records m
        JOIN trading_records r ON r.trading_book_id=m.trading_book_id AND r.record_id=m.record_id
        JOIN trading_source_evidence_snapshots e ON e.trading_book_id=r.trading_book_id
            AND e.snapshot_id=r.source_evidence_snapshot_id
        WHERE m.revision_id=?::uuid
        ORDER BY m.semantic_order_key COLLATE "C",e.snapshot_id COLLATE "C" """,
        revisionId, accept)

    override fun visitEngineInputs(revisionId: String, accept: (String) -> Unit) = visit("""
        SELECT engine_input_json::text FROM trading_ledger_analytics_supplement
        WHERE revision_id=?::uuid ORDER BY review_unit_id COLLATE "C" """, revisionId, accept)

    private fun visit(sql: String, revisionId: String, accept: (String) -> Unit) {
        jdbc.query(PreparedStatementCreator { connection ->
            connection.prepareStatement(sql).apply {
                setString(1, revisionId)
                fetchSize = 128
            }
        }, RowCallbackHandler { row -> accept(row.getString(1)) })
    }
}
