package com.refinvest.core.tradingreview.adapter.storage

import com.refinvest.core.tradingreview.domain.valueobject.*
import com.refinvest.core.tradingreview.port.RawArtifactStorage
import com.refinvest.core.tradingreview.port.StagedRawArtifact
import com.refinvest.core.tradingreview.port.TradingFailure
import com.refinvest.core.tradingreview.port.TradingFailureKind
import com.refinvest.core.tradingreview.port.deletion.*
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    private val workspaces: LocalTradingArtifactWorkspaces = LocalTradingArtifactWorkspaces(rootPath.resolve("workspaces")),
    private val keyResolver: (String) -> SecretKey? = { if (it == keyVersion) wrappingKey else null },
) : RawArtifactStorage {
    private val root = Files.createDirectories(rootPath.toAbsolutePath()).toRealPath()
    private val staging = Files.createDirectories(root.resolve("staging"))
    private val objects = Files.createDirectories(root.resolve("objects"))
    private val random = SecureRandom()
    private class Upload(val sessionId: TradingImportSessionId, val artifactId: TradingSourceArtifactId,
                         val uploadLease: LocalTradingArtifactWorkspaces.LocalLease,
                         var file: LocalTradingArtifactWorkspaces.LocalFile) {
        var slotLease: LocalTradingArtifactWorkspaces.LocalLease? = null
        var publicationKey: String? = null
        fun checkActive() { uploadLease.checkActive(); slotLease?.checkActive() }
    }
    private val uploads = ConcurrentHashMap<String, Upload>()

    override fun putStaging(source: InputStream, maxBytes: Long, artifactId: TradingSourceArtifactId,
                            sessionId: TradingImportSessionId): StagedRawArtifact {
        val lease = workspaces.acquire(TradingArtifactScope.SessionUploads(sessionId))
        val file = try { lease.createTemporary() } catch (failure: Exception) { lease.close(); throw failure }
        val context = Upload(sessionId, artifactId, lease, file)
        val key = root.relativize(file.path).joinToString("/")
        uploads[key] = context
        val dek = ByteArray(32).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val wrapped = wrap(dek, artifactId)
        val sha = MessageDigest.getInstance("SHA-256")
        var size = 0L
        var completed = false
        try {
            file.openOutput().use { output ->
                output.write(MAGIC); output.write(nonce)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(128, nonce))
                    updateAAD(artifactId.value.toString().toByteArray(Charsets.US_ASCII))
                }
                CipherOutputStream(output, cipher).use { encrypted ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        context.checkActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        size += count
                        if (size > maxBytes) throw TradingFailure("FILE_TOO_LARGE", TradingFailureKind.PAYLOAD_TOO_LARGE)
                        sha.update(buffer, 0, count)
                        encrypted.write(buffer, 0, count)
                    }
                }
            }
            if (size == 0L) throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
            val hash = sha.digest().toHex()
            val verified = MessageDigest.getInstance("SHA-256")
            var verifiedSize = 0L
            open(key, wrapped, keyVersion, artifactId).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    verified.update(buffer, 0, count); verifiedSize += count
                }
            }
            check(verifiedSize == size && verified.digest().toHex() == hash) { "Storage verification failed" }
            completed = true
            return StagedRawArtifact(key, hash, size, wrapped, keyVersion)
        } finally {
            dek.fill(0)
            if (!completed) completePublication(key)
        }
    }

    override fun preparePublication(staged: StagedRawArtifact, role: TradingArtifactRole): StagedRawArtifact {
        val context = requireNotNull(uploads[staged.stagingKey]) { "Unknown staged upload" }
        context.checkActive()
        check(context.slotLease == null)
        val slot = workspaces.acquire(TradingArtifactScope.RawSlot(context.sessionId, role))
        context.slotLease = slot
        val target = slot.createTemporary()
        context.checkActive()
        Files.move(context.file.path, target.path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        context.file = target
        val key = root.relativize(target.path).joinToString("/")
        val publication = "objects/${slot.key}/${UUID.randomUUID()}"
        context.publicationKey = publication
        uploads[key] = context
        uploads[publication] = context
        return staged.copy(stagingKey = key, publicationKey = publication)
    }

    override fun promote(staged: StagedRawArtifact, finalKey: String) {
        val context = requireNotNull(uploads[staged.stagingKey]) { "Unknown staged upload" }
        require(finalKey == staged.publicationKey && finalKey == context.publicationKey)
        context.checkActive()
        val source = context.file.path
        val target = resolve(finalKey)
        Files.createDirectories(target.parent)
        require(!Files.isSymbolicLink(target.parent))
        val publishing = target.resolveSibling(UUID.randomUUID().toString() + ".publishing")
        try {
            Files.newInputStream(source).use { input ->
                Files.newOutputStream(publishing, StandardOpenOption.CREATE_NEW).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        context.checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(Files.size(source) == Files.size(publishing) && digest(source) == digest(publishing))
            context.checkActive()
            Files.move(publishing, target)
            context.file.close()
        } finally { Files.deleteIfExists(publishing) }
    }

    override fun completePublication(finalKey: String) {
        val context = uploads[finalKey] ?: return
        uploads.entries.removeIf { it.value === context }
        try { context.file.close() }
        finally { try { context.slotLease?.close() } finally { context.uploadLease.close() } }
    }

    override fun open(key: String, wrappedDek: ByteArray, keyVersion: String,
                      artifactId: TradingSourceArtifactId): InputStream {
        val scope = scopeKey(key)
        val lease = if (scope != null) workspaces.acquireKey(scope)
            else workspaces.acquire(TradingArtifactScope.LegacyArtifact(artifactId))
        try {
            val file = Files.newInputStream(resolve(key))
            try {
                check(file.readNBytes(4).contentEquals(MAGIC)) { "Invalid encrypted artifact" }
                val nonce = file.readNBytes(12)
                val dek = unwrap(wrappedDek, keyVersion, artifactId)
                val cipher = try { Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(128, nonce))
                    updateAAD(artifactId.value.toString().toByteArray(Charsets.US_ASCII))
                } } finally { dek.fill(0) }
                return object : FilterInputStream(CipherInputStream(file, cipher)) {
                    override fun read(): Int { lease.checkActive(); return super.read() }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        lease.checkActive(); return super.read(bytes, offset, length)
                    }
                    override fun close() { try { super.close() } finally { lease.close() } }
                }
            } catch (failure: Exception) { file.close(); throw failure }
        } catch (failure: Exception) { lease.close(); throw failure }
    }

    override fun delete(key: String) {
        try { Files.deleteIfExists(resolve(key)) } finally { completePublication(key) }
    }
    override fun exists(key: String) = Files.isRegularFile(resolve(key))

    override fun purgeCopies(scope: TradingArtifactScope): Boolean {
        if (!workspaces.purge(scope)) return false
        val key = workspaces.key(scope)
        val directory = objects.resolve(key)
        if (Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(directory))
            Files.newDirectoryStream(directory).use { files ->
                for (file in files) {
                    require(Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    Files.deleteIfExists(file)
                }
            }
            Files.deleteIfExists(directory)
        }
        // Unattributed pre-upgrade staging must be reconciled by the orphan sweeper, not guessed away.
        return !Files.exists(directory) && Files.newDirectoryStream(staging).use { !it.iterator().hasNext() }
    }

    override fun cleanupOrphans(olderThan: Instant, limit: Int, isReferenced: (String) -> Boolean): Int {
        require(limit > 0)
        var removed = workspaces.cleanupOrphans(olderThan, limit)
        fun removeFiles(directory: Path) {
            Files.newDirectoryStream(directory).use { files ->
                for (file in files) {
                    if (removed >= limit) break
                    if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue
                    if (!Files.getLastModifiedTime(file).toInstant().isBefore(olderThan)) continue
                    val key = root.relativize(file).joinToString("/")
                    if (uploads.containsKey(key) || isReferenced(key)) continue
                    Files.deleteIfExists(file); removed++
                }
            }
        }
        removeFiles(staging)
        Files.newDirectoryStream(objects).use { entries ->
            for (entry in entries) {
                if (removed >= limit) break
                if (Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    val key = entry.fileName.toString()
                    require(Regex("[0-9a-f]{64}").matches(key))
                    workspaces.exclusive(key) { removeFiles(entry) }
                } else if (Files.isRegularFile(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    // Legacy flat objects; all old writers are drained before activation.
                    val key = root.relativize(entry).joinToString("/")
                    if (Files.getLastModifiedTime(entry).toInstant().isBefore(olderThan) && !isReferenced(key)) {
                        Files.deleteIfExists(entry); removed++
                    }
                }
            }
        }
        return removed
    }

    private fun scopeKey(key: String): String? {
        val parts = key.split('/')
        return when {
            parts.size == 3 && parts[0] == "objects" -> parts[1]
            parts.size == 4 && parts[0] == "workspaces" && parts[1] == "data" -> parts[2]
            else -> null
        }?.takeIf { Regex("[0-9a-f]{64}").matches(it) }
    }

    private fun wrap(dek: ByteArray, id: TradingSourceArtifactId): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, wrappingKey, GCMParameterSpec(128, nonce))
            updateAAD(id.value.toString().toByteArray(Charsets.US_ASCII))
        }
        return nonce + cipher.doFinal(dek)
    }

    private fun unwrap(wrapped: ByteArray, version: String, id: TradingSourceArtifactId): ByteArray =
        unwrap(wrapped, id, keyResolver(version) ?: error("Wrapping key is unavailable"))

    private fun digest(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
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
        val KEY = Regex("(?:(?:staging|objects)/[0-9a-fA-F-]{36}|objects/[0-9a-f]{64}/[0-9a-fA-F-]{36}|workspaces/data/[0-9a-f]{64}/copy-[A-Za-z0-9-]+\\.tmp)")
    }
}

@Configuration
class RawArtifactStorageConfiguration {
    @Bean
    fun tradingArtifactWorkspaces(@Value("\${refinvest.trading-review.raw.root:\${java.io.tmpdir}/refinvest-trading-raw}") root: String) =
        LocalTradingArtifactWorkspaces(Path.of(root).resolve("workspaces"))

    @Bean
    @ConditionalOnProperty(prefix = "refinvest.trading-review.raw", name = ["root", "keystore-path", "keystore-password", "key-alias"])
    fun localRawArtifactStorage(
        @Value("\${refinvest.trading-review.raw.root}") root: String,
        @Value("\${refinvest.trading-review.raw.keystore-path}") keyStorePath: String,
        @Value("\${refinvest.trading-review.raw.keystore-password}") password: String,
        @Value("\${refinvest.trading-review.raw.key-alias}") alias: String,
        workspaces: LocalTradingArtifactWorkspaces,
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
        return LocalEncryptedRawArtifactStorage(Path.of(root), key, alias, workspaces, keys::get)
    }

    @Bean
    @ConditionalOnMissingBean(RawArtifactStorage::class)
    fun unavailableRawArtifactStorage(): RawArtifactStorage = object : RawArtifactStorage {
        private fun unavailable(): Nothing = throw TradingFailure("TEMPORARY_DEPENDENCY_FAILURE", TradingFailureKind.DEPENDENCY_UNAVAILABLE)
        override fun putStaging(source: InputStream, maxBytes: Long, artifactId: TradingSourceArtifactId, sessionId: TradingImportSessionId): StagedRawArtifact = unavailable()
        override fun preparePublication(staged: StagedRawArtifact, role: TradingArtifactRole): StagedRawArtifact = unavailable()
        override fun purgeCopies(scope: TradingArtifactScope): Boolean = unavailable()
        override fun promote(staged: StagedRawArtifact, finalKey: String) = unavailable()
        override fun completePublication(finalKey: String) = unavailable()
        override fun open(key: String, wrappedDek: ByteArray, keyVersion: String, artifactId: TradingSourceArtifactId): InputStream = unavailable()
        override fun delete(key: String) = unavailable()
        override fun exists(key: String): Boolean = unavailable()
        override fun cleanupOrphans(olderThan: Instant, limit: Int, isReferenced: (String) -> Boolean): Int = unavailable()
    }
}
