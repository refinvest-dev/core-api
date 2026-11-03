package com.refinvest

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class TradingPortBoundaryTest {
    @Test
    fun `trading contracts are organized by capability instead of mixed port files`() {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
        val port = root.resolve("tradingreview/port/src/main/kotlin/com/refinvest/core/tradingreview/port")
        listOf("TradingReviewPorts.kt", "TradingImportPorts.kt", "TradingNormalizationPorts.kt", "TradingAnalyticsInputPorts.kt").forEach {
            assertFalse(Files.exists(port.resolve(it)), "Mixed inbound/outbound contract file remains: $it")
        }
    }
}
