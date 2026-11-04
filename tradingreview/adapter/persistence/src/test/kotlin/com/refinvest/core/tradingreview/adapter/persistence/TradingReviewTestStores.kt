package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.port.inbound.deletion.TradingDeletion
import com.refinvest.core.tradingreview.port.outbound.idempotency.TradingIdempotencyStore
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountReader
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountStore
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookReader
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookStore
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionRequestStore

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import org.springframework.jdbc.core.JdbcTemplate

/** Test fixture composition only; production adapters each implement one aggregate or capability. */
internal class TradingReviewTestStores(
    val accountAdapter: TradingAccountStore,
    val bookAdapter: TradingBookStore,
    val deletionAdapter: JdbcDeletionRequestPersistenceAdapter,
    val idempotencyAdapter: JdbcTradingIdempotencyStoreAdapter,
    val bookDeletionCascade: JdbcTradingBookDeletionCascade,
    val accountReader: TradingAccountReader,
    val bookReader: TradingBookReader,
) : TradingAccountStore by accountAdapter, TradingBookStore by bookAdapter,
    DeletionRequestStore by deletionAdapter, TradingIdempotencyStore by idempotencyAdapter {
    constructor(jdbc: JdbcTemplate) : this(
        TradingJpaTestContexts.context(requireNotNull(jdbc.dataSource)).getBean(TradingAccountStore::class.java),
        TradingJpaTestContexts.context(requireNotNull(jdbc.dataSource)).getBean(TradingBookStore::class.java), JdbcDeletionRequestPersistenceAdapter(jdbc),
        JdbcTradingIdempotencyStoreAdapter(jdbc), JdbcTradingBookDeletionCascade(jdbc),
        TradingJpaTestContexts.context(requireNotNull(jdbc.dataSource)).getBean(TradingAccountReader::class.java),
        TradingJpaTestContexts.context(requireNotNull(jdbc.dataSource)).getBean(TradingBookReader::class.java))
    fun insert(value: TradingDeletion) {
        deletionAdapter.insert(DeletionRequestPersistenceMapper.toDomain(DeletionRequestRecord(
            value.id, value.owner.value, value.targetType, value.targetId, value.requestedScope, value.state,
            value.deletionGeneration, value.accountCount, value.bookCount, value.importSessionCount, value.artifactCount,
            value.revokedAt, value.requestedAt, value.updatedAt, value.backupPurgeDueAt, value.completedAt, value.failureCode, 0)))
    }
    fun find(id: Long, owner: MemberId): TradingDeletion? = deletionAdapter.find(DeletionRequestId(id), owner)?.let(TradingDeletion::fromDomain)
    fun list(owner: MemberId, page: Int, size: Int) = accountReader.list(owner, page, size)
    fun list(accountId: TradingAccountId, owner: MemberId, page: Int, size: Int) = bookReader.list(accountId, owner, page, size)
}
