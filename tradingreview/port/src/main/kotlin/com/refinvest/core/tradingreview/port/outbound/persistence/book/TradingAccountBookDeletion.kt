package com.refinvest.core.tradingreview.port.outbound.persistence.book

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import java.time.Instant

data class TradingAccountBookDeletion(
    val accountId: TradingAccountId,
    val owner: MemberId,
    val accountDeletionGeneration: Long,
    val expectedAccountVersion: Long,
    val requestedAt: Instant,
) {
    init {
        require(accountDeletionGeneration > 0)
        require(expectedAccountVersion >= 0)
    }
}
