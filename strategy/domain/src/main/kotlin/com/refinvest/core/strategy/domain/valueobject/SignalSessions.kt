package com.refinvest.core.strategy.domain.valueobject

@JvmInline
value class SignalSessions(val value: Int) {
    init {
        require(value >= 0) { "SignalSessions must be zero or greater" }
    }
}
