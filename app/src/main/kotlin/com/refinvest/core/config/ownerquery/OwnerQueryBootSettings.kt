package com.refinvest.core.config.ownerquery

import org.springframework.core.env.Environment
import java.nio.file.Path
import java.time.Duration

class OwnerQueryBootSettings private constructor(
    val enabled: Boolean,
    val snapshotFile: Path?,
    val preparationTimeout: Duration?,
    val drainTimeout: Duration?,
) {
    override fun toString() = "OwnerQueryBootSettings(redacted)"

    companion object {
        fun read(environment: Environment): OwnerQueryBootSettings {
            try {
                fun value(name: String) = environment.getProperty("trading-review.owner-query-runtime.$name")
                val rawEnabled = value("enabled") ?: "false"
                require(rawEnabled.lowercase() in setOf("true", "false"))
                val enabled = rawEnabled.equals("true", true)
                val file = value("snapshot-file")?.takeIf { it.isNotEmpty() }
                val preparation = value("preparation-timeout")?.takeIf { it.isNotEmpty() }
                val drain = value("drain-timeout")?.takeIf { it.isNotEmpty() }
                if (!enabled) {
                    require(file == null && preparation == null && drain == null)
                    return OwnerQueryBootSettings(false, null, null, null)
                }
                require(environment.getProperty("trading-review.owner-query-proxy.enabled").equals("true", true))
                require(environment.getProperty("server.ssl.enabled").equals("true", true))
                fun duration(raw: String?): Duration = Duration.parse(requireNotNull(raw)).also {
                    require(it > Duration.ZERO && it <= Duration.ofSeconds(60))
                }
                return OwnerQueryBootSettings(true, Path.of(requireNotNull(file)), duration(preparation), duration(drain))
            } catch (_: Exception) {
                throw OwnerQueryPreparationException(OwnerQueryPreparationException.Stage.INPUT)
            }
        }
    }
}
