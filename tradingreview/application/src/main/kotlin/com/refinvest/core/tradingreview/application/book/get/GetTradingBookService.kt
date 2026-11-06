package com.refinvest.core.tradingreview.application.book.get

import com.refinvest.core.tradingreview.application.support.missingResource
import com.refinvest.core.tradingreview.domain.TradingBook
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.book.TradingBookResult
import com.refinvest.core.tradingreview.port.inbound.book.get.GetTradingBookQuery
import com.refinvest.core.tradingreview.port.inbound.book.get.GetTradingBookUseCase
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountReader
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookReader
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class GetTradingBookService(
    private val members: TradingMemberProvider,
    private val accountReader: TradingAccountReader,
    private val bookReader: TradingBookReader,
) : GetTradingBookUseCase {
    @Transactional(readOnly = true)
    override fun execute(query: GetTradingBookQuery): TradingBookResult =
        TradingBookResult.fromDomain(getBook(query.id))

    private fun getBook(id: TradingBookId): TradingBook {
        val owner = members.currentMemberId()
        val book = bookReader.find(id, owner) ?: throw missingResource(bookReader.deleted(id, owner))
        if (book.status != TradingLifecycleStatus.ACTIVE) throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        val account = accountReader.find(book.accountId, owner) ?: throw missingResource(accountReader.deleted(book.accountId, owner))
        if (account.status != TradingLifecycleStatus.ACTIVE) throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        return book
    }
}
