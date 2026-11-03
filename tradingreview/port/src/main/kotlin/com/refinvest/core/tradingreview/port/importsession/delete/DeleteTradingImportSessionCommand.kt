package com.refinvest.core.tradingreview.port.importsession.delete

import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId

data class DeleteTradingImportSessionCommand(val id: TradingImportSessionId, val key: String)
