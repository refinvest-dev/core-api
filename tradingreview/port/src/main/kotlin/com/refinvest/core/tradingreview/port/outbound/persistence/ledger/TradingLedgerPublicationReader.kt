package com.refinvest.core.tradingreview.port.outbound.persistence.ledger

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId

interface TradingLedgerPublicationReader {
    fun publishedForSession(sessionId: TradingImportSessionId, owner: MemberId): PublishedLedgerSummary?
}
