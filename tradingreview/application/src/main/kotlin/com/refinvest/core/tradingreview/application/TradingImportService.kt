package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRetentionStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingDataPolicy
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.port.*
import com.refinvest.core.tradingreview.port.account.TradingAccountStore
import com.refinvest.core.tradingreview.port.book.TradingBookStore
import com.refinvest.core.tradingreview.port.importsession.TradingImportSessionReader
import com.refinvest.core.tradingreview.port.importsession.TradingImportSessionStore
import com.refinvest.core.tradingreview.port.importsession.TradingSourceArtifactReader
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@Service
open class TradingImportService(
    private val members: TradingMemberProvider,
    private val ids: TradingIdGenerator,
    private val accounts: TradingAccountStore,
    private val books: TradingBookStore,
    private val sessions: TradingImportSessionStore,
    private val artifacts: TradingSourceArtifactReader,
    private val deletions: TradingDeletionStore,
    private val idempotency: TradingIdempotencyStore,
    private val storage: RawArtifactStorage,
    private val versions: TradingVersionPins,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val revisions: TradingLedgerPublicationReader,
    private val reader: TradingImportSessionReader,
) : TradingImportUseCase {
    @Transactional
    override fun create(bookId: TradingBookId, sourceTimezone: String, reviewTimezone: String,
                        mode: TradingPositionMode, dataPolicyVersion: String?, clientRequestReference: String?,
                        key: String): TradingImportSession {
        validKey(key)
        if (sourceTimezone !in ZoneId.getAvailableZoneIds()) throw TradingFailure("SOURCE_TIMEZONE_INVALID", 422)
        if (reviewTimezone !in ZoneId.getAvailableZoneIds()) throw TradingFailure("REVIEW_TIMEZONE_INVALID", 422)
        if (dataPolicyVersion != null && dataPolicyVersion != POLICY.dataPolicyVersion)
            throw TradingFailure("VERSION_UNAVAILABLE", 422)
        if (clientRequestReference != null && clientRequestReference.length > 100)
            throw TradingFailure("REQUEST_INVALID", 400)
        val owner = members.currentMemberId()
        val initial = books.find(bookId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        val account = accounts.find(initial.accountId, owner, forUpdate = true)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        val book = books.find(bookId, owner, forUpdate = true) ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        if (account.status != TradingLifecycleStatus.ACTIVE || book.status != TradingLifecycleStatus.ACTIVE)
            throw TradingFailure("RESOURCE_DELETED", 410)
        if (mode != TradingPositionMode.ONE_WAY || mode != book.positionMode)
            throw TradingFailure("POSITION_MODE_UNSUPPORTED", 422)
        val fingerprint = fingerprint(bookId.value.toString(), sourceTimezone, reviewTimezone, mode.name,
            POLICY.dataPolicyVersion, clientRequestReference ?: "")
        return when (val claim = idempotency.claim(owner, "createTradingImportSession", key, fingerprint)) {
            is IdempotencyClaim.Replayed -> sessions.find(TradingImportSessionId(claim.resourceId), owner)
                ?: throw TradingFailure("RESOURCE_DELETED", 410)
            IdempotencyClaim.New -> {
                val session = TradingImportSession.create(ids.importSessionId(), book, sourceTimezone,
                    reviewTimezone, mode, POLICY, versions.currentJson(), clock.instant())
                sessions.insert(session)
                idempotency.complete(owner, "createTradingImportSession", key, session.id.value)
                session
            }
        }
    }

    @Transactional(readOnly = true)
    override fun get(id: TradingImportSessionId): TradingImportSessionView {
        val owner = members.currentMemberId()
        val session = reader.find(id, owner) ?: throw missing(reader.deleted(id, owner))
        if (session.deletionPending) throw TradingFailure("RESOURCE_DELETED", 410)
        val book = books.find(session.bookId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        val account = accounts.find(book.accountId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        if (book.status != TradingLifecycleStatus.ACTIVE || account.status != TradingLifecycleStatus.ACTIVE)
            throw TradingFailure("RESOURCE_DELETED", 410)
        return TradingImportSessionView(session, session.artifacts,
            if (session.status == TradingImportStatus.ACCEPTED)
                revisions.publishedForSession(id, owner) else null)
    }

    @Transactional(readOnly = true)
    override fun list(bookId: TradingBookId, page: Int, size: Int): TradingPage<TradingImportSessionView> {
        if (page < 0 || size !in 1..100) throw TradingFailure("REQUEST_INVALID", 400)
        val owner = members.currentMemberId()
        val book = books.find(bookId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        val account = accounts.find(book.accountId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        if (book.status != TradingLifecycleStatus.ACTIVE || account.status != TradingLifecycleStatus.ACTIVE)
            throw TradingFailure("RESOURCE_DELETED", 410)
        val result = reader.list(bookId, owner, page, size)
        return TradingPage(result.items.map { session -> TradingImportSessionView(session,
            session.artifacts, if (session.status == TradingImportStatus.ACCEPTED)
                revisions.publishedForSession(session.id, owner) else null) }, page, size, result.total)
    }

    override fun upload(id: TradingImportSessionId, role: TradingArtifactRole, filename: String,
                        contentType: String, declaredSize: Long, declaredSha256: String,
                        dialectCandidate: String, source: InputStream, key: String): UploadArtifactResult =
        uploadDeferred(id, source, key) {
            TradingUploadMetadata(role, filename, contentType, declaredSize, declaredSha256, dialectCandidate)
        }

    override fun uploadDeferred(id: TradingImportSessionId, source: InputStream, key: String,
                                metadata: () -> TradingUploadMetadata): UploadArtifactResult {
        validKey(key)
        val owner = members.currentMemberId()
        val preflight = sessions.find(id, owner) ?: throw missing(sessions.deleted(id, owner))
        if (!preflight.canUpload()) throw TradingFailure("RESOURCE_STATE_CONFLICT", 409)
        val preflightBook = books.find(preflight.bookId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        val preflightAccount = accounts.find(preflightBook.accountId, owner)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        if (preflightBook.status != TradingLifecycleStatus.ACTIVE || preflightAccount.status != TradingLifecycleStatus.ACTIVE)
            throw TradingFailure("RESOURCE_DELETED", 410)
        val artifactId = ids.sourceArtifactId()
        val staged = try {
            source.use { storage.putStaging(it, MAX_BYTES, artifactId) }
        } catch (failure: TradingFailure) { throw failure }
          catch (failure: Exception) { throw TradingFailure("TEMPORARY_DEPENDENCY_FAILURE", 503) }
        val finalKey = "objects/" + UUID.randomUUID()
        try {
            val details = metadata()
            val role = details.role
            val contentType = details.contentType
            val dialectCandidate = details.dialectCandidate
            if (details.byteSize !in 1..MAX_BYTES) throw TradingFailure("FILE_TOO_LARGE", 413)
            if (!SHA256.matches(details.sha256)) throw TradingFailure("REQUEST_INVALID", 400)
            if (contentType !in CONTENT_TYPES) throw TradingFailure("UNSUPPORTED_MEDIA_TYPE", 415)
            if (dialectCandidate != "AUTO" && dialectCandidate != when (role) {
                    TradingArtifactRole.TRADE_HISTORY -> "BINANCE_USDS_TRADE_HISTORY_V1"
                    TradingArtifactRole.POSITION_HISTORY -> "BINANCE_USDS_POSITION_HISTORY_V1"
                }) throw TradingFailure("REQUEST_INVALID", 400)
            val safeFilename = details.filename.substringAfterLast('/').substringAfterLast('\\')
            if (safeFilename.isBlank() || safeFilename.length > 255 || safeFilename.any { it.isISOControl() })
                throw TradingFailure("REQUEST_INVALID", 400)
            if (staged.byteSize != details.byteSize || staged.sha256 != details.sha256)
                throw TradingFailure("CHECKSUM_MISMATCH", 422)
            storage.promote(staged, finalKey)
            if (!storage.exists(finalKey)) throw TradingFailure("TEMPORARY_DEPENDENCY_FAILURE", 503)
            val result = transactions.execute {
                val initial = sessions.find(id, owner) ?: throw missing(sessions.deleted(id, owner))
                val account = accounts.find((books.find(initial.bookId, owner)
                    ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)).accountId, owner, forUpdate = true)
                    ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
                val book = books.find(initial.bookId, owner, forUpdate = true)
                    ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
                val session = sessions.find(id, owner, forUpdate = true) ?: throw missing(sessions.deleted(id, owner))
                if (account.status != TradingLifecycleStatus.ACTIVE || book.status != TradingLifecycleStatus.ACTIVE)
                    throw TradingFailure("RESOURCE_DELETED", 410)
                if (!session.canUpload()) throw TradingFailure("RESOURCE_STATE_CONFLICT", 409)
                val payload = fingerprint(id.value.toString(), role.name, staged.sha256, staged.byteSize.toString(),
                    contentType, dialectCandidate)
                val claim = idempotency.claim(owner, "uploadTradingImportArtifact", key, payload)
                if (claim is IdempotencyClaim.Replayed) {
                    val existing = artifacts.find(TradingSourceArtifactId(claim.resourceId), owner)
                        ?: throw TradingFailure("RESOURCE_DELETED", 410)
                    return@execute UploadArtifactResult(existing, false)
                }
                val existing = session.artifacts.singleOrNull { it.role == role }
                if (existing != null) {
                    if (existing.sha256 != staged.sha256) throw TradingFailure("ARTIFACT_ROLE_CONFLICT", 409)
                    if (existing.retentionStatus != TradingArtifactRetentionStatus.ACTIVE)
                        throw TradingFailure("SOURCE_ARTIFACT_DELETED", 410)
                    idempotency.complete(owner, "uploadTradingImportArtifact", key, existing.id.value)
                    return@execute UploadArtifactResult(existing, false)
                }
                val now = clock.instant()
                val changed = session.recordUpload(artifactId, role, contentType, staged.byteSize, staged.sha256,
                    dialectCandidate, finalKey, staged.wrappedDek, staged.keyVersion, now)
                if (!sessions.update(changed)) throw TradingFailure("CONCURRENT_MODIFICATION", 409)
                val artifact = changed.artifacts.single { it.id == artifactId }
                idempotency.complete(owner, "uploadTradingImportArtifact", key, artifactId.value)
                UploadArtifactResult(artifact, true)
            } ?: throw TradingFailure("TEMPORARY_DEPENDENCY_FAILURE", 503)
            if (!result.created) storage.delete(finalKey)
            return result
        } catch (failure: Exception) {
            try {
                storage.delete(staged.stagingKey)
                if (!artifacts.referencesObjectKey(finalKey)) storage.delete(finalKey)
            } catch (_: Exception) { /* orphan worker retries after grace period */ }
            if (failure is TradingFailure) throw failure
            throw TradingFailure("TEMPORARY_DEPENDENCY_FAILURE", 503)
        } finally {
            storage.completePublication(finalKey)
        }
    }

    @Transactional
    override fun delete(id: TradingImportSessionId, key: String): TradingDeletion {
        validKey(key)
        val owner = members.currentMemberId()
        val operation = "deleteTradingImportSession"
        val fingerprint = fingerprint(id.value.toString())
        val claim = idempotency.claim(owner, operation, key, fingerprint)
        if (claim is IdempotencyClaim.Replayed) return deletions.find(claim.resourceId, owner)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        deletions.findByTarget(owner, "TRADING_IMPORT_SESSION", id.value)?.let { existing ->
            idempotency.complete(owner, operation, key, existing.id)
            return existing
        }
        val initial = sessions.find(id, owner) ?: throw missing(sessions.deleted(id, owner))
        val account = accounts.find((books.find(initial.bookId, owner)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)).accountId, owner, forUpdate = true)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        val book = books.find(initial.bookId, owner, forUpdate = true) ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        val session = sessions.find(id, owner, forUpdate = true) ?: throw missing(sessions.deleted(id, owner))
        if (account.status != TradingLifecycleStatus.ACTIVE || book.status != TradingLifecycleStatus.ACTIVE)
            throw TradingFailure("RESOURCE_DELETED", 410)
        if (session.deletionPending) throw TradingFailure("RESOURCE_DELETED", 410)
        val now = clock.instant()
        val changed = session.requestDeletion(now)
        if (!sessions.update(changed)) throw TradingFailure("CONCURRENT_MODIFICATION", 409)
        val count = session.artifacts.size.toLong()
        val deletion = TradingDeletion(ids.deletionId(), owner, "TRADING_IMPORT_SESSION", id.value,
            "IMPORT_SESSION_SUBTREE", "REQUESTED", changed.deletionGeneration, 0, 0,
            now, now, now, now.plusSeconds(30L * 24 * 60 * 60), importSessionCount = 1, artifactCount = count)
        deletions.insert(deletion)
        idempotency.complete(owner, operation, key, deletion.id)
        return deletion
    }

    @Transactional
    override fun deleteArtifact(id: TradingSourceArtifactId, key: String): TradingDeletion {
        validKey(key)
        val owner = members.currentMemberId()
        val operation = "deleteTradingSourceArtifact"
        val fingerprint = fingerprint(id.value.toString())
        val claim = idempotency.claim(owner, operation, key, fingerprint)
        if (claim is IdempotencyClaim.Replayed) return deletions.find(claim.resourceId, owner)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        deletions.findByTarget(owner, "TRADING_SOURCE_ARTIFACT", id.value)?.let { existing ->
            idempotency.complete(owner, operation, key, existing.id)
            return existing
        }
        val initialArtifact = artifacts.find(id, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        val initialSession = sessions.find(initialArtifact.sessionId, owner)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        val initialBook = books.find(initialSession.bookId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        val account = accounts.find(initialBook.accountId, owner, forUpdate = true)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        val book = books.find(initialBook.id, owner, forUpdate = true)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        val session = sessions.find(initialSession.id, owner, forUpdate = true)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        val artifact = session.artifacts.singleOrNull { it.id == id } ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        if (account.status != TradingLifecycleStatus.ACTIVE || book.status != TradingLifecycleStatus.ACTIVE ||
            session.deletionPending) throw TradingFailure("RESOURCE_DELETED", 410)
        if (artifact.retentionStatus != TradingArtifactRetentionStatus.ACTIVE &&
            artifact.retentionStatus != TradingArtifactRetentionStatus.RETENTION_SCHEDULED)
            throw TradingFailure("RESOURCE_DELETED", 410)
        val now = clock.instant()
        val changed = session.requestArtifactDeletion(id, now)
        if (!sessions.update(changed))
            throw TradingFailure("CONCURRENT_MODIFICATION", 409)
        val deletion = TradingDeletion(ids.deletionId(), owner, "TRADING_SOURCE_ARTIFACT", id.value,
            "RAW_ONLY", "REQUESTED", changed.deletionGeneration, 0, 0,
            now, now, now, now.plusSeconds(30L * 24 * 60 * 60), artifactCount = 1)
        deletions.insert(deletion)
        idempotency.complete(owner, operation, key, deletion.id)
        return deletion
    }

    private fun missing(deleted: Boolean) = TradingFailure(if (deleted) "RESOURCE_DELETED" else "RESOURCE_NOT_FOUND",
        if (deleted) 410 else 404)

    private fun validKey(key: String) {
        if (key.length !in 1..128 || !KEY.matches(key)) throw TradingFailure("IDEMPOTENCY_KEY_INVALID", 400)
    }

    private fun fingerprint(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach {
            val bytes = it.toByteArray(StandardCharsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            digest.update(0)
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val POLICY = TradingDataPolicy()
        const val MAX_BYTES = 52_428_800L
        val KEY = Regex("[A-Za-z0-9._~-]+")
        val SHA256 = Regex("[0-9a-f]{64}")
        val CONTENT_TYPES = setOf("text/csv", "application/vnd.ms-excel")
    }
}
