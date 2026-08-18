package com.refinvest.core.backtest.application.backtest.poll

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Percent
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.port.inbound.backtest.poll.PollBacktestStatusQuery
import com.refinvest.core.backtest.port.outbound.BacktestRunReadModel
import com.refinvest.core.backtest.port.outbound.BacktestRunReader
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PollBacktestStatusServiceTest {
    @Test
    fun `returns a persisted pending run`() {
        val runId = BacktestRunId(10L)
        val service = PollBacktestStatusService(
            BacktestRunReader { id ->
                BacktestRunReadModel(
                    id = id,
                    strategyVersionId = StrategyVersionId(20L),
                    requestedPeriod = Period(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)),
                    feeModel = FeeModel(Percent(BigDecimal("0.001")), Percent(BigDecimal("0.002"))),
                    status = BacktestRunStatus.PENDING,
                    createdAt = Instant.parse("2024-01-01T00:00:00Z"),
                )
            },
        )

        val result = service.execute(PollBacktestStatusQuery(runId))

        assertEquals(runId, result?.id)
        assertEquals(BacktestRunStatus.PENDING, result?.status)
    }

    @Test
    fun `returns null when the run does not exist`() {
        val service = PollBacktestStatusService(BacktestRunReader { null })

        assertNull(service.execute(PollBacktestStatusQuery(BacktestRunId(10L))))
    }
}
