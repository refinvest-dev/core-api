package com.refinvest.core.strategy.adapter.web.strategy.list

import com.refinvest.core.strategy.port.inbound.list.ListStrategiesResult

data class ListStrategiesResponse(
    val items: List<StrategySummaryResponse>,
    val page: Int,
    val size: Int,
    val total: Long,
) {
    companion object {
        fun from(result: ListStrategiesResult): ListStrategiesResponse = ListStrategiesResponse(
            items = result.items.map(StrategySummaryResponse::from),
            page = result.page,
            size = result.size,
            total = result.total,
        )
    }
}
