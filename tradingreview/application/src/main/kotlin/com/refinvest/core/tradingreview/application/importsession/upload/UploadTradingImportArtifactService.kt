package com.refinvest.core.tradingreview.application.importsession.upload

import com.refinvest.core.tradingreview.application.support.missingResource
import com.refinvest.core.tradingreview.application.support.requestFingerprint
import com.refinvest.core.tradingreview.application.support.validateIdempotencyKey
import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRetentionStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.importsession.TradingSourceArtifactResult
import com.refinvest.core.tradingreview.port.inbound.importsession.upload.TradingUploadMetadata
import com.refinvest.core.tradingreview.port.inbound.importsession.upload.UploadArtifactResult
import com.refinvest.core.tradingreview.port.inbound.importsession.upload.UploadTradingImportArtifactCommand
import com.refinvest.core.tradingreview.port.inbound.importsession.upload.UploadTradingImportArtifactUseCase
import com.refinvest.core.tradingreview.port.outbound.idempotency.IdempotencyClaim
import com.refinvest.core.tradingreview.port.outbound.idempotency.TradingIdempotencyStore
import com.refinvest.core.tradingreview.port.outbound.identity.TradingIdGenerator
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountStore
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingImportSessionStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingSourceArtifactReader
import com.refinvest.core.tradingreview.port.outbound.storage.raw.RawArtifactStorage
import java.io.InputStream
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
open class UploadTradingImportArtifactService(
    private val members: TradingMemberProvider,
    private val ids: TradingIdGenerator,
    private val accounts: TradingAccountStore,
    private val books: TradingBookStore,
    private val sessions: TradingImportSessionStore,
    private val artifacts: TradingSourceArtifactReader,
    private val idempotency: TradingIdempotencyStore,
    private val storage: RawArtifactStorage,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
) : UploadTradingImportArtifactUseCase {
    override fun execute(command: UploadTradingImportArtifactCommand): UploadArtifactResult =
        uploadDeferred(command.id, command.source, command.key, command.metadata).let {
            UploadArtifactResult(TradingSourceArtifactResult.fromDomain(it.artifact, clock.instant()), it.created)
        }

    private fun uploadDeferred(id: TradingImportSessionId, source: InputStream, key: String,
                                metadata: () -> TradingUploadMetadata): StoredUpload {
        validateIdempotencyKey(key)
        val owner = members.currentMemberId()
        val preflight = sessions.find(id, owner) ?: throw missingResource(sessions.deleted(id, owner))
        if (!preflight.canUpload()) throw TradingFailure("RESOURCE_STATE_CONFLICT", TradingFailureKind.CONFLICT)
        val preflightBook = books.find(preflight.bookId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val preflightAccount = accounts.find(preflightBook.accountId, owner)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        if (preflightBook.status != TradingLifecycleStatus.ACTIVE || preflightAccount.status != TradingLifecycleStatus.ACTIVE)
            throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        val artifactId = ids.sourceArtifactId()
        var staged = try {
            source.use { storage.putStaging(it, MAX_BYTES, artifactId, id) }
        } catch (failure: TradingFailure) { throw failure }
          catch (failure: Exception) { throw TradingFailure("TEMPORARY_DEPENDENCY_FAILURE", TradingFailureKind.DEPENDENCY_UNAVAILABLE) }
        var finalKey: String? = null
        try {
            val details = metadata()
            val role = details.role
            val contentType = details.contentType
            val dialectCandidate = details.dialectCandidate
            if (details.byteSize !in 1..MAX_BYTES) throw TradingFailure("FILE_TOO_LARGE", TradingFailureKind.PAYLOAD_TOO_LARGE)
            if (!SHA256.matches(details.sha256)) throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
            if (contentType !in CONTENT_TYPES) throw TradingFailure("UNSUPPORTED_MEDIA_TYPE", TradingFailureKind.UNSUPPORTED_MEDIA)
            if (dialectCandidate != "AUTO" && dialectCandidate != when (role) {
                    TradingArtifactRole.TRADE_HISTORY -> "BINANCE_USDS_TRADE_HISTORY_V1"
                    TradingArtifactRole.POSITION_HISTORY -> "BINANCE_USDS_POSITION_HISTORY_V1"
                }) throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
            val safeFilename = details.filename.substringAfterLast('/').substringAfterLast('\\')
            if (safeFilename.isBlank() || safeFilename.length > 255 || safeFilename.any { it.isISOControl() })
                throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
            if (staged.byteSize != details.byteSize || staged.sha256 != details.sha256)
                throw TradingFailure("CHECKSUM_MISMATCH", TradingFailureKind.INPUT_REJECTED)
            staged = storage.preparePublication(staged, role)
            val publishedKey = requireNotNull(staged.publicationKey)
            finalKey = publishedKey
            storage.promote(staged, publishedKey)
            if (!storage.exists(publishedKey)) throw TradingFailure("TEMPORARY_DEPENDENCY_FAILURE", TradingFailureKind.DEPENDENCY_UNAVAILABLE)
            val result = transactions.execute {
                val initial = sessions.find(id, owner) ?: throw missingResource(sessions.deleted(id, owner))
                val account = accounts.find((books.find(initial.bookId, owner)
                    ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)).accountId, owner, forUpdate = true)
                    ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
                val book = books.find(initial.bookId, owner, forUpdate = true)
                    ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
                val session = sessions.find(id, owner, forUpdate = true) ?: throw missingResource(sessions.deleted(id, owner))
                if (account.status != TradingLifecycleStatus.ACTIVE || book.status != TradingLifecycleStatus.ACTIVE)
                    throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
                if (!session.canUpload()) throw TradingFailure("RESOURCE_STATE_CONFLICT", TradingFailureKind.CONFLICT)
                val payload = requestFingerprint(id.value.toString(), role.name, staged.sha256, staged.byteSize.toString(),
                    contentType, dialectCandidate)
                val claim = idempotency.claim(owner, "uploadTradingImportArtifact", key, payload)
                if (claim is IdempotencyClaim.Replayed) {
                    val existing = artifacts.find(TradingSourceArtifactId(claim.resourceId), owner)
                        ?: throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
                    return@execute StoredUpload(existing, false)
                }
                val existing = session.artifacts.singleOrNull { it.role == role }
                if (existing != null) {
                    if (existing.sha256 != staged.sha256) throw TradingFailure("ARTIFACT_ROLE_CONFLICT", TradingFailureKind.CONFLICT)
                    if (existing.retentionStatus != TradingArtifactRetentionStatus.ACTIVE)
                        throw TradingFailure("SOURCE_ARTIFACT_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
                    idempotency.complete(owner, "uploadTradingImportArtifact", key, existing.id.value)
                    return@execute StoredUpload(existing, false)
                }
                val now = clock.instant()
                val changed = session.recordUpload(artifactId, role, contentType, staged.byteSize, staged.sha256,
                    dialectCandidate, publishedKey, staged.wrappedDek, staged.keyVersion, now)
                if (!sessions.update(changed)) throw TradingFailure("CONCURRENT_MODIFICATION", TradingFailureKind.CONFLICT)
                val artifact = changed.artifacts.single { it.id == artifactId }
                idempotency.complete(owner, "uploadTradingImportArtifact", key, artifactId.value)
                StoredUpload(artifact, true)
            } ?: throw TradingFailure("TEMPORARY_DEPENDENCY_FAILURE", TradingFailureKind.DEPENDENCY_UNAVAILABLE)
            if (!result.created) storage.delete(publishedKey)
            return result
        } catch (failure: Exception) {
            try {
                storage.delete(staged.stagingKey)
                finalKey?.let { if (!artifacts.referencesObjectKey(it)) storage.delete(it) }
            } catch (_: Exception) { /* orphan worker retries after grace period */ }
            if (failure is TradingFailure) throw failure
            throw TradingFailure("TEMPORARY_DEPENDENCY_FAILURE", TradingFailureKind.DEPENDENCY_UNAVAILABLE)
        } finally {
            storage.completePublication(staged.stagingKey)
        }
    }

    private data class StoredUpload(val artifact: com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact, val created: Boolean)

    private companion object {
        const val MAX_BYTES = 52_428_800L
        val SHA256 = Regex("[0-9a-f]{64}")
        val CONTENT_TYPES = setOf("text/csv", "application/vnd.ms-excel")
    }
}
