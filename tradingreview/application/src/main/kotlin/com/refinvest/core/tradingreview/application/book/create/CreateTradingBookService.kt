package com.refinvest.core.tradingreview.application.book.create

import com.refinvest.core.tradingreview.application.support.missingResource
import com.refinvest.core.tradingreview.application.support.requestFingerprint
import com.refinvest.core.tradingreview.application.support.validateIdempotencyKey
import com.refinvest.core.tradingreview.domain.TradingBook
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingProductFamily
import com.refinvest.core.tradingreview.domain.valueobject.TradingSettlementAsset
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.book.TradingBookResult
import com.refinvest.core.tradingreview.port.inbound.book.create.CreateTradingBookCommand
import com.refinvest.core.tradingreview.port.inbound.book.create.CreateTradingBookUseCase
import com.refinvest.core.tradingreview.port.outbound.idempotency.IdempotencyClaim
import com.refinvest.core.tradingreview.port.outbound.idempotency.TradingIdempotencyStore
import com.refinvest.core.tradingreview.port.outbound.identity.TradingIdGenerator
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountStore
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookStore
import java.time.Clock
import java.time.ZoneId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class CreateTradingBookService(
    private val members: TradingMemberProvider,
    private val ids: TradingIdGenerator,
    private val accounts: TradingAccountStore,
    private val books: TradingBookStore,
    private val idempotency: TradingIdempotencyStore,
    private val clock: Clock,
) : CreateTradingBookUseCase {
    @Transactional
    override fun execute(command: CreateTradingBookCommand): TradingBookResult =
        TradingBookResult.fromDomain(createBook(command.accountId, command.displayName, command.productFamily,
            command.positionMode, command.settlementAsset, command.reviewTimezone, command.key))

    private fun createBook(accountId: TradingAccountId, displayName: String, productFamily: TradingProductFamily,
                        positionMode: TradingPositionMode, settlementAsset: TradingSettlementAsset,
                        reviewTimezone: String, key: String): TradingBook {
        validateIdempotencyKey(key)
        val owner = members.currentMemberId()
        val account = accounts.find(accountId, owner, forUpdate = true) ?: throw missingResource(accounts.deleted(accountId, owner))
        if (account.status != TradingLifecycleStatus.ACTIVE) throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        if (reviewTimezone !in ZoneId.getAvailableZoneIds()) throw TradingFailure("REVIEW_TIMEZONE_INVALID", TradingFailureKind.INPUT_REJECTED)
        val fingerprint = requestFingerprint(accountId.value.toString(), displayName, productFamily.name,
            positionMode.name, settlementAsset.name, reviewTimezone)
        return when (val claim = idempotency.claim(owner, "createTradingBook", key, fingerprint)) {
            is IdempotencyClaim.Replayed -> books.find(TradingBookId(claim.resourceId), owner)
                ?.takeIf { it.status == TradingLifecycleStatus.ACTIVE }
                ?: throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
            IdempotencyClaim.New -> TradingBook.create(ids.bookId(), account, displayName, productFamily,
                positionMode, settlementAsset, reviewTimezone, clock.instant()).also {
                books.insert(it)
                idempotency.complete(owner, "createTradingBook", key, it.id.value)
            }
        }
    }
}
