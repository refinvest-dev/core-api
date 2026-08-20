package com.refinvest.core.strategy.adapter.web.strategy.preview

import com.refinvest.core.strategy.port.inbound.strategy.preview.PreviewMetricReference

data class PreviewMetricReferenceRequest(
    val asset: String? = null,
    val metric: String? = null,
    val window: Int? = null,
) {
    fun toCommand(): PreviewMetricReference = PreviewMetricReference(asset, metric, window)
}
