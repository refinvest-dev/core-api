package com.refinvest.core.tradingreview.adapter.storage

import com.refinvest.core.tradingreview.port.TradingFailure
import com.refinvest.core.tradingreview.port.deletion.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/** Shared-volume process locks plus JVM-wide reference counts; handles may close on another thread. */
class LocalTradingArtifactWorkspaces(rootPath: Path) : TradingArtifactWorkspaces {
    internal val root = Files.createDirectories(rootPath.toAbsolutePath().normalize()).toRealPath()
    private val controls = Files.createDirectories(root.resolve("control"))
    private val data = Files.createDirectories(root.resolve("data"))
    init { require(!Files.isSymbolicLink(controls) && !Files.isSymbolicLink(data)) }

    override fun acquire(scope: TradingArtifactScope): LocalLease {
        return acquireKey(key(scope))
    }

    internal fun acquireKey(key: String): LocalLease {
        require(Regex("[0-9a-f]{64}").matches(key))
        val control = state(controls.resolve("$key.lock"))
        synchronized(control) {
            requireLive(key)
            if (control.writing) throw TradingFailure("RESOURCE_DELETED", 410)
            if (control.readers == 0) {
                val channel = FileChannel.open(controls.resolve("$key.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
                try {
                    control.lock = channel.lock(0, Long.MAX_VALUE, true)
                    requireLive(key)
                    control.channel = channel
                } catch (error: Exception) {
                    control.lock?.release()
                    control.lock = null
                    channel.close()
                    throw error
                }
            }
            control.readers++
            return LocalLease(key, control)
        }
    }

    override fun purge(scope: TradingArtifactScope): Boolean {
        val key = key(scope)
        val marker = controls.resolve("$key.deleted")
        try { Files.createFile(marker) } catch (_: FileAlreadyExistsException) { }
        val control = state(controls.resolve("$key.lock"))
        synchronized(control) {
            if (control.readers > 0 || control.writing) return false
            control.writing = true
            try {
                FileChannel.open(controls.resolve("$key.lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
                    val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                        ?: return false
                    lock.use {
                        val directory = data.resolve(key)
                        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                            require(!Files.isSymbolicLink(directory))
                            Files.newDirectoryStream(directory).use { files ->
                                for (file in files) {
                                    require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                                    Files.deleteIfExists(file)
                                }
                            }
                            Files.deleteIfExists(directory)
                        }
                        return !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                    }
                }
            } finally { control.writing = false }
        }
    }

    private fun requireLive(key: String) {
        if (Files.exists(controls.resolve("$key.deleted"), LinkOption.NOFOLLOW_LINKS))
            throw TradingFailure("RESOURCE_DELETED", 410)
    }

    internal fun key(scope: TradingArtifactScope): String {
        val identity = when (scope) {
            is TradingArtifactScope.SessionUploads -> "UPLOADS:${scope.sessionId.value}"
            is TradingArtifactScope.RawSlot -> "RAW:${scope.sessionId.value}:${scope.role.name}"
            is TradingArtifactScope.LegacyArtifact -> "ARTIFACT:${scope.artifactId.value}"
            is TradingArtifactScope.Run -> "RUN:${scope.runId.value}"
            is TradingArtifactScope.Revision -> "REVISION:${scope.revisionId.value}"
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)))
    }

    internal class State {
        var readers = 0
        var writing = false
        var channel: FileChannel? = null
        var lock: FileLock? = null
    }

    override fun cleanupOrphans(olderThan: Instant, limit: Int): Int {
        require(limit > 0)
        var removed = 0
        Files.newDirectoryStream(data).use { directories ->
            for (directory in directories) {
                if (removed >= limit) break
                val key = directory.fileName.toString()
                require(Regex("[0-9a-f]{64}").matches(key) && !Files.isSymbolicLink(directory))
                exclusive(key) {
                    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return@exclusive
                    Files.newDirectoryStream(directory).use { files ->
                        for (file in files) {
                            if (removed >= limit) break
                            require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                            if (Files.getLastModifiedTime(file).toInstant().isBefore(olderThan)) {
                                Files.deleteIfExists(file)
                                removed++
                            }
                        }
                    }
                }
            }
        }
        return removed
    }

    internal fun <T> exclusive(key: String, operation: () -> T): T? {
        require(Regex("[0-9a-f]{64}").matches(key))
        val control = state(controls.resolve("$key.lock"))
        synchronized(control) {
            if (control.readers > 0 || control.writing) return null
            control.writing = true
            try {
                FileChannel.open(controls.resolve("$key.lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
                    val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                        ?: return null
                    lock.use { return operation() }
                }
            } finally { control.writing = false }
        }
    }

    inner class LocalLease internal constructor(internal val key: String, private val state: State) : TradingArtifactLease {
        private var closed = false
        override fun checkActive() = synchronized(state) {
            check(!closed)
            requireLive(key)
        }
        override fun createTemporary(): LocalFile = synchronized(state) {
            checkActive()
            val directory = Files.createDirectories(data.resolve(key))
            require(!Files.isSymbolicLink(directory))
            LocalFile(Files.createTempFile(directory, "copy-", ".tmp"), this)
        }
        override fun close() = synchronized(state) {
            if (!closed) {
                closed = true
                state.readers--
                if (state.readers == 0) {
                    try { state.lock?.release() } finally { state.channel?.close() }
                    state.lock = null
                    state.channel = null
                }
            }
        }
    }

    class LocalFile internal constructor(internal val path: Path, private val lease: TradingArtifactLease) : TradingTemporaryArtifact {
        override fun openInput(): InputStream { lease.checkActive(); return Files.newInputStream(path) }
        override fun openOutput(): OutputStream { lease.checkActive(); return Files.newOutputStream(path) }
        override fun close() { Files.deleteIfExists(path) }
    }

    private companion object {
        val expired = ReferenceQueue<State>()
        val states = ConcurrentHashMap<Path, StateReference>()
        class StateReference(val path: Path, state: State) : WeakReference<State>(state, expired)
        fun state(path: Path): State {
            while (true) {
                val old = expired.poll() as StateReference? ?: break
                states.remove(old.path, old)
            }
            var selected: State? = null
            states.compute(path) { _, reference ->
                val live = reference?.get()
                selected = live ?: State()
                if (live != null) reference else StateReference(path, requireNotNull(selected))
            }
            return requireNotNull(selected)
        }
    }
}
