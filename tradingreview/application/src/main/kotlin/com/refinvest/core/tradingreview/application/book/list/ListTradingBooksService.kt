package com.refinvest.core.tradingreview.application.book.list

import com.refinvest.core.tradingreview.application.support.missingResource
import com.refinvest.core.tradingreview.application.support.validatePage
import com.refinvest.core.tradingreview.domain.TradingBook
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.book.TradingBookResult
import com.refinvest.core.tradingreview.port.inbound.book.list.ListTradingBooksQuery
import com.refinvest.core.tradingreview.port.inbound.book.list.ListTradingBooksUseCase
import com.refinvest.core.tradingreview.port.inbound.pagination.TradingPage
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.persistence.TradingPageReadModel
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountReader
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookReader
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class ListTradingBooksService(
    private val members: TradingMemberProvider,
    private val accountReader: TradingAccountReader,
    private val bookReader: TradingBookReader,
) : ListTradingBooksUseCase {
    @Transactional(readOnly = true)
    override fun execute(query: ListTradingBooksQuery): TradingPage<TradingBookResult> =
        listBooks(query.accountId, query.page, query.size).let { page ->
            TradingPage(page.items.map(TradingBookResult::fromDomain), page.page, page.size, page.total)
        }

    private fun listBooks(accountId: TradingAccountId, page: Int, size: Int): TradingPageReadModel<TradingBook> {
        validatePage(page, size)
        val owner = members.currentMemberId()
        val account = accountReader.find(accountId, owner) ?: throw missingResource(accountReader.deleted(accountId, owner))
        if (account.status == TradingLifecycleStatus.DELETION_PENDING || account.status == TradingLifecycleStatus.DELETION_FAILED)
            throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        return bookReader.list(accountId, members.currentMemberId(), page, size)
    }
}
