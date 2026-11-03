package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.port.account.TradingAccountResult
import com.refinvest.core.tradingreview.port.account.create.CreateTradingAccountCommand
import com.refinvest.core.tradingreview.port.account.create.CreateTradingAccountUseCase
import com.refinvest.core.tradingreview.port.account.delete.DeleteTradingAccountCommand
import com.refinvest.core.tradingreview.port.account.delete.DeleteTradingAccountUseCase
import com.refinvest.core.tradingreview.port.account.get.GetTradingAccountQuery
import com.refinvest.core.tradingreview.port.account.get.GetTradingAccountUseCase
import com.refinvest.core.tradingreview.port.account.list.ListTradingAccountsQuery
import com.refinvest.core.tradingreview.port.account.list.ListTradingAccountsUseCase
import com.refinvest.core.tradingreview.port.book.TradingBookResult
import com.refinvest.core.tradingreview.port.book.create.CreateTradingBookCommand
import com.refinvest.core.tradingreview.port.book.create.CreateTradingBookUseCase
import com.refinvest.core.tradingreview.port.book.delete.DeleteTradingBookCommand
import com.refinvest.core.tradingreview.port.book.delete.DeleteTradingBookUseCase
import com.refinvest.core.tradingreview.port.book.get.GetTradingBookQuery
import com.refinvest.core.tradingreview.port.book.get.GetTradingBookUseCase
import com.refinvest.core.tradingreview.port.book.list.ListTradingBooksQuery
import com.refinvest.core.tradingreview.port.book.list.ListTradingBooksUseCase

import com.refinvest.core.tradingreview.port.idempotency.IdempotencyClaim
import com.refinvest.core.tradingreview.port.idempotency.TradingIdempotencyStore
import com.refinvest.core.tradingreview.port.identity.TradingIdGenerator
import com.refinvest.core.tradingreview.port.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.pagination.TradingPage

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.DeletionAffectedResources
import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import com.refinvest.core.tradingreview.domain.valueobject.DeletionScope
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingProductFamily
import com.refinvest.core.tradingreview.domain.valueobject.TradingSettlementAsset
import com.refinvest.core.tradingreview.domain.valueobject.TradingVenue
import com.refinvest.core.tradingreview.port.*
import com.refinvest.core.tradingreview.port.TradingFailureKind
import com.refinvest.core.tradingreview.port.account.TradingAccountReader
import com.refinvest.core.tradingreview.port.account.TradingAccountStore
import com.refinvest.core.tradingreview.port.book.TradingAccountBookDeletion
import com.refinvest.core.tradingreview.port.book.TradingBookDeletionCascade
import com.refinvest.core.tradingreview.port.book.TradingBookReader
import com.refinvest.core.tradingreview.port.book.TradingBookStore
import com.refinvest.core.tradingreview.port.deletion.DeletionRequestStore
import com.refinvest.core.tradingreview.port.deletion.DeletionScopeInventory
import com.refinvest.core.tradingreview.port.deletion.TradingDeletion
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class TradingReviewService(
    private val members: TradingMemberProvider,
    private val ids: TradingIdGenerator,
    private val accounts: TradingAccountStore,
    private val books: TradingBookStore,
    private val deletions: DeletionRequestStore,
    private val idempotency: TradingIdempotencyStore,
    private val clock: Clock,
    private val accountReader: TradingAccountReader,
    private val bookReader: TradingBookReader,
    private val bookDeletionCascade: TradingBookDeletionCascade,
    private val deletionInventory: DeletionScopeInventory,
) : CreateTradingAccountUseCase, GetTradingAccountUseCase, ListTradingAccountsUseCase, DeleteTradingAccountUseCase,
    CreateTradingBookUseCase, GetTradingBookUseCase, ListTradingBooksUseCase, DeleteTradingBookUseCase {
    @Transactional
    override fun execute(command: CreateTradingAccountCommand): TradingAccountResult =
        TradingAccountResult.fromDomain(createAccount(command.venue, command.displayName, command.key))

    @Transactional(readOnly = true)
    override fun execute(query: GetTradingAccountQuery): TradingAccountResult =
        TradingAccountResult.fromDomain(getAccount(query.id))

    @Transactional(readOnly = true)
    override fun execute(query: ListTradingAccountsQuery): TradingPage<TradingAccountResult> =
        listAccounts(query.page, query.size).let { page ->
            TradingPage(page.items.map(TradingAccountResult::fromDomain), page.page, page.size, page.total)
        }

    @Transactional
    override fun execute(command: DeleteTradingAccountCommand): TradingDeletion =
        deleteAccount(command.id, command.key)

    @Transactional
    override fun execute(command: CreateTradingBookCommand): TradingBookResult =
        TradingBookResult.fromDomain(createBook(command.accountId, command.displayName, command.productFamily,
            command.positionMode, command.settlementAsset, command.reviewTimezone, command.key))

    @Transactional(readOnly = true)
    override fun execute(query: GetTradingBookQuery): TradingBookResult =
        TradingBookResult.fromDomain(getBook(query.id))

    @Transactional(readOnly = true)
    override fun execute(query: ListTradingBooksQuery): TradingPage<TradingBookResult> =
        listBooks(query.accountId, query.page, query.size).let { page ->
            TradingPage(page.items.map(TradingBookResult::fromDomain), page.page, page.size, page.total)
        }

    @Transactional
    override fun execute(command: DeleteTradingBookCommand): TradingDeletion =
        deleteBook(command.id, command.key)

    private fun createAccount(venue: TradingVenue, displayName: String, key: String): TradingAccount {
        validKey(key)
        val owner = members.currentMemberId()
        val fingerprint = fingerprint(venue.name, displayName)
        return when (val claim = idempotency.claim(owner, "createTradingAccount", key, fingerprint)) {
            is IdempotencyClaim.Replayed -> accounts.find(TradingAccountId(claim.resourceId), owner)
                ?.takeIf { it.status == TradingLifecycleStatus.ACTIVE || it.status == TradingLifecycleStatus.ARCHIVED }
                ?: throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
            IdempotencyClaim.New -> TradingAccount.create(ids.accountId(), owner, venue, displayName, clock.instant()).also {
                accounts.insert(it)
                idempotency.complete(owner, "createTradingAccount", key, it.id.value)
            }
        }
    }

    private fun getAccount(id: TradingAccountId): TradingAccount {
        val owner = members.currentMemberId()
        val account = accountReader.find(id, owner) ?: throw missing(accountReader.deleted(id, owner))
        if (account.status == TradingLifecycleStatus.DELETION_PENDING || account.status == TradingLifecycleStatus.DELETION_FAILED)
            throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        return account
    }

    private fun listAccounts(page: Int, size: Int): TradingPage<TradingAccount> {
        validPage(page, size)
        return accountReader.list(members.currentMemberId(), page, size)
    }

    private fun deleteAccount(id: TradingAccountId, key: String): TradingDeletion {
        validKey(key)
        val owner = members.currentMemberId()
        val operation = "deleteTradingAccount"
        val fingerprint = fingerprint(id.value.toString())
        val existing = idempotency.claim(owner, operation, key, fingerprint)
        if (existing is IdempotencyClaim.Replayed) return deletions.find(DeletionRequestId(existing.resourceId), owner)?.let(TradingDeletion::fromDomain)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val account = accounts.find(id, owner, forUpdate = true) ?: throw missing(accounts.deleted(id, owner))
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

    private fun createBook(accountId: TradingAccountId, displayName: String, productFamily: TradingProductFamily,
                        positionMode: TradingPositionMode, settlementAsset: TradingSettlementAsset,
                        reviewTimezone: String, key: String): TradingBook {
        validKey(key)
        val owner = members.currentMemberId()
        val account = accounts.find(accountId, owner, forUpdate = true) ?: throw missing(accounts.deleted(accountId, owner))
        if (account.status != TradingLifecycleStatus.ACTIVE) throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        if (reviewTimezone !in ZoneId.getAvailableZoneIds()) throw TradingFailure("REVIEW_TIMEZONE_INVALID", TradingFailureKind.INPUT_REJECTED)
        val fingerprint = fingerprint(accountId.value.toString(), displayName, productFamily.name,
            positionMode.name, settlementAsset.name, reviewTimezone)
        return when (val claim = idempotency.claim(owner, "createTradingBook", key, fingerprint)) {
            is IdempotencyClaim.Replayed -> books.find(TradingBookId(claim.resourceId), owner)
                ?.takeIf { it.status == TradingLifecycleStatus.ACTIVE }
                ?: throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
            IdempotencyClaim.New -> TradingBook.create(ids.bookId(), account, displayName, productFamily,
                positionMode, settlementAsset, reviewTimezone, clock.instant()).also {
                books.insert(it)
                idempotency.complete(owner, "createTradingBook", key, it.id.value)
            }
        }
    }

    private fun getBook(id: TradingBookId): TradingBook {
        val owner = members.currentMemberId()
        val book = bookReader.find(id, owner) ?: throw missing(bookReader.deleted(id, owner))
        if (book.status != TradingLifecycleStatus.ACTIVE) throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        val account = accountReader.find(book.accountId, owner) ?: throw missing(accountReader.deleted(book.accountId, owner))
        if (account.status != TradingLifecycleStatus.ACTIVE) throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        return book
    }

    private fun listBooks(accountId: TradingAccountId, page: Int, size: Int): TradingPage<TradingBook> {
        validPage(page, size)
        getAccount(accountId)
        return bookReader.list(accountId, members.currentMemberId(), page, size)
    }

    private fun deleteBook(id: TradingBookId, key: String): TradingDeletion {
        validKey(key)
        val owner = members.currentMemberId()
        val operation = "deleteTradingBook"
        val fingerprint = fingerprint(id.value.toString())
        val existing = idempotency.claim(owner, operation, key, fingerprint)
        if (existing is IdempotencyClaim.Replayed) return deletions.find(DeletionRequestId(existing.resourceId), owner)?.let(TradingDeletion::fromDomain)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val initialBook = books.find(id, owner) ?: throw missing(books.deleted(id, owner))
        val account = accounts.find(initialBook.accountId, owner, forUpdate = true) ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        if (account.status != TradingLifecycleStatus.ACTIVE) throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        val book = books.find(id, owner, forUpdate = true) ?: throw missing(books.deleted(id, owner))
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

    private fun missing(deleted: Boolean): TradingFailure =
        if (deleted) TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE) else TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)

    private fun validPage(page: Int, size: Int) {
        if (page < 0 || size !in 1..100) throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
    }

    private fun validKey(key: String) {
        if (key.length !in 1..128 || !KEY.matches(key)) throw TradingFailure("IDEMPOTENCY_KEY_INVALID", TradingFailureKind.INVALID_REQUEST)
    }

    private fun fingerprint(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { value ->
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            digest.update(0)
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object { val KEY = Regex("[A-Za-z0-9._~-]+") }
}
