package com.refinvest.core.tradingreview.port.inbound.normalization.receive

fun interface ReceiveTradingNormalizationResultUseCase {
    fun processOne(): Boolean
}
