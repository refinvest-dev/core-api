package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.port.outbound.persistence.TradingPageReadModel
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** Restores only the two artifact slots and the current run; ledger/result rows are never loaded here. */
@Component
class TradingImportSessionSnapshots(
    private val sessions: TradingImportSessionJpaStore,
    private val artifacts: TradingSourceArtifactJpaRows,
    private val jdbc: JdbcTemplate,
) {
    fun find(id: TradingImportSessionId, owner: MemberId): TradingImportSession? =
        sessions.readSnapshot(id.value, owner.value)?.let(::complete)

    fun page(bookId: TradingBookId, owner: MemberId, page: Int, size: Int): TradingPageReadModel<TradingImportSession> {
        val result = sessions.page(bookId.value, owner.value, PageRequest.of(page, size))
        return TradingPageReadModel(result.content.map(::complete), page, size, result.totalElements)
    }

    private fun complete(entity: TradingImportSessionJpaEntity): TradingImportSession {
        val record = TradingImportSessionPersistenceMapper.toRecord(entity)
        val currentRun = record.activeNormalizationRunId?.let { id ->
            jdbc.query("SELECT * FROM trading_normalization_runs WHERE id=?::uuid AND import_session_id=? AND owner_member_id=?",
                { rows, _ -> NormalizationRunRows.record(rows) }, id, record.id, record.owner).firstOrNull()
        }
        return TradingImportSessionPersistenceMapper.toDomain(record.copy(
            artifacts = artifacts.readSession(record.id).map(TradingImportSessionPersistenceMapper::toRecord),
            normalizationRun = currentRun,
        ))
    }
}
