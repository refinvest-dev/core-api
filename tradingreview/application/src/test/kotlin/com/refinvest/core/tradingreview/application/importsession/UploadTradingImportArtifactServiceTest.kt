package com.refinvest.core.tradingreview.application.importsession

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.application.importsession.upload.UploadTradingImportArtifactService
import com.refinvest.core.tradingreview.domain.TradingAccount
import com.refinvest.core.tradingreview.domain.TradingBook
import com.refinvest.core.tradingreview.domain.TradingImportSession
import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.inbound.importsession.upload.TradingUploadMetadata
import com.refinvest.core.tradingreview.port.inbound.importsession.upload.UploadTradingImportArtifactCommand
import com.refinvest.core.tradingreview.port.outbound.idempotency.IdempotencyClaim
import com.refinvest.core.tradingreview.port.outbound.idempotency.TradingIdempotencyStore
import com.refinvest.core.tradingreview.port.outbound.identity.TradingIdGenerator
import com.refinvest.core.tradingreview.port.outbound.member.TradingMemberProvider
import com.refinvest.core.tradingreview.port.outbound.persistence.account.TradingAccountStore
import com.refinvest.core.tradingreview.port.outbound.persistence.book.TradingBookStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingImportSessionStore
import com.refinvest.core.tradingreview.port.outbound.persistence.importsession.TradingSourceArtifactReader
import com.refinvest.core.tradingreview.port.outbound.storage.raw.RawArtifactStorage
import com.refinvest.core.tradingreview.port.outbound.storage.raw.StagedRawArtifact
import java.io.InputStream
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

class UploadTradingImportArtifactServiceTest {
    private val now = Instant.parse("2026-06-01T00:00:00Z")
    private val owner = MemberId(11)
    private val account = TradingAccount.create(TradingAccountId(21), owner, TradingVenue.BINANCE, "A", now)
    private val book = TradingBook.create(TradingBookId(31), account, "B", TradingProductFamily.LINEAR_PERPETUAL,
        TradingPositionMode.ONE_WAY, TradingSettlementAsset.USDT, "UTC", now)
    private val session = TradingImportSession.create(TradingImportSessionId(41), book, "UTC", "UTC",
        TradingPositionMode.ONE_WAY, TradingDataPolicy(), "{}", now)
    private val sha256 = "0".repeat(64)
    private val staged = StagedRawArtifact("staging", sha256, 3, byteArrayOf(1), "test-key")

    @Test
    fun `upload streams before deferred metadata then publishes outside the locked database transaction`() {
        exercise(replay = false, conflict = false)
    }

    @Test
    fun `replay leaves the aggregate unchanged and removes the redundant published object outside the transaction`() {
        exercise(replay = true, conflict = false)
    }

    @Test
    fun `stale aggregate save rolls back and removes only the unreferenced publication`() {
        exercise(replay = false, conflict = true)
    }

    private fun exercise(replay: Boolean, conflict: Boolean) {
        val calls = mutableListOf<String>()
        val manager = object : AbstractPlatformTransactionManager() {
            override fun doGetTransaction(): Any = Any()
            override fun doBegin(transaction: Any, definition: TransactionDefinition) { calls += "begin" }
            override fun doCommit(status: DefaultTransactionStatus) { calls += "commit" }
            override fun doRollback(status: DefaultTransactionStatus) { calls += "rollback" }
        }
        val members = object : TradingMemberProvider { override fun currentMemberId() = owner }
        val ids = port<TradingIdGenerator> { name, _ ->
            assertEquals("sourceArtifactId", name)
            51L
        }
        val accounts = port<TradingAccountStore> { name, args ->
            assertEquals("find", name)
            assertEquals(owner.value, args[1])
            if (args[2] == true) { assertTransaction(); calls += "lock-account" }
            account
        }
        val books = port<TradingBookStore> { name, args ->
            assertEquals("find", name)
            assertEquals(owner.value, args[1])
            if (args[2] == true) { assertTransaction(); calls += "lock-book" }
            book
        }
        val sessions = port<TradingImportSessionStore> { name, args ->
            when (name) {
                "find" -> {
                    assertEquals(owner.value, args[1])
                    if (args[2] == true) { assertTransaction(); calls += "lock-session" }
                    session
                }
                "update" -> {
                    assertTransaction()
                    assertFalse(replay)
                    val changed = args[0] as TradingImportSession
                    assertEquals(TradingSourceArtifactId(51), changed.artifacts.single().id)
                    calls += "update"
                    !conflict
                }
                else -> error(name)
            }
        }
        val existing = session.recordUpload(TradingSourceArtifactId(61), TradingArtifactRole.TRADE_HISTORY,
            "text/csv", 3, sha256, "AUTO", "existing", byteArrayOf(1), "test-key", now).artifacts.single()
        val artifacts = port<TradingSourceArtifactReader> { name, _ ->
            when (name) {
                "find" -> { assertTransaction(); assertTrue(replay); existing }
                "referencesObjectKey" -> { assertFalse(inTransaction()); false }
                else -> error(name)
            }
        }
        val idempotency = port<TradingIdempotencyStore> { name, _ ->
            assertTransaction()
            calls += name
            when (name) {
                "claim" -> if (replay) IdempotencyClaim.Replayed(existing.id.value) else IdempotencyClaim.New
                "complete" -> { assertFalse(replay || conflict); Unit }
                else -> error(name)
            }
        }
        var consumed = false
        val storage = port<RawArtifactStorage> { name, args ->
            assertFalse(inTransaction(), "External storage call inside transaction: $name")
            calls += name + if (name == "delete") ":${args[0]}" else ""
            when (name) {
                "putStaging" -> {
                    assertEquals("csv", (args[0] as InputStream).readAllBytes().toString(Charsets.UTF_8))
                    consumed = true
                    staged
                }
                "preparePublication" -> staged.copy(publicationKey = "candidate")
                "promote", "delete", "completePublication" -> Unit
                "exists" -> true
                else -> error(name)
            }
        }
        val service = UploadTradingImportArtifactService(members, ids, accounts, books, sessions, artifacts,
            idempotency, storage, TransactionTemplate(manager), Clock.fixed(now, ZoneOffset.UTC))
        val command = UploadTradingImportArtifactCommand(session.id, "csv".byteInputStream(), "upload-1") {
            assertFalse(inTransaction())
            assertTrue(consumed, "Metadata may follow the file in the multipart stream")
            calls += "metadata"
            TradingUploadMetadata(TradingArtifactRole.TRADE_HISTORY, "file.csv", "text/csv", 3, sha256, "AUTO")
        }

        if (conflict) {
            assertEquals("CONCURRENT_MODIFICATION", assertFailsWith<TradingFailure> { service.execute(command) }.code)
            assertTrue("rollback" in calls)
            assertFalse("complete" in calls || "commit" in calls)
        } else {
            val result = service.execute(command)
            assertEquals(!replay, result.created)
            assertEquals(if (replay) existing.id else TradingSourceArtifactId(51), result.artifact.id)
            assertTrue("commit" in calls)
            assertEquals(!replay, "update" in calls)
        }
        assertTrue(calls.indexOf("promote") < calls.indexOf("begin"))
        assertTrue(calls.indexOf("lock-account") < calls.indexOf("lock-book"))
        assertTrue(calls.indexOf("lock-book") < calls.indexOf("lock-session"))
        assertEquals(replay || conflict, "delete:candidate" in calls)
        assertFalse("delete:existing" in calls)
        assertEquals("completePublication", calls.last())
        assertFalse(inTransaction())
    }

    private fun inTransaction() = TransactionSynchronizationManager.isActualTransactionActive()
    private fun assertTransaction() = assertTrue(inTransaction())

    private inline fun <reified T> port(noinline call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            call(method.name.substringBefore('-'), args ?: emptyArray())
        } as T
}
