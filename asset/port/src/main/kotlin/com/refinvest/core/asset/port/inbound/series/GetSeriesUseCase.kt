package com.refinvest.core.asset.port.inbound.series

fun interface GetSeriesUseCase {
    fun execute(query: GetSeriesQuery): GetSeriesResult
}
