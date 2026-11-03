package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.deletion.TradingDeletion
import com.refinvest.core.tradingreview.port.inbound.deletion.get.GetTradingDeletionRequestQuery
import com.refinvest.core.tradingreview.port.inbound.deletion.get.GetTradingDeletionRequestUseCase
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionRequestReader

import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class GetTradingDeletionRequestService(private val members: TradingMemberProvider, private val reader: DeletionRequestReader) :
    GetTradingDeletionRequestUseCase {
    @Transactional(readOnly = true)
    override fun execute(query: GetTradingDeletionRequestQuery): TradingDeletion = reader.find(query.id, members.currentMemberId())?.let(TradingDeletion::fromDomain)
        ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
}
