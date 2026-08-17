package com.refinvest.core.strategy.domain.valueobject

data class MetricReference(
    val asset: AssetSymbol,
    val metric: MetricType,
    val window: Int? = null,
) {
    init {
        when (metric) {
            MetricType.SIMPLE -> require(window == null) { "SIMPLE metric must not have a window" }
            MetricType.RETURN, MetricType.CHANGE -> require(window != null && window > 0) {
                "$metric metric requires a positive window"
            }
        }
    }
}
