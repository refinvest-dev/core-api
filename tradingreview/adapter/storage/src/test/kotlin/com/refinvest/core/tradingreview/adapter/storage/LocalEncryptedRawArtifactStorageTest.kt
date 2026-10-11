package com.refinvest.core.tradingreview.adapter.storage

import com.refinvest.core.tradingreview.domain.TradingSourceArtifactId
import com.refinvest.core.tradingreview.port.TradingFailure
import org.junit.jupiter.api.io.TempDir
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class LocalEncryptedRawArtifactStorageTest {
    @TempDir lateinit var root: Path
    private val artifactId = TradingSourceArtifactId(42)
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private fun storage() = LocalEncryptedRawArtifactStorage(root, key, "test-v1")

    @Test
    fun encryptsStreamsVerifiesHashAndDeletesIdempotently() {
        val storage = storage()
        val raw = "synthetic,not-financial\n1,2\n".toByteArray()
        val staged = storage.putStaging(ByteArrayInputStream(raw), raw.size.toLong(), artifactId)
        assertEquals(raw.size.toLong(), staged.byteSize)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }, staged.sha256)
        val objectKey = "objects/00000000-0000-0000-0000-000000000042"
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
        assertFailsWith<TradingFailure> { storage.putStaging(ByteArrayInputStream(byteArrayOf()), 2, artifactId) }
        assertFailsWith<TradingFailure> { storage.putStaging(ByteArrayInputStream(byteArrayOf(1,2,3)), 2, artifactId) }
        val broken = object : InputStream() {
            var count = 0
            override fun read(): Int = if (count++ == 0) 65 else throw IOException("interrupted")
        }
        assertFailsWith<IOException> { storage.putStaging(broken, 10, artifactId) }
        Files.list(root.resolve("staging")).use { assertEquals(0L, it.count()) }
    }

    @Test
    fun authenticatesCiphertextAndCleansOrphans() {
        val storage = storage()
        val staged = storage.putStaging(ByteArrayInputStream("safe".toByteArray()), 10, artifactId)
        val key = "objects/00000000-0000-0000-0000-000000000043"
        storage.promote(staged, key)
        val path = root.resolve(key)
        val encrypted = Files.readAllBytes(path)
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        Files.write(path, encrypted)
        assertFailsWith<Exception> { storage.open(key, staged.wrappedDek, staged.keyVersion, artifactId).use { it.readAllBytes() } }
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(Instant.EPOCH))
        assertEquals(0, storage.cleanupOrphans(Instant.now(), 10) { it == key })
        assertEquals(1, storage.cleanupOrphans(Instant.now(), 10) { false })
        assertFalse(storage.exists(key))
        assertFailsWith<IllegalArgumentException> { storage.exists("objects/../unsafe") }
    }

    @Test
    fun keyRotationRetainsOldArtifactReadabilityWhileItsVersionExists() {
        val old = storage()
        val staged = old.putStaging(ByteArrayInputStream("old".toByteArray()), 10, artifactId)
        val objectKey = "objects/00000000-0000-0000-0000-000000000044"
        old.promote(staged, objectKey)
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
        val objectKey = "objects/00000000-0000-0000-0000-000000000045"
        val first = storage.putStaging(ByteArrayInputStream("first".toByteArray()), 10, artifactId)
        storage.promote(first, objectKey)
        val existing = Files.readAllBytes(root.resolve(objectKey))
        val second = storage.putStaging(ByteArrayInputStream("second".toByteArray()), 10, artifactId)
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { storage.promote(second, objectKey) }
        assertContentEquals(existing, Files.readAllBytes(root.resolve(objectKey)))
    }
}
