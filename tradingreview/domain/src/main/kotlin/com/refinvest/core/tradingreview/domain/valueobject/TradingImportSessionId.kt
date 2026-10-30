package com.refinvest.core.tradingreview.domain.valueobject

import com.refinvest.core.common.domain.Identifier

@JvmInline value class TradingImportSessionId(override val value: Long) : Identifier<Long>
