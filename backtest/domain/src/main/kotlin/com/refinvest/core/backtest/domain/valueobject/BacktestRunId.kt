package com.refinvest.core.backtest.domain.valueobject

import com.refinvest.core.common.domain.Identifier

@JvmInline
value class BacktestRunId(override val value: Long) : Identifier<Long>
