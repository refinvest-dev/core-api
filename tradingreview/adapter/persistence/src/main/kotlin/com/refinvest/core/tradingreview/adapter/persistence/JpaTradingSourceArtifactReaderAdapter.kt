package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingSourceArtifactReader
import org.springframework.stereotype.Repository

@Repository
class JpaTradingSourceArtifactReaderAdapter(private val artifacts: TradingSourceArtifactJpaRows) : TradingSourceArtifactReader {
    override fun find(id: TradingSourceArtifactId, owner: MemberId): TradingSourceArtifact? =
        artifacts.readOwned(id.value, owner.value)?.let { TradingImportSessionPersistenceMapper.toDomain(TradingImportSessionPersistenceMapper.toRecord(it)) }

    override fun findByRole(sessionId: TradingImportSessionId, role: TradingArtifactRole): TradingSourceArtifact? =
        artifacts.readRole(sessionId.value, role.name)?.let { TradingImportSessionPersistenceMapper.toDomain(TradingImportSessionPersistenceMapper.toRecord(it)) }

    override fun list(sessionId: TradingImportSessionId): List<TradingSourceArtifact> = artifacts.readSession(sessionId.value)
        .map { TradingImportSessionPersistenceMapper.toDomain(TradingImportSessionPersistenceMapper.toRecord(it)) }

    override fun referencesObjectKey(key: String): Boolean = artifacts.countByObjectKey(key) > 0
}
