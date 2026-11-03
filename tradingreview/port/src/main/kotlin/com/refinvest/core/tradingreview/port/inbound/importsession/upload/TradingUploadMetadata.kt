package com.refinvest.core.tradingreview.port.inbound.importsession.upload

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole

data class TradingUploadMetadata(val role: TradingArtifactRole, val filename: String, val contentType: String,
                                 val byteSize: Long, val sha256: String, val dialectCandidate: String)
