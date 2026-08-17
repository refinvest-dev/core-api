package com.refinvest.core.backtest.domain.valueobject

import java.math.BigDecimal

@JvmInline
value class Percent(val value: BigDecimal) {
    init {
        require(value >= BigDecimal.ZERO) { "percent must not be negative" }
    }
}
