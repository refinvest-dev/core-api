package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.port.*
import com.refinvest.core.tradingreview.port.deletion.DeletionRequestStore
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.account.*
import com.refinvest.core.tradingreview.port.book.*
import org.springframework.jdbc.core.JdbcTemplate

/** Test fixture composition only; production adapters each implement one aggregate or capability. */
internal class TradingReviewTestStores(
    val accountAdapter: JdbcTradingAccountPersistenceAdapter,
    val bookAdapter: JdbcTradingBookPersistenceAdapter,
    val deletionAdapter: JdbcDeletionRequestPersistenceAdapter,
    val idempotencyAdapter: JdbcTradingIdempotencyStoreAdapter,
    val bookDeletionCascade: JdbcTradingBookDeletionCascade,
) : TradingAccountStore by accountAdapter, TradingBookStore by bookAdapter,
    DeletionRequestStore by deletionAdapter, TradingIdempotencyStore by idempotencyAdapter {
    constructor(jdbc: JdbcTemplate) : this(JdbcTradingAccountPersistenceAdapter(jdbc),
        JdbcTradingBookPersistenceAdapter(jdbc), JdbcDeletionRequestPersistenceAdapter(jdbc),
        JdbcTradingIdempotencyStoreAdapter(jdbc), JdbcTradingBookDeletionCascade(jdbc))
    fun insert(value: TradingDeletion) {
        deletionAdapter.insert(DeletionRequestPersistenceMapper.toDomain(DeletionRequestRecord(
            value.id, value.owner.value, value.targetType, value.targetId, value.requestedScope, value.state,
            value.deletionGeneration, value.accountCount, value.bookCount, value.importSessionCount, value.artifactCount,
            value.revokedAt, value.requestedAt, value.updatedAt, value.backupPurgeDueAt, value.completedAt, value.failureCode, 0)))
    }
    fun find(id: Long, owner: MemberId): TradingDeletion? = deletionAdapter.find(DeletionRequestId(id), owner)?.let(TradingDeletion::fromDomain)
    val accountReader: TradingAccountReader get() = accountAdapter
    val bookReader: TradingBookReader get() = bookAdapter
    fun list(owner: MemberId, page: Int, size: Int) = accountReader.list(owner, page, size)
    fun list(accountId: TradingAccountId, owner: MemberId, page: Int, size: Int) = bookReader.list(accountId, owner, page, size)
}
