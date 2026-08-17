package com.refinvest.core.strategy.adapter.web.strategy.define

import com.refinvest.core.strategy.domain.valueobject.AssetSymbol
import com.refinvest.core.strategy.domain.valueobject.MetricReference
import com.refinvest.core.strategy.domain.valueobject.MetricType

data class MetricReferenceRequest(
    val asset: String,
    val metric: String,
    val window: Int? = null,
) {
    fun toDomain(): MetricReference = MetricReference(
        asset = AssetSymbol.from(asset),
        metric = MetricType.valueOf(metric),
        window = window,
    )
}
