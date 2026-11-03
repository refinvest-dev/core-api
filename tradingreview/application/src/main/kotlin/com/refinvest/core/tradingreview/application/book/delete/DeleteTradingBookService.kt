package com.refinvest.core.tradingreview.application.book.delete

import com.refinvest.core.tradingreview.application.support.missingResource
import com.refinvest.core.tradingreview.application.support.requestFingerprint
import com.refinvest.core.tradingreview.application.support.validateIdempotencyKey
import com.refinvest.core.tradingreview.domain.DeletionRequest
import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import com.refinvest.core.tradingreview.domain.valueobject.DeletionScope
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.book.delete.DeleteTradingBookCommand
import com.refinvest.core.tradingreview.port.inbound.book.delete.DeleteTradingBookUseCase
import com.refinvest.core.tradingreview.port.inbound.deletion.TradingDeletion
import com.refinvest.core.tradingreview.port.outbound.idempotency.IdempotencyClaim
import com.refinvest.core.tradingreview.port.outbound.idempotency.TradingIdempotencyStore
import com.refinvest.core.tradingreview.port.outbound.identity.TradingIdGenerator
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountStore
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookStore
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionRequestStore
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionScopeInventory
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class DeleteTradingBookService(
    private val members: TradingMemberProvider,
    private val ids: TradingIdGenerator,
    private val accounts: TradingAccountStore,
    private val books: TradingBookStore,
    private val deletions: DeletionRequestStore,
    private val idempotency: TradingIdempotencyStore,
    private val clock: Clock,
    private val deletionInventory: DeletionScopeInventory,
) : DeleteTradingBookUseCase {
    @Transactional
    override fun execute(command: DeleteTradingBookCommand): TradingDeletion =
        deleteBook(command.id, command.key)

    private fun deleteBook(id: TradingBookId, key: String): TradingDeletion {
        validateIdempotencyKey(key)
        val owner = members.currentMemberId()
        val operation = "deleteTradingBook"
        val fingerprint = requestFingerprint(id.value.toString())
        val existing = idempotency.claim(owner, operation, key, fingerprint)
        if (existing is IdempotencyClaim.Replayed) return deletions.find(DeletionRequestId(existing.resourceId), owner)?.let(TradingDeletion::fromDomain)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val initialBook = books.find(id, owner) ?: throw missingResource(books.deleted(id, owner))
        val account = accounts.find(initialBook.accountId, owner, forUpdate = true) ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        if (account.status != TradingLifecycleStatus.ACTIVE) throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        val book = books.find(id, owner, forUpdate = true) ?: throw missingResource(books.deleted(id, owner))
        if (book.status != TradingLifecycleStatus.ACTIVE) throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        val now = clock.instant()
        val deletingBook = book.requestDeletion(now)
        if (!books.update(deletingBook)) throw TradingFailure("CONCURRENT_MODIFICATION", TradingFailureKind.CONFLICT)
        val deletion = DeletionRequest.request(ids.deletionId(), owner, DeletionScope.book(id),
            deletingBook.deletionGeneration, deletionInventory.capture(DeletionScope.book(id), owner)
                ?: throw TradingFailure("CONCURRENT_MODIFICATION", TradingFailureKind.CONFLICT), now)
        deletions.insert(deletion)
        idempotency.complete(owner, operation, key, deletion.id.value)
        return TradingDeletion.fromDomain(deletion)
    }
}
