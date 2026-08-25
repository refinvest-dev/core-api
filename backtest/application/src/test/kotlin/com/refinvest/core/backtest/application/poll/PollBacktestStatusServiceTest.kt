package com.refinvest.core.backtest.application.poll

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Percent
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.port.inbound.poll.PollBacktestStatusQuery
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunReadModel
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunReader
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
            runReader { id ->
                BacktestRunReadModel(
                    id = id,
                    strategyId = StrategyId(30L),
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
        assertEquals(StrategyId(30L), result?.strategyId)
        assertEquals(BacktestRunStatus.PENDING, result?.status)
    }

    @Test
    fun `returns null when the run does not exist`() {
        val service = PollBacktestStatusService(runReader { null })

        assertNull(service.execute(PollBacktestStatusQuery(BacktestRunId(10L))))
    }

    private fun runReader(
        findById: (BacktestRunId) -> BacktestRunReadModel?,
    ): BacktestRunReader = object : BacktestRunReader {
        override fun findById(id: BacktestRunId): BacktestRunReadModel? = findById(id)

        override fun findByStrategyId(
            strategyId: StrategyId,
            page: Int,
            size: Int,
        ) = error("findByStrategyId is not used by PollBacktestStatus")

        override fun countByMemberIdAndCreatedAtBetween(
            memberId: com.refinvest.core.shared.kernel.member.MemberId,
            startInclusive: Instant,
            endExclusive: Instant,
        ) = error("countByMemberIdAndCreatedAtBetween is not used by PollBacktestStatus")
    }
}
