package com.refinvest.core.tradingreview.port.identity

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId

interface TradingIdGenerator {
    fun accountId(): TradingAccountId
    fun bookId(): TradingBookId
    fun importSessionId(): TradingImportSessionId = error("Import ID generator is not configured")
    fun sourceArtifactId(): TradingSourceArtifactId = error("Artifact ID generator is not configured")
    fun deletionId(): DeletionRequestId
}
