package com.refinvest.core.strategy.domain

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.domain.strategy.StrategyVersion
import com.refinvest.core.strategy.domain.valueobject.*
import java.time.Instant
import kotlin.test.*

class StrategyRestorationTest {
    @Test
    fun publishedConditionsCannotBeMutatedThroughAListCast() {
        val conditions = conditions().toMutableList()
        val version = version(conditions = conditions)
        conditions.clear()
        assertEquals(2, version.conditions.size)
        assertFailsWith<UnsupportedOperationException> { (version.conditions as MutableList<Condition>).clear() }
        assertEquals(2, version.conditions.size)
    }

    @Test
    fun restorationKeepsTheExistingVersionsAndCopiesTheInputCollection() {
        val versions = mutableListOf(version(2), version(1))
        val restored = Strategy.restore(StrategyId(1), MemberId(9), "restored", Instant.EPOCH, versions)
        versions.clear()
        assertEquals(listOf(2L, 1L), restored.versions.map { it.id.value })
        (restored.versions as MutableList<StrategyVersion>).clear()
        assertEquals(2, restored.versions.size)
        restored.addVersion(version(3))
        assertEquals(listOf(2L, 1L, 3L), restored.versions.map { it.id.value })
    }

    @Test
    fun restorationRejectsForeignOrDuplicateChildrenAndInvalidMetadata() {
        listOf(listOf(version(strategyId = 2)), listOf(version(), version())).forEach { versions ->
            assertFailsWith<IllegalArgumentException> { Strategy.restore(StrategyId(1), MemberId(9), "restored", Instant.EPOCH, versions) }
        }
        listOf(" ", "x".repeat(201)).forEach { name ->
            assertFailsWith<IllegalArgumentException> { Strategy.restore(StrategyId(1), MemberId(9), name, Instant.EPOCH, emptyList()) }
        }
    }

    @Test
    fun restoredVersionHasTheSameInvariantsAndImmutableConditions() {
        val restored = StrategyVersion.restore(StrategyVersionId(1), StrategyId(1), Instant.EPOCH, AssetSymbol.QQQ,
            conditions(), AssetSymbol.TQQQ, SignalSessions(3), TimeBasedExit(5))
        assertEquals(conditions(), restored.conditions)
        assertFailsWith<UnsupportedOperationException> { (restored.conditions as MutableList<Condition>).removeAt(0) }
        listOf(emptyList(), conditions().reversed()).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                StrategyVersion.restore(StrategyVersionId(2), StrategyId(1), Instant.EPOCH, AssetSymbol.QQQ,
                    invalid, AssetSymbol.TQQQ, SignalSessions(3), TimeBasedExit(5))
            }
        }
    }

    private fun version(id: Long = 1, strategyId: Long = 1, conditions: List<Condition> = conditions()) =
        StrategyVersion.create(StrategyVersionId(id), StrategyId(strategyId), Instant.EPOCH, AssetSymbol.QQQ,
            conditions, AssetSymbol.TQQQ, SignalSessions(3), TimeBasedExit(5))

    private fun conditions() = listOf(
        Condition(ComparisonOperator.LT, operandA = MetricReference(AssetSymbol.QQQ, MetricType.RETURN, 5), operandB = LiteralValue(-0.07)),
        Condition(ComparisonOperator.GTE, LogicalCombinator.AND, MetricReference(AssetSymbol.SPY, MetricType.SIMPLE, null),
            MetricOperand(MetricReference(AssetSymbol.QQQ, MetricType.CHANGE, 3))))
}
