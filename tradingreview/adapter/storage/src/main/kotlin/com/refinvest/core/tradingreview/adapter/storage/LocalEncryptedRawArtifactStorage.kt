package com.refinvest.core.tradingreview.adapter.storage

import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import com.refinvest.core.tradingreview.port.RawArtifactStorage
import com.refinvest.core.tradingreview.port.StagedRawArtifact
import com.refinvest.core.tradingreview.port.TradingFailure
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class LocalEncryptedRawArtifactStorage(
    rootPath: Path,
    private val wrappingKey: SecretKey,
    private val keyVersion: String,
    private val keyResolver: (String) -> SecretKey? = { if (it == keyVersion) wrappingKey else null },
) : RawArtifactStorage {
    private val root = Files.createDirectories(rootPath.toAbsolutePath()).toRealPath()
    private val staging = Files.createDirectories(root.resolve("staging"))
    private val objects = Files.createDirectories(root.resolve("objects"))
    private val random = SecureRandom()
    private val activeStaging = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val activePublications = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun putStaging(source: InputStream, maxBytes: Long, artifactId: TradingSourceArtifactId): StagedRawArtifact {
        val key = "staging/" + UUID.randomUUID()
        val path = resolve(key)
        activeStaging.add(key)
        val dek = ByteArray(32).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val wrapped = wrap(dek, artifactId)
        val sha = MessageDigest.getInstance("SHA-256")
        var size = 0L
        var completed = false
        try {
            Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { file ->
                file.write(MAGIC)
                file.write(nonce)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(128, nonce))
                    updateAAD(artifactId.value.toString().toByteArray(Charsets.US_ASCII))
                }
                CipherOutputStream(file, cipher).use { encrypted ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        size += count
                        if (size > maxBytes) throw TradingFailure("FILE_TOO_LARGE", 413)
                        sha.update(buffer, 0, count)
                        encrypted.write(buffer, 0, count)
                    }
                }
            }
            if (size == 0L) throw TradingFailure("REQUEST_INVALID", 400)
            val hash = sha.digest().toHex()
            val staged = StagedRawArtifact(key, hash, size, wrapped, keyVersion)
            val verified = MessageDigest.getInstance("SHA-256")
            var verifiedSize = 0L
            open(key, wrapped, keyVersion, artifactId).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    verified.update(buffer, 0, count)
                    verifiedSize += count
                }
            }
            if (verifiedSize != size || verified.digest().toHex() != hash) throw IllegalStateException("Storage verification failed")
            completed = true
            return staged
        } catch (failure: Exception) {
            Files.deleteIfExists(path)
            throw failure
        } finally {
            dek.fill(0)
            if (!completed) activeStaging.remove(key)
        }
    }

    override fun promote(staged: StagedRawArtifact, finalKey: String) {
        require(staged.stagingKey.startsWith("staging/") && finalKey.startsWith("objects/"))
        val source = resolve(staged.stagingKey)
        val target = resolve(finalKey)
        val publishing = resolve("objects/" + UUID.randomUUID())
        try {
            Files.copy(source, publishing)
            if (Files.size(source) != Files.size(publishing) || digest(source) != digest(publishing))
                throw IllegalStateException("Encrypted object copy failed verification")
            Files.move(publishing, target)
            activePublications.add(finalKey)
            Files.delete(source)
            activeStaging.remove(staged.stagingKey)
        } catch (failure: Exception) {
            Files.deleteIfExists(publishing)
            throw failure
        }
    }

    override fun completePublication(finalKey: String) { activePublications.remove(finalKey) }

    private fun digest(path: Path): String {
        val hash = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().toHex()
    }

    override fun open(key: String, wrappedDek: ByteArray, keyVersion: String,
                      artifactId: TradingSourceArtifactId): InputStream {
        val unwrappingKey = keyResolver(keyVersion) ?: throw IllegalStateException("Key version unavailable")
        val file = Files.newInputStream(resolve(key), StandardOpenOption.READ)
        try {
            val header = file.readNBytes(MAGIC.size + 12)
            require(header.size == MAGIC.size + 12 && header.take(MAGIC.size).toByteArray().contentEquals(MAGIC)) {
                "Invalid encrypted object"
            }
            val nonce = header.copyOfRange(MAGIC.size, header.size)
            val dek = unwrap(wrappedDek, artifactId, unwrappingKey)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(artifactId.value.toString().toByteArray(Charsets.US_ASCII))
            }
            dek.fill(0)
            return CipherInputStream(file, cipher)
        } catch (failure: Exception) {
            file.close()
            throw failure
        }
    }

    override fun delete(key: String) {
        Files.deleteIfExists(resolve(key))
        activeStaging.remove(key)
        activePublications.remove(key)
    }
    override fun exists(key: String) = Files.isRegularFile(resolve(key))

    override fun cleanupOrphans(olderThan: Instant, limit: Int, isReferenced: (String) -> Boolean): Int {
        var removed = 0
        for ((name, directory) in listOf("staging" to staging, "objects" to objects)) {
            Files.list(directory).use { stream ->
                for (path in stream.sorted().toList()) {
                    if (removed >= limit) break
                    if (!Files.isRegularFile(path) || !Files.getLastModifiedTime(path).toInstant().isBefore(olderThan)) continue
                    val key = name + "/" + path.fileName.toString()
                    if (activeStaging.contains(key) || activePublications.contains(key) ||
                        (name == "objects" && isReferenced(key))) continue
                    Files.deleteIfExists(path)
                    removed++
                }
            }
        }
        return removed
    }

    private fun wrap(dek: ByteArray, id: TradingSourceArtifactId): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, wrappingKey, GCMParameterSpec(128, nonce))
            updateAAD(id.value.toString().toByteArray(Charsets.US_ASCII))
        }
        return nonce + cipher.doFinal(dek)
    }

    private fun unwrap(wrapped: ByteArray, id: TradingSourceArtifactId, key: SecretKey): ByteArray {
        require(wrapped.size >= 12 + 16 + 32) { "Invalid wrapped key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
            updateAAD(id.value.toString().toByteArray(Charsets.US_ASCII))
        }
        return cipher.doFinal(wrapped.copyOfRange(12, wrapped.size))
    }

    private fun resolve(key: String): Path {
        require(KEY.matches(key)) { "Invalid opaque key" }
        val path = root.resolve(key).normalize()
        require(path.startsWith(root)) { "Invalid opaque key" }
        return path
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private companion object {
        val MAGIC = byteArrayOf(0x52, 0x49, 0x41, 0x31)
        val KEY = Regex("(?:staging|objects)/[0-9a-fA-F-]{36}")
    }
}

@Configuration
class RawArtifactStorageConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "refinvest.trading-review.raw", name = ["root", "keystore-path", "keystore-password", "key-alias"])
    fun localRawArtifactStorage(
        @Value("\${refinvest.trading-review.raw.root}") root: String,
        @Value("\${refinvest.trading-review.raw.keystore-path}") keyStorePath: String,
        @Value("\${refinvest.trading-review.raw.keystore-password}") password: String,
        @Value("\${refinvest.trading-review.raw.key-alias}") alias: String,
    ): RawArtifactStorage {
        val store = KeyStore.getInstance("PKCS12")
        val secret = password.toCharArray()
        val keys = try {
            Files.newInputStream(Path.of(keyStorePath)).use { store.load(it, secret) }
            store.aliases().asSequence().mapNotNull { version ->
                (store.getKey(version, secret) as? SecretKey)?.let { version to it }
            }.toMap()
        } finally { secret.fill('\u0000') }
        val key = keys[alias] ?: error("Configured wrapping key is unavailable")
        return LocalEncryptedRawArtifactStorage(Path.of(root), key, alias, keys::get)
    }

    @Bean
    @ConditionalOnMissingBean(RawArtifactStorage::class)
    fun unavailableRawArtifactStorage(): RawArtifactStorage = object : RawArtifactStorage {
        private fun unavailable(): Nothing = throw TradingFailure("TEMPORARY_DEPENDENCY_FAILURE", 503)
        override fun putStaging(source: InputStream, maxBytes: Long, artifactId: TradingSourceArtifactId): StagedRawArtifact = unavailable()
        override fun promote(staged: StagedRawArtifact, finalKey: String) = unavailable()
        override fun completePublication(finalKey: String) = unavailable()
        override fun open(key: String, wrappedDek: ByteArray, keyVersion: String, artifactId: TradingSourceArtifactId): InputStream = unavailable()
        override fun delete(key: String) = unavailable()
        override fun exists(key: String): Boolean = unavailable()
        override fun cleanupOrphans(olderThan: Instant, limit: Int, isReferenced: (String) -> Boolean): Int = unavailable()
    }
}
