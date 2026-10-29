package com.refinvest

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class BacktestModuleBoundaryTest {
    @Test
    fun backtestLayersKeepFrameworkAndOtherFeatureBoundaries() {
        listOf("domain", "port", "application", "adapter/persistence", "adapter/compute", "adapter/web", "adapter/snowflake").forEach { layer ->
            val files = Files.walk(workspaceRoot().resolve("backtest/$layer/src/main/kotlin")).use {
                it.filter { path -> path.toString().endsWith(".kt") }.toList()
            }
            assertTrue(files.isNotEmpty(), "Missing Backtest sources for $layer")
            files.forEach { file ->
                val source = Files.readString(file)
                val references = Regex("com\\.refinvest\\.core\\.[A-Za-z0-9_.]+").findAll(source).map { it.value }.toList()
                val allowed = buildList {
                    addAll(listOf("com.refinvest.core.backtest.domain.", "com.refinvest.core.common.domain.", "com.refinvest.core.shared.kernel."))
                    if (layer != "domain") add("com.refinvest.core.backtest.port.")
                    if (layer == "application") addAll(listOf("com.refinvest.core.backtest.application.", "com.refinvest.core.strategy.port.", "com.refinvest.core.subscription.port."))
                    when (layer) {
                        "adapter/persistence" -> add("com.refinvest.core.backtest.adapter.out.persistence.")
                        "adapter/compute" -> addAll(listOf("com.refinvest.core.backtest.adapter.out.compute.", "com.refinvest.core.shared.infrastructure."))
                        "adapter/web" -> add("com.refinvest.core.backtest.adapter.web.")
                        "adapter/snowflake" -> addAll(listOf("com.refinvest.core.backtest.adapter.out.id.", "com.refinvest.core.shared.infrastructure."))
                    }
                }
                // Existing result/policy types are explicitly tracked until stage 3c.
                val pendingPortBoundaries = if (layer == "application") setOf("com.refinvest.core.strategy.domain.valueobject.StrategyId",
                    "com.refinvest.core.subscription.domain.policy.BacktestPolicy", "com.refinvest.core.subscription.domain.valueobject.SubscriptionTier") else emptySet()
                assertTrue(references.all { reference -> allowed.any { reference.startsWith(it) || reference == it.removeSuffix(".") } ||
                    pendingPortBoundaries.any { reference == it || reference.startsWith("$it.") } }, "Forbidden reference in $file: $references")
                if (layer in listOf("domain", "port")) assertTrue(!source.contains("org.springframework"), "Framework in $file")
                if (layer in listOf("domain", "port", "application")) assertTrue(!Regex("jakarta\\.(persistence|servlet)|org\\.springframework\\.(data|jdbc|orm|web|boot)").containsMatchIn(source), "Technology in $file")
            }
        }
    }

    @Test
    fun aggregateMappersOnlyTransformLoadedRecordsAndNeverReplayExecution() {
        val directory = workspaceRoot().resolve("backtest/adapter/persistence/src/main/kotlin/com/refinvest/core/backtest/adapter/out/persistence")
        listOf("BacktestRunPersistenceMapper.kt", "BacktestResultPersistenceMapper.kt", "BacktestRunReadModelMapper.kt", "BacktestResultPayloadMapper.kt").forEach { name ->
            val source = Files.readString(directory.resolve(name))
            assertTrue(!Regex("java\\.(sql|net|io)|EntityManager|JpaStore|JpaReader|Clock|randomUUID").containsMatchIn(source), "I/O or generated state in $name")
        }
        val aggregate = Files.readString(directory.resolve("BacktestRunPersistenceMapper.kt"))
        assertTrue(aggregate.contains("BacktestRun.restore("))
        assertTrue(listOf(".createPending(", ".start(", ".complete(", ".fail(").none(aggregate::contains))
    }

    private fun workspaceRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }
}
