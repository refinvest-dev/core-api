package com.refinvest.core.tradingreview.application.account.get

import com.refinvest.core.tradingreview.application.support.missingResource
import com.refinvest.core.tradingreview.domain.TradingAccount
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.account.TradingAccountResult
import com.refinvest.core.tradingreview.port.inbound.account.get.GetTradingAccountQuery
import com.refinvest.core.tradingreview.port.inbound.account.get.GetTradingAccountUseCase
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountReader
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class GetTradingAccountService(
    private val members: TradingMemberProvider,
    private val accountReader: TradingAccountReader,
) : GetTradingAccountUseCase {
    @Transactional(readOnly = true)
    override fun execute(query: GetTradingAccountQuery): TradingAccountResult =
        TradingAccountResult.fromDomain(getAccount(query.id))

    private fun getAccount(id: TradingAccountId): TradingAccount {
        val owner = members.currentMemberId()
        val account = accountReader.find(id, owner) ?: throw missingResource(accountReader.deleted(id, owner))
        if (account.status == TradingLifecycleStatus.DELETION_PENDING || account.status == TradingLifecycleStatus.DELETION_FAILED)
            throw TradingFailure("RESOURCE_DELETED", TradingFailureKind.NO_LONGER_AVAILABLE)
        return account
    }
}
