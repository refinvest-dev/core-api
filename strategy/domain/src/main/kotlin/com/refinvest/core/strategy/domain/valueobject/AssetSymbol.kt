package com.refinvest.core.strategy.domain.valueobject

enum class AssetSymbol {
    QQQ,
    SPY,
    TQQQ,
    SOXL,
    BTCUSDT;

    companion object {
        fun from(value: String): AssetSymbol =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException("Unsupported MVP asset: $value")
    }
}
