package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.port.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneId

@Service
open class TradingReviewService(
    private val members: TradingMemberProvider,
    private val ids: TradingIdGenerator,
    private val accounts: TradingAccountStore,
    private val books: TradingBookStore,
    private val deletions: TradingDeletionStore,
    private val idempotency: TradingIdempotencyStore,
    private val clock: Clock,
) : TradingAccountUseCase, TradingBookUseCase, GetTradingDeletionRequestUseCase {
    @Transactional
    override fun create(venue: TradingVenue, displayName: String, key: String): TradingAccount {
        validKey(key)
        val owner = members.currentMemberId()
        val fingerprint = fingerprint(venue.name, displayName)
        return when (val claim = idempotency.claim(owner, "createTradingAccount", key, fingerprint)) {
            is IdempotencyClaim.Replayed -> accounts.find(TradingAccountId(claim.resourceId), owner)
                ?: throw TradingFailure("RESOURCE_DELETED", 410)
            IdempotencyClaim.New -> TradingAccount.create(ids.accountId(), owner, venue, displayName, clock.instant()).also {
                accounts.insert(it)
                idempotency.complete(owner, "createTradingAccount", key, it.id.value)
            }
        }
    }

    @Transactional(readOnly = true)
    override fun get(id: TradingAccountId): TradingAccount {
        val owner = members.currentMemberId()
        val account = accounts.find(id, owner) ?: throw missing(accounts.deleted(id, owner))
        if (account.status == TradingLifecycleStatus.DELETION_PENDING || account.status == TradingLifecycleStatus.DELETION_FAILED)
            throw TradingFailure("RESOURCE_DELETED", 410)
        return account
    }

    @Transactional(readOnly = true)
    override fun list(page: Int, size: Int): TradingPage<TradingAccount> {
        validPage(page, size)
        return accounts.list(members.currentMemberId(), page, size)
    }

    @Transactional
    override fun delete(id: TradingAccountId, key: String): TradingDeletion {
        validKey(key)
        val owner = members.currentMemberId()
        val operation = "deleteTradingAccount"
        val fingerprint = fingerprint(id.value.toString())
        val existing = idempotency.claim(owner, operation, key, fingerprint)
        if (existing is IdempotencyClaim.Replayed) return deletions.find(existing.resourceId, owner)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        val account = accounts.find(id, owner, forUpdate = true) ?: throw missing(accounts.deleted(id, owner))
        if (account.status != TradingLifecycleStatus.ACTIVE && account.status != TradingLifecycleStatus.ARCHIVED)
            throw TradingFailure("RESOURCE_DELETED", 410)
        val now = clock.instant()
        if (!accounts.update(account.requestDeletion(now))) throw TradingFailure("CONCURRENT_MODIFICATION", 409)
        val count = books.list(id, owner, 0, 1).total
        books.markAccountBooksDeleting(id, now)
        val deletion = TradingDeletion(ids.deletionId(), owner, "TRADING_ACCOUNT", id.value,
            "ACCOUNT_SUBTREE", "REQUESTED", account.deletionGeneration + 1, 1, count, now, now, now,
            now.plusSeconds(30L * 24 * 60 * 60))
        deletions.insert(deletion)
        idempotency.complete(owner, operation, key, deletion.id)
        return deletion
    }

    @Transactional
    override fun create(accountId: TradingAccountId, displayName: String, productFamily: TradingProductFamily,
                        positionMode: TradingPositionMode, settlementAsset: TradingSettlementAsset,
                        reviewTimezone: String, key: String): TradingBook {
        validKey(key)
        val owner = members.currentMemberId()
        val account = accounts.find(accountId, owner, forUpdate = true) ?: throw missing(accounts.deleted(accountId, owner))
        if (account.status != TradingLifecycleStatus.ACTIVE) throw TradingFailure("RESOURCE_DELETED", 410)
        if (reviewTimezone !in ZoneId.getAvailableZoneIds()) throw TradingFailure("REVIEW_TIMEZONE_INVALID", 422)
        val fingerprint = fingerprint(accountId.value.toString(), displayName, productFamily.name,
            positionMode.name, settlementAsset.name, reviewTimezone)
        return when (val claim = idempotency.claim(owner, "createTradingBook", key, fingerprint)) {
            is IdempotencyClaim.Replayed -> books.find(TradingBookId(claim.resourceId), owner)
                ?: throw TradingFailure("RESOURCE_DELETED", 410)
            IdempotencyClaim.New -> TradingBook.create(ids.bookId(), account, displayName, productFamily,
                positionMode, settlementAsset, reviewTimezone, clock.instant()).also {
                books.insert(it)
                idempotency.complete(owner, "createTradingBook", key, it.id.value)
            }
        }
    }

    @Transactional(readOnly = true)
    override fun get(id: TradingBookId): TradingBook {
        val owner = members.currentMemberId()
        val book = books.find(id, owner) ?: throw missing(books.deleted(id, owner))
        if (book.status != TradingLifecycleStatus.ACTIVE) throw TradingFailure("RESOURCE_DELETED", 410)
        val account = accounts.find(book.accountId, owner) ?: throw missing(accounts.deleted(book.accountId, owner))
        if (account.status != TradingLifecycleStatus.ACTIVE) throw TradingFailure("RESOURCE_DELETED", 410)
        return book
    }

    @Transactional(readOnly = true)
    override fun list(accountId: TradingAccountId, page: Int, size: Int): TradingPage<TradingBook> {
        validPage(page, size)
        get(accountId)
        return books.list(accountId, members.currentMemberId(), page, size)
    }

    @Transactional
    override fun delete(id: TradingBookId, key: String): TradingDeletion {
        validKey(key)
        val owner = members.currentMemberId()
        val operation = "deleteTradingBook"
        val fingerprint = fingerprint(id.value.toString())
        val existing = idempotency.claim(owner, operation, key, fingerprint)
        if (existing is IdempotencyClaim.Replayed) return deletions.find(existing.resourceId, owner)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        val initialBook = books.find(id, owner) ?: throw missing(books.deleted(id, owner))
        val account = accounts.find(initialBook.accountId, owner, forUpdate = true) ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)
        if (account.status != TradingLifecycleStatus.ACTIVE) throw TradingFailure("RESOURCE_DELETED", 410)
        val book = books.find(id, owner, forUpdate = true) ?: throw missing(books.deleted(id, owner))
        if (book.status != TradingLifecycleStatus.ACTIVE) throw TradingFailure("RESOURCE_DELETED", 410)
        val now = clock.instant()
        if (!books.update(book.requestDeletion(now))) throw TradingFailure("CONCURRENT_MODIFICATION", 409)
        val deletion = TradingDeletion(ids.deletionId(), owner, "TRADING_BOOK", id.value,
            "BOOK_SUBTREE", "REQUESTED", book.deletionGeneration + 1, 0, 1, now, now, now,
            now.plusSeconds(30L * 24 * 60 * 60))
        deletions.insert(deletion)
        idempotency.complete(owner, operation, key, deletion.id)
        return deletion
    }

    @Transactional(readOnly = true)
    override fun get(id: Long): TradingDeletion = deletions.find(id, members.currentMemberId())
        ?: throw TradingFailure("RESOURCE_NOT_FOUND", 404)

    private fun missing(deleted: Boolean): TradingFailure =
        if (deleted) TradingFailure("RESOURCE_DELETED", 410) else TradingFailure("RESOURCE_NOT_FOUND", 404)

    private fun validPage(page: Int, size: Int) {
        if (page < 0 || size !in 1..100) throw TradingFailure("REQUEST_INVALID", 400)
    }

    private fun validKey(key: String) {
        if (key.length !in 1..128 || !KEY.matches(key)) throw TradingFailure("IDEMPOTENCY_KEY_INVALID", 400)
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
