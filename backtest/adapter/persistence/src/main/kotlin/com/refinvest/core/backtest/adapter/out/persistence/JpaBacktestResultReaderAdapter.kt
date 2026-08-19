package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.domain.backtest.BacktestResult
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.port.outbound.BacktestResultReader
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper

@Repository
class JpaBacktestResultReaderAdapter(
    private val backtestResultJpaReader: BacktestResultJpaReader,
    private val objectMapper: ObjectMapper,
) : BacktestResultReader {
    override fun findByBacktestRunId(backtestRunId: BacktestRunId): BacktestResult? =
        backtestResultJpaReader.findById(backtestRunId.value)?.let { entity ->
            objectMapper.readValue(entity.resultPayload, BacktestResult::class.java)
        }
}
