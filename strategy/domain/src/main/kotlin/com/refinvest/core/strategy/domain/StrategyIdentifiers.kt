package com.refinvest.core.strategy.domain

import com.refinvest.core.common.domain.Identifier
@JvmInline
value class StrategyId(override val value: Long) : Identifier<Long>

@JvmInline
value class StrategyVersionId(override val value: Long) : Identifier<Long>

@JvmInline
value class MemberId(override val value: Long) : Identifier<Long>
