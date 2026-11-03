package com.refinvest.core.tradingreview.port.importsession.create

import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode

data class CreateTradingImportSessionCommand(
    val bookId: TradingBookId,
    val sourceTimezone: String,
    val reviewTimezone: String,
    val mode: TradingPositionMode,
    val dataPolicyVersion: String?,
    val clientRequestReference: String?,
    val key: String,
)
