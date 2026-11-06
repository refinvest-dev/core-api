package com.refinvest.core.backtest.adapter.out.persistence

import com.refinvest.core.backtest.domain.BacktestRun
import com.refinvest.core.backtest.domain.backtest.EquityCurvePoint
import com.refinvest.core.backtest.domain.exception.LegacyBacktestResultPayloadException
import com.refinvest.core.backtest.domain.valueobject.*
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.*

class BacktestPersistenceMapperTest {
    private val mapper = BacktestResultPayloadMapper(jacksonObjectMapper())

    @Test
    fun allLifecycleStatesRoundTripWithoutRunningCommandsOrLosingNullableFields() {
        val runs = listOf(BacktestMappingFixtures.pending(), BacktestMappingFixtures.running(2), BacktestMappingFixtures.completed(3),
            BacktestMappingFixtures.pending(4).apply { failWithoutExecution("not accepted") },
            BacktestMappingFixtures.running(5).apply { fail("missing prices", "PRICE_DATA_MISSING") },
            BacktestMappingFixtures.completed(9007199254740993L, zero = true))
        runs.forEach { source ->
            val stored = BacktestRunPersistenceMapper.toPersistence(source, mapper)
            val restored = BacktestRunPersistenceMapper.toDomain(stored.run, stored.result, mapper)
            assertEquals(metadata(source), metadata(restored))
            val read = BacktestRunReadModelMapper.toReadModel(stored.run)
            assertEquals(source.status, read.status)
            assertEquals(source.actualPeriod, read.actualPeriod)
            assertEquals(source.failureReason, read.failureReason)
            assertEquals(source.errorCode, read.errorCode)
            if (source.result != null) {
                val original = source.result!!
                val result = restored.result!!
                assertEquals(original.backtestRunId, result.backtestRunId)
                assertEquals(original.metrics, result.metrics)
                assertEquals(original.equityCurve, result.equityCurve)
                assertEquals(original.trades, result.trades)
                assertEquals(original.benchmark, result.benchmark)
                assertEquals(original.signalExecutionMarketRelation, result.signalExecutionMarketRelation)
                assertEquals(original.signalExecutionDelay.distribution, result.signalExecutionDelay.distribution)
                assertEquals(original.signalExecutionDelay.median, result.signalExecutionDelay.median)
                assertEquals(original.signalExecutionDelay.max, result.signalExecutionDelay.max)
                assertEquals(original.sampleSizeWarning, result.sampleSizeWarning)
                assertEquals(original.dataIntegrityStatus, result.dataIntegrityStatus)
            } else assertNull(restored.result)
        }
    }

    @Test
    fun rowIdentityPayloadIdentityAndSnapshotMustAgree() {
        val stored = BacktestRunPersistenceMapper.toPersistence(BacktestMappingFixtures.completed(), mapper)
        assertFailsWith<IllegalArgumentException> { BacktestRunPersistenceMapper.toDomain(stored.run, null, mapper) }
        val foreignRow = BacktestResultPersistenceMapper.toEntity(BacktestMappingFixtures.result(2), mapper)
        assertFailsWith<IllegalArgumentException> { BacktestRunPersistenceMapper.toDomain(stored.run, foreignRow, mapper) }
        foreignRow.backtestRunId = 1
        assertFailsWith<IllegalArgumentException> { BacktestResultPersistenceMapper.toDomain(foreignRow, mapper) }
        val foreignSnapshot = BacktestResultPersistenceMapper.toEntity(BacktestMappingFixtures.result(snapshot = DatasetSnapshotId("other")), mapper)
        assertFailsWith<IllegalArgumentException> { BacktestRunPersistenceMapper.toDomain(stored.run, foreignSnapshot, mapper) }
    }

    @Test
    fun malformedPeriodBoundariesCannotBeSilentlyDiscardedByEitherMapper() {
        val row = BacktestRunPersistenceMapper.toPersistence(BacktestMappingFixtures.pending(), mapper).run
        row.actualPeriodEnd = BacktestMappingFixtures.period.end
        assertFailsWith<IllegalArgumentException> { BacktestRunPersistenceMapper.toDomain(row, null, mapper) }
        assertFailsWith<IllegalArgumentException> { BacktestRunReadModelMapper.toReadModel(row) }
        row.actualPeriodStart = BacktestMappingFixtures.period.start
        row.actualPeriodEnd = null
        assertFailsWith<IllegalArgumentException> { BacktestRunReadModelMapper.toReadModel(row) }
    }

    @Test
    fun payloadKeepsItsExistingShapeAndRestoresImmutableNestedCollections() {
        val stored = BacktestRunPersistenceMapper.toPersistence(BacktestMappingFixtures.completed(), mapper)
        val json = jacksonObjectMapper().readTree(stored.result!!.resultPayload)
        assertEquals(9, json.size())
        assertFalse(json.has("id"))
        assertTrue(json.has("backtestRunId"))
        val result = BacktestRunPersistenceMapper.toDomain(stored.run, stored.result, mapper).result!!
        assertFails { (result.equityCurve as MutableList<EquityCurvePoint>).clear() }
        assertFails { (result.benchmark.secondaryReference!!.equityCurve as MutableList<EquityCurvePoint>).clear() }
        assertEquals(1, result.trades.size)
        assertEquals(2, result.benchmark.primary.equityCurve.size)
    }

    @Test
    fun legacyVisualizationPayloadStillRequiresRecomputation() {
        val legacy = BacktestResultJpaEntity(1, """{"backtestRunId":1,"metrics":{}}""")
        assertFailsWith<LegacyBacktestResultPayloadException> { BacktestResultPersistenceMapper.toDomain(legacy, mapper) }
    }

    @Test
    fun payloadComparisonPreservesDecimalPrecisionWhileIgnoringPropertyOrder() {
        assertTrue(mapper.equivalent("""{"b":2,"a":0.1}""", """{"a":0.10,"b":2}"""))
        assertFalse(mapper.equivalent("""{"amount":0.123456789123456789}""", """{"amount":0.123456789123456788}"""))
    }

    private fun metadata(run: BacktestRun): List<Any?> = listOf(run.id, run.strategyId, run.strategyVersionId, run.requestedPeriod,
        run.feeModel, run.createdAt, run.status, run.actualPeriod, run.datasetSnapshotId, run.engineVersion, run.failureReason, run.errorCode)
}
