package com.refinvest.core.backtest.port.inbound.dispatch

fun interface PollSubmittedBacktestsUseCase {
    /** Polls at most one accepted Compute job and returns whether a job was claimed. */
    fun execute(): Boolean
}
