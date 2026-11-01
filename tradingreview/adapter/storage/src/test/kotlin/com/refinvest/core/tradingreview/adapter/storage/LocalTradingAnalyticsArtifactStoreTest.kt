package com.refinvest.core.tradingreview.adapter.storage

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.refinvest.core.tradingreview.port.TradingFailure

class LocalTradingAnalyticsArtifactStoreTest {
    @TempDir lateinit var directory: Path
    private val key = SecretKeySpec(ByteArray(32) { 7 }, "AES")

    @Test
    fun immutableReferenceIsDeterministicAndDeletedWithRevision() {
        val store = LocalTradingAnalyticsArtifactStore(directory, 1024, key)
        val revision = UUID.randomUUID().toString()
        val bytes = "{\"schemaVersion\":\"TRADING_ANALYTICS_LEDGER_INPUT_V1\"}"
            .toByteArray(Charsets.UTF_8)
        val first = store.put(revision, "ledgerRevisionContent", "application/json") {
            it.write(bytes)
        }
        val second = store.put(revision, "ledgerRevisionContent", "application/json") {
            it.write(bytes)
        }
        assertEquals(first, second)
        assertEquals(bytes.size.toLong(), first.byteSize)
        store.open(first.coreReferenceId).use { assertContentEquals(bytes, it.readAllBytes()) }
        val encrypted = java.nio.file.Files.readAllBytes(directory.resolve(revision).resolve(
            "ledgerRevisionContent-${first.sha256}.json"))
        assertFalse(encrypted.contentEquals(bytes))
        store.deleteRevision(revision)
        assertFailsWith<Exception> { store.open(first.coreReferenceId) }
    }

    @Test
    fun sizeLimitNeverPublishesPartialBytes() {
        val store = LocalTradingAnalyticsArtifactStore(directory, 2, key)
        val revision = UUID.randomUUID().toString()
        assertFailsWith<IllegalStateException> {
            store.put(revision, "reconciliationManifest", "application/json") {
                it.write("three".toByteArray())
            }
        }
    }

    @Test
    fun revisionDeletionWaitsForOpenReadersAndPreservesOtherRevisions() {
        val store = LocalTradingAnalyticsArtifactStore(directory, 1024, key)
        val revision = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val first = store.put(revision, "ledgerRevisionContent", "application/json") { it.write(1) }
        val unaffected = store.put(other, "ledgerRevisionContent", "application/json") { it.write(2) }
        val reader = store.open(first.coreReferenceId)
        assertFalse(store.purgeRevision(revision))
        assertFailsWith<TradingFailure> { reader.read() }
        reader.close()
        assertTrue(store.purgeRevision(revision))
        assertFalse(java.nio.file.Files.exists(directory.resolve(revision)))
        assertEquals(2, store.open(unaffected.coreReferenceId).use { it.read() })
        assertFailsWith<TradingFailure> {
            store.put(revision, "ledgerRevisionContent", "application/json") { it.write(3) }
        }
    }

    @Test
    fun revisionDeletionFencesAnUnpublishedWriterAndCleansItsTemporaryBytes() {
        val store = LocalTradingAnalyticsArtifactStore(directory, 1024, key)
        val revision = UUID.randomUUID().toString()
        assertFailsWith<TradingFailure> {
            store.put(revision, "ledgerRevisionContent", "application/json") {
                it.write(1)
                assertFalse(store.purgeRevision(revision))
                it.write(2)
            }
        }
        assertTrue(store.purgeRevision(revision))
        assertFalse(java.nio.file.Files.exists(directory.resolve(revision)))
    }
}
