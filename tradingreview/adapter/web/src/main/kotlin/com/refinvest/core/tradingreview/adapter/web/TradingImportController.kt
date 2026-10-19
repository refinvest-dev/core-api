package com.refinvest.core.tradingreview.adapter.web

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.port.*
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.Instant

data class CreateTradingImportSessionRequest(
    @field:NotBlank val sourceTimezone: String,
    @field:NotBlank val reviewTimezone: String,
    val declaredPositionMode: TradingPositionMode,
    val dataPolicyVersion: String? = null,
    val clientRequestReference: String? = null,
)

data class TradingSourceArtifactResponse(
    val id: String, val importSessionId: String, val role: TradingArtifactRole, val filename: String?,
    val contentType: String, val byteSize: Long, val sha256: String, val dialectCandidate: String,
    val detectedDialect: String?, val schemaFingerprint: String?, val uploadStatus: String,
    val retentionStatus: TradingArtifactRetentionStatus, val rawReparseAvailable: Boolean,
    val coverage: Any?, val rawExpiresAt: Instant, val uploadedAt: Instant, val deletedAt: Instant?,
) {
    companion object {
        fun from(a: TradingSourceArtifact, now: Instant) = TradingSourceArtifactResponse(
            a.id.value.toString(), a.sessionId.value.toString(), a.role, a.filename, a.contentType,
            a.byteSize, a.sha256, a.dialectCandidate, null, null, "UPLOADED", a.retentionStatus,
            a.rawReparseAvailable(now), null, a.rawExpiresAt, a.uploadedAt, a.deletedAt)
    }
}

data class TradingImportSessionResponse(
    val id: String, val tradingBookId: String, val sourceTimezone: String, val reviewTimezone: String,
    val declaredPositionMode: TradingPositionMode, val status: TradingImportStatus,
    val artifacts: List<TradingSourceArtifactResponse>, val dataPolicyVersion: String,
    val retentionPolicy: String, val maskingPolicyVersion: String, val evidenceRetention: String,
    val deletionPolicyVersion: String, val versions: JsonNode, val createdAt: Instant,
    val validationStartedAt: Instant?, val uploadedArtifactRoles: List<TradingArtifactRole>,
    val missingArtifactRoles: List<TradingArtifactRole>, val canValidate: Boolean,
    val failure: TradingImportFailure?, val completedAt: Instant?,
    val qualitySummary: JsonNode? = null, val createdLedgerRevisionId: String? = null,
) {
    companion object {
        fun from(view: TradingImportSessionView, now: Instant, mapper: ObjectMapper): TradingImportSessionResponse {
            val session = view.session
            val uploaded = view.artifacts.filter { it.retentionStatus == TradingArtifactRetentionStatus.ACTIVE }
                .map { it.role }.sortedBy { it.name }
            val missing = session.missingArtifactRoles(view.artifacts).sortedBy { it.name }
            val published = view.published
            val qualitySummary = published?.let {
                val report = mapper.readValue(it.qualityReportJson, Map::class.java)
                    .entries.associate { entry -> entry.key.toString() to entry.value }
                val capabilities = mapper.readValue(it.capabilityJson, List::class.java)
                mapper.readTree(mapper.writeValueAsString(report + ("capability" to capabilities)))
            }
            return TradingImportSessionResponse(session.id.value.toString(), session.bookId.value.toString(),
                session.sourceTimezone, session.reviewTimezone, session.declaredPositionMode, session.status,
                view.artifacts.map { TradingSourceArtifactResponse.from(it, now) }, session.dataPolicyVersion,
                session.retentionPolicy, session.maskingPolicyVersion, session.evidenceRetention,
                session.deletionPolicyVersion, mapper.readTree(session.versionSetJson), session.createdAt,
                session.validationStartedAt,
                uploaded, missing, session.canValidate(view.artifacts), session.failure,
                published?.publishedAt ?: session.failure?.occurredAt,
                qualitySummary, published?.revisionId)
        }
    }
}

@RestController
@RequestMapping("/trading-books/{tradingBookId}/imports")
class TradingBookImportController(private val imports: TradingImportUseCase, private val mapper: ObjectMapper,
                                  private val clock: Clock) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@PathVariable tradingBookId: Long, @RequestHeader("Idempotency-Key") key: String,
               @Valid @RequestBody request: CreateTradingImportSessionRequest): TradingImportSessionResponse {
        val session = imports.create(TradingBookId(tradingBookId), request.sourceTimezone, request.reviewTimezone,
            request.declaredPositionMode, request.dataPolicyVersion, request.clientRequestReference, key)
        return TradingImportSessionResponse.from(imports.get(session.id), clock.instant(), mapper)
    }

    @GetMapping
    fun list(@PathVariable tradingBookId: Long, @RequestParam(defaultValue = "0") page: Int,
             @RequestParam(defaultValue = "20") size: Int): TradingPageResponse<TradingImportSessionResponse> {
        val result = imports.list(TradingBookId(tradingBookId), page, size)
        return TradingPageResponse(result.items.map { TradingImportSessionResponse.from(it, clock.instant(), mapper) },
            result.page, result.size, result.total)
    }
}

@RestController
@RequestMapping("/trading-import-sessions")
class TradingImportSessionController(private val imports: TradingImportUseCase,
                                     private val normalization: TradingNormalizationUseCase,
                                     private val mapper: ObjectMapper,
                                     private val clock: Clock) {
    @GetMapping("/{importSessionId}")
    fun get(@PathVariable importSessionId: Long) = TradingImportSessionResponse.from(
        imports.get(TradingImportSessionId(importSessionId)), clock.instant(), mapper)

    @PostMapping("/{importSessionId}/validation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun validate(@PathVariable importSessionId: Long, @RequestHeader("Idempotency-Key") key: String,
                 @RequestBody request: JsonNode): TradingImportSessionResponse {
        if (!request.isObject || request.size() > 1 ||
            (request.size() == 1 && !request.has("targetVersionSet")))
            throw TradingFailure("REQUEST_INVALID", 400)
        normalization.validate(TradingImportSessionId(importSessionId),
            request.path("targetVersionSet").takeIf { !it.isMissingNode && !it.isNull }?.toString(), key)
        return TradingImportSessionResponse.from(imports.get(TradingImportSessionId(importSessionId)),
            clock.instant(), mapper)
    }

    @PostMapping("/{importSessionId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun retry(@PathVariable importSessionId: Long, @RequestHeader("Idempotency-Key") key: String):
        TradingImportSessionResponse {
        normalization.retry(TradingImportSessionId(importSessionId), key)
        return TradingImportSessionResponse.from(imports.get(TradingImportSessionId(importSessionId)),
            clock.instant(), mapper)
    }

    @DeleteMapping("/{importSessionId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun delete(@PathVariable importSessionId: Long, @RequestHeader("Idempotency-Key") key: String) =
        TradingDeletionResponse.from(imports.delete(TradingImportSessionId(importSessionId), key))

    @PostMapping("/{importSessionId}/artifacts", consumes = ["multipart/form-data"])
    fun upload(@PathVariable importSessionId: Long, @RequestHeader("Idempotency-Key") key: String,
               request: HttpServletRequest): ResponseEntity<TradingSourceArtifactResponse> {
        try {
            val reader = StreamingMultipartReader(request.inputStream, request.contentType)
            val first = reader.nextPart() ?: throw TradingFailure("REQUEST_INVALID", 400)
            var metadata: TradingUploadMetadata? = null
            val file = if (first.name == "metadata") {
                metadata = readMetadata(first)
                reader.nextPart() ?: throw TradingFailure("REQUEST_INVALID", 400)
            } else first
            if (file.name != "file") throw TradingFailure("REQUEST_INVALID", 400)
            val result = imports.uploadDeferred(TradingImportSessionId(importSessionId), file.body, key) {
                val parsed = metadata ?: (reader.nextPart() ?: throw TradingFailure("REQUEST_INVALID", 400))
                    .let(::readMetadata)
                if (reader.nextPart() != null) throw TradingFailure("REQUEST_INVALID", 400)
                if (file.contentType?.substringBefore(';') != parsed.contentType)
                    throw TradingFailure("UNSUPPORTED_MEDIA_TYPE", 415)
                parsed
            }
            return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
                .body(TradingSourceArtifactResponse.from(result.artifact, clock.instant()))
        } catch (failure: TradingFailure) { throw failure }
          catch (_: IOException) { throw TradingFailure("REQUEST_INVALID", 400) }
          catch (_: IllegalArgumentException) { throw TradingFailure("REQUEST_INVALID", 400) }
          catch (_: IllegalStateException) { throw TradingFailure("REQUEST_INVALID", 400) }
          catch (_: Exception) { throw TradingFailure("TEMPORARY_DEPENDENCY_FAILURE", 503) }
    }

    private fun readMetadata(part: StreamingMultipartReader.Part): TradingUploadMetadata {
        if (part.name != "metadata" || part.contentType?.substringBefore(';') != "application/json")
            throw TradingFailure("REQUEST_INVALID", 400)
        val bytes = part.body.use { it.readNBytes(8_193) }
        if (bytes.size > 8_192) throw TradingFailure("REQUEST_INVALID", 400)
        val json = try { mapper.readTree(bytes.toString(Charsets.UTF_8)) }
            catch (_: Exception) { throw TradingFailure("REQUEST_INVALID", 400) }
        return parseMetadata(json)
    }

    private fun parseMetadata(node: JsonNode): TradingUploadMetadata {
        if (!node.isObject) throw TradingFailure("REQUEST_INVALID", 400)
        val role = try { TradingArtifactRole.valueOf(node.path("role").asText()) }
            catch (_: Exception) { throw TradingFailure("REQUEST_INVALID", 400) }
        val filename = node.path("filename").asText()
        val contentType = node.path("contentType").asText()
        val size = node.path("byteSize").asLong()
        val sha = node.path("sha256").asText()
        val dialect = node.path("dialectCandidate").asText()
        if (node.size() != 6 || filename.isBlank() || size <= 0 || sha.isBlank() || dialect.isBlank())
            throw TradingFailure("REQUEST_INVALID", 400)
        return TradingUploadMetadata(role, filename, contentType, size, sha, dialect)
    }
}

@RestController
@RequestMapping("/trading-source-artifacts")
class TradingSourceArtifactController(private val imports: TradingImportUseCase) {
    @DeleteMapping("/{artifactId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun delete(@PathVariable artifactId: Long, @RequestHeader("Idempotency-Key") key: String) =
        TradingDeletionResponse.from(imports.deleteArtifact(TradingSourceArtifactId(artifactId), key))
}
