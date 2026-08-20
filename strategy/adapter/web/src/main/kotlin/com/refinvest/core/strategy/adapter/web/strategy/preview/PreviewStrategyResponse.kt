package com.refinvest.core.strategy.adapter.web.strategy.preview

import com.refinvest.core.strategy.port.inbound.strategy.preview.PreviewStrategyResult

data class PreviewStrategyResponse(
    val previewText: String,
) {
    companion object {
        fun from(result: PreviewStrategyResult): PreviewStrategyResponse = PreviewStrategyResponse(result.previewText)
    }
}
