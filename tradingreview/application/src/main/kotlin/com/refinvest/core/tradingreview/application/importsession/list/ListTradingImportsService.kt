package com.refinvest.core.tradingreview.application.importsession.list

import com.refinvest.core.tradingreview.application.importsession.LoadedImportSession
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.importsession.TradingImportSessionView
import com.refinvest.core.tradingreview.port.inbound.importsession.list.ListTradingImportsQuery
import com.refinvest.core.tradingreview.port.inbound.importsession.list.ListTradingImportsUseCase
import com.refinvest.core.tradingreview.port.inbound.pagination.TradingPage
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.persistence.TradingPageReadModel
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountStore
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingImportSessionReader
import com.refinvest.core.tradingreview.port.outbound.persistence.ledger.TradingLedgerPublicationReader
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class ListTradingImportsService(
    private val members: TradingMemberProvider,
    private val accounts: TradingAccountStore,
    private val books: TradingBookStore,
    private val clock: Clock,
    private val revisions: TradingLedgerPublicationReader,
    private val reader: TradingImportSessionReader,
) : ListTradingImportsUseCase {
    @Transactional(readOnly = true)
    override fun execute(query: ListTradingImportsQuery): TradingPage<TradingImportSessionView> =
        listSessions(query.bookId, query.page, query.size).let { page ->
            val now = clock.instant()
            TradingPage(page.items.map {
                it.toResult(now)
            }, page.page, page.size, page.total)
        }

    private fun listSessions(bookId: TradingBookId, page: Int, size: Int): TradingPageReadModel<LoadedImportSession> {
        if (page < 0 || size !in 1..100) throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
        val owner = members.currentMemberId()
        val book = books.find(bookId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val account = accounts.find(book.accountId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        if (book.status != TradingLifecycleStatus.ACTIVE || account.status != TradingLifecycleStatus.ACTIVE)
            throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        val result = reader.list(bookId, owner, page, size)
        return TradingPageReadModel(result.items.map { session -> LoadedImportSession(session,
            session.artifacts, if (session.status == TradingImportStatus.ACCEPTED)
                revisions.publishedForSession(session.id, owner) else null) }, page, size, result.total)
    }
}
