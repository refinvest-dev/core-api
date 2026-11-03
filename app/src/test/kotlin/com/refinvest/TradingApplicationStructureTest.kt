package com.refinvest

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

class TradingApplicationStructureTest {
    @Test
    fun `account book and import services each implement one use case in its feature package`() {
        val root = workspaceRoot()
        val prefix = "com.refinvest.core.tradingreview"
        val contracts = kotlinClasses(root.resolve("tradingreview/port/src/main/kotlin"))
            .filter { it.isInterface && it.simpleName.endsWith("UseCase") &&
                listOf("account", "book", "importsession").any { feature ->
                    it.packageName.startsWith("$prefix.port.inbound.$feature.")
                } }
        assertEquals(14, contracts.size)
        val services = kotlinClasses(root.resolve("tradingreview/application/src/main/kotlin"))
            .filter { type -> contracts.any { it.isAssignableFrom(type) } }
        contracts.forEach { contract ->
            val service = services.single { contract.isAssignableFrom(it) }
            assertEquals(listOf(contract), service.interfaces.toList(), service.name)
            assertEquals(contract.packageName.replace(".port.inbound.", ".application."), service.packageName)
            assertTrue(service.constructors.single().parameterTypes.all {
                it.name.startsWith("$prefix.port.outbound.") ||
                    it == Clock::class.java || it == TransactionTemplate::class.java
            }, "Service must own orchestration through outbound ports: ${service.name}")
            val execute = service.declaredMethods.single { it.name == "execute" }
            val transaction = execute.getAnnotation(Transactional::class.java)
            if (contract.simpleName == "UploadTradingImportArtifactUseCase") {
                assertEquals(null, transaction, "File I/O must remain outside the short upload transaction")
                assertEquals(null, service.getAnnotation(Transactional::class.java))
            } else {
                assertTrue(transaction != null, "Missing transaction: ${service.name}")
                assertEquals(contract.simpleName.startsWith("Get") || contract.simpleName.startsWith("List"),
                    transaction.readOnly, service.name)
            }
        }
    }

    private fun kotlinClasses(root: Path): List<Class<*>> = Files.walk(root).use { files ->
        files.filter { it.toString().endsWith(".kt") }.flatMap { file ->
            val source = Files.readString(file)
            val pkg = Regex("(?m)^package ([\\w.]+)").find(source)!!.groupValues[1]
            Regex("(?m)^(?:(?:open|internal|data|sealed|fun) )*(?:class|interface) (\\w+)")
                .findAll(source).map { Class.forName("$pkg.${it.groupValues[1]}") }.toList().stream()
        }.toList()
    }

    private fun workspaceRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }
}
