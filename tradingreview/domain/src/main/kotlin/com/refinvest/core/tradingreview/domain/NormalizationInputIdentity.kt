package com.refinvest.core.tradingreview.domain

import java.time.ZoneId
import java.util.Collections

/** ADR-065 V1 logical input. Transport grants, attempts and deletion state do not belong here. */
class NormalizationInputIdentity private constructor(
    val hashSpecificationVersion: String,
    val tradingBookId: String,
    sourceImportSessionIds: List<String>,
    artifactsByRole: Map<String, NormalizationArtifactIdentity>,
    val sourceTimezone: String,
    val reviewTimezone: String,
    val declaredProduct: NormalizationDeclaredProduct,
    val declaredPositionMode: String,
    val targetVersionSet: NormalizationTargetVersionSet,
) {
    val sourceImportSessionIds: List<String> = Collections.unmodifiableList(sourceImportSessionIds.toList())
    val artifactsByRole: Map<String, NormalizationArtifactIdentity> =
        Collections.unmodifiableMap(LinkedHashMap(artifactsByRole))

    fun toWireValue(): Map<String, Any> = mapOf(
        "hashSpecificationVersion" to hashSpecificationVersion,
        "tradingBookId" to tradingBookId,
        "sourceImportSessionIds" to sourceImportSessionIds,
        "artifactsByRole" to artifactsByRole.mapValues { it.value.toWireValue() },
        "sourceTimezone" to sourceTimezone,
        "reviewTimezone" to reviewTimezone,
        "declaredProduct" to declaredProduct.toWireValue(),
        "declaredPositionMode" to declaredPositionMode,
        "targetVersionSet" to targetVersionSet.toWireValue(),
    )

    companion object {
        const val V1 = "TRADING_INPUT_SET_HASH_V1"
        private val ROLES = setOf("TRADE_HISTORY", "POSITION_HISTORY")
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun fromPinnedInput(input: NormalizationInput, pinnedVersionSet: Map<*, *>): NormalizationInputIdentity {
            require(input.artifacts.size == 2) { "Normalization input requires two artifacts" }
            val artifacts = input.artifacts.associate { artifact ->
                artifact.role.name to mapOf("artifactId" to artifact.artifactId.value.toString(),
                    "sha256" to artifact.sha256)
            }
            require(artifacts.size == 2) { "Duplicate artifact role" }
            return fromWireValue(mapOf(
                "hashSpecificationVersion" to V1,
                "tradingBookId" to input.tradingBookId.value.toString(),
                "sourceImportSessionIds" to listOf(input.importSessionId.value.toString()),
                "artifactsByRole" to artifacts,
                "sourceTimezone" to input.sourceTimezone,
                "reviewTimezone" to input.reviewTimezone,
                "declaredProduct" to mapOf("venue" to input.venue,
                    "productFamily" to input.productFamily, "settlementAsset" to input.settlementAsset),
                "declaredPositionMode" to input.positionMode,
                // The session VersionSet may also contain analytics; normalization omits it.
                "targetVersionSet" to mapOf("normalizer" to pinnedVersionSet["normalizer"],
                    "reconstruction" to pinnedVersionSet["reconstruction"],
                    "versionSetHash" to pinnedVersionSet["versionSetHash"]),
            ))
        }

        fun fromWireValue(value: Any?): NormalizationInputIdentity {
            val root = objectValue(value, setOf("hashSpecificationVersion", "tradingBookId",
                "sourceImportSessionIds", "artifactsByRole", "sourceTimezone", "reviewTimezone",
                "declaredProduct", "declaredPositionMode", "targetVersionSet"))
            val specification = string(root, "hashSpecificationVersion")
            require(specification == V1) { "Unsupported input hash specification" }
            val sessions = root["sourceImportSessionIds"] as? List<*>
                ?: throw IllegalArgumentException("Source session IDs required")
            require(sessions.size == 1 && sessions.all { it is String && it.isNotEmpty() } &&
                sessions.toSet().size == sessions.size) { "Invalid source session IDs" }
            val artifactValues = objectValue(root["artifactsByRole"], ROLES)
            val artifacts = artifactValues.mapValues { (_, raw) ->
                val item = objectValue(raw, setOf("artifactId", "sha256"))
                NormalizationArtifactIdentity(string(item, "artifactId"), sha(item, "sha256"))
            }
            require(artifacts.values.map { it.artifactId }.toSet().size == artifacts.size) {
                "Duplicate artifact identity"
            }
            val productValue = objectValue(root["declaredProduct"],
                setOf("venue", "productFamily", "settlementAsset"))
            val product = NormalizationDeclaredProduct(string(productValue, "venue"),
                string(productValue, "productFamily"), string(productValue, "settlementAsset"))
            require(product.venue == "BINANCE" && product.productFamily == "LINEAR_PERPETUAL" &&
                product.settlementAsset == "USDT" && string(root, "declaredPositionMode") == "ONE_WAY") {
                "Unsupported product declaration"
            }
            val sourceTimezone = string(root, "sourceTimezone")
            val reviewTimezone = string(root, "reviewTimezone")
            require(sourceTimezone in ZoneId.getAvailableZoneIds() &&
                reviewTimezone in ZoneId.getAvailableZoneIds()) { "Invalid timezone declaration" }
            return NormalizationInputIdentity(specification, string(root, "tradingBookId"),
                sessions.map { it as String }.sorted(), artifacts, sourceTimezone, reviewTimezone,
                product, "ONE_WAY", NormalizationTargetVersionSet.fromWireValue(root["targetVersionSet"]))
        }

        internal fun objectValue(value: Any?, keys: Set<String>): Map<String, Any?> {
            val raw = value as? Map<*, *> ?: throw IllegalArgumentException("Invalid identity object")
            require(raw.keys.all { it is String } && raw.keys == keys) { "Invalid identity fields" }
            return raw.entries.associate { (key, item) -> key as String to item }
        }

        internal fun string(value: Map<String, Any?>, key: String): String =
            (value[key] as? String)?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("Required identity value missing")

        internal fun sha(value: Map<String, Any?>, key: String): String = string(value, key).also {
            require(SHA256.matches(it)) { "Invalid SHA-256 identity" }
        }

        internal fun stringMap(value: Any?, expectedKeys: Set<String>? = null): Map<String, String> {
            val raw = value as? Map<*, *> ?: throw IllegalArgumentException("Invalid identity map")
            require(raw.keys.all { it is String && it.isNotEmpty() } &&
                (expectedKeys == null || raw.keys == expectedKeys)) { "Invalid identity map keys" }
            return Collections.unmodifiableMap(raw.entries.associate { (key, item) ->
                val text = item as? String ?: throw IllegalArgumentException("Invalid identity map value")
                require(text.isNotEmpty()) { "Empty identity map value" }
                key as String to text
            })
        }
    }
}

data class NormalizationArtifactIdentity(val artifactId: String, val sha256: String) {
    fun toWireValue(): Map<String, String> = mapOf("artifactId" to artifactId, "sha256" to sha256)
}

data class NormalizationDeclaredProduct(val venue: String, val productFamily: String,
                                         val settlementAsset: String) {
    fun toWireValue(): Map<String, String> = mapOf("venue" to venue,
        "productFamily" to productFamily, "settlementAsset" to settlementAsset)
}

class NormalizationTargetVersionSet private constructor(
    val normalizer: NormalizationNormalizerVersionSet,
    val reconstruction: NormalizationReconstructionVersionSet,
    val versionSetHash: String,
) {
    fun toWireValue(): Map<String, Any> = mapOf("normalizer" to normalizer.toWireValue(),
        "reconstruction" to reconstruction.toWireValue(), "versionSetHash" to versionSetHash)

    companion object {
        fun fromWireValue(value: Any?): NormalizationTargetVersionSet {
            val fields = NormalizationInputIdentity.objectValue(value,
                setOf("normalizer", "reconstruction", "versionSetHash"))
            return NormalizationTargetVersionSet(
                NormalizationNormalizerVersionSet.fromWireValue(fields["normalizer"]),
                NormalizationReconstructionVersionSet.fromWireValue(fields["reconstruction"]),
                NormalizationInputIdentity.sha(fields, "versionSetHash"))
        }
    }
}

class NormalizationNormalizerVersionSet private constructor(
    val sourceDialectVersionsByArtifactRole: Map<String, String>,
    val adapterVersion: String,
    val normalizerVersion: String,
    val canonicalSchemaVersion: String,
    val implementationDigest: String,
    val versionSetHash: String,
) {
    fun toWireValue(): Map<String, Any> = mapOf(
        "sourceDialectVersionsByArtifactRole" to sourceDialectVersionsByArtifactRole,
        "adapterVersion" to adapterVersion, "normalizerVersion" to normalizerVersion,
        "canonicalSchemaVersion" to canonicalSchemaVersion,
        "implementationDigest" to implementationDigest, "versionSetHash" to versionSetHash)

    companion object {
        fun fromWireValue(value: Any?): NormalizationNormalizerVersionSet {
            val fields = NormalizationInputIdentity.objectValue(value, setOf(
                "sourceDialectVersionsByArtifactRole", "adapterVersion", "normalizerVersion",
                "canonicalSchemaVersion", "implementationDigest", "versionSetHash"))
            return NormalizationNormalizerVersionSet(
                NormalizationInputIdentity.stringMap(fields["sourceDialectVersionsByArtifactRole"],
                    setOf("TRADE_HISTORY", "POSITION_HISTORY")),
                NormalizationInputIdentity.string(fields, "adapterVersion"),
                NormalizationInputIdentity.string(fields, "normalizerVersion"),
                NormalizationInputIdentity.string(fields, "canonicalSchemaVersion"),
                NormalizationInputIdentity.string(fields, "implementationDigest"),
                NormalizationInputIdentity.sha(fields, "versionSetHash"))
        }
    }
}

class NormalizationReconstructionVersionSet private constructor(
    val instrumentTermsVersionByInstrument: Map<String, String>,
    val reconstructionPolicyVersion: String,
    val reconciliationVersion: String,
    val reviewUnitSchemaVersion: String,
    val evidenceSchemaVersion: String,
    val implementationDigest: String,
    val versionSetHash: String,
) {
    fun toWireValue(): Map<String, Any> = mapOf(
        "instrumentTermsVersionByInstrument" to instrumentTermsVersionByInstrument,
        "reconstructionPolicyVersion" to reconstructionPolicyVersion,
        "reconciliationVersion" to reconciliationVersion,
        "reviewUnitSchemaVersion" to reviewUnitSchemaVersion,
        "evidenceSchemaVersion" to evidenceSchemaVersion,
        "implementationDigest" to implementationDigest, "versionSetHash" to versionSetHash)

    companion object {
        fun fromWireValue(value: Any?): NormalizationReconstructionVersionSet {
            val fields = NormalizationInputIdentity.objectValue(value, setOf(
                "instrumentTermsVersionByInstrument", "reconstructionPolicyVersion",
                "reconciliationVersion", "reviewUnitSchemaVersion", "evidenceSchemaVersion",
                "implementationDigest", "versionSetHash"))
            return NormalizationReconstructionVersionSet(
                NormalizationInputIdentity.stringMap(fields["instrumentTermsVersionByInstrument"]),
                NormalizationInputIdentity.string(fields, "reconstructionPolicyVersion"),
                NormalizationInputIdentity.string(fields, "reconciliationVersion"),
                NormalizationInputIdentity.string(fields, "reviewUnitSchemaVersion"),
                NormalizationInputIdentity.string(fields, "evidenceSchemaVersion"),
                NormalizationInputIdentity.string(fields, "implementationDigest"),
                NormalizationInputIdentity.sha(fields, "versionSetHash"))
        }
    }
}
