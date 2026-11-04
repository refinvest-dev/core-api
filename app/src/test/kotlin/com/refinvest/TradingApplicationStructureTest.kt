package com.refinvest

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

class TradingApplicationStructureTest {
    @Test
    fun `resource controllers stay in separate packages and share the safe failure advice`() {
        val sourceRoot = workspaceRoot().resolve("tradingreview/adapter/web/src/main/kotlin")
        val types = kotlinClasses(sourceRoot)
        val controllers = types.filter { it.isAnnotationPresent(RestController::class.java) }
        assertEquals(8, controllers.size)
        controllers.forEach { controller ->
            assertTrue(controller.packageName != "com.refinvest.core.tradingreview.adapter.web", controller.name)
            assertTrue(controller.constructors.single().parameterTypes.all {
                it.name.startsWith("com.refinvest.core.tradingreview.port.inbound.") ||
                    it.name == "tools.jackson.databind.ObjectMapper"
            }, controller.name)
            val file = sourceRoot.resolve(controller.name.replace('.', '/') + ".kt")
            assertTrue(Files.exists(file), "Each resource controller must have its own source file: $controller")
        }
        val advice = types.single { it.isAnnotationPresent(RestControllerAdvice::class.java) }
            .getAnnotation(RestControllerAdvice::class.java)
        assertEquals(controllers.toSet(), advice.assignableTypes.map { it.java }.toSet())
    }

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

    @Test
    fun `normalization entry points own distinct use cases and share only admission support`() {
        val root = workspaceRoot()
        val prefix = "com.refinvest.core.tradingreview"
        val contracts = kotlinClasses(root.resolve("tradingreview/port/src/main/kotlin"))
            .filter { it.simpleName in setOf("ValidateTradingImportSessionUseCase", "RetryTradingImportSessionUseCase") }
        assertEquals(2, contracts.size)
        val application = kotlinClasses(root.resolve("tradingreview/application/src/main/kotlin"))
        val services = application.filter { type -> contracts.any { it.isAssignableFrom(type) } }
        assertEquals(2, services.size)
        contracts.forEach { contract ->
            val service = services.single { contract.isAssignableFrom(it) }
            assertEquals(listOf(contract), service.interfaces.toList(), service.name)
            assertEquals(contract.packageName.replace(".port.inbound.", ".application."), service.packageName)
            assertTrue(service.constructors.single().parameterTypes.all {
                it.name.startsWith("$prefix.port.outbound.") || it == Clock::class.java ||
                    it.name in setOf("$prefix.application.normalization.NormalizationAdmissionGuard",
                        "$prefix.application.normalization.NormalizationAdmissionFactory")
            }, "Use cases must own orchestration without delegating to another service: ${service.name}")
            val transaction = service.declaredMethods.single { it.name == "execute" }
                .getAnnotation(Transactional::class.java)
            assertTrue(transaction != null && !transaction.readOnly, service.name)
        }
        assertTrue(application.none { it.simpleName == "TradingNormalizationService" })
        val factory = application.single { it.simpleName == "NormalizationAdmissionFactory" }
        assertTrue(factory.interfaces.isEmpty())
        assertTrue(factory.constructors.single().parameterTypes.none {
            it == Clock::class.java || it.name.contains(".persistence.") || it.name.contains(".idempotency.")
        }, "Input creation must not lock, store, claim idempotency, or choose the current time")
    }

    @Test
    fun `normalization dispatch and poll own separate orchestration and short transaction boundaries`() {
        val root = workspaceRoot()
        val prefix = "com.refinvest.core.tradingreview"
        val contracts = kotlinClasses(root.resolve("tradingreview/port/src/main/kotlin"))
            .filter { it.simpleName in setOf("DispatchTradingNormalizationUseCase", "PollTradingNormalizationUseCase") }
        assertEquals(2, contracts.size)
        val application = kotlinClasses(root.resolve("tradingreview/application/src/main/kotlin"))
        val workers = application.filter { type -> contracts.any { it.isAssignableFrom(type) } }
        assertEquals(2, workers.size)
        contracts.forEach { contract ->
            val worker = workers.single { contract.isAssignableFrom(it) }
            assertEquals(listOf(contract), worker.interfaces.toList(), worker.name)
            assertEquals(contract.packageName.replace(".port.inbound.", ".application."), worker.packageName)
            assertEquals(null, worker.getAnnotation(Transactional::class.java), worker.name)
            assertTrue(worker.declaredMethods.none { it.isAnnotationPresent(Transactional::class.java) }, worker.name)
            assertTrue(worker.constructors.single().parameterTypes.all {
                it.name.startsWith("$prefix.port.outbound.") || it == Clock::class.java ||
                    it == TransactionTemplate::class.java || it.name == "tools.jackson.databind.ObjectMapper" ||
                    it.name in setOf("$prefix.application.normalization.NormalizationRetrySchedule",
                        "$prefix.application.normalization.NormalizationWorkerLifecycle")
            }, worker.name)
            val source = Files.readString(root.resolve("tradingreview/application/src/main/kotlin")
                .resolve(worker.name.replace('.', '/') + ".kt"))
            assertEquals(1, Regex("UUID\\.randomUUID\\(\\)").findAll(source).count(),
                "Only the per-claim lease owner is generated locally: ${worker.name}")
        }
        assertTrue(application.none { it.simpleName == "TradingNormalizationDispatcher" })
        val schedule = application.single { it.simpleName == "NormalizationRetrySchedule" }
        assertTrue(schedule.constructors.single().parameterTypes.none {
            it == Clock::class.java || it.name.contains(".persistence.") || it.name.contains(".compute.")
        }, "Pinned retry calculation must not choose a time or perform I/O")
    }

    @Test
    fun `artifact grants separate persistence signing and storage with suspended caller transactions`() {
        val root = workspaceRoot()
        val prefix = "com.refinvest.core.tradingreview"
        val application = kotlinClasses(root.resolve("tradingreview/application/src/main/kotlin"))
        val security = kotlinClasses(root.resolve("tradingreview/adapter/security/src/main/kotlin"))
        val persistenceRoot = root.resolve("tradingreview/adapter/persistence/src/main/kotlin")
        val persistence = kotlinClasses(persistenceRoot)
        for ((name, contract) in listOf("JdbcArtifactGrantIssueStore" to "ArtifactGrantIssueStore",
                "JdbcArtifactGrantRedemptionStore" to "ArtifactGrantRedemptionStore")) {
            val type = persistence.single { it.simpleName == name }
            assertEquals(listOf("$prefix.port.outbound.persistence.artifactgrant.$contract"), type.interfaces.map { it.name })
            val source = Files.readString(persistenceRoot.resolve(type.name.replace('.', '/') + ".kt"))
            assertTrue(!Regex("RawArtifactStorage|java\\.security|javax\\.crypto|InputStream|Clock|@Value")
                .containsMatchIn(source), "Persistence must only implement its database capability: $name")
        }
        assertTrue(persistence.none { it.simpleName == "JdbcArtifactAccessGrants" })
        val tokens = security.single { it.simpleName == "HmacArtifactGrantTokens" }
        assertTrue(tokens.constructors.single().parameterTypes.all { it == String::class.java })
        val redemption = application.single { it.simpleName == "RedeemTradingArtifactGrantService" }
        assertEquals("$prefix.application.artifactgrant.redeem", redemption.packageName)
        assertEquals(listOf("$prefix.port.inbound.artifactgrant.redeem.RedeemTradingArtifactGrantUseCase"),
            redemption.interfaces.map { it.name })
        val issuer = security.single { it.simpleName == "SignedArtifactAccessGrantIssuer" }
        for ((type, operation) in listOf(redemption to "execute", issuer to "issueReadGrant")) {
            assertTrue(type.constructors.single().parameterTypes.all {
                it.name.startsWith("$prefix.port.outbound.") || it == Clock::class.java
            }, type.name)
            val transaction = type.declaredMethods.single { it.name.startsWith(operation) }
                .getAnnotation(Transactional::class.java)
            assertEquals(org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED,
                transaction?.propagation, "External storage must suspend the caller transaction: ${type.name}")
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
