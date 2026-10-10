package com.refinvest.core.tradingreview.port

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import java.time.Instant

interface TradingMemberProvider { fun currentMemberId(): MemberId }
interface TradingIdGenerator {
    fun accountId(): TradingAccountId
    fun bookId(): TradingBookId
    fun deletionId(): Long
}

data class TradingPage<T>(val items: List<T>, val page: Int, val size: Int, val total: Long)

interface TradingAccountStore {
    fun insert(account: TradingAccount)
    fun find(id: TradingAccountId, owner: MemberId, forUpdate: Boolean = false): TradingAccount?
    fun list(owner: MemberId, page: Int, size: Int): TradingPage<TradingAccount>
    fun update(account: TradingAccount): Boolean
    fun deleted(id: TradingAccountId, owner: MemberId): Boolean
}

interface TradingBookStore {
    fun insert(book: TradingBook)
    fun find(id: TradingBookId, owner: MemberId, forUpdate: Boolean = false): TradingBook?
    fun list(accountId: TradingAccountId, owner: MemberId, page: Int, size: Int): TradingPage<TradingBook>
    fun update(book: TradingBook): Boolean
    fun markAccountBooksDeleting(accountId: TradingAccountId, now: Instant)
    fun deleted(id: TradingBookId, owner: MemberId): Boolean
}

data class TradingDeletion(
    val id: Long, val owner: MemberId, val targetType: String, val targetId: Long,
    val requestedScope: String, val state: String, val deletionGeneration: Long,
    val accountCount: Long, val bookCount: Long,
    val revokedAt: Instant, val requestedAt: Instant, val updatedAt: Instant,
    val backupPurgeDueAt: Instant,
    val completedAt: Instant? = null,
    val failureCode: String? = null,
)

interface TradingDeletionStore {
    fun insert(deletion: TradingDeletion)
    fun find(id: Long, owner: MemberId): TradingDeletion?
}

sealed interface IdempotencyClaim {
    data object New : IdempotencyClaim
    data class Replayed(val resourceId: Long) : IdempotencyClaim
}

interface TradingIdempotencyStore {
    fun claim(owner: MemberId, operation: String, key: String, fingerprint: String): IdempotencyClaim
    fun complete(owner: MemberId, operation: String, key: String, resourceId: Long)
}

class TradingFailure(val code: String, val httpStatus: Int) : RuntimeException(code)

interface TradingAccountUseCase {
    fun create(venue: TradingVenue, displayName: String, key: String): TradingAccount
    fun get(id: TradingAccountId): TradingAccount
    fun list(page: Int, size: Int): TradingPage<TradingAccount>
    fun delete(id: TradingAccountId, key: String): TradingDeletion
}

interface TradingBookUseCase {
    fun create(accountId: TradingAccountId, displayName: String, productFamily: TradingProductFamily,
               positionMode: TradingPositionMode, settlementAsset: TradingSettlementAsset,
               reviewTimezone: String, key: String): TradingBook
    fun get(id: TradingBookId): TradingBook
    fun list(accountId: TradingAccountId, page: Int, size: Int): TradingPage<TradingBook>
    fun delete(id: TradingBookId, key: String): TradingDeletion
}

interface GetTradingDeletionRequestUseCase { fun get(id: Long): TradingDeletion }
