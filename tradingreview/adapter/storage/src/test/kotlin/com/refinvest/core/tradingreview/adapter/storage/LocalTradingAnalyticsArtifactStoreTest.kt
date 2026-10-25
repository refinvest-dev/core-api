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
}
