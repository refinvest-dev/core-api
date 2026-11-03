package com.refinvest.core.tradingreview.port.inbound.normalization.acknowledge

fun interface AcknowledgeTradingNormalizationResultUseCase {
    fun acknowledgeOne(): Boolean
}
