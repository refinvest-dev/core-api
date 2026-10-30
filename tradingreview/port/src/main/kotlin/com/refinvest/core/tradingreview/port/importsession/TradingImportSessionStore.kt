package com.refinvest.core.tradingreview.port.importsession

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.valueobject.*

interface TradingImportSessionStore {
    fun insert(session: TradingImportSession)
    fun find(id: TradingImportSessionId, owner: MemberId, forUpdate: Boolean = false): TradingImportSession?
    /** Saves the bounded artifact slots with the root. False leaves all rows unchanged. */
    fun update(session: TradingImportSession): Boolean
    fun deleted(id: TradingImportSessionId, owner: MemberId): Boolean
}
