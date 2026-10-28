package com.refinvest.core.strategy.port.outbound.persistence

import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.domain.valueobject.StrategyVersionId
import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant

/** Versions ascend by (createdAt, numeric ID); latestVersionId is the last ID, or null when empty. */
data class StrategyReadModel(
    val id: StrategyId,
    val memberId: MemberId,
    val name: String,
    val createdAt: Instant,
    val latestVersionId: StrategyVersionId?,
    val versions: List<StrategyVersionReadModel>,
)
