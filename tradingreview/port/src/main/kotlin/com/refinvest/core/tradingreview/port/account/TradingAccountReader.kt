package com.refinvest.core.tradingreview.port.account

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingAccount
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.TradingPage

interface TradingAccountReader {
    fun find(id: TradingAccountId, owner: MemberId): TradingAccount?
    fun list(owner: MemberId, page: Int, size: Int): TradingPage<TradingAccount>
    fun deleted(id: TradingAccountId, owner: MemberId): Boolean
}
