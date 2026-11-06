package com.refinvest.core.tradingreview.application.account.create

import com.refinvest.core.tradingreview.application.support.requestFingerprint
import com.refinvest.core.tradingreview.application.support.validateIdempotencyKey
import com.refinvest.core.tradingreview.domain.TradingAccount
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingVenue
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.account.TradingAccountResult
import com.refinvest.core.tradingreview.port.inbound.account.create.CreateTradingAccountCommand
import com.refinvest.core.tradingreview.port.inbound.account.create.CreateTradingAccountUseCase
import com.refinvest.core.tradingreview.port.outbound.idempotency.IdempotencyClaim
import com.refinvest.core.tradingreview.port.outbound.idempotency.TradingIdempotencyStore
import com.refinvest.core.tradingreview.port.outbound.identity.TradingIdGenerator
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountStore
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class CreateTradingAccountService(
    private val members: TradingMemberProvider,
    private val ids: TradingIdGenerator,
    private val accounts: TradingAccountStore,
    private val idempotency: TradingIdempotencyStore,
    private val clock: Clock,
) : CreateTradingAccountUseCase {
    @Transactional
    override fun execute(command: CreateTradingAccountCommand): TradingAccountResult =
        TradingAccountResult.fromDomain(createAccount(command.venue, command.displayName, command.key))

    private fun createAccount(venue: TradingVenue, displayName: String, key: String): TradingAccount {
        validateIdempotencyKey(key)
        val owner = members.currentMemberId()
        val fingerprint = requestFingerprint(venue.name, displayName)
        return when (val claim = idempotency.claim(owner, "createTradingAccount", key, fingerprint)) {
            is IdempotencyClaim.Replayed -> accounts.find(TradingAccountId(claim.resourceId), owner)
                ?.takeIf { it.status == TradingLifecycleStatus.ACTIVE || it.status == TradingLifecycleStatus.ARCHIVED }
                ?: throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
            IdempotencyClaim.New -> TradingAccount.create(ids.accountId(), owner, venue, displayName, clock.instant()).also {
                accounts.insert(it)
                idempotency.complete(owner, "createTradingAccount", key, it.id.value)
            }
        }
    }
}
