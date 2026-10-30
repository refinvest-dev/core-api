package com.refinvest.core.tradingreview.adapter.persistence

import java.sql.ResultSet

internal object LedgerRevisionRows {
    const val SELECT = """SELECT r.id::text,r.run_id::text,r.trading_book_id,r.import_session_id,
        s.owner_member_id,r.revision_number,r.input_set_hash,r.input_hash_specification_version,
        r.input_identity_json::text,r.target_version_set_json::text,r.result_schema_version,
        r.result_hash_specification_version,r.result_hash,r.ledger_content_hash,r.reconciliation_manifest_hash,
        (r.reconciliation_manifest_json - 'reviewUnits' - 'reconciliationMatches' - 'reconciliationIssues')::text AS manifest_base,
        r.coverage_json::text,r.capability_json::text,(r.quality_report_json - 'validationIssues')::text AS quality_base,
        r.counts_json::text,r.section_digests_json::text,r.published_at
        FROM trading_ledger_revisions r JOIN trading_import_sessions s ON s.id=r.import_session_id
        JOIN trading_books b ON b.id=r.trading_book_id JOIN trading_accounts a ON a.id=b.account_id"""
    fun read(r: ResultSet) = LedgerRevisionRecord(r.getString(1),r.getString(2),r.getLong(3),r.getLong(4),
        r.getLong(5),r.getLong(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getString(11),
        r.getString(12),r.getString(13),r.getString(14),r.getString(15),r.getString(16),r.getString(17),
        r.getString(18),r.getString(19),r.getString(20),r.getString(21),r.getTimestamp(22).toInstant())
}
