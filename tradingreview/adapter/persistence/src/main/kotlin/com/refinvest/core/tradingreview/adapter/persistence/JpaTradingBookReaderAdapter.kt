package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.TradingBook
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.port.outbound.persistence.TradingPageReadModel
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookReader
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class JpaTradingBookReaderAdapter(
    private val jpaStore: TradingBookJpaStore,
) : TradingBookReader {
    @Transactional(readOnly = true)
    override fun find(id: TradingBookId, owner: MemberId): TradingBook? =
        jpaStore.readSnapshot(id.value, owner.value)?.let(TradingBookPersistenceMapper::toDomain)

    @Transactional(readOnly = true)
    override fun list(accountId: TradingAccountId, owner: MemberId, page: Int, size: Int): TradingPageReadModel<TradingBook> {
        val result = jpaStore.page(accountId.value, owner.value, PageRequest.of(page, size))
        val items = result.content.map(TradingBookPersistenceMapper::toDomain)
        return TradingPageReadModel(items, page, size, result.totalElements)
    }

    override fun deleted(id: TradingBookId, owner: MemberId): Boolean = jpaStore.deleted(id.value, owner.value)
}
