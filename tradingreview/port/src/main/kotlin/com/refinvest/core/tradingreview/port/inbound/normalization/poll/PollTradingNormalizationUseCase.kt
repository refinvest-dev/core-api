package com.refinvest.core.tradingreview.port.inbound.normalization.poll

fun interface PollTradingNormalizationUseCase {
    fun pollOne(): Boolean
}
