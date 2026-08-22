package com.refinvest.core.backtest.application.backtest.get

import com.refinvest.core.backtest.domain.backtest.BacktestResult
import com.refinvest.core.backtest.domain.backtest.BacktestResultMetrics
import com.refinvest.core.backtest.domain.backtest.Benchmark
import com.refinvest.core.backtest.domain.backtest.BuyAndHoldResult
import com.refinvest.core.backtest.domain.backtest.DataIntegrityStatus
import com.refinvest.core.backtest.domain.backtest.SampleSizeWarning
import com.refinvest.core.backtest.domain.backtest.SignalExecutionDelay
import com.refinvest.core.backtest.domain.valueobject.BacktestRunId
import com.refinvest.core.backtest.domain.valueobject.BacktestRunStatus
import com.refinvest.core.backtest.domain.valueobject.DatasetSnapshotId
import com.refinvest.core.backtest.domain.valueobject.EngineVersion
import com.refinvest.core.backtest.domain.valueobject.FeeModel
import com.refinvest.core.backtest.domain.valueobject.Percent
import com.refinvest.core.backtest.domain.valueobject.Period
import com.refinvest.core.backtest.domain.valueobject.StrategyId
import com.refinvest.core.backtest.domain.valueobject.StrategyVersionId
import com.refinvest.core.backtest.port.inbound.backtest.get.GetBacktestResultQuery
import com.refinvest.core.backtest.port.outbound.BacktestResultReader
import com.refinvest.core.backtest.port.outbound.BacktestMemberIdProvider
import com.refinvest.core.backtest.port.outbound.BacktestRunReadModel
import com.refinvest.core.backtest.port.outbound.BacktestRunReader
import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.port.inbound.strategy.version.backtest.LookupStrategyVersionForBacktestResult
import com.refinvest.core.strategy.port.inbound.strategy.version.backtest.LookupStrategyVersionForBacktestUseCase
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GetBacktestResultServiceTest {
    @Test
    fun `returns a result only for a completed run`() {
        val runId = BacktestRunId(10L)
        val result = resultFor(runId)
        val service = GetBacktestResultService(
            backtestRunReader = BacktestRunReader { completedRun(it) },
            backtestResultReader = BacktestResultReader { result },
            lookupStrategyVersionForBacktestUseCase = ownerLookup(MemberId(1L)),
            backtestMemberIdProvider = BacktestMemberIdProvider { MemberId(1L) },
        )

        val response = service.execute(GetBacktestResultQuery(runId))

        assertEquals(BacktestRunStatus.COMPLETED, response?.status)
        assertEquals(result, response?.result)
        assertEquals(DatasetSnapshotId("snapshot-1"), response?.datasetSnapshotId)
    }

    @Test
    fun `does not read a result for a pending run`() {
        val runId = BacktestRunId(10L)
        val service = GetBacktestResultService(
            backtestRunReader = BacktestRunReader { pendingRun(it) },
            backtestResultReader = BacktestResultReader { error("must not read result for a pending run") },
            lookupStrategyVersionForBacktestUseCase = ownerLookup(MemberId(1L)),
            backtestMemberIdProvider = BacktestMemberIdProvider { MemberId(1L) },
        )

        val response = service.execute(GetBacktestResultQuery(runId))

        assertEquals(BacktestRunStatus.PENDING, response?.status)
        assertNull(response?.result)
    }

    @Test
    fun `rejects a completed run without a persisted result`() {
        val service = GetBacktestResultService(
            backtestRunReader = BacktestRunReader { completedRun(it) },
            backtestResultReader = BacktestResultReader { null },
            lookupStrategyVersionForBacktestUseCase = ownerLookup(MemberId(1L)),
            backtestMemberIdProvider = BacktestMemberIdProvider { MemberId(1L) },
        )

        assertFailsWith<IllegalArgumentException> {
            service.execute(GetBacktestResultQuery(BacktestRunId(10L)))
        }
    }

    @Test
    fun `returns null when the run does not exist`() {
        val service = GetBacktestResultService(
            backtestRunReader = BacktestRunReader { null },
            backtestResultReader = BacktestResultReader { error("must not read a missing run") },
            lookupStrategyVersionForBacktestUseCase = ownerLookup(MemberId(1L)),
            backtestMemberIdProvider = BacktestMemberIdProvider { MemberId(1L) },
        )

        assertNull(service.execute(GetBacktestResultQuery(BacktestRunId(10L))))
    }

    @Test
    fun `returns null when the run belongs to another member`() {
        val service = GetBacktestResultService(
            backtestRunReader = BacktestRunReader { pendingRun(it) },
            backtestResultReader = BacktestResultReader { error("must not read another member result") },
            lookupStrategyVersionForBacktestUseCase = ownerLookup(MemberId(2L)),
            backtestMemberIdProvider = BacktestMemberIdProvider { MemberId(1L) },
        )

        assertNull(service.execute(GetBacktestResultQuery(BacktestRunId(10L))))
    }

    private fun ownerLookup(ownerMemberId: MemberId): LookupStrategyVersionForBacktestUseCase =
        LookupStrategyVersionForBacktestUseCase {
            LookupStrategyVersionForBacktestResult(30L, ownerMemberId, setOf("QQQ"))
        }

    private fun pendingRun(id: BacktestRunId): BacktestRunReadModel = BacktestRunReadModel(
        id = id,
        strategyId = StrategyId(30L),
        strategyVersionId = StrategyVersionId(20L),
        requestedPeriod = Period(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)),
        feeModel = FeeModel(Percent(BigDecimal("0.001")), Percent(BigDecimal("0.002"))),
        status = BacktestRunStatus.PENDING,
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
    )

    private fun completedRun(id: BacktestRunId): BacktestRunReadModel = pendingRun(id).copy(
        status = BacktestRunStatus.COMPLETED,
        actualPeriod = Period(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)),
        datasetSnapshotId = DatasetSnapshotId("snapshot-1"),
        engineVersion = EngineVersion("engine-1"),
    )

    private fun resultFor(runId: BacktestRunId): BacktestResult = BacktestResult(
        backtestRunId = runId,
        metrics = BacktestResultMetrics(
            totalReturn = BigDecimal("0.10"), cagr = BigDecimal("0.08"), mdd = BigDecimal("0.03"),
            sharpe = BigDecimal("1.20"), winRate = BigDecimal("0.60"), tradeCount = 3,
            avgTradeReturn = BigDecimal("0.04"), avgHoldingPeriod = BigDecimal("5"), profitFactor = BigDecimal("1.50"),
        ),
        equityCurve = emptyList(),
        trades = emptyList(),
        benchmark = Benchmark(BuyAndHoldResult(BigDecimal("0.05"), BigDecimal("0.04"), BigDecimal("0.02")), null),
        signalExecutionDelay = SignalExecutionDelay(BigDecimal.ONE, BigDecimal.ONE, emptyList()),
        sampleSizeWarning = SampleSizeWarning.LOW,
        dataIntegrityStatus = DataIntegrityStatus(DatasetSnapshotId("snapshot-1"), true, true),
    )
}
