package com.refinvest.core.tradingreview.port.outbound.persistence.account

import com.refinvest.core.tradingreview.port.outbound.persistence.TradingPageReadModel

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingAccount
import com.refinvest.core.tradingreview.domain.valueobject.*

interface TradingAccountReader {
    fun find(id: TradingAccountId, owner: MemberId): TradingAccount?
    fun list(owner: MemberId, page: Int, size: Int): TradingPageReadModel<TradingAccount>
    fun deleted(id: TradingAccountId, owner: MemberId): Boolean
}
