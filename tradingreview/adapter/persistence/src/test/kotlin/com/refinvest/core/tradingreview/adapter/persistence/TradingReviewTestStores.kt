package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.port.*
import com.refinvest.core.tradingreview.port.account.*
import com.refinvest.core.tradingreview.port.book.*
import org.springframework.jdbc.core.JdbcTemplate

/** Test fixture composition only; production adapters each implement one aggregate or capability. */
internal class TradingReviewTestStores(
    val accountAdapter: JdbcTradingAccountPersistenceAdapter,
    val bookAdapter: JdbcTradingBookPersistenceAdapter,
    val deletionAdapter: JdbcTradingDeletionStoreAdapter,
    val idempotencyAdapter: JdbcTradingIdempotencyStoreAdapter,
    val bookDeletionCascade: JdbcTradingBookDeletionCascade,
) : TradingAccountStore by accountAdapter, TradingBookStore by bookAdapter,
    TradingDeletionStore by deletionAdapter, TradingIdempotencyStore by idempotencyAdapter {
    constructor(jdbc: JdbcTemplate) : this(JdbcTradingAccountPersistenceAdapter(jdbc),
        JdbcTradingBookPersistenceAdapter(jdbc), JdbcTradingDeletionStoreAdapter(jdbc),
        JdbcTradingIdempotencyStoreAdapter(jdbc), JdbcTradingBookDeletionCascade(jdbc))
    val accountReader: TradingAccountReader get() = accountAdapter
    val bookReader: TradingBookReader get() = bookAdapter
    fun list(owner: MemberId, page: Int, size: Int) = accountReader.list(owner, page, size)
    fun list(accountId: TradingAccountId, owner: MemberId, page: Int, size: Int) = bookReader.list(accountId, owner, page, size)
}
