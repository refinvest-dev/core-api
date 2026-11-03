package com.refinvest.core.tradingreview.port.inbound.deletion.get

import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId

data class GetTradingDeletionRequestQuery(val id: DeletionRequestId)
