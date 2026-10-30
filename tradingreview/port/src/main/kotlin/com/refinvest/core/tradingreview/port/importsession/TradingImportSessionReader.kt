package com.refinvest.core.tradingreview.port.importsession

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.TradingPage

interface TradingImportSessionReader {
    fun find(id: TradingImportSessionId, owner: MemberId): TradingImportSession?
    fun list(bookId: TradingBookId, owner: MemberId, page: Int, size: Int): TradingPage<TradingImportSession>
    fun deleted(id: TradingImportSessionId, owner: MemberId): Boolean
}
