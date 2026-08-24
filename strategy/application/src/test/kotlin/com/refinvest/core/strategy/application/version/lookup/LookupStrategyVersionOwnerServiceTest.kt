package com.refinvest.core.strategy.application.version.lookup

import com.refinvest.core.strategy.domain.valueobject.StrategyId
import com.refinvest.core.strategy.domain.valueobject.StrategyVersionId
import com.refinvest.core.strategy.port.inbound.version.lookup.LookupStrategyVersionOwnerQuery
import com.refinvest.core.strategy.port.outbound.persistence.version.StrategyVersionOwnerReadModel
import com.refinvest.core.strategy.port.outbound.persistence.version.StrategyVersionOwnerReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LookupStrategyVersionOwnerServiceTest {
    @Test
    fun `returns the strategy owning a version`() {
        val versionId = StrategyVersionId(20L)
        val service = LookupStrategyVersionOwnerService(
            StrategyVersionOwnerReader {
                StrategyVersionOwnerReadModel(it, StrategyId(30L))
            },
        )

        val result = service.execute(LookupStrategyVersionOwnerQuery(versionId))

        assertEquals(versionId, result?.strategyVersionId)
        assertEquals(StrategyId(30L), result?.strategyId)
    }

    @Test
    fun `returns null when the version does not exist`() {
        val service = LookupStrategyVersionOwnerService(StrategyVersionOwnerReader { null })

        assertNull(service.execute(LookupStrategyVersionOwnerQuery(StrategyVersionId(20L))))
    }
}
