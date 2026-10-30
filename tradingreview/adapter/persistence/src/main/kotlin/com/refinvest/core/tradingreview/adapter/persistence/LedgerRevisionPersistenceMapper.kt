package com.refinvest.core.tradingreview.adapter.persistence

import com.refinvest.core.shared.kernel.member.MemberId
import com.refinvest.core.tradingreview.domain.LedgerRevision
import com.refinvest.core.tradingreview.domain.service.NormalizationInputCanonicalizer
import com.refinvest.core.tradingreview.domain.valueobject.*
import java.util.UUID
import tools.jackson.databind.json.JsonMapper

/** Pure bounded metadata conversion. No membership query or publication action is performed here. */
internal object LedgerRevisionPersistenceMapper {
    private val json = JsonMapper.builder().build()
    fun toRecord(domain: LedgerRevision): LedgerRevisionRecord {
        val stamp = requireNotNull(domain.publication)
        val metadata = metadata(domain.metadata)
        return LedgerRevisionRecord(domain.id.value.toString(), domain.runId.value, domain.bookId.value,
            domain.importSessionId.value, domain.owner.value, stamp.revisionNumber, domain.inputSetHash,
            domain.inputIdentity.hashSpecificationVersion, String(NormalizationInputCanonicalizer.canonicalize(domain.inputIdentity), Charsets.UTF_8),
            encode(domain.inputIdentity.targetVersionSet.toWireValue()), domain.result.versionPin.schemaVersion,
            domain.result.versionPin.hashSpecificationVersion, domain.result.resultHash, domain.result.ledgerContentHash,
            domain.result.reconciliationManifestHash, metadata.manifestBaseJson, metadata.coverageJson,
            metadata.capabilityJson, metadata.qualityBaseJson, encode(domain.result.counts),
            encode(domain.result.sectionDigests.map { it.toWireValue() }), stamp.publishedAt)
    }

    fun toDomain(record: LedgerRevisionRecord): LedgerRevision {
        val identity = NormalizationInputIdentity.fromWireValue(json.readValue(record.inputIdentityJson, Map::class.java))
        require(record.inputSetHash == NormalizationInputCanonicalizer.hash(identity) &&
            record.inputHashSpecificationVersion == identity.hashSpecificationVersion &&
            json.readTree(record.targetVersionSetJson) == json.valueToTree(identity.targetVersionSet.toWireValue()))
        val counts = json.readValue(record.countsJson, Map::class.java).entries.associate { (key, value) ->
            require(key is String && value is Number && value.toString().matches(Regex("[0-9]+")))
            key to value.toString().toLong()
        }
        val digests = json.readValue(record.sectionDigestsJson, List::class.java).map { value ->
            val item = value as Map<*, *>
            val count = item["itemCount"]
            require(count is Number && count.toString().matches(Regex("[0-9]+")))
            LedgerSectionDigest(item["sectionType"] as String, count.toString().toLong(),
                item["hashSpecificationVersion"] as String, item["sectionHash"] as String)
        }
        return LedgerRevision.restore(LedgerRevisionId(UUID.fromString(record.id)), MemberId(record.ownerId),
            NormalizationRunId(record.runId), TradingBookId(record.bookId), TradingImportSessionId(record.importSessionId),
            identity, LedgerRevisionResult(NormalizationResultVersionPin(record.resultSchemaVersion,
                record.resultHashSpecificationVersion), record.resultHash, record.ledgerContentHash, record.manifestHash, counts, digests),
            metadata(LedgerRevisionMetadata(record.manifestBaseJson, record.coverageJson, record.capabilityJson,
                record.qualityBaseJson)), LedgerPublicationStamp(record.revisionNumber, record.publishedAt))
    }

    private fun metadata(value: LedgerRevisionMetadata): LedgerRevisionMetadata {
        val manifest = json.readTree(value.manifestBaseJson)
        val quality = json.readTree(value.qualityBaseJson)
        require(manifest.isObject && quality.isObject && json.readTree(value.coverageJson).isArray &&
            json.readTree(value.capabilityJson).isArray)
        require(listOf("reviewUnits", "reconciliationMatches", "reconciliationIssues").all { !manifest.has(it) || manifest.path(it).isEmpty })
        require(!quality.has("validationIssues") || quality.path("validationIssues").isEmpty)
        return value
    }

    private fun encode(value: Any?): String {
        fun ordered(item: Any?): Any? = when (item) {
            is Map<*, *> -> item.entries.associate { it.key as String to ordered(it.value) }.toSortedMap()
            is List<*> -> item.map(::ordered)
            else -> item
        }
        return json.writeValueAsString(ordered(value))
    }
}
