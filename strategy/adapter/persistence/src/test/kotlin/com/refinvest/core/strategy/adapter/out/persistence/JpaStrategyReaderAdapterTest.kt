package com.refinvest.core.strategy.adapter.out.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.strategy.domain.valueobject.StrategyId
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JpaStrategyReaderAdapterTest {
    @Test
    fun detailAndListChooseTheSameLatestVersionForEveryCollectionOrder() {
        val strategy = StrategyJpaEntity(1, 7, "synthetic", Instant.EPOCH)
        val versions = listOf(
            version(strategy, 20, 0),
            version(strategy, 300, 1), // The greatest ID is not the newest creation time.
            version(strategy, 9, 2),
            version(strategy, 10, 2), // Numeric ID, rather than string order, breaks the time tie.
        )
        val reader = JpaStrategyReaderAdapter(StubReader(strategy))
        for (order in permutations(versions)) {
            strategy.versions = order.toMutableList()
            val detail = requireNotNull(reader.findById(StrategyId(1)))
            val listed = reader.findByMemberId(MemberId(7), 0, 10).items.single()

            assertEquals(listOf(20L, 300L, 9L, 10L), detail.versions.map { it.id.value })
            assertEquals(10L, detail.latestVersionId?.value)
            assertEquals(detail, listed)
            assertEquals(order, strategy.versions, "Reading must not mutate the persistence collection")
        }
    }

    @Test
    fun aStrategyWithoutVersionsHasNoLatestVersionInEitherReadPath() {
        val reader = JpaStrategyReaderAdapter(StubReader(StrategyJpaEntity(1, 7, "empty", Instant.EPOCH)))
        val detail = requireNotNull(reader.findById(StrategyId(1)))
        assertNull(detail.latestVersionId)
        assertEquals(emptyList(), detail.versions)
        assertEquals(detail, reader.findByMemberId(MemberId(7), 0, 10).items.single())
    }

    @Test
    fun missingStrategyRemainsAbsent() {
        val reader = JpaStrategyReaderAdapter(StubReader(null))
        assertNull(reader.findById(StrategyId(999)))
        assertEquals(emptyList(), reader.findByMemberId(MemberId(7), 0, 10).items)
    }

    private fun version(strategy: StrategyJpaEntity, id: Long, second: Long) = StrategyVersionJpaEntity(
        id, strategy, Instant.EPOCH.plusSeconds(second), "QQQ", "TQQQ", 3, 5,
    ).apply {
        conditions += ConditionJpaEntity(
            strategyVersion = this, conditionOrder = 0, operator = "LT", logicalCombinator = null,
            operandAAsset = "QQQ", operandAMetric = "RETURN", operandAWindow = 5,
            operandBKind = "LITERAL", operandBLiteral = -0.07,
            operandBAsset = null, operandBMetric = null, operandBWindow = null,
        )
    }

    private fun <T> permutations(items: List<T>): List<List<T>> =
        if (items.isEmpty()) listOf(emptyList()) else items.flatMap { item ->
            permutations(items - item).map { listOf(item) + it }
        }

    private class StubReader(private val strategy: StrategyJpaEntity?) : StrategyJpaReader {
        override fun findOwnerById(id: Long): Long? = strategy?.takeIf { it.id == id }?.memberId

        override fun findById(id: Long): StrategyJpaEntity? = strategy?.takeIf { it.id == id }

        override fun findAllByMemberIdOrderByCreatedAtDesc(memberId: Long, pageable: Pageable): Page<StrategyJpaEntity> {
            val matching = listOfNotNull(strategy?.takeIf { it.memberId == memberId })
            return PageImpl(matching, pageable, matching.size.toLong())
        }
    }
}
