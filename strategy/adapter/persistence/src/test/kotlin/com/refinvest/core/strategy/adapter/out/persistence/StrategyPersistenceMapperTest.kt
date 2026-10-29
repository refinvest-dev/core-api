package com.refinvest.core.strategy.adapter.out.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.domain.Strategy
import com.refinvest.core.strategy.domain.strategy.StrategyVersion
import com.refinvest.core.strategy.domain.valueobject.*
import com.refinvest.core.strategy.port.inbound.version.backtest.StrategyMetricOperandForBacktest
import java.time.Instant
import kotlin.test.*

class StrategyPersistenceMapperTest {
    @Test
    fun aggregateRoundTripPreservesEveryDefinitionAndTheChildRelationships() {
        val original = aggregate()
        val entity = StrategyPersistenceMapper.toEntity(original)
        entity.versions.forEach { version ->
            assertSame(entity, version.strategy)
            assertEquals(listOf(0, 1, 2), version.conditions.map { it.conditionOrder })
            version.conditions.forEach { assertSame(version, it.strategyVersion) }
        }
        val restored = StrategyPersistenceMapper.toDomain(entity)
        assertEquals(original.id, restored.id)
        assertEquals(original.memberId, restored.memberId)
        assertEquals(original.name, restored.name)
        assertEquals(original.createdAt, restored.createdAt)
        assertEquals(original.versions.map(::definition), restored.versions.map(::definition))
        assertFailsWith<UnsupportedOperationException> { (restored.versions.first().conditions as MutableList<Condition>).clear() }
        restored.addVersion(version(30))
        assertEquals(3, restored.versions.size)
        assertEquals(2, original.versions.size)
    }

    @Test
    fun conditionOrderAndQueryLatestDoNotDependOnPhysicalCollectionOrder() {
        val entity = StrategyPersistenceMapper.toEntity(aggregate())
        entity.versions.reverse()
        entity.versions.forEach { it.conditions.reverse() }
        val originalOrders = entity.versions.map { it.conditions.toList() }
        val domain = StrategyPersistenceMapper.toDomain(entity)
        assertTrue(domain.versions.all { it.conditions == conditions() })
        val read = StrategyReadModelMapper.toReadModel(entity)
        assertEquals(listOf(9L, 10L), read.versions.map { it.id.value })
        assertEquals(StrategyVersionId(10), read.latestVersionId)
        assertTrue(read.versions.all { it.conditions == conditions() })
        val version = entity.versions.first()
        val backtest = StrategyReadModelMapper.toBacktestReadModel(version, entity.id, MemberId(entity.memberId), version.conditions.toList())
        assertEquals(setOf("QQQ", "SPY", "TQQQ", "SOXL", "BTCUSDT"), backtest.assetSymbols)
        assertEquals(entity.id, backtest.strategyId)
        assertEquals(MemberId(entity.memberId), backtest.ownerMemberId)
        assertNull(backtest.definition.conditions.first().logicalCombinator)
        assertEquals("OR", backtest.definition.conditions.last().logicalCombinator)
        val operand = assertIs<StrategyMetricOperandForBacktest>(backtest.definition.conditions.last().operandB)
        assertEquals("SIMPLE", operand.value.metric)
        assertNull(operand.value.window)
        assertEquals(originalOrders, entity.versions.map { it.conditions.toList() }, "Mapping must not reorder stored collections")
    }

    @Test
    fun emptyAggregateRetainsMetadataAndHasNoLatestVersion() {
        val entity = StrategyPersistenceMapper.toEntity(Strategy.create(StrategyId(1), MemberId(2), "empty", TIME))
        assertTrue(StrategyPersistenceMapper.toDomain(entity).versions.isEmpty())
        assertNull(StrategyReadModelMapper.toReadModel(entity).latestVersionId)
    }

    @Test
    fun invalidStoredOperandsFailRatherThanDefaultingToAnotherDefinition() {
        val entity = StrategyPersistenceMapper.toEntity(aggregate())
        val row = entity.versions.first().conditions.first()
        row.operandBKind = "UNKNOWN"
        assertFailsWith<IllegalStateException> { StrategyPersistenceMapper.toDomain(entity) }
        row.operandBKind = "METRIC"
        assertFailsWith<IllegalArgumentException> { StrategyPersistenceMapper.toDomain(entity) }
        row.operandBKind = "LITERAL"
        row.operandBLiteral = null
        assertFailsWith<IllegalArgumentException> { StrategyPersistenceMapper.toDomain(entity) }
    }

    @Test
    fun mappingCannotReparentAChildOrRestoreDuplicateIdentities() {
        val entity = StrategyPersistenceMapper.toEntity(aggregate())
        val foreign = StrategyJpaEntity(2, 3, "foreign", TIME)
        assertFailsWith<IllegalArgumentException> { StrategyPersistenceMapper.toEntity(aggregate().versions.first(), foreign) }
        entity.versions.first().strategy = foreign
        assertFailsWith<IllegalArgumentException> { StrategyPersistenceMapper.toDomain(entity) }
        entity.versions.first().strategy = entity
        entity.versions[1].id = entity.versions[0].id
        assertFailsWith<IllegalArgumentException> { StrategyPersistenceMapper.toDomain(entity) }
    }

    private fun aggregate() = Strategy.create(StrategyId(1), MemberId(9007199254740993L), "round trip", TIME).apply {
        addVersion(version(10)); addVersion(version(9))
    }
    private fun version(id: Long) = StrategyVersion.create(StrategyVersionId(id), StrategyId(1), TIME, AssetSymbol.BTCUSDT,
        conditions(), AssetSymbol.SOXL, SignalSessions(0), TimeBasedExit(1))
    private fun definition(version: StrategyVersion): List<Any> = listOf(version.id, version.strategyId, version.createdAt,
        version.primarySignalAsset, version.conditions, version.executionAsset, version.lag, version.exit, version.positionPolicy)
    private fun conditions() = listOf(
        Condition(ComparisonOperator.LT, operandA = MetricReference(AssetSymbol.QQQ, MetricType.SIMPLE), operandB = LiteralValue(123.45)),
        Condition(ComparisonOperator.GTE, LogicalCombinator.AND, MetricReference(AssetSymbol.SPY, MetricType.RETURN, 5),
            MetricOperand(MetricReference(AssetSymbol.BTCUSDT, MetricType.CHANGE, 3))),
        Condition(ComparisonOperator.LTE, LogicalCombinator.OR, MetricReference(AssetSymbol.TQQQ, MetricType.CHANGE, 1),
            MetricOperand(MetricReference(AssetSymbol.SOXL, MetricType.SIMPLE))))
    companion object { private val TIME = Instant.parse("2026-10-01T00:00:00.123456789Z") }
}
