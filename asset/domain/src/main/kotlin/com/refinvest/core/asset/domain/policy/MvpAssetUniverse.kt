package com.refinvest.core.asset.domain.policy

object MvpAssetUniverse {
    private val symbols = setOf("QQQ", "SPY", "TQQQ", "SOXL", "BTCUSDT")

    fun contains(symbol: String): Boolean = symbol in symbols
}
