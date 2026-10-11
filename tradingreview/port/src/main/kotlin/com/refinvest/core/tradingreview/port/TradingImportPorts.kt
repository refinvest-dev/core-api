package com.refinvest.core.tradingreview.port

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import java.io.InputStream
import java.time.Instant

interface TradingImportSessionStore {
    fun insert(session: TradingImportSession)
    fun find(id: TradingImportSessionId, owner: MemberId, forUpdate: Boolean = false): TradingImportSession?
    fun list(bookId: TradingBookId, owner: MemberId, page: Int, size: Int): TradingPage<TradingImportSession>
    fun update(session: TradingImportSession): Boolean
    fun deleted(id: TradingImportSessionId, owner: MemberId): Boolean
}

interface TradingSourceArtifactStore {
    fun insert(artifact: TradingSourceArtifact)
    fun find(id: TradingSourceArtifactId, owner: MemberId, forUpdate: Boolean = false): TradingSourceArtifact?
    fun findByRole(sessionId: TradingImportSessionId, role: TradingArtifactRole): TradingSourceArtifact?
    fun list(sessionId: TradingImportSessionId): List<TradingSourceArtifact>
    fun markDeletionPending(sessionId: TradingImportSessionId, now: Instant)
    fun markDeletionPending(id: TradingSourceArtifactId, now: Instant): Boolean
    fun markDeleted(id: TradingSourceArtifactId, now: Instant): Boolean
    fun markDeletionFailed(id: TradingSourceArtifactId, code: String, now: Instant)
    fun claimDue(now: Instant, limit: Int): List<TradingSourceArtifact>
    fun referencesObjectKey(key: String): Boolean
}

data class StagedRawArtifact(val stagingKey: String, val sha256: String, val byteSize: Long,
                             val wrappedDek: ByteArray, val keyVersion: String)

interface RawArtifactStorage {
    fun putStaging(source: InputStream, maxBytes: Long, artifactId: TradingSourceArtifactId): StagedRawArtifact
    fun promote(staged: StagedRawArtifact, finalKey: String)
    fun open(key: String, wrappedDek: ByteArray, keyVersion: String, artifactId: TradingSourceArtifactId): InputStream
    fun delete(key: String)
    fun exists(key: String): Boolean
    fun cleanupOrphans(olderThan: Instant, limit: Int, isReferenced: (String) -> Boolean): Int
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

data class TradingImportSessionView(val session: TradingImportSession, val artifacts: List<TradingSourceArtifact>)
data class UploadArtifactResult(val artifact: TradingSourceArtifact, val created: Boolean)
data class TradingUploadMetadata(val role: TradingArtifactRole, val filename: String, val contentType: String,
                                 val byteSize: Long, val sha256: String, val dialectCandidate: String)
