package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import com.refinvest.core.tradingreview.port.TradingFailure
import com.refinvest.core.tradingreview.port.TradingFailureKind
import com.refinvest.core.tradingreview.port.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.deletion.DeletionRequestReader
import com.refinvest.core.tradingreview.port.deletion.GetTradingDeletionRequestUseCase
import com.refinvest.core.tradingreview.port.deletion.TradingDeletion
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class GetTradingDeletionRequestService(private val members: TradingMemberProvider, private val reader: DeletionRequestReader) :
    GetTradingDeletionRequestUseCase {
    @Transactional(readOnly = true)
    override fun get(id: Long): TradingDeletion = reader.find(DeletionRequestId(id), members.currentMemberId())
        ?: throw TradingFailure("RESOURCE_NOT_FOUND", TradingFailureKind.NOT_FOUND)
}
