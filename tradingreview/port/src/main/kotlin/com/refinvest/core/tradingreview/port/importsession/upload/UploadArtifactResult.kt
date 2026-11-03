package com.refinvest.core.tradingreview.port.importsession.upload

import com.refinvest.core.tradingreview.port.importsession.TradingSourceArtifactResult

data class UploadArtifactResult(val artifact: TradingSourceArtifactResult, val created: Boolean)
