package com.refinvest.core.tradingreview.port.outbound.persistence.importsession

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.time.Instant

interface TradingRawExpiryCandidates {
    fun due(now: Instant, limit: Int): List<TradingRawExpiryCandidate>
}
