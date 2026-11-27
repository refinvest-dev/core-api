package com.refinvest

import com.refinvest.core.tradingreview.adapter.compute.NormalizationResultSpoolSweeper
import com.refinvest.core.tradingreview.port.inbound.normalization.cleanup.CleanupTradingResultSpoolUseCase
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import java.util.function.Supplier
import kotlin.test.assertEquals

class LegacySpoolSelectionTest {
    @Test
    fun `spool sweeper preserves its default and can be explicitly suspended`() {
        for (selected in listOf(null, "true", "false")) {
            AnnotationConfigApplicationContext().use { context ->
                if (selected != null) context.environment.propertySources.addFirst(MapPropertySource("selected", mapOf(
                    "refinvest.trading-review.normalization.delivery.spool-sweep-enabled" to selected)))
                context.registerBean("cleanup", CleanupTradingResultSpoolUseCase::class.java,
                    Supplier { mock(CleanupTradingResultSpoolUseCase::class.java) })
                context.register(NormalizationResultSpoolSweeper::class.java)
                context.refresh()
                assertEquals(if (selected == "false") 0 else 1, context.getBeansOfType(NormalizationResultSpoolSweeper::class.java).size)
            }
        }
    }
}
