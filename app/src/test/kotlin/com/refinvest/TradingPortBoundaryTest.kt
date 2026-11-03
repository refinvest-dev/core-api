package com.refinvest

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.lang.reflect.Type
import java.lang.reflect.ParameterizedType
import java.lang.reflect.WildcardType
import java.lang.reflect.TypeVariable
import com.refinvest.core.common.domain.DomainEntity

class TradingPortBoundaryTest {
    @Test
    fun `port packages express direction and keep contract models in their owner`() {
        val root = portRoot()
        Files.walk(root).use { files ->
            files.filter { it.toString().endsWith(".kt") }.forEach { file ->
                val relative = root.relativize(file).toString().replace('\\', '/')
                assertTrue(relative.startsWith("inbound/") || relative.startsWith("outbound/") ||
                    relative in setOf("common/failure/TradingFailure.kt", "common/failure/TradingFailureKind.kt"),
                    "Port has no explicit owner: $relative")
                val expected = "com.refinvest.core.tradingreview.port." + relative.substringBeforeLast('/').replace('/', '.')
                val source = Files.readString(file)
                assertEquals(expected, Regex("(?m)^package ([\\w.]+)").find(source)!!.groupValues[1], relative)
                val opposite = if (relative.startsWith("inbound/")) "outbound" else if (relative.startsWith("outbound/")) "inbound" else null
                if (opposite != null) assertFalse(source.contains("com.refinvest.core.tradingreview.port.$opposite."),
                    "Cross-direction contract dependency: $relative")
            }
        }
    }

    @Test
    fun `each contract file has one top level concept`() {
        Files.walk(portRoot()).use { files ->
            files.filter { it.toString().endsWith(".kt") }.forEach { file ->
                val declarations = Regex("(?m)^(?:(?:data|sealed|fun|internal|enum)\\s+)*(?:class|interface|object)\\s+(\\w+)")
                    .findAll(Files.readString(file)).map { it.groupValues[1] }.toList()
                assertEquals(listOf(file.fileName.toString().removeSuffix(".kt")), declarations, file.toString())
            }
        }
    }

    private fun portRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }
        .resolve("tradingreview/port/src/main/kotlin/com/refinvest/core/tradingreview/port")

    @Test
    fun `public business ports accept one CQR model and return entity-free result graphs`() {
        val operations = mapOf(
            "account" to listOf("create/CreateTradingAccount", "get/GetTradingAccount", "list/ListTradingAccounts", "delete/DeleteTradingAccount"),
            "book" to listOf("create/CreateTradingBook", "get/GetTradingBook", "list/ListTradingBooks", "delete/DeleteTradingBook"),
            "importsession" to listOf("create/CreateTradingImportSession", "get/GetTradingImportSession", "list/ListTradingImports", "upload/UploadTradingImportArtifact", "delete/DeleteTradingImportSession", "deleteartifact/DeleteTradingSourceArtifact"),
            "normalization" to listOf("validate/ValidateTradingImportSession", "retry/RetryTradingImportSession"),
        )
        for ((feature, names) in operations) for (name in names) {
            val port = Class.forName("com.refinvest.core.tradingreview.port.inbound.$feature.${name.replace('/', '.')}UseCase")
            val method = port.declaredMethods.single()
            assertEquals("execute", method.name)
            assertEquals(1, method.parameterCount)
            val input = method.parameterTypes.single()
            assertEquals(true, input.simpleName.endsWith("Command") || input.simpleName.endsWith("Query"), port.name)
            checkResult(method.genericReturnType, mutableSetOf())
        }
    }

    private fun checkResult(type: Type, visited: MutableSet<Type>) {
        if (!visited.add(type)) return
        if (type is ParameterizedType) {
            type.actualTypeArguments.forEach { checkResult(it, visited) }
            checkResult(type.rawType, visited)
        } else if (type is WildcardType) {
            (type.upperBounds + type.lowerBounds).forEach { checkResult(it, visited) }
        } else if (type is TypeVariable<*>) {
            type.bounds.forEach { checkResult(it, visited) }
        } else if (type is Class<*>) {
            assertFalse(DomainEntity::class.java.isAssignableFrom(type), "Domain entity in inbound result: $type")
            if (type.packageName.startsWith("com.refinvest.core.tradingreview.port")) {
                type.declaredMethods.filter { it.name.startsWith("get") && it.parameterCount == 0 }
                    .forEach { checkResult(it.genericReturnType, visited) }
            }
        }
    }

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
