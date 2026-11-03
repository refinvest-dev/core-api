package com.refinvest.core.tradingreview.adapter.web

import com.refinvest.core.tradingreview.port.common.failure.TradingFailure
import com.refinvest.core.tradingreview.port.common.failure.TradingFailureKind
import com.refinvest.core.tradingreview.port.inbound.importsession.TradingImportSessionView
import com.refinvest.core.tradingreview.port.inbound.importsession.TradingSourceArtifactResult
import com.refinvest.core.tradingreview.port.inbound.importsession.create.CreateTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.inbound.importsession.create.CreateTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.inbound.importsession.delete.DeleteTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.inbound.importsession.delete.DeleteTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.inbound.importsession.deleteartifact.DeleteTradingSourceArtifactCommand
import com.refinvest.core.tradingreview.port.inbound.importsession.deleteartifact.DeleteTradingSourceArtifactUseCase
import com.refinvest.core.tradingreview.port.inbound.importsession.get.GetTradingImportSessionQuery
import com.refinvest.core.tradingreview.port.inbound.importsession.get.GetTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.inbound.importsession.list.ListTradingImportsQuery
import com.refinvest.core.tradingreview.port.inbound.importsession.list.ListTradingImportsUseCase
import com.refinvest.core.tradingreview.port.inbound.importsession.upload.TradingUploadMetadata
import com.refinvest.core.tradingreview.port.inbound.importsession.upload.UploadTradingImportArtifactCommand
import com.refinvest.core.tradingreview.port.inbound.importsession.upload.UploadTradingImportArtifactUseCase
import com.refinvest.core.tradingreview.port.inbound.normalization.retry.RetryTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.inbound.normalization.retry.RetryTradingImportSessionUseCase
import com.refinvest.core.tradingreview.port.inbound.normalization.validate.ValidateTradingImportSessionCommand
import com.refinvest.core.tradingreview.port.inbound.normalization.validate.ValidateTradingImportSessionUseCase

import com.refinvest.core.tradingreview.domain.*
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRetentionStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingArtifactRole
import com.refinvest.core.tradingreview.domain.valueobject.TradingBookId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportFailure
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportSessionId
import com.refinvest.core.tradingreview.domain.valueobject.TradingImportStatus
import com.refinvest.core.tradingreview.domain.valueobject.TradingPositionMode
import com.refinvest.core.tradingreview.domain.valueobject.TradingSourceArtifactId
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import java.io.IOException
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

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
        fun from(a: TradingSourceArtifactResult) = TradingSourceArtifactResponse(
            a.id.value.toString(), a.sessionId.value.toString(), a.role, a.filename, a.contentType,
            a.byteSize, a.sha256, a.dialectCandidate, null, null, "UPLOADED", a.retentionStatus,
            a.rawReparseAvailable, null, a.rawExpiresAt, a.uploadedAt, a.deletedAt)
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
    val failure: TradingImportFailureResponse?, val completedAt: Instant?,
    val qualitySummary: JsonNode? = null, val createdLedgerRevisionId: String? = null,
) {
    companion object {
        fun from(view: TradingImportSessionView, mapper: ObjectMapper): TradingImportSessionResponse {
            val session = view.session
            val uploaded = session.uploadedArtifactRoles
            val missing = session.missingArtifactRoles
            val published = view.published
            val qualitySummary = published?.let {
                val report = mapper.readValue(it.qualityReportJson, Map::class.java)
                    .entries.associate { entry -> entry.key.toString() to entry.value }
                val capabilities = mapper.readValue(it.capabilityJson, List::class.java)
                mapper.readTree(mapper.writeValueAsString(report + ("capability" to capabilities)))
            }
            return TradingImportSessionResponse(session.id.value.toString(), session.bookId.value.toString(),
                session.sourceTimezone, session.reviewTimezone, session.declaredPositionMode, session.status,
                view.artifacts.map { TradingSourceArtifactResponse.from(it) }, session.dataPolicyVersion,
                session.retentionPolicy, session.maskingPolicyVersion, session.evidenceRetention,
                session.deletionPolicyVersion, mapper.readTree(session.versionSetJson), session.createdAt,
                session.validationStartedAt,
                uploaded, missing, session.canValidate, session.failure?.let(TradingImportFailureResponse::from),
                published?.publishedAt ?: session.failure?.occurredAt,
                qualitySummary, published?.revisionId)
        }
    }
}

data class TradingImportFailureResponse(val code: String, val retryable: Boolean, val userAction: String,
                                        val occurredAt: Instant) {
    companion object {
        fun from(failure: TradingImportFailure) = TradingImportFailureResponse(
            failure.code, failure.retryable, failure.userAction, failure.occurredAt)
    }
}

@RestController
@RequestMapping("/trading-books/{tradingBookId}/imports")
class TradingBookImportController(
    private val createImport: CreateTradingImportSessionUseCase,
    private val getImport: GetTradingImportSessionUseCase,
    private val listImports: ListTradingImportsUseCase,
    private val mapper: ObjectMapper,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@PathVariable tradingBookId: Long, @RequestHeader("Idempotency-Key") key: String,
               @Valid @RequestBody request: CreateTradingImportSessionRequest): TradingImportSessionResponse {
        val session = createImport.execute(CreateTradingImportSessionCommand(TradingBookId(tradingBookId), request.sourceTimezone, request.reviewTimezone, request.declaredPositionMode, request.dataPolicyVersion, request.clientRequestReference, key))
        return TradingImportSessionResponse.from(getImport.execute(GetTradingImportSessionQuery(session.id)), mapper)
    }

    @GetMapping
    fun list(@PathVariable tradingBookId: Long, @RequestParam(defaultValue = "0") page: Int,
             @RequestParam(defaultValue = "20") size: Int): TradingPageResponse<TradingImportSessionResponse> {
        val result = listImports.execute(ListTradingImportsQuery(TradingBookId(tradingBookId), page, size))
        return TradingPageResponse(result.items.map { TradingImportSessionResponse.from(it, mapper) },
            result.page, result.size, result.total)
    }
}

@RestController
@RequestMapping("/trading-import-sessions")
class TradingImportSessionController(
    private val getImport: GetTradingImportSessionUseCase,
    private val validateImport: ValidateTradingImportSessionUseCase,
    private val retryImport: RetryTradingImportSessionUseCase,
    private val deleteImport: DeleteTradingImportSessionUseCase,
    private val uploadArtifact: UploadTradingImportArtifactUseCase,
    private val mapper: ObjectMapper,
) {
    @GetMapping("/{importSessionId}")
    fun get(@PathVariable importSessionId: Long) = TradingImportSessionResponse.from(
        getImport.execute(GetTradingImportSessionQuery(TradingImportSessionId(importSessionId))), mapper)

    @PostMapping("/{importSessionId}/validation")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun validate(@PathVariable importSessionId: Long, @RequestHeader("Idempotency-Key") key: String,
                 @RequestBody request: JsonNode): TradingImportSessionResponse {
        if (!request.isObject || request.size() > 1 ||
            (request.size() == 1 && !request.has("targetVersionSet")))
            throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
        validateImport.execute(ValidateTradingImportSessionCommand(TradingImportSessionId(importSessionId), request.path("targetVersionSet").takeIf { !it.isMissingNode && !it.isNull }?.toString(), key))
        return TradingImportSessionResponse.from(getImport.execute(GetTradingImportSessionQuery(TradingImportSessionId(importSessionId))), mapper)
    }

    @PostMapping("/{importSessionId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun retry(@PathVariable importSessionId: Long, @RequestHeader("Idempotency-Key") key: String):
        TradingImportSessionResponse {
        retryImport.execute(RetryTradingImportSessionCommand(TradingImportSessionId(importSessionId), key))
        return TradingImportSessionResponse.from(getImport.execute(GetTradingImportSessionQuery(TradingImportSessionId(importSessionId))), mapper)
    }

    @DeleteMapping("/{importSessionId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun delete(@PathVariable importSessionId: Long, @RequestHeader("Idempotency-Key") key: String) =
        TradingDeletionResponse.from(deleteImport.execute(DeleteTradingImportSessionCommand(TradingImportSessionId(importSessionId), key)))

    @PostMapping("/{importSessionId}/artifacts", consumes = ["multipart/form-data"])
    fun upload(@PathVariable importSessionId: Long, @RequestHeader("Idempotency-Key") key: String,
               request: HttpServletRequest): ResponseEntity<TradingSourceArtifactResponse> {
        try {
            val reader = StreamingMultipartReader(request.inputStream, request.contentType)
            val first = reader.nextPart() ?: throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
            var metadata: TradingUploadMetadata? = null
            val file = if (first.name == "metadata") {
                metadata = readMetadata(first)
                reader.nextPart() ?: throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
            } else first
            if (file.name != "file") throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
            val result = uploadArtifact.execute(UploadTradingImportArtifactCommand(TradingImportSessionId(importSessionId), file.body, key, metadata = {
                val parsed = metadata ?: (reader.nextPart() ?: throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST))
                    .let(::readMetadata)
                if (reader.nextPart() != null) throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
                if (file.contentType?.substringBefore(';') != parsed.contentType)
                    throw TradingFailure("UNSUPPORTED_MEDIA_TYPE", TradingFailureKind.UNSUPPORTED_MEDIA)
                parsed
            }))
            return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
                .body(TradingSourceArtifactResponse.from(result.artifact))
        } catch (failure: TradingFailure) { throw failure }
          catch (_: IOException) { throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST) }
          catch (_: IllegalArgumentException) { throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST) }
          catch (_: IllegalStateException) { throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST) }
          catch (_: Exception) { throw TradingFailure("TEMPORARY_DEPENDENCY_FAILURE", TradingFailureKind.DEPENDENCY_UNAVAILABLE) }
    }

    private fun readMetadata(part: StreamingMultipartReader.Part): TradingUploadMetadata {
        if (part.name != "metadata" || part.contentType?.substringBefore(';') != "application/json")
            throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
        val bytes = part.body.use { it.readNBytes(8_193) }
        if (bytes.size > 8_192) throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
        val json = try { mapper.readTree(bytes.toString(Charsets.UTF_8)) }
            catch (_: Exception) { throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST) }
        return parseMetadata(json)
    }

    private fun parseMetadata(node: JsonNode): TradingUploadMetadata {
        if (!node.isObject) throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
        val role = try { TradingArtifactRole.valueOf(node.path("role").asText()) }
            catch (_: Exception) { throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST) }
        val filename = node.path("filename").asText()
        val contentType = node.path("contentType").asText()
        val size = node.path("byteSize").asLong()
        val sha = node.path("sha256").asText()
        val dialect = node.path("dialectCandidate").asText()
        if (node.size() != 6 || filename.isBlank() || size <= 0 || sha.isBlank() || dialect.isBlank())
            throw TradingFailure("REQUEST_INVALID", TradingFailureKind.INVALID_REQUEST)
        return TradingUploadMetadata(role, filename, contentType, size, sha, dialect)
    }
}

@RestController
@RequestMapping("/trading-source-artifacts")
class TradingSourceArtifactController(
    private val deleteArtifact: DeleteTradingSourceArtifactUseCase,
) {
    @DeleteMapping("/{artifactId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun delete(@PathVariable artifactId: Long, @RequestHeader("Idempotency-Key") key: String) =
        TradingDeletionResponse.from(deleteArtifact.execute(DeleteTradingSourceArtifactCommand(TradingSourceArtifactId(artifactId), key)))
}
