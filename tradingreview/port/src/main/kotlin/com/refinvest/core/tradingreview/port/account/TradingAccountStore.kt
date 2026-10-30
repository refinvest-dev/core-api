package com.refinvest.core.tradingreview.port.account

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingAccount
import com.refinvest.core.tradingreview.domain.valueobject.*

interface TradingAccountStore {
    fun insert(aggregate: TradingAccount)
    fun find(id: TradingAccountId, owner: MemberId, forUpdate: Boolean = false): TradingAccount?
    /** CAS on the aggregate version and immutable identity; a successful save advances the stored version by one. */
    fun update(aggregate: TradingAccount): Boolean
    fun deleted(id: TradingAccountId, owner: MemberId): Boolean
}
