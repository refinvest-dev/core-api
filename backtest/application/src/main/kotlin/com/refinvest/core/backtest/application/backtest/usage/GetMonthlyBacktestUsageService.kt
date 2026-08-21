package com.refinvest.core.backtest.application.backtest.usage

import com.refinvest.core.backtest.port.inbound.backtest.usage.GetMonthlyBacktestUsageQuery
import com.refinvest.core.backtest.port.inbound.backtest.usage.GetMonthlyBacktestUsageResult
import com.refinvest.core.backtest.port.inbound.backtest.usage.GetMonthlyBacktestUsageUseCase
import com.refinvest.core.backtest.port.outbound.BacktestRunReader
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
open class GetMonthlyBacktestUsageService(
    private val backtestRunReader: BacktestRunReader,
) : GetMonthlyBacktestUsageUseCase {
    @Transactional(readOnly = true)
    override fun execute(query: GetMonthlyBacktestUsageQuery): GetMonthlyBacktestUsageResult =
        GetMonthlyBacktestUsageResult(
            backtestsUsed = backtestRunReader.countByMemberIdAndCreatedAtBetween(
                memberId = query.memberId,
                startInclusive = query.startInclusive,
                endExclusive = query.endExclusive,
            ),
        )
}
