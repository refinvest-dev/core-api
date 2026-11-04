package com.refinvest.core.tradingreview.application.normalization

import com.refinvest.core.tradingreview.application.support.validateIdempotencyKey
import com.refinvest.core.tradingreview.domain.TradingBook
import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountStore
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingImportSessionStore
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.TradingNormalizationTransitionGate
import org.springframework.stereotype.Component

/** The caller owns the transaction; both entry points acquire Account -> Book -> Session locks. */
@Component
class NormalizationAdmissionGuard(
    private val members: TradingMemberProvider,
    private val accounts: TradingAccountStore,
    private val books: TradingBookStore,
    private val sessions: TradingImportSessionStore,
    private val transition: TradingNormalizationTransitionGate,
) {
    fun lock(id: TradingImportSessionId, key: String): NormalizationAdmissionContext {
        validateIdempotencyKey(key)
        transition.requireAdmission()
        val owner = members.currentMemberId()
        val initial = sessions.find(id, owner) ?: throw TradingFailure(
            if (sessions.deleted(id, owner)) "RESOURCE_DELETED" else "RESOURCE_NOT_FOUND",
            if (sessions.deleted(id, owner)) TradingFailureKind.NO_LONGER_AVAILABLE else TradingFailureKind.NOT_FOUND)
        val initialBook = books.find(initial.bookId, owner) ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val account = accounts.find(initialBook.accountId, owner, forUpdate = true)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val book = books.find(initialBook.id, owner, forUpdate = true)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        val session = sessions.find(id, owner, forUpdate = true)
            ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        if (account.status != TradingLifecycleStatus.ACTIVE || book.status != TradingLifecycleStatus.ACTIVE ||
            session.deletionPending) throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        if (session.bookId != book.id || session.owner != book.owner || session.owner != owner)
            throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
        if (session.declaredPositionMode != book.positionMode ||
            session.sourceTimezone.isBlank() || session.reviewTimezone.isBlank())
            throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INPUT_REJECTED)
        return NormalizationAdmissionContext(book, session)
    }
}

data class NormalizationAdmissionContext(val book: TradingBook, val session: TradingImportSession)
