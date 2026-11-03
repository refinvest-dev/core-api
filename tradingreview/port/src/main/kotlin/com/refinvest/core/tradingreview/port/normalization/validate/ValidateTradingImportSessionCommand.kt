package com.refinvest.core.tradingreview.port.normalization.validate

import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId

data class ValidateTradingImportSessionCommand(
    val id: TradingImportSessionId,
    val targetVersionSetJson: String?,
    val key: String,
)
