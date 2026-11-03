package com.refinvest.core.tradingreview.adapter.web.deletion

import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId
import com.refinvest.core.tradingreview.port.inbound.deletion.get.GetTradingDeletionRequestQuery
import com.refinvest.core.tradingreview.port.inbound.deletion.get.GetTradingDeletionRequestUseCase
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/trading-deletion-requests")
class TradingDeletionController(private val getDeletion: GetTradingDeletionRequestUseCase) {
    @GetMapping("/{deletionRequestId}")
    fun get(@PathVariable deletionRequestId: Long) = TradingDeletionResponse.from(getDeletion.execute(GetTradingDeletionRequestQuery(DeletionRequestId(deletionRequestId))))
}
