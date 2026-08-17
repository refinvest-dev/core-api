package com.refinvest.core.strategy.adapter.web.strategy.define

import jakarta.validation.constraints.Min

data class TimeBasedExitRequest(
    @field:Min(1)
    val holdingSignalSessions: Int,
)
