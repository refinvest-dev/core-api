package com.refinvest.core.tradingreview.port.normalization

/** Polls at most one submitted dispatch, preserving its lease and pinned input identity. */
fun interface PollTradingNormalizationUseCase {
    fun pollOne(): Boolean
}
