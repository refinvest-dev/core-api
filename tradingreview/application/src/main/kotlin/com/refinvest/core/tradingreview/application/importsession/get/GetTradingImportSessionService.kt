package com.refinvest.core.tradingreview.application.importsession.get

import com.refinvest.core.tradingreview.application.importsession.LoadedImportSession
import com.refinvest.core.tradingreview.application.support.missingResource
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.importsession.TradingImportSessionView
import com.refinvest.core.tradingreview.port.inbound.importsession.get.GetTradingImportSessionQuery
import com.refinvest.core.tradingreview.port.inbound.importsession.get.GetTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountStore
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingImportSessionReader
import com.refinvest.core.tradingreview.port.outbound.persistence.ledger.TradingLedgerPublicationReader
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class GetTradingImportSessionService(
    private val members: TradingMemberProvider,
    private val accounts: TradingAccountStore,
    private val books: TradingBookStore,
    private val clock: Clock,
    private val revisions: TradingLedgerPublicationReader,
    private val reader: TradingImportSessionReader,
) : GetTradingImportSessionUseCase {
    @Transactional(readOnly = true)
    override fun execute(query: GetTradingImportSessionQuery): TradingImportSessionView =
        getSession(query.id).toResult(clock.instant())

    private fun getSession(id: TradingImportSessionId): LoadedImportSession {
        val owner = members.currentMemberId()
        val session = reader.find(id, owner) ?: throw missingResource(reader.deleted(id, owner))
        if (session.deletionPending) throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        val book = books.find(session.bookId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val account = accounts.find(book.accountId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        if (book.status != TradingLifecycleStatus.ACTIVE || account.status != TradingLifecycleStatus.ACTIVE)
            throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        return LoadedImportSession(session, session.artifacts,
            if (session.status == TradingImportStatus.ACCEPTED)
                revisions.publishedForSession(id, owner) else null)
    }
}
