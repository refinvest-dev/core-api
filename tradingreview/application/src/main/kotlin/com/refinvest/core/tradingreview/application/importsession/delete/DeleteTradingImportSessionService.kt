package com.refinvest.core.tradingreview.application.importsession.delete

import com.refinvest.core.tradingreview.application.support.missingResource
import com.refinvest.core.tradingreview.application.support.requestFingerprint
import com.refinvest.core.tradingreview.application.support.validateIdempotencyKey
import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import com.refinvest.core.tradingreview.domain.valueobject.DeletionScope
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.deletion.TradingDeletion
import com.refinvest.core.tradingreview.port.inbound.importsession.delete.DeleteTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.inbound.importsession.delete.DeleteTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.outbound.idempotency.IdempotencyClaim
import com.refinvest.core.tradingreview.port.outbound.idempotency.TradingIdempotencyStore
import com.refinvest.core.tradingreview.port.outbound.identity.TradingIdGenerator
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountStore
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookStore
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionRequestStore
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionScopeInventory
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingImportSessionStore
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class DeleteTradingImportSessionService(
    private val members: TradingMemberProvider,
    private val ids: TradingIdGenerator,
    private val accounts: TradingAccountStore,
    private val books: TradingBookStore,
    private val sessions: TradingImportSessionStore,
    private val deletions: DeletionRequestStore,
    private val idempotency: TradingIdempotencyStore,
    private val clock: Clock,
    private val deletionInventory: DeletionScopeInventory,
) : DeleteTradingImportSessionUseCase {
    @Transactional
    override fun execute(command: DeleteTradingImportSessionCommand): TradingDeletion =
        deleteSession(command.id, command.key)

    private fun deleteSession(id: TradingImportSessionId, key: String): TradingDeletion {
        validateIdempotencyKey(key)
        val owner = members.currentMemberId()
        val operation = "deleteTradingImportSession"
        val fingerprint = requestFingerprint(id.value.toString())
        val claim = idempotency.claim(owner, operation, key, fingerprint)
        if (claim is IdempotencyClaim.Replayed) return deletions.find(DeletionRequestId(claim.resourceId), owner)?.let(TradingDeletion::fromDomain)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        deletions.findByTarget(owner, DeletionScope.importSession(id))?.let { existing ->
            idempotency.complete(owner, operation, key, existing.id.value)
            return TradingDeletion.fromDomain(existing)
        }
        val initial = sessions.find(id, owner) ?: throw missingResource(sessions.deleted(id, owner))
        val account = accounts.find((books.find(initial.bookId, owner)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)).accountId, owner, forUpdate = true)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val book = books.find(initial.bookId, owner, forUpdate = true) ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val session = sessions.find(id, owner, forUpdate = true) ?: throw missingResource(sessions.deleted(id, owner))
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
}
