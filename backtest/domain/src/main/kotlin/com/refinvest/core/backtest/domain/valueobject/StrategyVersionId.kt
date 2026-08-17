package com.refinvest.core.backtest.domain.valueobject

import com.refinvest.core.common.domain.Identifier

/** A reference to the Strategy bounded context; this is not Strategy's domain type. */
@JvmInline
value class StrategyVersionId(override val value: Long) : Identifier<Long>
