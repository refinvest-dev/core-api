package com.refinvest.core.tradingreview.port.book

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingBook
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.TradingPage

interface TradingBookReader {
    fun find(id: TradingBookId, owner: MemberId): TradingBook?
    fun list(accountId: TradingAccountId, owner: MemberId, page: Int, size: Int): TradingPage<TradingBook>
    fun deleted(id: TradingBookId, owner: MemberId): Boolean
}
