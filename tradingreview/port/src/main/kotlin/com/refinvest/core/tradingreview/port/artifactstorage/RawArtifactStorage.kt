package com.refinvest.core.tradingreview.port.artifactstorage

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import java.io.InputStream
import java.time.Instant

interface RawArtifactStorage {
    fun putStaging(source: InputStream, maxBytes: Long, artifactId: TradingSourceArtifactId,
                   sessionId: TradingImportSessionId): StagedRawArtifact
    fun preparePublication(staged: StagedRawArtifact, role: TradingArtifactRole): StagedRawArtifact
    fun promote(staged: StagedRawArtifact, finalKey: String)
    fun completePublication(finalKey: String)
    fun open(key: String, wrappedDek: ByteArray, keyVersion: String, artifactId: TradingSourceArtifactId): InputStream
    fun delete(key: String)
    fun exists(key: String): Boolean
    fun cleanupOrphans(olderThan: Instant, limit: Int, isReferenced: (String) -> Boolean): Int
    fun purgeCopies(scope: com.refinvest.core.tradingreview.port.deletion.TradingArtifactScope): Boolean
}
