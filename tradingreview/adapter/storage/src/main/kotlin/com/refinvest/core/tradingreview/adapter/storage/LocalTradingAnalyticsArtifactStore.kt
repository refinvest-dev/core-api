package com.refinvest.core.tradingreview.adapter.storage

import com.refinvest.core.tradingreview.port.AnalyticsInputArtifact
import com.refinvest.core.tradingreview.port.TradingAnalyticsArtifactStore
import com.refinvest.core.tradingreview.port.TradingFailure
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** Private immutable content-addressed storage; grants and runtime locators are separate. */
class LocalTradingAnalyticsArtifactStore(rootPath: Path, private val maximumBytes: Long,
                                        private val encryptionKey: SecretKey) :
    TradingAnalyticsArtifactStore {
    init { require(maximumBytes > 0) }
    private val root = Files.createDirectories(rootPath.toAbsolutePath()).toRealPath()
    private val random = SecureRandom()
    private val magic = byteArrayOf(0x52, 0x49, 0x41, 0x32)

    override fun open(coreReferenceId: String): InputStream {
        val parts = coreReferenceId.split(':')
        require(parts.size == 3 && parts[1] in setOf("ledgerRevisionContent", "reconciliationManifest") &&
            Regex("[0-9a-f]{64}").matches(parts[2]))
        val revision = UUID.fromString(parts[0]).toString()
        val directory = checkedDirectory(root.resolve(revision))
        val file = Files.newInputStream(directory.resolve("${parts[1]}-${parts[2]}.json"))
        try {
            val header = file.readNBytes(magic.size + 12)
            require(header.size == magic.size + 12 &&
                header.copyOfRange(0, magic.size).contentEquals(magic))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey,
                GCMParameterSpec(128, header.copyOfRange(magic.size, header.size)))
            cipher.updateAAD("$revision:${parts[1]}".toByteArray(Charsets.UTF_8))
            return CipherInputStream(file, cipher)
        } catch (failure: Exception) {
            file.close()
            throw failure
        }
    }

    override fun deleteRevision(revisionId: String) {
        val directory = root.resolve(UUID.fromString(revisionId).toString())
        if (!Files.exists(directory)) return
        Files.newDirectoryStream(checkedDirectory(directory)).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                require(Files.isRegularFile(entry) && (Regex(
                    "(?:ledgerRevisionContent|reconciliationManifest)-[0-9a-f]{64}\\.json")
                    .matches(name) || name.startsWith(".building-")))
                Files.deleteIfExists(entry)
            }
        }
        Files.deleteIfExists(directory)
    }

    override fun put(revisionId: String, role: String, mediaType: String,
                     write: (OutputStream) -> Unit): AnalyticsInputArtifact {
        val revision = UUID.fromString(revisionId).toString()
        require(role in setOf("ledgerRevisionContent", "reconciliationManifest"))
        val directory = checkedDirectory(Files.createDirectories(root.resolve(revision)))
        val temporary = Files.createTempFile(directory, ".building-", ".tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            val nonce = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, GCMParameterSpec(128, nonce))
            // AAD uses stable revision/role; the final hash is unknown until serialization ends.
            cipher.updateAAD("$revision:$role".toByteArray(Charsets.UTF_8))
            Files.newOutputStream(temporary).use { file ->
                file.write(magic)
                file.write(nonce)
                CipherOutputStream(file, cipher).use { encrypted ->
                val counted = object : FilterOutputStream(encrypted) {
                    override fun write(value: Int) {
                        if (++size > maximumBytes) throw IllegalStateException("ANALYTICS_INPUT_TOO_LARGE")
                        digest.update(value.toByte())
                        out.write(value)
                    }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        if (length < 0 || size > maximumBytes - length)
                            throw IllegalStateException("ANALYTICS_INPUT_TOO_LARGE")
                        size += length
                        digest.update(bytes, offset, length)
                        out.write(bytes, offset, length)
                    }
                }
                write(counted)
                counted.flush()
                }
            }
            if (size == 0L) throw IllegalStateException("ANALYTICS_INPUT_EMPTY")
            val hash = HexFormat.of().formatHex(digest.digest())
            val target = directory.resolve("$role-$hash.json")
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            Files.newDirectoryStream(directory, "$role-*.json").use { existing ->
                if (existing.any { it != target })
                    throw IllegalStateException("ANALYTICS_ARTIFACT_IDENTITY_CONFLICT")
            }
            try {
                Files.createLink(target, temporary)
            } catch (_: FileAlreadyExistsException) {
                if (plaintextIdentity("$revision:$role:$hash") != (size to hash))
                    throw IllegalStateException("ANALYTICS_ARTIFACT_IDENTITY_CONFLICT")
            }
            return AnalyticsInputArtifact("$revision:$role:$hash", mediaType, size, hash)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun plaintextIdentity(referenceId: String): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        open(referenceId).use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                size += count
                digest.update(buffer, 0, count)
            }
        }
        return size to HexFormat.of().formatHex(digest.digest())
    }

    private fun checkedDirectory(directory: Path): Path {
        require(!Files.isSymbolicLink(directory))
        return directory.toRealPath().also { require(it.startsWith(root)) }
    }
}

@Configuration
class TradingAnalyticsArtifactStorageConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "refinvest.trading-review.analytics-artifacts",
        name = ["root", "keystore-path", "keystore-password", "key-alias"])
    fun localTradingAnalyticsArtifactStore(
        @Value("\${refinvest.trading-review.analytics-artifacts.root}") root: String,
        @Value("\${refinvest.trading-review.analytics-artifacts.max-bytes:104857600}") maximumBytes: Long,
        @Value("\${refinvest.trading-review.analytics-artifacts.keystore-path}") keystorePath: String,
        @Value("\${refinvest.trading-review.analytics-artifacts.keystore-password}") password: String,
        @Value("\${refinvest.trading-review.analytics-artifacts.key-alias}") alias: String,
    ): TradingAnalyticsArtifactStore {
        val secret = password.toCharArray()
        val store = KeyStore.getInstance("PKCS12")
        val key = try {
            Files.newInputStream(Path.of(keystorePath)).use { store.load(it, secret) }
            store.getKey(alias, secret) as? SecretKey
                ?: error("Configured analytics artifact key is unavailable")
        } finally { secret.fill('\u0000') }
        return LocalTradingAnalyticsArtifactStore(Path.of(root), maximumBytes, key)
    }

    @Bean
    @ConditionalOnMissingBean(TradingAnalyticsArtifactStore::class)
    fun unavailableTradingAnalyticsArtifactStore(): TradingAnalyticsArtifactStore =
        object : TradingAnalyticsArtifactStore {
            override fun put(revisionId: String, role: String, mediaType: String,
                             write: (OutputStream) -> Unit): AnalyticsInputArtifact =
                throw TradingFailure("TEMPORARY_DEPENDENCY_FAILURE", 503)
            override fun open(coreReferenceId: String): InputStream =
                throw TradingFailure("TEMPORARY_DEPENDENCY_FAILURE", 503)
            override fun deleteRevision(revisionId: String) = Unit
        }
}
