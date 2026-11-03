package com.refinvest.core.tradingreview.application.account.list

import com.refinvest.core.tradingreview.application.support.validatePage
import com.refinvest.core.tradingreview.domain.TradingAccount
import com.refinvest.core.tradingreview.port.inbound.account.TradingAccountResult
import com.refinvest.core.tradingreview.port.inbound.account.list.ListTradingAccountsQuery
import com.refinvest.core.tradingreview.port.inbound.account.list.ListTradingAccountsUseCase
import com.refinvest.core.tradingreview.port.inbound.pagination.TradingPage
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.persistence.TradingPageReadModel
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountReader
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class ListTradingAccountsService(
    private val members: TradingMemberProvider,
    private val accountReader: TradingAccountReader,
) : ListTradingAccountsUseCase {
    @Transactional(readOnly = true)
    override fun execute(query: ListTradingAccountsQuery): TradingPage<TradingAccountResult> =
        listAccounts(query.page, query.size).let { page ->
            TradingPage(page.items.map(TradingAccountResult::fromDomain), page.page, page.size, page.total)
        }

    private fun listAccounts(page: Int, size: Int): TradingPageReadModel<TradingAccount> {
        validatePage(page, size)
        return accountReader.list(members.currentMemberId(), page, size)
    }
}
