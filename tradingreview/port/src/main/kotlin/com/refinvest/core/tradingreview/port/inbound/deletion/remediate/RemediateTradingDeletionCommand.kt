package com.refinvest.core.tradingreview.port.inbound.deletion.remediate

import com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestId

data class RemediateTradingDeletionCommand(val failedId: DeletionRequestId)
