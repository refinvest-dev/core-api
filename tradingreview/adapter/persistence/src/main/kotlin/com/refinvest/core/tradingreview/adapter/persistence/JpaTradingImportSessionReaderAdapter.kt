package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.port.outbound.persistence.TradingPageReadModel
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingImportSessionReader
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class JpaTradingImportSessionReaderAdapter(
    private val snapshots: TradingImportSessionSnapshots,
    private val sessions: TradingImportSessionJpaStore,
) : TradingImportSessionReader {
    @Transactional(readOnly = true)
    override fun find(id: TradingImportSessionId, owner: MemberId): TradingImportSession? = snapshots.find(id, owner)

    @Transactional(readOnly = true)
    override fun list(bookId: TradingBookId, owner: MemberId, page: Int, size: Int): TradingPageReadModel<TradingImportSession> =
        snapshots.page(bookId, owner, page, size)

    override fun deleted(id: TradingImportSessionId, owner: MemberId): Boolean = sessions.deleted(id.value, owner.value)
}
