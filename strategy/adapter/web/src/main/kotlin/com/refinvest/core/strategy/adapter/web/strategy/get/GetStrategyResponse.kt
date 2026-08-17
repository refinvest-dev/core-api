package com.refinvest.core.strategy.adapter.web.strategy.get

import java.time.Instant

data class GetStrategyResponse(
    val id: String,
    val name: String,
    val createdAt: Instant,
    val latestVersionId: String?,
    val versions: List<GetStrategyVersionResponse>,
)
