package com.refinvest

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class StrategyModuleBoundaryTest {
    @Test
    fun strategySourcesRespectDomainPortApplicationAndTechnologyBoundaries() {
        listOf("domain", "port", "application", "adapter/persistence", "adapter/web", "adapter/snowflake").forEach { layer ->
            val sourceRoot = workspaceRoot().resolve("strategy/$layer/src/main/kotlin")
            val files = Files.walk(sourceRoot).use { it.filter { path -> path.toString().endsWith(".kt") }.toList() }
            assertTrue(files.isNotEmpty(), "Missing Strategy sources for $layer")
            files.forEach { file ->
                val source = Files.readString(file)
                val references = Regex("com\\.refinvest\\.core\\.[A-Za-z0-9_.]+").findAll(source).map { it.value }.toList()
                val allowed = buildList {
                    addAll(listOf("com.refinvest.core.strategy.domain.", "com.refinvest.core.common.domain.", "com.refinvest.core.shared.kernel."))
                    if (layer != "domain") add("com.refinvest.core.strategy.port.")
                    if (layer == "application") add("com.refinvest.core.strategy.application.")
                    when (layer) {
                        "adapter/persistence" -> add("com.refinvest.core.strategy.adapter.out.persistence.")
                        "adapter/web" -> add("com.refinvest.core.strategy.adapter.web.")
                        "adapter/snowflake" -> addAll(listOf("com.refinvest.core.strategy.adapter.out.id.", "com.refinvest.core.shared.infrastructure."))
                    }
                }
                assertTrue(references.all { reference -> allowed.any { reference.startsWith(it) || reference == it.removeSuffix(".") } },
                    "Forbidden dependency in $file: $references")
                if (layer in listOf("domain", "port")) assertTrue(!source.contains("org.springframework"), "Framework in $file")
                if (layer in listOf("domain", "port", "application")) assertTrue(!Regex("jakarta\\.(persistence|servlet)|org\\.springframework\\.(data|jdbc|orm|web|boot)").containsMatchIn(source),
                    "Technology dependency in $file")
            }
        }
    }

    @Test
    fun strategyMappersOnlyConvertLoadedDataAndUseExplicitRestoration() {
        val path = workspaceRoot().resolve("strategy/adapter/persistence/src/main/kotlin/com/refinvest/core/strategy/adapter/out/persistence")
        listOf("StrategyPersistenceMapper.kt", "StrategyReadModelMapper.kt").forEach { name ->
            val source = Files.readString(path.resolve(name))
            assertTrue(!Regex("org\\.springframework|jakarta\\.persistence|java\\.(sql|net|io)|EntityManager|JpaStore|JpaReader|Clock|randomUUID").containsMatchIn(source),
                "Mapper must not perform I/O or generate identities: $name")
        }
        val persistence = Files.readString(path.resolve("StrategyPersistenceMapper.kt"))
        assertTrue(persistence.contains("Strategy.restore(") && persistence.contains("StrategyVersion.restore("))
        assertTrue(!persistence.contains(".create(") && !persistence.contains(".addVersion("), "Restoration must not replay domain commands")
    }

    private fun workspaceRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }
}
