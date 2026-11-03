package com.refinvest.core.tradingreview.port.inbound.normalization.dispatch

fun interface DispatchTradingNormalizationUseCase {
    fun dispatchOne(): Boolean
}
