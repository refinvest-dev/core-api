package com.refinvest.core.backtest.port.inbound.dispatch

/** Claims and dispatches at most one pending Compute request. */
fun interface DispatchPendingBacktestsUseCase {
    fun execute(): Boolean
}
