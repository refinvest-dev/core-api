package com.refinvest.core.backtest.domain.valueobject

@JvmInline
value class EngineVersion(val value: String) {
    init {
        require(value.isNotBlank()) { "engineVersion must not be blank" }
    }
}
