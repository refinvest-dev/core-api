package com.refinvest

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class TradingReviewModuleBoundaryTest {
    @Test
    fun `domain and application do not import framework or technology adapters`() {
        val root = workspaceRoot()
        val domain = imports(root.resolve("tradingreview/domain/src/main/kotlin"))
        val application = imports(root.resolve("tradingreview/application/src/main/kotlin"))
        val web = imports(root.resolve("tradingreview/adapter/web/src/main/kotlin"))
        assertTrue(domain.none { it.startsWith("org.springframework") || it.startsWith("jakarta.persistence") ||
            it.startsWith("jakarta.servlet") || it.contains(".adapter.") || it.contains(".backtest.") })
        val forbiddenApplication = application.filter { it.contains(".adapter.") || it.startsWith("jakarta.persistence") ||
            it.startsWith("jakarta.servlet") || it.contains(".backtest.") ||
            (it.contains(".compute.") && !it.startsWith("com.refinvest.core.tradingreview.port.normalization.compute.")) }
        assertTrue(forbiddenApplication.isEmpty(), "Forbidden application imports: $forbiddenApplication")
        val forbiddenWeb = web.filter { it.contains(".persistence.") || it.contains(".backtest.") ||
            it.contains(".compute.") }
        assertTrue(forbiddenWeb.isEmpty(), "Forbidden web imports: $forbiddenWeb")
    }

    private fun workspaceRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }

    private fun imports(directory: Path): List<String> = Files.walk(directory).use { paths ->
        paths.filter { it.toString().endsWith(".kt") }.flatMap { file ->
            Files.readAllLines(file).stream().filter { it.startsWith("import ") }
                .map { it.removePrefix("import ") }
        }.toList()
    }
}
