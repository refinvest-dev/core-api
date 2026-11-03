package com.refinvest.core.tradingreview.port.outbound.persistence.importsession

import com.refinvest.core.tradingreview.port.outbound.persistence.TradingPageReadModel

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.valueobject.*

interface TradingImportSessionReader {
    fun find(id: TradingImportSessionId, owner: MemberId): TradingImportSession?
    fun list(bookId: TradingBookId, owner: MemberId, page: Int, size: Int): TradingPageReadModel<TradingImportSession>
    fun deleted(id: TradingImportSessionId, owner: MemberId): Boolean
}
