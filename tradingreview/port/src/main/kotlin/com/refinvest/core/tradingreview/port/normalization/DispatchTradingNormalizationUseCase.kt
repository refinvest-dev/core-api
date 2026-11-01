package com.refinvest.core.tradingreview.port.normalization

/** Claims and submits at most one eligible dispatch. False means no work was claimed. */
fun interface DispatchTradingNormalizationUseCase {
    fun dispatchOne(): Boolean
}
