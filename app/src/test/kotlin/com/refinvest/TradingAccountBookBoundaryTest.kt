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
            "adapter/storage", "adapter/security", "adapter/snowflake", "adapter/uuid").forEach { layer ->
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
        listOf("TradingAccount", "TradingBook", "TradingImportSession").forEach { aggregate ->
            val mapper = Files.readString(persistence.resolve("${aggregate}PersistenceMapper.kt"))
            assertFalse(Regex("org\\.springframework|java\\.(sql|net|io)|JdbcTemplate|Clock|randomUUID|Instant\\.now|\\.create\\(|\\.rename\\(|\\.requestDeletion\\(").containsMatchIn(mapper))
            assertTrue(mapper.contains("$aggregate.restore("))
            val adapter = Files.readString(persistence.resolve("Jdbc${aggregate}PersistenceAdapter.kt"))
            assertTrue(adapter.contains("${aggregate}PersistenceMapper.toDomain("))
            val foreign = if (aggregate == "TradingAccount") "TradingBookStore" else "TradingAccountStore"
            assertFalse(adapter.contains(foreign) || adapter.contains("TradingIdempotencyStore") || adapter.contains("TradingDeletionStore"))
        }
        assertFalse(Files.exists(persistence.resolve("JdbcTradingReviewStore.kt")))
        assertFalse(Files.exists(persistence.resolve("JdbcTradingImportStore.kt")))
        listOf("account", "book", "importsession", "normalization", "ledger", "deletion").forEach { aggregate ->
            Files.walk(root.resolve("tradingreview/port/src/main/kotlin/com/refinvest/core/tradingreview/port/$aggregate")).use { files ->
                files.filter { it.toString().endsWith(".kt") }.forEach { file ->
                    assertFalse(Regex("org\\.springframework|java\\.(sql|net|io)|java\\.nio\\.file|javax\\.crypto").containsMatchIn(Files.readString(file)))
                }
            }
        }
    }

    @Test
    fun importChildrenStayInsideRootAndCommandPortsAcceptAggregates() {
        val root = workspaceRoot()
        val domain = root.resolve("tradingreview/domain/src/main/kotlin/com/refinvest/core/tradingreview/domain")
        val source = Files.readString(domain.resolve("TradingImportSession.kt"))
        assertTrue(source.contains("class TradingImportSession private constructor("))
        assertTrue(source.contains("AggregateRoot<TradingImportSessionId>"))
        assertFalse(Regex("(?m)^    fun copy\\(").containsMatchIn(source))
        val child = Files.readString(domain.resolve("importsession/TradingSourceArtifact.kt"))
        assertTrue(child.contains("DomainEntity<TradingSourceArtifactId>"))
        assertFalse(child.contains("AggregateRoot") || child.contains("data class TradingSourceArtifact"))
        val store = Files.readString(root.resolve("tradingreview/port/src/main/kotlin/com/refinvest/core/tradingreview/port/importsession/TradingImportSessionStore.kt"))
        assertTrue(store.contains("update(session: TradingImportSession)"))
        val service = Files.readString(root.resolve("tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/TradingImportService.kt"))
        assertTrue(service.contains("session.recordUpload("))
        assertFalse(service.contains("artifacts.insert(") || service.contains("markDeletionPending("))
    }

    @Test
    fun normalizationCreationAndRestorationRespectTheOwningRootAndPureMapper() {
        val root = workspaceRoot()
        val domain = root.resolve("tradingreview/domain/src/main/kotlin/com/refinvest/core/tradingreview/domain")
        val run = Files.readString(domain.resolve("importsession/NormalizationRun.kt"))
        assertTrue(run.contains("class NormalizationRun private constructor("))
        assertTrue(run.contains("DomainEntity<NormalizationRunId>"))
        assertFalse(run.contains("AggregateRoot") || run.contains("fun copy("))
        val persistence = root.resolve("tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence")
        val mapper = Files.readString(persistence.resolve("NormalizationRunPersistenceMapper.kt"))
        assertTrue(mapper.contains("NormalizationRun.restore("))
        assertFalse(Regex("org\\.springframework|java\\.(sql|net|io)|JdbcTemplate|Clock|randomUUID|Instant\\.now|admitNormalization").containsMatchIn(mapper))
        assertFalse(Files.exists(persistence.resolve("JdbcTradingNormalizationStore.kt")))
        val application = Files.readString(root.resolve("tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/TradingNormalizationService.kt"))
        assertTrue(application.contains("session.admitNormalization("))
        assertTrue(application.contains("admissions.admit(NormalizationAdmission("))
        assertFalse(application.contains("runs.insert(") || application.contains("UUID.randomUUID"))
    }

    @Test
    fun ledgerPublicationUsesAnImmutableAggregateAndBoundedPureMapping() {
        val root = workspaceRoot()
        val domain = Files.readString(root.resolve("tradingreview/domain/src/main/kotlin/com/refinvest/core/tradingreview/domain/LedgerRevision.kt"))
        assertTrue(domain.contains("class LedgerRevision private constructor(") && domain.contains("AggregateRoot<LedgerRevisionId>"))
        assertFalse(domain.contains("fun copy(") || domain.contains("JdbcTemplate"))
        val persistence = root.resolve("tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence")
        val mapper = Files.readString(persistence.resolve("LedgerRevisionPersistenceMapper.kt"))
        assertTrue(mapper.contains("LedgerRevision.restore("))
        assertFalse(Regex("org\\.springframework|java\\.(sql|net|io)|JdbcTemplate|Clock|randomUUID|Instant\\.now|\\.publish\\(").containsMatchIn(mapper))
        val staging = Files.readString(persistence.resolve("JdbcTradingNormalizationResultStore.kt"))
        assertFalse(staging.contains("override fun publish(") || staging.contains("TradingLedgerPublicationReader"))
        val rows = Files.readString(persistence.resolve("LedgerRevisionRows.kt"))
        assertTrue(rows.contains("- 'reviewUnits'") && rows.contains("- 'validationIssues'"))
        val application = Files.readString(root.resolve("tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/TradingNormalizationResultProcessor.kt"))
        assertTrue(application.contains("LedgerRevision.prepare(") && application.contains("publications.publish(LedgerPublication("))
        assertFalse(application.contains("results.publish("))
    }

    @Test
    fun deletionRequestsUseATypedAggregateAndPureMapperInsteadOfPersistingInboundResults() {
        val root = workspaceRoot()
        val domain = Files.readString(root.resolve("tradingreview/domain/src/main/kotlin/com/refinvest/core/tradingreview/domain/DeletionRequest.kt"))
        assertTrue(domain.contains("class DeletionRequest private constructor(") && domain.contains("AggregateRoot<DeletionRequestId>"))
        assertFalse(domain.contains("fun copy(") || domain.contains("org.springframework"))
        val persistence = root.resolve("tradingreview/adapter/persistence/src/main/kotlin/com/refinvest/core/tradingreview/adapter/persistence")
        val mapper = Files.readString(persistence.resolve("DeletionRequestPersistenceMapper.kt"))
        assertTrue(mapper.contains("DeletionRequest.restore("))
        assertFalse(Regex("org\\.springframework|java\\.(sql|net|io)|JdbcTemplate|Clock|randomUUID|Instant\\.now|\\.request\\(").containsMatchIn(mapper))
        assertFalse(Files.exists(persistence.resolve("JdbcTradingDeletionStoreAdapter.kt")))
        val adapter = Files.readString(persistence.resolve("JdbcDeletionRequestPersistenceAdapter.kt"))
        assertTrue(adapter.contains("insert(request: DeletionRequest)") && adapter.contains("DeletionRequestPersistenceMapper.toDomain("))
        assertFalse(adapter.contains("TradingIdempotencyStore") || adapter.contains("TradingAccountStore"))
        listOf("TradingReviewService.kt", "TradingImportService.kt").forEach { name ->
            val source = Files.readString(root.resolve("tradingreview/application/src/main/kotlin/com/refinvest/core/tradingreview/application/$name"))
            assertTrue(source.contains("DeletionRequest.request(") && source.contains("TradingDeletion.fromDomain("))
            assertFalse(source.contains("val deletion = TradingDeletion(") || source.contains("DeletionRequest.restore("))
        }
        // Worker state CAS and persisted component progress are the next 4d-2 boundary.
    }

    private fun workspaceRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }
}
