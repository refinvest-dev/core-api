package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.tradingreview.application.normalization.NormalizationRetrySchedule
import com.refinvest.core.tradingreview.application.normalization.NormalizationWorkerLifecycle
import com.refinvest.core.tradingreview.application.normalization.dispatch.DispatchTradingNormalizationService
import com.refinvest.core.tradingreview.application.normalization.poll.PollTradingNormalizationService

import com.refinvest.core.tradingreview.adapter.security.HmacArtifactGrantTokens
import com.refinvest.core.tradingreview.adapter.security.SignedArtifactAccessGrantIssuer
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionRemediation
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.account.create.CreateTradingAccountCommand
import com.refinvest.core.tradingreview.port.inbound.account.delete.DeleteTradingAccountCommand
import com.refinvest.core.tradingreview.port.inbound.account.get.GetTradingAccountQuery
import com.refinvest.core.tradingreview.port.inbound.artifactgrant.redeem.RedeemTradingArtifactGrantCommand
import com.refinvest.core.tradingreview.port.inbound.book.TradingBookResult
import com.refinvest.core.tradingreview.port.inbound.book.create.CreateTradingBookCommand
import com.refinvest.core.tradingreview.port.inbound.book.delete.DeleteTradingBookCommand
import com.refinvest.core.tradingreview.port.inbound.book.get.GetTradingBookQuery
import com.refinvest.core.tradingreview.port.inbound.deletion.PurgeComponentStatus
import com.refinvest.core.tradingreview.port.inbound.deletion.TradingDeletion
import com.refinvest.core.tradingreview.port.inbound.deletion.get.GetTradingDeletionRequestQuery
import com.refinvest.core.tradingreview.port.inbound.importsession.TradingImportSessionResult
import com.refinvest.core.tradingreview.port.inbound.importsession.create.CreateTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.inbound.importsession.delete.DeleteTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.inbound.importsession.deleteartifact.DeleteTradingSourceArtifactCommand
import com.refinvest.core.tradingreview.port.inbound.importsession.get.GetTradingImportSessionQuery
import com.refinvest.core.tradingreview.port.inbound.importsession.upload.TradingUploadMetadata
import com.refinvest.core.tradingreview.port.inbound.importsession.upload.UploadArtifactResult
import com.refinvest.core.tradingreview.port.inbound.importsession.upload.UploadTradingImportArtifactCommand
import com.refinvest.core.tradingreview.port.inbound.normalization.acknowledge.AcknowledgeTradingNormalizationResultUseCase
import com.refinvest.core.tradingreview.port.inbound.normalization.retry.RetryTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.inbound.normalization.validate.ValidateTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.outbound.artifactgrant.ArtifactAccessGrantIssuer
import com.refinvest.core.tradingreview.port.outbound.artifactgrant.ArtifactReadGrant
import com.refinvest.core.tradingreview.port.outbound.compute.deletion.TradingRuntimeDeletion
import com.refinvest.core.tradingreview.port.outbound.compute.deletion.TradingRuntimeDeletionClient
import com.refinvest.core.tradingreview.port.outbound.compute.deletion.TradingRuntimeDeletionOutcome
import com.refinvest.core.tradingreview.port.outbound.compute.normalization.NormalizationGrantUpdate
import com.refinvest.core.tradingreview.port.outbound.compute.normalization.NormalizationJobStatus
import com.refinvest.core.tradingreview.port.outbound.compute.normalization.NormalizationStatusLookup
import com.refinvest.core.tradingreview.port.outbound.compute.normalization.NormalizationSubmission
import com.refinvest.core.tradingreview.port.outbound.compute.normalization.TradingNormalizationComputeClient
import com.refinvest.core.tradingreview.port.outbound.compute.result.NormalizationAckOutcome
import com.refinvest.core.tradingreview.port.outbound.compute.result.NormalizationPayloadFile
import com.refinvest.core.tradingreview.port.outbound.compute.result.NormalizationPayloadLookup
import com.refinvest.core.tradingreview.port.outbound.compute.result.NormalizationResultDescriptor
import com.refinvest.core.tradingreview.port.outbound.compute.result.NormalizationResultLookup
import com.refinvest.core.tradingreview.port.outbound.compute.result.TradingNormalizationResultClient
import com.refinvest.core.tradingreview.port.outbound.idempotency.IdempotencyClaim
import com.refinvest.core.tradingreview.port.outbound.identity.LedgerRevisionIdGenerator
import com.refinvest.core.tradingreview.port.outbound.identity.TradingIdGenerator
import com.refinvest.core.tradingreview.port.outbound.identity.TradingNormalizationIdGenerator
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingNormalizationRetryPolicy
import com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingNormalizationSettings
import com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingRetryJitter
import com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingVersionPins
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingAccountBookDeletion
import com.refinvest.core.tradingreview.port.outbound.persistence.deletion.DeletionRequestStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.RawArtifactPurgeOutcome
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.RawArtifactRetentionCompletion
import com.refinvest.core.tradingreview.port.outbound.persistence.ledger.LedgerPublication
import com.refinvest.core.tradingreview.port.outbound.persistence.ledger.PublishedLedgerSummary
import com.refinvest.core.tradingreview.port.outbound.persistence.ledger.TradingLedgerPublication
import com.refinvest.core.tradingreview.port.outbound.persistence.ledger.TradingLedgerPublicationReader
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.NormalizationAdmission
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.NormalizationDispatchLease
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.NormalizationGrantAttemptAdvance
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.NormalizationJobRetry
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.NormalizationPayloadAcknowledgement
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.NormalizationResultStoreFailure
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.NormalizationStagedItem
import com.refinvest.core.tradingreview.port.outbound.persistence.normalization.NormalizationSubmissionReceipt
import com.refinvest.core.tradingreview.port.outbound.storage.analytics.AnalyticsInputArtifact
import com.refinvest.core.tradingreview.port.outbound.storage.analytics.TradingAnalyticsArtifactStore
import com.refinvest.core.tradingreview.port.outbound.storage.raw.RawArtifactStorage
import com.refinvest.core.tradingreview.port.outbound.storage.raw.StagedRawArtifact
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.LegacyTradingTemporaryCopies
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingArtifactScope
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingArtifactWorkspaces

import com.refinvest.core.tradingreview.application.artifactgrant.redeem.RedeemTradingArtifactGrantService

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.application.GetTradingDeletionRequestService
import com.refinvest.core.tradingreview.application.NormalizationInputFingerprint
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.importsession.NormalizationArtifactInput
import com.refinvest.core.tradingreview.domain.importsession.NormalizationDispatch
import com.refinvest.core.tradingreview.domain.importsession.NormalizationInput
import com.refinvest.core.tradingreview.domain.importsession.NormalizationRun
import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
import com.refinvest.core.tradingreview.domain.service.NormalizationInputCanonicalizer
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationDispatchStatus
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationInputIdentity
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationResultDeliveryPolicy
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationResultVersionPin
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationRunId
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationRunStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingAccountId
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRetentionStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingDataPolicy
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportFailure
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingLifecycleStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingProductFamily
import com.refinvest.core.tradingreview.domain.valueobject.TradingSettlementAsset
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.domain.valueobject.TradingVenue
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertSame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@EnabledIfEnvironmentVariable(named = "TRADING_REVIEW_TEST_JDBC_URL", matches = ".+")
class PostgresTradingReviewStoreTest {
    @org.junit.jupiter.api.AfterEach
    fun closeJpaContexts() = TradingJpaTestContexts.close()

    @Test
    fun jpaDeletionSnapshotKeepsRootVersionAndCheckpointsInOneFreshStatement() = withDatabase { connection ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        members(jdbc)
        val stores = TradingReviewTestStores(jdbc)
        val request = DeletionRequest.request(DeletionRequestId(85001), MemberId(1), DeletionScope.book(TradingBookId(85002)),
            1, DeletionAffectedResources(0, 1), now)
        stores.deletionAdapter.insert(request)
        val context = TradingJpaTestContexts.context(requireNotNull(jdbc.dataSource))
        val tx = TransactionTemplate(tradingTestTransactions(requireNotNull(jdbc.dataSource)))
        tx.executeWithoutResult {
            val cached = context.getBean(DeletionRequestJpaStore::class.java).findById(request.id.value).orElseThrow()
            val progressed = request.start(now.plusSeconds(1))
                .verifyAbsent(DeletionPurgeComponent.COMPUTE_RUNTIME, now.plusSeconds(1))
            assertTrue(JdbcDeletionRequestExecution(jdbc).commit(request, progressed))
            val statistics = context.getBean(jakarta.persistence.EntityManagerFactory::class.java)
                .unwrap(org.hibernate.SessionFactory::class.java).statistics
            statistics.isStatisticsEnabled = true
            try {
                statistics.clear()
                val fresh = stores.deletionReader.find(request.id, request.owner)!!
                assertEquals(1L, statistics.queryExecutionCount)
                assertEquals(progressed.version, fresh.version)
                assertEquals(progressed.state, fresh.state)
                assertEquals(progressed.verifiedAbsent, fresh.verifiedAbsent)
                assertEquals(0L, cached.version)
            } finally { statistics.isStatisticsEnabled = false }
            assertNull(stores.deletionReader.find(request.id, MemberId(2)))
            val byTarget = stores.deletionAdapter.findByTarget(request.owner, request.scope)!!
            assertEquals(progressed.version, byTarget.version)
            assertEquals(progressed.verifiedAbsent, byTarget.verifiedAbsent)
        }
        val before = jdbc.queryForList("SELECT * FROM trading_deletion_requests")
        assertFailsWith<jakarta.persistence.PersistenceException> { stores.deletionAdapter.insert(request) }
        assertEquals(before, jdbc.queryForList("SELECT * FROM trading_deletion_requests"))
        assertFalse(tx.execute { JdbcDeletionRequestExecution(jdbc).commit(request, request.start(now.plusSeconds(2))) })
        assertEquals(before, jdbc.queryForList("SELECT * FROM trading_deletion_requests"))
    }

    @Test
    fun jpaSessionAndArtifactSnapshotsFenceJdbcCacheChangesAndAdvanceOnlyChangedRows() = withDatabase { connection ->
        val (jdbc, tx, session) = retentionFixture(connection)
        val store = TradingImportTestStores(jdbc)
        val uploaded = session.recordUpload(TradingSourceArtifactId(32101), TradingArtifactRole.TRADE_HISTORY,
            "text/csv", 2, "a".repeat(64), "AUTO", "objects/jpa-snapshot", byteArrayOf(1), "test", now)
        assertTrue(tx.execute { store.update(uploaded) })
        val stale = store.find(session.id, session.owner)!!
        val context = TradingJpaTestContexts.context(requireNotNull(jdbc.dataSource))
        tx.executeWithoutResult {
            val cached = context.getBean(TradingImportSessionJpaStore::class.java).findById(session.id.value).orElseThrow()
            val cachedArtifact = context.getBean(TradingSourceArtifactJpaRows::class.java).findById(32101).orElseThrow()
            jdbc.update("UPDATE trading_import_sessions SET version=version+1 WHERE id=?", session.id.value)
            jdbc.update("UPDATE trading_source_artifacts SET retention_status='RETENTION_SCHEDULED',version=version+1 WHERE id=32101")
            val fresh = store.find(session.id, session.owner)!!
            assertEquals(2L, fresh.version)
            assertEquals(1L, fresh.artifacts.single().version)
            assertEquals(TradingArtifactRetentionStatus.RETENTION_SCHEDULED, fresh.artifacts.single().retentionStatus)
            assertEquals(1L, cached.version)
            assertEquals(0L, cachedArtifact.version)
            val before = importRows(jdbc)
            assertFalse(store.update(stale.requestDeletion(now.plusSeconds(1))))
            assertEquals(before, importRows(jdbc))
            assertTrue(store.update(fresh))
            assertEquals(3L, jdbc.queryForObject("SELECT version FROM trading_import_sessions WHERE id=?", Long::class.java, session.id.value))
            assertEquals(1L, jdbc.queryForObject("SELECT version FROM trading_source_artifacts WHERE id=32101", Long::class.java))
        }
        val beforeDuplicate = importRows(jdbc)
        assertFailsWith<jakarta.persistence.PersistenceException> { store.insert(session) }
        assertEquals(beforeDuplicate, importRows(jdbc))
        jdbc.update("UPDATE trading_source_artifacts SET version=? WHERE id=32101", Long.MAX_VALUE)
        val current = store.find(session.id, session.owner)!!
        val beforeOverflow = importRows(jdbc)
        assertFailsWith<ArithmeticException> { tx.execute { store.update(current.requestArtifactDeletion(TradingSourceArtifactId(32101), now.plusSeconds(1))) } }
        assertEquals(beforeOverflow, importRows(jdbc))
    }

    @Test
    fun jpaSnapshotsRejectStaleSavesAndAdvanceEverySuccessfulSaveExactlyOnce() = withDatabase { connection ->
        val (_, _, stores) = accountBookFixture(connection)
        val createdAt = now.plusNanos(123456789)
        val account = TradingAccount.create(TradingAccountId(31), MemberId(1), TradingVenue.BINANCE, "A", createdAt)
        stores.insert(account)
        val book = TradingBook.create(TradingBookId(32), account, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", createdAt)
        stores.insert(book)
        assertEquals(createdAt.atTradingStoredPrecision(), stores.find(account.id, MemberId(1))!!.createdAt)
        val accountSnapshot = account
        val bookSnapshot = book
        assertTrue(stores.update(accountSnapshot.rename("A2", now.plusSeconds(1))))
        assertFalse(stores.update(accountSnapshot.rename("stale", now.plusSeconds(2))))
        val currentAccount = stores.find(account.id, MemberId(1))!!
        assertEquals(1L, currentAccount.version)
        assertTrue(stores.update(currentAccount))
        assertEquals(2L, stores.find(account.id, MemberId(1))!!.version)
        assertTrue(stores.update(bookSnapshot.rename("B2", now.plusSeconds(1))))
        assertFalse(stores.update(bookSnapshot.rename("stale", now.plusSeconds(2))))
        val currentBook = stores.find(book.id, MemberId(1))!!
        assertEquals(1L, currentBook.version)
        assertTrue(stores.update(currentBook))
        assertEquals(2L, stores.find(book.id, MemberId(1))!!.version)
        assertNull(stores.find(account.id, MemberId(2)))
        assertNull(stores.bookReader.find(book.id, MemberId(2)))
    }

    @Test
    fun jpaCachedRowsCannotHideAJdbcVersionChange() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val account = tx.execute { service.execute(CreateTradingAccountCommand(TradingVenue.BINANCE, "A", "cached-account")) }!!
        val book = tx.execute { service.execute(CreateTradingBookCommand(account.id, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "cached-book")) }!!
        val source = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(source)
        val context = TradingJpaTestContexts.context(source)
        val staleAccount = stores.find(account.id, MemberId(1))!!
        val staleBook = stores.find(book.id, MemberId(1))!!
        tx.executeWithoutResult {
            val cachedAccount = context.getBean(TradingAccountJpaStore::class.java).findOwned(account.id.value, 1)!!
            val cachedBook = context.getBean(TradingBookJpaStore::class.java).findOwned(book.id.value, 1)!!
            jdbc.update("UPDATE trading_accounts SET version=version+1,display_name='JDBC' WHERE id=?", account.id.value)
            jdbc.update("UPDATE trading_books SET version=version+1,display_name='JDBC' WHERE id=?", book.id.value)
            assertEquals(0L, cachedAccount.version)
            assertEquals(0L, cachedBook.version)
            assertEquals("JDBC", stores.accountReader.find(account.id, MemberId(1))!!.displayName)
            assertEquals("JDBC", stores.bookReader.find(book.id, MemberId(1))!!.displayName)
            assertEquals(0L, cachedAccount.version, "Reader snapshots must not reuse a managed cached entity")
            assertEquals(0L, cachedBook.version)
            assertFalse(stores.update(staleAccount.rename("stale", now)))
            assertFalse(stores.update(staleBook.rename("stale", now)))
            assertEquals("JDBC", stores.accountReader.find(account.id, MemberId(1))!!.displayName)
            assertEquals("JDBC", stores.bookReader.find(book.id, MemberId(1))!!.displayName)
        }
        assertEquals(1L, stores.find(account.id, MemberId(1))!!.version)
        assertEquals(1L, stores.find(book.id, MemberId(1))!!.version)
    }

    @Test
    fun jpaNewIdentityInsertCannotMergeOverAnExistingAggregate() = withDatabase { connection ->
        val (_, _, stores) = accountBookFixture(connection)
        val account = TradingAccount.create(TradingAccountId(41), MemberId(1), TradingVenue.BINANCE, "A", now)
        stores.insert(account)
        val book = TradingBook.create(TradingBookId(42), account, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
        stores.insert(book)
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val before = accountBookRows(jdbc)
        assertFailsWith<jakarta.persistence.PersistenceException> { stores.insert(account.rename("replacement", now)) }
        assertEquals(before, accountBookRows(jdbc))
        assertFailsWith<jakarta.persistence.PersistenceException> { stores.insert(book.rename("replacement", now)) }
        assertEquals(before, accountBookRows(jdbc))
    }

    @Test
    fun jpaVersionExhaustionCannotWrapAndPersistAnInvalidSnapshot() = withDatabase { connection ->
        val (_, _, stores) = accountBookFixture(connection)
        val account = TradingAccount.create(TradingAccountId(51), MemberId(1), TradingVenue.BINANCE, "A", now)
        stores.insert(account)
        stores.insert(TradingBook.create(TradingBookId(52), account, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now))
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        jdbc.update("UPDATE trading_accounts SET version=? WHERE id=51", Long.MAX_VALUE)
        jdbc.update("UPDATE trading_books SET version=? WHERE id=52", Long.MAX_VALUE)
        val before = accountBookRows(jdbc)
        assertFailsWith<ArithmeticException> { stores.update(stores.find(account.id, MemberId(1))!!.rename("overflow", now)) }
        assertFailsWith<ArithmeticException> { stores.update(stores.find(TradingBookId(52), MemberId(1))!!.rename("overflow", now)) }
        assertEquals(before, accountBookRows(jdbc))
    }

    @Test
    fun jpaAndJdbcShareDeletionFlushFreshReadsAndIdempotencyRollback() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val account = tx.execute { service.execute(CreateTradingAccountCommand(TradingVenue.BINANCE, "A", "mixed-account")) }!!
        val book = tx.execute { service.execute(CreateTradingBookCommand(account.id, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "mixed-book")) }!!
        val source = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(source)
        val before = accountBookRows(jdbc)
        val stale = stores.find(book.id, MemberId(1))!!
        val repository = TradingJpaTestContexts.context(source).getBean(TradingBookJpaStore::class.java)
        val rollback = IllegalStateException("synthetic rollback after mixed persistence")
        assertSame(rollback, assertFailsWith<IllegalStateException> {
            tx.executeWithoutResult {
                val cached = repository.findOwned(book.id.value, 1)!!
                service.execute(DeleteTradingAccountCommand(account.id, "mixed-delete"))
                assertEquals("DELETION_PENDING", jdbc.queryForObject("SELECT status FROM trading_accounts WHERE id=?", String::class.java, account.id.value))
                assertEquals("ACTIVE", cached.status, "JDBC deliberately bypasses the existing managed Book")
                assertEquals(TradingLifecycleStatus.DELETION_PENDING, stores.find(book.id, MemberId(1))!!.status)
                assertEquals(1L, stores.bookReader.find(book.id, MemberId(1))!!.version)
                assertFalse(stores.update(stale.rename("must not resurrect", now.plusSeconds(1))))
                throw rollback
            }
        })
        assertEquals(before, accountBookRows(jdbc))
        val request = tx.execute { service.execute(DeleteTradingAccountCommand(account.id, "mixed-delete")) }!!
        assertEquals("REQUESTED", request.state)
        assertEquals(1L, stores.find(account.id, MemberId(1))!!.version)
    }

    private val deliveryPolicy = NormalizationResultDeliveryPolicy(
        "RESULT_DELIVERY_V1", "SINGLE_RESOURCE", 52_428_800, listOf("identity"))
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val noPublished = object : TradingLedgerPublicationReader {
        override fun publishedForSession(sessionId: TradingImportSessionId, owner: MemberId): PublishedLedgerSummary? = null
    }
    private val normalizationIds = object : com.refinvest.core.tradingreview.port.outbound.identity.TradingNormalizationIdGenerator {
        override fun runId() = NormalizationRunId(UUID.randomUUID().toString())
        override fun dispatchId() = UUID.randomUUID().toString()
        override fun attemptToken() = UUID.randomUUID().toString()
        override fun grantUpdateKey() = UUID.randomUUID().toString()
    }
    private val normalizationVersions = """{"versionSetHash":"${"f".repeat(64)}","normalizer":{"sourceDialectVersionsByArtifactRole":{"TRADE_HISTORY":"TRADE_V1","POSITION_HISTORY":"POSITION_V1"},"adapterVersion":"ADAPTER_V1","normalizerVersion":"NORMALIZER_V1","canonicalSchemaVersion":"CANONICAL_V1","implementationDigest":"normalizer-digest","versionSetHash":"${"d".repeat(64)}"},"reconstruction":{"instrumentTermsVersionByInstrument":{},"reconstructionPolicyVersion":"RECONSTRUCTION_V1","reconciliationVersion":"RECONCILIATION_V1","reviewUnitSchemaVersion":"REVIEW_UNIT_V1","evidenceSchemaVersion":"EVIDENCE_V1","implementationDigest":"reconstruction-digest","versionSetHash":"${"e".repeat(64)}"}}"""

    @Test
    fun bookDeletionPinsDescendantCountsBeforePurgeAndReplaysThem() = withDatabase { connection ->
        val (service, tx, _) = accountBookFixture(connection)
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val source = grantSource(jdbc, 71900, MemberId(1))
        val request = tx.execute { service.execute(DeleteTradingBookCommand(source.bookId, "count-book")) }!!
        assertEquals(1L, request.importSessionCount)
        assertEquals(1L, request.artifactCount)
        assertEquals("REQUEST_TIME_SNAPSHOT", request.countSnapshotStatus)
        val again = tx.execute { service.execute(DeleteTradingBookCommand(source.bookId, "count-book")) }!!
        assertEquals(request, again)
        deletionWorker(jdbc, tx, emptyStorage(), Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)).purgePending()
        val retained = tx.execute { service.execute(DeleteTradingBookCommand(source.bookId, "count-book")) }!!
        assertEquals("COMPLETED", retained.state)
        assertEquals(1L, retained.importSessionCount)
        assertEquals(1L, retained.artifactCount)
        assertEquals("REQUEST_TIME_SNAPSHOT", retained.countSnapshotStatus)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_import_sessions", Long::class.java))
    }

    @Test
    fun scopeInventoryCountsOwnedResourcesInOneSnapshotAndExcludesNormalizationFromAnalysis() = withDatabase { connection ->
        val (_, tx, _) = accountBookFixture(connection)
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val source = grantSource(jdbc, 72000, MemberId(1))
        grantSource(jdbc, 72010, MemberId(1), source.account)
        grantSource(jdbc, 72100, MemberId(2))
        val inventory = JdbcDeletionScopeInventory(jdbc)
        assertFailsWith<IllegalStateException> { inventory.capture(DeletionScope.account(source.account.id), MemberId(1)) }
        assertEquals(DeletionAffectedResources(1, 2, 2, 2),
            tx.execute { inventory.capture(DeletionScope.account(source.account.id), MemberId(1)) })
        assertEquals(DeletionAffectedResources(0, 1, 1, 1),
            tx.execute { inventory.capture(DeletionScope.book(source.bookId), MemberId(1)) })
        assertEquals(DeletionAffectedResources(0, 0, 1, 1),
            tx.execute { inventory.capture(DeletionScope.importSession(source.sessionId), MemberId(1)) })
        assertEquals(DeletionAffectedResources(0, 0, 0, 1),
            tx.execute { inventory.capture(DeletionScope.rawArtifact(source.artifact.artifactId), MemberId(1)) })
        for (scope in listOf(DeletionScope.account(source.account.id), DeletionScope.book(source.bookId),
                DeletionScope.importSession(source.sessionId), DeletionScope.rawArtifact(source.artifact.artifactId)))
            assertNull(tx.execute { inventory.capture(scope, MemberId(2)) })
        val empty = TradingAccount.create(TradingAccountId(72500), MemberId(1), TradingVenue.BINANCE, "empty", now)
        TradingReviewTestStores(jdbc).accountAdapter.insert(empty)
        assertEquals(DeletionAffectedResources(1, 0), tx.execute { inventory.capture(DeletionScope.account(empty.id), MemberId(1)) })
    }

    @Test
    fun countSnapshotAndTombstoneRollbackTogetherAndTheRetryCapturesCurrentScope() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val source = grantSource(jdbc, 72200, MemberId(1))
        tx.execute { status ->
            assertEquals(1L, service.execute(DeleteTradingAccountCommand(source.account.id, "rollback-count")).bookCount)
            status.setRollbackOnly()
        }
        assertNull(stores.deletionAdapter.findByTarget(MemberId(1), DeletionScope.account(source.account.id)))
        assertEquals(TradingLifecycleStatus.ACTIVE, stores.find(source.account.id, MemberId(1))?.status)
        grantSource(jdbc, 72210, MemberId(1), source.account)
        val request = tx.execute { service.execute(DeleteTradingAccountCommand(source.account.id, "rollback-count")) }!!
        assertEquals(2L, request.bookCount)
        assertEquals(2L, request.importSessionCount)
        assertEquals(2L, request.artifactCount)
        assertEquals(request, tx.execute { service.execute(DeleteTradingAccountCommand(source.account.id, "rollback-count")) })
    }

    @Test
    fun accountCountSnapshotFencesABookCreationWaitingOnTheSameParent() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val source = grantSource(jdbc, 72400, MemberId(1))
        val executor = Executors.newSingleThreadExecutor()
        val backend = java.util.concurrent.CompletableFuture<Int>()
        val schema = connection.schema
        var creation: java.util.concurrent.Future<Result<TradingBookResult>>? = null
        try {
            val request = tx.execute {
                assertNotNull(stores.find(source.account.id, MemberId(1), true))
                creation = executor.submit<Result<TradingBookResult>> {
                    DriverManager.getConnection(System.getenv("TRADING_REVIEW_TEST_JDBC_URL"),
                        System.getenv("TRADING_REVIEW_TEST_DB_USER"), System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD")).use { other ->
                        other.schema = schema
                        val (writer, writerTx, _) = accountBookFixture(other, seedMembers = false)
                        val otherJdbc = JdbcTemplate(SingleConnectionDataSource(other, true))
                        backend.complete(otherJdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                        runCatching { writerTx.execute { writer.execute(CreateTradingBookCommand(source.account.id, "blocked", TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "count-race-create")) }!! }
                    }
                }
                val pid = backend.get(5, TimeUnit.SECONDS)
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var blocked = false
                while (!blocked && System.nanoTime() < deadline) {
                    blocked = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')",
                        Boolean::class.java, pid) == true
                    if (!blocked) Thread.sleep(10)
                }
                assertTrue(blocked, "Writer must actually wait on the parent's command lock")
                service.execute(DeleteTradingAccountCommand(source.account.id, "count-race-delete"))
            }!!
            val failure = creation!!.get(10, TimeUnit.SECONDS).exceptionOrNull()
            assertIs<TradingFailure>(failure)
            assertEquals(TradingFailureKind.NO_LONGER_AVAILABLE, failure.kind)
            assertEquals(1L, request.bookCount)
            assertEquals(1L, request.importSessionCount)
            assertEquals(1L, request.artifactCount)
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_books WHERE account_id=?", Long::class.java, source.account.id.value))
        } finally { executor.shutdownNow() }
    }

    @Test
    fun v17PreservesLegacyCountsAndRemediationNeverInventsARequestTimeSnapshot() = withDatabase(applyCountMigration = false) { connection ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        members(jdbc)
        jdbc.update("""INSERT INTO trading_deletion_requests(id,owner_member_id,target_type,target_id,requested_scope,state,
            deletion_generation,account_count,book_count,import_session_count,artifact_count,revoked_at,requested_at,updated_at,
            backup_purge_due_at,attempt_count,failure_code,version) VALUES
            (72300,1,'TRADING_BOOK',72301,'BOOK_SUBTREE','FAILED',1,0,1,3,5,?,?,?,?,5,'DELETION_PARTIALLY_COMPLETED',7)""",
            java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now.plusSeconds(10)),
            java.sql.Timestamp.from(now.plusSeconds(86400)))
        val before = jdbc.queryForMap("SELECT account_count,book_count,import_session_count,artifact_count,state,version,attempt_count FROM trading_deletion_requests")
        val migration = javaClass.getResourceAsStream("/db/migration/V17__trading_deletion_count_snapshot.sql")!!.bufferedReader().use { it.readText() }
        connection.createStatement().use { statement -> splitSqlStatements(migration).forEach(statement::execute) }
        assertEquals(before, jdbc.queryForMap("SELECT account_count,book_count,import_session_count,artifact_count,state,version,attempt_count FROM trading_deletion_requests"))
        val store = TradingReviewTestStores(jdbc).deletionAdapter
        val legacy = assertNotNull(store.find(DeletionRequestId(72300), MemberId(1)))
        assertEquals(DeletionAffectedResources(0, 1, 3, 5, snapshotStatus = DeletionCountSnapshotStatus.LEGACY_PARTIAL), legacy.affectedResources)
        val tx = TransactionTemplate(tradingTestTransactions(SingleConnectionDataSource(connection, true)))
        val repaired = tx.execute { JdbcTradingDeletionRemediationAdapter(jdbc)
            .remediate(DeletionRemediation(legacy.id, DeletionRequestId(72302), now.plusSeconds(20))) }!!
        assertEquals(legacy.affectedResources, repaired.affectedResources)
        assertEquals(repaired.affectedResources, store.find(repaired.id, legacy.owner)?.affectedResources)
        assertEquals(7L, store.find(legacy.id, legacy.owner)?.version)
        assertEquals(DeletionCountSnapshotStatus.LEGACY_PARTIAL,
            TradingReviewTestStores(jdbc).deletionReader.find(legacy.id, legacy.owner)?.affectedResources?.snapshotStatus)
        val exact = DeletionRequest.request(DeletionRequestId(72310), MemberId(1), DeletionScope.book(TradingBookId(72311)),
            1, DeletionAffectedResources(0, 1, 2, 4, 3, 5, 6), now)
        store.insert(exact)
        val failed = exact.start(now.plusSeconds(1)).recordFailure(now.plusSeconds(2), 1)
        assertTrue(tx.execute { JdbcDeletionRequestExecution(jdbc).commit(exact, failed) }!!)
        val exactRemediation = tx.execute { JdbcTradingDeletionRemediationAdapter(jdbc)
            .remediate(DeletionRemediation(exact.id, DeletionRequestId(72312), now.plusSeconds(20))) }!!
        assertEquals(exact.affectedResources, store.find(exactRemediation.id, exact.owner)?.affectedResources)
    }

    @Test
    fun deletionReaderPreservesVerifiedPartialProgressAndHidesOtherOwnersWithoutWriting() = withDatabase { connection ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        members(jdbc)
        val store = TradingReviewTestStores(jdbc).deletionAdapter
        val reader = TradingReviewTestStores(jdbc).deletionReader
        val request = DeletionRequest.request(DeletionRequestId(7101), MemberId(1), DeletionScope.book(TradingBookId(7102)),
            1, DeletionAffectedResources(0, 1), now)
        store.insert(request)
        val requested = TradingDeletion.fromDomain(assertNotNull(reader.find(request.id, request.owner)))
        assertEquals(PurgeComponentStatus.NOT_STARTED, requested.primaryPurgeStatus)
        assertEquals(PurgeComponentStatus.NOT_STARTED, requested.objectPurgeStatus) // zero counters do not define scope
        assertEquals(PurgeComponentStatus.NOT_STARTED, requested.computeRuntimePurgeStatus)
        val partial = request.start(now.plusSeconds(1)).beginPrimaryPurge(now.plusSeconds(2))
            .verifyAbsent(DeletionPurgeComponent.PRIMARY_DATA, now.plusSeconds(3))
            .verifyAbsent(DeletionPurgeComponent.DERIVED_RESULTS, now.plusSeconds(3))
            .beginObjectPurge(now.plusSeconds(4))
            .verifyAbsent(DeletionPurgeComponent.COMPUTE_RUNTIME, now.plusSeconds(5))
            .verifyAbsent(DeletionPurgeComponent.RAW_OBJECTS, now.plusSeconds(6))
            .recordFailure(now.plusSeconds(7), 1)
        val tx = TransactionTemplate(tradingTestTransactions(SingleConnectionDataSource(connection, true)))
        assertTrue(tx.execute { JdbcDeletionRequestExecution(jdbc).commit(request, partial) }!!)
        val persisted = DeletionRequestPersistenceMapper.toRecord(assertNotNull(store.find(request.id, request.owner)))
        val member = object : TradingMemberProvider {
            var id = MemberId(1)
            override fun currentMemberId() = id
        }
        val service = GetTradingDeletionRequestService(member, reader)
        repeat(2) {
            val view = service.execute(GetTradingDeletionRequestQuery(request.id))
            assertEquals("FAILED", view.state)
            assertEquals(PurgeComponentStatus.COMPLETED, view.primaryPurgeStatus)
            assertEquals(PurgeComponentStatus.COMPLETED, view.computeRuntimePurgeStatus)
            assertEquals(PurgeComponentStatus.FAILED, view.objectPurgeStatus)
            assertEquals(PurgeComponentStatus.IN_PROGRESS, view.backupPurgeStatus)
            assertFalse(view.retryable)
        }
        assertEquals(persisted, DeletionRequestPersistenceMapper.toRecord(assertNotNull(store.find(request.id, request.owner))))
        member.id = MemberId(2)
        assertEquals(TradingFailureKind.NOT_FOUND, assertFailsWith<TradingFailure> { service.execute(GetTradingDeletionRequestQuery(request.id)) }.kind)
        assertNull(reader.find(request.id, MemberId(2)))

        // V13 preserves historical COMPLETED labels but deliberately does not manufacture absence checkpoints.
        val legacy = DeletionRequest.request(DeletionRequestId(7103), MemberId(1), DeletionScope.book(TradingBookId(7104)), 2,
            DeletionAffectedResources(0, 1), now)
        store.insert(legacy)
        jdbc.update("""UPDATE trading_deletion_requests SET state='COMPLETED',completed_at=?,updated_at=? WHERE id=?""",
            java.sql.Timestamp.from(now.plusSeconds(10)), java.sql.Timestamp.from(now.plusSeconds(10)), legacy.id.value)
        val view = TradingDeletion.fromDomain(assertNotNull(reader.find(legacy.id, legacy.owner)))
        assertEquals("COMPLETED", view.state)
        assertEquals(PurgeComponentStatus.IN_PROGRESS, view.primaryPurgeStatus)
        assertEquals(PurgeComponentStatus.IN_PROGRESS, view.objectPurgeStatus)
        assertEquals(PurgeComponentStatus.IN_PROGRESS, view.computeRuntimePurgeStatus)
        assertEquals(PurgeComponentStatus.IN_PROGRESS, view.backupPurgeStatus)
    }

    @Test
    fun legacyRawOnlyRequestResumesWithoutPurgingRetainedPrimaryData() = withDatabase { connection ->
        val (_, tx, stores) = accountBookFixture(connection)
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val source = grantSource(jdbc, 88001, MemberId(1))
        val deletion = DeletionRequest.request(DeletionRequestId(88901), MemberId(1),
            DeletionScope.rawArtifact(source.artifact.artifactId), 1, DeletionAffectedResources(0,0,0,1), now)
        stores.deletionAdapter.insert(deletion)
        jdbc.update("UPDATE trading_deletion_requests SET state='PURGING_OBJECTS' WHERE id=?", deletion.id.value)
        deletionWorker(jdbc, tx, emptyStorage(), Clock.fixed(now, ZoneOffset.UTC)).purgePending()
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_import_sessions", Long::class.java))
        assertEquals("DELETED", jdbc.queryForObject("SELECT retention_status FROM trading_source_artifacts", String::class.java))
        val restored = stores.deletionAdapter.find(deletion.id, deletion.owner)!!
        assertEquals(DeletionRequestState.PURGING_OBJECTS, restored.state)
        assertTrue(DeletionPurgeComponent.RAW_OBJECTS in restored.verifiedAbsent)
        assertTrue(DeletionPurgeComponent.PRIMARY_DATA !in restored.verifiedAbsent)
        assertTrue(restored.version > 0)
    }

    @Test
    fun deletionMigrationPreservesHistoricalTerminalRowsWithoutInventingCheckpoints() = withDatabase(applyDeletionMigration = false) { connection ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        members(jdbc)
        for ((index, state) in listOf("REQUESTED", "PURGING_OBJECTS", "COMPLETED", "FAILED").withIndex()) {
            jdbc.update("""INSERT INTO trading_deletion_requests(id,owner_member_id,target_type,target_id,
                requested_scope,state,deletion_generation,revoked_at,requested_at,updated_at,backup_purge_due_at,
                completed_at,failure_code,attempt_count)
                VALUES (?,1,'TRADING_IMPORT_SESSION',?,'IMPORT_SUBTREE',?,1,?,?,?,?,?,?,?)""",
                85000L+index, 85100L+index, state, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now.plusSeconds(2592000)),
                if (state == "COMPLETED") java.sql.Timestamp.from(now) else null,
                if (state == "FAILED") "DELETION_PARTIALLY_COMPLETED" else null, if (state == "FAILED") 5 else 0)
        }
        val before = jdbc.queryForList("SELECT * FROM trading_deletion_requests ORDER BY id")
        val migration = javaClass.getResourceAsStream("/db/migration/V13__trading_deletion_checkpoints.sql")!!
            .bufferedReader().use { it.readText() }
        connection.createStatement().use { statement -> splitSqlStatements(migration).forEach(statement::execute) }
        assertEquals(before, jdbc.queryForList("SELECT * FROM trading_deletion_requests ORDER BY id").map { it - "version" })
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_deletion_checkpoints", Long::class.java))
        val completed = TradingReviewTestStores(jdbc).deletionAdapter.find(DeletionRequestId(85002), MemberId(1))!!
        assertEquals(DeletionRequestState.COMPLETED, completed.state)
        assertEquals(0L, completed.version)
        assertTrue(completed.verifiedAbsent.isEmpty())
    }

    @Test
    fun legacyObjectPhaseRetainsPrimaryUntilCoverageCanBeVerified() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val source = grantSource(jdbc, 86001, MemberId(1))
        val deletion = tx.execute { service.execute(DeleteTradingAccountCommand(source.account.id, "delete-legacy")) }!!
        jdbc.update("UPDATE trading_deletion_requests SET state='PURGING_OBJECTS' WHERE id=?", deletion.id)
        val removed = mutableListOf<String>()
        val storage = object : RawArtifactStorage by emptyStorage() {
            override fun delete(key: String) { removed += key }
        }
        deletionWorker(jdbc, tx, storage, Clock.fixed(now, ZoneOffset.UTC)).purgePending()
        assertEquals(listOf("objects/synthetic-86001"), removed)
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_import_sessions", Long::class.java))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_deletion_runtime_tasks", Long::class.java))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_deletion_dispatch_tasks", Long::class.java))
        assertEquals(source.sessionId.value, jdbc.queryForObject("SELECT session_id FROM trading_deletion_session_tasks", Long::class.java))
        val root = stores.deletionAdapter.find(DeletionRequestId(deletion.id), MemberId(1))!!
        assertEquals(DeletionRequestState.PURGING_OBJECTS, root.state)
        assertNull(root.verifiedAbsent[DeletionPurgeComponent.TEMPORARY_COPIES])
        assertNull(root.verifiedAbsent[DeletionPurgeComponent.COMPUTE_RUNTIME])
    }

    private val pendingRuntime = object : TradingRuntimeDeletionClient {
        override fun purge(command: TradingRuntimeDeletion): TradingRuntimeDeletionOutcome {
            check(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            return TradingRuntimeDeletionOutcome.Pending(java.time.Duration.ofSeconds(5))
        }
    }

    @Test
    fun parentCannotCompleteUntilItsPendingChildHasActualAbsenceEvidence() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val source = grantSource(jdbc, 87001, MemberId(1))
        jdbc.update("UPDATE trading_import_sessions SET deletion_pending=true,deletion_generation=1 WHERE id=?", source.sessionId.value)
        val child = DeletionRequest.request(DeletionRequestId(87901), MemberId(1), DeletionScope.importSession(source.sessionId),
            1, DeletionAffectedResources(0,0,1,1), now)
        stores.deletionAdapter.insert(child)
        deletionWorker(jdbc, tx, emptyStorage(), Clock.fixed(now, ZoneOffset.UTC), runtimeClient=pendingRuntime).purgePending()
        val parent = tx.execute { service.execute(DeleteTradingAccountCommand(source.account.id, "delete-parent")) }!!
        deletionWorker(jdbc, tx, emptyStorage(), Clock.fixed(now, ZoneOffset.UTC), runtimeClient=pendingRuntime).purgePending()
        val root = stores.deletionAdapter.find(DeletionRequestId(parent.id), MemberId(1))!!
        assertEquals(DeletionRequestState.CANCELLING_JOBS, root.state)
        assertNull(root.verifiedAbsent[DeletionPurgeComponent.COMPUTE_RUNTIME])
        assertEquals(0, root.attemptCount)
        deletionWorker(jdbc, tx, emptyStorage(), Clock.fixed(now.plusSeconds(6), ZoneOffset.UTC)).purgePending()
        assertEquals(DeletionRequestState.COMPLETED, stores.deletionAdapter.find(child.id, child.owner)?.state)
        deletionWorker(jdbc, tx, emptyStorage(), Clock.fixed(now.plusSeconds(12), ZoneOffset.UTC)).purgePending()
        assertEquals(DeletionRequestState.COMPLETED, stores.deletionAdapter.find(root.id, root.owner)?.state)
    }

    @Test
    fun pendingRuntimeDoesNotConsumeErrorBudgetAndRestartCompletesOnlyWithActualEvidence() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val source = grantSource(jdbc, 80001, MemberId(1))
        val deletion = tx.execute { service.execute(DeleteTradingAccountCommand(source.account.id, "delete-external")) }!!
        repeat(6) { attempt ->
            deletionWorker(jdbc, tx, emptyStorage(), Clock.fixed(now.plusSeconds(attempt * 6L), ZoneOffset.UTC),
                runtimeClient=pendingRuntime).purgePending()
        }
        val pending = stores.deletionAdapter.find(DeletionRequestId(deletion.id), MemberId(1))!!
        assertEquals(DeletionRequestState.CANCELLING_JOBS, pending.state)
        assertNull(pending.verifiedAbsent[DeletionPurgeComponent.COMPUTE_RUNTIME])
        assertEquals(0, pending.attemptCount)
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_import_sessions", Long::class.java))
        deletionWorker(jdbc, tx, emptyStorage(), Clock.fixed(now.plusSeconds(40), ZoneOffset.UTC)).purgePending()
        val completed = stores.deletionAdapter.find(pending.id, pending.owner)!!
        assertEquals(DeletionRequestState.COMPLETED, completed.state)
        assertEquals(completed.checklist.requiredComponents, completed.verifiedAbsent.keys)
        assertEquals(0, completed.attemptCount)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_import_sessions", Long::class.java))
        assertEquals(TradingFailureKind.NO_LONGER_AVAILABLE, assertFailsWith<TradingFailure> { service.execute(GetTradingAccountQuery(source.account.id)) }.kind)
    }

    @Test
    fun deletionObjectLocatorsAndSuccessfulItemsSurvivePrimaryPurgeAndWorkerRestart() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val one = grantSource(jdbc, 81001, MemberId(1))
        grantSource(jdbc, 82001, MemberId(1), one.account)
        val deletion = tx.execute { service.execute(DeleteTradingAccountCommand(one.account.id, "delete-partial")) }!!
        val deleted = mutableListOf<String>()
        val flaky = object : RawArtifactStorage by emptyStorage() {
            override fun delete(key: String) {
                if (key == "objects/synthetic-82001") error("Synthetic object failure")
                deleted += key
            }
        }
        deletionWorker(jdbc, tx, flaky, Clock.fixed(now, ZoneOffset.UTC)).purgePending()
        assertEquals(listOf("objects/synthetic-81001"), deleted)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_source_artifacts", Long::class.java))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_deletion_object_tasks WHERE verified_at IS NOT NULL", Long::class.java))
        val recovered = object : RawArtifactStorage by emptyStorage() {
            override fun delete(key: String) { deleted += key }
        }
        deletionWorker(jdbc, tx, recovered, Clock.fixed(now.plusSeconds(6), ZoneOffset.UTC)).purgePending()
        assertEquals(listOf("objects/synthetic-81001", "objects/synthetic-82001"), deleted)
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM trading_deletion_object_tasks WHERE verified_at IS NOT NULL", Long::class.java))
        val root = stores.deletionAdapter.find(DeletionRequestId(deletion.id), MemberId(1))!!
        assertEquals(DeletionRequestState.COMPLETED, root.state)
        assertTrue(DeletionPurgeComponent.RAW_OBJECTS in root.verifiedAbsent)
    }

    @Test
    fun replacedOrExpiredDeletionLeaseCannotWriteProgress() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val source = grantSource(jdbc, 89101, MemberId(1))
        val deletion = tx.execute { service.execute(DeleteTradingAccountCommand(source.account.id, "delete-lease")) }!!
        val work = JdbcTradingDeletionWorkStore(jdbc, Clock.fixed(now, ZoneOffset.UTC))
        val first = tx.execute { work.claim(now, UUID.randomUUID().toString(), java.time.Duration.ofSeconds(1)) }!!
        val second = tx.execute { work.claim(now.plusSeconds(2), UUID.randomUUID().toString(), java.time.Duration.ofSeconds(30)) }!!
        val before = jdbc.queryForList("SELECT * FROM trading_deletion_requests WHERE id=?", deletion.id)
        assertFalse(tx.execute { work.finish(first, emptyList(), now.plusSeconds(2), java.time.Duration.ZERO) }!!)
        assertEquals(before, jdbc.queryForList("SELECT * FROM trading_deletion_requests WHERE id=?", deletion.id))
        assertTrue(tx.execute { work.finish(second, emptyList(), now.plusSeconds(2), java.time.Duration.ZERO) }!!)
        assertFalse(tx.execute { work.finish(second, emptyList(), now.plusSeconds(3), java.time.Duration.ZERO) }!!)
        val root = stores.deletionAdapter.find(DeletionRequestId(deletion.id), MemberId(1))!!
        assertEquals(DeletionRequestState.CANCELLING_JOBS, root.state)
        assertTrue(root.verifiedAbsent.isEmpty())
        assertEquals(1L, root.version)
    }

    @Test
    fun pendingHasZeroErrorsButFailsTruthfullyAtTheLivePurgeSla() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val source = grantSource(jdbc, 89201, MemberId(1))
        val deletion = tx.execute { service.execute(DeleteTradingAccountCommand(source.account.id, "delete-sla")) }!!
        deletionWorker(jdbc, tx, emptyStorage(), Clock.fixed(now, ZoneOffset.UTC), runtimeClient=pendingRuntime).purgePending()
        deletionWorker(jdbc, tx, emptyStorage(), Clock.fixed(now.plusSeconds(86400), ZoneOffset.UTC), runtimeClient=pendingRuntime).purgePending()
        val root = stores.deletionAdapter.find(DeletionRequestId(deletion.id), MemberId(1))!!
        assertEquals(DeletionRequestState.FAILED, root.state)
        assertEquals(0, root.attemptCount)
        assertNull(root.completedAt)
        assertNull(root.verifiedAbsent[DeletionPurgeComponent.COMPUTE_RUNTIME])
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_import_sessions", Long::class.java))
    }

    @Test
    fun remediationPreservesFailedAuditAndUnblocksParentAfterVerifiedCompletion() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val source = grantSource(jdbc, 89301, MemberId(1))
        jdbc.update("UPDATE trading_import_sessions SET deletion_pending=true,deletion_generation=1 WHERE id=?", source.sessionId.value)
        val child = DeletionRequest.request(DeletionRequestId(89311), MemberId(1), DeletionScope.importSession(source.sessionId),
            1, DeletionAffectedResources(0,0,1,1), now)
        stores.deletionAdapter.insert(child)
        val unavailable = object : TradingRuntimeDeletionClient {
            override fun purge(command: TradingRuntimeDeletion): TradingRuntimeDeletionOutcome = error("synthetic dependency failure")
        }
        repeat(5) { attempt -> deletionWorker(jdbc, tx, emptyStorage(),
            Clock.fixed(now.plusSeconds(attempt*100L), ZoneOffset.UTC), runtimeClient=unavailable).purgePending() }
        val original = stores.deletionAdapter.find(child.id, child.owner)!!
        assertEquals(DeletionRequestState.FAILED, original.state)
        assertEquals(5, original.attemptCount)
        val parent = tx.execute { service.execute(DeleteTradingAccountCommand(source.account.id, "delete-remediation-parent")) }!!
        deletionWorker(jdbc, tx, emptyStorage(), Clock.fixed(now.plusSeconds(500), ZoneOffset.UTC)).purgePending()
        assertEquals(DeletionRequestState.CANCELLING_JOBS,
            stores.deletionAdapter.find(DeletionRequestId(parent.id), MemberId(1))?.state)
        val work = JdbcTradingDeletionWorkStore(jdbc, Clock.fixed(now.plusSeconds(501), ZoneOffset.UTC))
        val remediation = tx.execute { JdbcTradingDeletionRemediationAdapter(jdbc).remediate(DeletionRemediation(child.id, DeletionRequestId(89312), now.plusSeconds(501))) }!!
        assertEquals(original.revokedAt, remediation.revokedAt)
        assertEquals(original.deletionGeneration, remediation.deletionGeneration)
        assertEquals(remediation.id, tx.execute { JdbcTradingDeletionRemediationAdapter(jdbc).remediate(DeletionRemediation(child.id, DeletionRequestId(89313), now.plusSeconds(501))) }!!.id)
        deletionWorker(jdbc, tx, emptyStorage(), Clock.fixed(now.plusSeconds(510), ZoneOffset.UTC)).purgePending()
        deletionWorker(jdbc, tx, emptyStorage(), Clock.fixed(now.plusSeconds(520), ZoneOffset.UTC)).purgePending()
        assertEquals(DeletionRequestState.COMPLETED, stores.deletionAdapter.find(remediation.id, child.owner)?.state)
        assertEquals(DeletionRequestState.COMPLETED, stores.deletionAdapter.find(DeletionRequestId(parent.id), child.owner)?.state)
        val retained = stores.deletionAdapter.find(child.id, child.owner)!!
        assertEquals(DeletionRequestPersistenceMapper.toRecord(original), DeletionRequestPersistenceMapper.toRecord(retained))
        assertEquals(child.id, stores.deletionAdapter.findByTarget(child.owner, child.scope)?.id)
    }

    @Test
    fun deletionProgressCasRejectsStaleOwnerGenerationAndRollsBackProofTogether() = withDatabase { connection ->
        val source = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(source)
        val tx = TransactionTemplate(tradingTestTransactions(source))
        members(jdbc)
        val store = TradingReviewTestStores(jdbc).deletionAdapter
        val execution = JdbcDeletionRequestExecution(jdbc)
        val initial = DeletionRequest.request(DeletionRequestId(83001), MemberId(1),
            DeletionScope.book(TradingBookId(83002)), 1, DeletionAffectedResources(0,1), now)
        store.insert(initial)
        assertFailsWith<IllegalStateException> { execution.lockNext() }
        val unverifiable = DeletionRequestPersistenceMapper.toDomain(DeletionRequestPersistenceMapper.toRecord(initial)
            .copy(state="COMPLETED",completedAt=now,version=1))
        assertFailsWith<IllegalArgumentException> { tx.execute { execution.commit(initial, unverifiable) } }
        val first = initial.start(now)
        assertTrue(tx.execute { execution.commit(initial, first) }!!)
        assertFalse(tx.execute { execution.commit(initial, initial.recordFailure(now)) }!!)
        assertFailsWith<IllegalStateException> { tx.execute {
            assertTrue(execution.commit(first, first.verifyAbsent(DeletionPurgeComponent.COMPUTE_RUNTIME, now)))
            error("Synthetic rollback")
        } }
        assertEquals(first.version, store.find(initial.id, initial.owner)!!.version)
        assertTrue(store.find(initial.id, initial.owner)!!.verifiedAbsent.isEmpty())
        fun impostor(owner: MemberId, generation: Long) = DeletionRequest.restore(initial.id, owner, initial.scope,
            first.state, generation, initial.affectedResources, now, now, now, initial.backupPurgeDueAt,
            null, null, 0, first.version)
        for (wrong in listOf(impostor(MemberId(2), 1), impostor(MemberId(1), 2)))
            assertFalse(tx.execute { execution.commit(wrong, wrong.recordFailure(now)) }!!)
        assertTrue(tx.execute { execution.commit(first, first.verifyAbsent(DeletionPurgeComponent.COMPUTE_RUNTIME, now)) }!!)
        assertEquals(mapOf(DeletionPurgeComponent.COMPUTE_RUNTIME to now), store.find(initial.id, initial.owner)!!.verifiedAbsent)
        val current = store.find(initial.id, initial.owner)!!
        var complete = current.beginPrimaryPurge(now).verifyAbsent(DeletionPurgeComponent.PRIMARY_DATA, now)
            .verifyAbsent(DeletionPurgeComponent.DERIVED_RESULTS, now).beginObjectPurge(now)
        for (component in complete.checklist.requiredComponents - complete.verifiedAbsent.keys)
            complete = complete.verifyAbsent(component, now)
        complete = complete.complete(now)
        assertTrue(tx.execute { execution.commit(current, complete) }!!)
        assertFalse(tx.execute { execution.commit(current, current.recordFailure(now)) }!!)
        assertEquals(DeletionRequestState.COMPLETED, store.find(initial.id, initial.owner)!!.state)
        assertEquals(0, store.find(initial.id, initial.owner)!!.attemptCount)
    }

    @Test
    fun deletionClaimsSkipLockedAcrossIndependentConnectionsAndRollbackReleasesClaim() = withDatabase { connection ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        members(jdbc)
        val store = TradingReviewTestStores(jdbc).deletionAdapter
        val initial = DeletionRequest.request(DeletionRequestId(84001), MemberId(1),
            DeletionScope.book(TradingBookId(84002)), 1, DeletionAffectedResources(0,1), now)
        store.insert(initial)
        val firstSource = transitionSource(connection.schema)
        val secondSource = transitionSource(connection.schema)
        val first = JdbcDeletionRequestExecution(JdbcTemplate(firstSource))
        val second = JdbcDeletionRequestExecution(JdbcTemplate(secondSource))
        val otherTx = TransactionTemplate(tradingTestTransactions(secondSource))
        TransactionTemplate(tradingTestTransactions(firstSource)).execute { status ->
            assertEquals(initial.id, first.lockNext()!!.id)
            assertNull(otherTx.execute { second.lockNext() })
            status.setRollbackOnly()
        }
        assertEquals(initial.id, otherTx.execute { second.lockNext() }!!.id)
    }

    @Test
    fun deletionAggregateStoreRestoresWorkerStatesAttemptsAndHistoricalScopeWithoutRewritingThem() = withDatabase { connection ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        members(jdbc)
        val adapter = TradingReviewTestStores(jdbc).deletionAdapter
        val request = DeletionRequest.request(DeletionRequestId(7001), MemberId(1),
            com.refinvest.core.tradingreview.domain.valueobject.DeletionScope.book(TradingBookId(7002)), 1,
            com.refinvest.core.tradingreview.domain.valueobject.DeletionAffectedResources(0, 1), now)
        adapter.insert(request)
        assertEquals(DeletionRequestPersistenceMapper.toRecord(request), DeletionRequestPersistenceMapper.toRecord(
            assertNotNull(adapter.find(request.id, MemberId(1)))))
        assertNull(adapter.find(request.id, MemberId(2)))
        assertNull(adapter.find(DeletionRequestId(-1), MemberId(1)))
        assertNotNull(adapter.findByTarget(MemberId(1), request.scope))
        assertNull(adapter.findByTarget(MemberId(2), request.scope))
        com.refinvest.core.tradingreview.domain.valueobject.DeletionRequestState.entries.forEach { state ->
            val failure = if (state.name in setOf("REQUESTED", "FAILED")) "DELETION_PARTIALLY_COMPLETED" else null
            jdbc.update("""UPDATE trading_deletion_requests SET state=?,attempt_count=5,
                failure_code=?,completed_at=?,updated_at=? WHERE id=?""", state.name, failure,
                if (state.name == "COMPLETED") java.sql.Timestamp.from(now.plusSeconds(10)) else null,
                java.sql.Timestamp.from(now.plusSeconds(10)), request.id.value)
            val loaded = assertNotNull(adapter.find(request.id, MemberId(1)))
            assertEquals(state, loaded.state)
            assertEquals(5, loaded.attemptCount)
            assertEquals(failure, loaded.failureCode)
            assertEquals(request.checklist.requiredComponents, loaded.checklist.requiredComponents)
            assertFailsWith<IllegalArgumentException> { adapter.insert(loaded) }
        }
        jdbc.update("""UPDATE trading_deletion_requests SET target_type='TRADING_IMPORT_SESSION',
            requested_scope='IMPORT_SUBTREE',book_count=0,import_session_count=0 WHERE id=?""", request.id.value)
        val legacy = assertNotNull(adapter.findByTarget(MemberId(1),
            com.refinvest.core.tradingreview.domain.valueobject.DeletionScope.importSession(TradingImportSessionId(7002))))
        assertEquals("IMPORT_SUBTREE", legacy.scope.requestedScope)
        assertEquals(0L, legacy.affectedResources.importSessions)
        assertEquals("IMPORT_SUBTREE", TradingDeletion.fromDomain(legacy).requestedScope)
        val tx = TransactionTemplate(tradingTestTransactions(SingleConnectionDataSource(connection, true)))
        tx.execute { status ->
            adapter.insert(DeletionRequest.request(DeletionRequestId(7003), MemberId(1),
                com.refinvest.core.tradingreview.domain.valueobject.DeletionScope.book(TradingBookId(7004)), 1,
                com.refinvest.core.tradingreview.domain.valueobject.DeletionAffectedResources(0, 1), now))
            status.setRollbackOnly()
        }
        assertNull(adapter.find(DeletionRequestId(7003), MemberId(1)))
    }

    @Test
    fun admissionPersistsOneOwnedRunAndRejectsStaleOrForgedSnapshotsWithoutChanges() = withDatabase { connection ->
        val f = normalizationAdmissionFixture(connection)
        val first = prepareAdmission(f.current)
        assertFailsWith<IllegalStateException> { f.admission.admit(first) }
        assertTrue(f.tx.execute { f.store.update(f.current) })
        val unchanged = normalizationAdmissionRows(f.jdbc)
        assertFalse(f.tx.execute { f.admission.admit(first) })
        assertEquals(unchanged, normalizationAdmissionRows(f.jdbc))
        val current = f.store.find(f.current.id, f.current.owner)!!
        val request = prepareAdmission(current)
        val record = TradingImportSessionPersistenceMapper.toRecord(request.admitted)
        val run = requireNotNull(record.normalizationRun)
        for (forged in listOf(record.copy(artifacts = emptyList()))) {
            assertFalse(f.tx.execute { f.admission.admit(request.copy(
                admitted = TradingImportSessionPersistenceMapper.toDomain(forged))) })
            assertEquals(unchanged, normalizationAdmissionRows(f.jdbc))
        }
        assertFailsWith<IllegalArgumentException> { f.tx.execute { f.admission.admit(request.copy(admitted =
            TradingImportSessionPersistenceMapper.toDomain(record.copy(normalizationRun = run.copy(deletionGeneration = 1))))) } }
        assertEquals(unchanged, normalizationAdmissionRows(f.jdbc))
        assertFailsWith<IllegalArgumentException> { f.tx.execute { f.admission.admit(request.copy(admitted =
            TradingImportSessionPersistenceMapper.toDomain(record.copy(normalizationRun = run.copy(
                inputJson = run.inputJson.replace("BINANCE", "OTHER")))))) } }
        assertEquals(unchanged, normalizationAdmissionRows(f.jdbc))
        assertFailsWith<IllegalArgumentException> { f.tx.execute { f.admission.admit(request.copy(
            dispatch = request.dispatch.copy(runId = UUID.randomUUID().toString()))) } }
        assertEquals(unchanged, normalizationAdmissionRows(f.jdbc))
        assertTrue(f.tx.execute { f.admission.admit(request) })
        val admitted = f.store.find(current.id, current.owner)!!
        assertEquals(TradingImportStatus.VALIDATING, admitted.status)
        assertEquals(request.admitted.normalizationRun?.id, admitted.activeNormalizationRunId)
        assertEquals(request.admitted.normalizationRun?.inputFingerprint, admitted.normalizationRun?.inputFingerprint)
        assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM trading_normalization_runs", Long::class.java))
        assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM trading_normalization_attempts", Long::class.java))
        assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM trading_normalization_dispatches", Long::class.java))
        assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM trading_normalization_audit", Long::class.java))
        val saved = normalizationAdmissionRows(f.jdbc)
        assertFalse(f.tx.execute { f.admission.admit(request) })
        assertEquals(saved, normalizationAdmissionRows(f.jdbc))
    }

    @Test
    fun admissionRollbackExpiryAndDeletedParentsPreserveAllAdmissionRows() = withDatabase { connection ->
        val f = normalizationAdmissionFixture(connection)
        val request = prepareAdmission(f.current)
        val before = normalizationAdmissionRows(f.jdbc)
        assertFailsWith<IllegalStateException> { f.tx.execute {
            assertTrue(f.admission.admit(request))
            assertNotNull(f.store.find(f.current.id, f.current.owner, true)?.normalizationRun)
            error("rollback whole admission")
        } }
        assertEquals(before, normalizationAdmissionRows(f.jdbc))
        f.time.set(now.plusSeconds(86400))
        assertFalse(f.tx.execute { f.admission.admit(request) })
        assertEquals(before, normalizationAdmissionRows(f.jdbc))
        f.time.set(now)
        f.jdbc.execute("SELECT trading_normalization_transition_control('PAUSE',0,'direct-admission')")
        assertEquals(TradingFailureKind.DEPENDENCY_UNAVAILABLE, assertFailsWith<TradingFailure> { f.tx.execute { f.admission.admit(request) } }.kind)
        assertEquals(before, normalizationAdmissionRows(f.jdbc))
        f.jdbc.execute("SELECT trading_normalization_transition_control('VERIFY',1,'direct-admission')")
        f.jdbc.execute("SELECT trading_normalization_transition_control('RESUME',1,'direct-admission')")
        f.tx.execute { f.review.execute(DeleteTradingBookCommand(f.current.bookId, "delete-admission-parent")) }
        val afterDelete = normalizationAdmissionRows(f.jdbc)
        assertFalse(f.tx.execute { f.admission.admit(request) })
        assertEquals(afterDelete, normalizationAdmissionRows(f.jdbc))
    }

    @Test
    fun competingAdmissionsWaitForTheRootAndCommitOrRollbackExactlyOneRun() {
        for (commitFirst in listOf(true, false)) withDatabase { connection ->
            val f = normalizationAdmissionFixture(connection)
            val first = prepareAdmission(f.current)
            val second = prepareAdmission(f.current)
            DriverManager.getConnection(System.getenv("TRADING_REVIEW_TEST_JDBC_URL"),
                System.getenv("TRADING_REVIEW_TEST_DB_USER"), System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD")).use { other ->
                other.schema = connection.schema
                val source = SingleConnectionDataSource(other, true)
                val jdbc = JdbcTemplate(source)
                val store = TradingImportTestStores(jdbc)
                val admission = JdbcTradingNormalizationAdmissionAdapter(jdbc, store, Clock.fixed(now, ZoneOffset.UTC), JdbcTradingNormalizationTransitionGate(jdbc))
                val tx = TransactionTemplate(tradingTestTransactions(source))
                val backend = jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!
                val executor = Executors.newSingleThreadExecutor()
                var future: java.util.concurrent.Future<Boolean>? = null
                try {
                    f.tx.execute { status ->
                        assertTrue(f.admission.admit(first))
                        future = executor.submit<Boolean> { tx.execute { admission.admit(second) }!! }
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                        var waited = false
                        while (System.nanoTime() < deadline) {
                            f.jdbc.execute("SELECT pg_stat_clear_snapshot()")
                            if (f.jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')",
                                    Boolean::class.java, backend) == true) { waited = true; break }
                            Thread.sleep(25)
                        }
                        assertTrue(waited, "The competing admission must wait on an actual database lock")
                        if (!commitFirst) status.setRollbackOnly()
                    }
                    assertEquals(!commitFirst, future!!.get(10, TimeUnit.SECONDS))
                    val winner = if (commitFirst) first else second
                    val current = f.store.find(f.current.id, f.current.owner)!!
                    assertEquals(winner.admitted.normalizationRun?.id, current.activeNormalizationRunId)
                    assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM trading_normalization_runs", Long::class.java))
                    assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM trading_normalization_attempts", Long::class.java))
                    assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM trading_normalization_dispatches", Long::class.java))
                    assertEquals(1L, f.jdbc.queryForObject("SELECT count(*) FROM trading_normalization_audit", Long::class.java))
                } finally { executor.shutdownNow() }
            }
        }
    }

    @Test
    fun explicitRetryPreservesV2PinAndRejectsAChangedPreviousRunVersion() = withDatabase { connection ->
        val f = normalizationAdmissionFixture(connection)
        val first = prepareAdmission(f.current, NormalizationResultVersionPin.V2)
        assertTrue(f.tx.execute { f.admission.admit(first) })
        val run = first.admitted.normalizationRun!!
        val runtime = JdbcTradingNormalizationRuntimeAdapter(f.jdbc)
        val submitWorker = UUID.randomUUID().toString()
        val claimed = assertNotNull(runtime.claimNext(now, java.time.Duration.ofSeconds(45), submitWorker))
        assertTrue(f.tx.execute { runtime.markSubmitted(NormalizationSubmissionReceipt(run.id,
            NormalizationDispatchLease(claimed.id, submitWorker, now), 0, run.attemptToken, "retry-job")) })
        val pollWorker = UUID.randomUUID().toString()
        val polled = assertNotNull(runtime.claimSubmitted(now, java.time.Duration.ofSeconds(45), pollWorker))
        f.tx.execute {
            assertTrue(runtime.markTerminal(polled.id, pollWorker, "TEMPORARY_DEPENDENCY_FAILURE", now))
            val current = f.store.find(f.current.id, f.current.owner, true)!!
            val failed = current.failValidation(run.id.value, 0,
                TradingImportFailure("TEMPORARY_DEPENDENCY_FAILURE", true, "RETRY", now), now)!!
            assertTrue(f.store.update(failed))
        }
        val failed = f.store.find(f.current.id, f.current.owner)!!
        assertEquals(NormalizationRunStatus.TERMINAL_FAILURE, failed.normalizationRun?.status)
        assertFailsWith<IllegalArgumentException> { prepareAdmission(failed, NormalizationResultVersionPin.V1) }
        val stale = prepareAdmission(failed, NormalizationResultVersionPin.V2)
        f.jdbc.update("UPDATE trading_normalization_runs SET version=version+1 WHERE id=?::uuid", run.id.value)
        val before = normalizationAdmissionRows(f.jdbc)
        assertFalse(f.tx.execute { f.admission.admit(stale) })
        assertEquals(before, normalizationAdmissionRows(f.jdbc))
        val retry = prepareAdmission(f.store.find(f.current.id, f.current.owner)!!, NormalizationResultVersionPin.V2)
        assertTrue(f.tx.execute { f.admission.admit(retry) })
        val current = f.store.find(f.current.id, f.current.owner)!!.normalizationRun!!
        assertEquals(run.id, current.retryOfRunId)
        assertEquals(run.inputFingerprint, current.inputFingerprint)
        assertEquals(run.logicalKey, current.logicalKey)
        assertEquals(NormalizationResultVersionPin.V2, current.resultVersionPin)
        assertEquals(2L, f.jdbc.queryForObject("SELECT count(*) FROM trading_normalization_runs", Long::class.java))
    }

    private data class AdmissionFixture(
        val jdbc: JdbcTemplate, val store: TradingImportTestStores,
        val admission: JdbcTradingNormalizationAdmissionAdapter, val tx: TransactionTemplate,
        val current: TradingImportSession, val time: java.util.concurrent.atomic.AtomicReference<Instant>,
        val review: TradingAccountBookTestServices,
    )

    private fun normalizationAdmissionFixture(connection: Connection): AdmissionFixture {
        val (review, tx, stores) = accountBookFixture(connection)
        val account = tx.execute { review.execute(CreateTradingAccountCommand(TradingVenue.BINANCE, "A", "a")) }.let { stores.find(it.id, MemberId(1))!! }
        val book = tx.execute { review.execute(CreateTradingBookCommand(account.id, "B", TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "b")) }.let { stores.find(it.id, MemberId(1))!! }
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val store = TradingImportTestStores(jdbc)
        val session = TradingImportSession.create(TradingImportSessionId(40000), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), normalizationVersions, now)
        tx.execute { store.insert(session) }
        val ready = session.recordUpload(TradingSourceArtifactId(40001), TradingArtifactRole.TRADE_HISTORY,
            "text/csv", 10, "a".repeat(64), "AUTO", "objects/admission-trade", byteArrayOf(1), "test", now)
            .recordUpload(TradingSourceArtifactId(40002), TradingArtifactRole.POSITION_HISTORY,
                "text/csv", 20, "b".repeat(64), "AUTO", "objects/admission-position", byteArrayOf(2), "test", now)
        assertTrue(tx.execute { store.update(ready) })
        val time = java.util.concurrent.atomic.AtomicReference(now)
        val clock = object : Clock() {
            override fun instant() = time.get()
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
        }
        return AdmissionFixture(jdbc, store, JdbcTradingNormalizationAdmissionAdapter(jdbc, store, clock, JdbcTradingNormalizationTransitionGate(jdbc)), tx,
            store.find(session.id, session.owner)!!, time, review)
    }

    private fun prepareAdmission(expected: TradingImportSession,
                                 pin: NormalizationResultVersionPin = NormalizationResultVersionPin.V1): NormalizationAdmission {
        val input = NormalizationInput(expected.bookId, expected.id, expected.owner.value,
            expected.artifacts.sortedBy { it.role.ordinal }.map { NormalizationArtifactInput(it.id, it.role,
                it.sha256, it.byteSize, it.contentType, it.dialectCandidate) }, "BINANCE", "LINEAR_PERPETUAL",
            "ONE_WAY", "USDT", expected.sourceTimezone, expected.reviewTimezone, expected.versionSetJson, "[]",
            expected.dataPolicyVersion, "retry-v1", """{"policyVersion":"retry-v1","maxAttempts":3,"maxElapsedTime":"PT1M","initialDelay":"PT1S","maxDelay":"PT10S","jitter":"NONE"}""", expected.deletionGeneration)
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        val versions = mapper.readValue(expected.versionSetJson, Map::class.java)
        val identity = NormalizationInputIdentity.fromPinnedInput(input, versions)
        val id = NormalizationRunId(UUID.randomUUID().toString())
        val token = UUID.randomUUID().toString()
        val logical = NormalizationInputCanonicalizer.logicalKey(expected.owner.value,
            NormalizationInputCanonicalizer.hash(identity), expected.deletionGeneration, pin)
        val admitted = expected.admitNormalization(id, input, logical, token, identity, versions, deliveryPolicy, pin, now)
        val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), id.value,
            NormalizationInputCanonicalizer.dispatchKey(id.value, logical, token, "f".repeat(64), expected.deletionGeneration),
            NormalizationDispatchStatus.PENDING, 0, now, null, null, null, now, now, resultVersionPin = pin)
        return NormalizationAdmission(expected, admitted, dispatch)
    }

    private fun normalizationAdmissionRows(jdbc: JdbcTemplate) = listOf("trading_import_sessions",
        "trading_source_artifacts", "trading_normalization_runs", "trading_normalization_attempts",
        "trading_normalization_dispatches", "trading_normalization_audit").map { table ->
        jdbc.queryForList("SELECT row_to_json(r)::text FROM $table r ORDER BY row_to_json(r)::text", String::class.java)
    }

    @Test
    fun sessionAggregateSaveFencesPinsMissingChildrenAndStaleSnapshots() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val account = tx.execute { service.execute(CreateTradingAccountCommand(TradingVenue.BINANCE, "A", "a")) }.let { stores.find(it.id, MemberId(1))!! }
        val book = tx.execute { service.execute(CreateTradingBookCommand(account.id, "B", TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "b")) }.let { stores.find(it.id, MemberId(1))!! }
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val store = TradingImportTestStores(jdbc)
        val session = TradingImportSession.create(TradingImportSessionId(30000), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), normalizationVersions, now)
        tx.execute { store.insert(session) }
        assertFailsWith<IllegalStateException> { store.find(session.id, session.owner, true) }
        val first = session.recordUpload(TradingSourceArtifactId(30001), TradingArtifactRole.TRADE_HISTORY,
            "text/csv", 2, "a".repeat(64), "AUTO", "objects/first", byteArrayOf(1), "test", now)
        assertTrue(tx.execute { store.update(first) })
        val stale = session.recordUpload(TradingSourceArtifactId(30002), TradingArtifactRole.POSITION_HISTORY,
            "text/csv", 2, "b".repeat(64), "AUTO", "objects/stale", byteArrayOf(2), "test", now)
        assertFalse(tx.execute { store.update(stale) })
        val current = store.find(session.id, session.owner)!!
        assertEquals(1L, current.version)
        assertEquals(listOf(TradingSourceArtifactId(30001)), current.artifacts.map { it.id })
        assertNull(store.find(session.id, MemberId(2)))
        val record = TradingImportSessionPersistenceMapper.toRecord(current)
        val before = importRows(jdbc)
        for (forged in listOf(record.copy(sourceTimezone = "Asia/Seoul"), record.copy(versionSetJson = "changed"),
            record.copy(artifacts = emptyList()), record.copy(artifacts = record.artifacts.map { it.copy(sha256 = "f".repeat(64)) }),
            record.copy(artifacts = record.artifacts.map { it.copy(version = 99) }))) {
            assertFalse(tx.execute { store.update(TradingImportSessionPersistenceMapper.toDomain(forged)) })
            assertEquals(before, importRows(jdbc))
        }
        val second = current.recordUpload(TradingSourceArtifactId(30002), TradingArtifactRole.POSITION_HISTORY,
            "text/csv", 2, "b".repeat(64), "AUTO", "objects/second", byteArrayOf(2), "test", now)
        assertTrue(tx.execute { store.update(second) })
        assertEquals(2, store.find(session.id, session.owner)!!.artifacts.size)
        val fresh = store.find(session.id, session.owner)!!
        assertTrue(tx.execute { store.update(fresh.requestDeletion(now.plusSeconds(1))) })
        val deleting = store.find(session.id, session.owner)!!
        assertEquals(1L, deleting.deletionGeneration)
        assertTrue(deleting.artifacts.all { it.retentionStatus == TradingArtifactRetentionStatus.DELETION_PENDING && it.version == 1L })
        assertFalse(tx.execute { store.update(fresh) })
    }

    @Test
    fun sessionAggregateRollbackPreservesSlotsGenerationAndParentLifecycle() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val account = tx.execute { service.execute(CreateTradingAccountCommand(TradingVenue.BINANCE, "A", "a")) }.let { stores.find(it.id, MemberId(1))!! }
        val book = tx.execute { service.execute(CreateTradingBookCommand(account.id, "B", TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "b")) }.let { stores.find(it.id, MemberId(1))!! }
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val store = TradingImportTestStores(jdbc)
        val session = TradingImportSession.create(TradingImportSessionId(31000), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), normalizationVersions, now)
        tx.execute { store.insert(session) }
        val withArtifact = session.recordUpload(TradingSourceArtifactId(31001), TradingArtifactRole.TRADE_HISTORY,
            "text/csv", 2, "a".repeat(64), "AUTO", "objects/rollback", byteArrayOf(1), "test", now)
        val before = importRows(jdbc)
        assertFailsWith<IllegalStateException> { tx.execute {
            assertTrue(store.update(withArtifact))
            val fresh = store.find(session.id, session.owner, true)!!
            assertTrue(store.update(fresh.requestDeletion(now.plusSeconds(1))))
            error("rollback")
        } }
        assertEquals(before, importRows(jdbc))
        assertTrue(tx.execute { store.update(withArtifact) })
        tx.execute { service.execute(DeleteTradingBookCommand(book.id, "delete-book")) }
        val afterDelete = importRows(jdbc)
        assertFalse(tx.execute { store.update(store.find(session.id, session.owner)!!.requestDeletion(now.plusSeconds(1))) })
        assertEquals(afterDelete, importRows(jdbc))
    }

    @Test
    fun rawPurgeClaimVersionRejectsLateCompletionFailureAndAggregateOverwrite() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val account = tx.execute { service.execute(CreateTradingAccountCommand(TradingVenue.BINANCE, "A", "a")) }.let { stores.find(it.id, MemberId(1))!! }
        val book = tx.execute { service.execute(CreateTradingBookCommand(account.id, "B", TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "b")) }.let { stores.find(it.id, MemberId(1))!! }
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val store = TradingImportTestStores(jdbc)
        val retention = JdbcTradingArtifactRetentionAdapter(jdbc)
        val session = TradingImportSession.create(TradingImportSessionId(32000), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), normalizationVersions, now)
        tx.execute { store.insert(session) }
        assertTrue(tx.execute { store.update(session.recordUpload(TradingSourceArtifactId(32001),
            TradingArtifactRole.TRADE_HISTORY, "text/csv", 2, "a".repeat(64), "AUTO", "objects/claim", byteArrayOf(1), "test", now)) })
        val old = store.find(session.id, session.owner)!!
        val firstClaim = retention.claimDue(Instant.now(), 1).single()
        assertFalse(tx.execute { store.update(old.requestArtifactDeletion(firstClaim.artifact.id, now.plusSeconds(1))) })
        jdbc.update("UPDATE trading_source_artifacts SET deletion_claim_until=CURRENT_TIMESTAMP - INTERVAL '1 second'")
        val expired = importRows(jdbc)
        assertFalse(retention.finish(RawArtifactRetentionCompletion(firstClaim, RawArtifactPurgeOutcome.ABSENT, Instant.now())))
        assertFalse(retention.finish(RawArtifactRetentionCompletion(firstClaim, RawArtifactPurgeOutcome.EXHAUSTED, Instant.now())))
        assertEquals(expired, importRows(jdbc))
        val nextClaim = retention.claimDue(Instant.now(), 1).single()
        val before = importRows(jdbc)
        assertFalse(retention.finish(RawArtifactRetentionCompletion(firstClaim, RawArtifactPurgeOutcome.ABSENT, Instant.now())))
        assertFalse(retention.finish(RawArtifactRetentionCompletion(firstClaim, RawArtifactPurgeOutcome.EXHAUSTED, Instant.now())))
        assertEquals(before, importRows(jdbc))
        assertTrue(retention.finish(RawArtifactRetentionCompletion(nextClaim, RawArtifactPurgeOutcome.ABSENT, Instant.now())))
        val deleted = store.find(session.id, session.owner)!!.artifacts.single()
        assertEquals(TradingArtifactRetentionStatus.DELETED, deleted.retentionStatus)
        assertNull(deleted.objectKey)
        assertNull(deleted.wrappedDek)
        assertFalse(deleted.rawReparseAvailable(Instant.now()))
    }

    @Test
    fun rawRetentionDrainsCurrentReadersAndTemporaryCopies(@org.junit.jupiter.api.io.TempDir root: java.nio.file.Path) =
        verifyRawRetentionDrain(root, false)

    @Test
    fun rawRetentionDrainsLegacyReadersBeforeRemovingTheirDescriptor(@org.junit.jupiter.api.io.TempDir root: java.nio.file.Path) =
        verifyRawRetentionDrain(root, true)

    private fun verifyRawRetentionDrain(root: java.nio.file.Path, legacy: Boolean) = withDatabase { connection ->
        val (jdbc, tx, session) = retentionFixture(connection)
        val wrappingKey = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 7 }, "AES")
        val local = com.refinvest.core.tradingreview.adapter.storage.LocalEncryptedRawArtifactStorage(root, wrappingKey, "test-v1")
        val store = TradingImportTestStores(jdbc)
        var uploaded = session
        val keys = mutableListOf<String>()
        val descriptors = mutableListOf<StagedRawArtifact>()
        for ((index, role) in TradingArtifactRole.entries.withIndex()) {
            val id = TradingSourceArtifactId(32101L + index)
            val staged = local.preparePublication(local.putStaging(java.io.ByteArrayInputStream(byteArrayOf(1, 2)),
                2, id, session.id), role)
            var key = requireNotNull(staged.publicationKey)
            local.promote(staged, key)
            local.completePublication(key)
            if (legacy && index == 0) {
                val oldKey = "objects/${UUID.randomUUID()}"
                java.nio.file.Files.move(root.resolve(key), root.resolve(oldKey))
                key = oldKey
            }
            keys += key
            descriptors += staged
            uploaded = uploaded.recordUpload(id, role, "text/csv", 2, staged.sha256, "AUTO", key,
                staged.wrappedDek, staged.keyVersion, if (index == 0) now else Instant.now())
        }
        assertTrue(tx.execute { store.update(uploaded) })
        jdbc.update("UPDATE trading_source_artifacts SET filename='synthetic.csv' WHERE id=32101")
        val reader = local.open(keys[0], descriptors[0].wrappedDek, descriptors[0].keyVersion, TradingSourceArtifactId(32101))
        val workspaces = com.refinvest.core.tradingreview.adapter.storage.LocalTradingArtifactWorkspaces(root.resolve("workspaces"))
        val scope = if (legacy) TradingArtifactScope.LegacyArtifact(TradingSourceArtifactId(32101))
            else TradingArtifactScope.RawSlot(session.id, TradingArtifactRole.TRADE_HISTORY)
        val lease = workspaces.acquire(scope)
        val temporary = lease.createTemporary()
        temporary.openOutput().use { it.write(byteArrayOf(3, 4)) }
        lease.close() // A crashed writer may leave a closed-handle temporary copy behind.
        val restarted = com.refinvest.core.tradingreview.adapter.storage.LocalEncryptedRawArtifactStorage(root, wrappingKey, "test-v1")
        val checked = object : RawArtifactStorage by restarted {
            override fun purgeCopies(scope: TradingArtifactScope): Boolean {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                assertTrue(connection.autoCommit)
                return restarted.purgeCopies(scope)
            }
        }
        val sweepAt = Instant.now()
        fun sweep(at: Instant) = com.refinvest.core.tradingreview.application.TradingRawRetentionService(
            JdbcTradingArtifactRetentionAdapter(jdbc), checked, tx, Clock.fixed(at, ZoneOffset.UTC)).purgeExpired()
        try {
            val waiting = sweep(sweepAt)
            assertEquals(1, waiting.waiting)
            assertEquals(0, waiting.failures)
            assertEquals(1L, waiting.overdue)
            val pending = store.find(session.id, session.owner)!!.artifacts.single { it.id.value == 32101L }
            assertEquals(TradingArtifactRetentionStatus.DELETION_PENDING, pending.retentionStatus)
            assertNotNull(pending.wrappedDek)
            assertEquals(0, jdbc.queryForObject("SELECT deletion_attempt_count FROM trading_source_artifacts WHERE id=32101", Int::class.java))
            assertFailsWith<TradingFailure> { reader.read() }
            reader.close()
            assertEquals(0, sweep(sweepAt.plusSeconds(1)).deleted)
            val complete = sweep(sweepAt.plusSeconds(6))
            assertEquals(1, complete.deleted)
            assertEquals(0L, complete.overdue)
            val deleted = store.find(session.id, session.owner)!!.artifacts.single { it.id.value == 32101L }
            assertEquals(TradingArtifactRetentionStatus.DELETED, deleted.retentionStatus)
            assertNull(deleted.objectKey)
            assertNull(deleted.wrappedDek)
            assertNull(deleted.keyVersion)
            assertNull(deleted.filename)
            assertFalse(restarted.exists(keys[0]))
            assertTrue(restarted.exists(keys[1]))
            assertTrue(workspaces.purge(scope))
            assertEquals(0, sweep(sweepAt.plusSeconds(7)).deleted)
            assertEquals(TradingImportStatus.RECEIVED, store.find(session.id, session.owner)!!.status)
        } finally { reader.close(); temporary.close() }
    }

    @Test
    fun rawRetentionPersistsBackoffAndStopsAfterFiveRealFailures() = withDatabase { connection ->
        val (jdbc, _) = seedExpiredRetentionArtifact(connection)
        val raw = object : RawArtifactStorage by emptyStorage() {
            override fun purgeCopies(scope: TradingArtifactScope): Boolean = error("synthetic storage failure")
        }
        val tx = TransactionTemplate(tradingTestTransactions(requireNotNull(jdbc.dataSource)))
        var at = Instant.now()
        repeat(5) { attempt ->
            val service = com.refinvest.core.tradingreview.application.TradingRawRetentionService(
                JdbcTradingArtifactRetentionAdapter(jdbc), raw, tx, Clock.fixed(at, ZoneOffset.UTC))
            assertEquals(1, service.purgeExpired().failures)
            assertEquals(attempt + 1, jdbc.queryForObject("SELECT deletion_attempt_count FROM trading_source_artifacts", Int::class.java))
            assertEquals(1L, JdbcTradingArtifactRetentionAdapter(jdbc).countOverdue(at))
            assertTrue(JdbcTradingArtifactRetentionAdapter(jdbc).claimDue(at.plusSeconds(1), 20).isEmpty())
            assertNotNull(jdbc.queryForObject("SELECT wrapped_dek FROM trading_source_artifacts", ByteArray::class.java))
            if (attempt < 4) {
                val next = jdbc.queryForObject("SELECT deletion_next_attempt_at FROM trading_source_artifacts", java.sql.Timestamp::class.java)!!.toInstant()
                assertEquals(at.plusSeconds(5L shl attempt).epochSecond, next.epochSecond)
                at = next
            }
        }
        assertEquals("DELETION_FAILED", jdbc.queryForObject("SELECT retention_status FROM trading_source_artifacts", String::class.java))
        assertEquals("ARTIFACT_DELETION_FAILED", jdbc.queryForObject("SELECT deletion_failure FROM trading_source_artifacts", String::class.java))
        assertTrue(JdbcTradingArtifactRetentionAdapter(jdbc).claimDue(at.plusSeconds(3600), 20).isEmpty())
    }

    @Test
    fun rawRetentionChecksLeaseAfterWaitingForTheArtifactLock() = withDatabase { connection ->
        val (jdbc, artifact) = seedExpiredRetentionArtifact(connection)
        val store = JdbcTradingArtifactRetentionAdapter(jdbc)
        val at = Instant.now()
        val claim = store.claimDue(at, 1, java.time.Duration.ofSeconds(3)).single()
        val workerPid = jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)
        DriverManager.getConnection(System.getenv("TRADING_REVIEW_TEST_JDBC_URL"), System.getenv("TRADING_REVIEW_TEST_DB_USER"),
            System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD")).use { blocker ->
            blocker.schema = connection.schema
            blocker.autoCommit = false
            blocker.createStatement().use { it.executeQuery("SELECT id FROM trading_source_artifacts WHERE id=${artifact.id.value} FOR UPDATE").close() }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val task = executor.submit<Boolean> {
                    store.finish(RawArtifactRetentionCompletion(claim, RawArtifactPurgeOutcome.ABSENT, at))
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                var locked = false
                while (!locked && System.nanoTime() < deadline) {
                    blocker.createStatement().use { statement ->
                        statement.executeQuery("SELECT wait_event_type FROM pg_stat_activity WHERE pid=$workerPid").use {
                            locked = it.next() && it.getString(1) == "Lock"
                        }
                    }
                    if (!locked) Thread.sleep(10)
                }
                assertTrue(locked, "The completion must actually wait for the row lock")
                Thread.sleep(3100)
                blocker.commit()
                assertFalse(task.get(5, TimeUnit.SECONDS))
                assertEquals("DELETION_PENDING", jdbc.queryForObject("SELECT retention_status FROM trading_source_artifacts", String::class.java))
                assertNotNull(jdbc.queryForObject("SELECT wrapped_dek FROM trading_source_artifacts", ByteArray::class.java))
            } finally { blocker.rollback(); executor.shutdownNow() }
        }
    }

    @Test
    fun rawRetentionMigrationPreservesDescriptorsDeadlinesAndExistingClaims() = withDatabase(applyRetentionMigration = false) { connection ->
        val (jdbc, _) = seedExpiredRetentionArtifact(connection)
        jdbc.update("UPDATE trading_source_artifacts SET retention_status='DELETION_PENDING',deletion_attempt_count=3,deletion_claim_until=clock_timestamp()+INTERVAL '5 minutes'")
        val before = jdbc.queryForList("SELECT to_jsonb(a)::text FROM trading_source_artifacts a", String::class.java)
        val sql = javaClass.getResourceAsStream("/db/migration/V15__trading_raw_retention_retry.sql")!!.bufferedReader().use { it.readText() }
        connection.createStatement().use { statement -> splitSqlStatements(sql).forEach(statement::execute) }
        assertEquals(before, jdbc.queryForList("SELECT (to_jsonb(a)-'deletion_next_attempt_at')::text FROM trading_source_artifacts a", String::class.java))
        assertNull(jdbc.queryForObject("SELECT deletion_next_attempt_at FROM trading_source_artifacts", java.sql.Timestamp::class.java))
        assertTrue(JdbcTradingArtifactRetentionAdapter(jdbc).claimDue(Instant.now(), 20).isEmpty())
    }

    @Test
    fun rawOrphanCleanupKeepsReferencedObjectsAndActiveUploads(@org.junit.jupiter.api.io.TempDir root: java.nio.file.Path) = withDatabase { connection ->
        val (jdbc, tx, session) = retentionFixture(connection)
        val storage = com.refinvest.core.tradingreview.adapter.storage.LocalEncryptedRawArtifactStorage(root,
            javax.crypto.spec.SecretKeySpec(ByteArray(32) { 6 }, "AES"), "test-v1")
        val staged = storage.preparePublication(storage.putStaging(java.io.ByteArrayInputStream(byteArrayOf(1, 2)), 2,
            TradingSourceArtifactId(32201), session.id), TradingArtifactRole.TRADE_HISTORY)
        val key = requireNotNull(staged.publicationKey)
        storage.promote(staged, key)
        storage.completePublication(key)
        val imports = TradingImportTestStores(jdbc)
        assertTrue(tx.execute { imports.update(session.recordUpload(TradingSourceArtifactId(32201), TradingArtifactRole.TRADE_HISTORY,
            "text/csv", 2, staged.sha256, "AUTO", key, staged.wrappedDek, staged.keyVersion, now)) })
        val orphan = root.resolve("objects/${UUID.randomUUID()}")
        java.nio.file.Files.write(orphan, byteArrayOf(3, 4))
        java.nio.file.Files.setLastModifiedTime(orphan, java.nio.file.attribute.FileTime.from(Instant.EPOCH))
        java.nio.file.Files.setLastModifiedTime(root.resolve(key), java.nio.file.attribute.FileTime.from(Instant.EPOCH))
        val pending = storage.putStaging(java.io.ByteArrayInputStream(byteArrayOf(5, 6)), 2, TradingSourceArtifactId(32202), session.id)
        java.nio.file.Files.setLastModifiedTime(root.resolve(pending.stagingKey), java.nio.file.attribute.FileTime.from(Instant.EPOCH))
        val checked = object : RawArtifactStorage by storage {
            override fun cleanupOrphans(olderThan: Instant, limit: Int, isReferenced: (String) -> Boolean): Int {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                return storage.cleanupOrphans(olderThan, limit) {
                    assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                    isReferenced(it)
                }
            }
        }
        try {
            assertEquals(0, com.refinvest.core.tradingreview.application.TradingRawOrphanCleanupService(
                checked, imports, Clock.systemUTC(), 599).cleanupOrphans())
            assertEquals(1, com.refinvest.core.tradingreview.application.TradingRawOrphanCleanupService(
                checked, imports, Clock.systemUTC()).cleanupOrphans())
            assertTrue(storage.exists(key))
            assertTrue(storage.exists(pending.stagingKey))
            assertFalse(java.nio.file.Files.exists(orphan))
        } finally { storage.delete(pending.stagingKey) }
    }

    private fun retentionFixture(connection: Connection): Triple<JdbcTemplate, TransactionTemplate, TradingImportSession> {
        val (service, tx, stores) = accountBookFixture(connection)
        val account = tx.execute { service.execute(CreateTradingAccountCommand(TradingVenue.BINANCE, "A", "retention-account")) }.let { stores.find(it.id, MemberId(1))!! }
        val book = tx.execute { service.execute(CreateTradingBookCommand(account.id, "B", TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "retention-book")) }.let { stores.find(it.id, MemberId(1))!! }
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val session = TradingImportSession.create(TradingImportSessionId(32100), book, "UTC", "UTC", TradingPositionMode.ONE_WAY,
            TradingDataPolicy(), normalizationVersions, now)
        tx.execute { TradingImportTestStores(jdbc).insert(session) }
        return Triple(jdbc, tx, session)
    }

    private fun seedExpiredRetentionArtifact(connection: Connection): Pair<JdbcTemplate, TradingSourceArtifact> {
        val (jdbc, tx, session) = retentionFixture(connection)
        val uploaded = session.recordUpload(TradingSourceArtifactId(32101), TradingArtifactRole.TRADE_HISTORY,
            "text/csv", 2, "a".repeat(64), "AUTO", "objects/expired", byteArrayOf(1), "test-v1", now)
        assertTrue(tx.execute { TradingImportTestStores(jdbc).update(uploaded) })
        return jdbc to uploaded.artifacts.single()
    }

    private fun expiryService(jdbc: JdbcTemplate, tx: TransactionTemplate, clock: Clock,
        deletionStore: DeletionRequestStore = TradingReviewTestStores(jdbc).deletionAdapter):
        com.refinvest.core.tradingreview.application.TradingImportExpiryService {
        val nextId = java.util.concurrent.atomic.AtomicLong(600000)
        return com.refinvest.core.tradingreview.application.TradingImportExpiryService(
            JdbcTradingRawExpiryCandidates(jdbc), TradingReviewTestStores(jdbc).accountAdapter, TradingReviewTestStores(jdbc).bookAdapter,
            TradingImportTestStores(jdbc), deletionStore, object : TradingIdGenerator {
                override fun accountId() = error("not used")
                override fun bookId() = error("not used")
                override fun deletionId() = DeletionRequestId(nextId.incrementAndGet())
            }, tx, clock)
    }

    @Test
    fun rawExpiryAndItsDurableRequestRollBackTogetherAndReplayOnce() = withDatabase { connection ->
        val (jdbc, artifact) = seedExpiredRetentionArtifact(connection)
        val tx = TransactionTemplate(tradingTestTransactions(requireNotNull(jdbc.dataSource)))
        val before = importRows(jdbc)
        val requests = TradingReviewTestStores(jdbc).deletionAdapter
        val interrupted = object : DeletionRequestStore by requests {
            override fun insert(request: DeletionRequest) { requests.insert(request); error("synthetic expiry failure") }
        }
        val at = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        assertFailsWith<IllegalStateException> { expiryService(jdbc, tx, Clock.fixed(at, ZoneOffset.UTC), interrupted).expireDue() }
        assertEquals(before, importRows(jdbc))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_deletion_requests", Long::class.java))
        val service = expiryService(jdbc, tx, Clock.fixed(at, ZoneOffset.UTC))
        assertEquals(1, service.expireDue())
        assertEquals(0, service.expireDue())
        val root = TradingImportTestStores(jdbc).find(artifact.sessionId, MemberId(1))!!
        assertEquals(at, root.rawExpiredAt)
        assertEquals(1L, root.deletionGeneration)
        assertEquals("SOURCE_ARTIFACT_EXPIRED", root.failure?.code)
        assertFalse(root.failure!!.retryable)
        assertEquals("RAW_ONLY", jdbc.queryForObject("SELECT requested_scope FROM trading_deletion_requests", String::class.java))
        assertEquals(artifact.id.value, jdbc.queryForObject("SELECT target_id FROM trading_deletion_requests", Long::class.java))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_deletion_requests", Long::class.java))
    }

    @Test
    fun policyClockMigrationRecoversRejectedEvidenceWithoutExtendingOldRaw() = withDatabase { connection ->
        val (jdbc, _) = seedExpiredRetentionArtifact(connection)
        val rejected = now.plusSeconds(60)
        jdbc.update("""UPDATE trading_import_sessions SET status='REJECTED',failure_code='FILE_FORMAT_INVALID',
            failure_retryable=false,failure_user_action='NONE',failure_occurred_at=?,updated_at=?""",
            java.sql.Timestamp.from(rejected), java.sql.Timestamp.from(rejected))
        val deadline = jdbc.queryForObject("SELECT raw_expires_at FROM trading_source_artifacts", java.sql.Timestamp::class.java)
        connection.createStatement().use { statement ->
            statement.execute("ALTER TABLE trading_import_sessions DROP COLUMN accepted_at,DROP COLUMN rejected_at,DROP COLUMN raw_expired_at")
            val migration = javaClass.getResourceAsStream("/db/migration/V16__trading_raw_policy_clocks.sql")!!.bufferedReader().use { it.readText() }
            splitSqlStatements(migration).forEach(statement::execute)
        }
        val root = TradingImportTestStores(jdbc).find(TradingImportSessionId(32100), MemberId(1))!!
        assertEquals(rejected, root.rejectedAt)
        assertEquals(deadline, jdbc.queryForObject("SELECT raw_expires_at FROM trading_source_artifacts", java.sql.Timestamp::class.java))
        assertNull(root.acceptedAt)
        assertNull(root.rawExpiredAt)
    }

    private fun importRows(jdbc: JdbcTemplate) = listOf(
        jdbc.queryForList("SELECT row_to_json(s)::text FROM trading_import_sessions s ORDER BY id", String::class.java),
        jdbc.queryForList("SELECT row_to_json(a)::text FROM trading_source_artifacts a ORDER BY id", String::class.java),
    )

    @Test
    fun accountCreationReplayAfterDeletionIsGone() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val account = tx.execute { service.execute(CreateTradingAccountCommand(TradingVenue.BINANCE, "A", "create-a")) }
        tx.execute { service.execute(DeleteTradingAccountCommand(account.id, "delete-a")) }
        assertEquals("RESOURCE_DELETED", assertFailsWith<TradingFailure> {
            tx.execute { service.execute(CreateTradingAccountCommand(TradingVenue.BINANCE, "A", "create-a")) }
        }.code)
        assertEquals(1L, stores.find(account.id, MemberId(1))?.deletionGeneration)
    }

    @Test
    fun bookCreationReplayAfterDeletionIsGone() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val account = tx.execute { service.execute(CreateTradingAccountCommand(TradingVenue.BINANCE, "A", "create-a")) }
        val book = tx.execute { service.execute(CreateTradingBookCommand(account.id, "B", TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "create-b")) }
        tx.execute { service.execute(DeleteTradingBookCommand(book.id, "delete-b")) }
        assertEquals("RESOURCE_DELETED", assertFailsWith<TradingFailure> {
            tx.execute { service.execute(CreateTradingBookCommand(account.id, "B", TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "create-b")) }
        }.code)
        assertEquals(1L, stores.find(book.id, MemberId(1))?.deletionGeneration)
    }

    @Test
    fun ordinaryAggregateWritesCannotReopenDeletedRowsOrChangeBookIdentity() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val account = tx.execute { service.execute(CreateTradingAccountCommand(TradingVenue.BINANCE, "A", "create-a")) }.let { stores.find(it.id, MemberId(1))!! }
        val book = tx.execute { service.execute(CreateTradingBookCommand(account.id, "B", TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "create-b")) }.let { stores.find(it.id, MemberId(1))!! }
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val changedParent = TradingBook.restore(book.id, TradingAccountId(999), book.owner, book.venue,
            book.displayName, book.productFamily, book.positionMode, book.settlementAsset, book.reviewTimezone,
            book.status, 0, book.createdAt, book.updatedAt, book.version)
        val changedTimezone = TradingBook.restore(book.id, book.accountId, book.owner, book.venue,
            book.displayName, book.productFamily, book.positionMode, book.settlementAsset, "Asia/Seoul",
            book.status, 0, book.createdAt, book.updatedAt, book.version)
        assertFalse(stores.update(changedParent))
        assertFalse(stores.update(changedTimezone))
        assertFailsWith<IllegalStateException> { stores.find(account.id, account.owner, forUpdate = true) }
        tx.execute { service.execute(DeleteTradingAccountCommand(account.id, "delete-a")) }
        val before = accountBookRows(jdbc)
        assertFailsWith<IllegalArgumentException> {
            stores.insert(TradingBook.create(TradingBookId(9999), account, "Stale parent snapshot",
                TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now))
        }
        assertFalse(stores.update(account.rename("Stale", now)))
        assertFalse(stores.update(book.rename("Stale", now)))
        val currentAccount = stores.find(account.id, account.owner)!!
        val forgedAccount = TradingAccount.restore(account.id, account.owner, account.venue, "Reopened",
            TradingLifecycleStatus.ACTIVE, 0, currentAccount.createdAt, now, currentAccount.version)
        assertFalse(stores.update(forgedAccount))
        val currentBook = stores.find(book.id, book.owner)!!
        val forgedBook = TradingBook.restore(book.id, book.accountId, book.owner, book.venue, "Reopened",
            book.productFamily, book.positionMode, book.settlementAsset, book.reviewTimezone,
            TradingLifecycleStatus.ACTIVE, 0, currentBook.createdAt, now, currentBook.version)
        assertFalse(stores.update(forgedBook))
        assertEquals(before, accountBookRows(jdbc))
        assertNull(stores.find(book.id, MemberId(2)))
        assertTrue(stores.accountReader.list(MemberId(1), 0, 20).items.isEmpty())
        assertTrue(stores.bookReader.list(account.id, MemberId(1), 0, 20).items.isEmpty())
    }

    @Test
    fun cascadeKeepsChildStateRestorableWhenTheRequestClockIsBehind() = withDatabase { connection ->
        val (_, tx, stores) = accountBookFixture(connection)
        val account = TradingAccount.create(TradingAccountId(1), MemberId(1), TradingVenue.BINANCE, "A", now)
        val book = TradingBook.create(TradingBookId(2), account, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now.plusSeconds(1))
        stores.insert(account); stores.insert(book)
        tx.execute {
            assertTrue(stores.update(account.requestDeletion(now)))
            assertEquals(1L, stores.bookDeletionCascade.markDeleting(TradingAccountBookDeletion(
                account.id, account.owner, 1, 1, now)))
        }
        val restored = assertNotNull(stores.find(book.id, book.owner))
        assertEquals(TradingLifecycleStatus.DELETION_PENDING, restored.status)
        assertEquals(1L, restored.deletionGeneration)
        assertEquals(book.updatedAt, restored.updatedAt)
    }

    @Test
    fun staleCascadeFencesLeaveOtherOwnersAndAlreadyDeletingBooksUnchanged() = withDatabase { connection ->
        val (_, tx, stores) = accountBookFixture(connection)
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val account = TradingAccount.create(TradingAccountId(1), MemberId(1), TradingVenue.BINANCE, "A", now)
        val other = TradingAccount.create(TradingAccountId(2), MemberId(2), TradingVenue.BINANCE, "Other", now)
        stores.insert(account); stores.insert(other)
        fun book(id: Long, parent: TradingAccount) = TradingBook.create(TradingBookId(id), parent, "B",
            TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
        val alreadyDeleting = book(11, account)
        stores.insert(alreadyDeleting); stores.insert(book(12, account)); stores.insert(book(21, other))
        assertTrue(stores.update(alreadyDeleting.requestDeletion(now)))
        jdbc.update("UPDATE trading_books SET status='ARCHIVED' WHERE id=12")
        tx.execute {
            assertTrue(stores.update(account.requestDeletion(now)))
            val fence = TradingAccountBookDeletion(account.id, account.owner, 1, 1, now)
            val before = accountBookRows(jdbc)
            for (stale in listOf(fence.copy(owner = other.owner), fence.copy(accountDeletionGeneration = 2),
                fence.copy(expectedAccountVersion = 2), fence.copy(requestedAt = now.minusSeconds(1)))) {
                assertNull(stores.bookDeletionCascade.markDeleting(stale))
                assertEquals(before, accountBookRows(jdbc))
            }
            assertEquals(2L, stores.bookDeletionCascade.markDeleting(fence))
            assertEquals(2L, stores.bookDeletionCascade.markDeleting(fence))
        }
        assertEquals(1L, stores.find(TradingBookId(11), MemberId(1))!!.deletionGeneration)
        assertEquals(1L, stores.find(TradingBookId(12), MemberId(1))!!.deletionGeneration)
        assertEquals(TradingLifecycleStatus.ACTIVE, stores.find(TradingBookId(21), MemberId(2))!!.status)
        assertEquals(0L, stores.find(TradingBookId(21), MemberId(2))!!.deletionGeneration)
    }

    @Test
    fun deletionRollbackRestoresRootChildrenJournalAndIdempotencyTogether() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val account = tx.execute { service.execute(CreateTradingAccountCommand(TradingVenue.BINANCE, "A", "create-a")) }.let { stores.find(it.id, MemberId(1))!! }
        tx.execute { service.execute(CreateTradingBookCommand(account.id, "B", TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "create-b")) }
        val before = accountBookRows(jdbc)
        assertFailsWith<IllegalStateException> {
            tx.execute { service.execute(DeleteTradingAccountCommand(account.id, "delete-a")); error("Synthetic failure after cascade and journal") }
        }
        assertEquals(before, accountBookRows(jdbc))
        assertEquals(TradingLifecycleStatus.ACTIVE, stores.find(account.id, account.owner)!!.status)
        val deletion = tx.execute { service.execute(DeleteTradingAccountCommand(account.id, "delete-a")) }
        assertEquals(1L, deletion.bookCount)
        assertEquals(deletion.id, tx.execute { service.execute(DeleteTradingAccountCommand(account.id, "delete-a")) }.id)
        assertEquals(1L, stores.find(account.id, account.owner)!!.deletionGeneration)
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_deletion_requests", Long::class.java))
    }

    @Test
    fun aConcurrentBookWriterWaitsForAccountDeletionAndCannotUndoIt() = withDatabase { first ->
        val (service, tx, stores) = accountBookFixture(first)
        val jdbc = JdbcTemplate(SingleConnectionDataSource(first, true))
        val account = tx.execute { service.execute(CreateTradingAccountCommand(TradingVenue.BINANCE, "A", "create-a")) }.let { stores.find(it.id, MemberId(1))!! }
        val book = tx.execute { service.execute(CreateTradingBookCommand(account.id, "B", TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "create-b")) }.let { stores.find(it.id, MemberId(1))!! }
        DriverManager.getConnection(System.getenv("TRADING_REVIEW_TEST_JDBC_URL"),
            System.getenv("TRADING_REVIEW_TEST_DB_USER"), System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD")).use { second ->
            second.schema = first.schema
            val secondSource = SingleConnectionDataSource(second, true)
            val secondJdbc = JdbcTemplate(secondSource)
            val writer = TradingReviewTestStores(secondJdbc).bookAdapter
            val secondTx = TransactionTemplate(tradingTestTransactions(secondSource))
            val backend = secondJdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!
            val executor = Executors.newSingleThreadExecutor()
            var result: java.util.concurrent.Future<Boolean>? = null
            try {
                tx.execute {
                    service.execute(DeleteTradingAccountCommand(account.id, "delete-a"))
                    result = executor.submit<Boolean> { secondTx.execute { writer.update(book.rename("Stale", now)) }!! }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    var waited = false
                    while (System.nanoTime() < deadline) {
                        jdbc.execute("SELECT pg_stat_clear_snapshot()")
                        if (jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')",
                            Boolean::class.java, backend) == true) { waited = true; break }
                        Thread.sleep(10)
                    }
                    assertTrue(waited, "Expected an actual PostgreSQL row lock wait")
                }
                val committed = accountBookRows(jdbc)
                assertFalse(result!!.get(15, TimeUnit.SECONDS))
                assertEquals(committed, accountBookRows(jdbc))
                assertEquals(1L, stores.find(book.id, book.owner)!!.deletionGeneration)
            } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS)) }
        }
    }

    private fun accountBookFixture(connection: Connection, seedMembers: Boolean = true): Triple<TradingAccountBookTestServices, TransactionTemplate, TradingReviewTestStores> {
        val source = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(source)
        if (seedMembers) members(jdbc)
        val stores = TradingReviewTestStores(jdbc)
        val ids = object : TradingIdGenerator {
            var next = 10000L
            override fun accountId() = TradingAccountId(next++)
            override fun bookId() = TradingBookId(next++)
            override fun deletionId() = DeletionRequestId(next++)
        }
        val service = TradingAccountBookTestServices(object : TradingMemberProvider {
            override fun currentMemberId() = MemberId(1)
        }, ids, stores, stores, stores, stores, Clock.fixed(now, ZoneOffset.UTC),
            stores.accountReader, stores.bookReader, stores.bookDeletionCascade, JdbcDeletionScopeInventory(jdbc))
        return Triple(service, TransactionTemplate(tradingTestTransactions(source)), stores)
    }

    private fun accountBookRows(jdbc: JdbcTemplate): Any = listOf(
        jdbc.queryForList("SELECT * FROM trading_accounts ORDER BY id"),
        jdbc.queryForList("SELECT * FROM trading_books ORDER BY id"),
        jdbc.queryForList("SELECT * FROM trading_deletion_requests ORDER BY id"),
        jdbc.queryForList("SELECT * FROM trading_idempotency ORDER BY owner_member_id,operation_id,request_key"),
    )

    @Test
    fun legacyV1MigrationBackfillsPinAndLogicalKeyWithoutChangingDispatchOrRevision() =
        withDatabase(applyPinMigration = false) { connection ->
            val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
            val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
            members(jdbc)
            val review = TradingReviewTestStores(jdbc)
            val account = TradingAccount.create(TradingAccountId(3001), MemberId(1),
                TradingVenue.BINANCE, "A", now)
            review.insert(account)
            val book = TradingBook.create(TradingBookId(3002), account, "B",
                TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY,
                TradingSettlementAsset.USDT, "UTC", now)
            review.insert(book)
            val session = TradingImportSession.create(TradingImportSessionId(3003), book,
                "UTC", "UTC", TradingPositionMode.ONE_WAY, TradingDataPolicy(),
                normalizationVersions, now)
            TradingImportTestStores(jdbc).insert(session)
            val runId = UUID.randomUUID().toString()
            val dispatchId = UUID.randomUUID().toString()
            val token = UUID.randomUUID().toString()
            val inputHash = "a".repeat(64)
            val oldKey = "b".repeat(64)
            val dispatchKey = "c".repeat(64)
            val resultHash = "d".repeat(64)
            jdbc.update("""INSERT INTO trading_normalization_runs
                (id,import_session_id,trading_book_id,owner_member_id,input_fingerprint,logical_key,
                input_json,target_version_set_json,instrument_terms_json,data_policy_version,
                retry_policy_version,retry_policy_json,deletion_generation,status,current_attempt,
                attempt_token,next_attempt_at,created_at,updated_at)
                VALUES (?::uuid,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?::jsonb,0,'PUBLISHED',1,
                ?::uuid,?,?,?)""", runId, session.id.value, book.id.value, 1, inputHash, oldKey,
                "{}", normalizationVersions, "[]", "TRADING_DATA_POLICY_V1", "retry-v1",
                "{}", token, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now))
            jdbc.update("""INSERT INTO trading_normalization_dispatches
                (id,run_id,compute_key,status,next_attempt_at,created_at,updated_at)
                VALUES (?::uuid,?::uuid,?,'PUBLISHED',?,?,?)""", dispatchId, runId,
                dispatchKey, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now))
            jdbc.update("""INSERT INTO trading_ledger_revisions
                (id,run_id,trading_book_id,import_session_id,revision_number,input_set_hash,
                input_hash_specification_version,input_identity_json,target_version_set_json,
                result_schema_version,result_hash_specification_version,result_hash,
                ledger_content_hash,reconciliation_manifest_hash,reconciliation_manifest_json,
                coverage_json,capability_json,quality_report_json,counts_json,section_digests_json,
                published_at) VALUES (?::uuid,?::uuid,?,?,1,?,'TRADING_INPUT_SET_HASH_V1',
                '{}'::jsonb,?::jsonb,?,?,?,? ,?,'{}'::jsonb,'[]'::jsonb,'[]'::jsonb,
                '{}'::jsonb,'{}'::jsonb,'[]'::jsonb,?)""", UUID.randomUUID().toString(),
                runId, book.id.value, session.id.value, inputHash, normalizationVersions,
                NormalizationResultVersionPin.V1.schemaVersion,
                NormalizationResultVersionPin.V1.hashSpecificationVersion,
                resultHash, "e".repeat(64), "f".repeat(64), java.sql.Timestamp.from(now))
            val migration = javaClass.getResourceAsStream(
                "/db/migration/V9__trading_normalization_result_pin.sql")!!
                .bufferedReader().use { it.readText() }
            connection.createStatement().use { statement ->
                splitSqlStatements(migration).forEach(statement::execute)
            }
            val expectedKey = NormalizationInputFingerprint.logicalKey(1, inputHash, 0,
                NormalizationResultVersionPin.V1, mapper)
            assertNotEquals(oldKey, expectedKey)
            assertEquals(expectedKey, jdbc.queryForObject("""SELECT logical_key FROM
                trading_normalization_runs WHERE id=?::uuid""", String::class.java, runId))
            assertEquals(dispatchKey, jdbc.queryForObject("""SELECT compute_key FROM
                trading_normalization_dispatches WHERE id=?::uuid""", String::class.java, dispatchId))
            assertEquals(resultHash, jdbc.queryForObject("""SELECT result_hash FROM
                trading_ledger_revisions WHERE run_id=?::uuid""", String::class.java, runId))
            assertEquals(NormalizationResultVersionPin.V1,
                TradingNormalizationTestRuntime(jdbc, mapper).findRun(runId)?.resultVersionPin)
            assertEquals(NormalizationResultVersionPin.V1.schemaVersion,
                jdbc.queryForObject("""SELECT result_schema_version FROM
                    trading_normalization_dispatches WHERE id=?::uuid""", String::class.java, dispatchId))
            assertFailsWith<Exception> {
                jdbc.update("""UPDATE trading_normalization_runs
                    SET result_schema_version='TRADING_NORMALIZATION_RESULT_V2' WHERE id=?::uuid""", runId)
            }
        }

    @Test
    fun v2SupplementMustMatchPublishedUnitAndCanonicalEvidence() = withDatabase { connection ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        members(jdbc)
        val review = TradingReviewTestStores(jdbc)
        val account = TradingAccount.create(TradingAccountId(1201), MemberId(1), TradingVenue.BINANCE, "A", now)
        review.insert(account)
        val book = TradingBook.create(TradingBookId(1202), account, "B",
            TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY,
            TradingSettlementAsset.USDT, "UTC", now)
        review.insert(book)
        val session = TradingImportSession.create(TradingImportSessionId(1203), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), normalizationVersions, now)
        TradingImportTestStores(jdbc).insert(session)
        val inputs = TradingArtifactRole.entries.mapIndexed { index, role ->
            NormalizationArtifactInput(TradingSourceArtifactId(1204L + index), role,
                (if (role == TradingArtifactRole.TRADE_HISTORY) "a" else "b").repeat(64),
                20, "text/csv", "AUTO")
        }
        val input = NormalizationInput(book.id, session.id, 1, inputs, "BINANCE",
            "LINEAR_PERPETUAL", "ONE_WAY", "USDT", "UTC", "UTC", normalizationVersions,
            "[]", "TRADING_DATA_POLICY_V1", "retry-v1",
            """{"policyVersion":"retry-v1","maxAttempts":1,"maxElapsedTime":"PT1H","initialDelay":"PT1S","maxDelay":"PT10S","jitter":"NONE"}""", 0)
        val identity = NormalizationInputIdentity.fromPinnedInput(input,
            mapper.readValue(normalizationVersions, Map::class.java))
        val runId = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        val run = NormalizationRun.restore(NormalizationRunId(runId), input, NormalizationInputCanonicalizer.hash(identity),
            "b".repeat(64), NormalizationRunStatus.DISPATCH_PENDING, 1, token,
            null, null, now, now, null, now, null, 0, inputIdentity = identity,
            resultDeliveryPolicy = deliveryPolicy,
            resultVersionPin = NormalizationResultVersionPin.V2)
        val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId, "c".repeat(64),
            NormalizationDispatchStatus.PENDING, 0, now, null, null, null, now, now,
            resultVersionPin = NormalizationResultVersionPin.V2)
        TradingNormalizationTestRuntime(jdbc, mapper).seed(run, dispatch)
        val fixture = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .map { java.io.File(it,
                "tradingreview/application/src/test/resources/trading-normalization/result-integrity-v2.json") }
            .first { it.isFile }
        @Suppress("UNCHECKED_CAST")
        val vector = mapper.readValue(fixture.readText(), Map::class.java) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val payload = vector.getValue("terminalPayload") as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val manifest = payload.getValue("reconciliationManifest") as Map<String, Any?>
        val results = TradingLedgerTestStore(jdbc, mapper)
        @Suppress("UNCHECKED_CAST")
        val descriptor = NormalizationResultDescriptor("job-v2", dispatch.id, runId,
            "payload-v2", 1, "a".repeat(64), token, 0, run.inputFingerprint,
            identity.hashSpecificationVersion, identity.targetVersionSet.toWireValue(),
            "TRADING_NORMALIZATION_RESULT_V2", "TRADING_NORMALIZATION_RESULT_HASH_V2",
            payload.getValue("counts") as Map<String, Any?>, payload.getValue("sectionDigests") as List<Any?>,
            "b".repeat(64), "c".repeat(64), now.plusSeconds(3600), "RESULT_DELIVERY_V1", "identity")
        results.beginStage(run, descriptor, now)
        fun stage(section: String, id: String, value: Any?) = results.stageItem(runId,
            NormalizationStagedItem(section, id,
                com.refinvest.core.tradingreview.application.NormalizationResultCanonicalizer.hash(value),
                mapper.writeValueAsString(value)))
        @Suppress("UNCHECKED_CAST")
        (payload.getValue("canonicalRecords") as List<Map<String, Any?>>).forEach {
            stage("CANONICAL_RECORDS", it.getValue("recordId") as String, it)
        }
        @Suppress("UNCHECKED_CAST")
        (manifest.getValue("reviewUnits") as List<Map<String, Any?>>).forEach {
            stage("REVIEW_UNITS", it.getValue("reviewUnitId") as String, it)
        }
        @Suppress("UNCHECKED_CAST")
        val supplement = payload.getValue("analyticsInputSupplement") as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        (supplement.getValue("reviewUnitEngineInputs") as List<Map<String, Any?>>).forEach {
            stage("ANALYTICS_INPUT_SUPPLEMENT_ROWS", it.getValue("reviewUnitId") as String, it)
        }
        assertFalse(results.hasInvalidSupplement(runId))
        jdbc.update("""UPDATE trading_normalization_staged_items SET item_json=jsonb_set(item_json,
            '{allocationEvidence,0,allocatedFee,currency}', '"BTC"'::jsonb)
            WHERE run_id=?::uuid AND section_type='ANALYTICS_INPUT_SUPPLEMENT_ROWS'""", runId)
        assertTrue(results.hasInvalidSupplement(runId))
    }

    @Test
    fun verifiedTerminalPayloadPublishesOnceAndAcknowledgesAfterCommit() =
        verifyPublishedTerminal(false)

    @Test
    fun verifiedV2TerminalPublishesImmutableAnalyticsSupplement() =
        verifyPublishedTerminal(true)

    @Test
    fun rawPurgeKeepsPublishedRevisionEvidenceAndAnalyticsSupplement() =
        verifyPublishedTerminal(true, expireRaw = true)

    @Test
    fun expiredSourcesBlockLatePublicationBeforeTheExpiryWorkerRuns() =
        verifyPublishedTerminal(false, expireBeforePublication = true)

    @Test
    fun mismatchedTerminalDescriptorBlocksRevisionAndPersistedAck() =
        verifyPublishedTerminal(false, mismatchedDescriptor = true)

    @Test
    fun mismatchedTerminalPayloadBlocksRevisionAndPersistedAck() =
        verifyPublishedTerminal(false, mismatchedPayload = true)

    private fun verifyPublishedTerminal(v2: Boolean, mismatchedDescriptor: Boolean = false,
                                        mismatchedPayload: Boolean = false, expireRaw: Boolean = false,
                                        expireBeforePublication: Boolean = false) =
        withDatabase { connection ->
        val source = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(source)
        val transactions = TransactionTemplate(tradingTestTransactions(source))
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        val canonicalizer = com.refinvest.core.tradingreview.application.NormalizationResultCanonicalizer
        members(jdbc)
        val review = TradingReviewTestStores(jdbc)
        val account = TradingAccount.create(TradingAccountId(971), MemberId(1), TradingVenue.BINANCE, "A", now)
        review.insert(account)
        val book = TradingBook.create(TradingBookId(972), account, "B",
            TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY,
            TradingSettlementAsset.USDT, "UTC", now)
        review.insert(book)
        val fixture = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .map { java.io.File(it,
                "tradingreview/application/src/test/resources/trading-normalization/result-integrity-v${if (v2) 2 else 1}.json") }
            .first { it.isFile }
        @Suppress("UNCHECKED_CAST")
        val vector = mapper.readValue(fixture.readText(), Map::class.java) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val payload = mapper.readValue(mapper.writeValueAsString(vector.getValue("terminalPayload")),
            Map::class.java) as MutableMap<String, Any?>
        val versions = mapper.writeValueAsString(payload.getValue("targetVersionSet"))
        val imports = TradingImportTestStores(jdbc)
        val session = TradingImportSession.create(TradingImportSessionId(973), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), versions, now)
        imports.insert(session)
        val inputs = TradingArtifactRole.entries.mapIndexed { index, role ->
            val id = 974L + index
            val sha = (if (role == TradingArtifactRole.TRADE_HISTORY) "a" else "b").repeat(64)
            imports.seedArtifact(TradingSourceArtifact.restore(TradingSourceArtifactId(id), session.id, role,
                null, "text/csv", 20, sha, "AUTO", "objects/" + UUID.randomUUID(),
                byteArrayOf(1), "test-v1", TradingArtifactRetentionStatus.ACTIVE,
                now, now.plusSeconds(3600), null, null, 0))
            NormalizationArtifactInput(TradingSourceArtifactId(id), role, sha, 20, "text/csv", "AUTO")
        }
        val input = NormalizationInput(book.id, session.id, 1, inputs, "BINANCE",
            "LINEAR_PERPETUAL", "ONE_WAY", "USDT", "UTC", "UTC", versions,
            if (v2) """[{"instrumentId":"instrument-synthetic-1","venueSymbol":"SYNTHETICUSDT","settlementAsset":"USDT","quantityStep":"0.01","priceTick":"0.01","contractMultiplier":"1","settlementPrecision":8,"instrumentTermsVersion":"TERMS_V1"}]""" else "[]",
            "TRADING_DATA_POLICY_V1", "retry-v1",
            """{"policyVersion":"retry-v1","maxAttempts":3,"maxElapsedTime":"PT1H","initialDelay":"PT1S","maxDelay":"PT10S","jitter":"NONE"}""", 0)
        val identity = NormalizationInputIdentity.fromPinnedInput(input,
            mapper.readValue(versions, Map::class.java))
        val runId = UUID.randomUUID().toString()
        val attemptToken = UUID.randomUUID().toString()
        val run = NormalizationRun.restore(NormalizationRunId(runId), input, NormalizationInputCanonicalizer.hash(identity),
            "b".repeat(64), NormalizationRunStatus.DISPATCH_PENDING, 1, attemptToken,
            null, null, now, now, null, now, null, 0, inputIdentity = identity,
            resultDeliveryPolicy = deliveryPolicy,
            resultVersionPin = if (v2 && !mismatchedPayload) NormalizationResultVersionPin.V2
                else NormalizationResultVersionPin.V1)
        val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId, "c".repeat(64),
            NormalizationDispatchStatus.PENDING, 0, now, null, null, null, now, now,
            resultVersionPin = run.resultVersionPin)
        val runs = TradingNormalizationTestRuntime(jdbc, mapper)
        runs.seed(run, dispatch)
        assertEquals(deliveryPolicy, runs.findRun(runId)?.resultDeliveryPolicy)
        assertTrue(imports.seedValidation(session.id, runId, now))
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
        evidence.forEach { row ->
            row["coreArtifactId"] = inputs.single {
                it.role.name == row["artifactRole"]
            }.artifactId.value.toString()
        }
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
        if (mismatchedPayload) {
            payload["resultSchemaVersion"] = NormalizationResultVersionPin.V2.schemaVersion
            payload["resultHashSpecificationVersion"] =
                NormalizationResultVersionPin.V2.hashSpecificationVersion
        }
        val bytes = mapper.writeValueAsBytes(payload)
        val transportHash = java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
        @Suppress("UNCHECKED_CAST")
        val descriptor = NormalizationResultDescriptor("job-synthetic-1", dispatch.id, runId,
            "payload-synthetic-1", bytes.size.toLong(), transportHash, attemptToken, 0,
            run.inputFingerprint, identity.hashSpecificationVersion,
            identity.targetVersionSet.toWireValue(),
            run.resultVersionPin.schemaVersion, run.resultVersionPin.hashSpecificationVersion,
            payload.getValue("counts") as Map<String, Any?>,
            digests, manifestHash, resultHash, now.plusSeconds(3600), "RESULT_DELIVERY_V1", "identity")
        val deliveredDescriptor = if (mismatchedDescriptor) descriptor.copy(
            resultSchemaVersion = NormalizationResultVersionPin.V2.schemaVersion,
            resultHashSpecificationVersion = NormalizationResultVersionPin.V2.hashSpecificationVersion)
            else descriptor
        var acknowledgeCalls = 0
        var descriptorCalls = 0
        var downloadCalls = 0
        val client = object : TradingNormalizationResultClient {
            override fun descriptor(jobId: String): NormalizationResultLookup {
                descriptorCalls++
                check(descriptorCalls == 1) { "Completed staging must not fetch Compute again" }
                return NormalizationResultLookup.Found(deliveredDescriptor)
            }
            override fun download(descriptor: NormalizationResultDescriptor,
                                  policy: NormalizationResultDeliveryPolicy): NormalizationPayloadLookup {
                downloadCalls++
                check(downloadCalls == 1) { "Completed staging must not download again" }
                val path = java.nio.file.Files.createTempFile("synthetic-result-", ".json")
                java.nio.file.Files.write(path, bytes)
                return NormalizationPayloadLookup.Found(
                    object : NormalizationPayloadFile {
                        override val byteSize = bytes.size.toLong()
                        override val sha256 = transportHash
                        override fun open() = java.nio.file.Files.newInputStream(path)
                        override fun close() { java.nio.file.Files.deleteIfExists(path) }
                    })
            }
            override fun acknowledge(acknowledgement: NormalizationPayloadAcknowledgement): NormalizationAckOutcome {
                acknowledgeCalls++
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                assertTrue(connection.autoCommit)
                assertEquals("PERSISTED", acknowledgement.disposition)
                assertEquals(TradingImportStatus.ACCEPTED, imports.find(session.id, MemberId(1))?.status)
                DriverManager.getConnection(System.getenv("TRADING_REVIEW_TEST_JDBC_URL"),
                    System.getenv("TRADING_REVIEW_TEST_DB_USER"),
                    System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD")).use { observer ->
                    observer.schema = connection.schema
                    val committed = JdbcTemplate(SingleConnectionDataSource(observer, true))
                    assertEquals(1L, committed.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
                    assertEquals("ACCEPTED", committed.queryForObject("SELECT status FROM trading_import_sessions", String::class.java))
                    assertEquals(acknowledgement.idempotencyKey, committed.queryForObject(
                        "SELECT idempotency_key FROM trading_normalization_ack_dispatches", String::class.java))
                }
                return NormalizationAckOutcome.Accepted
            }
        }
        val results = TradingLedgerTestStore(jdbc, mapper)
        if (mismatchedDescriptor || mismatchedPayload) {
            val processor = com.refinvest.core.tradingreview.application.TradingNormalizationResultProcessor(
                runs, imports, review, results, client, transactions,
                Clock.fixed(now, ZoneOffset.UTC), mapper,
                object : TradingRetryJitter { override fun factor() = 0.5 }, JdbcTradingNormalizationTransitionGate(jdbc) , publications = results, revisionIds = object : LedgerRevisionIdGenerator {
                override fun revisionId() = com.refinvest.core.tradingreview.domain.valueobject.LedgerRevisionId(UUID.randomUUID())
            })
            assertTrue(processor.processOne())
            assertEquals(1, descriptorCalls)
            assertEquals(if (mismatchedPayload) 1 else 0, downloadCalls)
            assertEquals(0, acknowledgeCalls)
            assertEquals(TradingImportStatus.PROCESSING_FAILED,
                imports.find(session.id, MemberId(1))?.status)
            assertEquals("RESULT_VERSION_PIN_MISMATCH", jdbc.queryForObject(
                "SELECT last_failure_code FROM trading_normalization_runs WHERE id=?::uuid",
                String::class.java, runId))
            assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
            assertEquals(0L, jdbc.queryForObject(
                "SELECT count(*) FROM trading_normalization_ack_dispatches", Long::class.java))
            return@withDatabase
        }
        val interrupted = object : TradingLedgerPublication by results {
            override fun publish(publication: LedgerPublication): Boolean {
                assertTrue(results.publish(publication))
                error("simulated process failure after publication writes but before commit")
            }
        }
        val processor = com.refinvest.core.tradingreview.application.TradingNormalizationResultProcessor(
            runs, imports, review, results, client, transactions, Clock.fixed(now, ZoneOffset.UTC), mapper,
            object : TradingRetryJitter { override fun factor() = 0.5 }, JdbcTradingNormalizationTransitionGate(jdbc) , publications = interrupted, revisionIds = object : LedgerRevisionIdGenerator {
                override fun revisionId() = com.refinvest.core.tradingreview.domain.valueobject.LedgerRevisionId(UUID.randomUUID())
            })
        assertTrue(processor.processOne())
        assertEquals(TradingImportStatus.VALIDATING, imports.find(session.id, MemberId(1))?.status)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_result_staging", Long::class.java))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_ack_dispatches", Long::class.java))
        val acknowledgement: AcknowledgeTradingNormalizationResultUseCase =
            com.refinvest.core.tradingreview.application.TradingNormalizationAcknowledgementService(
                results, client, transactions, Clock.fixed(now, ZoneOffset.UTC), mapper,
                object : TradingRetryJitter { override fun factor() = 0.5 })
        assertFalse(acknowledgement.acknowledgeOne())
        assertEquals(0, acknowledgeCalls)
        if (expireBeforePublication) jdbc.update("UPDATE trading_source_artifacts SET raw_expires_at=?",
            java.sql.Timestamp.from(now.plusSeconds(1)))
        val recoveredProcessor = com.refinvest.core.tradingreview.application.TradingNormalizationResultProcessor(
            runs, imports, review, results, client, transactions,
            Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC), mapper,
            object : TradingRetryJitter { override fun factor() = 0.5 }, JdbcTradingNormalizationTransitionGate(jdbc) , publications = results, revisionIds = object : LedgerRevisionIdGenerator {
                override fun revisionId() = com.refinvest.core.tradingreview.domain.valueobject.LedgerRevisionId(UUID.randomUUID())
            })
        assertTrue(recoveredProcessor.processOne())
        if (expireBeforePublication) {
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_ack_dispatches", Long::class.java))
            assertEquals("SOURCE_ARTIFACT_EXPIRED", jdbc.queryForObject("SELECT last_failure_code FROM trading_normalization_dispatches", String::class.java))
            assertEquals(1, expiryService(jdbc, transactions, Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC)).expireDue())
            assertEquals(0, expiryService(jdbc, transactions, Clock.fixed(now.plusSeconds(2), ZoneOffset.UTC)).expireDue())
            val expired = imports.find(session.id, MemberId(1))!!
            assertEquals(TradingImportStatus.PROCESSING_FAILED, expired.status)
            assertEquals("SOURCE_ARTIFACT_EXPIRED", expired.failure?.code)
            assertEquals(1L, expired.deletionGeneration)
            assertNotNull(expired.rawExpiredAt)
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_deletion_requests", Long::class.java))
            deletionWorker(jdbc, transactions, emptyStorage(), Clock.fixed(now.plusSeconds(3), ZoneOffset.UTC)).purgePending()
            assertEquals("COMPLETED", jdbc.queryForObject("SELECT state FROM trading_deletion_requests", String::class.java))
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
            return@withDatabase
        }
        assertEquals(1, descriptorCalls)
        assertEquals(1, downloadCalls)
        assertEquals(TradingImportStatus.ACCEPTED, imports.find(session.id, MemberId(1))?.status)
        assertTrue(imports.find(session.id, MemberId(1))!!.artifacts.all {
            it.rawExpiresAt == now.plusSeconds(1).plus(java.time.Duration.ofDays(7))
        }, "Accepted raw must expire seven days after publication")
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
        val revisionId = assertNotNull(jdbc.queryForObject(
            "SELECT id::text FROM trading_ledger_revisions", String::class.java))
        assertEquals(1L, transactions.execute {
            JdbcDeletionScopeInventory(jdbc).capture(DeletionScope.importSession(session.id), MemberId(1))
        }?.ledgerRevisions)
        assertEquals(0L, transactions.execute {
            JdbcDeletionScopeInventory(jdbc).capture(DeletionScope.rawArtifact(imports.find(session.id, MemberId(1))!!.artifacts.first().id), MemberId(1))
        }?.ledgerRevisions)
        transactions.execute {
            val analyticsReader = JdbcTradingAnalyticsRevisionReader(jdbc)
            val header = assertNotNull(analyticsReader.find(revisionId, 1))
            var records = 0L
            analyticsReader.visitRecords(revisionId) { records++ }
            var references = 0L
            analyticsReader.visitEvidenceReferences(revisionId) { references++ }
            assertEquals(header.canonicalRecordCount, records)
            assertEquals(records, references)
            var supplements = 0L
            analyticsReader.visitEngineInputs(revisionId) { supplements++ }
            assertEquals(if (v2) header.reviewUnitCount else 0L, supplements)
            assertNull(analyticsReader.find(revisionId, 2))
            if (v2) {
                val saved = mutableMapOf<String, ByteArray>()
                val artifacts = object : TradingAnalyticsArtifactStore {
                    override fun put(revisionId: String, role: String, mediaType: String,
                                     write: (java.io.OutputStream) -> Unit): AnalyticsInputArtifact {
                        val out = java.io.ByteArrayOutputStream()
                        write(out)
                        val bytes = out.toByteArray()
                        val hash = java.util.HexFormat.of().formatHex(
                            java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
                        val reference = "$revisionId:$role:$hash"
                        saved[reference] = bytes
                        return AnalyticsInputArtifact(reference, mediaType, bytes.size.toLong(), hash)
                    }
                    override fun open(coreReferenceId: String): java.io.InputStream =
                        java.io.ByteArrayInputStream(saved.getValue(coreReferenceId))
                    override fun purgeRevision(revisionId: String) = true
                }
                val builder = com.refinvest.core.tradingreview.application.TradingAnalyticsInputBuilder(
                    analyticsReader, artifacts, object : TradingMemberProvider {
                        override fun currentMemberId() = MemberId(1)
                    }, mapper)
                val prepared = builder.prepare(revisionId)
                val ledger = mapper.readValue(artifacts.open(
                    prepared.ledgerRevisionContent.coreReferenceId), Map::class.java)
                assertEquals(header.canonicalRecordCount,
                    (ledger["canonicalRecords"] as List<*>).size.toLong())
                assertEquals(header.reviewUnitCount,
                    (ledger["reviewUnitEngineInputs"] as List<*>).size.toLong())
                assertEquals(header.ledgerContentHash, prepared.ledgerContentHash)
                assertEquals(header.manifestHash, prepared.manifestHash)
            }
        }
        assertEquals("PUBLISHED", jdbc.queryForObject(
            "SELECT status FROM trading_normalization_runs WHERE id=?::uuid", String::class.java, runId))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_ack_dispatches", Long::class.java))
        assertFalse(recoveredProcessor.processOne())
        // Simulate Compute accepting an ACK immediately before Core loses its lease/state update.
        val lostWorker = UUID.randomUUID().toString()
        val pending = assertNotNull(transactions.execute { results.claimAck(now.plusSeconds(1),
            java.time.Duration.ofSeconds(45), lostWorker) })
        assertEquals(NormalizationAckOutcome.Accepted, client.acknowledge(pending))
        val ackRecovered: AcknowledgeTradingNormalizationResultUseCase =
            com.refinvest.core.tradingreview.application.TradingNormalizationAcknowledgementService(
            results, client, transactions,
            Clock.fixed(now.plusSeconds(47), ZoneOffset.UTC), mapper,
            object : TradingRetryJitter { override fun factor() = 0.5 })
        assertTrue(ackRecovered.acknowledgeOne())
        assertEquals(2, acknowledgeCalls)
        assertEquals("ACKNOWLEDGED", jdbc.queryForObject(
            "SELECT status FROM trading_normalization_ack_dispatches", String::class.java))
        if (expireRaw) {
            // This execution regression uses the persisted deadline. Terminal policy clocks are a separate 5b-2 change.
            val tables = listOf("trading_ledger_revisions", "trading_records", "trading_source_evidence_snapshots",
                "trading_ledger_analytics_supplement", "trading_books", "trading_normalization_ack_dispatches")
            fun retainedRows() = tables.associateWith { table ->
                jdbc.queryForList("SELECT row_to_json(t)::text FROM $table t ORDER BY 1", String::class.java)
            }
            val retained = retainedRows()
            val expiry = jdbc.queryForObject("SELECT max(raw_expires_at) FROM trading_source_artifacts", java.sql.Timestamp::class.java)!!.toInstant()
            val sweep = com.refinvest.core.tradingreview.application.TradingRawRetentionService(
                JdbcTradingArtifactRetentionAdapter(jdbc), emptyStorage(), transactions, Clock.fixed(expiry, ZoneOffset.UTC))
            assertEquals(2, sweep.purgeExpired().deleted)
            assertEquals(0, sweep.purgeExpired().deleted)
            assertEquals(retained, retainedRows())
            assertEquals(TradingImportStatus.ACCEPTED, imports.find(session.id, MemberId(1))!!.status)
            assertTrue(imports.find(session.id, MemberId(1))!!.artifacts.all {
                it.retentionStatus == TradingArtifactRetentionStatus.DELETED && it.wrappedDek == null && !it.rawReparseAvailable(expiry)
            })
        }
    }

    @Test
    fun terminalDescriptorRetriesExhaustToProcessingFailedWithoutRevision() = withDatabase { connection ->
        val source = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(source)
        val transactions = TransactionTemplate(tradingTestTransactions(source))
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        members(jdbc)
        val review = TradingReviewTestStores(jdbc)
        val account = TradingAccount.create(TradingAccountId(951), MemberId(1), TradingVenue.BINANCE, "A", now)
        review.insert(account)
        val book = TradingBook.create(TradingBookId(952), account, "B",
            TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY,
            TradingSettlementAsset.USDT, "UTC", now)
        review.insert(book)
        val imports = TradingImportTestStores(jdbc)
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
        val run = NormalizationRun.restore(NormalizationRunId(runId), input, NormalizationInputCanonicalizer.hash(identity),
            "b".repeat(64), NormalizationRunStatus.DISPATCH_PENDING, 1,
            UUID.randomUUID().toString(), null, null, now, now, null, now, null, 0,
            inputIdentity = identity, resultDeliveryPolicy = deliveryPolicy)
        val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId, "c".repeat(64),
            NormalizationDispatchStatus.PENDING, 0, now, null, null, null, now, now)
        val runs = TradingNormalizationTestRuntime(jdbc, mapper)
        runs.seed(run, dispatch)
        assertTrue(imports.seedValidation(session.id, runId, now))
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
            runs, imports, review, TradingLedgerTestStore(jdbc, mapper), client,
            transactions, Clock.fixed(now, ZoneOffset.UTC), mapper,
            object : TradingRetryJitter { override fun factor() = 0.5 }, JdbcTradingNormalizationTransitionGate(jdbc) , publications = TradingLedgerTestStore(jdbc, mapper), revisionIds = object : LedgerRevisionIdGenerator {
                override fun revisionId() = com.refinvest.core.tradingreview.domain.valueobject.LedgerRevisionId(UUID.randomUUID())
            })
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
        val tx = TransactionTemplate(tradingTestTransactions(source))
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        members(jdbc)
        val review = TradingReviewTestStores(jdbc)
        val account = TradingAccount.create(TradingAccountId(901), MemberId(1), TradingVenue.BINANCE, "A", now)
        review.insert(account)
        val book = TradingBook.create(TradingBookId(902), account, "B",
            TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY,
            TradingSettlementAsset.USDT, "UTC", now)
        review.insert(book)
        val imports = TradingImportTestStores(jdbc)
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
        val run = NormalizationRun.restore(NormalizationRunId(runId), input, NormalizationInputCanonicalizer.hash(identity),
            "b".repeat(64), NormalizationRunStatus.DISPATCH_PENDING, 1, token, null, null,
            now, now, null, now, null, 0, inputIdentity = identity,
            resultDeliveryPolicy = deliveryPolicy)
        val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId, "c".repeat(64),
            NormalizationDispatchStatus.PENDING, 0, now, null, null, null, now, now)
        val runs = TradingNormalizationTestRuntime(jdbc, mapper)
        runs.seed(run, dispatch)
        assertTrue(imports.seedValidation(session.id, runId, now))
        jdbc.update("UPDATE trading_normalization_runs SET status='RESULT_READY',compute_job_id='job-stale' WHERE id=?::uuid", runId)
        jdbc.update("UPDATE trading_normalization_dispatches SET status='RESULT_READY',attempt_count=10 WHERE id=?::uuid", dispatch.id)
        val current = assertNotNull(runs.findRun(runId))
        val results = TradingLedgerTestStore(jdbc, mapper)
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
        deletionWorker(jdbc, tx, emptyStorage(), Clock.fixed(now.plusSeconds(3), ZoneOffset.UTC)).purgePending()
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_runs", Long::class.java))
        val ack = assertNotNull(tx.execute { results.claimAck(now.plusSeconds(4),
            java.time.Duration.ofSeconds(45), UUID.randomUUID().toString()) })
        assertEquals("DISCARDED_STALE", ack.disposition)
        assertEquals(1L, ack.currentDeletionGeneration)
        assertEquals(session.id.value.toString(), ack.coreImportSessionId)
    }

    @Test
    fun publicationRollsBackOnMissingEvidenceThenCreatesOneRevisionAndAck() = verifyLedgerPublication(false)

    @Test
    fun publicationWaitingForABookLockRejectsALeaseThatExpiresWhileWaiting() = verifyLedgerPublication(true)

    @Test
    fun secondSessionPublishesOnlyItsOwnMembershipAndPreservesTheFirstRevision() = verifyLedgerPublication(false, true)

    private fun verifyLedgerPublication(waitForExpiry: Boolean, secondSession: Boolean = false) = withDatabase { connection ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        val tx = TransactionTemplate(tradingTestTransactions(SingleConnectionDataSource(connection, true)))
        members(jdbc)
        val review = TradingReviewTestStores(jdbc)
        val account = TradingAccount.create(TradingAccountId(991), MemberId(1), TradingVenue.BINANCE, "A", now)
        review.insert(account)
        val book = TradingBook.create(TradingBookId(992), account, "B",
            TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY,
            TradingSettlementAsset.USDT, "UTC", now)
        review.insert(book)
        val imports = TradingImportTestStores(jdbc)
        val session = TradingImportSession.create(TradingImportSessionId(993), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), normalizationVersions, now)
        imports.insert(session)
        val inputs = TradingArtifactRole.entries.mapIndexed { index, role ->
            val id = 994L + index
            imports.seedArtifact(TradingSourceArtifact.restore(TradingSourceArtifactId(id), session.id, role,
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
        val run = NormalizationRun.restore(NormalizationRunId(runId), input, NormalizationInputCanonicalizer.hash(identity),
            "b".repeat(64), NormalizationRunStatus.DISPATCH_PENDING, 1, token, null, null,
            now, now, null, now, null, 0, inputIdentity = identity,
            resultDeliveryPolicy = deliveryPolicy)
        val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId, "c".repeat(64),
            NormalizationDispatchStatus.PENDING, 0, now, null, null, null, now, now)
        val runs = TradingNormalizationTestRuntime(jdbc, mapper)
        runs.seed(run, dispatch)
        assertTrue(imports.seedValidation(session.id, runId, now))
        jdbc.update("UPDATE trading_normalization_runs SET status='RESULT_READY',compute_job_id='job-1' WHERE id=?::uuid", runId)
        jdbc.update("UPDATE trading_normalization_dispatches SET status='RESULT_READY' WHERE id=?::uuid", dispatch.id)
        val current = assertNotNull(runs.findRun(runId))
        val results = TradingLedgerTestStore(jdbc, mapper)
        val worker = UUID.randomUUID().toString()
        val claimed = assertNotNull(tx.execute { results.claimReady(now, java.time.Duration.ofMinutes(15), worker) })
        assertTrue(tx.execute { results.hasReadyLease(claimed.id, worker, now) } == true)
        assertFalse(tx.execute { results.hasReadyLease(claimed.id, worker,
            now.plusSeconds(16 * 60)) } == true)
        val coverageJson = """[{"artifactRole":"POSITION_HISTORY"},{"artifactRole":"TRADE_HISTORY"}]"""
        val counts = mapOf("canonicalRecordCount" to 1L, "sourceEvidenceSnapshotCount" to 1L,
            "reviewUnitCount" to 1L, "positionAllocationCount" to 1L, "artifactCoverageCount" to 2L,
            "excludedReviewUnitCount" to 0L, "capabilityCount" to 0L, "validationIssueCount" to 2L,
            "reconciliationMatchCount" to 0L, "reconciliationIssueCount" to 0L)
        val digests = com.refinvest.core.tradingreview.domain.valueobject.LedgerRevisionResult.COUNT_SECTIONS
            .filterValues { it != null }.map { (count, section) -> mapOf("sectionType" to section,
                "itemCount" to counts.getValue(count), "sectionHash" to "f".repeat(64),
                "hashSpecificationVersion" to NormalizationResultVersionPin.V1.hashSpecificationVersion) } +
            listOf("QUALITY_REPORT", "RECONCILIATION_MANIFEST").map { section -> mapOf(
                "sectionType" to section, "itemCount" to 1L, "sectionHash" to (if (section == "RECONCILIATION_MANIFEST") "d" else "f").repeat(64),
                "hashSpecificationVersion" to NormalizationResultVersionPin.V1.hashSpecificationVersion) }
        val descriptor = NormalizationResultDescriptor("job-1", dispatch.id, runId, "payload-1", 100,
            "a".repeat(64), token, 0, run.inputFingerprint, identity.hashSpecificationVersion,
            identity.targetVersionSet.toWireValue(), "TRADING_NORMALIZATION_RESULT_V1",
            "TRADING_NORMALIZATION_RESULT_HASH_V1", counts,
            digests, "d".repeat(64), "e".repeat(64), now.plusSeconds(3600),
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
        stage("ARTIFACT_COVERAGES", "POSITION_HISTORY", """{"artifactRole":"POSITION_HISTORY"}""")
        stage("ARTIFACT_COVERAGES", "TRADE_HISTORY", """{"artifactRole":"TRADE_HISTORY"}""")
        stage("QUALITY_REPORT", "QUALITY_REPORT", "{}")
        stage("RECONCILIATION_MANIFEST", "RECONCILIATION_MANIFEST", "{}")
        stage("CANONICAL_RECORDS", "record-1",
            """{"recordId":"record-1","sourceEvidenceSnapshotId":"snapshot-1","recordHash":"${"a".repeat(64)}"}""")
        assertFailsWith<NormalizationResultStoreFailure> {
            results.stageItem(runId, NormalizationStagedItem("CANONICAL_RECORDS", "record-1",
                "0".repeat(64), """{"recordId":"record-1","sourceEvidenceSnapshotId":"other"}"""))
        }
        stage("REVIEW_UNITS", "unit-1",
            """{"reviewUnitId":"unit-1","allocations":[{"allocationId":"allocation-1","reviewUnitId":"unit-1","recordId":"record-1"}],"exclusionReasons":[]}""")
        assertTrue(results.hasInvalidEvidence(current))
        stage("VALIDATION_ISSUES", "\uE000", """{"id":"bmp"}""")
        stage("VALIDATION_ISSUES", "\uD800\uDC00", """{"id":"supplementary"}""")
        val ordered = mutableListOf<String>()
        results.visitItems(runId, "VALIDATION_ISSUES", 1) { ordered.add(it.identity) }
        assertEquals(listOf("\uD800\uDC00", "\uE000"), ordered)
        results.markValidated(runId, now)
        assertNull(results.publishedForSession(session.id, MemberId(1)))
        assertFailsWith<Exception> {
            tx.execute { results.publish(current, claimed, worker, descriptor, "{}", coverageJson, "[]", "{}",
                "f".repeat(64), now) }
        }
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
        assertEquals(TradingImportStatus.VALIDATING, imports.find(session.id, MemberId(1))?.status)
        stage("SOURCE_EVIDENCE_SNAPSHOTS", "snapshot-1",
            """{"snapshotId":"snapshot-1","coreArtifactId":"994","artifactRole":"${inputs.first().role.name}","sourceRowNumber":1,"sourceRowHash":"${"a".repeat(64)}","maskingPolicyVersion":"MASK_V1"}""")
        assertFalse(results.hasInvalidEvidence(current))
        assertEquals(1L, results.stagedCounts(runId)["SOURCE_EVIDENCE_SNAPSHOTS"])
        val request = results.request(current, claimed, worker, descriptor, "{}", coverageJson,
            "[]", "{}", "f".repeat(64), now)
        fun snapshot() = listOf(
            jdbc.queryForMap("SELECT status,version,created_ledger_revision_id FROM trading_import_sessions WHERE id=?", session.id.value),
            jdbc.queryForMap("SELECT version,latest_ledger_revision_id FROM trading_books WHERE id=?", book.id.value),
            jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java),
            jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revision_records", Long::class.java),
            jdbc.queryForObject("SELECT count(*) FROM trading_normalization_ack_dispatches", Long::class.java),
            jdbc.queryForMap("SELECT state,validated_at FROM trading_normalization_result_staging WHERE run_id=?::uuid", runId))
        val before = snapshot()
        assertFailsWith<IllegalStateException> { results.publish(request) }
        val altered = results.request(current, claimed, worker, descriptor.copy(resultHash = "0".repeat(64)),
            "{}", coverageJson, "[]", "{}", "f".repeat(64), now)
        val changedMetadata = results.request(current, claimed, worker, descriptor,
            "{}", coverageJson, "[]", """{"unsupported":true}""", "f".repeat(64), now)
        val staleRun = NormalizationRunPersistenceMapper.toDomain(
            NormalizationRunPersistenceMapper.toRecord(current).copy(version = current.version + 1))
        listOf(altered, changedMetadata, request.copy(descriptor = descriptor.copy(payloadId = "different-payload")),
            request.copy(descriptor = descriptor.copy(payloadSha256 = "0".repeat(64))),
            request.copy(lease = request.lease.copy(owner = UUID.randomUUID().toString())),
            request.copy(run = staleRun)).forEach { invalid ->
            assertFalse(tx.execute { results.publish(invalid) } == true)
            assertEquals(before, snapshot())
        }
        assertFalse(tx.execute { results.publisher(now.plusSeconds(900)).publish(request) } == true)
        assertEquals(before, snapshot())
        jdbc.update("UPDATE trading_normalization_transition SET paused=true WHERE singleton")
        assertFalse(tx.execute { results.publish(request) } == true)
        assertEquals(before, snapshot())
        jdbc.update("UPDATE trading_normalization_transition SET paused=false WHERE singleton")
        tx.execute { status ->
            assertTrue(results.publish(request))
            status.setRollbackOnly()
        }
        assertEquals(before, snapshot())
        if (waitForExpiry) {
            val observedAt = java.util.concurrent.atomic.AtomicReference(now)
            val liveClock = object : Clock() {
                override fun instant() = observedAt.get()
                override fun getZone() = ZoneOffset.UTC
                override fun withZone(zone: java.time.ZoneId) = this
            }
            val backend = java.util.concurrent.atomic.AtomicInteger()
            val ready = java.util.concurrent.CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            connection.autoCommit = false
            jdbc.queryForObject("SELECT id FROM trading_books WHERE id=? FOR UPDATE", Long::class.java, book.id.value)
            try {
                val waiting = executor.submit<Boolean> {
                    DriverManager.getConnection(requireNotNull(System.getenv("TRADING_REVIEW_TEST_JDBC_URL")),
                        requireNotNull(System.getenv("TRADING_REVIEW_TEST_DB_USER")),
                        requireNotNull(System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD"))).use { other ->
                        other.schema = connection.schema
                        val source = SingleConnectionDataSource(other, true)
                        val otherJdbc = JdbcTemplate(source)
                        backend.set(otherJdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                        ready.countDown()
                        TransactionTemplate(tradingTestTransactions(source)).execute {
                            JdbcLedgerRevisionPersistenceAdapter(otherJdbc, mapper, liveClock,
                                JdbcTradingNormalizationTransitionGate(otherJdbc),
                                JdbcTradingNormalizationResultStore(otherJdbc, mapper)).publish(request)
                        } == true
                    }
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var blocked = false
                while (System.nanoTime() < deadline) {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()")
                    blocked = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')",
                        Boolean::class.java, backend.get()) == true
                    if (blocked) break
                    Thread.sleep(20)
                }
                assertTrue(blocked, "Publisher must actually wait for the Book lock")
                observedAt.set(now.plusSeconds(900))
                connection.commit()
                assertFalse(waiting.get(10, TimeUnit.SECONDS))
            } finally {
                connection.rollback()
                connection.autoCommit = true
                executor.shutdownNow()
            }
            assertEquals(before, snapshot())
        }
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
                        val publisherStore = TradingLedgerTestStore(JdbcTemplate(publisherSource), mapper)
                        val publisherTx = TransactionTemplate(tradingTestTransactions(publisherSource))
                        publicationGate.await(5, TimeUnit.SECONDS)
                        publisherTx.execute { publisherStore.publish(current, claimed, worker, descriptor,
                            "{}", coverageJson, "[]", "{}", "f".repeat(64), now) } == true
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
        val storedId = com.refinvest.core.tradingreview.domain.valueobject.LedgerRevisionId(UUID.fromString(
            jdbc.queryForObject("SELECT id::text FROM trading_ledger_revisions", String::class.java)))
        val aggregate = assertNotNull(results.publisher(now).find(storedId, MemberId(1)))
        assertEquals(current.id, aggregate.runId)
        assertEquals(1L, aggregate.publication?.revisionNumber)
        assertFalse(aggregate.metadata.manifestBaseJson.contains("unit-1"))
        assertFalse(aggregate.metadata.qualityBaseJson.contains("supplementary"))
        assertNull(results.publisher(now).find(storedId, MemberId(2)))
        assertNull(results.publishedForSession(session.id, MemberId(2)))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
        val publishedJson = jdbc.queryForMap("""SELECT reconciliation_manifest_json::text AS manifest,
            quality_report_json::text AS quality FROM trading_ledger_revisions WHERE run_id=?::uuid""",
            runId)
        val publishedManifest = mapper.readTree(publishedJson.getValue("manifest") as String)
        val publishedQuality = mapper.readTree(publishedJson.getValue("quality") as String)
        assertEquals("unit-1", publishedManifest.path("reviewUnits")[0].path("reviewUnitId").asText())
        assertEquals(2, publishedQuality.path("validationIssues").size())
        assertEquals("supplementary", publishedQuality.path("validationIssues")[0].path("id").asText())
        assertFailsWith<Exception> {
            jdbc.update("UPDATE trading_ledger_revisions SET result_hash=? WHERE run_id=?::uuid",
                "0".repeat(64), runId)
        }
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_ack_dispatches", Long::class.java))
        assertTrue(tx.execute { results.publish(current, claimed, worker, descriptor, "{}", coverageJson, "[]", "{}",
            "f".repeat(64), now) } == true)
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
        val publishedBook = review.find(book.id, book.owner)!!
        val latestRevision = assertNotNull(publishedBook.latestLedgerRevisionId)
        assertEquals(jdbc.queryForObject("SELECT id::text FROM trading_ledger_revisions", String::class.java), latestRevision.value.toString())
        assertFalse(review.update(book.rename("Stale pre-publication snapshot", now)))
        assertTrue(review.update(publishedBook.rename("Renamed after publication", now.plusSeconds(1))))
        val renamedBook = review.find(book.id, book.owner)!!
        assertEquals(latestRevision, renamedBook.latestLedgerRevisionId)
        if (secondSession) {
            val nextSession = TradingImportSession.create(TradingImportSessionId(1001), renamedBook, "UTC", "UTC",
                TradingPositionMode.ONE_WAY, TradingDataPolicy(), normalizationVersions, now)
            imports.insert(nextSession)
            val nextInputs = TradingArtifactRole.entries.mapIndexed { index, role ->
                val id = TradingSourceArtifactId(1002L + index)
                val sha = (if (index == 0) "c" else "d").repeat(64)
                imports.seedArtifact(TradingSourceArtifact.restore(id, nextSession.id, role, null, "text/csv", 20,
                    sha, "AUTO", "objects/${UUID.randomUUID()}", byteArrayOf(1), "test-v1",
                    TradingArtifactRetentionStatus.ACTIVE, now, now.plusSeconds(3600), null, null, 0))
                NormalizationArtifactInput(id, role, sha, 20, "text/csv", "AUTO")
            }
            val nextInput = input.copy(importSessionId = nextSession.id, artifacts = nextInputs)
            val nextIdentity = NormalizationInputIdentity.fromPinnedInput(nextInput,
                mapper.readValue(normalizationVersions, Map::class.java))
            val nextRunId = NormalizationRunId(UUID.randomUUID().toString())
            val nextToken = UUID.randomUUID().toString()
            val nextRun = NormalizationRun.restore(nextRunId, nextInput, NormalizationInputCanonicalizer.hash(nextIdentity),
                "c".repeat(64), NormalizationRunStatus.DISPATCH_PENDING, 1, nextToken, null, null,
                now, now, null, now, null, 0, inputIdentity = nextIdentity, resultDeliveryPolicy = deliveryPolicy)
            val nextDispatch = dispatch.copy(id = UUID.randomUUID().toString(), runId = nextRunId.value, computeKey = "d".repeat(64))
            runs.seed(nextRun, nextDispatch)
            assertTrue(imports.seedValidation(nextSession.id, nextRunId.value, now))
            jdbc.update("UPDATE trading_normalization_runs SET status='RESULT_READY',compute_job_id='job-2' WHERE id=?::uuid", nextRunId.value)
            jdbc.update("UPDATE trading_normalization_dispatches SET status='RESULT_READY' WHERE id=?::uuid", nextDispatch.id)
            val nextCurrent = assertNotNull(runs.findRun(nextRunId))
            val nextLease = assertNotNull(tx.execute { results.claimReady(now, java.time.Duration.ofMinutes(15), worker) })
            val nextCounts = counts.mapValues { (key, _) -> if (key == "artifactCoverageCount") 2L else 0L }
            val nextDigests = digests.map { digest ->
                val section = digest["sectionType"]
                val countKey = com.refinvest.core.tradingreview.domain.valueobject.LedgerRevisionResult.COUNT_SECTIONS
                    .entries.firstOrNull { it.value == section }?.key
                digest + ("itemCount" to (countKey?.let { nextCounts.getValue(it) } ?: 1L))
            }
            val nextDescriptor = descriptor.copy(jobId = "job-2", logicalJobId = nextDispatch.id, runId = nextRunId.value,
                payloadId = "payload-2", attemptToken = nextToken, inputSetHash = nextRun.inputFingerprint,
                counts = nextCounts, sectionDigests = nextDigests)
            results.beginStage(nextCurrent, nextDescriptor, now)
            listOf("QUALITY_REPORT", "RECONCILIATION_MANIFEST").forEach { section ->
                results.stageItem(nextRunId.value, NormalizationStagedItem(section, section, "f".repeat(64), "{}"))
            }
            TradingArtifactRole.entries.forEach { role -> results.stageItem(nextRunId.value,
                NormalizationStagedItem("ARTIFACT_COVERAGES", role.name, "f".repeat(64),
                    """{"artifactRole":"${role.name}"}""")) }
            val firstRecord = LedgerRevisionPersistenceMapper.toRecord(aggregate)
            assertTrue(tx.execute { results.publish(nextCurrent, nextLease, worker, nextDescriptor, "{}", coverageJson,
                "[]", "{}", "f".repeat(64), now.plusSeconds(2)) } == true)
            val secondId = assertNotNull(imports.find(nextSession.id, MemberId(1))?.createdLedgerRevisionId)
            assertNotEquals(storedId, secondId)
            assertEquals(2L, results.publisher(now).find(secondId, MemberId(1))?.publication?.revisionNumber)
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revision_records WHERE revision_id=?::uuid",
                Long::class.java, secondId.value.toString()))
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revision_records WHERE revision_id=?::uuid",
                Long::class.java, storedId.value.toString()))
            assertEquals(firstRecord, LedgerRevisionPersistenceMapper.toRecord(assertNotNull(results.publisher(now).find(storedId, MemberId(1)))))
            assertEquals(secondId, review.find(book.id, book.owner)?.latestLedgerRevisionId)
            assertEquals(storedId, imports.find(session.id, MemberId(1))?.createdLedgerRevisionId)
            assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
            assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_ack_dispatches", Long::class.java))
            return@withDatabase
        }
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
        assertFalse(tx.execute { results.publish(request) } == true)
        assertEquals(latestRevision, review.find(book.id, book.owner)?.latestLedgerRevisionId)
        assertNull(results.publisher(now).find(storedId, MemberId(1)))
        review.insert(TradingDeletion(996, MemberId(1), "TRADING_IMPORT_SESSION", session.id.value,
            "IMPORT_SUBTREE", "REQUESTED", 1, 0, 0, now, now, now, now.plusSeconds(30L * 24 * 3600)))
        deletionWorker(jdbc, tx, emptyStorage(), Clock.fixed(now.plusSeconds(5), ZoneOffset.UTC)).purgePending()
        assertNull(review.find(book.id, book.owner)!!.latestLedgerRevisionId)
        assertFalse(review.update(renamedBook.rename("Stale before purge", now.plusSeconds(7))))
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
    fun normalizationDispatchPersistsSnapshotAndHoldsUncertainExpiredLease() = withDatabase { connection ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        members(jdbc)
        val review = TradingReviewTestStores(jdbc)
        val account = TradingAccount.create(TradingAccountId(901), MemberId(1), TradingVenue.BINANCE, "A", now)
        review.insert(account)
        val book = TradingBook.create(TradingBookId(902), account, "B",
            TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY,
            TradingSettlementAsset.USDT, "UTC", now)
        review.insert(book)
        val session = TradingImportSession.create(TradingImportSessionId(903), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), "{}", now)
        TradingImportTestStores(jdbc).insert(session)
        val store = TradingNormalizationTestRuntime(jdbc, tools.jackson.databind.json.JsonMapper.builder().build())
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
        val run = NormalizationRun.restore(NormalizationRunId(runId), input, NormalizationInputCanonicalizer.hash(identity), "b".repeat(64),
            NormalizationRunStatus.DISPATCH_PENDING, 1, token, null, null,
            now, now, null, now, null, 0, inputIdentity = identity,
            resultDeliveryPolicy = deliveryPolicy)
        val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId, "c".repeat(64),
            NormalizationDispatchStatus.PENDING, 0, now, null, null, null, now, now)
        store.seed(run, dispatch)
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
        assertFailsWith<Exception> { store.seed(NormalizationRunPersistenceMapper.toDomain(NormalizationRunPersistenceMapper.toRecord(run).copy(id = UUID.randomUUID().toString())),
            dispatch.copy(id = UUID.randomUUID().toString(), runId = runId)) }
        val first = store.claimNext(now, java.time.Duration.ofSeconds(45), UUID.randomUUID().toString())
        assertEquals(dispatch.id, first?.id)
        assertEquals(null, store.claimNext(now, java.time.Duration.ofSeconds(45), UUID.randomUUID().toString()))
        val recovered = store.claimNext(now.plusSeconds(46), java.time.Duration.ofSeconds(45),
            UUID.randomUUID().toString())
        assertNull(recovered) // The previous worker may already have reached Compute.
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

        // A grant refresh changes the attempt token while retaining the same Compute job.
        jdbc.update("UPDATE trading_normalization_runs SET status='SUBMITTED',compute_job_id='same-job' WHERE id=?::uuid", runId)
        jdbc.update("UPDATE trading_normalization_attempts SET compute_job_id='same-job' WHERE run_id=?::uuid", runId)
        val refreshAt = pollAt.plusSeconds(2)
        val refreshWorker = UUID.randomUUID().toString()
        assertNotNull(store.claimSubmitted(refreshAt, java.time.Duration.ofSeconds(45), refreshWorker))
        val transactions = TransactionTemplate(tradingTestTransactions(requireNotNull(jdbc.dataSource)))
        val nextToken = UUID.randomUUID().toString()
        assertFalse(transactions.execute { store.advanceAttempt(NormalizationGrantAttemptAdvance(NormalizationRunId(runId), NormalizationDispatchLease(dispatch.id, UUID.randomUUID().toString(), refreshAt), token, nextToken)) })
        assertFalse(transactions.execute { store.advanceAttempt(NormalizationGrantAttemptAdvance(NormalizationRunId(runId), NormalizationDispatchLease(dispatch.id, refreshWorker, refreshAt.plusSeconds(45)), token, nextToken)) })
        assertTrue(transactions.execute { store.advanceAttempt(NormalizationGrantAttemptAdvance(NormalizationRunId(runId), NormalizationDispatchLease(dispatch.id, refreshWorker, refreshAt), token, nextToken)) })
        val refreshed = assertNotNull(store.findRun(runId))
        assertEquals(2, refreshed.currentAttempt)
        assertEquals(nextToken, refreshed.attemptToken)
        assertEquals("same-job", refreshed.computeJobId)
        assertEquals(listOf("same-job", "same-job"), jdbc.queryForList("""SELECT compute_job_id
            FROM trading_normalization_attempts WHERE run_id=?::uuid ORDER BY attempt_number""", String::class.java, runId))
        assertEquals(run.inputFingerprint, refreshed.inputFingerprint)
        assertEquals(run.resultVersionPin, refreshed.resultVersionPin)
        assertFalse(transactions.execute { store.advanceAttempt(NormalizationGrantAttemptAdvance(NormalizationRunId(runId), NormalizationDispatchLease(dispatch.id, refreshWorker, refreshAt), token, UUID.randomUUID().toString())) })
        assertFailsWith<IllegalStateException> { transactions.execute {
            assertTrue(store.advanceAttempt(NormalizationGrantAttemptAdvance(NormalizationRunId(runId), NormalizationDispatchLease(dispatch.id, refreshWorker, refreshAt), nextToken, UUID.randomUUID().toString())))
            error("rollback grant attempt")
        } }
        assertEquals(2, store.findRun(runId)?.currentAttempt)
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_attempts WHERE run_id=?::uuid", Long::class.java, runId))
        assertEquals(1L, jdbc.queryForObject("""SELECT count(*) FROM trading_normalization_audit
            WHERE run_id=?::uuid AND event='GRANT_RETRY_ATTEMPT'""", Long::class.java, runId))
        val newJobToken = UUID.randomUUID().toString()
        val newDispatch = dispatch.copy(id = UUID.randomUUID().toString(), computeKey = "e".repeat(64))
        assertTrue(transactions.execute { store.restartAfterJobFailure(NormalizationJobRetry(NormalizationRunId(runId), NormalizationDispatchLease(dispatch.id, refreshWorker, refreshAt), newDispatch, newJobToken, "TEMPORARY_DEPENDENCY_FAILURE")) })
        assertNull(store.findRun(runId)?.computeJobId)
        assertNull(jdbc.queryForObject("""SELECT compute_job_id FROM trading_normalization_attempts
            WHERE run_id=?::uuid AND attempt_number=3""", String::class.java, runId))
    }

    @Test
    fun validationAdmissionCommitsSessionRunAndDispatchTogether() = withDatabase { connection ->
        val source = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(source)
        val transactions = TransactionTemplate(tradingTestTransactions(source))
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        members(jdbc)
        val review = TradingReviewTestStores(jdbc)
        val imports = TradingImportTestStores(jdbc)
        val runs = TradingNormalizationTestRuntime(jdbc, mapper)
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
        val workerTime = java.util.concurrent.atomic.AtomicReference(now)
        val workerClock = object : Clock() {
            override fun instant() = workerTime.get()
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
        }
        var currentInstrumentTerms = "[]"
        val admissionGuard = com.refinvest.core.tradingreview.application.normalization.NormalizationAdmissionGuard(
            object : TradingMemberProvider { override fun currentMemberId() = MemberId(1) },
            review, review, imports, JdbcTradingNormalizationTransitionGate(jdbc))
        val admissionFactory = com.refinvest.core.tradingreview.application.normalization.NormalizationAdmissionFactory(
            object : com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingVersionPins {
                override fun currentJson() = versions
            }, object : com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingNormalizationSettings {
                override fun retryPolicy() = com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingNormalizationRetryPolicy(
                    "retry-v1", 3, java.time.Duration.ofMinutes(1), java.time.Duration.ofSeconds(1),
                    java.time.Duration.ofSeconds(10), "NONE")
                override fun instrumentTermsJson() = currentInstrumentTerms
                override fun resultDeliveryPolicy() = deliveryPolicy
            }, mapper, normalizationIds)
        val admissionStore = JdbcTradingNormalizationAdmissionAdapter(jdbc, imports, workerClock, JdbcTradingNormalizationTransitionGate(jdbc))
        val validateImport = com.refinvest.core.tradingreview.application.normalization.validate.ValidateTradingImportSessionService(
            admissionGuard, admissionFactory, imports, review, runs, admissionStore, workerClock)
        val retryImport = com.refinvest.core.tradingreview.application.normalization.retry.RetryTradingImportSessionService(
            admissionGuard, admissionFactory, imports, review, admissionStore, workerClock)
        jdbc.execute("SELECT trading_normalization_transition_control('PAUSE',0,'test-validation')")
        for (retry in listOf(false, true)) {
            val failure = assertFailsWith<TradingFailure> {
                transactions.execute {
                    if (retry) retryImport.execute(RetryTradingImportSessionCommand(session.id, "same-public-key"))
                    else validateImport.execute(ValidateTradingImportSessionCommand(session.id, null, "same-public-key"))
                }
            }
            assertEquals("TEMPORARY_DEPENDENCY_FAILURE", failure.code)
            assertEquals(TradingFailureKind.DEPENDENCY_UNAVAILABLE, failure.kind)
        }
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_idempotency", Long::class.java))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_dispatches", Long::class.java))
        assertEquals(session.id, imports.find(session.id, MemberId(1))?.id)
        jdbc.execute("SELECT trading_normalization_transition_control('VERIFY',1,'test-validation-http')")
        jdbc.execute("SELECT trading_normalization_transition_control('RESUME',1,'test-validation-http')")
        assertEquals("MISSING_REQUIRED_ARTIFACT", assertFailsWith<TradingFailure> {
            transactions.execute { validateImport.execute(ValidateTradingImportSessionCommand(session.id, null, "missing-position")) }
        }.code)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_runs", Long::class.java))
        assertEquals(TradingImportStatus.RECEIVED, imports.find(session.id, MemberId(1))?.status)
        for ((index, role) in TradingArtifactRole.entries.withIndex()) {
            imports.seedArtifact(TradingSourceArtifact.restore(TradingSourceArtifactId(914L + index), session.id,
                role, null, "text/csv", 20, (if (index == 0) "a" else "b").repeat(64),
                "AUTO", "objects/${UUID.randomUUID()}", byteArrayOf(1), "test-v1",
                TradingArtifactRetentionStatus.ACTIVE, now, now.plusSeconds(3600), null, null, 0))
        }
        val started = transactions.execute { validateImport.execute(ValidateTradingImportSessionCommand(session.id, null, "same-public-key")) }!!
        assertEquals(TradingImportStatus.VALIDATING, started.status)
        assertNotNull(started.activeNormalizationRunId)
        val replay = transactions.execute { validateImport.execute(ValidateTradingImportSessionCommand(session.id, null, "validate-again")) }!!
        assertEquals(started.activeNormalizationRunId, replay.activeNormalizationRunId)
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_runs", Long::class.java))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_dispatches", Long::class.java))
        assertEquals(started.activeNormalizationRunId, imports.find(session.id, MemberId(1))?.activeNormalizationRunId)
        val active = assertNotNull(runs.findRun(started.activeNormalizationRunId!!.value))
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
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
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
        val retrySchedule = NormalizationRetrySchedule(mapper, object : TradingRetryJitter {
            override fun factor() = 0.5
        })
        val workerLifecycle = NormalizationWorkerLifecycle(runs, imports, review, imports)
        val dispatcher = DispatchTradingNormalizationService(runs, imports, imports, issuer, compute,
            retrySchedule, workerLifecycle, transactions, workerClock, JdbcTradingNormalizationTransitionGate(jdbc))
        assertTrue(dispatcher.dispatchOne())
        assertEquals(TradingImportStatus.VALIDATING, imports.find(session.id, MemberId(1))?.status)
        workerTime.set(now.plusSeconds(1))
        assertTrue(dispatcher.dispatchOne())
        assertEquals(2, submittedKeys.size)
        assertEquals(submittedKeys[0], submittedKeys[1])
        assertTrue(submittedLocators[0] != submittedLocators[1])
        assertFalse(jdbc.queryForObject("SELECT input_json::text FROM trading_normalization_runs WHERE id=?::uuid",
            String::class.java, active.id.value)!!.contains("https://grant.test"))
        assertEquals("job-1", runs.findRun(active.id.value)?.computeJobId)
        val polling = assertNotNull(runs.claimSubmitted(now.plusSeconds(1), java.time.Duration.ofSeconds(45),
            UUID.randomUUID().toString()))
        transactions.execute {
            val locked = assertNotNull(imports.find(session.id, MemberId(1), true))
            assertTrue(runs.markTerminal(polling.id, polling.leaseOwner!!,
                "TEMPORARY_DEPENDENCY_FAILURE", now))
            val failed = assertNotNull(locked.failValidation(active.id.value, active.input.deletionGeneration,
                TradingImportFailure("TEMPORARY_DEPENDENCY_FAILURE", true, "RETRY", now), now))
            assertTrue(imports.update(failed))
        }
        assertEquals(TradingImportStatus.PROCESSING_FAILED, imports.find(session.id, MemberId(1))?.status)
        assertEquals("TEMPORARY_DEPENDENCY_FAILURE", runs.findRun(active.id.value)?.lastFailureCode)
        currentInstrumentTerms = """[{"instrumentId":"CHANGED_CURRENT_CONFIGURATION"}]"""
        val retried = transactions.execute { retryImport.execute(RetryTradingImportSessionCommand(session.id, "retry-once")) }!!
        assertEquals(TradingImportStatus.VALIDATING, retried.status)
        assertEquals(active.input.instrumentTermsJson, imports.find(retried.id, MemberId(1))?.normalizationRun?.input?.instrumentTermsJson)
        assertTrue(retried.activeNormalizationRunId != active.id)
        assertEquals(active.id, runs.findRun(retried.activeNormalizationRunId!!.value)?.retryOfRunId)
        assertEquals(NormalizationResultVersionPin.V1,
            runs.findRun(retried.activeNormalizationRunId!!.value)?.resultVersionPin)
        assertEquals(NormalizationResultVersionPin.V1.schemaVersion,
            jdbc.queryForObject("""SELECT result_schema_version FROM trading_normalization_dispatches
                WHERE run_id=?::uuid""", String::class.java, retried.activeNormalizationRunId!!.value))
        assertEquals(retried.activeNormalizationRunId,
            transactions.execute { retryImport.execute(RetryTradingImportSessionCommand(session.id, "retry-once")) }!!.activeNormalizationRunId)
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
        val exhausting = DispatchTradingNormalizationService(runs, imports, imports, issuer, alwaysRetry,
            retrySchedule, workerLifecycle, transactions, workerClock, JdbcTradingNormalizationTransitionGate(jdbc))
        for (seconds in listOf(1L, 2L, 4L)) {
            workerTime.set(now.plusSeconds(seconds))
            assertTrue(exhausting.dispatchOne())
        }
        val exhausted = assertNotNull(imports.find(session.id, MemberId(1)))
        assertEquals(TradingImportStatus.PROCESSING_FAILED, exhausted.status)
        assertEquals("TEMPORARY_DEPENDENCY_FAILURE", exhausted.failure?.code)
        assertTrue(exhausted.failure?.retryable == true)
        assertEquals(NormalizationRunStatus.TERMINAL_FAILURE,
            runs.findRun(retried.activeNormalizationRunId!!.value)?.status)
        jdbc.update("UPDATE trading_source_artifacts SET raw_expires_at=?, uploaded_at=? WHERE import_session_id=?",
            java.sql.Timestamp.from(now.minusSeconds(1)), java.sql.Timestamp.from(now.minusSeconds(86401)), session.id.value)
        assertEquals("SOURCE_ARTIFACT_EXPIRED", assertFailsWith<TradingFailure> {
            transactions.execute { retryImport.execute(RetryTradingImportSessionCommand(session.id, "retry-expired")) }
        }.code)
        assertEquals(TradingImportStatus.PROCESSING_FAILED, imports.find(session.id, MemberId(1))?.status)
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_dispatches", Long::class.java))
        jdbc.update("UPDATE trading_source_artifacts SET raw_expires_at=? WHERE import_session_id=?",
            java.sql.Timestamp.from(now.plusSeconds(3600)), session.id.value)
        val again = transactions.execute { retryImport.execute(RetryTradingImportSessionCommand(session.id, "retry-after-exhaustion")) }!!
        assertEquals(retried.activeNormalizationRunId, runs.findRun(again.activeNormalizationRunId!!)?.retryOfRunId)
        assertEquals(NormalizationResultVersionPin.V1,
            runs.findRun(again.activeNormalizationRunId!!)?.resultVersionPin)
        val staleDispatch = assertNotNull(runs.claimNext(workerTime.get(), java.time.Duration.ofSeconds(45),
            UUID.randomUUID().toString()))
        val staleRun = assertNotNull(runs.findRun(again.activeNormalizationRunId!!))
        jdbc.update("UPDATE trading_import_sessions SET deletion_generation=deletion_generation+1 WHERE id=?",
            session.id.value)
        assertFalse(runs.markSubmitted(NormalizationSubmissionReceipt(NormalizationRunId(staleRun.id.value), NormalizationDispatchLease(staleDispatch.id, staleDispatch.leaseOwner!!, now), staleRun.input.deletionGeneration, staleRun.attemptToken, "late-job")))
    }

    @Test
    fun `migration and JDBC adapter preserve ownership and optimistic version`() = withDatabase { connection ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val store = TradingReviewTestStores(jdbc)
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
        val store = TradingReviewTestStores(jdbc)
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
        val firstStore = TradingReviewTestStores(JdbcTemplate(SingleConnectionDataSource(first, true)))
        members(JdbcTemplate(SingleConnectionDataSource(first, true)))
        DriverManager.getConnection(System.getenv("TRADING_REVIEW_TEST_JDBC_URL"),
            System.getenv("TRADING_REVIEW_TEST_DB_USER"), System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD")).use { second ->
            second.schema = first.schema
            second.autoCommit = false
            val secondStore = TradingReviewTestStores(JdbcTemplate(SingleConnectionDataSource(second, true)))
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
        val store = TradingReviewTestStores(jdbc)
        val transactions = TransactionTemplate(tradingTestTransactions(dataSource))
        members(jdbc)
        val member = object : TradingMemberProvider {
            var id = MemberId(1)
            override fun currentMemberId() = id
        }
        val ids = object : TradingIdGenerator {
            var next = 1000L
            override fun accountId() = TradingAccountId(next++)
            override fun bookId() = TradingBookId(next++)
            override fun deletionId() = DeletionRequestId(next++)
        }
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val service = TradingAccountBookTestServices(member, ids, store, store, store, store, clock,
            store.accountReader, store.bookReader, store.bookDeletionCascade, JdbcDeletionScopeInventory(jdbc))
        val account = transactions.execute { service.execute(CreateTradingAccountCommand(TradingVenue.BINANCE, "Primary", "create-account")) }
        assertEquals(account.id, transactions.execute {
            service.execute(CreateTradingAccountCommand(TradingVenue.BINANCE, "Primary", "create-account"))
        }.id)
        assertEquals("IDEMPOTENCY_PAYLOAD_CONFLICT", assertFailsWith<TradingFailure> {
            transactions.execute { service.execute(CreateTradingAccountCommand(TradingVenue.BINANCE, "Different", "create-account")) }
        }.code)
        member.id = MemberId(2)
        assertEquals(TradingFailureKind.NOT_FOUND, assertFailsWith<TradingFailure> { service.execute(GetTradingAccountQuery(account.id)) }.kind)
        assertEquals(TradingFailureKind.NOT_FOUND, assertFailsWith<TradingFailure> {
            transactions.execute { service.execute(CreateTradingBookCommand(account.id, "Other", TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "foreign-book")) }
        }.kind)
        member.id = MemberId(1)
        val book = transactions.execute { service.execute(CreateTradingBookCommand(account.id, "Futures", TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "create-book")) }
        assertEquals(book.id, transactions.execute { service.execute(CreateTradingBookCommand(account.id, "Futures", TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "create-book")) }.id)
        assertEquals(TradingFailureKind.INPUT_REJECTED, assertFailsWith<TradingFailure> {
            transactions.execute { service.execute(CreateTradingBookCommand(account.id, "Bad timezone", TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "+09:00", "invalid-timezone")) }
        }.kind)
        val deletion = transactions.execute { service.execute(DeleteTradingAccountCommand(account.id, "delete-account")) }
        assertEquals(1L, deletion.deletionGeneration)
        assertEquals(TradingFailureKind.NO_LONGER_AVAILABLE, assertFailsWith<TradingFailure> { service.execute(GetTradingAccountQuery(account.id)) }.kind)
        assertEquals(TradingFailureKind.NO_LONGER_AVAILABLE, assertFailsWith<TradingFailure> {
            transactions.execute { service.execute(CreateTradingBookCommand(account.id, "Blocked", TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "new-book")) }
        }.kind)
        deletionWorker(jdbc, transactions, emptyStorage(), clock).purgePending()
        assertEquals("COMPLETED", GetTradingDeletionRequestService(member, TradingReviewTestStores(jdbc).deletionReader).execute(GetTradingDeletionRequestQuery(DeletionRequestId(deletion.id))).state)
        assertEquals(TradingFailureKind.NO_LONGER_AVAILABLE, assertFailsWith<TradingFailure> { service.execute(GetTradingBookQuery(book.id)) }.kind)
        member.id = MemberId(2)
        assertEquals(TradingFailureKind.NOT_FOUND, assertFailsWith<TradingFailure> { service.execute(GetTradingBookQuery(book.id)) }.kind)
    }

    @Test
    fun `failed primary purge remains inaccessible and does not report completion`() = withDatabase { connection ->
        val dataSource = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(dataSource)
        val store = TradingReviewTestStores(jdbc)
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
        repeat(5) { attempt ->
            deletionWorker(jdbc, TransactionTemplate(tradingTestTransactions(dataSource)),
                emptyStorage(), Clock.fixed(now.plusSeconds(attempt * 100L), ZoneOffset.UTC)).purgePending()
        }
        val result = store.find(303L, MemberId(1))!!
        assertEquals("FAILED", result.state)
        assertEquals("DELETION_PARTIALLY_COMPLETED", result.failureCode)
        assertNotNull(store.find(book.id, MemberId(1)))
    }

    @Test
    fun importUploadReplayConflictAndDeletionUseRealPostgres(@org.junit.jupiter.api.io.TempDir root: java.nio.file.Path) = withDatabase { connection ->
        val dataSource = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(dataSource)
        val accountStore = TradingReviewTestStores(jdbc)
        val importStore = TradingImportTestStores(jdbc)
        val transactions = TransactionTemplate(tradingTestTransactions(dataSource))
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
            override fun deletionId() = DeletionRequestId(next++)
        }
        val storage = com.refinvest.core.tradingreview.adapter.storage.LocalEncryptedRawArtifactStorage(root,
            javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"), "test-v1")
        val service = TradingImportTestServices(
            object : TradingMemberProvider { override fun currentMemberId() = MemberId(1) }, ids, accountStore, accountStore, importStore, importStore,
            accountStore, accountStore, storage, object : com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingVersionPins {
                override fun currentJson() = "{\"versionSetHash\":\"exact\"}"
            }, transactions, Clock.fixed(now, ZoneOffset.UTC), noPublished, importStore, JdbcDeletionScopeInventory(jdbc))
        val session = transactions.execute { service.execute(CreateTradingImportSessionCommand(book.id, "UTC", "UTC", TradingPositionMode.ONE_WAY, null, null, "create-import")) }
        assertEquals(session.id, transactions.execute { service.execute(CreateTradingImportSessionCommand(book.id, "UTC", "UTC", TradingPositionMode.ONE_WAY, null, null, "create-import")) }.id)
        assertEquals("IDEMPOTENCY_PAYLOAD_CONFLICT", assertFailsWith<TradingFailure> {
            transactions.execute { service.execute(CreateTradingImportSessionCommand(book.id, "Asia/Seoul", "UTC", TradingPositionMode.ONE_WAY, null, null, "create-import")) }
        }.code)
        val tradeBytes = "synthetic,one\n".toByteArray()
        val positionBytes = "synthetic,two\n".toByteArray()
        fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        fun upload(role: TradingArtifactRole, bytes: ByteArray, key: String) = service.execute(UploadTradingImportArtifactCommand.withMetadata(session.id, TradingUploadMetadata(role, "sample.csv", "text/csv", bytes.size.toLong(), sha(bytes), "AUTO"), java.io.ByteArrayInputStream(bytes), key))
        val trade = upload(TradingArtifactRole.TRADE_HISTORY, tradeBytes, "trade-first")
        val tradeObjectKey = importStore.find(trade.artifact.id, MemberId(1))!!.objectKey!!
        assertTrue(trade.created)
        assertEquals(null, importStore.find(trade.artifact.id, MemberId(1))?.filename)
        assertFalse(service.execute(GetTradingImportSessionQuery(session.id)).session.canValidate)
        val replay = upload(TradingArtifactRole.TRADE_HISTORY, tradeBytes, "trade-second")
        assertFalse(replay.created)
        assertEquals(trade.artifact.id, replay.artifact.id)
        assertEquals("ARTIFACT_ROLE_CONFLICT", assertFailsWith<TradingFailure> {
            upload(TradingArtifactRole.TRADE_HISTORY, "other\n".toByteArray(), "trade-conflict")
        }.code)
        val position = upload(TradingArtifactRole.POSITION_HISTORY, positionBytes, "position-first")
        val positionObjectKey = importStore.find(position.artifact.id, MemberId(1))!!.objectKey!!
        assertTrue(position.created)
        assertTrue(service.execute(GetTradingImportSessionQuery(session.id)).session.canValidate)
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM trading_source_artifacts", Long::class.java))
        val rawDelete = transactions.execute { service.execute(DeleteTradingSourceArtifactCommand(position.artifact.id, "delete-raw")) }
        assertEquals(rawDelete.id, transactions.execute { service.execute(DeleteTradingSourceArtifactCommand(position.artifact.id, "delete-raw-again")) }.id)
        deletionWorker(jdbc, transactions, storage, Clock.fixed(now, ZoneOffset.UTC)).purgePending()
        assertEquals("COMPLETED", accountStore.find(rawDelete.id, MemberId(1))?.state)
        assertFalse(storage.exists(positionObjectKey))
        assertFalse(service.execute(GetTradingImportSessionQuery(session.id)).session.canValidate)
        assertEquals(null, jdbc.queryForObject("SELECT wrapped_dek FROM trading_source_artifacts WHERE id=?",
            ByteArray::class.java, position.artifact.id.value))
        val deletion = transactions.execute { service.execute(DeleteTradingImportSessionCommand(session.id, "delete-import")) }
        assertEquals(deletion.id, transactions.execute { service.execute(DeleteTradingImportSessionCommand(session.id, "delete-import-again")) }.id)
        assertEquals(TradingFailureKind.NO_LONGER_AVAILABLE, assertFailsWith<TradingFailure> { service.execute(GetTradingImportSessionQuery(session.id)) }.kind)
        fun assertCreateReplayIsGone() {
            val failure = assertFailsWith<TradingFailure> {
                transactions.execute { service.execute(CreateTradingImportSessionCommand(book.id, "UTC", "UTC", TradingPositionMode.ONE_WAY, null, null, "create-import")) }
            }
            assertEquals(TradingFailureKind.NO_LONGER_AVAILABLE, failure.kind)
            assertEquals("RESOURCE_DELETED", failure.code)
        }
        assertCreateReplayIsGone()
        deletionWorker(jdbc, transactions, storage, Clock.fixed(now, ZoneOffset.UTC)).purgePending()
        assertEquals("COMPLETED", accountStore.find(deletion.id, MemberId(1))?.state)
        assertFalse(storage.exists(tradeObjectKey))
        assertFalse(storage.exists(positionObjectKey))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_source_artifacts", Long::class.java))
        assertCreateReplayIsGone()
    }

    @Test
    fun concurrentUploadsPublishOneArtifactPerRole(@org.junit.jupiter.api.io.TempDir root: java.nio.file.Path) = withDatabase { setup ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(setup, true))
        members(jdbc)
        val baseStore = TradingReviewTestStores(jdbc)
        val account = TradingAccount.create(TradingAccountId(701), MemberId(1), TradingVenue.BINANCE, "A", now)
        baseStore.insert(account)
        val book = TradingBook.create(TradingBookId(702), account, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
        baseStore.insert(book)
        val session = TradingImportSession.create(TradingImportSessionId(703), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), "{}", now)
        TradingImportTestStores(jdbc).insert(session)
        val storage = com.refinvest.core.tradingreview.adapter.storage.LocalEncryptedRawArtifactStorage(root,
            javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"), "test-v1")
        val nextId = java.util.concurrent.atomic.AtomicLong(704)
        fun runPair(firstBytes: ByteArray, secondBytes: ByteArray, role: TradingArtifactRole): List<Result<com.refinvest.core.tradingreview.port.inbound.importsession.upload.UploadArtifactResult>> {
            val start = java.util.concurrent.CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val inputs = listOf(firstBytes, secondBytes)
                val futures = inputs.mapIndexed { index, bytes -> executor.submit<Result<com.refinvest.core.tradingreview.port.inbound.importsession.upload.UploadArtifactResult>> {
                    DriverManager.getConnection(System.getenv("TRADING_REVIEW_TEST_JDBC_URL"),
                        System.getenv("TRADING_REVIEW_TEST_DB_USER"), System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD")).use { connection ->
                        connection.schema = setup.schema
                        val dataSource = SingleConnectionDataSource(connection, true)
                        val workerJdbc = JdbcTemplate(dataSource)
                        val reviewStore = TradingReviewTestStores(workerJdbc)
                        val importStore = TradingImportTestStores(workerJdbc)
                        val ids = object : TradingIdGenerator {
                            override fun accountId() = TradingAccountId(nextId.getAndIncrement())
                            override fun bookId() = TradingBookId(nextId.getAndIncrement())
                            override fun sourceArtifactId() = TradingSourceArtifactId(nextId.getAndIncrement())
                            override fun deletionId() = DeletionRequestId(nextId.getAndIncrement())
                        }
                        val service = TradingImportTestServices(
                            object : TradingMemberProvider { override fun currentMemberId() = MemberId(1) },
                            ids, reviewStore, reviewStore, importStore, importStore, reviewStore, reviewStore,
                            storage, object : com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingVersionPins {
                                override fun currentJson() = "{}"
                            }, TransactionTemplate(tradingTestTransactions(dataSource)),
                            Clock.fixed(now, ZoneOffset.UTC), noPublished, importStore, JdbcDeletionScopeInventory(workerJdbc))
                        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                            .joinToString("") { "%02x".format(it) }
                        start.await(10, TimeUnit.SECONDS)
                        runCatching { service.execute(UploadTradingImportArtifactCommand.withMetadata(session.id, TradingUploadMetadata(role, "synthetic.csv", "text/csv", bytes.size.toLong(), hash, "AUTO"), java.io.ByteArrayInputStream(bytes), "race-$role-$index")) }
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
        val reviewStore = TradingReviewTestStores(jdbc)
        val importStore = TradingImportTestStores(jdbc)
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
        fun service(connection: Connection): TradingImportTestServices {
            val source = SingleConnectionDataSource(connection, true)
            val connectionJdbc = JdbcTemplate(source)
            val reviews = TradingReviewTestStores(connectionJdbc)
            val imports = TradingImportTestStores(connectionJdbc)
            val ids = object : TradingIdGenerator {
                override fun accountId() = TradingAccountId(nextId.getAndIncrement())
                override fun bookId() = TradingBookId(nextId.getAndIncrement())
                override fun sourceArtifactId() = TradingSourceArtifactId(nextId.getAndIncrement())
                override fun deletionId() = DeletionRequestId(nextId.getAndIncrement())
            }
            return TradingImportTestServices(
                object : TradingMemberProvider { override fun currentMemberId() = MemberId(1) }, ids,
                reviews, reviews, imports, imports, reviews, reviews, storage,
                object : com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingVersionPins { override fun currentJson() = "{}" },
                TransactionTemplate(tradingTestTransactions(source)), Clock.fixed(now, ZoneOffset.UTC),
                noPublished, imports, JdbcDeletionScopeInventory(connectionJdbc))
        }
        val reading = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val raw = "synthetic\n".toByteArray()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val upload = executor.submit<Result<com.refinvest.core.tradingreview.port.inbound.importsession.upload.UploadArtifactResult>> {
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
                    runCatching { service(connection).execute(UploadTradingImportArtifactCommand.withMetadata(session.id, TradingUploadMetadata(TradingArtifactRole.TRADE_HISTORY, "synthetic.csv", "text/csv", raw.size.toLong(), hash, "AUTO"), stream, "upload-race")) }
                }
            }
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            val deletion = TransactionTemplate(tradingTestTransactions(dataSource)).execute {
                service(setup).execute(DeleteTradingImportSessionCommand(session.id, "delete-race"))
            }
            assertNotNull(deletion)
            resume.countDown()
            val outcome = upload.get(20, TimeUnit.SECONDS)
            assertTrue(outcome.isFailure)
            assertEquals("RESOURCE_STATE_CONFLICT", (outcome.exceptionOrNull() as TradingFailure).code)
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_source_artifacts", Long::class.java))
            java.nio.file.Files.list(root.resolve("staging")).use { assertEquals(0L, it.count()) }
            java.nio.file.Files.walk(root.resolve("objects")).use { assertEquals(0L, it.filter(java.nio.file.Files::isRegularFile).count()) }
            deletionWorker(jdbc, TransactionTemplate(tradingTestTransactions(dataSource)), storage,
                Clock.fixed(now, ZoneOffset.UTC)).purgePending()
            assertEquals("COMPLETED", reviewStore.find(deletion.id, MemberId(1))?.state)
        } finally { resume.countDown(); executor.shutdownNow() }
    }

    @Test
    fun parentDeletionWinsAgainstSessionCreation() = withDatabase { setup ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(setup, true))
        members(jdbc)
        val store = TradingReviewTestStores(jdbc)
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
                val creation = executor.submit<Result<TradingImportSessionResult>> {
                    DriverManager.getConnection(System.getenv("TRADING_REVIEW_TEST_JDBC_URL"),
                        System.getenv("TRADING_REVIEW_TEST_DB_USER"), System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD")).use { connection ->
                        connection.schema = setup.schema
                        val dataSource = SingleConnectionDataSource(connection, true)
                        val workerJdbc = JdbcTemplate(dataSource)
                        val reviews = TradingReviewTestStores(workerJdbc)
                        val imports = TradingImportTestStores(workerJdbc)
                        val ids = object : TradingIdGenerator {
                            override fun accountId() = TradingAccountId(9999)
                            override fun bookId() = TradingBookId(9999)
                            override fun importSessionId() = TradingImportSessionId(903 + index * 10L)
                            override fun deletionId() = DeletionRequestId(9999L)
                        }
                        val storage = emptyStorage()
                        val service = TradingImportTestServices(
                            object : TradingMemberProvider { override fun currentMemberId() = MemberId(1) },
                            ids, reviews, reviews, imports, imports, reviews, reviews, storage,
                            object : com.refinvest.core.tradingreview.port.outbound.normalization.policy.TradingVersionPins {
                                override fun currentJson() = "{}"
                            }, TransactionTemplate(tradingTestTransactions(dataSource)),
                            Clock.fixed(now, ZoneOffset.UTC), noPublished, imports, JdbcDeletionScopeInventory(workerJdbc))
                        started.countDown()
                        runCatching { TransactionTemplate(tradingTestTransactions(dataSource)).execute {
                            service.execute(CreateTradingImportSessionCommand(bookId, "UTC", "UTC", TradingPositionMode.ONE_WAY, null, null, "create-$target"))
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

    @Test
    fun accountDeletionRevokesExistingArtifactGrantsBeforePurge() = verifyParentGrantRevocation(deleteAccount = true)

    @Test
    fun bookDeletionRevokesExistingArtifactGrantsBeforePurge() = verifyParentGrantRevocation(deleteAccount = false)

    @Test
    fun grantIssuanceRechecksExpiryAndParentDeletionAfterStorageAvailability() = withDatabase { connection ->
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        members(jdbc)
        val observed = java.util.concurrent.atomic.AtomicReference(now)
        val clock = object : Clock() {
            override fun instant() = observed.get()
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
        }
        for ((index, race) in listOf("expiry", "parent-deletion").withIndex()) {
            observed.set(now)
            val fixture = grantSource(jdbc, 4101 + index * 10L, MemberId(1))
            val storage = object : RawArtifactStorage by emptyStorage() {
                override fun exists(key: String): Boolean {
                    assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                    if (race == "expiry") observed.set(jdbc.queryForObject(
                        "SELECT raw_expires_at FROM trading_source_artifacts WHERE id=?", java.sql.Timestamp::class.java,
                        fixture.artifact.artifactId.value)!!.toInstant())
                    else jdbc.update("UPDATE trading_accounts SET status='DELETION_PENDING',deletion_generation=deletion_generation+1 WHERE id=?",
                        fixture.account.id.value)
                    return true
                }
            }
            val grants = SignedArtifactAccessGrantIssuer(JdbcArtifactGrantIssueStore(jdbc),
                HmacArtifactGrantTokens("https://core.test", "synthetic-internal-key"), storage, clock)
            val failure = assertFailsWith<TradingFailure>(race) {
                grants.issueReadGrant(fixture.artifact, fixture.dispatchId, fixture.sessionId, fixture.attemptToken, 0)
            }
            assertEquals("SOURCE_ARTIFACT_EXPIRED", failure.code, race)
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_artifact_read_grants", Long::class.java), race)
        }
    }

    @Test
    fun grantConsumptionCommitsBeforeStorageEvenWhenTheCallerRollsBack() = withDatabase { connection ->
        val setup = JdbcTemplate(SingleConnectionDataSource(connection, true))
        members(setup)
        val fixture = grantSource(setup, 4201, MemberId(1))
        val schema = setup.queryForObject("SELECT current_schema()", String::class.java)!!
        val dataSource = transitionSource(schema)
        val jdbc = JdbcTemplate(dataSource)
        val manager = DataSourceTransactionManager(dataSource)
        val transactions = TransactionTemplate(manager)
        val clock = Clock.fixed(now, ZoneOffset.UTC)
        val tokens = HmacArtifactGrantTokens("https://core.test", "synthetic-internal-key")
        val openFailure = IllegalStateException("synthetic storage open failure")
        var opened = 0
        val storage = object : RawArtifactStorage by emptyStorage() {
            override fun exists(key: String): Boolean {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                return true
            }
            override fun open(key: String, wrappedDek: ByteArray, keyVersion: String,
                              artifactId: TradingSourceArtifactId): java.io.InputStream {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                opened++
                // This independent connection must see committed consumption before any external read starts.
                assertEquals(1L, jdbc.queryForObject(
                    "SELECT count(*) FROM trading_artifact_read_grants WHERE consumed_at IS NOT NULL", Long::class.java))
                throw openFailure
            }
        }
        val advice = org.springframework.transaction.interceptor.TransactionInterceptor().apply {
            transactionManager = manager
            transactionAttributeSource = org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()
            afterPropertiesSet()
        }
        val issuer = org.springframework.aop.framework.ProxyFactory(SignedArtifactAccessGrantIssuer(
            JdbcArtifactGrantIssueStore(jdbc), tokens, storage, clock)).apply { addAdvice(advice) }
            .getProxy() as ArtifactAccessGrantIssuer
        val redeemer = org.springframework.aop.framework.ProxyFactory(RedeemTradingArtifactGrantService(
            tokens, JdbcArtifactGrantRedemptionStore(jdbc), storage, clock)).apply { addAdvice(advice) }
            .getProxy() as com.refinvest.core.tradingreview.port.inbound.artifactgrant.redeem.RedeemTradingArtifactGrantUseCase
        var token = ""
        val failure = assertFailsWith<IllegalStateException> {
            transactions.execute {
                assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                token = issuer.issueReadGrant(fixture.artifact, fixture.dispatchId, fixture.sessionId,
                    fixture.attemptToken, 0).accessUri.substringAfterLast('/')
                try { redeemer.execute(RedeemTradingArtifactGrantCommand(token)) }
                finally {
                    assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                }
            }
        }
        assertSame(openFailure, failure)
        assertEquals(1, opened)
        assertEquals(1L, setup.queryForObject(
            "SELECT count(*) FROM trading_artifact_read_grants WHERE consumed_at IS NOT NULL", Long::class.java))
        assertEquals("RESOURCE_NOT_FOUND", assertFailsWith<TradingFailure> {
            redeemer.execute(RedeemTradingArtifactGrantCommand(token))
        }.code)
        assertEquals(1, opened)
    }

    private fun verifyParentGrantRevocation(deleteAccount: Boolean) = withDatabase { connection ->
        val source = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(source)
        val tx = TransactionTemplate(tradingTestTransactions(source))
        members(jdbc)
        val review = TradingReviewTestStores(jdbc)
        val imports = TradingImportTestStores(jdbc)
        var opened = 0
        val storage = object : RawArtifactStorage by emptyStorage() {
            override fun exists(key: String) = true
            override fun open(key: String, wrappedDek: ByteArray, keyVersion: String,
                              artifactId: TradingSourceArtifactId): java.io.InputStream {
                opened++
                return "synthetic raw".byteInputStream()
            }
        }
        val tokens = HmacArtifactGrantTokens("https://core.test", "synthetic-internal-key")
        val grants = SignedArtifactAccessGrantIssuer(JdbcArtifactGrantIssueStore(jdbc), tokens, storage,
            Clock.fixed(now, ZoneOffset.UTC))
        val redemption = RedeemTradingArtifactGrantService(tokens, JdbcArtifactGrantRedemptionStore(jdbc), storage,
            Clock.fixed(now, ZoneOffset.UTC))
        val target = grantSource(jdbc, 3001, MemberId(1))
        val sibling = grantSource(jdbc, 3011, MemberId(1), target.account)
        val unrelated = grantSource(jdbc, 3021, MemberId(1))
        val otherOwner = grantSource(jdbc, 3031, MemberId(2))
        val fixtures = listOf(target, sibling, unrelated, otherOwner)
        fun issue(fixture: GrantSource) = grants.issueReadGrant(fixture.artifact,
            fixture.dispatchId, fixture.sessionId, fixture.attemptToken, 0)
        val issued = fixtures.associateWith { issue(it).accessUri.substringAfterLast('/') }
        val ids = object : TradingIdGenerator {
            override fun accountId() = error("Unused")
            override fun bookId() = error("Unused")
            override fun deletionId() = DeletionRequestId(3900L)
        }
        val service = TradingAccountBookTestServices(object : TradingMemberProvider {
            override fun currentMemberId() = MemberId(1)
        }, ids, review, review, review, review, Clock.fixed(now, ZoneOffset.UTC),
            review.accountReader, review.bookReader, review.bookDeletionCascade, JdbcDeletionScopeInventory(jdbc))
        val deletion = assertNotNull(tx.execute {
            if (deleteAccount) service.execute(DeleteTradingAccountCommand(target.account.id, "delete-account"))
            else service.execute(DeleteTradingBookCommand(target.bookId, "delete-book"))
        })
        assertEquals("REQUESTED", deletion.state)
        val revoked = if (deleteAccount) listOf(target, sibling) else listOf(target)
        for (fixture in revoked) {
            // The deletion worker has not run: raw metadata and Session are still readable in storage.
            assertEquals(TradingArtifactRetentionStatus.ACTIVE,
                imports.find(fixture.artifact.artifactId, MemberId(1))?.retentionStatus)
            assertEquals(TradingImportStatus.VALIDATING,
                imports.find(fixture.sessionId, MemberId(1))?.status)
            val failure = assertFailsWith<TradingFailure> { redemption.execute(RedeemTradingArtifactGrantCommand(issued.getValue(fixture))) }
            assertEquals("RESOURCE_NOT_FOUND", failure.code)
            assertEquals(TradingFailureKind.NOT_FOUND, failure.kind)
            val issueFailure = assertFailsWith<TradingFailure> { issue(fixture) }
            assertEquals("SOURCE_ARTIFACT_EXPIRED", issueFailure.code)
        }
        assertEquals(0, opened)
        assertEquals(4L, jdbc.queryForObject("SELECT count(*) FROM trading_artifact_read_grants", Long::class.java))
        assertEquals(0L, jdbc.queryForObject(
            "SELECT count(*) FROM trading_artifact_read_grants WHERE consumed_at IS NOT NULL", Long::class.java))
        for (fixture in fixtures - revoked.toSet()) {
            assertEquals("synthetic raw", redemption.execute(RedeemTradingArtifactGrantCommand(issued.getValue(fixture))).input.bufferedReader().use { it.readText() })
            assertEquals("RESOURCE_NOT_FOUND", assertFailsWith<TradingFailure> {
                redemption.execute(RedeemTradingArtifactGrantCommand(issued.getValue(fixture)))
            }.code)
        }
        assertEquals(fixtures.size - revoked.size, opened)
    }

    private data class GrantSource(val account: TradingAccount, val bookId: TradingBookId,
        val sessionId: TradingImportSessionId, val artifact: NormalizationArtifactInput,
        val dispatchId: String, val attemptToken: String)

    private fun grantSource(jdbc: JdbcTemplate, baseId: Long, owner: MemberId,
                            existingAccount: TradingAccount? = null): GrantSource {
        val review = TradingReviewTestStores(jdbc)
        val imports = TradingImportTestStores(jdbc)
        val account = existingAccount ?: TradingAccount.create(TradingAccountId(baseId), owner,
            TradingVenue.BINANCE, "Synthetic", now).also(review::insert)
        val book = TradingBook.create(TradingBookId(baseId + 1), account, "Synthetic",
            TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY,
            TradingSettlementAsset.USDT, "UTC", now).also(review::insert)
        val session = TradingImportSession.create(TradingImportSessionId(baseId + 2), book,
            "UTC", "UTC", TradingPositionMode.ONE_WAY, TradingDataPolicy(), normalizationVersions, now)
        imports.insert(session)
        val artifact = TradingSourceArtifact.restore(TradingSourceArtifactId(baseId + 3), session.id,
            TradingArtifactRole.TRADE_HISTORY, null, "text/csv", 13, "a".repeat(64), "AUTO",
            "objects/synthetic-$baseId", byteArrayOf(1), "test-key", TradingArtifactRetentionStatus.ACTIVE,
            now, now.plusSeconds(86400), null, null, 0)
        imports.seedArtifact(artifact)
        val pinned = NormalizationArtifactInput(artifact.id, artifact.role, artifact.sha256,
            artifact.byteSize, artifact.contentType, artifact.dialectCandidate)
        val runId = UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        val input = NormalizationInput(book.id, session.id, owner.value, listOf(pinned), "BINANCE",
            "LINEAR_PERPETUAL", "ONE_WAY", "USDT", "UTC", "UTC", normalizationVersions, "[]",
            "TRADING_DATA_POLICY_V1", "retry-v1", "{}", 0)
        val run = NormalizationRun.restore(NormalizationRunId(runId), input, "a".repeat(64), "synthetic-$baseId",
            NormalizationRunStatus.DISPATCH_PENDING, 1, token, null, null, now, now, null, now, null, 0,
            resultDeliveryPolicy = deliveryPolicy)
        val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId,
            baseId.toString().padStart(64, '0'), NormalizationDispatchStatus.PENDING,
            0, now, null, null, null, now, now)
        TradingNormalizationTestRuntime(jdbc, tools.jackson.databind.json.JsonMapper.builder().build()).seed(run, dispatch)
        assertTrue(imports.seedValidation(session.id, runId, now))
        return GrantSource(account, book.id, session.id, pinned, dispatch.id, token)
    }

    private fun emptyStorage() = object : com.refinvest.core.tradingreview.port.outbound.storage.raw.RawArtifactStorage {
        override fun preparePublication(staged: com.refinvest.core.tradingreview.port.outbound.storage.raw.StagedRawArtifact, role: TradingArtifactRole) = error("not used")
        override fun purgeCopies(scope: com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingArtifactScope) = true
        override fun putStaging(source: java.io.InputStream, maxBytes: Long, artifactId: TradingSourceArtifactId, sessionId: TradingImportSessionId):
            com.refinvest.core.tradingreview.port.outbound.storage.raw.StagedRawArtifact = error("Unused")
        override fun promote(staged: com.refinvest.core.tradingreview.port.outbound.storage.raw.StagedRawArtifact, finalKey: String) = Unit
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
        val transactions = TransactionTemplate(tradingTestTransactions(source))
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        members(jdbc)
        val review = TradingReviewTestStores(jdbc)
        val imports = TradingImportTestStores(jdbc)
        val runs = TradingNormalizationTestRuntime(jdbc, mapper)
        val account = TradingAccount.create(TradingAccountId(2001), MemberId(1), TradingVenue.BINANCE, "A", now)
        review.insert(account)
        val book = TradingBook.create(TradingBookId(2002), account, "B",
            TradingProductFamily.LINEAR_PERPETUAL, TradingPositionMode.ONE_WAY,
            TradingSettlementAsset.USDT, "UTC", now)
        review.insert(book)
        val codes = listOf("FILE_FORMAT_INVALID", "REQUIRED_COLUMN_MISSING",
            "UNSUPPORTED_SOURCE_SCHEMA", "UNSUPPORTED_PRODUCT", "UNSUPPORTED_POSITION_MODE",
            "RECORD_CONFLICT", "RESULT_HASH_MISMATCH", "RESULT_COUNT_MISMATCH",
            "RECONCILIATION_MANIFEST_HASH_MISMATCH", "EVIDENCE_REFERENCE_INVALID")
        for ((index, code) in codes.withIndex()) {
            val session = TradingImportSession.create(TradingImportSessionId(2010L + index), book,
                "UTC", "UTC", TradingPositionMode.ONE_WAY, TradingDataPolicy(), normalizationVersions, now)
            imports.insert(session)
            val artifactInputs = TradingArtifactRole.entries.mapIndexed { roleIndex, role ->
                val artifactId = TradingSourceArtifactId(2100L + index * 2 + roleIndex)
                val sha = (if (roleIndex == 0) "a" else "b").repeat(64)
                imports.seedArtifact(TradingSourceArtifact.restore(artifactId, session.id, role, null, "text/csv",
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
            val run = NormalizationRun.restore(NormalizationRunId(runId), input, NormalizationInputCanonicalizer.hash(identity),
                UUID.randomUUID().toString().replace("-", "").repeat(2), NormalizationRunStatus.SUBMITTED,
                1, attemptToken, jobId, null, now, now, now, now, null, 0,
                inputIdentity = identity, resultDeliveryPolicy = deliveryPolicy)
            val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId,
                index.toString(16).padStart(64, 'c'), NormalizationDispatchStatus.SUBMITTED, 0, now,
                null, null, null, now, now)
            runs.seed(run, dispatch)
            assertTrue(imports.seedValidation(session.id, runId, now))
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
            val dispatcher = PollTradingNormalizationService(runs, imports, object : ArtifactAccessGrantIssuer {
                override fun issueReadGrant(artifact: NormalizationArtifactInput, logicalJobId: String,
                                            sessionId: TradingImportSessionId, attemptToken: String,
                                            deletionGeneration: Long): ArtifactReadGrant = error("poll only")
            }, compute, NormalizationRetrySchedule(mapper, object : TradingRetryJitter {
                override fun factor() = 0.5
            }), NormalizationWorkerLifecycle(runs, imports, review, imports), transactions,
                Clock.fixed(now, ZoneOffset.UTC), mapper, normalizationIds)
            assertTrue(dispatcher.pollOne(), code)
            val terminal = assertNotNull(imports.find(session.id, MemberId(1)))
            val expected = if (index < 6) TradingImportStatus.REJECTED else TradingImportStatus.PROCESSING_FAILED
            assertEquals(expected, terminal.status, code)
            if (expected == TradingImportStatus.REJECTED) {
                assertEquals(now, terminal.rejectedAt)
                assertTrue(terminal.artifacts.all { it.rawExpiresAt == now.plusSeconds(86400) })
            }
            assertEquals(code, terminal.failure?.code, code)
            assertEquals(NormalizationRunStatus.TERMINAL_FAILURE, runs.findRun(runId)?.status, code)
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
            assertNull(jdbc.queryForObject("SELECT latest_ledger_revision_id FROM trading_books WHERE id=?",
                String::class.java, book.id.value))
            assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_ack_dispatches",
                Long::class.java))
        }
    }

    @Test
    fun transitionFenceWaitsForAdmissionCommitAndSurvivesNewInstances() = withDatabase { connection ->
        val source = transitionSource(connection.schema)
        val jdbc = JdbcTemplate(source)
        val gate = JdbcTradingNormalizationTransitionGate(jdbc)
        val tx = TransactionTemplate(tradingTestTransactions(source))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val admission = pool.submit {
                tx.execute {
                    gate.requireAdmission()
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val pause = pool.submit<Long> {
                jdbc.queryForObject("SELECT trading_normalization_transition_control('PAUSE',0,'test-admission')", Long::class.java)!!
            }
            assertFailsWith<java.util.concurrent.TimeoutException> { pause.get(200, TimeUnit.MILLISECONDS) }
            release.countDown()
            admission.get(10, TimeUnit.SECONDS)
            assertEquals(1L, pause.get(10, TimeUnit.SECONDS))
            repeat(2) {
                val restarted = JdbcTradingNormalizationTransitionGate(JdbcTemplate(transitionSource(connection.schema)))
                val failure = assertFailsWith<TradingFailure> { tx.execute { restarted.requireAdmission() } }
                assertEquals("TEMPORARY_DEPENDENCY_FAILURE", failure.code)
                assertEquals(TradingFailureKind.DEPENDENCY_UNAVAILABLE, failure.kind)
                assertNull(restarted.withCreatePermit { error("No HTTP send while paused") })
            }
            assertFailsWith<org.springframework.dao.DataAccessException> {
                jdbc.execute("SELECT trading_normalization_transition_control('RESUME',1,'unverified')")
            }
            jdbc.execute("SELECT trading_normalization_transition_control('VERIFY',1,'verified-http-evidence')")
            jdbc.execute("SELECT trading_normalization_transition_control('RESUME',1,'verified-http-evidence')")
            tx.execute { gate.requireAdmission() }
            assertEquals(3L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_transition_audit", Long::class.java))
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test
    fun transitionFenceWaitsForClaimSendAndReceiptAcrossWorkers() = withDatabase { connection ->
        val jdbc = JdbcTemplate(transitionSource(connection.schema))
        val gate = JdbcTradingNormalizationTransitionGate(jdbc)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val send = pool.submit<Boolean> {
                gate.withCreatePermit {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    // PAUSE is already queued. Claim uses another connection and must not
                    // request another advisory lock behind that exclusive waiter.
                    val store = TradingNormalizationTestRuntime(jdbc, tools.jackson.databind.json.JsonMapper.builder().build())
                    assertNull(store.claimNext(now, java.time.Duration.ofSeconds(45), UUID.randomUUID().toString()))
                    true
                } ?: false
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val pause = pool.submit<Long> {
                jdbc.queryForObject("SELECT trading_normalization_transition_control('PAUSE',0,'test-send')", Long::class.java)!!
            }
            assertFailsWith<java.util.concurrent.TimeoutException> { pause.get(200, TimeUnit.MILLISECONDS) }
            release.countDown()
            assertTrue(send.get(10, TimeUnit.SECONDS))
            assertEquals(1L, pause.get(10, TimeUnit.SECONDS))
            val worker2 = JdbcTradingNormalizationTransitionGate(JdbcTemplate(transitionSource(connection.schema)))
            assertNull(worker2.withCreatePermit { error("Worker 2 must be stopped") })
            assertNull(gate.withCreatePermit { error("Worker 1 must be stopped") })
        } finally { release.countDown(); pool.shutdownNow() }
    }

    private fun transitionSource(schema: String): org.springframework.jdbc.datasource.DriverManagerDataSource =
        org.springframework.jdbc.datasource.DriverManagerDataSource().apply {
            setUrl(requireNotNull(System.getenv("TRADING_REVIEW_TEST_JDBC_URL")))
            setUsername(requireNotNull(System.getenv("TRADING_REVIEW_TEST_DB_USER")))
            setPassword(requireNotNull(System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD")))
            setConnectionProperties(java.util.Properties().apply { setProperty("currentSchema", schema) })
        }

    private fun members(jdbc: JdbcTemplate) {
        jdbc.update("INSERT INTO members(id, role, created_at) VALUES (1, 'USER', CURRENT_TIMESTAMP)")
        jdbc.update("INSERT INTO members(id, role, created_at) VALUES (2, 'USER', CURRENT_TIMESTAMP)")
    }

    private fun deletionWorker(jdbc: JdbcTemplate, tx: TransactionTemplate, storage: RawArtifactStorage,
                               clock: Clock, analytics: TradingAnalyticsArtifactStore? = null,
                               runtimeClient: TradingRuntimeDeletionClient? = null): TradingDeletionWorker {
        val workspaces = object : com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingArtifactWorkspaces {
            override fun acquire(scope: com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingArtifactScope) = error("not used")
            override fun purge(scope: com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingArtifactScope) = true
            override fun cleanupOrphans(olderThan: Instant, limit: Int) = 0
        }
        val files = analytics ?: object : TradingAnalyticsArtifactStore {
            override fun put(revisionId: String, role: String, mediaType: String, write: (java.io.OutputStream) -> Unit) = error("not used")
            override fun open(coreReferenceId: String) = error("not used")
            override fun purgeRevision(revisionId: String) = true
        }
        val runtime = object : com.refinvest.core.tradingreview.port.outbound.compute.deletion.TradingRuntimeDeletionClient {
            override fun purge(command: com.refinvest.core.tradingreview.port.outbound.compute.deletion.TradingRuntimeDeletion): TradingRuntimeDeletionOutcome {
                check(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                return TradingRuntimeDeletionOutcome.Absent(clock.instant())
            }
        }
        val legacy = object : com.refinvest.core.tradingreview.port.outbound.storage.workspace.LegacyTradingTemporaryCopies {
            override fun absent() = true
        }
        return TradingDeletionWorker(com.refinvest.core.tradingreview.application.deletion.purge.PurgeTradingDeletionService(
            JdbcTradingDeletionWorkStore(jdbc, clock), tx,
            com.refinvest.core.tradingreview.application.deletion.purge.DeletionExternalPurger(
                storage, files, workspaces, runtimeClient ?: runtime, legacy), clock))
    }

    private fun withDatabase(applyPinMigration: Boolean = true, applyDeletionMigration: Boolean = true,
                             applyRetentionMigration: Boolean = true, applyCountMigration: Boolean = true, test: (Connection) -> Unit) {
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
                val migration7 = javaClass.getResourceAsStream(
                    "/db/migration/V7__trading_result_stage_order.sql")!!
                    .bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    splitSqlStatements(migration7).forEach(statement::execute)
                }
                val migration8 = javaClass.getResourceAsStream(
                    "/db/migration/V8__trading_analytics_input_supplement.sql")!!
                    .bufferedReader().use { it.readText() }
                connection.createStatement().use { statement ->
                    splitSqlStatements(migration8).forEach(statement::execute)
                }
                if (applyPinMigration) {
                    val migration9 = javaClass.getResourceAsStream(
                        "/db/migration/V9__trading_normalization_result_pin.sql")!!
                        .bufferedReader().use { it.readText() }
                    connection.createStatement().use { statement ->
                        splitSqlStatements(migration9).forEach(statement::execute)
                    }
                }
                if (applyPinMigration) {
                    connection.createStatement().use { statement ->
                        val migration10 = javaClass.getResourceAsStream(
                            "/db/migration/V10__trading_normalization_transition.sql")!!
                            .bufferedReader().use { it.readText() }
                        splitSqlStatements(migration10).forEach(statement::execute)
                    }
                }
                if (applyDeletionMigration) connection.createStatement().use { statement ->
                    val deletionMigration = javaClass.getResourceAsStream(
                        "/db/migration/V13__trading_deletion_checkpoints.sql")!!
                        .bufferedReader().use { it.readText() }
                    splitSqlStatements(deletionMigration).forEach(statement::execute)
                    val leases = javaClass.getResourceAsStream("/db/migration/V14__trading_deletion_execution_leases.sql")!!
                        .bufferedReader().use { it.readText() }
                    splitSqlStatements(leases).forEach(statement::execute)
                }
                if (applyRetentionMigration) connection.createStatement().use { statement ->
                    val retry = javaClass.getResourceAsStream("/db/migration/V15__trading_raw_retention_retry.sql")!!
                        .bufferedReader().use { it.readText() }
                    splitSqlStatements(retry).forEach(statement::execute)
                }
                connection.createStatement().use { statement ->
                    val policy = javaClass.getResourceAsStream("/db/migration/V16__trading_raw_policy_clocks.sql")!!
                        .bufferedReader().use { it.readText() }
                    splitSqlStatements(policy).forEach(statement::execute)
                }
                if (applyCountMigration) connection.createStatement().use { statement ->
                    val counts = javaClass.getResourceAsStream("/db/migration/V17__trading_deletion_count_snapshot.sql")!!
                        .bufferedReader().use { it.readText() }
                    splitSqlStatements(counts).forEach(statement::execute)
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
