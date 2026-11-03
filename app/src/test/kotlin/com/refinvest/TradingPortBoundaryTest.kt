package com.refinvest

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import java.lang.reflect.Type
import java.lang.reflect.ParameterizedType
import java.lang.reflect.WildcardType
import java.lang.reflect.TypeVariable
import com.refinvest.core.common.domain.DomainEntity

class TradingPortBoundaryTest {
    @Test
    fun `public business ports accept one CQR model and return entity-free result graphs`() {
        val operations = mapOf(
            "account" to listOf("create/CreateTradingAccount", "get/GetTradingAccount", "list/ListTradingAccounts", "delete/DeleteTradingAccount"),
            "book" to listOf("create/CreateTradingBook", "get/GetTradingBook", "list/ListTradingBooks", "delete/DeleteTradingBook"),
            "importsession" to listOf("create/CreateTradingImportSession", "get/GetTradingImportSession", "list/ListTradingImports", "upload/UploadTradingImportArtifact", "delete/DeleteTradingImportSession", "deleteartifact/DeleteTradingSourceArtifact"),
            "normalization" to listOf("validate/ValidateTradingImportSession", "retry/RetryTradingImportSession"),
        )
        for ((feature, names) in operations) for (name in names) {
            val port = Class.forName("com.refinvest.core.tradingreview.port.$feature.${name.replace('/', '.')}UseCase")
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
