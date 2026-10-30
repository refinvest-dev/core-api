package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.application.NormalizationInputFingerprint
import com.refinvest.core.tradingreview.application.TradingReviewService
import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.importsession.TradingSourceArtifact
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
import com.refinvest.core.tradingreview.port.*
import com.refinvest.core.tradingreview.port.IdempotencyClaim
import com.refinvest.core.tradingreview.port.TradingDeletion
import com.refinvest.core.tradingreview.port.TradingFailure
import com.refinvest.core.tradingreview.port.TradingIdGenerator
import com.refinvest.core.tradingreview.port.TradingMemberProvider
import com.refinvest.core.tradingreview.port.book.TradingAccountBookDeletion
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.transaction.support.TransactionTemplate

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
    fun sessionAggregateSaveFencesPinsMissingChildrenAndStaleSnapshots() = withDatabase { connection ->
        val (service, tx, _) = accountBookFixture(connection)
        val account = tx.execute { service.create(TradingVenue.BINANCE, "A", "a") }
        val book = tx.execute { service.create(account.id, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "b") }
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val store = JdbcTradingImportSessionPersistenceAdapter(jdbc)
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
        val (service, tx, _) = accountBookFixture(connection)
        val account = tx.execute { service.create(TradingVenue.BINANCE, "A", "a") }
        val book = tx.execute { service.create(account.id, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "b") }
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val store = JdbcTradingImportSessionPersistenceAdapter(jdbc)
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
        tx.execute { service.delete(book.id, "delete-book") }
        val afterDelete = importRows(jdbc)
        assertFalse(tx.execute { store.update(store.find(session.id, session.owner)!!.requestDeletion(now.plusSeconds(1))) })
        assertEquals(afterDelete, importRows(jdbc))
    }

    @Test
    fun rawPurgeClaimVersionRejectsLateCompletionFailureAndAggregateOverwrite() = withDatabase { connection ->
        val (service, tx, _) = accountBookFixture(connection)
        val account = tx.execute { service.create(TradingVenue.BINANCE, "A", "a") }
        val book = tx.execute { service.create(account.id, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "b") }
        val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
        val store = JdbcTradingImportSessionPersistenceAdapter(jdbc)
        val retention = JdbcTradingArtifactRetentionAdapter(jdbc)
        val session = TradingImportSession.create(TradingImportSessionId(32000), book, "UTC", "UTC",
            TradingPositionMode.ONE_WAY, TradingDataPolicy(), normalizationVersions, now)
        tx.execute { store.insert(session) }
        assertTrue(tx.execute { store.update(session.recordUpload(TradingSourceArtifactId(32001),
            TradingArtifactRole.TRADE_HISTORY, "text/csv", 2, "a".repeat(64), "AUTO", "objects/claim", byteArrayOf(1), "test", now)) })
        val old = store.find(session.id, session.owner)!!
        val firstClaim = retention.claimDue(Instant.now(), 1).single()
        assertFalse(tx.execute { store.update(old.requestArtifactDeletion(firstClaim.id, now.plusSeconds(1))) })
        jdbc.update("UPDATE trading_source_artifacts SET deletion_claim_until=CURRENT_TIMESTAMP - INTERVAL '1 second'")
        val nextClaim = retention.claimDue(Instant.now(), 1).single()
        val before = importRows(jdbc)
        assertFalse(retention.markDeleted(firstClaim, Instant.now()))
        retention.markDeletionFailed(firstClaim, "LATE_FAILURE", Instant.now())
        assertEquals(before, importRows(jdbc))
        assertTrue(retention.markDeleted(nextClaim, Instant.now()))
        val deleted = store.find(session.id, session.owner)!!.artifacts.single()
        assertEquals(TradingArtifactRetentionStatus.DELETED, deleted.retentionStatus)
        assertNull(deleted.objectKey)
        assertNull(deleted.wrappedDek)
        assertFalse(deleted.rawReparseAvailable(Instant.now()))
    }

    private fun importRows(jdbc: JdbcTemplate) = listOf(
        jdbc.queryForList("SELECT row_to_json(s)::text FROM trading_import_sessions s ORDER BY id", String::class.java),
        jdbc.queryForList("SELECT row_to_json(a)::text FROM trading_source_artifacts a ORDER BY id", String::class.java),
    )

    @Test
    fun accountCreationReplayAfterDeletionIsGone() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val account = tx.execute { service.create(TradingVenue.BINANCE, "A", "create-a") }
        tx.execute { service.delete(account.id, "delete-a") }
        assertEquals("RESOURCE_DELETED", assertFailsWith<TradingFailure> {
            tx.execute { service.create(TradingVenue.BINANCE, "A", "create-a") }
        }.code)
        assertEquals(1L, stores.find(account.id, MemberId(1))?.deletionGeneration)
    }

    @Test
    fun bookCreationReplayAfterDeletionIsGone() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val account = tx.execute { service.create(TradingVenue.BINANCE, "A", "create-a") }
        val book = tx.execute { service.create(account.id, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "create-b") }
        tx.execute { service.delete(book.id, "delete-b") }
        assertEquals("RESOURCE_DELETED", assertFailsWith<TradingFailure> {
            tx.execute { service.create(account.id, "B", TradingProductFamily.LINEAR_PERPETUAL,
                TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "create-b") }
        }.code)
        assertEquals(1L, stores.find(book.id, MemberId(1))?.deletionGeneration)
    }

    @Test
    fun ordinaryAggregateWritesCannotReopenDeletedRowsOrChangeBookIdentity() = withDatabase { connection ->
        val (service, tx, stores) = accountBookFixture(connection)
        val account = tx.execute { service.create(TradingVenue.BINANCE, "A", "create-a") }
        val book = tx.execute { service.create(account.id, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "create-b") }
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
        tx.execute { service.delete(account.id, "delete-a") }
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
        val account = tx.execute { service.create(TradingVenue.BINANCE, "A", "create-a") }
        tx.execute { service.create(account.id, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "create-b") }
        val before = accountBookRows(jdbc)
        assertFailsWith<IllegalStateException> {
            tx.execute { service.delete(account.id, "delete-a"); error("Synthetic failure after cascade and journal") }
        }
        assertEquals(before, accountBookRows(jdbc))
        assertEquals(TradingLifecycleStatus.ACTIVE, stores.find(account.id, account.owner)!!.status)
        val deletion = tx.execute { service.delete(account.id, "delete-a") }
        assertEquals(1L, deletion.bookCount)
        assertEquals(deletion.id, tx.execute { service.delete(account.id, "delete-a") }.id)
        assertEquals(1L, stores.find(account.id, account.owner)!!.deletionGeneration)
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_deletion_requests", Long::class.java))
    }

    @Test
    fun aConcurrentBookWriterWaitsForAccountDeletionAndCannotUndoIt() = withDatabase { first ->
        val (service, tx, stores) = accountBookFixture(first)
        val jdbc = JdbcTemplate(SingleConnectionDataSource(first, true))
        val account = tx.execute { service.create(TradingVenue.BINANCE, "A", "create-a") }
        val book = tx.execute { service.create(account.id, "B", TradingProductFamily.LINEAR_PERPETUAL,
            TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", "create-b") }
        DriverManager.getConnection(System.getenv("TRADING_REVIEW_TEST_JDBC_URL"),
            System.getenv("TRADING_REVIEW_TEST_DB_USER"), System.getenv("TRADING_REVIEW_TEST_DB_PASSWORD")).use { second ->
            second.schema = first.schema
            val secondSource = SingleConnectionDataSource(second, true)
            val secondJdbc = JdbcTemplate(secondSource)
            val writer = JdbcTradingBookPersistenceAdapter(secondJdbc)
            val secondTx = TransactionTemplate(DataSourceTransactionManager(secondSource))
            val backend = secondJdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!
            val executor = Executors.newSingleThreadExecutor()
            var result: java.util.concurrent.Future<Boolean>? = null
            try {
                tx.execute {
                    service.delete(account.id, "delete-a")
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

    private fun accountBookFixture(connection: Connection): Triple<TradingReviewService, TransactionTemplate, TradingReviewTestStores> {
        val source = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(source)
        members(jdbc)
        val stores = TradingReviewTestStores(jdbc)
        val ids = object : TradingIdGenerator {
            var next = 10000L
            override fun accountId() = TradingAccountId(next++)
            override fun bookId() = TradingBookId(next++)
            override fun deletionId() = next++
        }
        val service = TradingReviewService(object : TradingMemberProvider {
            override fun currentMemberId() = MemberId(1)
        }, ids, stores, stores, stores, stores, Clock.fixed(now, ZoneOffset.UTC),
            stores.accountReader, stores.bookReader, stores.bookDeletionCascade)
        return Triple(service, TransactionTemplate(DataSourceTransactionManager(source)), stores)
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
                JdbcTradingNormalizationStore(jdbc, mapper).findRun(runId)?.resultVersionPin)
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
        val run = NormalizationRun(runId, input, NormalizationInputCanonicalizer.hash(identity),
            "b".repeat(64), NormalizationRunStatus.DISPATCH_PENDING, 1, token,
            null, null, now, now, null, now, null, 0, inputIdentity = identity,
            resultDeliveryPolicy = deliveryPolicy,
            resultVersionPin = NormalizationResultVersionPin.V2)
        val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId, "c".repeat(64),
            NormalizationDispatchStatus.PENDING, 0, now, null, null, null, now, now,
            resultVersionPin = NormalizationResultVersionPin.V2)
        JdbcTradingNormalizationStore(jdbc, mapper).insert(run, dispatch)
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
        val results = JdbcTradingNormalizationResultStore(jdbc, mapper)
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
    fun mismatchedTerminalDescriptorBlocksRevisionAndPersistedAck() =
        verifyPublishedTerminal(false, mismatchedDescriptor = true)

    @Test
    fun mismatchedTerminalPayloadBlocksRevisionAndPersistedAck() =
        verifyPublishedTerminal(false, mismatchedPayload = true)

    private fun verifyPublishedTerminal(v2: Boolean, mismatchedDescriptor: Boolean = false,
                                        mismatchedPayload: Boolean = false) =
        withDatabase { connection ->
        val source = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(source)
        val transactions = TransactionTemplate(DataSourceTransactionManager(source))
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
        val run = NormalizationRun(runId, input, NormalizationInputCanonicalizer.hash(identity),
            "b".repeat(64), NormalizationRunStatus.DISPATCH_PENDING, 1, attemptToken,
            null, null, now, now, null, now, null, 0, inputIdentity = identity,
            resultDeliveryPolicy = deliveryPolicy,
            resultVersionPin = if (v2 && !mismatchedPayload) NormalizationResultVersionPin.V2
                else NormalizationResultVersionPin.V1)
        val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId, "c".repeat(64),
            NormalizationDispatchStatus.PENDING, 0, now, null, null, null, now, now,
            resultVersionPin = run.resultVersionPin)
        val runs = JdbcTradingNormalizationStore(jdbc, mapper)
        runs.insert(run, dispatch)
        assertEquals(deliveryPolicy, runs.findRun(runId)?.resultDeliveryPolicy)
        assertTrue(imports.update(imports.find(session.id, session.owner)!!.beginValidation(runId, now)))
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
        if (mismatchedDescriptor || mismatchedPayload) {
            val processor = com.refinvest.core.tradingreview.application.TradingNormalizationResultProcessor(
                runs, imports, review, results, client, transactions,
                Clock.fixed(now, ZoneOffset.UTC), mapper,
                object : TradingRetryJitter { override fun factor() = 0.5 }, JdbcTradingNormalizationTransitionGate(jdbc))
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
        val interrupted = object : TradingNormalizationResultStore by results {
            override fun publish(run: NormalizationRun, dispatch: NormalizationDispatch, workerId: String,
                                 descriptor: NormalizationResultDescriptor, manifestJson: String,
                                 coverageJson: String, capabilityJson: String, qualityJson: String,
                                 ledgerContentHash: String, now: Instant): Boolean =
                error("simulated process failure before publication commit")
        }
        val processor = com.refinvest.core.tradingreview.application.TradingNormalizationResultProcessor(
            runs, imports, review, interrupted, client, transactions, Clock.fixed(now, ZoneOffset.UTC), mapper,
            object : TradingRetryJitter { override fun factor() = 0.5 }, JdbcTradingNormalizationTransitionGate(jdbc))
        assertTrue(processor.processOne())
        assertEquals(TradingImportStatus.VALIDATING, imports.find(session.id, MemberId(1))?.status)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_result_staging", Long::class.java))
        val recoveredProcessor = com.refinvest.core.tradingreview.application.TradingNormalizationResultProcessor(
            runs, imports, review, results, client, transactions,
            Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC), mapper,
            object : TradingRetryJitter { override fun factor() = 0.5 }, JdbcTradingNormalizationTransitionGate(jdbc))
        assertTrue(recoveredProcessor.processOne())
        assertEquals(1, descriptorCalls)
        assertEquals(1, downloadCalls)
        assertEquals(TradingImportStatus.ACCEPTED, imports.find(session.id, MemberId(1))?.status)
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
        val revisionId = assertNotNull(jdbc.queryForObject(
            "SELECT id::text FROM trading_ledger_revisions", String::class.java))
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
                    override fun deleteRevision(revisionId: String) = Unit
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
        val ackRecovered = com.refinvest.core.tradingreview.application.TradingNormalizationResultProcessor(
            runs, imports, review, results, client, transactions,
            Clock.fixed(now.plusSeconds(47), ZoneOffset.UTC), mapper,
            object : TradingRetryJitter { override fun factor() = 0.5 }, JdbcTradingNormalizationTransitionGate(jdbc))
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
        val run = NormalizationRun(runId, input, NormalizationInputCanonicalizer.hash(identity),
            "b".repeat(64), NormalizationRunStatus.DISPATCH_PENDING, 1,
            UUID.randomUUID().toString(), null, null, now, now, null, now, null, 0,
            inputIdentity = identity, resultDeliveryPolicy = deliveryPolicy)
        val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId, "c".repeat(64),
            NormalizationDispatchStatus.PENDING, 0, now, null, null, null, now, now)
        val runs = JdbcTradingNormalizationStore(jdbc, mapper)
        runs.insert(run, dispatch)
        assertTrue(imports.update(imports.find(session.id, session.owner)!!.beginValidation(runId, now)))
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
            object : TradingRetryJitter { override fun factor() = 0.5 }, JdbcTradingNormalizationTransitionGate(jdbc))
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
        val run = NormalizationRun(runId, input, NormalizationInputCanonicalizer.hash(identity),
            "b".repeat(64), NormalizationRunStatus.DISPATCH_PENDING, 1, token, null, null,
            now, now, null, now, null, 0, inputIdentity = identity,
            resultDeliveryPolicy = deliveryPolicy)
        val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId, "c".repeat(64),
            NormalizationDispatchStatus.PENDING, 0, now, null, null, null, now, now)
        val runs = JdbcTradingNormalizationStore(jdbc, mapper)
        runs.insert(run, dispatch)
        assertTrue(imports.update(imports.find(session.id, session.owner)!!.beginValidation(runId, now)))
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
        val run = NormalizationRun(runId, input, NormalizationInputCanonicalizer.hash(identity),
            "b".repeat(64), NormalizationRunStatus.DISPATCH_PENDING, 1, token, null, null,
            now, now, null, now, null, 0, inputIdentity = identity,
            resultDeliveryPolicy = deliveryPolicy)
        val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId, "c".repeat(64),
            NormalizationDispatchStatus.PENDING, 0, now, null, null, null, now, now)
        val runs = JdbcTradingNormalizationStore(jdbc, mapper)
        runs.insert(run, dispatch)
        assertTrue(imports.update(imports.find(session.id, session.owner)!!.beginValidation(runId, now)))
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
        assertTrue(results.hasInvalidEvidence(current))
        stage("VALIDATION_ISSUES", "\uE000", """{"id":"bmp"}""")
        stage("VALIDATION_ISSUES", "\uD800\uDC00", """{"id":"supplementary"}""")
        val ordered = mutableListOf<String>()
        results.visitItems(runId, "VALIDATION_ISSUES", 1) { ordered.add(it.identity) }
        assertEquals(listOf("\uD800\uDC00", "\uE000"), ordered)
        results.markValidated(runId, now)
        assertNull(results.publishedForSession(session.id, MemberId(1)))
        assertFailsWith<Exception> {
            tx.execute { results.publish(current, claimed, worker, descriptor, "{}", "[]", "[]", "{}",
                "f".repeat(64), now) }
        }
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
        assertEquals(TradingImportStatus.VALIDATING, imports.find(session.id, MemberId(1))?.status)
        stage("SOURCE_EVIDENCE_SNAPSHOTS", "snapshot-1",
            """{"snapshotId":"snapshot-1","coreArtifactId":"994","artifactRole":"${inputs.first().role.name}","sourceRowNumber":1,"sourceRowHash":"${"a".repeat(64)}","maskingPolicyVersion":"MASK_V1"}""")
        assertFalse(results.hasInvalidEvidence(current))
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
        assertTrue(results.publish(current, claimed, worker, descriptor, "{}", "[]", "[]", "{}",
            "f".repeat(64), now))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_ledger_revisions", Long::class.java))
        val publishedBook = review.find(book.id, book.owner)!!
        val latestRevision = assertNotNull(publishedBook.latestLedgerRevisionId)
        assertEquals(jdbc.queryForObject("SELECT id::text FROM trading_ledger_revisions", String::class.java), latestRevision.value.toString())
        assertFalse(review.update(book.rename("Stale pre-publication snapshot", now)))
        assertTrue(review.update(publishedBook.rename("Renamed after publication", now.plusSeconds(1))))
        val renamedBook = review.find(book.id, book.owner)!!
        assertEquals(latestRevision, renamedBook.latestLedgerRevisionId)
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
        val transactions = TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbc.dataSource)))
        val nextToken = UUID.randomUUID().toString()
        assertFalse(transactions.execute { store.advanceAttempt(runId, dispatch.id, UUID.randomUUID().toString(),
            token, nextToken, refreshAt) })
        assertFalse(transactions.execute { store.advanceAttempt(runId, dispatch.id, refreshWorker,
            token, nextToken, refreshAt.plusSeconds(45)) })
        assertTrue(transactions.execute { store.advanceAttempt(runId, dispatch.id, refreshWorker, token, nextToken, refreshAt) })
        val refreshed = assertNotNull(store.findRun(runId))
        assertEquals(2, refreshed.currentAttempt)
        assertEquals(nextToken, refreshed.attemptToken)
        assertEquals("same-job", refreshed.computeJobId)
        assertEquals(listOf("same-job", "same-job"), jdbc.queryForList("""SELECT compute_job_id
            FROM trading_normalization_attempts WHERE run_id=?::uuid ORDER BY attempt_number""", String::class.java, runId))
        assertEquals(run.inputFingerprint, refreshed.inputFingerprint)
        assertEquals(run.resultVersionPin, refreshed.resultVersionPin)
        assertFalse(transactions.execute { store.advanceAttempt(runId, dispatch.id, refreshWorker, token,
            UUID.randomUUID().toString(), refreshAt) })
        assertFailsWith<IllegalStateException> { transactions.execute {
            assertTrue(store.advanceAttempt(runId, dispatch.id, refreshWorker, nextToken,
                UUID.randomUUID().toString(), refreshAt))
            error("rollback grant attempt")
        } }
        assertEquals(2, store.findRun(runId)?.currentAttempt)
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_attempts WHERE run_id=?::uuid", Long::class.java, runId))
        assertEquals(1L, jdbc.queryForObject("""SELECT count(*) FROM trading_normalization_audit
            WHERE run_id=?::uuid AND event='GRANT_RETRY_ATTEMPT'""", Long::class.java, runId))
        val newJobToken = UUID.randomUUID().toString()
        val newDispatch = dispatch.copy(id = UUID.randomUUID().toString(), computeKey = "e".repeat(64))
        assertTrue(transactions.execute { store.restartAfterJobFailure(runId, dispatch.id, refreshWorker,
            newDispatch, newJobToken, "TEMPORARY_DEPENDENCY_FAILURE", refreshAt) })
        assertNull(store.findRun(runId)?.computeJobId)
        assertNull(jdbc.queryForObject("""SELECT compute_job_id FROM trading_normalization_attempts
            WHERE run_id=?::uuid AND attempt_number=3""", String::class.java, runId))
    }

    @Test
    fun validationAdmissionCommitsSessionRunAndDispatchTogether() = withDatabase { connection ->
        val source = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(source)
        val transactions = TransactionTemplate(DataSourceTransactionManager(source))
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        members(jdbc)
        val review = TradingReviewTestStores(jdbc)
        val imports = TradingImportTestStores(jdbc)
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
        val workerTime = java.util.concurrent.atomic.AtomicReference(now)
        val workerClock = object : Clock() {
            override fun instant() = workerTime.get()
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
        }
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
            }, mapper, workerClock, JdbcTradingNormalizationTransitionGate(jdbc))
        jdbc.execute("SELECT trading_normalization_transition_control('PAUSE',0,'test-validation')")
        for (retry in listOf(false, true)) {
            val failure = assertFailsWith<TradingFailure> {
                transactions.execute {
                    if (retry) service.retry(session.id, "same-public-key")
                    else service.validate(session.id, null, "same-public-key")
                }
            }
            assertEquals("TEMPORARY_DEPENDENCY_FAILURE", failure.code)
            assertEquals(503, failure.httpStatus)
        }
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_idempotency", Long::class.java))
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_dispatches", Long::class.java))
        assertEquals(session.id, imports.find(session.id, MemberId(1))?.id)
        jdbc.execute("SELECT trading_normalization_transition_control('VERIFY',1,'test-validation-http')")
        jdbc.execute("SELECT trading_normalization_transition_control('RESUME',1,'test-validation-http')")
        assertEquals("MISSING_REQUIRED_ARTIFACT", assertFailsWith<TradingFailure> {
            transactions.execute { service.validate(session.id, null, "missing-position") }
        }.code)
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_runs", Long::class.java))
        assertEquals(TradingImportStatus.RECEIVED, imports.find(session.id, MemberId(1))?.status)
        for ((index, role) in TradingArtifactRole.entries.withIndex()) {
            imports.seedArtifact(TradingSourceArtifact.restore(TradingSourceArtifactId(914L + index), session.id,
                role, null, "text/csv", 20, (if (index == 0) "a" else "b").repeat(64),
                "AUTO", "objects/${UUID.randomUUID()}", byteArrayOf(1), "test-v1",
                TradingArtifactRetentionStatus.ACTIVE, now, now.plusSeconds(3600), null, null, 0))
        }
        val started = transactions.execute { service.validate(session.id, null, "same-public-key") }!!
        assertEquals(TradingImportStatus.VALIDATING, started.status)
        assertNotNull(started.activeNormalizationRunId)
        val replay = transactions.execute { service.validate(session.id, null, "validate-again") }!!
        assertEquals(started.activeNormalizationRunId, replay.activeNormalizationRunId)
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_runs", Long::class.java))
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_dispatches", Long::class.java))
        assertEquals(started.activeNormalizationRunId, imports.find(session.id, MemberId(1))?.activeNormalizationRunId)
        val active = assertNotNull(runs.findRun(started.activeNormalizationRunId!!))
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
            }, transactions, workerClock, mapper, JdbcTradingNormalizationTransitionGate(jdbc))
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
        assertEquals(NormalizationResultVersionPin.V1,
            runs.findRun(retried.activeNormalizationRunId!!)?.resultVersionPin)
        assertEquals(NormalizationResultVersionPin.V1.schemaVersion,
            jdbc.queryForObject("""SELECT result_schema_version FROM trading_normalization_dispatches
                WHERE run_id=?::uuid""", String::class.java, retried.activeNormalizationRunId!!))
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
            }, transactions, workerClock, mapper, JdbcTradingNormalizationTransitionGate(jdbc))
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
        jdbc.update("UPDATE trading_source_artifacts SET raw_expires_at=?, uploaded_at=? WHERE import_session_id=?",
            java.sql.Timestamp.from(now.minusSeconds(1)), java.sql.Timestamp.from(now.minusSeconds(86401)), session.id.value)
        assertEquals("SOURCE_ARTIFACT_EXPIRED", assertFailsWith<TradingFailure> {
            transactions.execute { service.retry(session.id, "retry-expired") }
        }.code)
        assertEquals(TradingImportStatus.PROCESSING_FAILED, imports.find(session.id, MemberId(1))?.status)
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM trading_normalization_dispatches", Long::class.java))
        jdbc.update("UPDATE trading_source_artifacts SET raw_expires_at=? WHERE import_session_id=?",
            java.sql.Timestamp.from(now.plusSeconds(3600)), session.id.value)
        val again = transactions.execute { service.retry(session.id, "retry-after-exhaustion") }!!
        assertEquals(retried.activeNormalizationRunId, runs.findRun(again.activeNormalizationRunId!!)?.retryOfRunId)
        assertEquals(NormalizationResultVersionPin.V1,
            runs.findRun(again.activeNormalizationRunId!!)?.resultVersionPin)
        val staleDispatch = assertNotNull(runs.claimNext(workerTime.get(), java.time.Duration.ofSeconds(45),
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
        val service = TradingReviewService(member, ids, store, store, store, store, clock,
            store.accountReader, store.bookReader, store.bookDeletionCascade)
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
        val accountStore = TradingReviewTestStores(jdbc)
        val importStore = TradingImportTestStores(jdbc)
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
            }, transactions, Clock.fixed(now, ZoneOffset.UTC), noPublished, importStore)
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
        fun assertCreateReplayIsGone() {
            val failure = assertFailsWith<TradingFailure> {
                transactions.execute { service.create(book.id, "UTC", "UTC", TradingPositionMode.ONE_WAY,
                    null, null, "create-import") }
            }
            assertEquals(410, failure.httpStatus)
            assertEquals("RESOURCE_DELETED", failure.code)
        }
        assertCreateReplayIsGone()
        TradingDeletionWorker(jdbc, transactions, storage, Clock.fixed(now, ZoneOffset.UTC)).purgePending()
        assertEquals("COMPLETED", accountStore.find(deletion.id, MemberId(1))?.state)
        assertFalse(storage.exists(trade.artifact.objectKey!!))
        assertFalse(storage.exists(position.artifact.objectKey!!))
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
                        val reviewStore = TradingReviewTestStores(workerJdbc)
                        val importStore = TradingImportTestStores(workerJdbc)
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
                            Clock.fixed(now, ZoneOffset.UTC), noPublished, importStore)
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
        fun service(connection: Connection): com.refinvest.core.tradingreview.application.TradingImportService {
            val source = SingleConnectionDataSource(connection, true)
            val connectionJdbc = JdbcTemplate(source)
            val reviews = TradingReviewTestStores(connectionJdbc)
            val imports = TradingImportTestStores(connectionJdbc)
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
                noPublished, imports)
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
                val creation = executor.submit<Result<TradingImportSession>> {
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
                            override fun deletionId() = 9999L
                        }
                        val storage = emptyStorage()
                        val service = com.refinvest.core.tradingreview.application.TradingImportService(
                            object : TradingMemberProvider { override fun currentMemberId() = MemberId(1) },
                            ids, reviews, reviews, imports, imports, reviews, reviews, storage,
                            object : com.refinvest.core.tradingreview.port.TradingVersionPins {
                                override fun currentJson() = "{}"
                            }, TransactionTemplate(DataSourceTransactionManager(dataSource)),
                            Clock.fixed(now, ZoneOffset.UTC), noPublished, imports)
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

    @Test
    fun accountDeletionRevokesExistingArtifactGrantsBeforePurge() = verifyParentGrantRevocation(deleteAccount = true)

    @Test
    fun bookDeletionRevokesExistingArtifactGrantsBeforePurge() = verifyParentGrantRevocation(deleteAccount = false)

    private fun verifyParentGrantRevocation(deleteAccount: Boolean) = withDatabase { connection ->
        val source = SingleConnectionDataSource(connection, true)
        val jdbc = JdbcTemplate(source)
        val tx = TransactionTemplate(DataSourceTransactionManager(source))
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
        val grants = JdbcArtifactAccessGrants(jdbc, storage, Clock.fixed(now, ZoneOffset.UTC),
            "https://core.test", "synthetic-internal-key")
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
            override fun deletionId() = 3900L
        }
        val service = TradingReviewService(object : TradingMemberProvider {
            override fun currentMemberId() = MemberId(1)
        }, ids, review, review, review, review, Clock.fixed(now, ZoneOffset.UTC),
            review.accountReader, review.bookReader, review.bookDeletionCascade)
        val deletion = assertNotNull(tx.execute {
            if (deleteAccount) service.delete(target.account.id, "delete-account")
            else service.delete(target.bookId, "delete-book")
        })
        assertEquals("REQUESTED", deletion.state)
        val revoked = if (deleteAccount) listOf(target, sibling) else listOf(target)
        for (fixture in revoked) {
            // The deletion worker has not run: raw metadata and Session are still readable in storage.
            assertEquals(TradingArtifactRetentionStatus.ACTIVE,
                imports.find(fixture.artifact.artifactId, MemberId(1))?.retentionStatus)
            assertEquals(TradingImportStatus.VALIDATING,
                imports.find(fixture.sessionId, MemberId(1))?.status)
            val failure = assertFailsWith<TradingFailure> { grants.redeem(issued.getValue(fixture)) }
            assertEquals("RESOURCE_NOT_FOUND", failure.code)
            assertEquals(404, failure.httpStatus)
            val issueFailure = assertFailsWith<TradingFailure> { issue(fixture) }
            assertEquals("SOURCE_ARTIFACT_EXPIRED", issueFailure.code)
        }
        assertEquals(0, opened)
        assertEquals(4L, jdbc.queryForObject("SELECT count(*) FROM trading_artifact_read_grants", Long::class.java))
        assertEquals(0L, jdbc.queryForObject(
            "SELECT count(*) FROM trading_artifact_read_grants WHERE consumed_at IS NOT NULL", Long::class.java))
        for (fixture in fixtures - revoked.toSet()) {
            assertEquals("synthetic raw", grants.redeem(issued.getValue(fixture)).input.bufferedReader().use { it.readText() })
            assertEquals("RESOURCE_NOT_FOUND", assertFailsWith<TradingFailure> {
                grants.redeem(issued.getValue(fixture))
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
        val run = NormalizationRun(runId, input, "a".repeat(64), "synthetic-$baseId",
            NormalizationRunStatus.DISPATCH_PENDING, 1, token, null, null, now, now, null, now, null, 0,
            resultDeliveryPolicy = deliveryPolicy)
        val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId,
            baseId.toString().padStart(64, '0'), NormalizationDispatchStatus.PENDING,
            0, now, null, null, null, now, now)
        JdbcTradingNormalizationStore(jdbc, tools.jackson.databind.json.JsonMapper.builder().build()).insert(run, dispatch)
        assertTrue(imports.update(imports.find(session.id, session.owner)!!.beginValidation(runId, now)))
        return GrantSource(account, book.id, session.id, pinned, dispatch.id, token)
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
        val review = TradingReviewTestStores(jdbc)
        val imports = TradingImportTestStores(jdbc)
        val runs = JdbcTradingNormalizationStore(jdbc, mapper)
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
            val run = NormalizationRun(runId, input, NormalizationInputCanonicalizer.hash(identity),
                UUID.randomUUID().toString().replace("-", "").repeat(2), NormalizationRunStatus.SUBMITTED,
                1, attemptToken, jobId, null, now, now, now, now, null, 0,
                inputIdentity = identity, resultDeliveryPolicy = deliveryPolicy)
            val dispatch = NormalizationDispatch(UUID.randomUUID().toString(), runId,
                index.toString(16).padStart(64, 'c'), NormalizationDispatchStatus.SUBMITTED, 0, now,
                null, null, null, now, now)
            runs.insert(run, dispatch)
            assertTrue(imports.update(imports.find(session.id, session.owner)!!.beginValidation(runId, now)))
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
                transactions, Clock.fixed(now, ZoneOffset.UTC), mapper, JdbcTradingNormalizationTransitionGate(jdbc))
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

    @Test
    fun transitionFenceWaitsForAdmissionCommitAndSurvivesNewInstances() = withDatabase { connection ->
        val source = transitionSource(connection.schema)
        val jdbc = JdbcTemplate(source)
        val gate = JdbcTradingNormalizationTransitionGate(jdbc)
        val tx = TransactionTemplate(DataSourceTransactionManager(source))
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
                assertEquals(503, failure.httpStatus)
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
                    val store = JdbcTradingNormalizationStore(jdbc, tools.jackson.databind.json.JsonMapper.builder().build())
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

    private fun withDatabase(applyPinMigration: Boolean = true, test: (Connection) -> Unit) {
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
