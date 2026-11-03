package com.refinvest.core.tradingreview.adapter.storage

import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingArtifactScope

import com.refinvest.core.tradingreview.domain.valueobject.*
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class LocalEncryptedRawArtifactStorageTest {
    @TempDir lateinit var root: Path
    private val sessionId = TradingImportSessionId(7)
    private val artifactId = TradingSourceArtifactId(42)
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private fun storage() = LocalEncryptedRawArtifactStorage(root, key, "test-v1")

    @Test
    fun encryptsStreamsVerifiesHashAndDeletesIdempotently() {
        val storage = storage()
        val raw = "synthetic,not-financial\n1,2\n".toByteArray()
        val staged = storage.preparePublication(storage.putStaging(ByteArrayInputStream(raw), raw.size.toLong(), artifactId, sessionId), TradingArtifactRole.TRADE_HISTORY)
        assertEquals(raw.size.toLong(), staged.byteSize)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }, staged.sha256)
        val objectKey = requireNotNull(staged.publicationKey)
        storage.promote(staged, objectKey)
        assertFalse(storage.exists(staged.stagingKey))
        assertTrue(storage.exists(objectKey))
        assertFalse(Files.readAllBytes(root.resolve(objectKey)).contentEquals(raw))
        assertContentEquals(raw, storage.open(objectKey, staged.wrappedDek, staged.keyVersion, artifactId).use { it.readAllBytes() })
        storage.delete(objectKey)
        storage.delete(objectKey)
        assertFalse(storage.exists(objectKey))
    }

    @Test
    fun rejectsEmptyOversizeAndInterruptedStreamsWithoutPlaintextFiles() {
        val storage = storage()
        assertFailsWith<TradingFailure> { storage.putStaging(ByteArrayInputStream(byteArrayOf()), 2, artifactId, sessionId) }
        assertFailsWith<TradingFailure> { storage.putStaging(ByteArrayInputStream(byteArrayOf(1,2,3)), 2, artifactId, sessionId) }
        val broken = object : InputStream() {
            var count = 0
            override fun read(): Int = if (count++ == 0) 65 else throw IOException("interrupted")
        }
        assertFailsWith<IOException> { storage.putStaging(broken, 10, artifactId, sessionId) }
        Files.walk(root).use { files -> assertEquals(0L, files.filter { it.fileName.toString().endsWith(".tmp") }.count()) }
    }

    @Test
    fun authenticatesCiphertextAndCleansOrphans() {
        val storage = storage()
        val staged = storage.preparePublication(storage.putStaging(ByteArrayInputStream("safe".toByteArray()), 10, artifactId, sessionId), TradingArtifactRole.TRADE_HISTORY)
        val key = requireNotNull(staged.publicationKey)
        storage.promote(staged, key)
        val path = root.resolve(key)
        val encrypted = Files.readAllBytes(path)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        Files.write(path, encrypted)
        assertFailsWith<Exception> { storage.open(key, staged.wrappedDek, staged.keyVersion, artifactId).use { it.readAllBytes() } }
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(Instant.EPOCH))
        assertEquals(0, storage.cleanupOrphans(Instant.now(), 10) { it == key })
        assertEquals(0, storage.cleanupOrphans(Instant.now(), 10) { false })
        storage.completePublication(key)
        assertEquals(1, storage.cleanupOrphans(Instant.now(), 10) { false })
        assertFalse(storage.exists(key))
        assertFailsWith<IllegalArgumentException> { storage.exists("objects/../unsafe") }
    }

    @Test
    fun keyRotationRetainsOldArtifactReadabilityWhileItsVersionExists() {
        val old = storage()
        val staged = old.preparePublication(old.putStaging(ByteArrayInputStream("old".toByteArray()), 10, artifactId, sessionId), TradingArtifactRole.TRADE_HISTORY)
        val objectKey = requireNotNull(staged.publicationKey)
        old.promote(staged, objectKey)
        old.completePublication(objectKey)
        val newKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val rotated = LocalEncryptedRawArtifactStorage(root, newKey, "test-v2") {
            when (it) { "test-v1" -> key; "test-v2" -> newKey; else -> null }
        }
        assertContentEquals("old".toByteArray(), rotated.open(objectKey, staged.wrappedDek,
            staged.keyVersion, artifactId).use { it.readAllBytes() })
        assertFailsWith<IllegalStateException> {
            LocalEncryptedRawArtifactStorage(root, newKey, "test-v2")
                .open(objectKey, staged.wrappedDek, staged.keyVersion, artifactId)
        }
    }

    @Test
    fun promotionCannotOverwriteAnExistingObject() {
        val storage = storage()
        val staged = storage.preparePublication(storage.putStaging(ByteArrayInputStream("new".toByteArray()),
            10, artifactId, sessionId), TradingArtifactRole.TRADE_HISTORY)
        val objectKey = requireNotNull(staged.publicationKey)
        Files.createDirectories(root.resolve(objectKey).parent)
        val existing = "already-published".toByteArray()
        Files.write(root.resolve(objectKey), existing)
        try {
            assertFailsWith<java.nio.file.FileAlreadyExistsException> { storage.promote(staged, objectKey) }
            assertContentEquals(existing, Files.readAllBytes(root.resolve(objectKey)))
        } finally { storage.completePublication(staged.stagingKey) }
    }

    @Test
    fun orphanCleanupSkipsAnActiveStagedUpload() {
        val storage = storage()
        val staged = storage.putStaging(ByteArrayInputStream("pending".toByteArray()), 10, artifactId, sessionId)
        Files.setLastModifiedTime(root.resolve(staged.stagingKey),
            java.nio.file.attribute.FileTime.from(Instant.EPOCH))
        assertEquals(0, storage.cleanupOrphans(Instant.now(), 10) { false })
        storage.delete(staged.stagingKey)
        assertFalse(storage.exists(staged.stagingKey))
    }

    @Test
    fun deletionWaitsForUploadAndFencesFurtherBytes() {
        val storage = storage()
        val reading = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val task = executor.submit<Unit> {
                val stream = object : InputStream() {
                    override fun read(): Int = error("bulk reads only")
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        reading.countDown()
                        check(resume.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        bytes[offset] = 65
                        return 1
                    }
                }
                assertFailsWith<TradingFailure> { storage.putStaging(stream, 10, artifactId, sessionId) }
            }
            assertTrue(reading.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(storage.purgeCopies(TradingArtifactScope.SessionUploads(sessionId)))
            resume.countDown()
            task.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(storage.purgeCopies(TradingArtifactScope.SessionUploads(sessionId)))
            assertFailsWith<TradingFailure> {
                storage.putStaging(ByteArrayInputStream(byteArrayOf(1)), 10, artifactId, sessionId)
            }
        } finally { resume.countDown(); executor.shutdownNow() }
    }

    @Test
    fun deletionDrainsReadersAndFindsAnUncommittedPublishedCopyAfterRestart() {
        val storage = storage()
        val staged = storage.preparePublication(storage.putStaging(ByteArrayInputStream("pending".toByteArray()),
            10, artifactId, sessionId), TradingArtifactRole.TRADE_HISTORY)
        val objectKey = requireNotNull(staged.publicationKey)
        storage.promote(staged, objectKey)
        storage.completePublication(objectKey)
        val reader = storage.open(objectKey, staged.wrappedDek, staged.keyVersion, artifactId)
        val restarted = storage()
        val scope = TradingArtifactScope.RawSlot(sessionId, TradingArtifactRole.TRADE_HISTORY)
        assertFalse(restarted.purgeCopies(scope))
        assertFailsWith<TradingFailure> { reader.read() }
        reader.close()
        assertTrue(restarted.purgeCopies(scope))
        assertFalse(restarted.exists(objectKey))
        assertTrue(restarted.purgeCopies(scope))
    }
}
