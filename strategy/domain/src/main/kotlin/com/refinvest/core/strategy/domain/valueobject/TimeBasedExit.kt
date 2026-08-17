package com.refinvest.core.strategy.domain.valueobject

@JvmInline
value class TimeBasedExit(val holdingSignalSessions: Int) {
    init {
        require(holdingSignalSessions > 0) { "holdingSignalSessions must be positive" }
    }
}
