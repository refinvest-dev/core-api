package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.domain.backtest.BacktestResult
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.port.outbound.persistence.BacktestResultReader
import org.springframework.stereotype.Repository

@Repository
class JpaBacktestResultReaderAdapter(
    private val backtestResultJpaReader: BacktestResultJpaReader,
    private val resultPayloadMapper: BacktestResultPayloadMapper,
) : BacktestResultReader {
    override fun findByBacktestRunId(backtestRunId: BacktestRunId): BacktestResult? =
        backtestResultJpaReader.findById(backtestRunId.value)?.let { entity ->
            resultPayloadMapper.deserialize(entity.resultPayload)
        }
}
