package com.refinvest

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class MemberSubscriptionModuleBoundaryTest {
    @Test
    fun `domain port and application keep their source boundaries`() {
        val root = workspaceRoot()
        listOf("member", "subscription").forEach { feature ->
            listOf("domain", "port", "application").forEach { layer ->
                val files = sources(root.resolve("$feature/$layer/src/main/kotlin"))
                assertTrue(files.isNotEmpty(), "Missing sources for $feature/$layer")
                files.forEach { file ->
                    val source = Files.readString(file)
                    val references = Regex("com\\.refinvest\\.core\\.[A-Za-z0-9_.]+").findAll(source).map { it.value }.toList()
                    val allowed = buildList {
                        addAll(listOf("com.refinvest.core.$feature.domain.", "com.refinvest.core.common.domain.", "com.refinvest.core.shared.kernel."))
                        if (layer != "domain") add("com.refinvest.core.$feature.port.")
                        if (layer == "application") {
                            add("com.refinvest.core.$feature.application.")
                            addAll(if (feature == "member") listOf("com.refinvest.core.subscription.port.")
                                else listOf("com.refinvest.core.backtest.port."))
                        }
                    }
                    assertTrue(references.all { reference -> allowed.any { reference.startsWith(it) || reference == it.removeSuffix(".") } },
                        "Forbidden reference in $file: $references")
                    assertTrue(!Regex("jakarta\\.(persistence|servlet)|org\\.springframework\\.(data|jdbc|orm|web|boot)").containsMatchIn(source),
                        "Technology reference in $file")
                    if (layer != "application") assertTrue(!source.contains("org.springframework"), "Framework reference in $file")
                }
            }
        }
    }

    @Test
    fun `persistence mappers only depend on aggregate and stored state`() {
        val root = workspaceRoot()
        listOf("member" to "Member", "subscription" to "Subscription").forEach { (feature, aggregate) ->
            val path = root.resolve("$feature/adapter/persistence/src/main/kotlin/com/refinvest/core/$feature/adapter/out/persistence/${aggregate}PersistenceMapper.kt")
            val source = Files.readString(path)
            assertTrue(!Regex("org\\.springframework|jakarta\\.persistence|java\\.(sql|net|io)|javax\\.sql|EntityManager|JpaStore|JpaReader|Clock|UUID").containsMatchIn(source),
                "Mapper must remain free of I/O, repositories, clocks and ID generation: $path")
        }
    }

    private fun workspaceRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }

    private fun sources(directory: Path): List<Path> = Files.walk(directory).use { paths ->
        paths.filter { it.toString().endsWith(".kt") }.toList()
    }
}
