package com.refinvest.core.backtest.application.list

import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Percent
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.port.outbound.member.BacktestMemberIdProvider
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunPageReadModel
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunReadModel
import com.refinvest.core.backtest.port.outbound.persistence.BacktestRunReader
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.port.inbound.version.backtest.LookupStrategyVersionForBacktestResult
import com.refinvest.core.strategy.port.inbound.version.backtest.LookupStrategyVersionForBacktestUseCase
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GetLatestTerminalBacktestRunServiceTest {
    @Test
    fun `returns the latest failed run for the owned strategy version`() {
        val latestFailedRun = run(12L, BacktestRunStatus.FAILED)
        val service = service(reader { latestFailedRun })

        val result = service.execute(StrategyVersionId(20L))

        assertEquals(latestFailedRun.id, result?.id)
        assertEquals(BacktestRunStatus.FAILED, result?.status)
    }

    @Test
    fun `returns null when no terminal run exists`() {
        assertNull(service(reader { null }).execute(StrategyVersionId(20L)))
    }

    @Test
    fun `does not expose another members strategy version`() {
        val service = service(
            reader = reader { error("runs must not be queried") },
            lookup = LookupStrategyVersionForBacktestUseCase {
                LookupStrategyVersionForBacktestResult(30L, MemberId(2L), setOf("QQQ"))
            },
        )

        assertFailsWith<NoSuchElementException> { service.execute(StrategyVersionId(20L)) }
    }

    private fun service(
        reader: BacktestRunReader,
        lookup: LookupStrategyVersionForBacktestUseCase = LookupStrategyVersionForBacktestUseCase {
            LookupStrategyVersionForBacktestResult(30L, MemberId(1L), setOf("QQQ"))
        },
    ) = GetLatestTerminalBacktestRunService(
        backtestRunReader = reader,
        lookupStrategyVersionForBacktestUseCase = lookup,
        backtestMemberIdProvider = BacktestMemberIdProvider { MemberId(1L) },
    )

    private fun reader(
        latestTerminal: (StrategyVersionId) -> BacktestRunReadModel?,
    ): BacktestRunReader = object : BacktestRunReader {
        override fun findById(id: BacktestRunId): BacktestRunReadModel? = null

        override fun findLatestTerminalByStrategyVersionId(strategyVersionId: StrategyVersionId): BacktestRunReadModel? =
            latestTerminal(strategyVersionId)

        override fun findByStrategyId(
            strategyId: StrategyId,
            page: Int,
            size: Int,
        ): BacktestRunPageReadModel = error("findByStrategyId is not used")

        override fun countByMemberIdAndCreatedAtBetween(
            memberId: MemberId,
            startInclusive: Instant,
            endExclusive: Instant,
        ): Long = error("countByMemberIdAndCreatedAtBetween is not used")
    }

    private fun run(id: Long, status: BacktestRunStatus): BacktestRunReadModel = BacktestRunReadModel(
        id = BacktestRunId(id),
        strategyId = StrategyId(30L),
        strategyVersionId = StrategyVersionId(20L),
        requestedPeriod = Period(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)),
        feeModel = FeeModel(Percent(BigDecimal("0.001")), Percent(BigDecimal("0.002"))),
        status = status,
        createdAt = Instant.parse("2024-03-01T00:00:00Z"),
    )
}
