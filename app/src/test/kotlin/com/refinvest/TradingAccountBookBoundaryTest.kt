package com.refinvest

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TradingAccountBookBoundaryTest {
    @Test
    fun tradingReviewSourcesRespectFeatureLayersAndKeepExistingSchedulerDebtExplicit() {
        val root = workspaceRoot()
        listOf("domain", "port", "application", "adapter/persistence", "adapter/web", "adapter/compute",
            "adapter/storage", "adapter/security", "adapter/snowflake").forEach { layer ->
            val files = Files.walk(root.resolve("tradingreview/$layer/src/main/kotlin")).use {
                it.filter { path -> path.toString().endsWith(".kt") }.toList()
            }
            assertTrue(files.isNotEmpty(), "Missing Trading Review sources for $layer")
            files.forEach { file ->
                val source = Files.readString(file)
                val references = Regex("com\\.refinvest\\.core\\.[A-Za-z0-9_.]+").findAll(source).map { it.value }.toList()
                val allowed = buildList {
                    addAll(listOf("com.refinvest.core.tradingreview.domain.", "com.refinvest.core.common.domain.", "com.refinvest.core.shared.kernel."))
                    if (layer != "domain") add("com.refinvest.core.tradingreview.port.")
                    if (layer == "application") add("com.refinvest.core.tradingreview.application.")
                    if (layer.startsWith("adapter/")) add("com.refinvest.core.tradingreview.adapter.${layer.substringAfter('/') }.")
                    if (layer == "adapter/security") add("com.refinvest.core.member.port.")
                    if (layer == "adapter/snowflake") add("com.refinvest.core.shared.infrastructure.")
                }
                // R10: stage 5a will replace these two existing scheduler implementation dependencies with inbound ports.
                val pendingSchedulers = if (layer == "adapter/compute") setOf(
                    "com.refinvest.core.tradingreview.application.TradingNormalizationDispatcher",
                    "com.refinvest.core.tradingreview.application.TradingNormalizationResultProcessor") else emptySet()
                assertTrue(references.all { ref -> allowed.any { ref.startsWith(it) || ref == it.removeSuffix(".") } ||
                    pendingSchedulers.any { ref == it || ref.startsWith("$it.") } }, "Forbidden reference in $file: $references")
                if (layer in setOf("domain", "port")) assertFalse(source.contains("org.springframework"), "Framework in $file")
                if (!layer.startsWith("adapter/")) assertFalse(Regex("jakarta\\.(persistence|servlet)|org\\.springframework\\.(data|jdbc|orm|web|boot)").containsMatchIn(source), "Technology in $file")
            }
        }
    }

    @Test
    fun aggregateMappersAndPortsArePureAndPersistenceCapabilitiesStaySeparate() {
        val root = workspaceRoot()
        val persistence = root.resolve("tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence")
        listOf("TradingAccount", "TradingBook").forEach { aggregate ->
            val mapper = Files.readString(persistence.resolve("${aggregate}PersistenceMapper.kt"))
            assertFalse(Regex("org\\.springframework|java\\.(sql|net|io)|JdbcTemplate|Clock|randomUUID|Instant\\.now|\\.create\\(|\\.rename\\(|\\.requestDeletion\\(").containsMatchIn(mapper))
            assertTrue(mapper.contains("$aggregate.restore("))
            val adapter = Files.readString(persistence.resolve("Jdbc${aggregate}PersistenceAdapter.kt"))
            assertTrue(adapter.contains("${aggregate}PersistenceMapper.toDomain("))
            val foreign = if (aggregate == "TradingAccount") "TradingBookStore" else "TradingAccountStore"
            assertFalse(adapter.contains(foreign) || adapter.contains("TradingIdempotencyStore") || adapter.contains("TradingDeletionStore"))
        }
        assertFalse(Files.exists(persistence.resolve("JdbcTradingReviewStore.kt")))
        listOf("account", "book").forEach { aggregate ->
            Files.walk(root.resolve("tradingreview/port/src/main/kotlin/com/refinvest/core/tradingreview/port/$aggregate")).use { files ->
                files.filter { it.toString().endsWith(".kt") }.forEach { file ->
                    assertFalse(Regex("org\\.springframework|java\\.(sql|net|io)|java\\.nio\\.file|javax\\.crypto").containsMatchIn(Files.readString(file)))
                }
            }
        }
    }

    private fun workspaceRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }
}
