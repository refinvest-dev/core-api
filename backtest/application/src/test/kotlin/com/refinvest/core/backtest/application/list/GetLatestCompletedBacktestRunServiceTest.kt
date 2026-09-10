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

class GetLatestCompletedBacktestRunServiceTest {
    @Test
    fun `returns the latest completed run for the strategy version`() {
        val strategyVersionId = StrategyVersionId(20L)
        val latestCompletedRun = run(12L, BacktestRunStatus.COMPLETED, "2024-03-01T00:00:00Z")
        var requestedStrategyVersionId: StrategyVersionId? = null
        val service = service(
            reader = reader { versionId ->
                requestedStrategyVersionId = versionId
                latestCompletedRun
            },
        )

        val result = service.execute(strategyVersionId)

        assertEquals(strategyVersionId, requestedStrategyVersionId)
        assertEquals(latestCompletedRun.id, result?.id)
        assertEquals(BacktestRunStatus.COMPLETED, result?.status)
    }

    @Test
    fun `does not return pending running or failed runs`() {
        listOf(BacktestRunStatus.PENDING, BacktestRunStatus.RUNNING, BacktestRunStatus.FAILED).forEach { status ->
            val service = service(
                reader = reader { run(10L, status, "2024-01-01T00:00:00Z") },
            )

            assertNull(service.execute(StrategyVersionId(20L)))
        }
    }

    @Test
    fun `returns null when no completed run exists`() {
        val service = service(
            reader = reader { null },
        )

        assertNull(service.execute(StrategyVersionId(20L)))
    }

    @Test
    fun `rejects a missing strategy version without querying runs`() {
        val service = service(
            reader = reader { error("runs must not be queried for a missing strategy version") },
            lookup = LookupStrategyVersionForBacktestUseCase { null },
        )

        assertFailsWith<NoSuchElementException> {
            service.execute(StrategyVersionId(20L))
        }
    }

    @Test
    fun `rejects a strategy version owned by another member without querying runs`() {
        val service = service(
            reader = reader { error("runs must not be queried for another member's strategy version") },
            lookup = ownerLookup(MemberId(2L)),
        )

        assertFailsWith<NoSuchElementException> {
            service.execute(StrategyVersionId(20L))
        }
    }

    private fun service(
        reader: BacktestRunReader,
        lookup: LookupStrategyVersionForBacktestUseCase = ownerLookup(MemberId(1L)),
    ): GetLatestCompletedBacktestRunService = GetLatestCompletedBacktestRunService(
        backtestRunReader = reader,
        lookupStrategyVersionForBacktestUseCase = lookup,
        backtestMemberIdProvider = BacktestMemberIdProvider { MemberId(1L) },
    )

    private fun ownerLookup(ownerMemberId: MemberId): LookupStrategyVersionForBacktestUseCase =
        LookupStrategyVersionForBacktestUseCase {
            LookupStrategyVersionForBacktestResult(30L, ownerMemberId, setOf("QQQ"))
        }

    private fun reader(
        findLatestCompleted: (StrategyVersionId) -> BacktestRunReadModel?,
    ): BacktestRunReader = object : BacktestRunReader {
        override fun findById(id: BacktestRunId): BacktestRunReadModel? = null

        override fun findLatestCompletedByStrategyVersionId(strategyVersionId: StrategyVersionId): BacktestRunReadModel? =
            findLatestCompleted(strategyVersionId)

        override fun findByStrategyId(
            strategyId: StrategyId,
            page: Int,
            size: Int,
        ): BacktestRunPageReadModel = error("findByStrategyId is not used by GetLatestCompletedBacktestRun")

        override fun countByMemberIdAndCreatedAtBetween(
            memberId: MemberId,
            startInclusive: Instant,
            endExclusive: Instant,
        ): Long = error("countByMemberIdAndCreatedAtBetween is not used by GetLatestCompletedBacktestRun")
    }

    private fun run(id: Long, status: BacktestRunStatus, createdAt: String): BacktestRunReadModel = BacktestRunReadModel(
        id = BacktestRunId(id),
        strategyId = StrategyId(30L),
        strategyVersionId = StrategyVersionId(20L),
        requestedPeriod = Period(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)),
        feeModel = FeeModel(Percent(BigDecimal("0.001")), Percent(BigDecimal("0.002"))),
        status = status,
        createdAt = Instant.parse(createdAt),
    )
}
