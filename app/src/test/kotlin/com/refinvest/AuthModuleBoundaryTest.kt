package com.refinvest

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class AuthModuleBoundaryTest {
    @Test
    fun `auth source dependencies keep domain port application and persistence separate`() {
        val root = workspaceRoot()
        listOf("domain", "port", "application", "adapter/persistence").forEach { layer ->
            val sourceRoot = root.resolve("auth/$layer/src/main/kotlin")
            val files = Files.walk(sourceRoot).use { it.filter { path -> path.toString().endsWith(".kt") }.toList() }
            assertTrue(files.isNotEmpty(), "Missing Auth sources for $layer")
            files.forEach { file ->
                val source = Files.readString(file)
                val references = Regex("com\\.refinvest\\.core\\.[A-Za-z0-9_.]+").findAll(source).map { it.value }.toList()
                val allowed = buildList {
                    addAll(listOf("com.refinvest.core.auth.domain.", "com.refinvest.core.common.domain.", "com.refinvest.core.shared.kernel."))
                    if (layer != "domain") add("com.refinvest.core.auth.port.")
                    if (layer == "application") {
                        addAll(listOf("com.refinvest.core.auth.application.", "com.refinvest.core.member.port."))
                        // Existing cross-feature result type; removal belongs to stage 3c.
                        add("com.refinvest.core.member.domain.valueobject.MemberRole")
                    }
                    if (layer == "adapter/persistence") add("com.refinvest.core.auth.adapter.out.persistence.")
                }
                assertTrue(references.all { reference -> allowed.any { reference.startsWith(it) || reference == it.removeSuffix(".") } },
                    "Forbidden reference in $file: $references")
                if (layer in listOf("domain", "port")) assertTrue(!source.contains("org.springframework"), "Framework in $file")
                if (layer != "adapter/persistence") assertTrue(!Regex("jakarta\\.(persistence|servlet)|org\\.springframework\\.(data|jdbc|orm|web|boot)").containsMatchIn(source),
                    "Technology dependency in $file")
            }
        }
    }

    @Test
    fun `auth mappers restore aggregates without repositories time or credential generation`() {
        listOf("RefreshSession", "SocialIdentity").forEach { aggregate ->
            val path = workspaceRoot().resolve("auth/adapter/persistence/src/main/kotlin/com/refinvest/core/auth/adapter/out/persistence/${aggregate}PersistenceMapper.kt")
            val source = Files.readString(path)
            assertTrue(!Regex("org\\.springframework|jakarta\\.persistence|java\\.(sql|net|io)|javax\\.sql|EntityManager|JpaStore|Clock|randomUUID|\\.create\\(").containsMatchIn(source),
                "Mapper must only convert the supplied state: $path")
            assertTrue(source.contains(".restore("), "Mapper must use explicit aggregate restoration: $path")
        }
    }

    private fun workspaceRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }
}
