package com.refinvest.core.tradingreview.port.inbound.importsession.upload

import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import java.io.InputStream

class UploadTradingImportArtifactCommand(
    val id: TradingImportSessionId,
    val source: InputStream,
    val key: String,
    val metadata: () -> TradingUploadMetadata,
) {
    companion object {
        fun withMetadata(id: TradingImportSessionId, metadata: TradingUploadMetadata, source: InputStream, key: String) =
            UploadTradingImportArtifactCommand(id, source, key) { metadata }
    }
}
