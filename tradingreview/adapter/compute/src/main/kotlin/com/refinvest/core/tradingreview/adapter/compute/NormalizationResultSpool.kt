package com.refinvest.core.tradingreview.adapter.compute

import com.refinvest.core.tradingreview.port.outbound.storage.workspace.LegacyTradingTemporaryCopies
import com.refinvest.core.tradingreview.port.outbound.storage.workspace.TradingResultSpoolCleanup

import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Only ciphertext is written here. The per-download key is held in process memory. */
internal object NormalizationResultSpool {
    private val root: Path = Path.of(System.getProperty("java.io.tmpdir"), "refinvest-trading-result-spool")

    fun create(): Path {
        Files.createDirectories(root)
        require(!Files.isSymbolicLink(root))
        return Files.createTempFile(root, "payload-", ".spool")
    }

    fun cleanupOlderThan(cutoff: java.time.Instant): Int = cleanupOlderThan(root, cutoff)

    fun absent(): Boolean = !Files.exists(root) || Files.newDirectoryStream(root).use { !it.iterator().hasNext() }

    internal fun cleanupOlderThan(directory: Path, cutoff: java.time.Instant): Int {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return 0
        var removed = 0
        Files.list(directory).use { paths ->
            paths.filter { path ->
                val name = path.fileName.toString()
                name.startsWith("payload-") && name.endsWith(".spool") &&
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                    Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)
            }.forEach { if (Files.deleteIfExists(it)) removed++ }
        }
        return removed
    }
}

@Component
class LegacyNormalizationResultSpoolAdapter : LegacyTradingTemporaryCopies, TradingResultSpoolCleanup {
    override fun cleanupOlderThan(cutoff: java.time.Instant) = NormalizationResultSpool.cleanupOlderThan(cutoff)
    override fun absent() = NormalizationResultSpool.absent()
}
