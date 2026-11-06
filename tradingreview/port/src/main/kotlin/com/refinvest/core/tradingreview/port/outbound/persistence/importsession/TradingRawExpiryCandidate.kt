package com.refinvest.core.tradingreview.port.outbound.persistence.importsession

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant

data class TradingRawExpiryCandidate(val sessionId: TradingImportSessionId, val owner: MemberId,
    val bookId: TradingBookId, val accountId: TradingAccountId)
