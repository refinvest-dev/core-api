package com.refinvest.core.tradingreview.port.inbound.importsession.upload

import com.refinvest.core.tradingreview.port.inbound.importsession.TradingSourceArtifactResult

data class UploadArtifactResult(val artifact: TradingSourceArtifactResult, val created: Boolean)
