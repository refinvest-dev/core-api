package com.refinvest.core.strategy.adapter.web.strategy.define

import com.refinvest.core.strategy.domain.valueobject.MetricReference

data class MetricReferenceResponse(
    val asset: String,
    val metric: String,
    val window: Int?,
) {
    companion object {
        fun from(reference: MetricReference): MetricReferenceResponse =
            MetricReferenceResponse(reference.asset.name, reference.metric.name, reference.window)
    }
}
