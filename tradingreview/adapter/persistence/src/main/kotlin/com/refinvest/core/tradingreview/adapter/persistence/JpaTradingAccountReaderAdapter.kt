package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.domain.TradingAccount
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.port.outbound.persistence.TradingPageReadModel
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountReader
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class JpaTradingAccountReaderAdapter(
    private val jpaStore: TradingAccountJpaStore,
) : TradingAccountReader {
    @Transactional(readOnly = true)
    override fun find(id: TradingAccountId, owner: MemberId): TradingAccount? =
        jpaStore.readSnapshot(id.value, owner.value)?.let(TradingAccountPersistenceMapper::toDomain)

    @Transactional(readOnly = true)
    override fun list(owner: MemberId, page: Int, size: Int): TradingPageReadModel<TradingAccount> {
        val result = jpaStore.page(owner.value, PageRequest.of(page, size))
        val items = result.content.map(TradingAccountPersistenceMapper::toDomain)
        return TradingPageReadModel(items, page, size, result.totalElements)
    }

    override fun deleted(id: TradingAccountId, owner: MemberId): Boolean = jpaStore.deleted(id.value, owner.value)
}
