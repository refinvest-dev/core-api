package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.port.IdempotencyClaim
import com.refinvest.core.tradingreview.port.TradingFailure
import com.refinvest.core.tradingreview.port.TradingDeletion
import com.refinvest.core.tradingreview.port.TradingIdGenerator
import com.refinvest.core.tradingreview.port.TradingMemberProvider
import com.refinvest.core.tradingreview.port.*
import com.refinvest.core.tradingreview.application.TradingReviewService
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@EnabledIfEnvironmentVariable(named = "TRADING_REVIEW_TEST_JDBC_URL", matches = ".+")
class PostgresTradingReviewStoreTest {
    private val deliveryPolicy = NormalizationResultDeliveryPolicy(
        "RESULT_DELIVERY_V1", "SINGLE_RESOURCE", 52_428_800, listOf("identity"))
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val noPublished = object : TradingLedgerPublicationReader {
        override fun publishedForSession(sessionId: TradingImportSessionId, owner: MemberId): PublishedLedgerSummary? = null
    }
    private val normalizationVersions = """{"versionSetHash":"${"f".repeat(64)}","normalizer":{"sourceDialectVersionsByArtifactRole":{"TRADE_HISTORY":"TRADE_V1","POSITION_HISTORY":"POSITION_V1"},"adapterVersion":"ADAPTER_V1","normalizerVersion":"NORMALIZER_V1","canonicalSchemaVersion":"CANONICAL_V1","implementationDigest":"normalizer-digest","versionSetHash":"${"d".repeat(64)}"},"reconstruction":{"instrumentTermsVersionByInstrument":{},"reconstructionPolicyVersion":"RECONSTRUCTION_V1","reconciliationVersion":"RECONCILIATION_V1","reviewUnitSchemaVersion":"REVIEW_UNIT_V1","evidenceSchemaVersion":"EVIDENCE_V1","implementationDigest":"reconstruction-digest","versionSetHash":"${"e".repeat(64)}"}}"""

    @Test
    fun verifiedTerminalPayloadPublishesOnceAndAcknowledgesAfterCommit() = withDatabase { connection ->
        val source = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(source)
        val transactions = TransactionTemplate(DataSourceTransactionManager(source))
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        val canonicalizer = com.refinvest.core.tradingreview.application.NormalizationResultCanonicalizer
        members(jdbc)
        val review = JdbcTradingReviewStore(jdbc)
        val account = TradingAccount.create(TradingAccountId(971), MemberId(1), TradingVenue.BINANCE, "A", now)
        review.insert(account)
        val book = TradingBook.create(TradingBookId(972), account, "B",
            TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY,
            TradingSettlementAsset.USDT, "UTC", now)
        review.insert(book)
        val fixture = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .map { java.io.File(it,
                "tradingreview/application/src/test/resources/trading-normalization/result-integrity-v1.json") }
            .first { it.isFile }
        @Suppress("UNCHECKED_CAST")
        val vector = mapper.readValue(fixture.readText(), Map::class.java) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val payload = mapper.readValue(mapper.writeValueAsString(vector.getValue("terminalPayload")),
            Map::class.java) as MutableMap<String, Any?>
        val versions = mapper.writeValueAsString(payload.getValue("targetVersionSet"))
        val imports = JdbcTradingImportStore(jdbc)
        val session = TradingImportSession.create(TradingImportSessionId(973), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), versions, now)
        imports.insert(session)
        val inputs = TradingArtifactRole.entries.mapIndexed { index, role ->
            val id = 974L + index
            val sha = (if (role == TradingArtifactRole.TRADE_HISTORY) "a" else "b").repeat(64)
            imports.insert(TradingSourceArtifact(TradingSourceArtifactId(id), session.id, role,
                null, "text/csv", 20, sha, "AUTO", "objects/" + UUID.randomUUID(),
                byteArrayOf(1), "test-v1", TradingArtifactRetentionStatus.ACTIVE,
                now, now.plusSeconds(3600), null, null, 0))
            NormalizationArtifactInput(TradingSourceArtifactId(id), role, sha, 20, "text/csv", "AUTO")
        }
        val input = NormalizationInput(book.id, session.id, 1, inputs, "BINANCE",
            "LINEAR_PERPETUAL", "ONE_WAY", "USDT", "UTC", "UTC", versions, "[]",
            "TRADING_DATA_POLICY_V1", "retry-v1",
            """{"policyVersion":"retry-v1","maxAttempts":3,"maxElapsedTime":"PT1H","initialDelay":"PT1S","maxDelay":"PT10S","jitter":"NONE"}""", 0)
        val identity = NormalizationInputIdentity.fromPinnedInput(input,
            mapper.readValue(versions, Map::class.java))
        val runId = UUID.randomUUID().toString()
        val attemptToken = UUID.randomUUID().toString()
        val run = NormalizationRun(runId, input, NormalizationInputCanonicalizer.hash(identity),
            "b".repeat(64), NormalizationRunStatus.DISPATCH_PENDING, 1, attemptToken,
            null, null, now, now, null, now, null, 0, inputIdentity = identity,
            resultDeliveryPolicy = deliveryPolicy)
        val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId, "c".repeat(64),
            NormalizationDispatchStatus.PENDING, 0, now, null, null, null, now, now)
        val runs = JdbcTradingNormalizationStore(jdbc, mapper)
        runs.insert(run, dispatch)
        assertEquals(deliveryPolicy, runs.findRun(runId)?.resultDeliveryPolicy)
        assertTrue(imports.update(session.beginValidation(runId, now)))
        jdbc.update("UPDATE trading_normalization_runs SET status='RESULT_READY',compute_job_id='job-synthetic-1' WHERE id=?::uuid", runId)
        jdbc.update("UPDATE trading_normalization_dispatches SET status='RESULT_READY' WHERE id=?::uuid", dispatch.id)

        payload["coreNormalizationRunId"] = runId
        payload["coreLogicalJobId"] = dispatch.id
        payload["coreImportSessionId"] = session.id.value.toString()
        payload["attemptToken"] = attemptToken
        payload["deletionGeneration"] = 0
        payload["inputSetHash"] = run.inputFingerprint
        payload["targetVersionSet"] = identity.targetVersionSet.toWireValue()
        @Suppress("UNCHECKED_CAST")
        val evidence = payload.getValue("sourceEvidenceSnapshots") as MutableList<MutableMap<String, Any?>>
        evidence.single()["coreArtifactId"] = inputs.single { it.role == TradingArtifactRole.TRADE_HISTORY }
            .artifactId.value.toString()
        @Suppress("UNCHECKED_CAST")
        val manifest = payload.getValue("reconciliationManifest") as MutableMap<String, Any?>
        manifest["inputSetHash"] = run.inputFingerprint
        @Suppress("UNCHECKED_CAST")
        val digests = payload.getValue("sectionDigests") as MutableList<MutableMap<String, Any?>>
        digests.single { it["sectionType"] == "SOURCE_EVIDENCE_SNAPSHOTS" }["sectionHash"] =
            canonicalizer.hash(evidence.map { it - "capturedAt" })
        val manifestHash = canonicalizer.hash(manifest - "manifestHash")
        manifest["manifestHash"] = manifestHash
        digests.single { it["sectionType"] == "RECONCILIATION_MANIFEST" }["sectionHash"] = manifestHash
        @Suppress("UNCHECKED_CAST")
        val digestManifest = payload.getValue("resultDigestManifest") as MutableMap<String, Any?>
        digestManifest["inputSetHash"] = run.inputFingerprint
        digestManifest["targetVersionSet"] = identity.targetVersionSet.toWireValue()
        digestManifest["sectionDigests"] = digests
        digestManifest["reconciliationManifestHash"] = manifestHash
        val resultHash = canonicalizer.hash(digestManifest)
        payload["resultHash"] = resultHash
        val bytes = mapper.writeValueAsBytes(payload)
        val transportHash = java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
        @Suppress("UNCHECKED_CAST")
        val descriptor = NormalizationResultDescriptor("job-synthetic-1", dispatch.id, runId,
            "payload-synthetic-1", bytes.size.toLong(), transportHash, attemptToken, 0,
            run.inputFingerprint, identity.hashSpecificationVersion,
            identity.targetVersionSet.toWireValue(), "TRADING_NORMALIZATION_RESULT_V1",
            "TRADING_NORMALIZATION_RESULT_HASH_V1", payload.getValue("counts") as Map<String, Any?>,
            digests, manifestHash, resultHash, now.plusSeconds(3600), "RESULT_DELIVERY_V1", "identity")
        var acknowledgeCalls = 0
        var descriptorCalls = 0
        var downloadCalls = 0
        val client = object : TradingNormalizationResultClient {
            override fun descriptor(jobId: String): NormalizationResultLookup {
                descriptorCalls++
                check(descriptorCalls == 1) { "Completed staging must not fetch Compute again" }
                return NormalizationResultLookup.Found(descriptor)
            }
            override fun download(descriptor: NormalizationResultDescriptor,
                                  policy: NormalizationResultDeliveryPolicy): NormalizationPayloadLookup {
                downloadCalls++
                check(downloadCalls == 1) { "Completed staging must not download again" }
                val path = java.nio.file.Files.createTempFile("synthetic-result-", ".json")
                java.nio.file.Files.write(path, bytes)
                return NormalizationPayloadLookup.Found(
                    NormalizationPayloadFile(path, bytes.size.toLong(), transportHash))
            }
            override fun acknowledge(acknowledgement: NormalizationPayloadAcknowledgement): NormalizationAckOutcome {
                acknowledgeCalls++
                assertEquals("PERSISTED", acknowledgement.disposition)
                assertEquals(TradingImportStatus.ACCEPTED, imports.find(session.id, MemberId(1))?.status)
                return NormalizationAckOutcome.Accepted
            }
        }
        val results = JdbcTradingNormalizationResultStore(jdbc, mapper)
        val interrupted = object : TradingNormalizationResultStore by results {
            override fun publish(run: NormalizationRun, dispatch: NormalizationDispatch, workerId: String,
                                 descriptor: NormalizationResultDescriptor, manifestJson: String,
                                 coverageJson: String, capabilityJson: String, qualityJson: String,
                                 ledgerContentHash: String, now: Instant): Boolean =
                error("simulated process failure before publication commit")
        }
        val processor = com.refinvest.core.tradingreview.application.TradingNormalizationResultProcessor(
            runs, imports, review, interrupted, client, transactions, Clock.fixed(now, ZoneOffset.UTC), mapper,
            object : TradingRetryJitter { override fun factor() = 0.5 })
        assertTrue(processor.processOne())
        assertEquals(TradingImportStatus.VALIDATING, imports.find(session.id, MemberId(1))?.status)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_result_staging", Long::class.java))
        val recoveredProcessor = com.refinvest.core.tradingreview.application.TradingNormalizationResultProcessor(
            runs, imports, review, results, client, transactions,
            Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC), mapper,
            object : TradingRetryJitter { override fun factor() = 0.5 })
        assertTrue(recoveredProcessor.processOne())
        assertEquals(1, descriptorCalls)
        assertEquals(1, downloadCalls)
        assertEquals(TradingImportStatus.ACCEPTED, imports.find(session.id, MemberId(1))?.status)
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
        assertEquals("PUBLISHED", jdbc.queryForObject(
            "SELECT status FROM trading_normalization_runs WHERE id=?::uuid", String::class.java, runId))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_ack_dispatches", Long::class.java))
        assertFalse(recoveredProcessor.processOne())
        // Simulate Compute accepting an ACK immediately before Core loses its lease/state update.
        val lostWorker = UUID.randomUUID().toString()
        val pending = assertNotNull(transactions.execute { results.claimAck(now.plusSeconds(1),
            java.time.Duration.ofSeconds(45), lostWorker) })
        assertEquals(NormalizationAckOutcome.Accepted, client.acknowledge(pending))
        val ackRecovered = com.refinvest.core.tradingreview.application.TradingNormalizationResultProcessor(
            runs, imports, review, results, client, transactions,
            Clock.fixed(now.plusSeconds(47), ZoneOffset.UTC), mapper,
            object : TradingRetryJitter { override fun factor() = 0.5 })
        assertTrue(ackRecovered.acknowledgeOne())
        assertEquals(2, acknowledgeCalls)
        assertEquals("ACKNOWLEDGED", jdbc.queryForObject(
            "SELECT status FROM trading_normalization_ack_dispatches", String::class.java))
    }

    @Test
    fun terminalDescriptorRetriesExhaustToProcessingFailedWithoutRevision() = withDatabase { connection ->
        val source = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(source)
        val transactions = TransactionTemplate(DataSourceTransactionManager(source))
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        members(jdbc)
        val review = JdbcTradingReviewStore(jdbc)
        val account = TradingAccount.create(TradingAccountId(951), MemberId(1), TradingVenue.BINANCE, "A", now)
        review.insert(account)
        val book = TradingBook.create(TradingBookId(952), account, "B",
            TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY,
            TradingSettlementAsset.USDT, "UTC", now)
        review.insert(book)
        val imports = JdbcTradingImportStore(jdbc)
        val session = TradingImportSession.create(TradingImportSessionId(953), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), normalizationVersions, now)
        imports.insert(session)
        val artifacts = TradingArtifactRole.entries.mapIndexed { index, role ->
            NormalizationArtifactInput(TradingSourceArtifactId(954L + index), role,
                (if (index == 0) "a" else "b").repeat(64), 20, "text/csv", "AUTO")
        }
        val input = NormalizationInput(book.id, session.id, 1, artifacts, "BINANCE",
            "LINEAR_PERPETUAL", "ONE_WAY", "USDT", "UTC", "UTC", normalizationVersions,
            "[]", "TRADING_DATA_POLICY_V1", "retry-v1",
            """{"policyVersion":"retry-v1","maxAttempts":1,"maxElapsedTime":"PT1H","initialDelay":"PT1S","maxDelay":"PT10S","jitter":"NONE"}""", 0)
        val identity = NormalizationInputIdentity.fromPinnedInput(input,
            mapper.readValue(normalizationVersions, Map::class.java))
        val runId = UUID.randomUUID().toString()
        val run = NormalizationRun(runId, input, NormalizationInputCanonicalizer.hash(identity),
            "b".repeat(64), NormalizationRunStatus.DISPATCH_PENDING, 1,
            UUID.randomUUID().toString(), null, null, now, now, null, now, null, 0,
            inputIdentity = identity, resultDeliveryPolicy = deliveryPolicy)
        val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId, "c".repeat(64),
            NormalizationDispatchStatus.PENDING, 0, now, null, null, null, now, now)
        val runs = JdbcTradingNormalizationStore(jdbc, mapper)
        runs.insert(run, dispatch)
        assertTrue(imports.update(session.beginValidation(runId, now)))
        jdbc.update("UPDATE trading_normalization_runs SET status='RESULT_READY',compute_job_id='job-timeout' WHERE id=?::uuid", runId)
        jdbc.update("UPDATE trading_normalization_dispatches SET status='RESULT_READY' WHERE id=?::uuid", dispatch.id)
        val client = object : TradingNormalizationResultClient {
            override fun descriptor(jobId: String) = NormalizationResultLookup.Retry("TEMPORARY_DEPENDENCY_FAILURE")
            override fun download(descriptor: NormalizationResultDescriptor,
                                  policy: NormalizationResultDeliveryPolicy): NormalizationPayloadLookup =
                error("No payload after timeout")
            override fun acknowledge(acknowledgement: NormalizationPayloadAcknowledgement): NormalizationAckOutcome =
                error("No ACK after timeout")
        }
        val processor = com.refinvest.core.tradingreview.application.TradingNormalizationResultProcessor(
            runs, imports, review, JdbcTradingNormalizationResultStore(jdbc, mapper), client,
            transactions, Clock.fixed(now, ZoneOffset.UTC), mapper,
            object : TradingRetryJitter { override fun factor() = 0.5 })
        assertTrue(processor.processOne())
        assertEquals(TradingImportStatus.PROCESSING_FAILED, imports.find(session.id, MemberId(1))?.status)
        assertEquals("TERMINAL_FAILURE", jdbc.queryForObject(
            "SELECT status FROM trading_normalization_runs WHERE id=?::uuid", String::class.java, runId))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_ack_dispatches", Long::class.java))
    }

    @Test
    fun deletionWorkerKeepsDiscardAckForKnownStagedPayloadAfterPrimaryPurge() = verifyDiscardAfterDeletion(false)

    @Test
    fun leasedResultWorkerCreatesDiscardAckBeforePrimaryPurge() = verifyDiscardAfterDeletion(true)

    private fun verifyDiscardAfterDeletion(workerDiscards: Boolean) = withDatabase { connection ->
        val source = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(source)
        val tx = TransactionTemplate(DataSourceTransactionManager(source))
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        members(jdbc)
        val review = JdbcTradingReviewStore(jdbc)
        val account = TradingAccount.create(TradingAccountId(901), MemberId(1), TradingVenue.BINANCE, "A", now)
        review.insert(account)
        val book = TradingBook.create(TradingBookId(902), account, "B",
            TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY,
            TradingSettlementAsset.USDT, "UTC", now)
        review.insert(book)
        val imports = JdbcTradingImportStore(jdbc)
        val session = TradingImportSession.create(TradingImportSessionId(903), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), normalizationVersions, now)
        imports.insert(session)
        val pinnedArtifacts = TradingArtifactRole.entries.mapIndexed { index, role ->
            NormalizationArtifactInput(TradingSourceArtifactId(910L + index), role,
                (if (index == 0) "a" else "b").repeat(64), 20, "text/csv", "AUTO")
        }
        val input = NormalizationInput(book.id, session.id, 1, pinnedArtifacts, "BINANCE",
            "LINEAR_PERPETUAL", "ONE_WAY", "USDT", "UTC", "UTC", normalizationVersions, "[]",
            "TRADING_DATA_POLICY_V1", "retry-v1",
            """{"policyVersion":"retry-v1","maxAttempts":3,"maxElapsedTime":"PT1H","initialDelay":"PT1S","maxDelay":"PT10S","jitter":"NONE"}""", 0)
        val identity = NormalizationInputIdentity.fromPinnedInput(input,
            mapper.readValue(normalizationVersions, Map::class.java))
        val runId = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        val run = NormalizationRun(runId, input, NormalizationInputCanonicalizer.hash(identity),
            "b".repeat(64), NormalizationRunStatus.DISPATCH_PENDING, 1, token, null, null,
            now, now, null, now, null, 0, inputIdentity = identity,
            resultDeliveryPolicy = deliveryPolicy)
        val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId, "c".repeat(64),
            NormalizationDispatchStatus.PENDING, 0, now, null, null, null, now, now)
        val runs = JdbcTradingNormalizationStore(jdbc, mapper)
        runs.insert(run, dispatch)
        assertTrue(imports.update(session.beginValidation(runId, now)))
        jdbc.update("UPDATE trading_normalization_runs SET status='RESULT_READY',compute_job_id='job-stale' WHERE id=?::uuid", runId)
        jdbc.update("UPDATE trading_normalization_dispatches SET status='RESULT_READY',attempt_count=10 WHERE id=?::uuid", dispatch.id)
        val current = assertNotNull(runs.findRun(runId))
        val results = JdbcTradingNormalizationResultStore(jdbc, mapper)
        val worker = UUID.randomUUID().toString()
        val claimed = assertNotNull(tx.execute { results.claimReady(now, java.time.Duration.ofMinutes(15), worker) })
        assertEquals(1, claimed.attemptCount)
        val descriptor = NormalizationResultDescriptor("job-stale", dispatch.id, runId, "payload-stale", 100,
            "a".repeat(64), token, 0, run.inputFingerprint, identity.hashSpecificationVersion,
            identity.targetVersionSet.toWireValue(), "TRADING_NORMALIZATION_RESULT_V1",
            "TRADING_NORMALIZATION_RESULT_HASH_V1", emptyMap(), emptyList(),
            "d".repeat(64), "e".repeat(64), now.plusSeconds(3600), "RESULT_DELIVERY_V1", "identity")
        results.beginStage(current, descriptor, now)
        val locked = assertNotNull(imports.find(session.id, MemberId(1)))
        assertTrue(imports.update(locked.requestDeletion(now.plusSeconds(1))))
        if (workerDiscards) {
            assertTrue(tx.execute { results.discardStale(current, claimed, worker, descriptor,
                now.plusSeconds(2)) } == true)
            assertEquals("OBSOLETE", jdbc.queryForObject(
                "SELECT status FROM trading_normalization_runs WHERE id=?::uuid", String::class.java, runId))
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_result_staging", Long::class.java))
        } else assertEquals(1L, jdbc.queryForObject(
            "SELECT count(*) FROM trading_normalization_result_staging", Long::class.java))
        review.insert(TradingDeletion(904, MemberId(1), "TRADING_IMPORT_SESSION", session.id.value,
            "IMPORT_SUBTREE", "REQUESTED", 1, 0, 0, now, now, now, now.plusSeconds(30L * 24 * 3600)))
        TradingDeletionWorker(jdbc, tx, emptyStorage(), Clock.fixed(now.plusSeconds(3), ZoneOffset.UTC)).purgePending()
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_runs", Long::class.java))
        val ack = assertNotNull(tx.execute { results.claimAck(now.plusSeconds(4),
            java.time.Duration.ofSeconds(45), UUID.randomUUID().toString()) })
        assertEquals("DISCARDED_STALE", ack.disposition)
        assertEquals(1L, ack.currentDeletionGeneration)
        assertEquals(session.id.value.toString(), ack.coreImportSessionId)
    }

    @Test
    fun publicationRollsBackOnMissingEvidenceThenCreatesOneRevisionAndAck() = withDatabase { connection ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        val tx = TransactionTemplate(DataSourceTransactionManager(SingleConnectionDataSource(connection, true)))
        members(jdbc)
        val review = JdbcTradingReviewStore(jdbc)
        val account = TradingAccount.create(TradingAccountId(991), MemberId(1), TradingVenue.BINANCE, "A", now)
        review.insert(account)
        val book = TradingBook.create(TradingBookId(992), account, "B",
            TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY,
            TradingSettlementAsset.USDT, "UTC", now)
        review.insert(book)
        val imports = JdbcTradingImportStore(jdbc)
        val session = TradingImportSession.create(TradingImportSessionId(993), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), normalizationVersions, now)
        imports.insert(session)
        val inputs = TradingArtifactRole.entries.mapIndexed { index, role ->
            val id = 994L + index
            imports.insert(TradingSourceArtifact(TradingSourceArtifactId(id), session.id, role,
                null, "text/csv", 20, (if (index == 0) "a" else "b").repeat(64), "AUTO",
                "objects/${UUID.randomUUID()}", byteArrayOf(1), "test-v1",
                TradingArtifactRetentionStatus.ACTIVE, now, now.plusSeconds(3600), null, null, 0))
            NormalizationArtifactInput(TradingSourceArtifactId(id), role,
                (if (index == 0) "a" else "b").repeat(64), 20, "text/csv", "AUTO")
        }
        val input = NormalizationInput(book.id, session.id, 1, inputs, "BINANCE",
            "LINEAR_PERPETUAL", "ONE_WAY", "USDT", "UTC", "UTC", normalizationVersions, "[]",
            "TRADING_DATA_POLICY_V1", "retry-v1",
            """{"policyVersion":"retry-v1","maxAttempts":3,"maxElapsedTime":"PT1H","initialDelay":"PT1S","maxDelay":"PT10S","jitter":"NONE"}""", 0)
        val identity = NormalizationInputIdentity.fromPinnedInput(input,
            mapper.readValue(normalizationVersions, Map::class.java))
        val runId = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        val run = NormalizationRun(runId, input, NormalizationInputCanonicalizer.hash(identity),
            "b".repeat(64), NormalizationRunStatus.DISPATCH_PENDING, 1, token, null, null,
            now, now, null, now, null, 0, inputIdentity = identity,
            resultDeliveryPolicy = deliveryPolicy)
        val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId, "c".repeat(64),
            NormalizationDispatchStatus.PENDING, 0, now, null, null, null, now, now)
        val runs = JdbcTradingNormalizationStore(jdbc, mapper)
        runs.insert(run, dispatch)
        assertTrue(imports.update(session.beginValidation(runId, now)))
        jdbc.update("UPDATE trading_normalization_runs SET status='RESULT_READY',compute_job_id='job-1' WHERE id=?::uuid", runId)
        jdbc.update("UPDATE trading_normalization_dispatches SET status='RESULT_READY' WHERE id=?::uuid", dispatch.id)
        val current = assertNotNull(runs.findRun(runId))
        val results = JdbcTradingNormalizationResultStore(jdbc, mapper)
        val worker = UUID.randomUUID().toString()
        val claimed = assertNotNull(tx.execute { results.claimReady(now, java.time.Duration.ofMinutes(15), worker) })
        assertTrue(tx.execute { results.hasReadyLease(claimed.id, worker, now) } == true)
        assertFalse(tx.execute { results.hasReadyLease(claimed.id, worker,
            now.plusSeconds(16 * 60)) } == true)
        val descriptor = NormalizationResultDescriptor("job-1", dispatch.id, runId, "payload-1", 100,
            "a".repeat(64), token, 0, run.inputFingerprint, identity.hashSpecificationVersion,
            identity.targetVersionSet.toWireValue(), "TRADING_NORMALIZATION_RESULT_V1",
            "TRADING_NORMALIZATION_RESULT_HASH_V1", mapOf("canonicalRecordCount" to 1),
            emptyList(), "d".repeat(64), "e".repeat(64), now.plusSeconds(3600),
            "RESULT_DELIVERY_V1", "identity")
        results.beginStage(current, descriptor, now)
        results.saveStageBase(runId, """{"status":"COMPLETED","canonicalRecords":[]}""")
        assertEquals("COMPLETED", mapper.readTree(assertNotNull(results.loadStageBase(runId)))
            .path("status").asText())
        assertEquals(descriptor.resultHash,
            assertNotNull(results.loadStageDescriptor(current, claimed)).resultHash)
        assertFailsWith<NormalizationResultStoreFailure> {
            results.saveStageBase(runId, """{"status":"FAILED"}""")
        }
        fun stage(section: String, id: String, json: String) = results.stageItem(runId,
            NormalizationStagedItem(section, id, "f".repeat(64), json))
        stage("CANONICAL_RECORDS", "record-1",
            """{"recordId":"record-1","sourceEvidenceSnapshotId":"snapshot-1","recordHash":"${"a".repeat(64)}"}""")
        assertFailsWith<NormalizationResultStoreFailure> {
            results.stageItem(runId, NormalizationStagedItem("CANONICAL_RECORDS", "record-1",
                "0".repeat(64), """{"recordId":"record-1","sourceEvidenceSnapshotId":"other"}"""))
        }
        stage("REVIEW_UNITS", "unit-1",
            """{"reviewUnitId":"unit-1","allocations":[{"allocationId":"allocation-1","reviewUnitId":"unit-1","recordId":"record-1"}],"exclusionReasons":[]}""")
        results.markValidated(runId, now)
        assertNull(results.publishedForSession(session.id, MemberId(1)))
        assertFailsWith<Exception> {
            tx.execute { results.publish(current, claimed, worker, descriptor, "{}", "[]", "[]", "{}",
                "f".repeat(64), now) }
        }
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
        assertEquals(TradingImportStatus.VALIDATING, imports.find(session.id, MemberId(1))?.status)
        stage("SOURCE_EVIDENCE_SNAPSHOTS", "snapshot-1",
            """{"snapshotId":"snapshot-1","coreArtifactId":"994","sourceRowNumber":1,"maskingPolicyVersion":"MASK_V1"}""")
        assertEquals(1L, results.stagedCounts(runId)["SOURCE_EVIDENCE_SNAPSHOTS"])
        val publicationGate = java.util.concurrent.CountDownLatch(1)
        val publishers = Executors.newFixedThreadPool(2)
        try {
            val publications = (1..2).map {
                publishers.submit<Boolean> {
                    DriverManager.getConnection(
                        requireNotNull(System.getenv("TRADING_REVIEW_TEST_JDBC_URL")),
                        requireNotNull(System.getenv("TRADING_REVIEW_TEST_DB_USER")),
                        requireNotNull(System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD"))).use { publisherConnection ->
                        publisherConnection.schema = connection.schema
                        val publisherSource = SingleConnectionDataSource(publisherConnection, true)
                        val publisherStore = JdbcTradingNormalizationResultStore(JdbcTemplate(publisherSource), mapper)
                        val publisherTx = TransactionTemplate(DataSourceTransactionManager(publisherSource))
                        publicationGate.await(5, TimeUnit.SECONDS)
                        publisherTx.execute { publisherStore.publish(current, claimed, worker, descriptor,
                            "{}", "[]", "[]", "{}", "f".repeat(64), now) } == true
                    }
                }
            }
            publicationGate.countDown()
            assertTrue(publications.all { it.get(10, TimeUnit.SECONDS) })
        } finally {
            publishers.shutdownNow()
        }
        assertEquals(TradingImportStatus.ACCEPTED, imports.find(session.id, MemberId(1))?.status)
        assertNotNull(results.publishedForSession(session.id, MemberId(1)))
        assertNull(results.publishedForSession(session.id, MemberId(2)))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
        assertFailsWith<Exception> {
            jdbc.update("UPDATE trading_ledger_revisions SET result_hash=? WHERE run_id=?::uuid",
                "0".repeat(64), runId)
        }
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_ack_dispatches", Long::class.java))
        assertTrue(results.publish(current, claimed, worker, descriptor, "{}", "[]", "[]", "{}",
            "f".repeat(64), now))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
        val ackWorker = UUID.randomUUID().toString()
        val ack = assertNotNull(tx.execute { results.claimAck(now, java.time.Duration.ofSeconds(45), ackWorker) })
        assertEquals("PERSISTED", ack.disposition)
        assertNull(tx.execute { results.claimAck(now.plusSeconds(1),
            java.time.Duration.ofSeconds(45), UUID.randomUUID().toString()) })
        assertTrue(results.retryAck(ack.idempotencyKey, ackWorker, now.plusSeconds(2), now))
        val retryWorker = UUID.randomUUID().toString()
        val retryAck = assertNotNull(tx.execute { results.claimAck(now.plusSeconds(3),
            java.time.Duration.ofSeconds(45), retryWorker) })
        assertEquals(ack.idempotencyKey, retryAck.idempotencyKey)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_result_staging", Long::class.java))
        val acceptedSession = assertNotNull(imports.find(session.id, MemberId(1)))
        assertTrue(imports.update(acceptedSession.requestDeletion(now.plusSeconds(4))))
        review.insert(TradingDeletion(996, MemberId(1), "TRADING_IMPORT_SESSION", session.id.value,
            "IMPORT_SUBTREE", "REQUESTED", 1, 0, 0, now, now, now, now.plusSeconds(30L * 24 * 3600)))
        TradingDeletionWorker(jdbc, tx, emptyStorage(), Clock.fixed(now.plusSeconds(5), ZoneOffset.UTC)).purgePending()
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revision_records", Long::class.java))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_records", Long::class.java))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_source_evidence_snapshots", Long::class.java))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_ack_dispatches", Long::class.java))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_runs", Long::class.java))
        assertNull(results.publishedForSession(session.id, MemberId(1)))
        assertTrue(results.completeAck(ack.idempotencyKey, retryWorker, now.plusSeconds(6)))
    }

    @Test
    fun normalizationDispatchPersistsSnapshotAndRecoversExpiredLease() = withDatabase { connection ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        members(jdbc)
        val review = JdbcTradingReviewStore(jdbc)
        val account = TradingAccount.create(TradingAccountId(901), MemberId(1), TradingVenue.BINANCE, "A", now)
        review.insert(account)
        val book = TradingBook.create(TradingBookId(902), account, "B",
            TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY,
            TradingSettlementAsset.USDT, "UTC", now)
        review.insert(book)
        val session = TradingImportSession.create(TradingImportSessionId(903), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), "{}", now)
        JdbcTradingImportStore(jdbc).insert(session)
        val store = JdbcTradingNormalizationStore(jdbc, tools.jackson.databind.json.JsonMapper.builder().build())
        val runId = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        val pinnedArtifacts = TradingArtifactRole.entries.mapIndexed { index, role ->
            NormalizationArtifactInput(TradingSourceArtifactId(904L + index), role,
                (if (index == 0) "a" else "b").repeat(64), 100, "text/csv", "AUTO")
        }
        val input = NormalizationInput(book.id, session.id, 1, pinnedArtifacts, "BINANCE",
            "LINEAR_PERPETUAL", "ONE_WAY", "USDT", "UTC", "UTC", normalizationVersions, "[]",
            "TRADING_DATA_POLICY_V1", "retry-v1",
            "{\"policyVersion\":\"retry-v1\",\"maxAttempts\":3,\"maxElapsedTime\":\"PT1M\",\"initialDelay\":\"PT1S\",\"maxDelay\":\"PT10S\",\"jitter\":\"NONE\"}", 0)
        val identity = NormalizationInputIdentity.fromPinnedInput(input,
            tools.jackson.databind.json.JsonMapper.builder().build().readValue(
                normalizationVersions, Map::class.java))
        val run = NormalizationRun(runId, input, NormalizationInputCanonicalizer.hash(identity), "b".repeat(64),
            NormalizationRunStatus.DISPATCH_PENDING, 1, token, null, null,
            now, now, null, now, null, 0, inputIdentity = identity,
            resultDeliveryPolicy = deliveryPolicy)
        val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId, "c".repeat(64),
            NormalizationDispatchStatus.PENDING, 0, now, null, null, null, now, now)
        store.insert(run, dispatch)
        val restored = assertNotNull(store.findRun(runId)?.input)
        assertEquals(input.copy(retryPolicyJson = restored.retryPolicyJson,
            versionSetJson = restored.versionSetJson), restored)
        assertEquals(NormalizationInputCanonicalizer.hash(identity),
            NormalizationInputCanonicalizer.hash(assertNotNull(store.findRun(runId)?.inputIdentity)))
        assertFalse(jdbc.queryForObject("SELECT input_identity_json::text FROM trading_normalization_runs WHERE id=?::uuid",
            String::class.java, runId)!!.contains("accessUri"))
        jdbc.update("UPDATE trading_import_sessions SET source_timezone='Asia/Seoul' WHERE id=?", session.id.value)
        assertEquals("UTC", assertNotNull(store.findRun(runId)?.inputIdentity).sourceTimezone)
        jdbc.update("UPDATE trading_normalization_runs SET input_fingerprint=? WHERE id=?::uuid",
            "0".repeat(64), runId)
        assertFailsWith<IllegalArgumentException> { store.findRun(runId) }
        jdbc.update("UPDATE trading_normalization_runs SET input_fingerprint=? WHERE id=?::uuid",
            run.inputFingerprint, runId)
        assertEquals(tools.jackson.databind.json.JsonMapper.builder().build().readTree(input.retryPolicyJson),
            tools.jackson.databind.json.JsonMapper.builder().build().readTree(restored.retryPolicyJson))
        assertFailsWith<Exception> { store.insert(run.copy(id = UUID.randomUUID().toString()),
            dispatch.copy(id = UUID.randomUUID().toString(), runId = runId)) }
        val first = store.claimNext(now, java.time.Duration.ofSeconds(45), UUID.randomUUID().toString())
        assertEquals(dispatch.id, first?.id)
        assertEquals(null, store.claimNext(now, java.time.Duration.ofSeconds(45), UUID.randomUUID().toString()))
        val recovered = store.claimNext(now.plusSeconds(46), java.time.Duration.ofSeconds(45),
            UUID.randomUUID().toString())
        assertEquals(dispatch.id, recovered?.id)
        assertEquals(2, recovered?.attemptCount)
        jdbc.update("""UPDATE trading_normalization_dispatches SET status='SUBMITTED',
            lease_owner=NULL,lease_expires_at=NULL WHERE id=?::uuid""", dispatch.id)
        val pollWorker = UUID.randomUUID().toString()
        val pollAt = now.plusSeconds(46)
        val polled = assertNotNull(store.claimSubmitted(pollAt,
            java.time.Duration.ofSeconds(45), pollWorker))
        assertEquals(0, polled.pollFailureCount)
        assertTrue(store.schedulePollFailure(dispatch.id, pollWorker,
            "TEMPORARY_DEPENDENCY_FAILURE", pollAt.plusSeconds(1), pollAt))
        val secondWorker = UUID.randomUUID().toString()
        val secondPoll = assertNotNull(store.claimSubmitted(pollAt.plusSeconds(1),
            java.time.Duration.ofSeconds(45), secondWorker))
        assertEquals(1, secondPoll.pollFailureCount)
        assertTrue(store.schedulePoll(dispatch.id, secondWorker, pollAt.plusSeconds(2),
            pollAt.plusSeconds(1)))
        assertEquals(0, jdbc.queryForObject("""SELECT poll_failure_count FROM
            trading_normalization_dispatches WHERE id=?::uuid""", Int::class.java, dispatch.id))
    }

    @Test
    fun validationAdmissionCommitsSessionRunAndDispatchTogether() = withDatabase { connection ->
        val source = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(source)
        val transactions = TransactionTemplate(DataSourceTransactionManager(source))
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        members(jdbc)
        val review = JdbcTradingReviewStore(jdbc)
        val imports = JdbcTradingImportStore(jdbc)
        val runs = JdbcTradingNormalizationStore(jdbc, mapper)
        val account = TradingAccount.create(TradingAccountId(911), MemberId(1), TradingVenue.BINANCE, "A", now)
        review.insert(account)
        val book = TradingBook.create(TradingBookId(912), account, "B",
            TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY,
            TradingSettlementAsset.USDT, "UTC", now)
        review.insert(book)
        val versions = normalizationVersions
        val session = TradingImportSession.create(TradingImportSessionId(913), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), versions, now)
        imports.insert(session)
        val service = com.refinvest.core.tradingreview.application.TradingNormalizationService(
            object : TradingMemberProvider { override fun currentMemberId() = MemberId(1) },
            review, review, imports, imports, review, runs,
            object : com.refinvest.core.tradingreview.port.TradingVersionPins {
                override fun currentJson() = versions
            }, object : com.refinvest.core.tradingreview.port.TradingNormalizationSettings {
                override fun retryPolicy() = com.refinvest.core.tradingreview.port.TradingNormalizationRetryPolicy(
                    "retry-v1", 3, java.time.Duration.ofMinutes(1), java.time.Duration.ofSeconds(1),
                    java.time.Duration.ofSeconds(10), "NONE")
                override fun instrumentTermsJson() = "[]"
                override fun resultDeliveryPolicy() = deliveryPolicy
            }, mapper, Clock.fixed(now, ZoneOffset.UTC))
        assertEquals("MISSING_REQUIRED_ARTIFACT", assertFailsWith<TradingFailure> {
            transactions.execute { service.validate(session.id, null, "missing-position") }
        }.code)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_runs", Long::class.java))
        assertEquals(TradingImportStatus.RECEIVED, imports.find(session.id, MemberId(1))?.status)
        for ((index, role) in TradingArtifactRole.entries.withIndex()) {
            imports.insert(TradingSourceArtifact(TradingSourceArtifactId(914L + index), session.id,
                role, null, "text/csv", 20, (if (index == 0) "a" else "b").repeat(64),
                "AUTO", "objects/${UUID.randomUUID()}", byteArrayOf(1), "test-v1",
                TradingArtifactRetentionStatus.ACTIVE, now, now.plusSeconds(3600), null, null, 0))
        }
        val started = transactions.execute { service.validate(session.id, null, "validate-first") }!!
        assertEquals(TradingImportStatus.VALIDATING, started.status)
        assertNotNull(started.activeNormalizationRunId)
        val replay = transactions.execute { service.validate(session.id, null, "validate-again") }!!
        assertEquals(started.activeNormalizationRunId, replay.activeNormalizationRunId)
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_runs", Long::class.java))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_dispatches", Long::class.java))
        assertEquals(started.activeNormalizationRunId, imports.find(session.id, MemberId(1))?.activeNormalizationRunId)
        val active = assertNotNull(runs.findRun(started.activeNormalizationRunId!!))
        val workerTime = java.util.concurrent.atomic.AtomicReference(now)
        val workerClock = object : Clock() {
            override fun instant() = workerTime.get()
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
        }
        var grantNumber = 0
        val submittedKeys = mutableListOf<String>()
        val submittedLocators = mutableListOf<List<String>>()
        val issuer = object : ArtifactAccessGrantIssuer {
            override fun issueReadGrant(artifact: NormalizationArtifactInput, logicalJobId: String,
                                        sessionId: TradingImportSessionId, attemptToken: String,
                                        deletionGeneration: Long): ArtifactReadGrant = ArtifactReadGrant(
                artifact.artifactId.value.toString(), artifact.role.name, artifact.sha256,
                artifact.contentLength, artifact.mediaType, "https://grant.test/${++grantNumber}",
                workerTime.get().plusSeconds(60))
        }
        val compute = object : TradingNormalizationComputeClient {
            override fun submit(run: NormalizationRun, dispatch: NormalizationDispatch,
                                grants: List<ArtifactReadGrant>): NormalizationSubmission {
                submittedKeys.add(dispatch.computeKey)
                submittedLocators.add(grants.map { it.accessUri })
                assertEquals(active.inputFingerprint, run.inputFingerprint)
                return if (submittedKeys.size == 1)
                    NormalizationSubmission.Retry("TEMPORARY_DEPENDENCY_FAILURE")
                else NormalizationSubmission.Accepted("job-1")
            }
            override fun status(run: NormalizationRun, dispatch: NormalizationDispatch): NormalizationStatusLookup =
                error("No status poll in this test")
            override fun updateGrants(run: NormalizationRun, dispatch: NormalizationDispatch,
                                      current: NormalizationJobStatus, nextAttemptToken: String,
                                      grants: List<ArtifactReadGrant>, updateKey: String): NormalizationGrantUpdate =
                error("No grant update in this test")
        }
        val dispatcher = com.refinvest.core.tradingreview.application.TradingNormalizationDispatcher(
            runs, imports, review, imports, issuer, compute, object : TradingRetryJitter {
                override fun factor() = 0.5
            }, transactions, workerClock, mapper)
        assertTrue(dispatcher.dispatchOne())
        assertEquals(TradingImportStatus.VALIDATING, imports.find(session.id, MemberId(1))?.status)
        workerTime.set(now.plusSeconds(1))
        assertTrue(dispatcher.dispatchOne())
        assertEquals(2, submittedKeys.size)
        assertEquals(submittedKeys[0], submittedKeys[1])
        assertTrue(submittedLocators[0] != submittedLocators[1])
        assertFalse(jdbc.queryForObject("SELECT input_json::text FROM trading_normalization_runs WHERE id=?::uuid",
            String::class.java, active.id)!!.contains("https://grant.test"))
        assertEquals("job-1", runs.findRun(active.id)?.computeJobId)
        val polling = assertNotNull(runs.claimSubmitted(now.plusSeconds(1), java.time.Duration.ofSeconds(45),
            UUID.randomUUID().toString()))
        transactions.execute {
            val locked = assertNotNull(imports.find(session.id, MemberId(1), true))
            assertTrue(runs.markTerminal(polling.id, polling.leaseOwner!!,
                "TEMPORARY_DEPENDENCY_FAILURE", now))
            val failed = assertNotNull(locked.failValidation(active.id, active.input.deletionGeneration,
                TradingImportFailure("TEMPORARY_DEPENDENCY_FAILURE", true, "RETRY", now), now))
            assertTrue(imports.update(failed))
        }
        assertEquals(TradingImportStatus.PROCESSING_FAILED, imports.find(session.id, MemberId(1))?.status)
        assertEquals("TEMPORARY_DEPENDENCY_FAILURE", runs.findRun(active.id)?.lastFailureCode)
        val retried = transactions.execute { service.retry(session.id, "retry-once") }!!
        assertEquals(TradingImportStatus.VALIDATING, retried.status)
        assertTrue(retried.activeNormalizationRunId != active.id)
        assertEquals(active.id, runs.findRun(retried.activeNormalizationRunId!!)?.retryOfRunId)
        assertEquals(retried.activeNormalizationRunId,
            transactions.execute { service.retry(session.id, "retry-once") }!!.activeNormalizationRunId)
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_runs", Long::class.java))
        val alwaysRetry = object : TradingNormalizationComputeClient {
            override fun submit(run: NormalizationRun, dispatch: NormalizationDispatch,
                                grants: List<ArtifactReadGrant>): NormalizationSubmission =
                NormalizationSubmission.Retry("TEMPORARY_DEPENDENCY_FAILURE")
            override fun status(run: NormalizationRun, dispatch: NormalizationDispatch): NormalizationStatusLookup =
                error("No status poll in this test")
            override fun updateGrants(run: NormalizationRun, dispatch: NormalizationDispatch,
                                      current: NormalizationJobStatus, nextAttemptToken: String,
                                      grants: List<ArtifactReadGrant>, updateKey: String): NormalizationGrantUpdate =
                error("No grant update in this test")
        }
        val exhausting = com.refinvest.core.tradingreview.application.TradingNormalizationDispatcher(
            runs, imports, review, imports, issuer, alwaysRetry, object : TradingRetryJitter {
                override fun factor() = 0.5
            }, transactions, workerClock, mapper)
        for (seconds in listOf(1L, 2L, 4L)) {
            workerTime.set(now.plusSeconds(seconds))
            assertTrue(exhausting.dispatchOne())
        }
        val exhausted = assertNotNull(imports.find(session.id, MemberId(1)))
        assertEquals(TradingImportStatus.PROCESSING_FAILED, exhausted.status)
        assertEquals("TEMPORARY_DEPENDENCY_FAILURE", exhausted.failure?.code)
        assertTrue(exhausted.failure?.retryable == true)
        assertEquals(NormalizationRunStatus.TERMINAL_FAILURE,
            runs.findRun(retried.activeNormalizationRunId!!)?.status)
        jdbc.update("UPDATE trading_source_artifacts SET raw_expires_at=? WHERE import_session_id=?",
            java.sql.Timestamp.from(now.minusSeconds(1)), session.id.value)
        assertEquals("SOURCE_ARTIFACT_EXPIRED", assertFailsWith<TradingFailure> {
            transactions.execute { service.retry(session.id, "retry-expired") }
        }.code)
        assertEquals(TradingImportStatus.PROCESSING_FAILED, imports.find(session.id, MemberId(1))?.status)
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_dispatches", Long::class.java))
        jdbc.update("UPDATE trading_source_artifacts SET raw_expires_at=? WHERE import_session_id=?",
            java.sql.Timestamp.from(now.plusSeconds(3600)), session.id.value)
        val again = transactions.execute { service.retry(session.id, "retry-after-exhaustion") }!!
        assertEquals(retried.activeNormalizationRunId, runs.findRun(again.activeNormalizationRunId!!)?.retryOfRunId)
        val staleDispatch = assertNotNull(runs.claimNext(now, java.time.Duration.ofSeconds(45),
            UUID.randomUUID().toString()))
        val staleRun = assertNotNull(runs.findRun(again.activeNormalizationRunId!!))
        jdbc.update("UPDATE trading_import_sessions SET deletion_generation=deletion_generation+1 WHERE id=?",
            session.id.value)
        assertFalse(runs.markSubmitted(staleDispatch.id, staleDispatch.leaseOwner!!, staleRun.id,
            staleRun.input.deletionGeneration, staleRun.attemptToken, "late-job", now))
    }

    @Test
    fun `migration and JDBC adapter preserve ownership and optimistic version`() = withDatabase { connection ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val store = JdbcTradingReviewStore(jdbc)
        members(jdbc)
        val account = TradingAccount.create(TradingAccountId(101), MemberId(1), TradingVenue.BINANCE, "One", now)
        store.insert(account)
        val book = TradingBook.create(TradingBookId(201), account, "Futures", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
        store.insert(book)

        assertNotNull(store.find(account.id, account.owner))
        assertEquals(null, store.find(account.id, MemberId(2)))
        assertEquals(null, store.find(book.id, MemberId(2)))
        assertEquals(1, store.list(account.owner, 0, 20).total)
        assertEquals(1, store.list(account.id, account.owner, 0, 20).total)
        assertTrue(store.update(account.rename("Two", now.plusSeconds(1))))
        assertFalse(store.update(account.rename("Stale", now.plusSeconds(2))))
        assertEquals("Two", store.find(account.id, account.owner)?.displayName)
        assertTrue(store.update(book.requestDeletion(now.plusSeconds(1))))
        assertEquals(1, store.find(book.id, book.owner)?.deletionGeneration)
    }

    @Test
    fun `database constraints and idempotency key enforce the declared boundary`() = withDatabase { connection ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val store = JdbcTradingReviewStore(jdbc)
        members(jdbc)
        val account = TradingAccount.create(TradingAccountId(102), MemberId(1), TradingVenue.BINANCE, "One", now)
        store.insert(account)
        val ownBook = TradingBook.create(TradingBookId(202), account, "Futures", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
        store.insert(ownBook)
        // The contract permits another Book with the same family and mode under one Account.
        store.insert(TradingBook.create(TradingBookId(203), account, "Second", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now))
        assertEquals(2, store.list(account.id, account.owner, 0, 20).total)
        assertFailsWith<Exception> {
            jdbc.update("""INSERT INTO trading_books (id, account_id, owner_member_id, venue, display_name,
                product_family, position_mode, settlement_asset, review_timezone, status, created_at, updated_at)
                VALUES (204, 102, 2, 'BINANCE', 'Wrong owner', 'LINEAR_PERPETUAL', 'ONE_WAY', 'USDT', 'UTC',
                'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""")
        }
        assertIs<IdempotencyClaim.New>(store.claim(MemberId(1), "createTradingAccount", "same-key", "a".repeat(64)))
        store.complete(MemberId(1), "createTradingAccount", "same-key", 102)
        assertEquals(102L, (store.claim(MemberId(1), "createTradingAccount", "same-key", "a".repeat(64))
            as IdempotencyClaim.Replayed).resourceId)
        assertEquals("IDEMPOTENCY_PAYLOAD_CONFLICT", assertFailsWith<TradingFailure> {
            store.claim(MemberId(1), "createTradingAccount", "same-key", "b".repeat(64))
        }.code)
        assertIs<IdempotencyClaim.New>(store.claim(MemberId(1), "createTradingBook", "same-key", "a".repeat(64)))
    }

    @Test
    fun `concurrent claims return one committed resource and rolled back claims can retry`() = withDatabase { first ->
        val firstStore = JdbcTradingReviewStore(JdbcTemplate(SingleConnectionDataSource(first, true)))
        members(JdbcTemplate(SingleConnectionDataSource(first, true)))
        DriverManager.getConnection(System.getenv("TRADING_REVIEW_TEST_JDBC_URL"),
            System.getenv("TRADING_REVIEW_TEST_DB_USER"), System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD")).use { second ->
            second.schema = first.schema
            second.autoCommit = false
            val secondStore = JdbcTradingReviewStore(JdbcTemplate(SingleConnectionDataSource(second, true)))
            first.autoCommit = false
            assertIs<IdempotencyClaim.New>(firstStore.claim(MemberId(1), "createTradingAccount", "race", "a".repeat(64)))
            firstStore.complete(MemberId(1), "createTradingAccount", "race", 400)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val replay = executor.submit<IdempotencyClaim> {
                    secondStore.claim(MemberId(1), "createTradingAccount", "race", "a".repeat(64))
                }
                Thread.sleep(150)
                assertFalse(replay.isDone)
                first.commit()
                assertEquals(400L, (replay.get(10, TimeUnit.SECONDS) as IdempotencyClaim.Replayed).resourceId)
                second.commit()

                assertIs<IdempotencyClaim.New>(firstStore.claim(MemberId(1), "createTradingAccount", "rollback", "b".repeat(64)))
                first.rollback()
                assertIs<IdempotencyClaim.New>(secondStore.claim(MemberId(1), "createTradingAccount", "rollback", "b".repeat(64)))
                second.rollback()
            } finally {
                executor.shutdownNow()
                first.autoCommit = true
            }
        }
    }

    @Test
    fun `application transactions enforce owner replay deletion and tombstone`() = withDatabase { connection ->
        val dataSource = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(dataSource)
        val store = JdbcTradingReviewStore(jdbc)
        val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
        members(jdbc)
        val member = object : TradingMemberProvider {
            var id = MemberId(1)
            override fun currentMemberId() = id
        }
        val ids = object : TradingIdGenerator {
            var next = 1000L
            override fun accountId() = TradingAccountId(next++)
            override fun bookId() = TradingBookId(next++)
            override fun deletionId() = next++
        }
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val service = TradingReviewService(member, ids, store, store, store, store, clock)
        val account = transactions.execute { service.create(TradingVenue.BINANCE, "Primary", "create-account") }
        assertEquals(account.id, transactions.execute {
            service.create(TradingVenue.BINANCE, "Primary", "create-account")
        }.id)
        assertEquals("IDEMPOTENCY_PAYLOAD_CONFLICT", assertFailsWith<TradingFailure> {
            transactions.execute { service.create(TradingVenue.BINANCE, "Different", "create-account") }
        }.code)
        member.id = MemberId(2)
        assertEquals(404, assertFailsWith<TradingFailure> { service.get(account.id) }.httpStatus)
        assertEquals(404, assertFailsWith<TradingFailure> {
            transactions.execute { service.create(account.id, "Other", TradingProductFamily.LINEAR_PERPETUAL,
                TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "foreign-book") }
        }.httpStatus)
        member.id = MemberId(1)
        val book = transactions.execute { service.create(account.id, "Futures", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "create-book") }
        assertEquals(book.id, transactions.execute { service.create(account.id, "Futures", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "create-book") }.id)
        assertEquals(422, assertFailsWith<TradingFailure> {
            transactions.execute { service.create(account.id, "Bad timezone", TradingProductFamily.LINEAR_PERPETUAL,
                TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "+09:00", "invalid-timezone") }
        }.httpStatus)
        val deletion = transactions.execute { service.delete(account.id, "delete-account") }
        assertEquals(1L, deletion.deletionGeneration)
        assertEquals(410, assertFailsWith<TradingFailure> { service.get(account.id) }.httpStatus)
        assertEquals(410, assertFailsWith<TradingFailure> {
            transactions.execute { service.create(account.id, "Blocked", TradingProductFamily.LINEAR_PERPETUAL,
                TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "new-book") }
        }.httpStatus)
        TradingDeletionWorker(jdbc, transactions, emptyStorage(), clock).purgePending()
        assertEquals("COMPLETED", service.get(deletion.id).state)
        assertEquals(410, assertFailsWith<TradingFailure> { service.get(book.id) }.httpStatus)
        member.id = MemberId(2)
        assertEquals(404, assertFailsWith<TradingFailure> { service.get(book.id) }.httpStatus)
    }

    @Test
    fun `failed primary purge remains inaccessible and does not report completion`() = withDatabase { connection ->
        val dataSource = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(dataSource)
        val store = JdbcTradingReviewStore(jdbc)
        members(jdbc)
        val account = TradingAccount.create(TradingAccountId(301), MemberId(1), TradingVenue.BINANCE, "Account", now)
        store.insert(account)
        val book = TradingBook.create(TradingBookId(302), account, "Book", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
        store.insert(book)
        assertTrue(store.update(book.requestDeletion(now)))
        store.insert(TradingDeletion(303, MemberId(1), "TRADING_BOOK", book.id.value, "BOOK_SUBTREE",
            "REQUESTED", 1, 0, 1, now, now, now, now.plusSeconds(30L * 24 * 3600)))
        jdbc.execute("CREATE TABLE blocking_child (id BIGINT PRIMARY KEY, book_id BIGINT NOT NULL REFERENCES trading_books(id))")
        jdbc.update("INSERT INTO blocking_child(id, book_id) VALUES (1, 302)")
        val worker = TradingDeletionWorker(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)),
            emptyStorage(), Clock.fixed(now, ZoneOffset.UTC))
        repeat(5) { worker.purgePending() }
        val result = store.find(303L, MemberId(1))!!
        assertEquals("FAILED", result.state)
        assertEquals("DELETION_PARTIALLY_COMPLETED", result.failureCode)
        assertNotNull(store.find(book.id, MemberId(1)))
    }

    @Test
    fun importUploadReplayConflictAndDeletionUseRealPostgres(@org.junit.jupiter.api.io.TempDir root: java.nio.file.Path) = withDatabase { connection ->
        val dataSource = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(dataSource)
        val accountStore = JdbcTradingReviewStore(jdbc)
        val importStore = JdbcTradingImportStore(jdbc)
        val transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
        members(jdbc)
        val account = TradingAccount.create(TradingAccountId(601), MemberId(1), TradingVenue.BINANCE, "A", now)
        accountStore.insert(account)
        val book = TradingBook.create(TradingBookId(602), account, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
        accountStore.insert(book)
        val ids = object : TradingIdGenerator {
            var next = 603L
            override fun accountId() = TradingAccountId(next++)
            override fun bookId() = TradingBookId(next++)
            override fun importSessionId() = TradingImportSessionId(next++)
            override fun sourceArtifactId() = TradingSourceArtifactId(next++)
            override fun deletionId() = next++
        }
        val storage = com.refinvest.core.tradingreview.adapter.storage.LocalEncryptedRawArtifactStorage(root,
            javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"), "test-v1")
        val service = com.refinvest.core.tradingreview.application.TradingImportService(
            object : TradingMemberProvider { override fun currentMemberId() = MemberId(1) }, ids, accountStore, accountStore, importStore, importStore,
            accountStore, accountStore, storage, object : com.refinvest.core.tradingreview.port.TradingVersionPins {
                override fun currentJson() = "{\"versionSetHash\":\"exact\"}"
            }, transactions, Clock.fixed(now, ZoneOffset.UTC), noPublished)
        val session = transactions.execute { service.create(book.id, "UTC", "UTC", TradingPositionMode.ONE_WAY,
            null, null, "create-import") }
        assertEquals(session.id, transactions.execute { service.create(book.id, "UTC", "UTC", TradingPositionMode.ONE_WAY,
            null, null, "create-import") }.id)
        assertEquals("IDEMPOTENCY_PAYLOAD_CONFLICT", assertFailsWith<TradingFailure> {
            transactions.execute { service.create(book.id, "Asia/Seoul", "UTC", TradingPositionMode.ONE_WAY,
                null, null, "create-import") }
        }.code)
        val tradeBytes = "synthetic,one\n".toByteArray()
        val positionBytes = "synthetic,two\n".toByteArray()
        fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        fun upload(role: TradingArtifactRole, bytes: ByteArray, key: String) = service.upload(session.id, role,
            "sample.csv", "text/csv", bytes.size.toLong(), sha(bytes), "AUTO",
            java.io.ByteArrayInputStream(bytes), key)
        val trade = upload(TradingArtifactRole.TRADE_HISTORY, tradeBytes, "trade-first")
        assertTrue(trade.created)
        assertEquals(null, importStore.find(trade.artifact.id, MemberId(1))?.filename)
        assertFalse(service.get(session.id).session.canValidate(importStore.list(session.id)))
        val replay = upload(TradingArtifactRole.TRADE_HISTORY, tradeBytes, "trade-second")
        assertFalse(replay.created)
        assertEquals(trade.artifact.id, replay.artifact.id)
        assertEquals("ARTIFACT_ROLE_CONFLICT", assertFailsWith<TradingFailure> {
            upload(TradingArtifactRole.TRADE_HISTORY, "other\n".toByteArray(), "trade-conflict")
        }.code)
        val position = upload(TradingArtifactRole.POSITION_HISTORY, positionBytes, "position-first")
        assertTrue(position.created)
        assertTrue(service.get(session.id).session.canValidate(importStore.list(session.id)))
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM trading_source_artifacts", Long::class.java))
        val rawDelete = transactions.execute { service.deleteArtifact(position.artifact.id, "delete-raw") }
        assertEquals(rawDelete.id, transactions.execute { service.deleteArtifact(position.artifact.id, "delete-raw-again") }.id)
        TradingDeletionWorker(jdbc, transactions, storage, Clock.fixed(now, ZoneOffset.UTC)).purgePending()
        assertEquals("COMPLETED", accountStore.find(rawDelete.id, MemberId(1))?.state)
        assertFalse(storage.exists(position.artifact.objectKey!!))
        assertFalse(service.get(session.id).session.canValidate(importStore.list(session.id)))
        assertEquals(null, jdbc.queryForObject("SELECT wrapped_dek FROM trading_source_artifacts WHERE id=?",
            ByteArray::class.java, position.artifact.id.value))
        val deletion = transactions.execute { service.delete(session.id, "delete-import") }
        assertEquals(deletion.id, transactions.execute { service.delete(session.id, "delete-import-again") }.id)
        assertEquals(410, assertFailsWith<TradingFailure> { service.get(session.id) }.httpStatus)
        TradingDeletionWorker(jdbc, transactions, storage, Clock.fixed(now, ZoneOffset.UTC)).purgePending()
        assertEquals("COMPLETED", accountStore.find(deletion.id, MemberId(1))?.state)
        assertFalse(storage.exists(trade.artifact.objectKey!!))
        assertFalse(storage.exists(position.artifact.objectKey!!))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_source_artifacts", Long::class.java))
    }

    @Test
    fun concurrentUploadsPublishOneArtifactPerRole(@org.junit.jupiter.api.io.TempDir root: java.nio.file.Path) = withDatabase { setup ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(setup, true))
        members(jdbc)
        val baseStore = JdbcTradingReviewStore(jdbc)
        val account = TradingAccount.create(TradingAccountId(701), MemberId(1), TradingVenue.BINANCE, "A", now)
        baseStore.insert(account)
        val book = TradingBook.create(TradingBookId(702), account, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
        baseStore.insert(book)
        val session = TradingImportSession.create(TradingImportSessionId(703), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), "{}", now)
        JdbcTradingImportStore(jdbc).insert(session)
        val storage = com.refinvest.core.tradingreview.adapter.storage.LocalEncryptedRawArtifactStorage(root,
            javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"), "test-v1")
        val nextId = java.util.concurrent.atomic.AtomicLong(704)
        fun runPair(firstBytes: ByteArray, secondBytes: ByteArray, role: TradingArtifactRole): List<Result<com.refinvest.core.tradingreview.port.UploadArtifactResult>> {
            val start = java.util.concurrent.CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val inputs = listOf(firstBytes, secondBytes)
                val futures = inputs.mapIndexed { index, bytes -> executor.submit<Result<com.refinvest.core.tradingreview.port.UploadArtifactResult>> {
                    DriverManager.getConnection(System.getenv("TRADING_REVIEW_TEST_JDBC_URL"),
                        System.getenv("TRADING_REVIEW_TEST_DB_USER"), System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD")).use { connection ->
                        connection.schema = setup.schema
                        val dataSource = SingleConnectionDataSource(connection, true)
                        val workerJdbc = JdbcTemplate(dataSource)
                        val reviewStore = JdbcTradingReviewStore(workerJdbc)
                        val importStore = JdbcTradingImportStore(workerJdbc)
                        val ids = object : TradingIdGenerator {
                            override fun accountId() = TradingAccountId(nextId.getAndIncrement())
                            override fun bookId() = TradingBookId(nextId.getAndIncrement())
                            override fun sourceArtifactId() = TradingSourceArtifactId(nextId.getAndIncrement())
                            override fun deletionId() = nextId.getAndIncrement()
                        }
                        val service = com.refinvest.core.tradingreview.application.TradingImportService(
                            object : TradingMemberProvider { override fun currentMemberId() = MemberId(1) },
                            ids, reviewStore, reviewStore, importStore, importStore, reviewStore, reviewStore,
                            storage, object : com.refinvest.core.tradingreview.port.TradingVersionPins {
                                override fun currentJson() = "{}"
                            }, TransactionTemplate(DataSourceTransactionManager(dataSource)),
                            Clock.fixed(now, ZoneOffset.UTC), noPublished)
                        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                            .joinToString("") { "%02x".format(it) }
                        start.await(10, TimeUnit.SECONDS)
                        runCatching { service.upload(session.id, role, "synthetic.csv", "text/csv",
                            bytes.size.toLong(), hash, "AUTO", java.io.ByteArrayInputStream(bytes), "race-$role-$index") }
                    }
                } }
                start.countDown()
                return futures.map { it.get(20, TimeUnit.SECONDS) }
            } finally { executor.shutdownNow() }
        }
        val same = runPair("same\n".toByteArray(), "same\n".toByteArray(), TradingArtifactRole.TRADE_HISTORY)
        assertTrue(same.all { it.isSuccess })
        assertEquals(1, same.count { it.getOrThrow().created })
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_source_artifacts WHERE artifact_role = 'TRADE_HISTORY'", Long::class.java))
        val different = runPair("first\n".toByteArray(), "second\n".toByteArray(), TradingArtifactRole.POSITION_HISTORY)
        assertEquals(1, different.count { it.isSuccess })
        assertEquals("ARTIFACT_ROLE_CONFLICT", (different.single { it.isFailure }.exceptionOrNull() as TradingFailure).code)
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_source_artifacts WHERE artifact_role = 'POSITION_HISTORY'", Long::class.java))
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM trading_source_artifacts", Long::class.java))
        java.nio.file.Files.list(root.resolve("staging")).use { assertEquals(0L, it.count()) }
        java.nio.file.Files.list(root.resolve("objects")).use { assertEquals(2L, it.count()) }
    }

    @Test
    fun deletionDuringStreamingUploadBlocksPublicationAndRemovesTheObject(
        @org.junit.jupiter.api.io.TempDir root: java.nio.file.Path,
    ) = withDatabase { setup ->
        val dataSource = SingleConnectionDataSource(setup, true)
        val jdbc = JdbcTemplate(dataSource)
        members(jdbc)
        val reviewStore = JdbcTradingReviewStore(jdbc)
        val importStore = JdbcTradingImportStore(jdbc)
        val account = TradingAccount.create(TradingAccountId(801), MemberId(1), TradingVenue.BINANCE, "A", now)
        reviewStore.insert(account)
        val book = TradingBook.create(TradingBookId(802), account, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
        reviewStore.insert(book)
        val session = TradingImportSession.create(TradingImportSessionId(803), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), "{}", now)
        importStore.insert(session)
        val storage = com.refinvest.core.tradingreview.adapter.storage.LocalEncryptedRawArtifactStorage(root,
            javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"), "test-v1")
        val nextId = java.util.concurrent.atomic.AtomicLong(804)
        fun service(connection: Connection): com.refinvest.core.tradingreview.application.TradingImportService {
            val source = SingleConnectionDataSource(connection, true)
            val connectionJdbc = JdbcTemplate(source)
            val reviews = JdbcTradingReviewStore(connectionJdbc)
            val imports = JdbcTradingImportStore(connectionJdbc)
            val ids = object : TradingIdGenerator {
                override fun accountId() = TradingAccountId(nextId.getAndIncrement())
                override fun bookId() = TradingBookId(nextId.getAndIncrement())
                override fun sourceArtifactId() = TradingSourceArtifactId(nextId.getAndIncrement())
                override fun deletionId() = nextId.getAndIncrement()
            }
            return com.refinvest.core.tradingreview.application.TradingImportService(
                object : TradingMemberProvider { override fun currentMemberId() = MemberId(1) }, ids,
                reviews, reviews, imports, imports, reviews, reviews, storage,
                object : com.refinvest.core.tradingreview.port.TradingVersionPins { override fun currentJson() = "{}" },
                TransactionTemplate(DataSourceTransactionManager(source)), Clock.fixed(now, ZoneOffset.UTC),
                noPublished)
        }
        val reading = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val raw = "synthetic\n".toByteArray()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val upload = executor.submit<Result<com.refinvest.core.tradingreview.port.UploadArtifactResult>> {
                DriverManager.getConnection(System.getenv("TRADING_REVIEW_TEST_JDBC_URL"),
                    System.getenv("TRADING_REVIEW_TEST_DB_USER"), System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD")).use { connection ->
                    connection.schema = setup.schema
                    val stream = object : java.io.ByteArrayInputStream(raw) {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            reading.countDown()
                            check(resume.await(10, TimeUnit.SECONDS))
                            return super.read(buffer, offset, length)
                        }
                    }
                    runCatching { service(connection).upload(session.id, TradingArtifactRole.TRADE_HISTORY,
                        "synthetic.csv", "text/csv", raw.size.toLong(), hash, "AUTO", stream, "upload-race") }
                }
            }
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            val deletion = TransactionTemplate(DataSourceTransactionManager(dataSource)).execute {
                service(setup).delete(session.id, "delete-race")
            }
            assertNotNull(deletion)
            resume.countDown()
            val outcome = upload.get(20, TimeUnit.SECONDS)
            assertTrue(outcome.isFailure)
            assertEquals("RESOURCE_STATE_CONFLICT", (outcome.exceptionOrNull() as TradingFailure).code)
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_source_artifacts", Long::class.java))
            java.nio.file.Files.list(root.resolve("staging")).use { assertEquals(0L, it.count()) }
            java.nio.file.Files.list(root.resolve("objects")).use { assertEquals(0L, it.count()) }
            TradingDeletionWorker(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)), storage,
                Clock.fixed(now, ZoneOffset.UTC)).purgePending()
            assertEquals("COMPLETED", reviewStore.find(deletion.id, MemberId(1))?.state)
        } finally { resume.countDown(); executor.shutdownNow() }
    }

    @Test
    fun parentDeletionWinsAgainstSessionCreation() = withDatabase { setup ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(setup, true))
        members(jdbc)
        val store = JdbcTradingReviewStore(jdbc)
        for ((index, target) in listOf("account", "book").withIndex()) {
            val accountId = TradingAccountId(901 + index * 10L)
            val bookId = TradingBookId(902 + index * 10L)
            val account = TradingAccount.create(accountId, MemberId(1), TradingVenue.BINANCE, "A", now)
            store.insert(account)
            val book = TradingBook.create(bookId, account, "B", TradingProductFamily.LINEAR_PERPETUAL,
                TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
            store.insert(book)
            setup.autoCommit = false
            val table = if (target == "account") "trading_accounts" else "trading_books"
            val targetId = if (target == "account") accountId.value else bookId.value
            jdbc.update("UPDATE $table SET status='DELETION_PENDING', deletion_generation=deletion_generation+1 WHERE id=?",
                targetId)
            val started = java.util.concurrent.CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val creation = executor.submit<Result<TradingImportSession>> {
                    DriverManager.getConnection(System.getenv("TRADING_REVIEW_TEST_JDBC_URL"),
                        System.getenv("TRADING_REVIEW_TEST_DB_USER"), System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD")).use { connection ->
                        connection.schema = setup.schema
                        val dataSource = SingleConnectionDataSource(connection, true)
                        val workerJdbc = JdbcTemplate(dataSource)
                        val reviews = JdbcTradingReviewStore(workerJdbc)
                        val imports = JdbcTradingImportStore(workerJdbc)
                        val ids = object : TradingIdGenerator {
                            override fun accountId() = TradingAccountId(9999)
                            override fun bookId() = TradingBookId(9999)
                            override fun importSessionId() = TradingImportSessionId(903 + index * 10L)
                            override fun deletionId() = 9999L
                        }
                        val storage = emptyStorage()
                        val service = com.refinvest.core.tradingreview.application.TradingImportService(
                            object : TradingMemberProvider { override fun currentMemberId() = MemberId(1) },
                            ids, reviews, reviews, imports, imports, reviews, reviews, storage,
                            object : com.refinvest.core.tradingreview.port.TradingVersionPins {
                                override fun currentJson() = "{}"
                            }, TransactionTemplate(DataSourceTransactionManager(dataSource)),
                            Clock.fixed(now, ZoneOffset.UTC), noPublished)
                        started.countDown()
                        runCatching { TransactionTemplate(DataSourceTransactionManager(dataSource)).execute {
                            service.create(bookId, "UTC", "UTC", TradingPositionMode.ONE_WAY, null, null,
                                "create-$target")
                        } }
                    }
                }
                assertTrue(started.await(10, TimeUnit.SECONDS))
                Thread.sleep(150)
                assertFalse(creation.isDone)
                setup.commit()
                val failure = creation.get(10, TimeUnit.SECONDS).exceptionOrNull() as TradingFailure
                assertEquals("RESOURCE_DELETED", failure.code)
                assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_import_sessions", Long::class.java))
            } finally {
                setup.rollback()
                setup.autoCommit = true
                executor.shutdownNow()
            }
        }
    }

    private fun emptyStorage() = object : com.refinvest.core.tradingreview.port.RawArtifactStorage {
        override fun putStaging(source: java.io.InputStream, maxBytes: Long, artifactId: TradingSourceArtifactId):
            com.refinvest.core.tradingreview.port.StagedRawArtifact = error("Unused")
        override fun promote(staged: com.refinvest.core.tradingreview.port.StagedRawArtifact, finalKey: String) = Unit
        override fun completePublication(finalKey: String) = Unit
        override fun open(key: String, wrappedDek: ByteArray, keyVersion: String,
                          artifactId: TradingSourceArtifactId): java.io.InputStream = error("Unused")
        override fun delete(key: String) = Unit
        override fun exists(key: String) = false
        override fun cleanupOrphans(olderThan: Instant, limit: Int, isReferenced: (String) -> Boolean) = 0
    }

    @Test
    fun sourceFailuresRejectWithoutRevisionOrLatestPointerUpdate() = withDatabase { connection ->
        val source = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(source)
        val transactions = TransactionTemplate(DataSourceTransactionManager(source))
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        members(jdbc)
        val review = JdbcTradingReviewStore(jdbc)
        val imports = JdbcTradingImportStore(jdbc)
        val runs = JdbcTradingNormalizationStore(jdbc, mapper)
        val account = TradingAccount.create(TradingAccountId(2001), MemberId(1), TradingVenue.BINANCE, "A", now)
        review.insert(account)
        val book = TradingBook.create(TradingBookId(2002), account, "B",
            TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY,
            TradingSettlementAsset.USDT, "UTC", now)
        review.insert(book)
        val codes = listOf("FILE_FORMAT_INVALID", "REQUIRED_COLUMN_MISSING",
            "UNSUPPORTED_SOURCE_SCHEMA", "UNSUPPORTED_PRODUCT", "UNSUPPORTED_POSITION_MODE",
            "RECORD_CONFLICT", "RESULT_HASH_MISMATCH")
        for ((index, code) in codes.withIndex()) {
            val session = TradingImportSession.create(TradingImportSessionId(2010L + index), book,
                "UTC", "UTC", TradingPositionMode.ONE_WAY, TradingDataPolicy(), normalizationVersions, now)
            imports.insert(session)
            val artifactInputs = TradingArtifactRole.entries.mapIndexed { roleIndex, role ->
                val artifactId = TradingSourceArtifactId(2100L + index * 2 + roleIndex)
                val sha = (if (roleIndex == 0) "a" else "b").repeat(64)
                imports.insert(TradingSourceArtifact(artifactId, session.id, role, null, "text/csv",
                    20, sha, "AUTO", "objects/${UUID.randomUUID()}", byteArrayOf(1), "test-v1",
                    TradingArtifactRetentionStatus.ACTIVE, now, now.plusSeconds(3600), null, null, 0))
                NormalizationArtifactInput(artifactId, role, sha, 20, "text/csv", "AUTO")
            }
            val input = NormalizationInput(book.id, session.id, 1, artifactInputs, "BINANCE",
                "LINEAR_PERPETUAL", "ONE_WAY", "USDT", "UTC", "UTC", normalizationVersions, "[]",
                "TRADING_DATA_POLICY_V1", "retry-v1",
                """{"policyVersion":"retry-v1","maxAttempts":3,"maxElapsedTime":"PT1H","initialDelay":"PT1S","maxDelay":"PT10S","jitter":"NONE"}""", 0)
            val identity = NormalizationInputIdentity.fromPinnedInput(input,
                mapper.readValue(normalizationVersions, Map::class.java))
            val runId = UUID.randomUUID().toString()
            val attemptToken = UUID.randomUUID().toString()
            val jobId = "job-$index"
            val run = NormalizationRun(runId, input, NormalizationInputCanonicalizer.hash(identity),
                UUID.randomUUID().toString().replace("-", "").repeat(2), NormalizationRunStatus.SUBMITTED,
                1, attemptToken, jobId, null, now, now, now, now, null, 0,
                inputIdentity = identity, resultDeliveryPolicy = deliveryPolicy)
            val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId,
                "c".repeat(64), NormalizationDispatchStatus.SUBMITTED, 0, now,
                null, null, null, now, now)
            runs.insert(run, dispatch)
            assertTrue(imports.update(session.beginValidation(runId, now)))
            val compute = object : TradingNormalizationComputeClient {
                override fun submit(run: NormalizationRun, dispatch: NormalizationDispatch,
                                    grants: List<ArtifactReadGrant>): NormalizationSubmission = error("poll only")
                override fun status(run: NormalizationRun, dispatch: NormalizationDispatch): NormalizationStatusLookup =
                    NormalizationStatusLookup.Found(NormalizationJobStatus(jobId, "FAILED", attemptToken,
                        0, 0, run.inputFingerprint, "f".repeat(64), "NONE", null, code))
                override fun updateGrants(run: NormalizationRun, dispatch: NormalizationDispatch,
                                          current: NormalizationJobStatus, nextAttemptToken: String,
                                          grants: List<ArtifactReadGrant>, updateKey: String): NormalizationGrantUpdate =
                    error("no grant update")
            }
            val dispatcher = com.refinvest.core.tradingreview.application.TradingNormalizationDispatcher(
                runs, imports, review, imports, object : ArtifactAccessGrantIssuer {
                    override fun issueReadGrant(artifact: NormalizationArtifactInput, logicalJobId: String,
                                                sessionId: TradingImportSessionId, attemptToken: String,
                                                deletionGeneration: Long): ArtifactReadGrant = error("poll only")
                }, compute, object : TradingRetryJitter { override fun factor() = 0.5 },
                transactions, Clock.fixed(now, ZoneOffset.UTC), mapper)
            assertTrue(dispatcher.pollOne(), code)
            val terminal = assertNotNull(imports.find(session.id, MemberId(1)))
            val expected = if (index < 6) TradingImportStatus.REJECTED else TradingImportStatus.PROCESSING_FAILED
            assertEquals(expected, terminal.status, code)
            assertEquals(code, terminal.failure?.code, code)
            assertEquals(NormalizationRunStatus.TERMINAL_FAILURE, runs.findRun(runId)?.status, code)
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
            assertNull(jdbc.queryForObject("SELECT latest_ledger_revision_id FROM trading_books WHERE id=?",
                String::class.java, book.id.value))
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_ack_dispatches",
                Long::class.java))
        }
    }

    private fun members(jdbc: JdbcTemplate) {
        jdbc.update("INSERT INTO members(id, role, created_at) VALUES (1, 'USER', CURRENT_TIMESTAMP)")
        jdbc.update("INSERT INTO members(id, role, created_at) VALUES (2, 'USER', CURRENT_TIMESTAMP)")
    }

    private fun withDatabase(test: (Connection) -> Unit) {
        val url = requireNotNull(System.getenv("TRADING_REVIEW_TEST_JDBC_URL"))
        val user = requireNotNull(System.getenv("TRADING_REVIEW_TEST_DB_USER"))
        val password = requireNotNull(System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD"))
        DriverManager.getConnection(url, user, password).use { connection ->
            val schema = "trading_test_" + UUID.randomUUID().toString().replace("-", "")
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            try {
                connection.schema = schema
                val migration = javaClass.getResourceAsStream("/db/migration/V1__trading_account_book.sql")!!
                    .bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    migration.split(';').map(String::trim).filter(String::isNotEmpty).forEach(statement::execute)
                }
                val migration2 = javaClass.getResourceAsStream("/db/migration/V2__trading_import_artifacts.sql")!!
                    .bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    migration2.split(';').map(String::trim).filter(String::isNotEmpty).forEach(statement::execute)
                }
                val migration3 = javaClass.getResourceAsStream("/db/migration/V3__trading_normalization_dispatch.sql")!!
                    .bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    migration3.split(';').map(String::trim).filter(String::isNotEmpty).forEach(statement::execute)
                }
                val migration4 = javaClass.getResourceAsStream("/db/migration/V4__trading_normalization_input_identity.sql")!!
                    .bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    migration4.split(';').map(String::trim).filter(String::isNotEmpty).forEach(statement::execute)
                }
                val migration5 = javaClass.getResourceAsStream("/db/migration/V5__trading_normalization_publication.sql")!!
                    .bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    splitSqlStatements(migration5).forEach(statement::execute)
                }
                val migration6 = javaClass.getResourceAsStream(
                    "/db/migration/V6__trading_result_delivery_policy.sql")!!
                    .bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    splitSqlStatements(migration6).forEach(statement::execute)
                }
                test(connection)
            } finally {
                connection.schema = "public"
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    private fun splitSqlStatements(sql: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var inDollarQuote = false
        var position = 0
        while (position < sql.length) {
            if (sql.startsWith("$$", position)) {
                inDollarQuote = !inDollarQuote
                current.append("$$")
                position += 2
            } else {
                val char = sql[position++]
                if (char == ';' && !inDollarQuote) {
                    current.toString().trim().takeIf(String::isNotEmpty)?.let(statements::add)
                    current.setLength(0)
                } else current.append(char)
            }
        }
        current.toString().trim().takeIf(String::isNotEmpty)?.let(statements::add)
        return statements
    }
}
