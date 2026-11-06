package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionScopeInventory

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronizationManager

@Repository
class JdbcDeletionScopeInventory(jdbc: JdbcTemplate) : DeletionScopeInventory {
    private val named = NamedParameterJdbcTemplate(jdbc)

    override fun capture(scope: DeletionScope, owner: MemberId): DeletionAffectedResources? {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "Count snapshot requires the command transaction" }
        val type = scope.targetType
        val raw = type == DeletionTargetType.TRADING_SOURCE_ARTIFACT
        val bookCondition = when (type) {
            DeletionTargetType.TRADING_ACCOUNT -> "b.account_id=:target"
            DeletionTargetType.TRADING_BOOK -> "b.id=:target"
            DeletionTargetType.TRADING_IMPORT_SESSION -> "EXISTS (SELECT 1 FROM trading_import_sessions s WHERE s.id=:target AND s.trading_book_id=b.id AND s.owner_member_id=:owner)"
            DeletionTargetType.TRADING_SOURCE_ARTIFACT -> "EXISTS (SELECT 1 FROM trading_source_artifacts r JOIN trading_import_sessions s ON s.id=r.import_session_id WHERE r.id=:target AND s.trading_book_id=b.id AND s.owner_member_id=:owner)"
        }
        val sessionCondition = when (type) {
            DeletionTargetType.TRADING_IMPORT_SESSION -> "s.id=:target"
            DeletionTargetType.TRADING_SOURCE_ARTIFACT -> "EXISTS (SELECT 1 FROM trading_source_artifacts r WHERE r.id=:target AND r.import_session_id=s.id)"
            else -> "TRUE"
        }
        val accountCount = if (type == DeletionTargetType.TRADING_ACCOUNT)
            "(SELECT count(*) FROM trading_accounts WHERE id=:target AND owner_member_id=:owner)" else "0"
        val bookCount = if (type in setOf(DeletionTargetType.TRADING_ACCOUNT, DeletionTargetType.TRADING_BOOK))
            "(SELECT count(*) FROM scoped_books)" else "0"
        val sessionCount = if (raw) "0" else "(SELECT count(*) FROM scoped_sessions)"
        val artifactCondition = if (raw) "AND r.id=:target" else ""
        val revisionCount = if (raw) "0" else "(SELECT count(*) FROM trading_ledger_revisions v JOIN scoped_sessions s ON s.id=v.import_session_id AND s.trading_book_id=v.trading_book_id)"
        return named.query("""WITH scoped_books AS (
            SELECT b.id FROM trading_books b JOIN trading_accounts a ON a.id=b.account_id
            WHERE b.owner_member_id=:owner AND a.owner_member_id=:owner AND $bookCondition
        ), scoped_sessions AS (
            SELECT s.id,s.trading_book_id FROM trading_import_sessions s JOIN scoped_books b ON b.id=s.trading_book_id
            WHERE s.owner_member_id=:owner AND $sessionCondition
        ) SELECT $accountCount AS accounts,$bookCount AS books,$sessionCount AS sessions,
            (SELECT count(*) FROM trading_source_artifacts r JOIN scoped_sessions s ON s.id=r.import_session_id $artifactCondition) AS artifacts,
            $revisionCount AS revisions""", mapOf("owner" to owner.value, "target" to scope.target.value)) { r, _ ->
            // Analysis/Reprocessing aggregates have no persisted runtime in this implementation.
            // NormalizationRun belongs to Session and must not be counted as AnalysisRun.
            val counts = DeletionAffectedResources(r.getLong("accounts"), r.getLong("books"), r.getLong("sessions"),
                r.getLong("artifacts"), r.getLong("revisions"))
            if (when (type) {
                DeletionTargetType.TRADING_ACCOUNT -> counts.accounts
                DeletionTargetType.TRADING_BOOK -> counts.books
                DeletionTargetType.TRADING_IMPORT_SESSION -> counts.importSessions
                DeletionTargetType.TRADING_SOURCE_ARTIFACT -> counts.artifacts
            } == 1L) counts else null
        }.single()
    }
}
