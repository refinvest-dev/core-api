package com.refinvest.core.tradingreview.application.importsession.deleteartifact

import com.refinvest.core.tradingreview.application.support.requestFingerprint
import com.refinvest.core.tradingreview.application.support.validateIdempotencyKey
import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.DeletionAffectedResources
import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import com.refinvest.core.tradingreview.domain.valueobject.DeletionScope
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRetentionStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.deletion.TradingDeletion
import com.refinvest.core.tradingreview.port.inbound.importsession.deleteartifact.DeleteTradingSourceArtifactCommand
import com.refinvest.core.tradingreview.port.inbound.importsession.deleteartifact.DeleteTradingSourceArtifactUseCase
import com.refinvest.core.tradingreview.port.outbound.idempotency.IdempotencyClaim
import com.refinvest.core.tradingreview.port.outbound.idempotency.TradingIdempotencyStore
import com.refinvest.core.tradingreview.port.outbound.identity.TradingIdGenerator
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountStore
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookStore
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionRequestStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingImportSessionStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingSourceArtifactReader
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class DeleteTradingSourceArtifactService(
    private val members: TradingMemberProvider,
    private val ids: TradingIdGenerator,
    private val accounts: TradingAccountStore,
    private val books: TradingBookStore,
    private val sessions: TradingImportSessionStore,
    private val artifacts: TradingSourceArtifactReader,
    private val deletions: DeletionRequestStore,
    private val idempotency: TradingIdempotencyStore,
    private val clock: Clock,
) : DeleteTradingSourceArtifactUseCase {
    @Transactional
    override fun execute(command: DeleteTradingSourceArtifactCommand): TradingDeletion =
        deleteArtifact(command.id, command.key)

    private fun deleteArtifact(id: TradingSourceArtifactId, key: String): TradingDeletion {
        validateIdempotencyKey(key)
        val owner = members.currentMemberId()
        val operation = "deleteTradingSourceArtifact"
        val fingerprint = requestFingerprint(id.value.toString())
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
}
