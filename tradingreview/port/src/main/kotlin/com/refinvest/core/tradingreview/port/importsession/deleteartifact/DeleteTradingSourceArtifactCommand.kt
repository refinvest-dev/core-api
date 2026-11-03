package com.refinvest.core.tradingreview.port.importsession.deleteartifact

import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId

data class DeleteTradingSourceArtifactCommand(val id: TradingSourceArtifactId, val key: String)
