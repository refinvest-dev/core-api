package com.refinvest.core.tradingreview.application.account.delete

import com.refinvest.core.tradingreview.application.support.missingResource
import com.refinvest.core.tradingreview.application.support.requestFingerprint
import com.refinvest.core.tradingreview.application.support.validateIdempotencyKey
import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import com.refinvest.core.tradingreview.domain.valueobject.DeletionScope
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.account.delete.DeleteTradingAccountCommand
import com.refinvest.core.tradingreview.port.inbound.account.delete.DeleteTradingAccountUseCase
import com.refinvest.core.tradingreview.port.inbound.deletion.TradingDeletion
import com.refinvest.core.tradingreview.port.outbound.idempotency.IdempotencyClaim
import com.refinvest.core.tradingreview.port.outbound.idempotency.TradingIdempotencyStore
import com.refinvest.core.tradingreview.port.outbound.identity.TradingIdGenerator
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountStore
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingAccountBookDeletion
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookDeletionCascade
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionRequestStore
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionScopeInventory
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class DeleteTradingAccountService(
    private val members: TradingMemberProvider,
    private val ids: TradingIdGenerator,
    private val accounts: TradingAccountStore,
    private val deletions: DeletionRequestStore,
    private val idempotency: TradingIdempotencyStore,
    private val clock: Clock,
    private val bookDeletionCascade: TradingBookDeletionCascade,
    private val deletionInventory: DeletionScopeInventory,
) : DeleteTradingAccountUseCase {
    @Transactional
    override fun execute(command: DeleteTradingAccountCommand): TradingDeletion =
        deleteAccount(command.id, command.key)

    private fun deleteAccount(id: TradingAccountId, key: String): TradingDeletion {
        validateIdempotencyKey(key)
        val owner = members.currentMemberId()
        val operation = "deleteTradingAccount"
        val fingerprint = requestFingerprint(id.value.toString())
        val existing = idempotency.claim(owner, operation, key, fingerprint)
        if (existing is IdempotencyClaim.Replayed) return deletions.find(DeletionRequestId(existing.resourceId), owner)?.let(TradingDeletion::fromDomain)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val account = accounts.find(id, owner, forUpdate = true) ?: throw missingResource(accounts.deleted(id, owner))
        if (account.status != TradingLifecycleStatus.ACTIVE && account.status != TradingLifecycleStatus.ARCHIVED)
            throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        val now = clock.instant()
        val deletingAccount = account.requestDeletion(now)
        if (!accounts.update(deletingAccount)) throw TradingFailure("CONCURRENT_MODIFICATION", TradingFailureKind.CONFLICT)
        bookDeletionCascade.markDeleting(TradingAccountBookDeletion(
            deletingAccount.id, owner, deletingAccount.deletionGeneration, Math.addExact(account.version, 1), now,
        )) ?: throw TradingFailure("CONCURRENT_MODIFICATION", TradingFailureKind.CONFLICT)
        val deletion = DeletionRequest.request(ids.deletionId(), owner, DeletionScope.account(id),
            deletingAccount.deletionGeneration, deletionInventory.capture(DeletionScope.account(id), owner)
                ?: throw TradingFailure("CONCURRENT_MODIFICATION", TradingFailureKind.CONFLICT), now)
        deletions.insert(deletion)
        idempotency.complete(owner, operation, key, deletion.id.value)
        return TradingDeletion.fromDomain(deletion)
    }
}
