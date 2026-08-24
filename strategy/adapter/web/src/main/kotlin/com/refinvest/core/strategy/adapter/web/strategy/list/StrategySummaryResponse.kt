package com.refinvest.core.strategy.adapter.web.strategy.list

import com.refinvest.core.strategy.port.inbound.list.StrategySummary
import java.time.Instant

data class StrategySummaryResponse(
    val id: String,
    val name: String,
    val createdAt: Instant,
    val latestVersionId: String?,
) {
    companion object {
        fun from(summary: StrategySummary): StrategySummaryResponse = StrategySummaryResponse(
            id = summary.id.value.toString(),
            name = summary.name,
            createdAt = summary.createdAt,
            latestVersionId = summary.latestVersionId?.value?.toString(),
        )
    }
}
