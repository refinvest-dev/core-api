package com.refinvest.core.strategy.adapter.web.strategy.create

import java.time.Instant

data class CreateStrategyResponse(
    val id: String,
    val name: String,
    val createdAt: Instant,
    val latestVersionId: String?,
)
