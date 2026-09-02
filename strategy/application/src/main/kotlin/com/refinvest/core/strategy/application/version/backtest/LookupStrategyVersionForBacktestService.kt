package com.refinvest.core.strategy.application.version.backtest

import com.refinvest.core.strategy.port.inbound.version.backtest.LookupStrategyVersionForBacktestQuery
import com.refinvest.core.strategy.port.inbound.version.backtest.LookupStrategyVersionForBacktestResult
import com.refinvest.core.strategy.port.inbound.version.backtest.LookupStrategyVersionForBacktestUseCase
import com.refinvest.core.strategy.port.outbound.persistence.version.StrategyVersionBacktestReader
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class LookupStrategyVersionForBacktestService(
    private val strategyVersionBacktestReader: StrategyVersionBacktestReader,
) : LookupStrategyVersionForBacktestUseCase {
    @Transactional(readOnly = true)
    override fun execute(query: LookupStrategyVersionForBacktestQuery): LookupStrategyVersionForBacktestResult? =
        strategyVersionBacktestReader.findById(query.strategyVersionId)?.let { version ->
            LookupStrategyVersionForBacktestResult(
                strategyId = version.strategyId,
                ownerMemberId = version.ownerMemberId,
                assetSymbols = version.assetSymbols,
                definition = version.definition,
            )
        }
}
