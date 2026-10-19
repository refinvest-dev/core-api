package com.refinvest.core.tradingreview.adapter.compute

import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

/** Only ciphertext is written here. The per-download key is held in process memory. */
internal object NormalizationResultSpool {
    private val root: Path = Path.of(System.getProperty("java.io.tmpdir"), "refinvest-trading-result-spool")

    fun create(): Path {
        Files.createDirectories(root)
        require(!Files.isSymbolicLink(root))
        return Files.createTempFile(root, "payload-", ".spool")
    }

    fun cleanupOlderThan(cutoff: java.time.Instant): Int = cleanupOlderThan(root, cutoff)

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
class NormalizationResultSpoolSweeper(
    private val clock: Clock,
    @Value("\${refinvest.trading-review.normalization.delivery.spool-orphan-retention:PT24H}")
    private val retention: Duration,
) {
    init {
        require(!retention.isNegative && !retention.isZero && retention <= Duration.ofHours(24))
    }

    @Scheduled(fixedDelayString = "\${refinvest.trading-review.normalization.delivery.spool-sweep-delay-ms:3600000}")
    fun sweep(): Int = NormalizationResultSpool.cleanupOlderThan(clock.instant().minus(retention))
}
