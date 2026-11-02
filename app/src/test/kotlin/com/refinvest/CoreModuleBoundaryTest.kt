package com.refinvest

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreModuleBoundaryTest {
    @Test
    fun assetLayersKeepFrameworkAndImplementationBoundaries() {
        listOf("domain", "port", "application", "adapter/web", "adapter/compute").forEach { layer ->
            sources("asset/$layer/src/main/kotlin").forEach { file ->
                val source = Files.readString(file)
                val allowed = buildList {
                    add("com.refinvest.core.asset.domain.")
                    if (layer != "domain") add("com.refinvest.core.asset.port.")
                    if (layer == "application") add("com.refinvest.core.asset.application.")
                    if (layer.startsWith("adapter/")) add("com.refinvest.core.asset.adapter.")
                    if (layer == "adapter/compute") add("com.refinvest.core.shared.infrastructure.")
                }
                val references = Regex("com\\.refinvest\\.core\\.[A-Za-z0-9_.]+").findAll(source).map { it.value }
                assertTrue(references.all { ref -> allowed.any { ref.startsWith(it) || ref == it.removeSuffix(".") } }, "Forbidden reference in $file")
                if (layer in setOf("domain", "port")) assertFalse(source.contains("org.springframework"), "Framework in $file")
                if (!layer.startsWith("adapter/")) assertFalse(Regex("jakarta\\.(persistence|servlet)|org\\.springframework\\.(data|jdbc|orm|web|boot)").containsMatchIn(source), "Technology in $file")
            }
        }
    }

    @Test
    fun sharedModulesNeverDependOnFeatureCode() {
        listOf("kernel", "infrastructure").forEach { layer ->
            sources("shared/$layer/src/main/kotlin").forEach { file ->
                val source = Files.readString(file)
                val references = Regex("com\\.refinvest\\.core\\.[A-Za-z0-9_.]+").findAll(source).map { it.value }
                assertTrue(references.all { it.startsWith("com.refinvest.core.shared.") || it.startsWith("com.refinvest.core.common.domain.") || it == "com.refinvest.core.common.domain" }, "Feature reference in $file")
                if (layer == "kernel") assertFalse(Regex("org\\.springframework|jakarta\\.|java\\.(io|net|sql)|java\\.nio\\.file").containsMatchIn(source), "Technology in kernel: $file")
            }
        }
    }

    @Test
    fun everyPersistenceMapperTransformsLoadedStateWithoutIOOrNewBusinessActions() {
        listOf("member", "subscription", "auth", "strategy", "backtest", "tradingreview").forEach { feature ->
            val mappers = sources("$feature/adapter/persistence/src/main/kotlin").filter { it.fileName.toString().endsWith("PersistenceMapper.kt") }
            assertTrue(mappers.isNotEmpty(), "Missing persistence mapper for $feature")
            mappers.forEach { file ->
                assertFalse(Regex("org\\.springframework|java\\.(io|net|sql)|java\\.nio\\.file|javax\\.sql|JdbcTemplate|EntityManager|JpaStore|JpaReader|Clock|randomUUID|Instant\\.now|\\.create\\(")
                    .containsMatchIn(Files.readString(file)), "Mapper contains I/O, ID/time generation or a business action: $file")
            }
        }
    }

    private fun sources(relative: String): List<Path> = Files.walk(workspaceRoot().resolve(relative)).use { paths ->
        paths.filter { it.toString().endsWith(".kt") }.toList().also { assertTrue(it.isNotEmpty(), "Missing sources: $relative") }
    }

    private fun workspaceRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }
}
