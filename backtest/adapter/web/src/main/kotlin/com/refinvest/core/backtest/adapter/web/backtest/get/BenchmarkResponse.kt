package com.refinvest.core.backtest.adapter.web.backtest.get

import com.refinvest.core.backtest.domain.backtest.Benchmark

data class BenchmarkResponse(
    val primary: BuyAndHoldResultResponse,
    val secondaryReference: BuyAndHoldResultResponse?,
) {
    companion object {
        fun from(benchmark: Benchmark): BenchmarkResponse = BenchmarkResponse(
            primary = BuyAndHoldResultResponse.from(benchmark.primary),
            secondaryReference = benchmark.secondaryReference?.let(BuyAndHoldResultResponse::from),
        )
    }
}
