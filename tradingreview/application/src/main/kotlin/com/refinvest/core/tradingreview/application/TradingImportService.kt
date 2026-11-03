package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.port.importsession.TradingImportSessionResult
import com.refinvest.core.tradingreview.port.importsession.TradingImportSessionView
import com.refinvest.core.tradingreview.port.importsession.TradingSourceArtifactResult
import com.refinvest.core.tradingreview.port.importsession.create.CreateTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.importsession.create.CreateTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.importsession.delete.DeleteTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.importsession.delete.DeleteTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.importsession.deleteartifact.DeleteTradingSourceArtifactCommand
import com.refinvest.core.tradingreview.port.importsession.deleteartifact.DeleteTradingSourceArtifactUseCase
import com.refinvest.core.tradingreview.port.importsession.get.GetTradingImportSessionQuery
import com.refinvest.core.tradingreview.port.importsession.get.GetTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.importsession.list.ListTradingImportsQuery
import com.refinvest.core.tradingreview.port.importsession.list.ListTradingImportsUseCase
import com.refinvest.core.tradingreview.port.importsession.upload.UploadArtifactResult
import com.refinvest.core.tradingreview.port.importsession.upload.UploadTradingImportArtifactCommand
import com.refinvest.core.tradingreview.port.importsession.upload.UploadTradingImportArtifactUseCase

import com.refinvest.core.tradingreview.port.artifactstorage.RawArtifactStorage
import com.refinvest.core.tradingreview.port.idempotency.IdempotencyClaim
import com.refinvest.core.tradingreview.port.idempotency.TradingIdempotencyStore
import com.refinvest.core.tradingreview.port.identity.TradingIdGenerator
import com.refinvest.core.tradingreview.port.importsession.upload.TradingUploadMetadata
import com.refinvest.core.tradingreview.port.ledger.TradingLedgerPublicationReader
import com.refinvest.core.tradingreview.port.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.normalization.policy.TradingVersionPins
import com.refinvest.core.tradingreview.port.pagination.TradingPage

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.DeletionAffectedResources
import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import com.refinvest.core.tradingreview.domain.valueobject.DeletionScope
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
import com.refinvest.core.tradingreview.port.TradingFailureKind
import com.refinvest.core.tradingreview.port.account.TradingAccountStore
import com.refinvest.core.tradingreview.port.book.TradingBookStore
import com.refinvest.core.tradingreview.port.deletion.DeletionRequestStore
import com.refinvest.core.tradingreview.port.deletion.DeletionScopeInventory
import com.refinvest.core.tradingreview.port.deletion.TradingDeletion
import com.refinvest.core.tradingreview.port.importsession.TradingImportSessionReader
import com.refinvest.core.tradingreview.port.importsession.TradingImportSessionStore
import com.refinvest.core.tradingreview.port.importsession.TradingSourceArtifactReader
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneId
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
    private val deletions: DeletionRequestStore,
    private val idempotency: TradingIdempotencyStore,
    private val storage: RawArtifactStorage,
    private val versions: TradingVersionPins,
    private val transactions: TransactionTemplate,
    private val clock: Clock,
    private val revisions: TradingLedgerPublicationReader,
    private val reader: TradingImportSessionReader,
    private val deletionInventory: DeletionScopeInventory,
) : CreateTradingImportSessionUseCase, GetTradingImportSessionUseCase, ListTradingImportsUseCase,
    UploadTradingImportArtifactUseCase, DeleteTradingImportSessionUseCase, DeleteTradingSourceArtifactUseCase {
    @Transactional
    override fun execute(command: CreateTradingImportSessionCommand): TradingImportSessionResult =
        TradingImportSessionResult.fromDomain(createSession(command.bookId, command.sourceTimezone,
            command.reviewTimezone, command.mode, command.dataPolicyVersion, command.clientRequestReference, command.key))

    @Transactional(readOnly = true)
    override fun execute(query: GetTradingImportSessionQuery): TradingImportSessionView =
        getSession(query.id).let { TradingImportSessionView.fromDomain(it.session, it.artifacts, it.published, clock.instant()) }

    @Transactional(readOnly = true)
    override fun execute(query: ListTradingImportsQuery): TradingPage<TradingImportSessionView> =
        listSessions(query.bookId, query.page, query.size).let { page ->
            val now = clock.instant()
            TradingPage(page.items.map {
                TradingImportSessionView.fromDomain(it.session, it.artifacts, it.published, now)
            }, page.page, page.size, page.total)
        }

    override fun execute(command: UploadTradingImportArtifactCommand): UploadArtifactResult =
        uploadDeferred(command.id, command.source, command.key, command.metadata).let {
            UploadArtifactResult(TradingSourceArtifactResult.fromDomain(it.artifact, clock.instant()), it.created)
        }

    @Transactional
    override fun execute(command: DeleteTradingImportSessionCommand): TradingDeletion =
        deleteSession(command.id, command.key)

    @Transactional
    override fun execute(command: DeleteTradingSourceArtifactCommand): TradingDeletion =
        deleteArtifact(command.id, command.key)

    private fun createSession(bookId: TradingBookId, sourceTimezone: String, reviewTimezone: String,
                        mode: TradingPositionMode, dataPolicyVersion: String?, clientRequestReference: String?,
                        key: String): TradingImportSession {
        validKey(key)
        if (sourceTimezone !in ZoneId.getAvailableZoneIds()) throw TradingFailure("SOURCE_TIMEZONE_INVALID", TradingFailureKind.INPUT_REJECTED)
        if (reviewTimezone !in ZoneId.getAvailableZoneIds()) throw TradingFailure("REVIEW_TIMEZONE_INVALID", TradingFailureKind.INPUT_REJECTED)
        if (dataPolicyVersion != null && dataPolicyVersion != POLICY.dataPolicyVersion)
            throw TradingFailure("VERSION_UNAVAILABLE", TradingFailureKind.INPUT_REJECTED)
        if (clientRequestReference != null && clientRequestReference.length > 100)
            throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
        val owner = members.currentMemberId()
        val initial = books.find(bookId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val account = accounts.find(initial.accountId, owner, forUpdate = true)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val book = books.find(bookId, owner, forUpdate = true) ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        if (account.status != TradingLifecycleStatus.ACTIVE || book.status != TradingLifecycleStatus.ACTIVE)
            throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        if (mode != TradingPositionMode.ONE_WAY || mode != book.positionMode)
            throw TradingFailure("POSITION_MODE_UNSUPPORTED", TradingFailureKind.INPUT_REJECTED)
        val fingerprint = fingerprint(bookId.value.toString(), sourceTimezone, reviewTimezone, mode.name,
            POLICY.dataPolicyVersion, clientRequestReference ?: "")
        return when (val claim = idempotency.claim(owner, "createTradingImportSession", key, fingerprint)) {
            is IdempotencyClaim.Replayed -> sessions.find(TradingImportSessionId(claim.resourceId), owner)
                ?.takeUnless { it.deletionPending }
                ?: throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
            IdempotencyClaim.New -> {
                val session = TradingImportSession.create(ids.importSessionId(), book, sourceTimezone,
                    reviewTimezone, mode, POLICY, versions.currentJson(), clock.instant())
                sessions.insert(session)
                idempotency.complete(owner, "createTradingImportSession", key, session.id.value)
                session
            }
        }
    }

    private fun getSession(id: TradingImportSessionId): LoadedImportSession {
        val owner = members.currentMemberId()
        val session = reader.find(id, owner) ?: throw missing(reader.deleted(id, owner))
        if (session.deletionPending) throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        val book = books.find(session.bookId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val account = accounts.find(book.accountId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        if (book.status != TradingLifecycleStatus.ACTIVE || account.status != TradingLifecycleStatus.ACTIVE)
            throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        return LoadedImportSession(session, session.artifacts,
            if (session.status == TradingImportStatus.ACCEPTED)
                revisions.publishedForSession(id, owner) else null)
    }

    private fun listSessions(bookId: TradingBookId, page: Int, size: Int): TradingPage<LoadedImportSession> {
        if (page < 0 || size !in 1..100) throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
        val owner = members.currentMemberId()
        val book = books.find(bookId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val account = accounts.find(book.accountId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        if (book.status != TradingLifecycleStatus.ACTIVE || account.status != TradingLifecycleStatus.ACTIVE)
            throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        val result = reader.list(bookId, owner, page, size)
        return TradingPage(result.items.map { session -> LoadedImportSession(session,
            session.artifacts, if (session.status == TradingImportStatus.ACCEPTED)
                revisions.publishedForSession(session.id, owner) else null) }, page, size, result.total)
    }

    private fun uploadDeferred(id: TradingImportSessionId, source: InputStream, key: String,
                                metadata: () -> TradingUploadMetadata): StoredUpload {
        validKey(key)
        val owner = members.currentMemberId()
        val preflight = sessions.find(id, owner) ?: throw missing(sessions.deleted(id, owner))
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
                val initial = sessions.find(id, owner) ?: throw missing(sessions.deleted(id, owner))
                val account = accounts.find((books.find(initial.bookId, owner)
                    ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)).accountId, owner, forUpdate = true)
                    ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
                val book = books.find(initial.bookId, owner, forUpdate = true)
                    ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
                val session = sessions.find(id, owner, forUpdate = true) ?: throw missing(sessions.deleted(id, owner))
                if (account.status != TradingLifecycleStatus.ACTIVE || book.status != TradingLifecycleStatus.ACTIVE)
                    throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
                if (!session.canUpload()) throw TradingFailure("RESOURCE_STATE_CONFLICT", TradingFailureKind.CONFLICT)
                val payload = fingerprint(id.value.toString(), role.name, staged.sha256, staged.byteSize.toString(),
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

    private fun deleteSession(id: TradingImportSessionId, key: String): TradingDeletion {
        validKey(key)
        val owner = members.currentMemberId()
        val operation = "deleteTradingImportSession"
        val fingerprint = fingerprint(id.value.toString())
        val claim = idempotency.claim(owner, operation, key, fingerprint)
        if (claim is IdempotencyClaim.Replayed) return deletions.find(DeletionRequestId(claim.resourceId), owner)?.let(TradingDeletion::fromDomain)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        deletions.findByTarget(owner, DeletionScope.importSession(id))?.let { existing ->
            idempotency.complete(owner, operation, key, existing.id.value)
            return TradingDeletion.fromDomain(existing)
        }
        val initial = sessions.find(id, owner) ?: throw missing(sessions.deleted(id, owner))
        val account = accounts.find((books.find(initial.bookId, owner)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)).accountId, owner, forUpdate = true)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val book = books.find(initial.bookId, owner, forUpdate = true) ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val session = sessions.find(id, owner, forUpdate = true) ?: throw missing(sessions.deleted(id, owner))
        if (account.status != TradingLifecycleStatus.ACTIVE || book.status != TradingLifecycleStatus.ACTIVE)
            throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        if (session.deletionPending) throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        val now = clock.instant()
        val changed = session.requestDeletion(now)
        if (!sessions.update(changed)) throw TradingFailure("CONCURRENT_MODIFICATION", TradingFailureKind.CONFLICT)
        val deletion = DeletionRequest.request(ids.deletionId(), owner, DeletionScope.importSession(id),
            changed.deletionGeneration, deletionInventory.capture(DeletionScope.importSession(id), owner)
                ?: throw TradingFailure("CONCURRENT_MODIFICATION", TradingFailureKind.CONFLICT), now)
        deletions.insert(deletion)
        idempotency.complete(owner, operation, key, deletion.id.value)
        return TradingDeletion.fromDomain(deletion)
    }

    private fun deleteArtifact(id: TradingSourceArtifactId, key: String): TradingDeletion {
        validKey(key)
        val owner = members.currentMemberId()
        val operation = "deleteTradingSourceArtifact"
        val fingerprint = fingerprint(id.value.toString())
        val claim = idempotency.claim(owner, operation, key, fingerprint)
        if (claim is IdempotencyClaim.Replayed) return deletions.find(DeletionRequestId(claim.resourceId), owner)?.let(TradingDeletion::fromDomain)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        deletions.findByTarget(owner, DeletionScope.rawArtifact(id))?.let { existing ->
            idempotency.complete(owner, operation, key, existing.id.value)
            return TradingDeletion.fromDomain(existing)
        }
        val initialArtifact = artifacts.find(id, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val initialSession = sessions.find(initialArtifact.sessionId, owner)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val initialBook = books.find(initialSession.bookId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val account = accounts.find(initialBook.accountId, owner, forUpdate = true)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val book = books.find(initialBook.id, owner, forUpdate = true)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val session = sessions.find(initialSession.id, owner, forUpdate = true)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val artifact = session.artifacts.singleOrNull { it.id == id } ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        if (account.status != TradingLifecycleStatus.ACTIVE || book.status != TradingLifecycleStatus.ACTIVE ||
            session.deletionPending) throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        if (artifact.retentionStatus != TradingArtifactRetentionStatus.ACTIVE &&
            artifact.retentionStatus != TradingArtifactRetentionStatus.RETENTION_SCHEDULED)
            throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        val now = clock.instant()
        val changed = session.requestArtifactDeletion(id, now)
        if (!sessions.update(changed))
            throw TradingFailure("CONCURRENT_MODIFICATION", TradingFailureKind.CONFLICT)
        val deletion = DeletionRequest.request(ids.deletionId(), owner, DeletionScope.rawArtifact(id),
            changed.deletionGeneration, DeletionAffectedResources(0, 0, 0, 1), now)
        deletions.insert(deletion)
        idempotency.complete(owner, operation, key, deletion.id.value)
        return TradingDeletion.fromDomain(deletion)
    }

    private data class LoadedImportSession(val session: TradingImportSession, val artifacts: List<com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact>, val published: com.refinvest.core.tradingreview.port.ledger.PublishedLedgerSummary?)
    private data class StoredUpload(val artifact: com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact, val created: Boolean)

    private fun missing(deleted: Boolean) = TradingFailure(if (deleted) "RESOURCE_DELETED" else "RESOURCE_NOT_FOUND",
        if (deleted) TradingFailureKind.NO_LONGER_AVAILABLE else TradingFailureKind.NOT_FOUND)

    private fun validKey(key: String) {
        if (key.length !in 1..128 || !KEY.matches(key)) throw TradingFailure("IDEMPOTENCY_KEY_INVALID", TradingFailureKind.INVALID_REQUEST)
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
