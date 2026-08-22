package com.refinvest.core.strategy.application.strategy.preview

import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.port.inbound.strategy.preview.PreviewCondition
import com.refinvest.core.strategy.port.inbound.strategy.preview.PreviewLiteralOperand
import com.refinvest.core.strategy.port.inbound.strategy.preview.PreviewMetricReference
import com.refinvest.core.strategy.port.inbound.strategy.preview.PreviewStrategyCommand
import com.refinvest.core.strategy.port.outbound.StrategyReadModel
import com.refinvest.core.strategy.port.outbound.StrategyReader
import com.refinvest.core.strategy.port.outbound.MemberIdProvider
import com.refinvest.core.shared.kernel.member.MemberId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreviewStrategyServiceTest {
    @Test
    fun `renders a strategy draft without persisting it`() {
        val strategyId = StrategyId(1L)
        val service = PreviewStrategyService(StrategyReader { existingStrategy(it) }, MemberIdProvider { MemberId(1L) })

        val result = service.execute(
            PreviewStrategyCommand(
                strategyId = strategyId,
                primarySignalAsset = "QQQ",
                conditions = listOf(
                    PreviewCondition(
                        operator = "LT",
                        logicalCombinator = null,
                        operandA = PreviewMetricReference("QQQ", "RETURN", 5),
                        operandB = PreviewLiteralOperand("-0.07"),
                    ),
                ),
                executionAsset = "TQQQ",
                lag = 3,
                holdingSignalSessions = 5,
            ),
        )

        assertEquals(
            "기준 신호 자산은 QQQ입니다. QQQ의 5일 수익률 -7% 미만, 3 Signal Session 후 TQQQ를 매수해 5 Signal Session 보유합니다.",
            result?.previewText,
        )
    }

    @Test
    fun `renders an incomplete draft with no conditions`() {
        val service = PreviewStrategyService(StrategyReader { existingStrategy(it) }, MemberIdProvider { MemberId(1L) })

        val result = service.execute(
            PreviewStrategyCommand(StrategyId(1L), "QQQ", emptyList(), "TQQQ", 0, 1),
        )

        assertEquals(
            "기준 신호 자산은 QQQ입니다. 조건을 입력해 주세요, 0 Signal Session 후 TQQQ를 매수해 1 Signal Session 보유합니다.",
            result?.previewText,
        )
    }

    @Test
    fun `returns null when the strategy does not exist`() {
        val service = PreviewStrategyService(StrategyReader { null }, MemberIdProvider { MemberId(1L) })

        assertNull(service.execute(PreviewStrategyCommand(StrategyId(1L), null, emptyList(), null, null, null)))
    }

    @Test
    fun `returns null when the strategy belongs to another member`() {
        val service = PreviewStrategyService(
            StrategyReader { existingStrategy(it).copy(memberId = MemberId(2L)) },
            MemberIdProvider { MemberId(1L) },
        )

        assertNull(service.execute(PreviewStrategyCommand(StrategyId(1L), null, emptyList(), null, null, null)))
    }

    private fun existingStrategy(id: StrategyId) = StrategyReadModel(
        id = id,
        memberId = MemberId(1L),
        name = "preview fixture",
        createdAt = Instant.parse("2026-08-20T00:00:00Z"),
        latestVersionId = null,
        versions = emptyList(),
    )
}
