package com.refinvest.core.tradingreview.port.book

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingBook
import com.refinvest.core.tradingreview.domain.valueobject.*

interface TradingBookStore {
    fun insert(aggregate: TradingBook)
    fun find(id: TradingBookId, owner: MemberId, forUpdate: Boolean = false): TradingBook?
    /** CAS on the aggregate version and immutable identity; a successful save advances the stored version by one. */
    fun update(aggregate: TradingBook): Boolean
    fun deleted(id: TradingBookId, owner: MemberId): Boolean
}
