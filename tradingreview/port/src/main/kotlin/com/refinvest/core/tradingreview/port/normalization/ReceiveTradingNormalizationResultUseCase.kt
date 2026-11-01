package com.refinvest.core.tradingreview.port.normalization

/** Verifies and publishes at most one result; publication durably schedules its ACK. */
fun interface ReceiveTradingNormalizationResultUseCase {
    fun processOne(): Boolean
}
