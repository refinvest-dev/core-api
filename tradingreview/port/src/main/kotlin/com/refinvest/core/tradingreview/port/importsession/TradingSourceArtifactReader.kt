package com.refinvest.core.tradingreview.port.importsession

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import com.refinvest.core.tradingreview.domain.valueobject.*

interface TradingSourceArtifactReader {
    fun find(id: TradingSourceArtifactId, owner: MemberId): TradingSourceArtifact?
    fun findByRole(sessionId: TradingImportSessionId, role: TradingArtifactRole): TradingSourceArtifact?
    fun list(sessionId: TradingImportSessionId): List<TradingSourceArtifact>
    fun referencesObjectKey(key: String): Boolean
}
