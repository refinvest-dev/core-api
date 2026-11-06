package com.refinvest.core.tradingreview.application

import com.refinvest.core.tradingreview.domain.service.NormalizationInputCanonicalizer
import com.refinvest.core.tradingreview.domain.valueobject.NormalizationResultVersionPin
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import tools.jackson.databind.ObjectMapper

object NormalizationInputFingerprint {
    fun canonical(value: Any?, mapper: ObjectMapper): String = when (value) {
        null -> "null"
        is Map<*, *> -> value.entries.sortedWith { left, right ->
            left.key.toString().compareTo(right.key.toString())
        }.joinToString(",", "{", "}") {
            mapper.writeValueAsString(it.key.toString()) + ":" + canonical(it.value, mapper)
        }
        is Iterable<*> -> value.joinToString(",", "[", "]") { canonical(it, mapper) }
        else -> mapper.writeValueAsString(value)
    }

    fun logicalKey(ownerId: Long, inputSetHash: String, deletionGeneration: Long,
                   resultPin: NormalizationResultVersionPin, mapper: ObjectMapper): String {
        return NormalizationInputCanonicalizer.logicalKey(ownerId, inputSetHash, deletionGeneration, resultPin)
    }

    fun dispatchKey(runId: String, logicalKey: String, initialAttemptToken: String,
                    versionSetJson: String, deletionGeneration: Long, mapper: ObjectMapper): String =
        NormalizationInputCanonicalizer.dispatchKey(runId, logicalKey, initialAttemptToken,
            mapper.readTree(versionSetJson).path("versionSetHash").asText(), deletionGeneration)

    fun hashParts(vararg parts: String): String = sha256(parts.joinToString("") {
        val bytes = it.toByteArray(StandardCharsets.UTF_8)
        bytes.size.toString() + ":" + it
    })

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
