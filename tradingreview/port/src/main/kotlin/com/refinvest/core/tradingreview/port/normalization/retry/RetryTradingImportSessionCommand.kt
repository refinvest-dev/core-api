package com.refinvest.core.tradingreview.port.normalization.retry

import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId

data class RetryTradingImportSessionCommand(val id: TradingImportSessionId, val key: String)
