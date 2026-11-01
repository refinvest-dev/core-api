package com.refinvest.core.tradingreview.port

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.port.deletion.TradingDeletion
import java.io.InputStream
import java.time.Instant

data class StagedRawArtifact(val stagingKey: String, val sha256: String, val byteSize: Long,
                             val wrappedDek: ByteArray, val keyVersion: String,
                             val publicationKey: String? = null)

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

interface TradingVersionPins { fun currentJson(): String }

interface TradingImportUseCase {
    fun create(bookId: TradingBookId, sourceTimezone: String, reviewTimezone: String,
               mode: TradingPositionMode, dataPolicyVersion: String?, clientRequestReference: String?, key: String): TradingImportSession
    fun get(id: TradingImportSessionId): TradingImportSessionView
    fun list(bookId: TradingBookId, page: Int, size: Int): TradingPage<TradingImportSessionView>
    fun upload(id: TradingImportSessionId, role: TradingArtifactRole, filename: String,
               contentType: String, declaredSize: Long, declaredSha256: String,
               dialectCandidate: String, source: InputStream, key: String): UploadArtifactResult
    fun uploadDeferred(id: TradingImportSessionId, source: InputStream, key: String,
                       metadata: () -> TradingUploadMetadata): UploadArtifactResult
    fun delete(id: TradingImportSessionId, key: String): TradingDeletion
    fun deleteArtifact(id: TradingSourceArtifactId, key: String): TradingDeletion
}

data class TradingImportSessionView(val session: TradingImportSession, val artifacts: List<TradingSourceArtifact>,
                                    val published: PublishedLedgerSummary? = null)
data class UploadArtifactResult(val artifact: TradingSourceArtifact, val created: Boolean)
data class TradingUploadMetadata(val role: TradingArtifactRole, val filename: String, val contentType: String,
                                 val byteSize: Long, val sha256: String, val dialectCandidate: String)
