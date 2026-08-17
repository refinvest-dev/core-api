package com.refinvest.core.strategy.domain.valueobject

import com.refinvest.core.common.domain.Identifier

@JvmInline
value class StrategyId(override val value: Long) : Identifier<Long>
